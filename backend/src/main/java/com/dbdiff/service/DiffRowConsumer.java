package com.dbdiff.service;

import com.dbdiff.model.DiffRow;
import java.util.List;

public interface DiffRowConsumer {
    void onColumns(List<String> columns) throws Exception;
    void onRow(DiffRow row) throws Exception;
    void onTotals(int totalSource, int totalTarget, int totalDiffs) throws Exception;
    
    // Fast path for MATCH rows to bypass DiffRow, DiffCell, LinkedHashMap allocations
    default void onMatchRow(String key, Object[] values, List<String> columns) throws Exception {
        DiffRow row = new DiffRow();
        row.setRowKey(key);
        row.setStatus(DiffRow.Status.MATCH);
        java.util.Map<String, com.dbdiff.model.DiffCell> cells = new java.util.LinkedHashMap<>(columns.size());
        for (int i = 0; i < columns.size(); i++) {
            cells.put(columns.get(i), new com.dbdiff.model.DiffCell(values[i], values[i], false));
        }
        row.setCells(cells);
        onRow(row);
    }
}
