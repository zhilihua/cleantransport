package com.gkgd.cleantransport.util;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;

public class DateUtil {
    public static boolean isInTimeRange(String intoTime, String startTime, String endTime) throws ParseException {
        SimpleDateFormat sdfAll = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

        //进行条件判断
        //1、如果两个时间点相同，则取全天
        if(startTime.equals(endTime)) return true;
        //2、若开始时间小于结束时间，则为同一天
        if (Integer.parseInt(startTime.split(":")[0]) < Integer.parseInt(endTime.split(":")[0])) {
            String firstStr = intoTime.split(" ")[0];
            //判断是否在该区间
            String start = firstStr + " " + startTime + ":00";
            String end = firstStr + " " + endTime + ":00";
            long diffStart = sdfAll.parse(intoTime).getTime() - sdfAll.parse(start).getTime();
            long diffEnd = sdfAll.parse(end).getTime() - sdfAll.parse(intoTime).getTime();
            if(diffStart >0 && diffEnd >0) return true;
        }
        //3、若开始时间大于结束时间，则为跨天
        if (Integer.parseInt(startTime.split(":")[0]) > Integer.parseInt(endTime.split(":")[0])) {
            int nowHour = Integer.parseInt(intoTime.split(" ")[1].split(":")[0]);
            int startHour = Integer.parseInt(startTime.split(":")[0]);
            int endHour = Integer.parseInt(endTime.split(":")[0]);

            if(nowHour >= startHour && nowHour < 24){
                return true;
            }else if(nowHour >= 0 && nowHour < endHour){
                return true;
            }
        }

        return false;
    }

    public static boolean isEffectiveDate(String intoTime, String startTimeStr, String endTimeStr) throws Exception {
        SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        long nowTime = simpleDateFormat.parse(intoTime).getTime();
        long startTime = simpleDateFormat.parse(startTimeStr).getTime();
        long endTime = simpleDateFormat.parse(endTimeStr).getTime();

        if(nowTime > startTime && nowTime < endTime){
            return true;
        }

        return false;
    }

    public static void main(String[] args) throws Exception {
        String timeStr = "2021-03-16 23:11:27";
        String startTime = "05:00";
        String endTime = "23:00";
        boolean inTimeRange = DateUtil.isInTimeRange(timeStr, startTime, endTime);
        System.out.println(inTimeRange);
//        System.out.println(isEffectiveDate(timeStr,startTime,endTime));

    }
}
