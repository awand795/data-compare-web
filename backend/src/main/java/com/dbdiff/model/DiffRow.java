package com.dbdiff.model;

import lombok.Data;
import java.util.Map;

@Data
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
}
