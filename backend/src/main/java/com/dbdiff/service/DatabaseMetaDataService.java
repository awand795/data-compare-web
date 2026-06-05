package com.dbdiff.service;

import com.dbdiff.model.SchemaCompareResult;
import com.dbdiff.model.SchemaCompareResult.ColumnDiff;
import com.dbdiff.model.TableInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.*;

@Service
public class DatabaseMetaDataService {

    private static final Logger logger = LoggerFactory.getLogger(DatabaseMetaDataService.class);

    public List<TableInfo> getTables(DataSource dataSource, String expectedSchema) {
        List<TableInfo> tables = new ArrayList<>();
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData metaData = conn.getMetaData();
            String schemaPattern = (expectedSchema != null && !expectedSchema.trim().isEmpty()) ? expectedSchema.trim() : null;
            
            try (ResultSet rs = metaData.getTables(null, schemaPattern, "%", new String[]{"TABLE", "VIEW", "MATERIALIZED VIEW"})) {
                while (rs.next()) {
                    String s = rs.getString("TABLE_SCHEM");
                    String tName = rs.getString("TABLE_NAME");
                    
                    // Exclude internal temporary excel import tables (case-insensitive)
                    if (tName.toLowerCase().startsWith("excel_import_")) {
                        continue;
                    }

                    // If schemaPattern is null, prepend schema to table name for uniqueness
                    String displayName = (schemaPattern == null && s != null && !s.equals("public")) ? s + "." + tName : tName;
                    tables.add(new TableInfo(displayName, rs.getString("TABLE_TYPE")));
                }
            }
        } catch (Exception e) {
            logger.error("Failed to get tables: {}", e.getMessage(), e);
        }
        return tables;
    }

    public List<String> getPrimaryKeys(DataSource dataSource, String tableName, String expectedSchema) {
        List<String> pks = new ArrayList<>();
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData metaData = conn.getMetaData();
            String schemaPattern = (expectedSchema != null && !expectedSchema.trim().isEmpty()) ? expectedSchema.trim() : null;
            
            // Handle schema-qualified table names like "mbi.users"
            String actualTable = tableName;
            if (schemaPattern == null && tableName.contains(".")) {
                String[] parts = tableName.split("\\.", 2);
                schemaPattern = parts[0];
                actualTable = parts[1];
            }

            try (ResultSet rs = metaData.getPrimaryKeys(null, schemaPattern, actualTable)) {
                while (rs.next()) {
                    pks.add(rs.getString("COLUMN_NAME"));
                }
            }
        } catch (Exception e) {
            logger.error("Failed to get primary keys for {}: {}", tableName, e.getMessage(), e);
        }
        return pks;
    }
    
    public List<String> getColumns(DataSource dataSource, String tableName, String expectedSchema) {
        List<String> columns = new ArrayList<>();
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData metaData = conn.getMetaData();
            String schemaPattern = (expectedSchema != null && !expectedSchema.trim().isEmpty()) ? expectedSchema.trim() : null;
            
            String actualTable = tableName;
            if (schemaPattern == null && tableName.contains(".")) {
                String[] parts = tableName.split("\\.", 2);
                schemaPattern = parts[0];
                actualTable = parts[1];
            }

            try (ResultSet rs = metaData.getColumns(null, schemaPattern, actualTable, "%")) {
                while (rs.next()) {
                    columns.add(rs.getString("COLUMN_NAME"));
                }
            }
        } catch (Exception e) {
            logger.error("Failed to get columns for {}: {}", tableName, e.getMessage(), e);
        }
        return columns;
    }

    /**
     * Returns detailed column info for a single table: name, type, size, nullable, isPK.
     */
    public List<ColumnDiff> getDetailedTableInfo(DataSource dataSource, String tableName, String expectedSchema) {
        List<ColumnDiff> columnInfos = new ArrayList<>();
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData metaData = conn.getMetaData();
            String schemaPattern = (expectedSchema != null && !expectedSchema.trim().isEmpty()) ? expectedSchema.trim() : null;
            
            String actualTable = tableName;
            if (schemaPattern == null && tableName.contains(".")) {
                String[] parts = tableName.split("\\.", 2);
                schemaPattern = parts[0];
                actualTable = parts[1];
            }

            // Collect primary keys first
            Set<String> pkColumns = new HashSet<>(getPrimaryKeys(dataSource, actualTable, schemaPattern));

            // Collect column details
            try (ResultSet rs = metaData.getColumns(null, schemaPattern, actualTable, "%")) {
                while (rs.next()) {
                    ColumnDiff col = new ColumnDiff();
                    col.setColumnName(rs.getString("COLUMN_NAME"));
                    col.setSourceType(rs.getString("TYPE_NAME"));
                    col.setSourceNullable(rs.getInt("NULLABLE") == DatabaseMetaData.columnNullable ? "YES" : "NO");
                    col.setSourceSize(rs.getInt("COLUMN_SIZE"));
                    col.setPrimaryKeySource(pkColumns.contains(col.getColumnName()));
                    col.setStatus("IDENTICAL"); // default when used standalone
                    columnInfos.add(col);
                }
            }
        } catch (Exception e) {
            logger.error("Failed to get detailed table info for {}: {}", tableName, e.getMessage(), e);
        }
        return columnInfos;
    }

    /**
     * Compares schema of a single table between two DataSources.
     * Returns a SchemaCompareResult showing which columns differ.
     */
    public SchemaCompareResult compareSchema(DataSource sourceDs, String sourceTable, String sourceSchema, DataSource targetDs, String targetTable, String targetSchema) {
        SchemaCompareResult result = new SchemaCompareResult();
        
        List<ColumnDiff> sourceCols = getDetailedTableInfo(sourceDs, sourceTable, sourceSchema);
        List<ColumnDiff> targetCols = getDetailedTableInfo(targetDs, targetTable, targetSchema);
        Map<String, ColumnDiff> sourceColumns = new LinkedHashMap<>();
        for(ColumnDiff c : sourceCols) sourceColumns.put(c.getColumnName(), c);
        Map<String, ColumnDiff> targetColumns = new LinkedHashMap<>();
        for(ColumnDiff c : targetCols) targetColumns.put(c.getColumnName(), c);

        List<ColumnDiff> columnDiffs = new ArrayList<>();
        boolean hasAnyDiff = false;

        // Collect all column names preserving source order first
        Set<String> allColumnNames = new LinkedHashSet<>();
        allColumnNames.addAll(sourceColumns.keySet());
        allColumnNames.addAll(targetColumns.keySet());

        for (String colName : allColumnNames) {
            ColumnDiff sourceCol = sourceColumns.get(colName);
            ColumnDiff targetCol = targetColumns.get(colName);

            ColumnDiff diff = new ColumnDiff();
            diff.setColumnName(colName);

            if (sourceCol != null && targetCol != null) {
                // Column exists in both - populate both sides
                diff.setSourceType(sourceCol.getSourceType());
                diff.setTargetType(targetCol.getSourceType());
                diff.setSourceNullable(sourceCol.getSourceNullable());
                diff.setTargetNullable(targetCol.getSourceNullable());
                diff.setSourceSize(sourceCol.getSourceSize());
                diff.setTargetSize(targetCol.getSourceSize());
                diff.setPrimaryKeySource(sourceCol.isPrimaryKeySource());
                diff.setPrimaryKeyTarget(targetCol.isPrimaryKeySource());

                // Check if anything differs
                boolean isDiff = !Objects.equals(diff.getSourceType(), diff.getTargetType())
                        || !Objects.equals(diff.getSourceNullable(), diff.getTargetNullable())
                        || !Objects.equals(diff.getSourceSize(), diff.getTargetSize())
                        || diff.isPrimaryKeySource() != diff.isPrimaryKeyTarget();

                diff.setStatus(isDiff ? "DIFFERENT" : "IDENTICAL");
                if (isDiff) hasAnyDiff = true;
            } else if (sourceCol != null) {
                diff.setSourceType(sourceCol.getSourceType());
                diff.setSourceNullable(sourceCol.getSourceNullable());
                diff.setSourceSize(sourceCol.getSourceSize());
                diff.setPrimaryKeySource(sourceCol.isPrimaryKeySource());
                diff.setStatus("SOURCE_ONLY");
                hasAnyDiff = true;
            } else {
                diff.setTargetType(targetCol.getSourceType());
                diff.setTargetNullable(targetCol.getSourceNullable());
                diff.setTargetSize(targetCol.getSourceSize());
                diff.setPrimaryKeyTarget(targetCol.isPrimaryKeySource());
                diff.setStatus("TARGET_ONLY");
                hasAnyDiff = true;
            }

            columnDiffs.add(diff);
        }

        result.setColumnDiffs(columnDiffs);
        result.setStatus(hasAnyDiff ? "DIFFERENT" : "IDENTICAL");

        return result;
    }

    /**
     * Helper: builds an ordered map of column name -> ColumnDiff for a given table.
     */
    private Map<String, ColumnDiff> getColumnMap(DataSource dataSource, String tableName) {
        Map<String, ColumnDiff> map = new LinkedHashMap<>();
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData metaData = conn.getMetaData();
            String catalog = conn.getCatalog();
            String schema = conn.getSchema();

            Set<String> pkColumns = new HashSet<>();
            try (ResultSet pkRs = metaData.getPrimaryKeys(catalog, schema, tableName)) {
                while (pkRs.next()) {
                    pkColumns.add(pkRs.getString("COLUMN_NAME"));
                }
            }

            try (ResultSet rs = metaData.getColumns(catalog, schema, tableName, "%")) {
                while (rs.next()) {
                    ColumnDiff col = new ColumnDiff();
                    String colName = rs.getString("COLUMN_NAME");
                    col.setColumnName(colName);
                    col.setSourceType(rs.getString("TYPE_NAME"));
                    col.setSourceNullable(rs.getInt("NULLABLE") == DatabaseMetaData.columnNullable ? "YES" : "NO");
                    col.setSourceSize(rs.getInt("COLUMN_SIZE"));
                    col.setPrimaryKeySource(pkColumns.contains(colName));
                    map.put(colName, col);
                }
            }
        } catch (Exception e) {
            logger.error("Failed to get column map for {}: {}", tableName, e.getMessage(), e);
        }
        return map;
    }
}
