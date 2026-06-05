package com.dbdiff.model;

import java.util.List;
import java.util.Map;

public class DiffResult {
    private List<String> columns;
    private List<DiffRow> rows;
    private int totalSourceRows;
    private int totalTargetRows;
    private int totalDifferences;

    public List<String> getColumns() {
        return this.columns;
    }
    public void setColumns(List<String> columns) {
        this.columns = columns;
    }
    public List<DiffRow> getRows() {
        return this.rows;
    }
    public void setRows(List<DiffRow> rows) {
        this.rows = rows;
    }
    public int getTotalSourceRows() {
        return this.totalSourceRows;
    }
    public void setTotalSourceRows(int totalSourceRows) {
        this.totalSourceRows = totalSourceRows;
    }
    public int getTotalTargetRows() {
        return this.totalTargetRows;
    }
    public void setTotalTargetRows(int totalTargetRows) {
        this.totalTargetRows = totalTargetRows;
    }
    public int getTotalDifferences() {
        return this.totalDifferences;
    }
    public void setTotalDifferences(int totalDifferences) {
        this.totalDifferences = totalDifferences;
    }
}
