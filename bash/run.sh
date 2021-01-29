#!/bin/bash
pcount=$#

if((pcount!=2))
then
        echo "第一个参数为任务所在的类";
        echo "第二个参数为其名字";
        exit;
fi

#sh application_submit.sh > /root/myRealTimeTest/hdfs/logs/log_$(date +%y%m%d).txt.out 2>&1 &

fname=/home/hdfs/myRealTimeTest


echo 运行脚本是：$fname/myScript/$1.sh，日志在：$fname/logs/log_$2_$(date +%y%m%d).txt.out
sh  $fname/bin/application.sh $1 $2 > $fname/log/log_$2_$(date +%y%m%d).txt.out 2>&1 &