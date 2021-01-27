package com.gkgd.cleantransport.old.realtime.out

import com.gkgd.cleantransport.util.{Configuration, EsUtil, KafkaSource}
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.spark.SparkConf
import org.apache.spark.streaming.dstream.InputDStream
import org.apache.spark.streaming.{Seconds, StreamingContext}

object DdwDataBus2EsStream {

    def main(args: Array[String]): Unit = {
        val sparkConf: SparkConf = new SparkConf()
            .setAppName("DdwDataBus2EsStream")
            .set("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
//            .setMaster("local[2]")
        val ssc = new StreamingContext(sparkConf, Seconds(5))

        val properties = Configuration.conf("config.properties")
        val topic = properties.getProperty("topic.dwd.data.bus")
        val index = properties.getProperty("es.index.dwd.tracks.vehicle.position")
        val tp = properties.getProperty("es.type.dwd.tracks")
        val groupId = "DdwDataBus2EsStream-001"
        //获取jt8080数据
        val recordInputStream: InputDStream[ConsumerRecord[String, String]] = KafkaSource.getKafkaStream(topic, ssc, groupId)
        recordInputStream.foreachRDD { rdd =>
            rdd.foreachPartition { dataBusBean =>
                val list: List[String] = dataBusBean.toList.map(_.value())
                EsUtil.bulkDocWithoutId(list, index, tp)
            }
        }

        ssc.start()
        ssc.awaitTermination()
    }

}
