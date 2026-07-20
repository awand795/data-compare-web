package com.dbdiff.model;

public class DataWarehouseDeployRequest {
    private ConnectionDetails sourceConnection;
    private ConnectionDetails targetConnection;
    private String query;
    private String targetTable;
    private String primaryKeys;
    private String targetDatabase;

    public String getTargetDatabase() {
        return targetDatabase;
    }

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
