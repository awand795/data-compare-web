package com.dbdiff.model;

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
        return getJdbcUrl(effectiveHost, effectivePort, this.database);
    }

    public String getJdbcUrl(String effectiveHost, int effectivePort, String dbName) {
        if (dbName == null || dbName.isBlank()) {
            // Browse mode — connect to system database without a specific database
            switch (type.toLowerCase()) {
                case "postgresql":
                    return "jdbc:postgresql://" + effectiveHost + ":" + effectivePort + "/postgres";
                case "mysql":
                    return "jdbc:mysql://" + effectiveHost + ":" + effectivePort + "/";
                case "mariadb":
                    return "jdbc:mariadb://" + effectiveHost + ":" + effectivePort + "/";
                case "sqlserver":
                    return "jdbc:sqlserver://" + effectiveHost + ":" + effectivePort + ";databaseName=master";
                default:
                    throw new IllegalArgumentException("Unsupported database type: " + type);
            }
        }
        switch (type.toLowerCase()) {
            case "postgresql":
                return "jdbc:postgresql://" + effectiveHost + ":" + effectivePort + "/" + dbName;
            case "mysql":
                return "jdbc:mysql://" + effectiveHost + ":" + effectivePort + "/" + dbName;
            case "mariadb":
                return "jdbc:mariadb://" + effectiveHost + ":" + effectivePort + "/" + dbName;
            case "sqlserver":
                return "jdbc:sqlserver://" + effectiveHost + ":" + effectivePort + ";databaseName=" + dbName;
            default:
                throw new IllegalArgumentException("Unsupported database type: " + type);
        }
    }

    public String getId() {
        return this.id;
    }
    public void setId(String id) {
        this.id = id;
    }
    public String getName() {
        return this.name;
    }
    public void setName(String name) {
        this.name = name;
    }
    public String getType() {
        return this.type;
    }
    public void setType(String type) {
        this.type = type;
    }
    public String getHost() {
        return this.host;
    }
    public void setHost(String host) {
        this.host = host;
    }
    public int getPort() {
        return this.port;
    }
    public void setPort(int port) {
        this.port = port;
    }
    public String getDatabase() {
        return this.database;
    }
    public void setDatabase(String database) {
        this.database = database;
    }
    public String getUsername() {
        return this.username;
    }
    public void setUsername(String username) {
        this.username = username;
    }
    public String getPassword() {
        return this.password;
    }
    public void setPassword(String password) {
        this.password = password;
    }
    public String getSchema() {
        return this.schema;
    }
    public void setSchema(String schema) {
        this.schema = schema;
    }
    public String getSslMode() {
        return this.sslMode;
    }
    public void setSslMode(String sslMode) {
        this.sslMode = sslMode;
    }
    public String getSslCaFile() {
        return this.sslCaFile;
    }
    public void setSslCaFile(String sslCaFile) {
        this.sslCaFile = sslCaFile;
    }
    public String getSslCertFile() {
        return this.sslCertFile;
    }
    public void setSslCertFile(String sslCertFile) {
        this.sslCertFile = sslCertFile;
    }
    public String getSslKeyFile() {
        return this.sslKeyFile;
    }
    public void setSslKeyFile(String sslKeyFile) {
        this.sslKeyFile = sslKeyFile;
    }
    public boolean isUseSsh() {
        return this.useSsh;
    }
    public void setUseSsh(boolean useSsh) {
        this.useSsh = useSsh;
    }
    public String getSshHost() {
        return this.sshHost;
    }
    public void setSshHost(String sshHost) {
        this.sshHost = sshHost;
    }
    public Integer getSshPort() {
        return this.sshPort;
    }
    public void setSshPort(Integer sshPort) {
        this.sshPort = sshPort;
    }
    public String getSshUsername() {
        return this.sshUsername;
    }
    public void setSshUsername(String sshUsername) {
        this.sshUsername = sshUsername;
    }
    public String getSshAuthMode() {
        return this.sshAuthMode;
    }
    public void setSshAuthMode(String sshAuthMode) {
        this.sshAuthMode = sshAuthMode;
    }
    public String getSshPassword() {
        return this.sshPassword;
    }
    public void setSshPassword(String sshPassword) {
        this.sshPassword = sshPassword;
    }
    public String getSshKeyFile() {
        return this.sshKeyFile;
    }
    public void setSshKeyFile(String sshKeyFile) {
        this.sshKeyFile = sshKeyFile;
    }
    public String getSshPassphrase() {
        return this.sshPassphrase;
    }
    public void setSshPassphrase(String sshPassphrase) {
        this.sshPassphrase = sshPassphrase;
    }
    public Integer getSshLocalPort() {
        return this.sshLocalPort;
    }
    public void setSshLocalPort(Integer sshLocalPort) {
        this.sshLocalPort = sshLocalPort;
    }
    public Integer getConnectionTimeout() {
        return this.connectionTimeout;
    }
    public void setConnectionTimeout(Integer connectionTimeout) {
        this.connectionTimeout = connectionTimeout;
    }
    public Integer getSocketTimeout() {
        return this.socketTimeout;
    }
    public void setSocketTimeout(Integer socketTimeout) {
        this.socketTimeout = socketTimeout;
    }
    public Integer getFetchSize() {
        return this.fetchSize;
    }
    public void setFetchSize(Integer fetchSize) {
        this.fetchSize = fetchSize;
    }
    public boolean isReadOnly() {
        return this.readOnly;
    }
    public void setReadOnly(boolean readOnly) {
        this.readOnly = readOnly;
    }
    public String getExtraProps() {
        return this.extraProps;
    }
    public void setExtraProps(String extraProps) {
        this.extraProps = extraProps;
    }
}
