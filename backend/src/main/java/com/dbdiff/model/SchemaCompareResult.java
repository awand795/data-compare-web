package com.dbdiff.model;

import java.util.List;

public class SchemaCompareResult {
    private String tableName;
    private String status; // IDENTICAL, DIFFERENT, SOURCE_ONLY, TARGET_ONLY
    private List<ColumnDiff> columnDiffs;

    public String getTableName() { return this.tableName; }
    public void setTableName(String tableName) { this.tableName = tableName; }

    public String getStatus() { return this.status; }
    public void setStatus(String status) { this.status = status; }

    public List<ColumnDiff> getColumnDiffs() { return this.columnDiffs; }
    public void setColumnDiffs(List<ColumnDiff> columnDiffs) { this.columnDiffs = columnDiffs; }

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

        public String getColumnName() { return this.columnName; }
        public void setColumnName(String columnName) { this.columnName = columnName; }

        public String getStatus() { return this.status; }
        public void setStatus(String status) { this.status = status; }

        public String getSourceType() { return this.sourceType; }
        public void setSourceType(String sourceType) { this.sourceType = sourceType; }

        public String getTargetType() { return this.targetType; }
        public void setTargetType(String targetType) { this.targetType = targetType; }

        public String getSourceNullable() { return this.sourceNullable; }
        public void setSourceNullable(String sourceNullable) { this.sourceNullable = sourceNullable; }

        public String getTargetNullable() { return this.targetNullable; }
        public void setTargetNullable(String targetNullable) { this.targetNullable = targetNullable; }

        public Integer getSourceSize() { return this.sourceSize; }
        public void setSourceSize(Integer sourceSize) { this.sourceSize = sourceSize; }

        public Integer getTargetSize() { return this.targetSize; }
        public void setTargetSize(Integer targetSize) { this.targetSize = targetSize; }

        public boolean isPrimaryKeySource() { return this.isPrimaryKeySource; }
        public void setPrimaryKeySource(boolean isPrimaryKeySource) { this.isPrimaryKeySource = isPrimaryKeySource; }

        public boolean isPrimaryKeyTarget() { return this.isPrimaryKeyTarget; }
        public void setPrimaryKeyTarget(boolean isPrimaryKeyTarget) { this.isPrimaryKeyTarget = isPrimaryKeyTarget; }
    }
}
