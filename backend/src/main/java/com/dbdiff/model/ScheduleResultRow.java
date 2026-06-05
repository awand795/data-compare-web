package com.dbdiff.model;

public class ScheduleResultRow {
    private Long id;
    private String resultId;
    private String rowKey;
    private String status;
    private String dataJson;
    private String tableName;

    public Long getId() {
        return this.id;
    }
    public void setId(Long id) {
        this.id = id;
    }
    public String getResultId() {
        return this.resultId;
    }
    public void setResultId(String resultId) {
        this.resultId = resultId;
    }
    public String getRowKey() {
        return this.rowKey;
    }
    public void setRowKey(String rowKey) {
        this.rowKey = rowKey;
    }
    public String getStatus() {
        return this.status;
    }
    public void setStatus(String status) {
        this.status = status;
    }
    public String getDataJson() {
        return this.dataJson;
    }
    public void setDataJson(String dataJson) {
        this.dataJson = dataJson;
    }
    public String getTableName() {
        return this.tableName;
    }
    public void setTableName(String tableName) {
        this.tableName = tableName;
    }
}
