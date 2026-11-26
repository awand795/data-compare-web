package com.dbdiff.consolidation.utils;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.TimeZone;

public class Formatter {

    public static Date nowDate() {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeZone(TimeZone.getTimeZone("Asia/Jakarta"));
        return calendar.getTime();
    }

    public static String getDateString(Date timestamp, String format) {
        String formatVal = format == null ? "yyyy-MM-dd hh:mm:ss" : format;
        Date tStamp = timestamp == null ? Calendar.getInstance().getTime() : timestamp;
        TimeZone tz = TimeZone.getTimeZone("Asia/Bangkok");
        SimpleDateFormat dateFormat = new SimpleDateFormat(formatVal);
        dateFormat.setTimeZone(tz);
        return dateFormat.format(tStamp);
    }

    public static Date stringToDateOnly(String s) {
        Date result = null;
        try {
            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");
            result = dateFormat.parse(s);
        } catch (ParseException e) {
            e.printStackTrace();
        }
        return result;
    }

    public static Date stringToTimestamps(String s) {
        Date result = null;
        try {
            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            result = dateFormat.parse(s);
        } catch (ParseException e) {
            e.printStackTrace();
        }
        return result;
    }

    public static Date subtractDateDays(Date _date, int days) {
        if (_date != null) {
            Calendar calendar = Calendar.getInstance();
            calendar.setTime(_date);
            calendar.setTimeZone(TimeZone.getTimeZone("Asia/Bangkok"));
            calendar.add(Calendar.DAY_OF_MONTH, days);
            return calendar.getTime();
        }
        return null;
    }

    public static String subtractDateDays(String _strDate, int days) {
        if (_strDate != null && !_strDate.isEmpty()) {
            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");
            dateFormat.setTimeZone(TimeZone.getTimeZone("Asia/Bangkok"));
            try {
                Calendar calendar = Calendar.getInstance();
                calendar.setTime(dateFormat.parse(_strDate));
                calendar.setTimeZone(TimeZone.getTimeZone("Asia/Bangkok"));
                calendar.add(Calendar.DAY_OF_MONTH, days);
                return dateFormat.format(calendar.getTime());
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    public static String nvlString(String _value, String _defaultValue) {
        if (_value == null || _value.isEmpty()) {
            return _defaultValue;
        }
        return _value;
    }
}
