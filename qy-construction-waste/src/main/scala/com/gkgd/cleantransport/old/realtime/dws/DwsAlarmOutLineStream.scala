package com.gkgd.cleantransport.old.realtime.dws

import java.lang
import java.text.SimpleDateFormat
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Properties

import com.alibaba.fastjson.JSON
import com.alibaba.fastjson.serializer.SerializerFeature
import com.gkgd.cleantransport.entity.dwd.DataBusBean
import com.gkgd.cleantransport.entity.dws.AlarmBean
import com.gkgd.cleantransport.util.{Configuration, GeoUtil, KafkaSink, KafkaSource, MysqlUtil, RedisUtil}
import net.sf.cglib.beans.BeanCopier
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.spark.SparkConf
import org.apache.spark.streaming.dstream.{DStream, InputDStream}
import org.apache.spark.streaming.{Seconds, StreamingContext}
import org.gavaghan.geodesy.Ellipsoid
import redis.clients.jedis.Jedis

/**
 * 驶离路线告警,两小时告警一次
 */
object DwsAlarmOutLineStream {
    def main(args: Array[String]): Unit = {
        val sparkConf: SparkConf = new SparkConf()
            .setAppName("ODS TRACK STREAM4")
            .set("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
            .setMaster("local[*]")
        val ssc = new StreamingContext(sparkConf, Seconds(5))

        val properties: Properties = Configuration.conf("config.properties")
        val topic = properties.getProperty("topic.dwd.data.bus")
        val groupId = "ods_track_stream-002"

        val alarmOutLine: String = properties.getProperty("alarm.out.line")

        //获取数据
        val recordInputStream: InputDStream[ConsumerRecord[String, String]] = KafkaSource.getKafkaStream(topic, ssc, groupId)

        val dataBusStream: DStream[DataBusBean] = recordInputStream.map { record =>
            val jsonString: String = record.value()
            val dataBusBean: DataBusBean = JSON.parseObject(jsonString, classOf[DataBusBean])
            dataBusBean
        }.filter { record =>
            record.getAudit_state.equals("1") && record.getManage_state.equals("1")
        }

        //关联处置证
        val joinVehicleRegisterCardStream = dataBusStream.mapPartitions {
            dataBusBean => {
                val sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                val jedis: Jedis = RedisUtil.getJedisClient
                val dataBusBeanList = dataBusBean.toList
                if (dataBusBeanList.nonEmpty) {
                    //每分区的操作(deptId-vehicleId)
                    val deptIdVehicleIdList = dataBusBeanList.filter(_.vehicle_id != null).map {
                        data => data.dept_id + "-" + data.vehicle_id
                    }.distinct
                    val deptIdVehicleIds = deptIdVehicleIdList.mkString("','")

                    val sql =
                        s"""
                           |select a.vehicle_id, a.coords, a.state, a.dept_id, b.lng as Blng, b.lat as Blat, c.lng as Dlng, c.lat as Dlat
                           |from ods_cwp_vehicle_register_card a
                           |    left join dim_cwp_d_build_site_info b on a.build_site_id=b.build_site_id
                           |    left join dim_cwp_d_disposal_site_info c on a.disposal_site_id=c.disposal_site_id
                           |where CONCAT_WS("-",a.dept_id,a.vehicle_id) in ('$deptIdVehicleIds')
                           |    and a.state=0
                        """.stripMargin
                    //获取所有车辆的处置证
                    val jsonObjList = MysqlUtil.queryList(sql)
                    println(jsonObjList.head.keySet())
                    //一辆车可以有多张双向登记卡，一张双向登记卡有多条路线
                    val hashMap = new scala.collection.mutable.HashMap[String, String]
                    for (json <- jsonObjList) {
                        val deptId = json.getInteger("dept_id")
                        val vehicleId = json.getInteger("vehicle_id")
                        val coords = json.getString("coords")
                        val buildLngLat = json.getDouble("Blng") + "," +json.getDouble("Blat")
                        val disposalLngLat = json.getDouble("Dlng") + "," +json.getDouble("Dlat")

                        val key = deptId + "-" + vehicleId
                        //将处置证信息写入hashMap
                        val value = buildLngLat + "|" + coords + "|" + disposalLngLat
                        if (hashMap.contains(key)) {
                            var lines: String = hashMap(key)
                            lines = lines + "&" + value
                            hashMap += (key -> lines)
                        } else {
                            hashMap += (key -> value)
                        }
                    }

                    //记录有处置证车
                    for (dataBusBean <- dataBusBeanList) {
                        val now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))  //当前时间
                        val key = dataBusBean.dept_id + ":" + dataBusBean.vehicle_id      //redis中保存的key
                        val time = jedis.hget(key, "time")     //上次进入时间
                        val coords = jedis.hget(key, "coords")   //是否有处置证记录
                        val status = jedis.hget(key, "status")   //上一时刻的状态：1工地内，0其他

                        val vehicleLng = dataBusBean.lng
                        val vehicleLat = dataBusBean.lat
                        val lines: String = hashMap.getOrElse(dataBusBean.dept_id + "-" + dataBusBean.vehicle_id, null)
                        if (lines != null && coords == null) {      //有处置证且没有路线才记录
                            val linesInfo = lines.split("&")
                            for(line <- linesInfo){
                                val buildLngLat = line.split("\\|").head
                                val buildLng = buildLngLat.split(",").head.toDouble
                                val buildLat = buildLngLat.split(",")(1).toDouble

                                val dis = GeoUtil.getDistanceMeter(vehicleLng, vehicleLat, buildLng, buildLat, Ellipsoid.Sphere)
                                if(dis < 500 && dataBusBean.speed.toInt < 15) {
                                    //说明此时在工地里面，需要是否连续3分钟且速度小于15km/h判断是否进入工地
                                    status match {
                                        case "1" =>    //上一次在工地中，判断时间
                                            val dif = (sdf.parse(now).getTime - sdf.parse(time).getTime) / 1000
                                            if(dif > 180) {  //在工地作业,将信息写入redis
                                                jedis.hset(key, "coords", line.split("\\|")(1))   //将处置线路记录
                                                jedis.hset(key, "disposal", line.split("\\|")(2))   //记录消纳场信息
                                                dataBusBean.register_card_state = 1
                                            }
                                        case _ =>    //进入工地范围
                                            jedis.hset(key, "time", now)    //记录时间
                                            jedis.hset(key, "status", 1.toString)   //记录状态
                                    }
                                }else {
                                    jedis.hset(key, "status", 0.toString)
                                }
                            }
                        }
                    }
                }
                jedis.close()
                dataBusBeanList.toIterator
            }
        }

