package com.gkgd.cleantransport.old.realtime.out

import java.text.SimpleDateFormat
import java.util.{Calendar, Date}

import com.alibaba.fastjson.JSON
import com.gkgd.cleantransport.entity.Tang
import com.gkgd.cleantransport.entity.dws.TangBean
import com.gkgd.cleantransport.util.{Configuration, KafkaSource}
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.streaming.dstream.{DStream, InputDStream}
import org.apache.spark.streaming.{Seconds, StreamingContext}

/**
  * @ModelName
  * @Description
  * @Author zhangjinhang
  * @Date 2020/11/21 16:43
  * @Version V1.0.0
  */
object DwsTangs2HiveStream {
  def main(args: Array[String]): Unit = {
    val properties = Configuration.conf("config.properties")
    val hiveMetastore = properties.getProperty("hive.metastore.uris")
    val hiveDatabase = properties.getProperty("hive.database")
    val sparkSession = SparkSession.builder()
      .appName("Tangs2Hive")
//      .master("local[4]")
      .config("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
      .config("hive.metastore.uris", hiveMetastore)
      .config("hive.exec.dynamic.partition.mode", "nonstrict")
      .enableHiveSupport()
      .getOrCreate()

    val sparkContext = sparkSession.sparkContext
    val ssc = new StreamingContext(sparkContext,Seconds(5))

    val topic = properties.getProperty("topic.dws.data.tangs")
    val groupId = "Tangs2Hive-001"

    //获取数据
    var recordInputStream: InputDStream[ConsumerRecord[String, String]] = KafkaSource.getKafkaStream(topic, ssc, groupId)

    val tangsBeanStream: DStream[TangBean] = recordInputStream.map { record =>
      val jsonString: String = record.value()
      val tangsBean: TangBean = JSON.parseObject(jsonString, classOf[TangBean])
      tangsBean
    }.filter{x =>
      if(x.from_site_lng!=null && x.from_site_lat!=null && x.to_site_lng!=null && x.to_site_lat!=null){
        true
      }else {
        false
      }
    }

    tangsBeanStream.foreachRDD { rdd =>
      val now: Date = new Date()
      val dateFormat: SimpleDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
      val date = dateFormat.format(now)
      val calendar = Calendar.getInstance()
      val dayz = calendar.get(Calendar.DATE).toString
      val hourz = calendar.get(Calendar.HOUR_OF_DAY).toString
      val split_str = date.split(" ")
      val split_1 = split_str(0).split("-")
      val yearz_month = split_1(0) + "-" + split_1(1)

      import sparkSession.implicits._
      val frame: DataFrame = rdd.map(bean => Tang(
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        bean.to_site_inter_time,
        bean.to_site_id,
        bean.to_site_name,
        bean.to_site_address,
        bean.to_site_province_id,
        bean.to_site_city_id,
        bean.to_site_area_id,
        bean.to_site_lat,
        bean.to_site_lng,
        if(bean.to_sit_flag==null) -1 else bean.to_sit_flag,
        0,
        null,
        0,
        bean.from_site_id,
        bean.from_site_name,
        if(bean.from_sit_flag==null) -1 else bean.from_sit_flag,
        bean.from_site_lng,
        bean.from_site_lat,
        bean.from_site_area_id,
        bean.from_site_province_id,
        bean.from_site_city_id,
        bean.from_site_address,
        if(bean.from_site_inter_time==null) "" else bean.from_site_inter_time,
        date,
        bean.dept_id,
        bean.vehicle_id,
        bean.enterprise_id,
        bean.car_card_number,
        bean.load,
        bean.enterprise_name,
        bean.province_id,
        bean.city_id,
        bean.area_id,
        bean.vehicle_empty,
        yearz_month,
        dayz,
        hourz
      )).toDF()

      frame.write.format("hive").mode("append").partitionBy("month_id", "day_id","hour_id").saveAsTable(hiveDatabase+".dwa_s_h_cwp_tangs")
    }

    ssc.start()
    ssc.awaitTermination()
  }
}
