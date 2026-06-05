package com.dbdiff.model;




public class QueryRequest {
    private ConnectionDetails connection;
    private String query;

    public ConnectionDetails getConnection() { return this.connection; }
    public void setConnection(ConnectionDetails connection) { this.connection = connection; }
    public String getQuery() { return this.query; }
    public void setQuery(String query) { this.query = query; }
}
