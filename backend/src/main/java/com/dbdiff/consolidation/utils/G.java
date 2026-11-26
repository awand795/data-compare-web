package com.dbdiff.consolidation.utils;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import org.json.JSONObject;

public class G {
    public static Connection databaseConn(String setOnDB) throws SQLException {
        Connection conn = null;
        JSONObject setupConn = new JSONObject();
        String host = "", port = "", databaseName = "";
        if (setOnDB.equalsIgnoreCase("dev")) {
            host = "148.135.42.81";
            port = "5432";
            databaseName = "dba_dev_konsol";
            setupConn.put("url", "jdbc:postgresql://" + host + ":" + port + "/" + databaseName);
            setupConn.put("username", "postgres");
            setupConn.put("password", "SuperDev2021");
        } else if (setOnDB.equalsIgnoreCase("pro")) {
            host = "war.darkosuite.com";
            port = "8832";
            databaseName = "data_erp";
            setupConn.put("url", "jdbc:postgresql://" + host + ":" + port + "/" + databaseName);
            setupConn.put("username", "erp_konsol");
            setupConn.put("password", "erp4Konsol");
        }
        conn = DriverManager.getConnection(setupConn.getString("url"), setupConn.getString("username"),
                setupConn.getString("password"));
        conn.setAutoCommit(false);
        return conn;
    }

    public static JSONObject erpAuth() {
        JSONObject authData = new JSONObject();
        authData.put("user_auth", "BLACKCOFFEE");
        authData.put("pass_auth", "$2b$10$CKHJ9IukXqv9SDKzjSMix.GUez5MsAqf0xu1O4nZ2ixSC9LWvylIi");
        authData.put("content_type", "application/x-www-form-urlencoded");
        return authData;
    }

    public static JSONObject programAuth() {
        JSONObject userList = new JSONObject();
        JSONObject authData = new JSONObject();
        authData.put("user_auth", "Administrator");
        authData.put("pass_auth", "Konsolidasi-Admin");
        userList.put("ADMIN", authData);
        return userList;
    }
}
