package com.dbdiff.consolidation.utils;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.json.JSONObject;

public class NativeQuery {

    public static List<Map<String, Object>> querySelect(String qSub, String[] qSelect, String qFrom,
            String qJoin, String qWhere, String qOrderBy, Integer qLimit, Integer qOffset) throws SQLException {
        try (Connection connDB = ConsolidationDbConfig.getConnection()) {
            String subQ = qSub != null ? qSub : "";

            String column = " SELECT *";
            if (qSelect != null) {
                StringBuilder listColumn = new StringBuilder();
                for (int i = 0; i < qSelect.length; i++) {
                    if (i == 0 && qSelect[i].equalsIgnoreCase("DISTINCT")) {
                        listColumn.append(" ").append(qSelect[i]).append(" ");
                    } else {
                        listColumn.append(" ").append(qSelect[i]).append(",");
                    }
                }
                String cols = listColumn.toString();
                column = " SELECT" + cols.substring(0, cols.lastIndexOf(","));
            }

            String from = " FROM " + qFrom;
            String join = qJoin != null && !qJoin.equalsIgnoreCase("") ? qJoin : "";
            String where = qWhere != null && !qWhere.equalsIgnoreCase("") ? " WHERE " + qWhere : "";
            String orderBy = qOrderBy != null && !qOrderBy.equalsIgnoreCase("") ? " ORDER BY " + qOrderBy : "";

            int setOffset = ((qOffset != null ? qOffset : 1) - 1) * (qLimit != null ? qLimit : 0);
            String limitOffset = qLimit != null ? " LIMIT " + qLimit + " OFFSET " + setOffset : "";

            String query = subQ + column + from + join + where + orderBy + limitOffset;
            try (Statement stm = connDB.createStatement();
                 ResultSet res = stm.executeQuery(query)) {
                return ResultType.convertResultSetToList(res);
            }
        } catch (Exception e) {
            Logger.getLogger(NativeQuery.class.getName()).log(Level.SEVERE, e.getMessage(), e);
            return new ArrayList<>();
        }
    }

    public static String queryFunctionDBString(String funcName, JSONObject listColumn) throws SQLException {
        try (Connection connDB = ConsolidationDbConfig.getConnection()) {
            String query = "SELECT " + funcName + "('" + listColumn + "')";
            try (Statement stm = connDB.createStatement();
                 ResultSet res = stm.executeQuery(query)) {
                if (res != null && res.next()) {
                    connDB.commit();
                    return res.getString(1);
                } else {
                    connDB.rollback();
                    return "{}";
                }
            }
        } catch (Exception e) {
            Logger.getLogger(NativeQuery.class.getName()).log(Level.SEVERE, e.getMessage(), e);
            return "{}";
        }
    }
}
