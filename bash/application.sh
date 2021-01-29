spark-submit \
--class $1  \
--name $2   \
--master yarn  \
--deploy-mode cluster  \
--driver-memory 2g  \
--executor-memory 3g  \
--executor-cores 3  \
--num-executors 3  \
--jars /root/myRealTimeTest/jar/httpasyncclient-4.1.3.jar,/root/myRealTimeTest/jar/httpcore-nio-4.4.6.jar,/root/myRealTimeTest/jar/jest-5.3.3.jar,/root/myRealTimeTest/jar/jest-common-5.3.3.jar,/root/myRealTimeTest/jar/spark-sql_2.11-2.2.0.jar,/root/myRealTimeTest/jar/jedis-2.9.0.jar,/root/myRealTimeTest/jar/commons-pool2-2.4.2.jar,/root/myRealTimeTest/jar/gd-common-1.0.0.jar,/root/myRealTimeTest/jar/kafka-clients-0.10.0.0.jar,/root/myRealTimeTest/jar/spark-streaming-kafka-0-10_2.11-2.2.0.jar,/root/myRealTimeTest/jar/cglib-2.2.2.jar,/root/myRealTimeTest/jar/mysql-connector-java-5.1.45.jar,/root/myRealTimeTest/jar/fastjson-1.2.60.jar,/root/myRealTimeTest/jar/geodesy-1.1.3.jar,/root/myRealTimeTest/jar/druid-1.1.21.jar \
/root/myRealTimeTest/jar/qy-construction-waste-1.0.0.jar