package com.dbdiff.consolidation.utils;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

public class ResultType {
    public static List<Map<String, Object>> convertResultSetToList(ResultSet res) throws SQLException {
        List<Map<String, Object>> resData = new ArrayList<>();
        ResultSetMetaData resMetaData = res.getMetaData();
        Integer totalData = resMetaData.getColumnCount();

        while (res.next()) {
            Map<String, Object> data = new HashMap<>();
            for (int index = 1; index <= totalData; index++) {
                String columnType = resMetaData.getColumnTypeName(index);
                String columnName = resMetaData.getColumnName(index);
                if (res.getObject(index) == null) {
                    data.put(columnName, "");
                } else if (columnType.equalsIgnoreCase("varchar") || columnType.equalsIgnoreCase("longvarchar")
                        || columnType.equalsIgnoreCase("char") || columnType.equalsIgnoreCase("text")) {
                    data.put(columnName, res.getString(index));
                } else if (columnType.equalsIgnoreCase("numeric")) {
                    data.put(columnName, res.getBigDecimal(index));
                } else if (columnType.equalsIgnoreCase("integer")) {
                    data.put(columnName, res.getInt(index));
                } else if (columnType.equalsIgnoreCase("bit")) {
                    data.put(columnName, res.getBoolean(index));
                } else if (columnType.equalsIgnoreCase("timestamp")) {
                    data.put(columnName, res.getTimestamp(index).toString());
                } else if (columnType.equalsIgnoreCase("date")) {
                    data.put(columnName, res.getDate(index).toString());
                } else if (columnType.equalsIgnoreCase("double")) {
                    data.put(columnName, res.getDouble(index));
                } else if (columnType.equalsIgnoreCase("bool") || columnType.equalsIgnoreCase("boolean")) {
                    Boolean rsBool = res.getBoolean(index);
                    data.put(columnName, rsBool);
                } else {
                    if (res.getString(index) == null) {
                        data.put(columnName, "null");
                    } else {
                        data.put(columnName, res.getString(index));
                    }
                }
            }
            resData.add(data);
        }
        res.close();

        return resData;
    }

    public static List<Map<String, Object>> convertJSONArrToList(JSONArray listData) {
        List<Map<String, Object>> resData = new ArrayList<>();

        if (listData != null && listData.length() > 0) {
            for (int i = 0; i < listData.length(); i++) {
                Map<String, Object> obj = new HashMap<>();
                JSONObject recData = listData.getJSONObject(i);
                Iterator<String> keys = recData.keys();

                while (keys.hasNext()) {
                    String key = keys.next();
                    if (recData.get(key).getClass() == String.class) {
                        obj.put(key, recData.getString(key));
                    } else if (recData.get(key).getClass() == BigDecimal.class) {
                        obj.put(key, new BigDecimal(recData.getDouble(key)));
                    } else {
                        obj.put(key, recData.get(key));
                    }
                }
                resData.add(obj);
            }
        }

        return resData;
    }
}
