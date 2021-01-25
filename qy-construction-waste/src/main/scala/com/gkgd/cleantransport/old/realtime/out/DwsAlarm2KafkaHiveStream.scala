package com.gkgd.cleantransport.old.realtime.out

import java.text.SimpleDateFormat
import java.util.{Calendar, Date, UUID}

import com.alibaba.fastjson.JSON
import com.alibaba.fastjson.serializer.SerializerFeature
import com.gkgd.cleantransport.entity.AlarmBeanScala
import com.gkgd.cleantransport.entity.dws.AlarmBean
import com.gkgd.cleantransport.util.{Configuration, GeoUtil, KafkaSink, KafkaSource, MysqlUtil}
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.spark.SparkConf
import org.apache.spark.sql.SparkSession
import org.apache.spark.streaming.dstream.InputDStream
import org.apache.spark.streaming.{Seconds, StreamingContext}
import org.gavaghan.geodesy.Ellipsoid


object DwsAlarm2KafkaHiveStream {
    def main(args: Array[String]): Unit = {
        val properties = Configuration.conf("config.properties")
        val hiveMetastore = properties.getProperty("hive.metastore.uris")
        val dwdAlamTopic = properties.getProperty("topic.dwd.data.alarm")
        val dwsAlamTopic = properties.getProperty("topic.dws.data.alarm")
        val hiveDatabase = properties.getProperty("hive.database")
        val groupId = "DwsAlarm2KafkaHiveStream-001"
        //配置参数
        val conf = new SparkConf()
            .setAppName("KafkaHiveWrite")
            .setMaster("local[2]")
            .set("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
        val ssc = new StreamingContext(conf, Seconds(5))    //定义流
        val sparkSession = SparkSession.builder().config(conf)   //批处理
            .config("hive.metastore.uris", hiveMetastore)
            .config("hive.exec.dynamic.partition.mode", "nonstrict")
            .enableHiveSupport()
            .getOrCreate()

        //获取流
        val recordInputStream: InputDStream[ConsumerRecord[String, String]] = KafkaSource.getKafkaStream(dwdAlamTopic, ssc, groupId)
        val alarmBeanDStream = recordInputStream.map(record => {
            val value = record.value()
            val alarmBean = JSON.parseObject(value, classOf[AlarmBean])

            val now = new Date()
            val dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
            val date = dateFormat.format(now)
            val calendar = Calendar.getInstance()
            val dayz = String.valueOf(calendar.get(Calendar.DATE))
            val hourz = String.valueOf(calendar.get(Calendar.HOUR_OF_DAY))
            val split_str = date.split(" ")
            val split_1 = split_str(0).split("-")
            val yearz_month = split_1(0) + "-" + split_1(1)
            alarmBean.setMonth_id(yearz_month)
            alarmBean.setDay_id(dayz)
            alarmBean.setHour_id(hourz)

            val startTime = alarmBean.getAlarm_start_time
            val endTime = alarmBean.getAlarm_end_time

            if (startTime != null && endTime != null) {
                val alarmStartTime = dateFormat.parse(startTime).getTime
                val alarmEndTime = dateFormat.parse(endTime).getTime
                val diff = alarmEndTime - alarmStartTime; //毫秒级差值
                var minute = diff / 1000 / 60
                val hour = minute / 60
                if (hour >= 1) {
                    minute = (diff / 1000) % 60
                    alarmBean.setInterval_time(hour + "小时" + minute + "分")
                } else {
                    alarmBean.setInterval_time(minute + "分")
                }
            }

            val alarmStartLng = alarmBean.getAlarm_start_lng
            val alarmStartLat = alarmBean.getAlarm_start_lat
            val alarmEndLng = alarmBean.getAlarm_end_lng
            val alarmEndLat = alarmBean.getAlarm_end_lat

            if (alarmStartLng != null && alarmStartLat != null && alarmEndLng != null && alarmEndLat != null) {
                val distanceMeter = GeoUtil.getDistanceMeter(alarmStartLng, alarmStartLat, alarmEndLng, alarmEndLat, Ellipsoid.Sphere)
                alarmBean.setInterval_distance(distanceMeter)
            }

            val dept_id = alarmBean.getDept_id
            val illegal_type_code = alarmBean.getIllegal_type_code

            val sql = "select illegal_type_code,illegal_type_short_desc,illegal_type_desc,enterprise_score," +
                "vehicle_score,driver_score from ods_cwp_credit_illegal_type_dept_set where CONCAT_WS('-',dept_id,illegal_type_code) in ('" + dept_id + "-" + illegal_type_code + "')"

            val jsonObjList = MysqlUtil.queryList(sql)

            if (jsonObjList != null && jsonObjList.nonEmpty) {
                val jsonObject = jsonObjList.head
                alarmBean.setIllegal_type_short_desc(jsonObject.getString("illegal_type_short_desc"))
                alarmBean.setIllegal_type_desc(jsonObject.getString("illegal_type_desc"))
                alarmBean.setEnterprise_score(jsonObject.getDouble("enterprise_score"))
                alarmBean.setVehicle_score(jsonObject.getDouble("vehicle_score"))
                alarmBean.setDriver_score(jsonObject.getDouble("driver_score"))
            }

            alarmBean.setUuid(UUID.randomUUID().toString)
            alarmBean.setScoring_year(Integer.valueOf(split_1(0)))

            if (endTime == null) {
                alarmBean.setAlarm_end_time(startTime)
            }

            if (alarmStartLat != null && alarmStartLng != null) {
                val address = GeoUtil.getAddress(alarmStartLat, alarmStartLng)
                alarmBean.setAlarm_start_address(address)
            }

            if (alarmEndLat != null && alarmEndLng != null) {
                val address = GeoUtil.getAddress(alarmEndLat, alarmEndLng)
                alarmBean.setAlarm_end_address(address)
            }

            alarmBean
        })

        //写入kafka,并返回结果
        val send2KafkaStream = alarmBeanDStream.map(record => {
            val jsonString = JSON.toJSONString(record, SerializerFeature.WriteMapNullValue)
            KafkaSink.send(dwsAlamTopic, jsonString)
            record
        })

        //写入Hive
        send2KafkaStream.foreachRDD(alarmBeanRDD => {
            val frame = sparkSession.createDataFrame(alarmBeanRDD, classOf[AlarmBeanScala])
            frame.repartition(10)
                .write
                .format("hive")
                .partitionBy("month_id", "day_id","hour_id")
                .saveAsTable(hiveDatabase+".dwd_h_cwp_vehicle_alarm")
        })

        ssc.start()
        ssc.awaitTermination()
    }
}
