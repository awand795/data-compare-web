package com.dbdiff.model;

import java.util.Map;

public class DiffRow {
    public enum Status {
        MATCH,      // Rows exist in both and are identical
        DIFFERENT,  // Rows exist in both but have different data
        SOURCE_ONLY,// Row only exists in source
        TARGET_ONLY // Row only exists in target
    }

    private String rowKey; // Primary key string representation
    private Status status;
    private Map<String, DiffCell> cells;

    public String getRowKey() {
        return this.rowKey;
    }
    public void setRowKey(String rowKey) {
        this.rowKey = rowKey;
    }
    public Status getStatus() {
        return this.status;
    }
    public void setStatus(Status status) {
        this.status = status;
    }
    public Map<String, DiffCell> getCells() {
        return this.cells;
    }
    public void setCells(Map<String, DiffCell> cells) {
        this.cells = cells;
    }
}
