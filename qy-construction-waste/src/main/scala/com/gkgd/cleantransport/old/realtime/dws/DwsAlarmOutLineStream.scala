package com.gkgd.cleantransport.old.realtime.dws

import java.lang
import java.text.SimpleDateFormat
import java.util.Properties

import com.alibaba.fastjson.JSON
import com.alibaba.fastjson.serializer.SerializerFeature
import com.gkgd.cleantransport.entity.dwd.DataBusBean
import com.gkgd.cleantransport.entity.dws.AlarmBean
import com.gkgd.cleantransport.util.{Configuration, GeoUtil, KafkaSink, KafkaSource, RedisUtil}
import net.sf.cglib.beans.BeanCopier
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.spark.SparkConf
import org.apache.spark.streaming.dstream.{DStream, InputDStream}
import org.apache.spark.streaming.{Seconds, StreamingContext}
import redis.clients.jedis.Jedis

/**
 * 驶离路线告警
 */
object DwsAlarmOutLineStream {
    def main(args: Array[String]): Unit = {
        val sparkConf: SparkConf = new SparkConf()
            .setAppName("ODS TRACK STREAM4")
            .set("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
        //      .setMaster("local[2]")
        val ssc = new StreamingContext(sparkConf, Seconds(3))

        val properties: Properties = Configuration.conf("config.properties")
        val topic = properties.getProperty("topic.dwd.data.bus")
        val groupId = "ods_track_stream-002"

        val alarmOutLine: String = properties.getProperty("alarm.out.line")

        //获取数据
        var recordInputStream: InputDStream[ConsumerRecord[String, String]] = KafkaSource.getKafkaStream(topic, ssc, groupId)

        val dataBusStream: DStream[DataBusBean] = recordInputStream.map { record =>
            val jsonString: String = record.value()
            val dataBusBean: DataBusBean = JSON.parseObject(jsonString, classOf[DataBusBean])
            dataBusBean
        }.filter { record =>
            record.getAudit_state.equals("1") && record.getManage_state.equals("1")
        }

        dataBusStream.foreachRDD { rdd =>
            rdd.foreachPartition { jsonObjItr =>
                val jedis: Jedis = RedisUtil.getJedisClient
                val list: List[DataBusBean] = jsonObjItr.toList
                if (list != null && list.nonEmpty) {
                    for (dataBusBean <- list) {
                        val registerCardState: Integer = dataBusBean.getRegister_card_state
                        if (registerCardState == 1) {
                            val lng: lang.Double = dataBusBean.getLng
                            val lat: lang.Double = dataBusBean.getLat
                            val coords: String = dataBusBean.getCoords
                            val outLine = GeoUtil.outLine(lng, lat, coords);
                            //获取状态后端
                            val key = dataBusBean.dept_id + ":" + dataBusBean.vehicle_id
                            val alarmState: String = jedis.hget(key, alarmOutLine)
                            //驶离路线并且没有记录
                            if (outLine && alarmState == null) {
                                val alarmBean = new AlarmBean
                                val copier: BeanCopier = BeanCopier.create(classOf[DataBusBean], classOf[AlarmBean], false)
                                copier.copy(dataBusBean, alarmBean, null)
                                alarmBean.setIllegal_type_code(alarmOutLine)
                                alarmBean.setAlarm_start_time(alarmBean.getTime)
                                alarmBean.setAlarm_start_lat(alarmBean.getLng)
                                alarmBean.setAlarm_start_lat(alarmBean.getLat)
                                jedis.hset(key, alarmOutLine, JSON.toJSONString(alarmBean, SerializerFeature.WriteMapNullValue))
                            }
                            //驶离路线并且有记录不做处理
                            if (outLine && alarmState != null) {
                            }
                            //没有驶离路线也没有记录不做处理
                            if (!outLine && alarmState == null) {
                            }
                            //没有驶离路线但有记录
                            if (!outLine && alarmState != null) {
                                val alarmBeanState: AlarmBean = JSON.parseObject(alarmState, classOf[AlarmBean])

                                val simpleDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                                val intoTime: Long = simpleDateFormat.parse(dataBusBean.getTime).getTime
                                //状态后端的时间
                                val intoTimeState = simpleDateFormat.parse(alarmBeanState.getAlarm_start_time).getTime()
                                val diff = intoTime - intoTimeState; //毫秒级差值
                                val minute = diff / 1000 / 60
                                if (minute > 3) {
                                    alarmBeanState.setAlarm_end_time(dataBusBean.getTime)
                                    alarmBeanState.setAlarm_end_lng(dataBusBean.getLng)
                                    alarmBeanState.setAlarm_end_lat(dataBusBean.getLat)

                                    val key = "alarm:time"
                                    val filed = dataBusBean.dept_id + ":" + dataBusBean.vehicle_id + ":" + alarmOutLine
                                    val alarmTimeState: String = jedis.hget(key, filed)
                                    if (alarmTimeState == null) {
                                        jedis.hset(key, filed, alarmBeanState.getAlarm_start_time)
                                        val alarmJsonString: String = JSON.toJSONString(alarmBeanState, SerializerFeature.WriteMapNullValue)
                                        KafkaSink.send(properties.getProperty("topic.dwd.data.alarm"), alarmJsonString)
                                    } else {
                                        val time: Long = simpleDateFormat.parse(alarmTimeState).getTime
                                        val diff = intoTimeState - time; //毫秒级差值
                                        val hours = diff / 1000 / 60 / 60
                                        if (hours >= 2) {
                                            jedis.hset(key, filed, alarmBeanState.getAlarm_start_time)
                                            val alarmJsonString: String = JSON.toJSONString(alarmBeanState, SerializerFeature.WriteMapNullValue)
                                            KafkaSink.send(properties.getProperty("topic.dwd.data.alarm"), alarmJsonString)
                                        }
                                    }
                                }
                                jedis.hdel(key, alarmOutLine)
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
