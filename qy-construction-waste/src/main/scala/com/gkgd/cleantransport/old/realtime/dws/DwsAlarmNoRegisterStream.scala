package com.gkgd.cleantransport.old.realtime.dws

import java.util.Properties

import com.alibaba.fastjson.JSON
import com.alibaba.fastjson.serializer.SerializerFeature
import com.gkgd.cleantransport.entity.dwd.DataBusBean
import com.gkgd.cleantransport.entity.dws.AlarmBean
import com.gkgd.cleantransport.util.{Configuration, KafkaSink, KafkaSource, RedisUtil}
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
        //      .setMaster("local[2]")
        val ssc = new StreamingContext(sparkConf, Seconds(3))

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

        dataBusStream.foreachRDD { rdd =>
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
