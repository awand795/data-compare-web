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
        return getObjectsByType(dataSource, schema, new String[]{"TABLE", "VIEW", "MATERIALIZED VIEW"});
    }

    public List<Map<String, Object>> getViews(DataSource dataSource, String schema) throws Exception {
        return getObjectsByType(dataSource, schema, new String[]{"VIEW"});
    }

    private List<Map<String, Object>> getObjectsByType(DataSource dataSource, String schema, String[] types) throws Exception {
        List<Map<String, Object>> objects = new ArrayList<>();
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData metaData = conn.getMetaData();
            try (ResultSet rs = metaData.getTables(null, schema, "%", types)) {
                while (rs.next()) {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("name", rs.getString("TABLE_NAME"));
                    map.put("type", rs.getString("TABLE_TYPE"));
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
            try (ResultSet rs = metaData.getColumns(null, schema, table, "%")) {
                while (rs.next()) {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("name", rs.getString("COLUMN_NAME"));
                    map.put("type", rs.getString("TYPE_NAME"));
                    map.put("size", rs.getInt("COLUMN_SIZE"));
                    map.put("nullable", rs.getInt("NULLABLE") == DatabaseMetaData.columnNullable);
                    map.put("defaultValue", rs.getString("COLUMN_DEF"));
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

    public Map<String, Object> getStats(DataSource dataSource, String schema, String table, String dbType) throws Exception {
        Map<String, Object> stats = new LinkedHashMap<>();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        try {
            // Very basic count for all
            Long count = jdbc.queryForObject("SELECT COUNT(*) FROM " + (schema != null && !schema.isEmpty() ? schema + "." : "") + table, Long.class);
            stats.put("rowCount", count);
            
            if ("postgresql".equalsIgnoreCase(dbType)) {
                List<Map<String, Object>> pgStats = jdbc.queryForList("SELECT * FROM pg_stat_user_tables WHERE schemaname = ? AND relname = ?", schema, table);
                if (!pgStats.isEmpty()) {
                    stats.put("pgStats", pgStats.get(0));
                }
            }
        } catch (Exception e) {
            stats.put("error", e.getMessage());
        }
        return stats;
    }

    public List<Map<String, Object>> previewData(DataSource dataSource, String schema, String table) throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        String sql = "SELECT * FROM " + (schema != null && !schema.isEmpty() ? schema + "." : "") + table;
        jdbc.setMaxRows(200);
        return jdbc.queryForList(sql);
    }
}
