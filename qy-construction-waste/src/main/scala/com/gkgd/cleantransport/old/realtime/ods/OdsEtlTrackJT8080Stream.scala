package com.gkgd.cleantransport.old.realtime.ods

import java.text.SimpleDateFormat
import java.util.Date

import com.alibaba.fastjson.serializer.SerializerFeature
import com.alibaba.fastjson.{JSON, JSONObject}
import com.gkgd.cleantransport.entity.dwd.DataBusBean
import com.gkgd.cleantransport.entity.ods.TblPosinfo
import com.gkgd.cleantransport.jt8080.{HexStringUtils, LocationInformationReport, MsgDecoderUtil, PackageData}
import com.gkgd.cleantransport.util.{Configuration, GeoUtil, KafkaSink, KafkaSource, MysqlUtil}
import net.sf.cglib.beans.BeanCopier
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.spark.SparkConf
import org.apache.spark.streaming.dstream.{DStream, InputDStream}
import org.apache.spark.streaming.{Seconds, StreamingContext}

import scala.collection.mutable.ListBuffer


object OdsEtlTrackJT8080Stream {
    def main(args: Array[String]): Unit = {
        val sparkConf: SparkConf = new SparkConf()
            .setAppName("ODS TRACK STREAM")
            .set("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
//            .setMaster("local[*]")
        val ssc = new StreamingContext(sparkConf, Seconds(5))

        val properties = Configuration.conf("config.properties")
        val topic = properties.getProperty("topic.ods.tracks")
        val groupId = "jt8080-0001"

        //获取jt8080数据
        val recordInputStream: InputDStream[ConsumerRecord[String, String]] = KafkaSource.getKafkaStream(topic, ssc, groupId)
        //将jt8080解析转换成数据总线
        val transportInputStream: DStream[DataBusBean] = recordInputStream.map { record =>
            val input: Array[Byte] = HexStringUtils.chars2Bytes(record.value().toCharArray)
            val packageData: PackageData = new MsgDecoderUtil().bytes2PackageData(input)
            val header: PackageData.MsgHeader = packageData.getMsgHeader
            val locationInformationReport: LocationInformationReport = LocationInformationReport.getEntity(packageData.getMsgBodyBytes)
            val tblPosinfo = new TblPosinfo(header.getTerminalPhone, locationInformationReport)
            val dataBusBean = new DataBusBean
            val copier: BeanCopier = BeanCopier.create(classOf[TblPosinfo], classOf[DataBusBean], false)
            copier.copy(tblPosinfo, dataBusBean, null)
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
                val devIdList: List[String] = dataBusBeanList.map(_.devid.trim).distinct
                val devs: String = devIdList.mkString("','")
                val sql =
                    s"""
                       |select terminal_id, vehicle_id, car_card_number, vehicle_model_id, vehicle_type_id,
                       |    vehicle_type_state, vehicle_state, if_new_energy, approved_tonnage, driver_id,
                       |    enterprise_id, audit_state, manage_state, dept_id
                       |from dim_cwp_d_vehicle_info
                       |where terminal_id in ('$devs')
            """.stripMargin
                val jsonObjList: List[JSONObject] = MysqlUtil.queryList(sql)
                val vehicleMap: Map[String, JSONObject] = jsonObjList.map(jsonObj => (jsonObj.getString("terminal_id"), jsonObj)).toMap

                val lst1 = new ListBuffer[DataBusBean]
                if (vehicleMap != null && vehicleMap.nonEmpty) {
                    for (dataBusBean <- dataBusBeanList) {
                        val vehicleObj: JSONObject = vehicleMap.getOrElse(dataBusBean.devid, null)
                        if (vehicleObj != null) {
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
                    val dataBusJsonString: String  = JSON.toJSONString(dataBusBean,SerializerFeature.WriteMapNullValue)
                    KafkaSink.send(properties.getProperty("dwd.data.etl"), dataBusJsonString)
                }
            }
        }

        ssc.start()
        ssc.awaitTermination()
    }
}