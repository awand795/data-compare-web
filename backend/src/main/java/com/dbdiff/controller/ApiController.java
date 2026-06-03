package com.dbdiff.controller;

import com.dbdiff.model.*;
import com.dbdiff.model.SchemaCompareResult.ColumnDiff;
import com.dbdiff.service.ConnectionManagerService;
import com.dbdiff.service.DataComparisonService;
import com.dbdiff.service.DatabaseMetaDataService;
import com.dbdiff.repository.ConnectionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import javax.sql.DataSource;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*") // Allows React frontend to connect
public class ApiController {

    @Autowired
    private ConnectionManagerService connectionManagerService;

    @Autowired
    private DatabaseMetaDataService metaDataService;

    @Autowired
    private DataComparisonService comparisonService;

    @Autowired
    private ConnectionRepository connectionRepository;

    @GetMapping("/connections")
    public ResponseEntity<List<ConnectionDetails>> getConnections() {
        return ResponseEntity.ok(connectionRepository.findAll());
    }

    @PostMapping("/connections")
    public ResponseEntity<?> saveConnection(@RequestBody ConnectionDetails details) {
        connectionRepository.save(details);
        return ResponseEntity.ok(Map.of("success", true, "connection", details));
    }

    @DeleteMapping("/connections/{id}")
    public ResponseEntity<?> deleteConnection(@PathVariable String id) {
        connectionRepository.deleteById(id);
        return ResponseEntity.ok(Map.of("success", true));
    }

    @PostMapping("/test-connection")
    public ResponseEntity<?> testConnection(@RequestBody ConnectionDetails details) {
        boolean isValid = connectionManagerService.testConnection(details);
        return ResponseEntity.ok(Map.of("success", isValid));
    }

    @PostMapping("/tables")
    public ResponseEntity<List<TableInfo>> getTables(@RequestBody ConnectionDetails details) {
        DataSource ds = connectionManagerService.getDataSource(details);
        List<TableInfo> tables = metaDataService.getTables(ds, details.getSchema());
        return ResponseEntity.ok(tables);
    }
    
    @PostMapping("/columns")
    public ResponseEntity<List<String>> getColumns(@RequestBody Map<String, Object> payload) {
        // Simple mapping, normally use proper DTO
        ConnectionDetails details = mapToDetails((Map<String, Object>) payload.get("connection"));
        String tableName = (String) payload.get("tableName");
        
        DataSource ds = connectionManagerService.getDataSource(details);
        List<String> columns = metaDataService.getColumns(ds, tableName, details.getSchema());
        return ResponseEntity.ok(columns);
    }
    
    @PostMapping("/primary-keys")
    public ResponseEntity<List<String>> getPrimaryKeys(@RequestBody Map<String, Object> payload) {
        ConnectionDetails details = mapToDetails((Map<String, Object>) payload.get("connection"));
        String tableName = (String) payload.get("tableName");
        
        DataSource ds = connectionManagerService.getDataSource(details);
        List<String> keys = metaDataService.getPrimaryKeys(ds, tableName, details.getSchema());
        return ResponseEntity.ok(keys);
    }

    @PostMapping("/compare")
    public ResponseEntity<DiffResult> compareData(@RequestBody DiffRequest request) {
        DiffResult result = comparisonService.compare(request);
        return ResponseEntity.ok(result);
    }

    // ==================== NEW ENDPOINTS ====================

