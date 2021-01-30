package com.gkgd.cleantransport.old.realtime.dws

import java.text.SimpleDateFormat
import java.util.Properties

import com.alibaba.fastjson.serializer.SerializerFeature
import com.alibaba.fastjson.{JSON, JSONObject}
import com.gkgd.cleantransport.entity.dwd.DataBusBean
import com.gkgd.cleantransport.entity.dws.AlarmBean
import com.gkgd.cleantransport.util.{AreaUtil, Configuration, DateUtil, KafkaSink, KafkaSource, MysqlUtil}
import net.sf.cglib.beans.BeanCopier
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.spark.sql.SparkSession
import org.apache.spark.streaming.dstream.InputDStream
import org.apache.spark.streaming.{Seconds, StreamingContext}

import scala.collection.mutable


/**
 * 违规时间作业
 */
object DwsAlarmIllegalTimeStream {
    def main(args: Array[String]): Unit = {
        val spark = SparkSession.builder()
            .master("local[*]")
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
        val groupId = "ods_track_stream-004"
        val alarmTimeCode: String = properties.getProperty("alarm.fence.time")    //违规时间代码

        //设置广播变量统计告警时间
        val updateAlarmTime = mutable.Map[String, String]()
        var AlarmTime = sc.broadcast(updateAlarmTime)
        //广播变量激活时间
        val updateActiveTime = mutable.Map[String, List[String]]()
        var ActiveTime = sc.broadcast(updateActiveTime)

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
        val IllegalTimeStream = dataBusStream.mapPartitions {
            dataBusBean => {
                val sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                //获取广播变量
                val almBD = AlarmTime.value
                val actBD = ActiveTime.value

                val almTime = mutable.Map[String, String]()
                val actTime = mutable.Map[String, List[String]]()
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
                    val sql =
                        s"""
                           |select t1.start_date, t1.end_date, t1.dept_id, t1.alarm_date, t2.coords
                           |from dim_cwp_boundary_condition_work_time t1
                           |     join dim_cwp_boundary_condition t2 on t1.condition_id=t2.id
                           |where t1.dept_id in($deptIds) and state=0
                           |""".stripMargin
                    //获取所有围栏信息
                    val jsonObject = MysqlUtil.queryList(sql)
                    //按区域存放围栏信息
                    val hashMap = new scala.collection.mutable.HashMap[Integer, List[JSONObject]]
                    for (json <- jsonObject) {
                        val deptId = json.getInteger("dept_id")
                        if (hashMap.contains(deptId)) {
                            val value = hashMap(deptId)
                            val values = value :+ json
                            hashMap += (deptId -> values)
                        } else {
                            hashMap += (deptId -> List(json))
                        }
                    }
                    //判断每辆车是否在违规时间作业
                    for (alarmBean <- alarmBeanList) {
                        val fences = hashMap.getOrElse(alarmBean.dept_id, null) //所有的围栏
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
                                    val key = alarmBean.vehicle_id.toString
                                    val startDate: String = fence.getString("start_date")
                                    val endDate: String = fence.getString("end_date")
                                    val totalTime = fence.getInteger("alarm_date")
                                    val time: String = alarmBean.getTime
                                    val inTimeRange = DateUtil.isInTimeRange(time, startDate, endDate)
                                    if (!inTimeRange && alarmBean.speed.toDouble > 0) { //如果不在规定时间内，告警
//                                        alarmBean.setFence_id(fence.getString("condition_id"))
//                                        alarmBean.setFence_name(fence.getString("name"))
//                                        alarmBean.setFence_area_id(fence.getString("area_id"))
//                                        alarmBean.setFence_flag(fence.getInteger("fence_flag"))
                                        alarmBean.setIllegal_type_code(alarmTimeCode)
                                        alarmBean.setAlarm_start_time(alarmBean.getTime)
                                        alarmBean.setAlarm_start_lng(lng)
                                        alarmBean.setAlarm_start_lat(lat)
                                        //获取持续违规时间
                                        val isAlmTime = actBD.getOrElse(key, null)

                                        isAlmTime match {
                                            case null =>
                                                //第一次告警，将告警信息写入
                                                val vehicleAct = List[String](alarmBean.time, 1.toString) //1代表上次违规， 0代表上次不违规
                                                actTime += (key -> vehicleAct)
                                            case t: List[String] =>
                                                val state = t(1) //上次违规状态
                                                state match {
                                                    case "0" =>
                                                        //上次不违规，重新添加激活信息
                                                        val vehicleAct = List[String](alarmBean.time, 1.toString) //1代表上次违规， 0代表上次不违规
                                                        actTime += (key -> vehicleAct)
                                                    case "1" =>
                                                        //上次违规，判断时间是否达到激活时间
                                                        val diff = (sdf.parse(alarmBean.time).getTime - sdf.parse(t.head).getTime) / 1000
                                                        if (diff > totalTime * 60) {
                                                            //达到报警条件，查询两小时内是否有过报警
                                                            val isUp = almBD.getOrElse(key, null)
                                                            isUp match {
                                                                case null =>
                                                                    //没有告警过，写入告警时间，并写入kafka
                                                                    almTime += (key -> alarmBean.time)
                                                                    val alarmJsonString: String = JSON.toJSONString(alarmBean, SerializerFeature.WriteMapNullValue)
                                                                    KafkaSink.send(properties.getProperty("topic.dwd.data.alarm"), alarmJsonString)

                                                                case t: String =>
                                                                    //告警过，查看是否在两小时内告警过
                                                                    val diff = (sdf.parse(alarmBean.time).getTime - sdf.parse(t).getTime) / 1000
                                                                    if (diff > 7200) {
                                                                        //达到再次告警条件，更新告警时间，并写入kafka
                                                                        almTime += (key -> alarmBean.time)
                                                                        val alarmJsonString: String = JSON.toJSONString(alarmBean, SerializerFeature.WriteMapNullValue)
                                                                        KafkaSink.send(properties.getProperty("topic.dwd.data.alarm"), alarmJsonString)

                                                                    }
                                                            }
                                                        }
                                                }
                                        }
                                    } else {
                                        //修改告警状态
                                        val vehicleAct = List[String](alarmBean.time, 0.toString) //1代表上次违规， 0代表上次不违规
                                        actTime += (key -> vehicleAct)
                                    }
                                }
                            }
                        }
                    }
                }
                List((actTime, almTime)).toIterator
            }
        }

        //更新广播变量
        IllegalTimeStream.foreachRDD(
            rdd => {
                val ret = rdd.collect()
                val v1 = ret.head._1     //激活时间
                val v2 = ret.head._2    //告警时间

                //清除广播变量
                updateAlarmTime ++= v2
                updateActiveTime ++= v1

                AlarmTime.unpersist(true)
                ActiveTime.unpersist(true)

                AlarmTime = sc.broadcast(updateAlarmTime)
                ActiveTime = sc.broadcast(updateActiveTime)
            }
        )

        ssc.start()
        ssc.awaitTermination()
    }
}
