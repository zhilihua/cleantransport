package com.gkgd.cleantransport.old.realtime.dws

import java.text.SimpleDateFormat
import java.util.Properties

import com.alibaba.fastjson.serializer.SerializerFeature
import com.alibaba.fastjson.{JSON, JSONObject}
import com.gkgd.cleantransport.entity.dwd.DataBusBean
import com.gkgd.cleantransport.entity.dws.AlarmBean
import com.gkgd.cleantransport.util.{AreaUtil, Configuration, KafkaSink, KafkaSource, MysqlUtil}
import net.sf.cglib.beans.BeanCopier
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.sql.SparkSession
import org.apache.spark.streaming.dstream.InputDStream
import org.apache.spark.streaming.{Seconds, StreamingContext}

import scala.collection.mutable

/**
 * 闯入禁区告警
 */
@deprecated
object DwsAlarmPenaltyStream {
    def main(args: Array[String]): Unit = {
        val spark = SparkSession.builder()
//            .master("local[*]")
            .config("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
            //.config("spark.locality.wait", "6")     //调节等待时间，使shuffle尽可能发生在本节点上
            .config("spark.shuffle.file.buffer", "64")   //调节map端shuffle的缓存大小，减少溢写磁盘
            .config("spark.shuffle.io.retryWait", "15s")    //调节reduce端拉取数据等待间隔
            .config("spark.shuffle.sort.bypassMergeThreshold", "3000")  //调节SortShuffle排序操作阈值
            .config("spark.storage.memoryFraction", "0.4")   //降低cache操作的内存占比
            .getOrCreate()
        val sc = spark.sparkContext

        val ssc = new StreamingContext(sc, Seconds(5))
        val properties: Properties = Configuration.conf("config.properties")
        val topic = properties.getProperty("topic.dwd.data.bus")
        val groupId = "ods_track_stream-006"
        val alarmInnerCode: String = properties.getProperty("alarm.fence.inner")    //违规时间代码

        //设置广播变量
        val updateBD = mutable.Map[String, InnerState]()     // 广播变量状态临时存放
        var states = sc.broadcast(updateBD)    //广播变量

        //获取数据
        val recordInputStream: InputDStream[ConsumerRecord[String, String]] = KafkaSource.getKafkaStream(topic, ssc, groupId)

        val dataBusStream = recordInputStream.map { record =>
            val jsonString: String = record.value()
            val dataBusBean: DataBusBean = JSON.parseObject(jsonString, classOf[DataBusBean])
            dataBusBean
        }.filter{ record =>
            record.getAudit_state.equals("1") && record.getManage_state.equals("1")
        }

        //是否进入围栏
        val PenaltyInStream = dataBusStream.mapPartitions {
            dataBusBean => {
                val sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                //获取广播变量
                val stateBD = states.value
                val retState = mutable.Map[String, InnerState]() //返回的状态

                val alarmBeans = dataBusBean.map { x =>
                    val alarmBean = new AlarmBean
                    val copier = BeanCopier.create(classOf[DataBusBean], classOf[AlarmBean], false)
                    copier.copy(x, alarmBean, null)
                    alarmBean
                }

                if (alarmBeans.nonEmpty) {
                    val alarmBeanList = alarmBeans.toList
                    //每分区的操作(deptId-vehicelId)
                    val deptIdList: List[Integer] = alarmBeanList.filter(_.dept_id != null).map { data =>
                        data.dept_id
                    }.distinct
                    val deptIds: String = deptIdList.mkString(",")
                    val InnerSql =
                        s"""
                           |select t1.dept_id, t1.alarm_date, t2.coords, t1.condition_id
                           |from dim_cwp_boundary_condition_penalty_fence t1
                           |     join dim_cwp_boundary_condition t2 on t1.condition_id=t2.id
                           |where t1.dept_id in($deptIds) and state=0
                           |""".stripMargin
                    //获取所有围栏信息
                    val jsonObjectInner = MysqlUtil.queryList(InnerSql)
                    //按区域存放围栏信息
                    val hashMapInner = new scala.collection.mutable.HashMap[Integer, List[JSONObject]]
                    for (json <- jsonObjectInner) {
                        val deptId = json.getInteger("dept_id")
                        if (hashMapInner.contains(deptId)) {
                            val value = hashMapInner(deptId)
                            val values = value :+ json
                            hashMapInner += (deptId -> values)
                        } else {
                            hashMapInner += (deptId -> List(json))
                        }
                    }
                    //判断每辆车是否闯入禁区作业
                    for (alarmBean <- alarmBeanList) {
                        val fences = hashMapInner.getOrElse(alarmBean.dept_id, null) //所有的围栏
                        if (fences != null) {
                            val lng = alarmBean.lng
                            val lat = alarmBean.lat
                            for (fence <- fences) {
                                val fenceCoords = fence.getString("coords")
                                var inArea = true //是否在围栏中
                                if (lng != null && lat != null && fenceCoords != null) {
                                    inArea = AreaUtil.inArea2(lng, lat, fenceCoords)
                                }

                                if (inArea) {
                                    val key = alarmBean.vehicle_id.toString + "-" +fence.getInteger("condition_id")
                                    val totalTime = fence.getInteger("alarm_date")

                                    // 基于上次判断的结果，进行状态的更新
                                    val totalAlarm = stateBD.getOrElse(key, InnerState(null, null))

                                    if (alarmBean.speed.toDouble > 0) { //如果不在规定时间内，告警
                                        alarmBean.setIllegal_type_code(alarmInnerCode)
                                        alarmBean.setAlarm_start_time(alarmBean.getTime)
                                        alarmBean.setAlarm_start_lng(lng)
                                        alarmBean.setAlarm_start_lat(lat)
                                        //获取持续违规时间
                                        val isAlmTime = totalAlarm.InnerActiveState

                                        isAlmTime match {
                                            case null =>
                                                //第一次告警，将告警信息写入
                                                val vehicleAct = List[String](alarmBean.time, 1.toString) //1代表违规， 0代表不违规
                                                val temp = InnerState(vehicleAct, totalAlarm.InnerAlarmState)
                                                retState += (key -> temp)
                                            case t: List[String] =>
                                                val state = t(1) //上次违规状态
                                                state match {
                                                    case "0" =>
                                                        //上次不违规，重新添加激活信息
                                                        val vehicleAct = List[String](alarmBean.time, 1.toString) //1代表违规， 0代表不违规
                                                        val temp = InnerState(vehicleAct, totalAlarm.InnerAlarmState)
                                                        retState += (key -> temp)
                                                    case "1" =>
                                                        //上次违规，判断时间是否达到激活时间
                                                        val diff = (sdf.parse(alarmBean.time).getTime - sdf.parse(t.head).getTime) / 1000
                                                        if (diff > totalTime * 60) {
                                                            //达到报警条件，查询两小时内是否有过报警
                                                            val isUp = totalAlarm.InnerAlarmState
                                                            isUp match {
                                                                case null =>
                                                                    //没有告警过，写入告警时间，并写入kafka
                                                                    val temp = InnerState(totalAlarm.InnerActiveState, alarmBean.time)
                                                                    retState += (key -> temp)
                                                                    // 添加告警开始时间
                                                                    alarmBean.setAlarm_start_time(t.head)
                                                                    val alarmJsonString: String = JSON.toJSONString(alarmBean, SerializerFeature.WriteMapNullValue)
                                                                    KafkaSink.send(properties.getProperty("topic.dwd.data.alarm"), alarmJsonString)

                                                                case t_alarm: String =>
                                                                    //告警过，查看是否在两小时内告警过
                                                                    val diff = (sdf.parse(alarmBean.time).getTime - sdf.parse(t_alarm).getTime) / 1000
                                                                    if (diff > 7200) {
                                                                        //达到再次告警条件，更新告警时间，并写入kafka
                                                                        val temp = InnerState(totalAlarm.InnerActiveState, alarmBean.time)
                                                                        retState += (key -> temp)
                                                                        // 添加告警开始时间
                                                                        alarmBean.setAlarm_start_time(t.head)
                                                                        val alarmJsonString: String = JSON.toJSONString(alarmBean, SerializerFeature.WriteMapNullValue)
                                                                        KafkaSink.send(properties.getProperty("topic.dwd.data.alarm"), alarmJsonString)

                                                                    }
                                                            }
                                                        }
                                                }
                                        }
                                    } else {
                                        //修改告警状态
                                        val vehicleAct = List[String](alarmBean.time, 0.toString) //1代表违规， 0代表不违规
                                        val temp = InnerState(vehicleAct, totalAlarm.InnerAlarmState)
                                        retState += (key -> temp)
                                    }
                                }
                            }
                        }
                    }
                }

                retState.toIterator
            }
        }

        //更新广播变量
        PenaltyInStream.foreachRDD(
            rdd => {
                val finalMap = rdd.collect().toMap
                updateBD ++= finalMap

                //清除广播变量状态，并更新广播变量
                states.unpersist(true)
                states = sc.broadcast(updateBD)
            }
        )

        //启动任务流
        ssc.start()
        ssc.awaitTermination()
    }

    case class InnerState(
                             InnerActiveState: List[String],                 //  闯入禁区激活时间状态
                             InnerAlarmState: String
                         )
}
