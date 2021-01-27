package com.gkgd.cleantransport.old.realtime.dwd

import java.lang

import com.alibaba.fastjson.serializer.SerializerFeature
import com.alibaba.fastjson.{JSON, JSONObject}
import com.gkgd.cleantransport.entity.dwd.DataBusBean
import com.gkgd.cleantransport.util.{AreaUtil, Configuration, KafkaSink, KafkaSource, MysqlUtil}
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.spark.SparkConf
import org.apache.spark.streaming.dstream.{DStream, InputDStream}
import org.apache.spark.streaming.{Seconds, StreamingContext}

import scala.collection.mutable.ListBuffer

/**
 * @ModelName
 * @Description
 * @Date 2020/11/19 17:29
 * @Version V1.0.0
 * @TODO 参数从配置文件中获取、双向登记卡一辆车有多个双向登记、运输证一车一证、区域表加入dept_id  1,过滤违规数据 2,关联车辆表 2,过滤服务费到期  3,关联司机表  4,关联公司表  5,关联双向登记卡表  6,关联运输证表  7,关联区域表
 */
object DwdDataBusStream {
    def main(args: Array[String]): Unit = {
        val sparkConf: SparkConf = new SparkConf()
            .setAppName("ODS TRACK STREAM")
            .set("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
//            .setMaster("local[*]")
        val ssc = new StreamingContext(sparkConf, Seconds(5))


        val properties = Configuration.conf("config.properties")
        val topic = properties.getProperty("dwd.data.etl")
        val groupId = "dataBus-0001"
        var recordInputStream: InputDStream[ConsumerRecord[String, String]] = KafkaSource.getKafkaStream(topic, ssc, groupId)

        val transportInputStream: DStream[DataBusBean] = recordInputStream.map { record =>
            val jsonString: String = record.value()
            val dataBusBean: DataBusBean = JSON.parseObject(jsonString, classOf[DataBusBean])
            dataBusBean
        }

        //过滤掉服务费到期车辆
        val filterServiceFeeStream: DStream[DataBusBean] = transportInputStream.mapPartitions { dataBusBean =>
            val dataBusBeanList: List[DataBusBean] = dataBusBean.toList

            if (dataBusBeanList.nonEmpty) {
                //每分区的操作(deptId-vehicelId)
                val deptIdVhicleIdList: List[String] = dataBusBeanList.map { data =>
                    data.dept_id + "-" + data.vehicle_id
                }.distinct
                val deptIdVhicleIds: String = deptIdVhicleIdList.mkString("','")
                val sql =
                    s"""
                       |select vehicle_id, dept_id, state
                       |from ods_cwp_vehicle_service_fee
                       |where CONCAT_WS("-",dept_id,vehicle_id) in ('$deptIdVhicleIds')
                       |    and state=0
            """.stripMargin
                val jsonObjList: List[JSONObject] = MysqlUtil.queryList(sql)
                val vehicleMap: Map[String, JSONObject] = jsonObjList.map(jsonObj => (jsonObj.getString("dept_id") + "-" + jsonObj.getString("vehicle_id"), jsonObj)).toMap

                val lst1 = new ListBuffer[DataBusBean]
                if (vehicleMap != null && vehicleMap.nonEmpty) {
                    for (dataBusBean <- dataBusBeanList) {
                        val vehicleObj: JSONObject = vehicleMap.getOrElse(dataBusBean.dept_id + "-" + dataBusBean.vehicle_id, null)
                        if (vehicleObj != null) {
                            dataBusBean.service_state = 1
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

        //关联司机表
        val joinDriverStream: DStream[DataBusBean] = filterServiceFeeStream.mapPartitions { dataBusBean =>
            val dataBusBeanList: List[DataBusBean] = dataBusBean.toList
            if (dataBusBeanList.nonEmpty) {
                //每分区的操作(deptId-vehicelId)
                val deptIdDriverIdList: List[String] = dataBusBeanList.filter(_.driver_id != null).map { data =>
                    data.dept_id + "-" + data.driver_id
                }.distinct
                val deptIdDriverIds: String = deptIdDriverIdList.mkString("','")
                val sql =
                    s"""
                       |select driver_id, driver_card_number, driver_name, dept_id
                       |from dim_cwp_d_driver_info
                       |where CONCAT_WS("-",dept_id,driver_id) in ('$deptIdDriverIds')
            """.stripMargin
                val jsonObjList: List[JSONObject] = MysqlUtil.queryList(sql)
                val vehicleMap: Map[String, JSONObject] = jsonObjList.map(jsonObj => (jsonObj.getString("dept_id") + "-" + jsonObj.getString("driver_id"), jsonObj)).toMap
                for (dataBusBean <- dataBusBeanList) {
                    val vehicleObj: JSONObject = vehicleMap.getOrElse(dataBusBean.dept_id + "-" + dataBusBean.driver_id, null)
                    if (vehicleObj != null) {
                        dataBusBean.driver_card_number = vehicleObj.getString("driver_card_number")
                        dataBusBean.driver_name = vehicleObj.getString("driver_name")
                    }
                }
            }
            dataBusBeanList.toIterator
        }
        //关联公司表
        val joinEnterpriseStream: DStream[DataBusBean] = joinDriverStream.mapPartitions { dataBusBean =>
            val dataBusBeanList: List[DataBusBean] = dataBusBean.toList
            if (dataBusBeanList.nonEmpty) {
                //每分区的操作(deptId-vehicelId)
                val deptIdDriverIdList: List[String] = dataBusBeanList.filter(_.enterprise_id != null).map { data =>
                    data.dept_id + "-" + data.enterprise_id
                }.distinct
                val deptIdEnterpriseIds: String = deptIdDriverIdList.mkString("','")
                val sql =
                    s"""
                       |select enterprise_id, enterprise_name, department_id, address, lng,
                       |    lat, enterprise_type_id, province_id, city_id, area_id, dept_id
                       |from dim_cwp_d_enterprise_info
                       |where CONCAT_WS("-",dept_id,enterprise_id) in ('$deptIdEnterpriseIds')
            """.stripMargin
                val jsonObjList: List[JSONObject] = MysqlUtil.queryList(sql)
                val vehicleMap: Map[String, JSONObject] = jsonObjList.map(jsonObj => (jsonObj.getString("dept_id") + "-" + jsonObj.getString("enterprise_id"), jsonObj)).toMap

                val lst1 = new ListBuffer[DataBusBean]
                for (dataBusBean <- dataBusBeanList) {
                    val vehicleObj: JSONObject = vehicleMap.getOrElse(dataBusBean.dept_id + "-" + dataBusBean.enterprise_id, null)
                    if (vehicleObj != null) {
                        dataBusBean.enterprise_name = vehicleObj.getString("enterprise_name")
                        dataBusBean.department_id = vehicleObj.getInteger("department_id")
                        dataBusBean.address = vehicleObj.getString("address")
                        dataBusBean.enterprise_lng = vehicleObj.getDouble("lng")
                        dataBusBean.enterprise_lat = vehicleObj.getDouble("lat")
                        dataBusBean.enterprise_type_id = vehicleObj.getString("enterprise_type_id")
                        dataBusBean.province_id = vehicleObj.getString("province_id")
                        dataBusBean.city_id = vehicleObj.getString("city_id")
                        dataBusBean.area_id = vehicleObj.getString("area_id")
                        lst1 += dataBusBean
                    }
                }
                lst1.toIterator
            } else {
                dataBusBeanList.toIterator
            }
        }

        // 6,关联运输证表
        val joinTransportCardStream: DStream[DataBusBean] = joinEnterpriseStream.mapPartitions { dataBusBean =>
            val dataBusBeanList: List[DataBusBean] = dataBusBean.toList
            if (dataBusBeanList.nonEmpty) {
                //每分区的操作(deptId-vehicelId)
                val deptIdVehicleIdList: List[String] = dataBusBeanList.filter(_.vehicle_id != null).map { data =>
                    data.dept_id + "-" + data.vehicle_id
                }.distinct
                val deptIdVehicleIds: String = deptIdVehicleIdList.mkString("','")
                val sql =
                    s"""
                       |select vehicle_id, state, dept_id
                       |from ods_cwp_vehicle_transport_card
                       |where CONCAT_WS("-",dept_id,vehicle_id) in ('$deptIdVehicleIds')
                       |    and state=1
            """.stripMargin
                val jsonObjList: List[JSONObject] = MysqlUtil.queryList(sql)
                //一车一证
                val vehicleMap: Map[String, JSONObject] = jsonObjList.map(jsonObj => (jsonObj.getString("dept_id") + "-" + jsonObj.getString("vehicle_id"), jsonObj)).toMap
                for (dataBusBean <- dataBusBeanList) {
                    val vehicleObj: JSONObject = vehicleMap.getOrElse(dataBusBean.dept_id + "-" + dataBusBean.vehicle_id, null)
                    if (vehicleObj != null) {
                        dataBusBean.transport_card_state = 1
                    }
                }
            }
            dataBusBeanList.toIterator
        }

        // 7,关联区域表
        val dataBusStream: DStream[DataBusBean] = joinTransportCardStream.mapPartitions { dataBusBean =>
            val dataBusBeanList: List[DataBusBean] = dataBusBean.toList
            if (dataBusBeanList.nonEmpty) {
                val deptIdList: List[Integer] = dataBusBeanList.filter(_.dept_id != null).map { data =>
                    data.dept_id
                }.distinct
                val deptIds: String = deptIdList.mkString(",")
                val sql =
                    s"""
                       |select id, city_name, coords, dept_id
                       |from dim_cwp_area_coords
                       |where dept_id in ($deptIds)
                       |    and state=1
            """.stripMargin
                val jsonObjList: List[JSONObject] = MysqlUtil.queryList(sql)
                val hashMap = new scala.collection.mutable.HashMap[Integer, List[JSONObject]]
                for (json <- jsonObjList) {
                    val deptId: Integer = json.getInteger("dept_id")
                    if (hashMap.contains(deptId)) {
                        val value: List[JSONObject] = hashMap(deptId)
                        val values = value :+ json
                        hashMap += (deptId -> values)
                    } else {
                        hashMap += (deptId -> List(json))
                    }
                }
                for (dataBusBean <- dataBusBeanList) {
                    val jsonObjs: List[JSONObject] = hashMap.getOrElse(dataBusBean.dept_id, null)
                    if (jsonObjs != null) {
                        //循环遍历
                        var flag = true
                        val lng: lang.Double = dataBusBean.getLng
                        val lat: lang.Double = dataBusBean.getLat
                        for (json <- jsonObjList if flag) {
                            val areaId: String = json.getString("id")
                            val cityName: String = json.getString("city_name")
                            val coords: String = json.getString("coords")
                            val bool = AreaUtil.inArea(lng, lat, coords)
                            if (bool) {
                                dataBusBean.region_id = areaId
                                dataBusBean.region_name = cityName
                                flag = false
                            }
                        }
                    }
                }
            }
            dataBusBeanList.toIterator
        }

        dataBusStream.foreachRDD { rdd =>
            rdd.foreachPartition { orderInfoItr =>
                val dataBusBeanList: List[DataBusBean] = orderInfoItr.toList
                for (dataBusBean <- dataBusBeanList) {
                    val dataBusJsonString: String = JSON.toJSONString(dataBusBean, SerializerFeature.WriteMapNullValue)
                    KafkaSink.send(properties.getProperty("topic.dwd.data.bus"), dataBusJsonString)
                }
            }
        }

        ssc.start()
        ssc.awaitTermination()
    }
}