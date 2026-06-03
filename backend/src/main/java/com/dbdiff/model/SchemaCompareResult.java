package com.dbdiff.model;

import lombok.Data;
import java.util.List;

@Data
public class SchemaCompareResult {
    private String tableName;
    private String status; // IDENTICAL, DIFFERENT, SOURCE_ONLY, TARGET_ONLY
    private List<ColumnDiff> columnDiffs;
    
    @Data
    public static class ColumnDiff {
        private String columnName;
        private String status; // IDENTICAL, DIFFERENT, SOURCE_ONLY, TARGET_ONLY
        private String sourceType;
        private String targetType;
        private String sourceNullable;
        private String targetNullable;
        private Integer sourceSize;
        private Integer targetSize;
        private boolean isPrimaryKeySource;
        private boolean isPrimaryKeyTarget;
    }
}
