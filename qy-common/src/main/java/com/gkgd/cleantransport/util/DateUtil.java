package com.gkgd.cleantransport.util;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;

public class DateUtil {
    public static boolean isInTimeRange(String intoTime, String startTime, String endTime) throws ParseException {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
        SimpleDateFormat sdfAll = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        String firstStr = intoTime.split(" ")[0];
        Calendar c = Calendar.getInstance();
        c.setTime(sdf.parse(firstStr));
        c.add(Calendar.DATE, 1);
        String SecondStr = sdf.format(c.getTime());

        //进行条件判断
        //1、如果两个时间点相同，则取全天
        if(startTime.equals(endTime)) return true;
        //2、若开始时间小于结束时间，则为同一天
        if (Integer.parseInt(startTime.split(":")[0]) < Integer.parseInt(endTime.split(":")[0])) {
            //判断是否在该区间
            String start = firstStr + " " + startTime + ":00";
            String end = firstStr + " " + endTime + ":00";
            long diffStart = sdfAll.parse(intoTime).getTime() - sdfAll.parse(start).getTime();
            long diffEnd = sdfAll.parse(end).getTime() - sdfAll.parse(intoTime).getTime();
            if(diffStart >0 && diffEnd >0) return true;
        }
        //3、若开始时间大于结束时间，则为跨天
        if (Integer.parseInt(startTime.split(":")[0]) > Integer.parseInt(endTime.split(":")[0])) {
            String start = firstStr + " " + startTime + ":00";
            String end = SecondStr + " " + endTime + ":00";
            long diffStart = sdfAll.parse(intoTime).getTime() - sdfAll.parse(start).getTime();
            long diffEnd = sdfAll.parse(end).getTime() - sdfAll.parse(intoTime).getTime();
            if(diffStart >0 && diffEnd >0) return true;
        }

        return false;
    }

    public static boolean isEffectiveDate(String intoTime, String startTimeStr, String endTimeStr) throws Exception {
        SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm");
        Date nowTime = simpleDateFormat.parse(intoTime);
        Date startTime = simpleDateFormat.parse(startTimeStr);
        Date endTime = simpleDateFormat.parse(endTimeStr);
        if (nowTime.getTime() == startTime.getTime()
                || nowTime.getTime() == endTime.getTime()) {
            return true;
        }

        Calendar date = Calendar.getInstance();
        date.setTime(nowTime);

        Calendar begin = Calendar.getInstance();
        begin.setTime(startTime);

        Calendar end = Calendar.getInstance();
        end.setTime(endTime);

        if (date.after(begin) && date.before(end)) {
            return true;
        } else {
            return false;
        }
    }

    public static void main(String[] args) throws Exception {
        String timeStr = "2020-10-20 23:46:39";
        String startTime = "23:00";
        String endTime = "05:00";
        boolean inTimeRange = DateUtil.isInTimeRange(timeStr, startTime, endTime);
        System.out.println(inTimeRange);
//        System.out.println(isEffectiveDate(timeStr,startTime,endTime));

    }
}
