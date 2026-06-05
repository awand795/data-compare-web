package com.dbdiff.model;

import java.util.List;

public class DiffRequest {
    private ConnectionDetails sourceConnection;
    private ConnectionDetails targetConnection;
    private String tableName; // if null, use customQuery
    private String customQuerySource;
    private String customQueryTarget;
    private List<String> primaryKeys;    // Needed to match rows
    private List<String> excludeColumns; // Columns to ignore in diff
    private List<String> sortColumns;    // Columns to sort by when no PK is present
    private boolean returnMatchedRows = true; // If false, skips sending MATCH rows

    public ConnectionDetails getSourceConnection() { return this.sourceConnection; }
    public void setSourceConnection(ConnectionDetails sourceConnection) { this.sourceConnection = sourceConnection; }
    
    public ConnectionDetails getTargetConnection() { return this.targetConnection; }
    public void setTargetConnection(ConnectionDetails targetConnection) { this.targetConnection = targetConnection; }
    
    public String getTableName() { return this.tableName; }
    public void setTableName(String tableName) { this.tableName = tableName; }
    
    public String getCustomQuerySource() { return this.customQuerySource; }
    public void setCustomQuerySource(String customQuerySource) { this.customQuerySource = customQuerySource; }
    
    public String getCustomQueryTarget() { return this.customQueryTarget; }
    public void setCustomQueryTarget(String customQueryTarget) { this.customQueryTarget = customQueryTarget; }
    
    public List<String> getPrimaryKeys() { return this.primaryKeys; }
    public void setPrimaryKeys(List<String> primaryKeys) { this.primaryKeys = primaryKeys; }
    
    public List<String> getExcludeColumns() { return this.excludeColumns; }
    public void setExcludeColumns(List<String> excludeColumns) { this.excludeColumns = excludeColumns; }
    
    public List<String> getSortColumns() { return this.sortColumns; }
    public void setSortColumns(List<String> sortColumns) { this.sortColumns = sortColumns; }
    
    public boolean isReturnMatchedRows() { return this.returnMatchedRows; }
    public void setReturnMatchedRows(boolean returnMatchedRows) { this.returnMatchedRows = returnMatchedRows; }
}
