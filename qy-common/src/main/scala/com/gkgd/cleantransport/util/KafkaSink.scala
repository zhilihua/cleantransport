package com.gkgd.cleantransport.util

/**
  * @ModelName
  * @Description
  * @Author zhangjinhang
  * @Date 2020/11/20 15:38
  * @Version V1.0.0
  */
import java.util.Properties

import org.apache.kafka.clients.producer.{KafkaProducer, ProducerRecord}

object KafkaSink {
  private val props: Properties = Configuration.conf("config.properties")
  private val broker_list: String = props.getProperty("bootstrap.servers")

  var kafkaProducer: KafkaProducer[String, String] = null

  def createKafkaProducer: KafkaProducer[String, String] = {
    val properties = new Properties
    properties.put("bootstrap.servers", broker_list)
    properties.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer")
    properties.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer")
    properties.put("enable.idompotence",(true: java.lang.Boolean))
    var producer: KafkaProducer[String, String] = null
    try
      producer = new KafkaProducer[String, String](properties)
    catch {
      case e: Exception =>
        e.printStackTrace()
    }
    producer
  }

  def send(topic: String, msg: String): Unit = {
    if (kafkaProducer == null) kafkaProducer = createKafkaProducer
    kafkaProducer.send(new ProducerRecord[String, String](topic, msg))

  }

  def send(topic: String,key:String, msg: String): Unit = {
    if (kafkaProducer == null) kafkaProducer = createKafkaProducer
    kafkaProducer.send(new ProducerRecord[String, String](topic,key, msg))

  }
}