    /**
     * Execute a custom query against a connection and return results.
     */
    @PostMapping("/execute-query")
    public ResponseEntity<?> executeQuery(@RequestBody QueryRequest request) {
        try {
            DataSource ds = connectionManagerService.getDataSource(request.getConnection());
            JdbcTemplate jdbc = new JdbcTemplate(ds);
            List<Map<String, Object>> results = jdbc.queryForList(request.getQuery());
            return ResponseEntity.ok(Map.of("success", true, "rows", results));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of(
                    "success", false,
                    "message", e.getMessage() != null ? e.getMessage() : "Unknown error executing query"
            ));
        }
    }

    /**
     * Synchronize data by executing INSERT/UPDATE/DELETE on target.
     */
    @PostMapping("/data-sync")
    public ResponseEntity<?> syncData(@RequestBody DiffRequest request) {
        try {
            return ResponseEntity.ok(comparisonService.syncData(request));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", e.getMessage() != null ? e.getMessage() : "Failed to sync data"
            ));
        }
    }

    /**
     * Compare schema of a single table between source and target databases.
     */
    @PostMapping("/schema-compare")
    public ResponseEntity<?> compareSchema(@RequestBody DiffRequest request) {
        try {
            DataSource sourceDs = connectionManagerService.getDataSource(request.getSourceConnection());
            DataSource targetDs = connectionManagerService.getDataSource(request.getTargetConnection());
            
            SchemaCompareResult result = metaDataService.compareSchema(
                sourceDs, request.getTableName(), request.getSourceConnection().getSchema(), 
                targetDs, request.getTableName(), request.getTargetConnection().getSchema()
            );
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of(
                    "success", false,
                    "message", e.getMessage() != null ? e.getMessage() : "Unknown error comparing schema"
            ));
        }
    }

    /**
     * Compare schema of ALL tables between source and target databases.
     * Includes SOURCE_ONLY and TARGET_ONLY tables.
     */
    @PostMapping("/schema-compare-all")
    public ResponseEntity<?> schemaCompareAll(@RequestBody SchemaCompareRequest request) {
        try {
            DataSource sourceDs = connectionManagerService.getDataSource(request.getSourceConnection());
            DataSource targetDs = connectionManagerService.getDataSource(request.getTargetConnection());

            List<TableInfo> sourceTableInfos = metaDataService.getTables(sourceDs, request.getSourceConnection().getSchema());
            List<TableInfo> targetTableInfos = metaDataService.getTables(targetDs, request.getTargetConnection().getSchema());
            
            List<String> sourceTables = sourceTableInfos.stream().map(TableInfo::getName).collect(Collectors.toList());
            List<String> targetTables = targetTableInfos.stream().map(TableInfo::getName).collect(Collectors.toList());

            Set<String> allTables = new LinkedHashSet<>();
            allTables.addAll(sourceTables);
            allTables.addAll(targetTables);

            Set<String> sourceSet = new HashSet<>(sourceTables);
            Set<String> targetSet = new HashSet<>(targetTables);

            List<SchemaCompareResult> results = new ArrayList<>();

            for (String tableName : allTables) {
                boolean inSource = sourceSet.contains(tableName);
                boolean inTarget = targetSet.contains(tableName);

                if (inSource && inTarget) {
                    // Table exists in both - do full schema comparison
                    SchemaCompareResult result = metaDataService.compareSchema(sourceDs, tableName, request.getSourceConnection().getSchema(), targetDs, tableName, request.getTargetConnection().getSchema());
                    results.add(result);
                } else if (inSource) {
                    // Source only
                    SchemaCompareResult result = new SchemaCompareResult();
                    result.setTableName(tableName);
                    result.setStatus("SOURCE_ONLY");
                    result.setColumnDiffs(metaDataService.getDetailedTableInfo(sourceDs, tableName, request.getSourceConnection().getSchema()));
                    results.add(result);
                } else {
                    // Target only
                    SchemaCompareResult result = new SchemaCompareResult();
                    result.setTableName(tableName);
                    result.setStatus("TARGET_ONLY");
                    // For target-only, shift source fields to target fields
                    List<ColumnDiff> targetInfo = metaDataService.getDetailedTableInfo(targetDs, tableName, request.getTargetConnection().getSchema());
                    for (ColumnDiff col : targetInfo) {
                        col.setTargetType(col.getSourceType());
                        col.setTargetNullable(col.getSourceNullable());
                        col.setTargetSize(col.getSourceSize());
                        col.setPrimaryKeyTarget(col.isPrimaryKeySource());
                        col.setSourceType(null);
                        col.setSourceNullable(null);
                        col.setSourceSize(null);
                        col.setPrimaryKeySource(false);
                        col.setStatus("TARGET_ONLY");
                    }
                    result.setColumnDiffs(targetInfo);
                    results.add(result);
                }
            }

            return ResponseEntity.ok(results);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", true,
                    "message", e.getMessage() != null ? e.getMessage() : "Unknown error comparing schemas"
            ));
        }
    }

    /**
     * Get detailed table info (column name, type, size, nullable, isPK) for a single table.
     */
    @PostMapping("/table-info")
    public ResponseEntity<?> getTableInfo(@RequestBody Map<String, Object> payload) {
        try {
            ConnectionDetails details = mapToDetails((Map<String, Object>) payload.get("connection"));
            String tableName = (String) payload.get("tableName");
            DataSource ds = connectionManagerService.getDataSource(details);
            List<ColumnDiff> info = metaDataService.getDetailedTableInfo(ds, tableName, details.getSchema());
            return ResponseEntity.ok(info);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", true,
                    "message", e.getMessage() != null ? e.getMessage() : "Unknown error getting table info"
            ));
        }
    }

    /**
     * Get available schemas for a connection
     */
    @PostMapping("/schemas")
    public ResponseEntity<?> getSchemas(@RequestBody Map<String, Object> payload) {
        try {
            ConnectionDetails details = mapToDetails(payload);
            DataSource ds = connectionManagerService.getDataSource(details);
            List<String> schemas = new ArrayList<>();
            try (java.sql.Connection conn = ds.getConnection();
                 java.sql.ResultSet rs = conn.getMetaData().getSchemas()) {
                while (rs.next()) {
                    String schemaName = rs.getString("TABLE_SCHEM");
                    if (schemaName != null && !schemaName.equals("information_schema") && !schemaName.equals("pg_catalog") && !schemaName.equals("pg_toast")) {
                        schemas.add(schemaName);
                    }
                }
            }
            return ResponseEntity.ok(schemas);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", true,
                    "message", e.getMessage() != null ? e.getMessage() : "Unknown error fetching schemas"
            ));
        }
    }

    // Helper mapper just for quick parsing of generic payload
    private ConnectionDetails mapToDetails(Map<String, Object> map) {
        ConnectionDetails details = new ConnectionDetails();
        details.setId((String) map.get("id"));
        details.setName((String) map.get("name"));
        details.setType((String) map.get("type"));
        details.setHost((String) map.get("host"));
        details.setPort(map.get("port") instanceof Integer ? (Integer) map.get("port") : Integer.parseInt(map.get("port").toString()));
        details.setDatabase((String) map.get("database"));
        details.setUsername((String) map.get("username"));
        details.setPassword((String) map.get("password"));
        if (map.containsKey("schema")) {
            details.setSchema((String) map.get("schema"));
        }
        return details;
    }
}

