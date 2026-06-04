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
    
    // SSL Settings
    private String sslMode;
    private String sslCaFile;
    private String sslCertFile;
    private String sslKeyFile;
    
    // SSH Tunnel Settings
    private boolean useSsh;
    private String sshHost;
    private Integer sshPort;
    private String sshUsername;
    private String sshAuthMode;
    private String sshPassword;
    private String sshKeyFile;
    private String sshPassphrase;
    private Integer sshLocalPort;
    
    // Advanced Settings
    private Integer connectionTimeout;
    private Integer socketTimeout;
    private Integer fetchSize;
    private boolean readOnly;
    private String extraProps;
    public String getJdbcUrl() {
        return getJdbcUrl(this.host, this.port);
    }
    
    public String getJdbcUrl(String effectiveHost, int effectivePort) {
        switch (type.toLowerCase()) {
            case "postgresql":
                return "jdbc:postgresql://" + effectiveHost + ":" + effectivePort + "/" + database;
            case "mysql":
                return "jdbc:mysql://" + effectiveHost + ":" + effectivePort + "/" + database;
            case "mariadb":
                return "jdbc:mariadb://" + effectiveHost + ":" + effectivePort + "/" + database;
            case "sqlserver":
                return "jdbc:sqlserver://" + effectiveHost + ":" + effectivePort + ";databaseName=" + database;
            default:
                throw new IllegalArgumentException("Unsupported database type: " + type);
        }
    }
}