        //逻辑判断
        joinVehicleRegisterCardStream.foreachRDD {rdd => {
            rdd.foreachPartition {
                jsonObjItr => {
                    val jedis: Jedis = RedisUtil.getJedisClient
                    val list: List[DataBusBean] = jsonObjItr.toList
                    if(list != null && list.nonEmpty) {
                        for (dataBusBean <- list) {
                            //获取状态后端
                            val key = dataBusBean.dept_id + ":" + dataBusBean.vehicle_id
                            val registerCardState = dataBusBean.register_card_state
                            if(registerCardState == 1) {
                                val lng = dataBusBean.lng
                                val lat = dataBusBean.lat
                                val disposal = jedis.hget(key, "disposal")
                                val DLng = disposal.split(",").head.toDouble
                                val DLat = disposal.split(",")(1).toDouble
                                if(GeoUtil.getDistanceMeter(DLng, DLat, lng, lat, Ellipsoid.Sphere) < 500){
                                    //进入消纳场，清除处置证线路
                                    jedis.hdel(key, "coords")
                                }
                                val coords = jedis.hget(key, "coords")
                                if(coords != null){
                                    //计算是否驶离路线
                                    val outLine = GeoUtil.outLine(lng, lat, coords)
                                    val alarmState: String = jedis.hget(key, alarmOutLine)     //驶离路线信息
                                    if(outLine) {
                                        //当前驶离路线
                                        alarmState match {
                                            case null =>
                                                val alarmBean = new AlarmBean
                                                val copier: BeanCopier = BeanCopier.create(classOf[DataBusBean], classOf[AlarmBean], false)
                                                copier.copy(dataBusBean, alarmBean, null)
                                                alarmBean.setIllegal_type_code(alarmOutLine)
                                                alarmBean.setAlarm_start_time(alarmBean.getTime)
                                                alarmBean.setAlarm_start_lat(alarmBean.getLng)
                                                alarmBean.setAlarm_start_lat(alarmBean.getLat)
                                                jedis.hset(key, alarmOutLine, JSON.toJSONString(alarmBean, SerializerFeature.WriteMapNullValue))
                                            case _ =>
                                                val alarmBeanState: AlarmBean = JSON.parseObject(alarmState, classOf[AlarmBean])

                                                val simpleDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                                                val intoTime: Long = simpleDateFormat.parse(dataBusBean.getTime).getTime
                                                //状态后端的时间
                                                val intoTimeState = simpleDateFormat.parse(alarmBeanState.getAlarm_start_time).getTime
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
                                        }
                                    }else {
                                        if(alarmState != null) {
                                            jedis.hdel(key, alarmOutLine)    //删除告警信息
                                        }
                                    }
                                }
                            }
                        }
                    }
                    jedis.close()
                }
            }
        }}

        ssc.start()
        ssc.awaitTermination()
    }
}
