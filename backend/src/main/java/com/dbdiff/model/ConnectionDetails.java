package com.dbdiff.model;

public class ConnectionDetails {
    private String id;
    private String name;
    private String type; // postgresql, mysql, mariadb, sqlserver, clickhouse
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
    private boolean sshStrictHostKeyChecking = true; // default true untuk keamanan
    private Integer sshLocalPort;

    // Advanced Settings
    private Integer connectionTimeout;
    private Integer socketTimeout;
    private Integer fetchSize;
    private boolean readOnly;
    private String extraProps;
    private boolean enableDataWarehouse;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ConnectionDetails that = (ConnectionDetails) o;
        return id != null ? id.equals(that.id) : that.id == null;
    }

    @Override
    public int hashCode() {
        return id != null ? id.hashCode() : 0;
    }

    public String getStableIdentifier() {
        if (id != null && !id.isBlank()) {
            return id;
        }
        String safeType = type != null ? type.toLowerCase() : "";
        String safeUser = username != null ? username : "";
        String safeHost = host != null ? host : "";
        String safeDb = database != null ? database : "";
        String safeSshUser = sshUsername != null ? sshUsername : "";
        String safeSshHost = sshHost != null ? sshHost : "";
        int safeSshPort = sshPort != null ? sshPort : 22;
        
        return safeType + "://" + safeUser + "@" + safeHost + ":" + port + "/" + safeDb 
             + (useSsh ? "[ssh:" + safeSshUser + "@" + safeSshHost + ":" + safeSshPort + "]" : "");
    }

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
            case "clickhouse":
                return "jdbc:ch://" + effectiveHost + ":" + effectivePort + "/" + database;
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
    @com.fasterxml.jackson.annotation.JsonProperty(access = com.fasterxml.jackson.annotation.JsonProperty.Access.WRITE_ONLY)
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
    @com.fasterxml.jackson.annotation.JsonProperty(access = com.fasterxml.jackson.annotation.JsonProperty.Access.WRITE_ONLY)
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
    @com.fasterxml.jackson.annotation.JsonProperty(access = com.fasterxml.jackson.annotation.JsonProperty.Access.WRITE_ONLY)
    public String getSshPassphrase() {
        return this.sshPassphrase;
    }
    public void setSshPassphrase(String sshPassphrase) {
        this.sshPassphrase = sshPassphrase;
    }
    public boolean isSshStrictHostKeyChecking() {
        return this.sshStrictHostKeyChecking;
    }
    public void setSshStrictHostKeyChecking(boolean sshStrictHostKeyChecking) {
        this.sshStrictHostKeyChecking = sshStrictHostKeyChecking;
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
    public boolean isEnableDataWarehouse() {
        return this.enableDataWarehouse;
    }
    public void setEnableDataWarehouse(boolean enableDataWarehouse) {
        this.enableDataWarehouse = enableDataWarehouse;
    }
}
