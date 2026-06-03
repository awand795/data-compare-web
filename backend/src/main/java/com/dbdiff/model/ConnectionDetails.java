package com.dbdiff.model;

import lombok.Data;

@Data
public class ConnectionDetails {
    private String id;
    private String name;
    private String type; // postgresql, mysql, mariadb, sqlserver
    private String host;
    private int port;
    private String database;
    private String username;
    private String password;
    private String schema;
    
    public String getJdbcUrl() {
        switch (type.toLowerCase()) {
            case "postgresql":
                String url = "jdbc:postgresql://" + host + ":" + port + "/" + database;
                if (schema != null && !schema.trim().isEmpty()) {
                    url += "?currentSchema=" + schema.trim();
                }
                return url;
            case "mysql":
                return "jdbc:mysql://" + host + ":" + port + "/" + database;
            case "mariadb":
                return "jdbc:mariadb://" + host + ":" + port + "/" + database;
            case "sqlserver":
                return "jdbc:sqlserver://" + host + ":" + port + ";databaseName=" + database + ";encrypt=true;trustServerCertificate=true;";
            default:
                throw new IllegalArgumentException("Unsupported database type: " + type);
        }
    }
}
