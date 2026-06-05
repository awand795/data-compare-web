package com.dbdiff.model;




public class SchemaCompareRequest {
    private ConnectionDetails sourceConnection;
    private ConnectionDetails targetConnection;
    private String tableName; // optional - null for compare-all

    public ConnectionDetails getSourceConnection() { return this.sourceConnection; }
    public void setSourceConnection(ConnectionDetails sourceConnection) { this.sourceConnection = sourceConnection; }
    public ConnectionDetails getTargetConnection() { return this.targetConnection; }
    public void setTargetConnection(ConnectionDetails targetConnection) { this.targetConnection = targetConnection; }
    public String getTableName() { return this.tableName; }
    public void setTableName(String tableName) { this.tableName = tableName; }
}
