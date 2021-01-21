package com.gkgd.cleantransport.old.realtime.ods

import java.text.SimpleDateFormat
import java.util.Date

import com.alibaba.fastjson.serializer.SerializerFeature
import com.alibaba.fastjson.{JSON, JSONObject}
import com.gkgd.cleantransport.entity.dwd.DataBusBean
import com.gkgd.cleantransport.util.{Configuration, GeoUtil, KafkaSink, KafkaSource, MysqlUtil}
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.spark.SparkConf
import org.apache.spark.streaming.dstream.{DStream, InputDStream}
import org.apache.spark.streaming.{Seconds, StreamingContext}

import scala.collection.mutable.ListBuffer

object OdsEtlTrackRawStream {
    def main(args: Array[String]): Unit = {
        val sparkConf: SparkConf = new SparkConf()
            .setAppName("ODS TRACK STREAM")
            .set("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
            .setMaster("local[2]")
        val ssc = new StreamingContext(sparkConf, Seconds(5))

        val properties = Configuration.conf("config.properties")
        val topic = properties.getProperty("topic.ods.raw")
        val groupId = "raw-0001"

        //获取raw数据
        val recordInputStream: InputDStream[ConsumerRecord[String, String]] = KafkaSource.getKafkaStream(topic, ssc, groupId)
        //将raw解析转换成数据总线
        val transportInputStream: DStream[DataBusBean] = recordInputStream.map { record =>
            val jsonString: String = record.value()
            val dataBusBean: DataBusBean = JSON.parseObject(jsonString, classOf[DataBusBean])
            dataBusBean
        }

        //过滤违规数据
        val filterIllegalStream: DStream[DataBusBean] = transportInputStream.filter { dataBusBean =>
            val lng: Double = dataBusBean.getLng
            val lat: Double = dataBusBean.getLat
            val speed: Double = dataBusBean.getSpeed.toDouble
            val time: String = dataBusBean.getTime
            val gpsDate: String = time.split(" ")(0)
            val sdf = new SimpleDateFormat("yyyy-MM-dd")
            val nowDate: String = sdf.format(new Date)
            //TODO 参数配置文件获取
            if ((lat > 20 && lat < 40) && (lng > 100 && lng < 120) && (speed >= 0 && speed < 100) && nowDate == gpsDate) {
                val gps: Array[Double] = GeoUtil.wgs2bd(lat, lng)
                dataBusBean.setLat(gps(0))
                dataBusBean.setLng(gps(1))
                true
            }
            else {
                false
            }
        }

        //关联车辆表
        val joinVehicleStream: DStream[DataBusBean] = filterIllegalStream.mapPartitions { dataBusBean =>
            val dataBusBeanList: List[DataBusBean] = dataBusBean.toList
            if (dataBusBeanList.nonEmpty) {
                //每分区的操作
                val carCardNumberList: List[String] = dataBusBeanList.map(_.car_card_number.trim).distinct
                val carCardNumbers: String = carCardNumberList.mkString("','")
                val sql =
                    s"""
                       |select terminal_id, vehicle_id, car_card_number, vehicle_model_id, vehicle_type_id,
                       |    vehicle_type_state, vehicle_state, if_new_energy, approved_tonnage, driver_id,
                       |    enterprise_id, audit_state, manage_state, dept_id
                       |from dim_cwp_d_vehicle_info
                       |where car_card_number in ('$carCardNumbers')
            """.stripMargin
                val jsonObjList: List[JSONObject] = MysqlUtil.queryList(sql)
                val vehicleMap: Map[String, JSONObject] = jsonObjList.map(jsonObj => (jsonObj.getString("car_card_number"), jsonObj)).toMap

                val lst1 = new ListBuffer[DataBusBean]
                if (vehicleMap != null && vehicleMap.nonEmpty) {
                    for (dataBusBean <- dataBusBeanList) {
                        val vehicleObj: JSONObject = vehicleMap.getOrElse(dataBusBean.car_card_number, null)
                        if (vehicleObj != null) {
                            dataBusBean.devid = vehicleObj.getString("terminal_id")
                            dataBusBean.vehicle_id = vehicleObj.getInteger("vehicle_id")
                            dataBusBean.car_card_number = vehicleObj.getString("car_card_number")
                            dataBusBean.vehicle_model_id = vehicleObj.getString("vehicle_model_id")
                            dataBusBean.vehicle_type_id = vehicleObj.getString("vehicle_type_id")
                            dataBusBean.vehicle_type_state = vehicleObj.getString("vehicle_type_state")
                            dataBusBean.vehicle_state = vehicleObj.getString("vehicle_state")
                            dataBusBean.if_new_energy = vehicleObj.getInteger("if_new_energy")
                            dataBusBean.approved_tonnage = vehicleObj.getFloat("approved_tonnage")
                            dataBusBean.driver_id = vehicleObj.getInteger("driver_id")
                            dataBusBean.enterprise_id = vehicleObj.getString("enterprise_id")
                            dataBusBean.dept_id = vehicleObj.getInteger("dept_id")
                            dataBusBean.audit_state = vehicleObj.getString("audit_state")
                            dataBusBean.manage_state = vehicleObj.getString("manage_state")
                            lst1 += dataBusBean
                        }
                    }
                    lst1.toIterator
                } else {
                    lst1.toIterator
                }
            }
            else {
                dataBusBeanList.toIterator
            }
        }

        joinVehicleStream.foreachRDD { rdd =>
            rdd.foreachPartition { orderInfoItr =>
                val dataBusBeanList: List[DataBusBean] = orderInfoItr.toList
                for (dataBusBean <- dataBusBeanList) {
                    val dataBusJsonString: String = JSON.toJSONString(dataBusBean, SerializerFeature.WriteMapNullValue)
                    KafkaSink.send(properties.getProperty("dwd.data.etl"), dataBusJsonString)
                }
            }
        }

        ssc.start()
        ssc.awaitTermination()
    }
}
