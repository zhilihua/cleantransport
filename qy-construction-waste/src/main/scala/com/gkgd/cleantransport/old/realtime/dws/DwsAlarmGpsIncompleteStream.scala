package com.gkgd.cleantransport.old.realtime.dws

import java.util.Properties

import com.alibaba.fastjson.JSON
import com.alibaba.fastjson.serializer.SerializerFeature
import com.gkgd.cleantransport.entity.dwd.DataBusBean
import com.gkgd.cleantransport.entity.dws.AlarmBean
import com.gkgd.cleantransport.util.{Configuration, GeoUtil, KafkaSink, KafkaSource}
import net.sf.cglib.beans.BeanCopier
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.spark.sql.SparkSession
import org.apache.spark.streaming.dstream.{DStream, InputDStream}
import org.apache.spark.streaming.{Seconds, StreamingContext}
import org.gavaghan.geodesy.Ellipsoid

import scala.collection.mutable


/**
 * 轨迹不完整报警：车辆所有相邻轨迹点位置之间超过2公里以上,视为车辆当日违规一次 ，一天一辆车只统计1次
 */
object DwsAlarmGpsIncompleteStream {
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
        val groupId = "ods_track_stream-003"

        val alarmGpsIncomplete: String = properties.getProperty("alarm.gps.incomplete")
        //初始化广播变量
        val updateLastPoint = mutable.Map[String, AlarmBean]()
        var lastPointBD = sc.broadcast(updateLastPoint)
        //告警广播变量
        val updateAlarm = mutable.Map[String, String]()
        var alarmBD = sc.broadcast(updateAlarm)

        //获取数据
        val recordInputStream: InputDStream[ConsumerRecord[String, String]] = KafkaSource.getKafkaStream(topic, ssc, groupId)

        val dataBusStream: DStream[DataBusBean] = recordInputStream.map { record =>
            val jsonString: String = record.value()
            val dataBusBean: DataBusBean = JSON.parseObject(jsonString, classOf[DataBusBean])
            dataBusBean
        }.filter { record =>
            record.getAudit_state.equals("1") && record.getManage_state.equals("1")
        }

        //进行逻辑处理
        val dataBusRest = dataBusStream.mapPartitions {
            partRDD => {
                val ret = mutable.Map[String, AlarmBean]() //存储返回车辆点的结果
                val alm = mutable.Map[String, String]() //存储告警记录
                if (partRDD != null && partRDD.nonEmpty) {
                    for (dataBusBean <- partRDD) {
                        val key = dataBusBean.dept_id + ":" + dataBusBean.vehicle_id
                        //获取广播变量
                        val lastPoint = lastPointBD.value
                        val alarms = alarmBD.value

                        //获取上一个点的信息
                        val noGpsState = lastPoint.getOrElse(key, null)

                        noGpsState match {
                            case null =>
                                //第一个点，刚开机
                                val alarmBean = new AlarmBean
                                val copier: BeanCopier = BeanCopier.create(classOf[DataBusBean], classOf[AlarmBean], false)
                                copier.copy(dataBusBean, alarmBean, null)
                                alarmBean.setIllegal_type_code(alarmGpsIncomplete)
                                alarmBean.setAlarm_start_time(dataBusBean.getTime)
                                alarmBean.setAlarm_start_lng(dataBusBean.getLng)
                                alarmBean.setAlarm_start_lat(dataBusBean.getLat)
                                //将结果保持到缓存中
                                ret += (key -> alarmBean)

                            case point: AlarmBean =>
                                //循环计算,当两个点位置差大于2000时候报警
                                //解析数据
                                val diff = GeoUtil.getDistanceMeter(dataBusBean.lng, dataBusBean.lat, point.lng, point.lat, Ellipsoid.Sphere)
                                if (diff > 2000) {
                                    point.setAlarm_end_time(dataBusBean.getTime)
                                    point.setAlarm_end_lng(dataBusBean.getLng)
                                    point.setAlarm_end_lat(dataBusBean.getLat)
                                    val key = dataBusBean.dept_id + ":" + dataBusBean.vehicle_id
                                    val alarmTimeState = alarms.getOrElse(key, null)
                                    alarmTimeState match {
                                        case null =>
                                            println(point)
                                            //没有报警，将报警写入kafka
                                            alm += (key -> point.getAlarm_start_time)
                                            val alarmJsonString: String = JSON.toJSONString(point, SerializerFeature.WriteMapNullValue)
                                            KafkaSink.send(properties.getProperty("topic.dwd.data.alarm"), alarmJsonString)
                                            //更新该点缓存记录
                                            val alarmBean = new AlarmBean
                                            val copier: BeanCopier = BeanCopier.create(classOf[DataBusBean], classOf[AlarmBean], false)
                                            copier.copy(dataBusBean, alarmBean, null)
                                            alarmBean.setIllegal_type_code(alarmGpsIncomplete)
                                            alarmBean.setAlarm_start_time(dataBusBean.getTime)
                                            alarmBean.setAlarm_start_lng(dataBusBean.getLng)
                                            alarmBean.setAlarm_start_lat(dataBusBean.getLat)
                                            ret += (key -> alarmBean)
                                        case alarm: String =>
                                            //存在报警信息
                                            val Hou = alarm.split(" ").head
                                            val Qian = dataBusBean.time.split(" ").head
                                            if (!Hou.equals(Qian)) {
                                                println(point)
                                                //将报警写入Kafka
                                                alm += (key -> point.getAlarm_start_time)
                                                val alarmJsonString: String = JSON.toJSONString(point, SerializerFeature.WriteMapNullValue)
                                                KafkaSink.send(properties.getProperty("topic.dwd.data.alarm"), alarmJsonString)
                                            }
                                            //更新该点缓存记录
                                            val alarmBean = new AlarmBean
                                            val copier: BeanCopier = BeanCopier.create(classOf[DataBusBean], classOf[AlarmBean], false)
                                            copier.copy(dataBusBean, alarmBean, null)
                                            alarmBean.setIllegal_type_code(alarmGpsIncomplete)
                                            alarmBean.setAlarm_start_time(dataBusBean.getTime)
                                            alarmBean.setAlarm_start_lng(dataBusBean.getLng)
                                            alarmBean.setAlarm_start_lat(dataBusBean.getLat)
                                            ret += (key -> alarmBean)
                                    }
                                } else {
                                    //更新初始点
                                    val alarmBean = new AlarmBean
                                    val copier: BeanCopier = BeanCopier.create(classOf[DataBusBean], classOf[AlarmBean], false)
                                    copier.copy(dataBusBean, alarmBean, null)
                                    alarmBean.setIllegal_type_code(alarmGpsIncomplete)
                                    alarmBean.setAlarm_start_time(dataBusBean.getTime)
                                    alarmBean.setAlarm_start_lng(dataBusBean.getLng)
                                    alarmBean.setAlarm_start_lat(dataBusBean.getLat)
                                    ret += (key -> alarmBean)
                                }
                        }
                    }
                }
                List((ret, alm)).toIterator
            }
        }

        dataBusRest.foreachRDD {
            rdd => {
                //拉取数据
                val ret = rdd.collect()
                //更新广播变量
                updateLastPoint ++= ret.head._1
                updateAlarm ++= ret.head._2
                lastPointBD.unpersist(true)
                alarmBD.unpersist(true)
                lastPointBD = sc.broadcast(updateLastPoint)
                alarmBD = sc.broadcast(updateAlarm)
            }
        }

        ssc.start()
        ssc.awaitTermination()
    }
}
