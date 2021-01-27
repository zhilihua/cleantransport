package com.gkgd.cleantransport.old.realtime.dws

import java.util.Properties

import com.alibaba.fastjson.JSON
import com.alibaba.fastjson.serializer.SerializerFeature
import com.gkgd.cleantransport.entity.dwd.DataBusBean
import com.gkgd.cleantransport.entity.dws.AlarmBean
import com.gkgd.cleantransport.util.{Configuration, KafkaSink, KafkaSource, MysqlUtil, RedisUtil}
import net.sf.cglib.beans.BeanCopier
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.spark.SparkConf
import org.apache.spark.streaming.dstream.{DStream, InputDStream}
import org.apache.spark.streaming.{Seconds, StreamingContext}

/**
 * 当车辆在线且有速度的时候，判断当前车辆是否有核准手续（处置核准证），一天告警一次
 */
object DwsAlarmNoRegisterStream {
    def main(args: Array[String]): Unit = {
        val sparkConf: SparkConf = new SparkConf()
            .setAppName("ODS TRACK STREAM3")
            .set("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
//            .setMaster("local[2]")
        val ssc = new StreamingContext(sparkConf, Seconds(5))

        val properties: Properties = Configuration.conf("config.properties")
        val topic = properties.getProperty("topic.dwd.data.bus")
        val groupId = "ods_track_stream-001"

        val alarmNoCard: String = properties.getProperty("alarm.register.card")

        //获取数据
        val recordInputStream: InputDStream[ConsumerRecord[String, String]] = KafkaSource.getKafkaStream(topic, ssc, groupId)

        val dataBusStream: DStream[DataBusBean] = recordInputStream.map { record =>
            val jsonString: String = record.value()
            val dataBusBean: DataBusBean = JSON.parseObject(jsonString, classOf[DataBusBean])
            dataBusBean
        }.filter { record =>
            record.getAudit_state.equals("1") && record.getManage_state.equals("1")
        }

        //关联车辆处置证
        val joinVehicleRegisterCard = dataBusStream.mapPartitions {
            dataBusBean => {
                val dataBusBeanList = dataBusBean.toList
                if (dataBusBeanList.nonEmpty) {
                    //每分区的操作（deptId-vehicleId）
                    val deptIdVehicleIdList = dataBusBeanList.filter(_.vehicle_id != null).map {
                        data => data.dept_id + "-" + data.vehicle_id
                    }.distinct
                    val deptIdVehicleIds = deptIdVehicleIdList.mkString("','")
                    val sql =
                        s"""
                           |select vehicle_id, state, dept_id
                           |from ods_cwp_vehicle_register_card
                           |where CONCAT_WS("-",dept_id,vehicle_id) in ('$deptIdVehicleIds')
                           |    and state=0
                        """.stripMargin
                    //从数据库中查询是否有该车的处置证
                    val jsonObjList = MysqlUtil.queryList(sql)
                    //一辆车可以有多张双向登记卡，一张双向登记卡有多条路线
                    val hashMap = new scala.collection.mutable.HashMap[String, String]
                    for (json <- jsonObjList) {
                        val deptId = json.getInteger("dept_id")
                        val vehicleId = json.getInteger("vehicle_id")

                        val key = deptId + "-" + vehicleId
                        if (hashMap.contains(key)) {
                            //什么都不做
                        } else {
                            hashMap += (key -> "1")
                        }
                    }
                    //查询该车是否有查询到的处置证
                    for (dataBusBean <- dataBusBeanList) {
                        val lines = hashMap.getOrElse(dataBusBean.dept_id + "-" + dataBusBean.vehicle_id, null)
                        if (lines != null) {
                            dataBusBean.register_card_state = 1
                        }
                    }
                }
                dataBusBeanList.toIterator
            }
        }

        joinVehicleRegisterCard.foreachRDD { rdd =>
            rdd.foreachPartition { jsonObjItr =>
                val jedis = RedisUtil.getJedisClient
                val list: List[DataBusBean] = jsonObjItr.toList
                if (list != null && list.nonEmpty) {
                    for (dataBusBean <- list) {
                        val registerCardState: Integer = dataBusBean.getRegister_card_state
                        if (registerCardState == 0) {
                            val key = "alarm:time"
                            val filed = dataBusBean.dept_id + ":" + dataBusBean.vehicle_id + ":" + alarmNoCard
                            val alarmTimeState: String = jedis.hget(key, filed)
                            if (alarmTimeState == null) {
                                val alarmBean = new AlarmBean
                                val copier: BeanCopier = BeanCopier.create(classOf[DataBusBean], classOf[AlarmBean], false)
                                copier.copy(dataBusBean, alarmBean, null)
                                alarmBean.setIllegal_type_code(alarmNoCard)
                                alarmBean.setAlarm_start_time(alarmBean.getTime)
                                alarmBean.setAlarm_start_lng(alarmBean.getLng)
                                alarmBean.setAlarm_start_lat(alarmBean.getLat)

                                jedis.hset(key, filed, dataBusBean.getTime)
                                KafkaSink.send(properties.getProperty("topic.dwd.data.alarm"), JSON.toJSONString(alarmBean, SerializerFeature.WriteMapNullValue))
                            } else {
                                val alarmHistoryDay: String = alarmTimeState.split(" ")(0)
                                val alarmDay = dataBusBean.getTime.split(" ")(0)
                                if (!alarmHistoryDay.equals(alarmDay)) {
                                    val alarmBean = new AlarmBean
                                    val copier: BeanCopier = BeanCopier.create(classOf[DataBusBean], classOf[AlarmBean], false)
                                    copier.copy(dataBusBean, alarmBean, null)
                                    alarmBean.setIllegal_type_code(alarmNoCard)
                                    alarmBean.setAlarm_start_time(alarmBean.getTime)
                                    alarmBean.setAlarm_start_lng(alarmBean.getLng)
                                    alarmBean.setAlarm_start_lat(alarmBean.getLat)

                                    jedis.hset(key, filed, dataBusBean.getTime)
                                    KafkaSink.send(properties.getProperty("topic.dwd.data.alarm"), JSON.toJSONString(alarmBean, SerializerFeature.WriteMapNullValue))
                                }
                            }
                        }
                    }
                }
                jedis.close()
            }
        }
        ssc.start()
        ssc.awaitTermination()
    }
}
