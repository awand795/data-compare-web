package com.dbdiff.model;

public class DataWarehouseDeployRequest {
    private ConnectionDetails sourceConnection;
    private ConnectionDetails targetConnection;
    private String query;
    private String targetTable;

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
