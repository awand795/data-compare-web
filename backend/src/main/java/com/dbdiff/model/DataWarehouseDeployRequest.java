package com.dbdiff.model;

public class DataWarehouseDeployRequest {
    private ConnectionDetails sourceConnection;
    private java.util.List<ConnectionDetails> sourceConnections;
    private ConnectionDetails targetConnection;
    private String query;
    private String targetTable;
    private String primaryKeys;
    private String targetDatabase;
    private String targetSchema;
    private String deployId;

    public java.util.List<ConnectionDetails> getSourceConnections() {
        if (sourceConnections == null || sourceConnections.isEmpty()) {
            if (sourceConnection != null) {
                return java.util.Collections.singletonList(sourceConnection);
            }
            return java.util.Collections.emptyList();
        }
        return sourceConnections;
    }

    public void setSourceConnections(java.util.List<ConnectionDetails> sourceConnections) {
        this.sourceConnections = sourceConnections;
        if (sourceConnections != null && !sourceConnections.isEmpty() && this.sourceConnection == null) {
            this.sourceConnection = sourceConnections.get(0);
        }
    }

    public String getTargetDatabase() {
        return targetDatabase;
    }

    public String getTargetSchema() {
        return targetSchema;
    }

    public void setTargetSchema(String targetSchema) {
        this.targetSchema = targetSchema;
    }

    public String getDeployId() { return deployId; }
    public void setDeployId(String deployId) { this.deployId = deployId; }

    public void setTargetDatabase(String targetDatabase) {
        this.targetDatabase = targetDatabase;
    }

    public String getPrimaryKeys() {
        return primaryKeys;
    }

    public void setPrimaryKeys(String primaryKeys) {
        this.primaryKeys = primaryKeys;
    }

    public ConnectionDetails getSourceConnection() {
        if (sourceConnection == null && sourceConnections != null && !sourceConnections.isEmpty()) {
            return sourceConnections.get(0);
        }
        return sourceConnection;
    }

    public void setSourceConnection(ConnectionDetails sourceConnection) {
        this.sourceConnection = sourceConnection;
    }

    public ConnectionDetails getTargetConnection() {
        return targetConnection;
    }

    public void setTargetConnection(ConnectionDetails targetConnection) {
        this.targetConnection = targetConnection;
    }

    public String getQuery() {
        return query;
    }

    public void setQuery(String query) {
        this.query = query;
    }

    public String getTargetTable() {
        return targetTable;
    }

    public void setTargetTable(String targetTable) {
        this.targetTable = targetTable;
    }
}
