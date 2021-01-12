package com.gkgd.cleantransport.realtime.ods

import java.text.SimpleDateFormat
import java.util.Date

import com.gkgd.cleantransport.entity.dwd.DataBusBean
import com.gkgd.cleantransport.entity.ods.TblPosinfo
import com.gkgd.cleantransport.jt8080.{HexStringUtils, LocationInformationReport, MsgDecoderUtil, PackageData}
import com.gkgd.cleantransport.util.{Configuration, GeoUtil, KafkaSource}
import net.sf.cglib.beans.BeanCopier
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.spark.SparkConf
import org.apache.spark.streaming.dstream.{DStream, InputDStream}
import org.apache.spark.streaming.{Seconds, StreamingContext}

object OdsTrackJT8080Stream {
    def main(args: Array[String]): Unit = {
        val sparkConf: SparkConf = new SparkConf()
            .setAppName("OdsTrackJT8080Stream")
            .set("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
            .setMaster("local[*]")
        val ssc = new StreamingContext(sparkConf, Seconds(10))

        //加载topic配置文件
        val propsTopics = Configuration.conf("kafka-topics.properties")
        val topic = propsTopics.getProperty("topic.ods.tracks")
        val groupId = "OdsTrackJT8080Stream001"
        //加载拦截器配置文件
        val propsInterceptor = Configuration.conf("interceptor.properties")
        val minLat = propsInterceptor.getProperty("min.lat").toInt
        val maxLat = propsInterceptor.getProperty("max.lat").toInt
        val minLng = propsInterceptor.getProperty("min.lng").toInt
        val maxLng = propsInterceptor.getProperty("max.lng").toInt
        val minSpeed = propsInterceptor.getProperty("min.speed").toInt
        val maxSpeed = propsInterceptor.getProperty("max.speed").toInt

        //获取jt8080数据
        val recordInputStream: InputDStream[ConsumerRecord[String, String]] = KafkaSource.getKafkaStream(topic, ssc, groupId)
        val transportInputStream = recordInputStream.map { record =>
            try {
                val input: Array[Byte] = HexStringUtils.chars2Bytes(record.value().toCharArray)
                val packageData: PackageData = new MsgDecoderUtil().bytes2PackageData(input)
                val header: PackageData.MsgHeader = packageData.getMsgHeader
                val locationInformationReport: LocationInformationReport = LocationInformationReport.getEntity(packageData.getMsgBodyBytes)
                val tblPosinfo = new TblPosinfo(header.getTerminalPhone, locationInformationReport)
                val dataBusBean = new DataBusBean
                val copier: BeanCopier = BeanCopier.create(classOf[TblPosinfo], classOf[DataBusBean], false)
                copier.copy(tblPosinfo, dataBusBean, null)
                dataBusBean
            } catch {   //异常情况返回空的车辆信息
                case ex: Exception =>
                    new DataBusBean
            }
        }

        //过滤违规数据和异常数据
        val filterIllegalStream: DStream[DataBusBean] = transportInputStream.filter { dataBusBean =>
            val lng = dataBusBean.lng
            val lat = dataBusBean.lat
            val speed = dataBusBean.speed.toDouble
            val time = dataBusBean.time
            val gpsDate = time.split(" ")(0)
            val sdf = new SimpleDateFormat("yyyy-MM-dd")
            val nowDate = sdf.format(new Date)
            val devid = dataBusBean.devid

            //TODO:配置文件中
            if ((lat > minLat && lat < maxLat) && (lng > minLng && lng < maxLng)
                && (speed >= minSpeed) && nowDate == gpsDate && devid != null) {
                val gps: Array[Double] = GeoUtil.wgs2bd(lat, lng)
                dataBusBean.setLat(gps(0))
                dataBusBean.setLng(gps(1))
                true
            }
            else false
        }

        filterIllegalStream.foreachRDD(rdd => {
            rdd.foreachPartition(partRDD => {
                partRDD.foreach(info => {
                    println(info.lat, info.lng, info.devid, info.speed)
                })
            })
        })


        //开始
        ssc.start()
        ssc.awaitTermination()
    }
}
