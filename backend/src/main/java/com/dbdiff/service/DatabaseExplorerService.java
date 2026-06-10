package com.dbdiff.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class DatabaseExplorerService {

    public List<String> getSchemas(DataSource dataSource) throws Exception {
        List<String> schemas = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             ResultSet rs = conn.getMetaData().getSchemas()) {
            while (rs.next()) {
                schemas.add(rs.getString("TABLE_SCHEM"));
            }
        }
        return schemas;
    }

    public List<Map<String, Object>> getTables(DataSource dataSource, String schema) throws Exception {
        return getObjectsByType(dataSource, schema, new String[]{"TABLE", "VIEW", "MATERIALIZED VIEW", "SYSTEM TABLE", "SYSTEM VIEW"});
    }

    public List<Map<String, Object>> getViews(DataSource dataSource, String schema) throws Exception {
        return getObjectsByType(dataSource, schema, new String[]{"VIEW", "SYSTEM VIEW"});
    }

    private List<Map<String, Object>> getObjectsByType(DataSource dataSource, String schema, String[] types) throws Exception {
        List<Map<String, Object>> objects = new ArrayList<>();
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData metaData = conn.getMetaData();
            try (ResultSet rs = metaData.getTables(null, schema, "%", types)) {
                while (rs.next()) {
                    String tName = rs.getString("TABLE_NAME");
                    // Exclude internal temporary excel import tables (case-insensitive)
                    if (tName.toLowerCase().startsWith("excel_import_")) {
                        continue;
                    }
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("name", tName);
                    String rawType = rs.getString("TABLE_TYPE");
                    map.put("type", rawType);
                    // Differentiate system objects for the frontend
                    if ("SYSTEM TABLE".equals(rawType) || "SYSTEM VIEW".equals(rawType)) {
                        map.put("isSystem", true);
                    }
                    objects.add(map);
                }
            }
        }
        return objects;
    }

    public List<Map<String, Object>> getColumns(DataSource dataSource, String schema, String table) throws Exception {
        List<Map<String, Object>> columns = new ArrayList<>();
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData metaData = conn.getMetaData();
            
            // Get PKs to flag them
            List<String> pks = new ArrayList<>();
            try (ResultSet rs = metaData.getPrimaryKeys(null, schema, table)) {
                while (rs.next()) pks.add(rs.getString("COLUMN_NAME"));
            }

            // Get FKs to flag them
            List<String> fks = new ArrayList<>();
            try (ResultSet rs = metaData.getImportedKeys(null, schema, table)) {
                while (rs.next()) fks.add(rs.getString("FKCOLUMN_NAME"));
            }

            try (ResultSet rs = metaData.getColumns(null, schema, table, "%")) {
                while (rs.next()) {
                    String colName = rs.getString("COLUMN_NAME");
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("name", colName);
                    map.put("type", rs.getString("TYPE_NAME"));
                    map.put("size", rs.getInt("COLUMN_SIZE"));
                    map.put("nullable", rs.getInt("NULLABLE") == DatabaseMetaData.columnNullable);
                    map.put("defaultValue", rs.getString("COLUMN_DEF"));
                    map.put("isPk", pks.contains(colName));
                    map.put("isFk", fks.contains(colName));
                    columns.add(map);
                }
            }
        }
        return columns;
    }

    public List<Map<String, Object>> getIndexes(DataSource dataSource, String schema, String table) throws Exception {
        List<Map<String, Object>> indexes = new ArrayList<>();
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData metaData = conn.getMetaData();
            try (ResultSet rs = metaData.getIndexInfo(null, schema, table, false, false)) {
                while (rs.next()) {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("name", rs.getString("INDEX_NAME"));
                    map.put("columnName", rs.getString("COLUMN_NAME"));
                    map.put("nonUnique", rs.getBoolean("NON_UNIQUE"));
                    map.put("type", rs.getShort("TYPE"));
                    indexes.add(map);
                }
            }
        }
        return indexes;
    }

    public List<Map<String, Object>> getForeignKeys(DataSource dataSource, String schema, String table) throws Exception {
        List<Map<String, Object>> fks = new ArrayList<>();
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData metaData = conn.getMetaData();
            try (ResultSet rs = metaData.getImportedKeys(null, schema, table)) {
                while (rs.next()) {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("fkName", rs.getString("FK_NAME"));
                    map.put("pkName", rs.getString("PK_NAME"));
                    map.put("pkTable", rs.getString("PKTABLE_NAME"));
                    map.put("pkColumn", rs.getString("PKCOLUMN_NAME"));
                    map.put("fkColumn", rs.getString("FKCOLUMN_NAME"));
                    fks.add(map);
                }
            }
        }
        return fks;
    }

    public String getDdl(DataSource dataSource, String schema, String table, String dbType) throws Exception {
        try {
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            String fullTableName = (schema != null && !schema.isEmpty() ? schema + "." : "") + table;
            
            if ("mysql".equalsIgnoreCase(dbType) || "mariadb".equalsIgnoreCase(dbType)) {
                List<Map<String, Object>> res = jdbc.queryForList("SHOW CREATE TABLE " + fullTableName);
                if (!res.isEmpty()) {
                    return (String) res.get(0).get("Create Table");
                }
            } else if ("postgresql".equalsIgnoreCase(dbType)) {
                // Check if it's a view or materialized view
                String relkindQuery = "SELECT relkind FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = ? AND c.relname = ?";
                List<String> relkinds = jdbc.queryForList(relkindQuery, String.class, schema, table);
                if (!relkinds.isEmpty()) {
                    String kind = relkinds.get(0);
                    if ("v".equals(kind) || "m".equals(kind)) {
                        String def = jdbc.queryForObject("SELECT pg_get_viewdef(?::regclass, true)", String.class, fullTableName);
                        return (kind.equals("m") ? "CREATE MATERIALIZED VIEW " : "CREATE VIEW ") + fullTableName + " AS\n" + def;
                    }
                }
                
                // Fallback to basic CREATE TABLE generation
                StringBuilder ddl = new StringBuilder("CREATE TABLE " + fullTableName + " (\n");
                List<Map<String, Object>> cols = jdbc.queryForList(
                    "SELECT column_name, data_type, character_maximum_length, column_default, is_nullable " +
                    "FROM information_schema.columns WHERE table_schema = ? AND table_name = ? ORDER BY ordinal_position", 
                    schema, table
                );
                
                if (cols.isEmpty()) return "-- Table not found or no columns accessible.";
                
                for (int i = 0; i < cols.size(); i++) {
                    Map<String, Object> col = cols.get(i);
                    String colName = (String) col.get("column_name");
                    String type = (String) col.get("data_type");
                    Integer maxLen = (Integer) col.get("character_maximum_length");
                    String def = (String) col.get("column_default");
                    String nullable = (String) col.get("is_nullable");
                    
                    ddl.append("    ").append(colName).append(" ").append(type);
                    if (maxLen != null && maxLen > 0) ddl.append("(").append(maxLen).append(")");
                    if ("NO".equalsIgnoreCase(nullable)) ddl.append(" NOT NULL");
                    if (def != null) ddl.append(" DEFAULT ").append(def);
                    
                    if (i < cols.size() - 1) ddl.append(",");
                    ddl.append("\n");
                }
                ddl.append(");\n");
                return ddl.toString();
            }
            return "-- DDL extraction not fully supported for this database type (" + dbType + ") via generic JDBC.\n-- Check table details for structure.";
        } catch (Exception e) {
            return "-- Failed to extract DDL: " + e.getMessage();
        }
    }

    public List<Map<String, Object>> getStats(DataSource dataSource, String schema, String table, String dbType) throws Exception {
        List<Map<String, Object>> statsList = new ArrayList<>();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        try {
            String q = "\"";
            String fullTableName = (schema != null && !schema.isEmpty() ? q + schema + q + "." : "") + q + table + q;
            
            // 1. Basic Row Count (Always available)
            Long count = jdbc.queryForObject("SELECT COUNT(*) FROM " + fullTableName, Long.class);
            addStat(statsList, "total_rows", count);
            
            if ("postgresql".equalsIgnoreCase(dbType)) {
                // PostgreSQL Specific Stats
                String pgSchema = (schema == null || schema.isEmpty()) ? "public" : schema;
                
                // Identify object type (Table, View, MatView)
                String typeQuery = "SELECT relkind FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = ? AND c.relname = ?";
                List<String> relkinds = jdbc.queryForList(typeQuery, String.class, pgSchema, table);
                String relkind = relkinds.isEmpty() ? "r" : relkinds.get(0);
                
                String typeLabel = "Table";
                boolean isVirtual = false;
                if ("v".equals(relkind)) { typeLabel = "Virtual View"; isVirtual = true; }
                else if ("m".equals(relkind)) { typeLabel = "Materialized View"; }
                else if ("f".equals(relkind)) { typeLabel = "Foreign Table"; }
                
                addStat(statsList, "object_type", typeLabel);
                if (isVirtual) {
                    addStat(statsList, "storage_note", "Views are virtual and do not occupy disk space.");
                }

                // Sizes
                String sizeQuery = 
                    "SELECT pg_size_pretty(pg_total_relation_size(?)) as total_size, " +
                    "       pg_size_pretty(pg_relation_size(?)) as data_size, " +
                    "       pg_size_pretty(pg_indexes_size(?)) as index_size";
                
                try {
                    Map<String, Object> sizes = jdbc.queryForMap(sizeQuery, fullTableName, fullTableName, fullTableName);
                    addStat(statsList, "total_size_on_disk", sizes.get("total_size"));
                    addStat(statsList, "data_size", sizes.get("data_size"));
                    addStat(statsList, "index_size", sizes.get("index_size"));
                } catch (Exception e) { /* ignore size errors */ }

                // Maintenance & Health
                String healthQuery = "SELECT * FROM pg_stat_user_tables WHERE schemaname = ? AND relname = ?";
                List<Map<String, Object>> healthResults = jdbc.queryForList(healthQuery, pgSchema, table);
                if (!healthResults.isEmpty()) {
                    Map<String, Object> h = healthResults.get(0);
                    addStat(statsList, "live_rows", h.get("n_live_tup"));
                    addStat(statsList, "dead_rows_bloat", h.get("n_dead_tup"));
                    addStat(statsList, "sequential_scans", h.get("seq_scan"));
                    addStat(statsList, "index_scans", h.get("idx_scan"));
                    addStat(statsList, "last_vacuum", h.get("last_vacuum"));
                    addStat(statsList, "last_analyze", h.get("last_analyze"));
                }
            } else if ("mysql".equalsIgnoreCase(dbType) || "mariadb".equalsIgnoreCase(dbType)) {
                // MySQL Specific Stats from information_schema
                String mysqlSchemaQuery = "SELECT * FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?";
                List<Map<String, Object>> results = jdbc.queryForList(mysqlSchemaQuery, table);
                
                if (!results.isEmpty()) {
                    Map<String, Object> r = results.get(0);
                    String tableType = (String) r.get("TABLE_TYPE");
                    addStat(statsList, "object_type", "VIEW".equalsIgnoreCase(tableType) ? "Virtual View" : "Table");
                    
                    if ("VIEW".equalsIgnoreCase(tableType)) {
                        addStat(statsList, "storage_note", "MySQL Views are virtual; sizes shown are from metadata.");
                    }

                    addStat(statsList, "engine", r.get("ENGINE"));
                    addStat(statsList, "version", r.get("VERSION"));
                    addStat(statsList, "row_format", r.get("ROW_FORMAT"));
                    
                    addStat(statsList, "data_length", formatBytes(r.get("DATA_LENGTH")));
                    addStat(statsList, "index_length", formatBytes(r.get("index_length")));
                    addStat(statsList, "data_free_fragmentation", formatBytes(r.get("DATA_FREE")));
                    
                    addStat(statsList, "create_time", r.get("CREATE_TIME"));
                    addStat(statsList, "update_time", r.get("UPDATE_TIME"));
                    addStat(statsList, "table_collation", r.get("TABLE_COLLATION"));
                }
            }
        } catch (Exception e) {
            addStat(statsList, "error", e.getMessage());
        }
        return statsList;
    }

    private void addStat(List<Map<String, Object>> list, String name, Object value) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", name);
        map.put("value", value != null ? value.toString() : "—");
        list.add(map);
    }

    private String formatBytes(Object bytesObj) {
        if (bytesObj == null) return "0 B";
        try {
            long bytes = Long.parseLong(bytesObj.toString());
            if (bytes < 1024) return bytes + " B";
            int exp = (int) (Math.log(bytes) / Math.log(1024));
            char pre = "KMGTPE".charAt(exp - 1);
            return String.format("%.2f %cB", bytes / Math.pow(1024, exp), pre);
        } catch (Exception e) {
            return bytesObj.toString();
        }
    }

    public List<Map<String, Object>> previewData(DataSource dataSource, String schema, String table) throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        String q = "\"";
        String fullTableName = (schema != null && !schema.isEmpty() ? q + schema + q + "." : "") + q + table + q;
        String sql = "SELECT * FROM " + fullTableName;
        jdbc.setMaxRows(200);
        return jdbc.queryForList(sql);
    }
}
