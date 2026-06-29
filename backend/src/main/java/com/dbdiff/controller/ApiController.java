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
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import com.dbdiff.service.ExcelService;
import com.dbdiff.service.ReportExportService;

import javax.sql.DataSource;
import java.util.*;
import java.util.stream.Collectors;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*", allowedHeaders = "*", methods = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE, RequestMethod.OPTIONS, RequestMethod.PATCH}) // Allows React frontend to connect
public class ApiController {

    @Autowired
    private ConnectionManagerService connectionManagerService;

    @Autowired
    private DatabaseMetaDataService metaDataService;

    @Autowired
    private DataComparisonService comparisonService;

    @Autowired
    private ExcelService excelService;

    @Autowired
    private ConnectionRepository connectionRepository;

    @GetMapping("/ping")
    public ResponseEntity<Map<String, Object>> ping() {
        return ResponseEntity.ok(Map.of("status", "UP", "timestamp", System.currentTimeMillis()));
    }

    @GetMapping("/connections")
    public ResponseEntity<List<ConnectionDetails>> getConnections() {
        return ResponseEntity.ok(connectionRepository.findAll());
    }

    @PostMapping("/connections")
    public ResponseEntity<?> saveConnection(@RequestBody ConnectionDetails details) {
        connectionManagerService.evictConnection(details.getStableIdentifier());
        connectionRepository.save(details);
        return ResponseEntity.ok(Map.of("success", true, "connection", details));
    }

    @PutMapping("/connections/{id}")
    public ResponseEntity<?> updateConnection(@PathVariable String id, @RequestBody ConnectionDetails details) {
        details.setId(id);
        ConnectionDetails existing = connectionRepository.findById(id);
        if (existing != null) {
            if (details.getPassword() == null) details.setPassword(existing.getPassword());
            if (details.getSshPassword() == null) details.setSshPassword(existing.getSshPassword());
            if (details.getSshPassphrase() == null) details.setSshPassphrase(existing.getSshPassphrase());
            connectionManagerService.evictConnection(existing.getStableIdentifier());
        }
        connectionManagerService.evictConnection(details.getStableIdentifier());
        connectionRepository.save(details);
        return ResponseEntity.ok(Map.of("success", true, "connection", details));
    }

    @DeleteMapping("/connections/{id}")
    public ResponseEntity<?> deleteConnection(@PathVariable String id) {
        ConnectionDetails existing = connectionRepository.findById(id);
        if (existing != null) {
            connectionManagerService.evictConnection(existing.getStableIdentifier());
        }
        connectionRepository.deleteById(id);
        return ResponseEntity.ok(Map.of("success", true));
    }

    @PostMapping("/test-connection")
    public ResponseEntity<?> testConnection(@RequestBody ConnectionDetails details) {
        details = fillConnectionDetails(details);
        Map<String, Object> result = connectionManagerService.testConnection(details);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/warmup")
    public ResponseEntity<?> warmupConnections(@RequestBody List<ConnectionDetails> connections) {
        for (ConnectionDetails details : connections) {
            try {
                connectionManagerService.getDataSource(fillConnectionDetails(details)); // triggers the async warmup
            } catch (Exception e) {
                // Ignore errors for warmup
            }
        }
        return ResponseEntity.ok(Map.of("success", true));
    }

    @PostMapping("/tables")
    public ResponseEntity<List<TableInfo>> getTables(@RequestBody ConnectionDetails details) {
        details = fillConnectionDetails(details);
        DataSource ds = connectionManagerService.getDataSource(details);
        List<TableInfo> tables = metaDataService.getTables(ds, details.getSchema());
        return ResponseEntity.ok(tables);
    }
    
    @PostMapping("/columns")
    public ResponseEntity<?> getColumns(@RequestBody Map<String, Object> payload) {
        // Simple mapping, normally use proper DTO
        ConnectionDetails details = mapToDetails(safeCastMap(payload.get("connection")));
        String tableName = (String) payload.get("tableName");
        if (details == null) {
            return ResponseEntity.badRequest().body(Map.of("error", true, "message", "Invalid connection data"));
        }
        
        DataSource ds = connectionManagerService.getDataSource(details);
        List<String> columns = metaDataService.getColumns(ds, tableName, details.getSchema());
        return ResponseEntity.ok(columns);
    }
    
    @PostMapping("/primary-keys")
    public ResponseEntity<?> getPrimaryKeys(@RequestBody Map<String, Object> payload) {
        ConnectionDetails details = mapToDetails(safeCastMap(payload.get("connection")));
        String tableName = (String) payload.get("tableName");
        if (details == null) {
            return ResponseEntity.badRequest().body(Map.of("error", true, "message", "Invalid connection data"));
        }
        
        DataSource ds = connectionManagerService.getDataSource(details);
        List<String> keys = metaDataService.getPrimaryKeys(ds, tableName, details.getSchema());
        return ResponseEntity.ok(keys);
    }

    @Autowired
    private ReportExportService exportService;

    @PostMapping("/compare")
    public ResponseEntity<StreamingResponseBody> compareData(@RequestBody DiffRequest request) {
        request.setSourceConnection(fillConnectionDetails(request.getSourceConnection()));
        request.setTargetConnection(fillConnectionDetails(request.getTargetConnection()));
        StreamingResponseBody stream = out -> {
            try (java.io.BufferedOutputStream bos = new java.io.BufferedOutputStream(out, 65536)) {
                comparisonService.compareAndStream(request, bos);
            }
        };
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_OCTET_STREAM_VALUE)
                .header("X-Accel-Buffering", "no")
                .header("Cache-Control", "no-cache")
                .body(stream);
    }

    /**
     * Compare data in batches (paginated) — untuk data besar
     */
    @PostMapping("/compare-batch")
    public ResponseEntity<?> compareBatch(
            @RequestBody Map<String, Object> payload) {
        try {
            DiffRequest request = new DiffRequest();
            Map<String, Object> srcMap = safeCastMap(payload.get("sourceConnection"));
            Map<String, Object> tgtMap = safeCastMap(payload.get("targetConnection"));
            request.setSourceConnection(mapToDetails(srcMap));
            request.setTargetConnection(mapToDetails(tgtMap));
            request.setTableName((String) payload.get("tableName"));
            request.setCustomQuerySource((String) payload.get("customQuerySource"));
            request.setCustomQueryTarget((String) payload.get("customQueryTarget"));
            
            if (payload.containsKey("primaryKeys")) {
                Object pks = payload.get("primaryKeys");
                if (pks instanceof List) request.setPrimaryKeys((List<String>) pks);
            }
            if (payload.containsKey("excludeColumns")) {
                Object exc = payload.get("excludeColumns");
                if (exc instanceof List) request.setExcludeColumns((List<String>) exc);
            }
            if (payload.containsKey("sortColumns")) {
                Object sc = payload.get("sortColumns");
                if (sc instanceof List) request.setSortColumns((List<String>) sc);
            }
            request.setReturnMatchedRows(
                !payload.containsKey("returnMatchedRows") || Boolean.TRUE.equals(payload.get("returnMatchedRows")));

            int batchSize = payload.containsKey("batchSize") ? ((Number) payload.get("batchSize")).intValue() : 5000;
            if (batchSize > 5000) {
                batchSize = 5000; // Cap to prevent OOM
            }
            int offset = payload.containsKey("offset") ? ((Number) payload.get("offset")).intValue() : 0;

            Map<String, Object> result = comparisonService.compareBatch(request, batchSize, offset);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", true,
                    "message", e.getMessage() != null ? e.getMessage() : "Failed to compare batch"
            ));
        }
    }

    /**
     * Count rows in source & target tables (for batch progress tracking)
     */
    @PostMapping("/compare-count")
    public ResponseEntity<?> compareCount(@RequestBody Map<String, Object> payload) {
        try {
            DiffRequest request = new DiffRequest();
            Map<String, Object> srcMap = safeCastMap(payload.get("sourceConnection"));
            Map<String, Object> tgtMap = safeCastMap(payload.get("targetConnection"));
            request.setSourceConnection(mapToDetails(srcMap));
            request.setTargetConnection(mapToDetails(tgtMap));
            request.setTableName((String) payload.get("tableName"));
            request.setCustomQuerySource((String) payload.get("customQuerySource"));
            request.setCustomQueryTarget((String) payload.get("customQueryTarget"));

            Map<String, Object> result = comparisonService.countRows(request);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", true,
                    "message", e.getMessage() != null ? e.getMessage() : "Failed to count rows"
            ));
        }
    }

    @PostMapping("/export-excel")
    public ResponseEntity<StreamingResponseBody> exportExcel(
            @RequestBody DiffRequest request,
            @RequestParam(defaultValue = "ALL") String filterStatus) {
        
        request.setSourceConnection(fillConnectionDetails(request.getSourceConnection()));
        request.setTargetConnection(fillConnectionDetails(request.getTargetConnection()));
        
        StreamingResponseBody stream = out -> {
            try {
                exportService.exportExcel(request, filterStatus, out);
            } catch (Exception e) {
                throw new java.io.IOException("Failed to export Excel", e);
            }
        };
        
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"data-compare-export.xlsx\"")
                .header(HttpHeaders.CONTENT_TYPE, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                .body(stream);
    }

    @PostMapping("/export-pdf")
    public ResponseEntity<StreamingResponseBody> exportPdf(
            @RequestBody DiffRequest request,
            @RequestParam(defaultValue = "ALL") String filterStatus) {
        
        request.setSourceConnection(fillConnectionDetails(request.getSourceConnection()));
        request.setTargetConnection(fillConnectionDetails(request.getTargetConnection()));
        
        StreamingResponseBody stream = out -> {
            try {
                exportService.exportPdf(request, filterStatus, out);
            } catch (Exception e) {
                throw new java.io.IOException("Failed to export PDF", e);
            }
        };
        
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"data-compare-export.pdf\"")
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_PDF_VALUE)
                .body(stream);
    }

    // ==================== NEW ENDPOINTS ====================

    /**
     * Execute a custom query against a connection and return results.
     */
    @PostMapping("/execute-query")
    public ResponseEntity<StreamingResponseBody> executeQuery(@RequestBody QueryRequest request) {
        request.setConnection(fillConnectionDetails(request.getConnection()));
        StreamingResponseBody stream = out -> {
            ObjectMapper mapper = new ObjectMapper();
            try (JsonGenerator gen = mapper.getFactory().createGenerator(out, com.fasterxml.jackson.core.JsonEncoding.UTF8)) {
                gen.disable(JsonGenerator.Feature.FLUSH_PASSED_TO_STREAM);

                try {
                    DataSource ds = connectionManagerService.getDataSource(request.getConnection());
                    try (Connection conn = ds.getConnection();
                         PreparedStatement ps = conn.prepareStatement(request.getQuery(), ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {

                        boolean isPostgres = conn.getMetaData().getDatabaseProductName().toLowerCase().contains("postgres");
                        boolean isMysql = conn.getMetaData().getDatabaseProductName().toLowerCase().contains("mysql");
                        if (isPostgres) {
                            conn.setAutoCommit(false);
                        }
                        ps.setFetchSize(isMysql ? Integer.MIN_VALUE : 1000);

                        try (ResultSet rs = ps.executeQuery()) {
                            ResultSetMetaData meta = rs.getMetaData();
                            int colCount = meta.getColumnCount();
                            String[] cols = new String[colCount];
                            for (int i = 1; i <= colCount; i++) {
                                cols[i - 1] = meta.getColumnLabel(i);
                            }

                            gen.writeStartObject();
                            gen.writeStringField("type", "columns");
                            gen.writeArrayFieldStart("data");
                            for (String col : cols) gen.writeString(col);
                            gen.writeEndArray();
                            gen.writeEndObject();
                            gen.writeRaw('\n');
                            gen.flush();

                            int rowCount = 0;
                            while (rs.next()) {
                                gen.writeStartObject();
                                gen.writeStringField("type", "row");
                                gen.writeObjectFieldStart("data");
                                for (int i = 1; i <= colCount; i++) {
                                    gen.writeObjectField(cols[i - 1], getSafeObject(rs, i));
                                }
                                gen.writeEndObject();
                                gen.writeEndObject();
                                gen.writeRaw('\n');
                                rowCount++;
                                if (rowCount >= 50000) {
                                    gen.writeStartObject();
                                    gen.writeStringField("type", "error");
                                    gen.writeStringField("message", "Query execution stopped at 50,000 rows to prevent memory exhaustion.");
                                    gen.writeEndObject();
                                    gen.writeRaw('\n');
                                    break;
                                }
                                if (rowCount % 5000 == 0) gen.flush();
                            }

                            gen.writeStartObject();
                            gen.writeStringField("type", "summary");
                            gen.writeObjectFieldStart("data");
                            gen.writeNumberField("totalRows", rowCount);
                            gen.writeEndObject();
                            gen.writeEndObject();
                            gen.writeRaw('\n');
                            gen.flush();
                        } finally {
                            // Selalu rollback untuk PostgreSQL cursor query — bersihkan transaction state
                            // sebelum koneksi kembali ke pool, mencegah "dirty commit state" warning
                            if (isPostgres) {
                                try { conn.rollback(); } catch (Exception ignored) {}
                                try { conn.setAutoCommit(true); } catch (Exception ignored) {}
                            }
                        }
                    }
                } catch (Exception e) {
                    gen.writeStartObject();
                    gen.writeStringField("type", "error");
                    gen.writeStringField("message", e.getMessage() != null ? e.getMessage() : "Unknown error");
                    gen.writeEndObject();
                    gen.writeRaw('\n');
                    gen.flush();
                }
            }
        };

        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(stream);
    }

    /**
     * Synchronize data by executing INSERT/UPDATE/DELETE on target.
     */
    @PostMapping("/data-sync")
    public ResponseEntity<?> syncData(@RequestBody DiffRequest request) {
        request.setSourceConnection(fillConnectionDetails(request.getSourceConnection()));
        request.setTargetConnection(fillConnectionDetails(request.getTargetConnection()));
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
        request.setSourceConnection(fillConnectionDetails(request.getSourceConnection()));
        request.setTargetConnection(fillConnectionDetails(request.getTargetConnection()));
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
        request.setSourceConnection(fillConnectionDetails(request.getSourceConnection()));
        request.setTargetConnection(fillConnectionDetails(request.getTargetConnection()));
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
            ConnectionDetails details = mapToDetails(safeCastMap(payload.get("connection")));
            if (details == null) {
                return ResponseEntity.badRequest().body(Map.of("error", true, "message", "Invalid connection data"));
            }
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

    private Object getSafeObject(java.sql.ResultSet rs, int colIdx) throws java.sql.SQLException {
        Object val = rs.getObject(colIdx);
        if (val == null) return null;
        if (val instanceof java.sql.Blob) {
            java.sql.Blob b = (java.sql.Blob) val;
            return "[BLOB Data: " + b.length() + " bytes]";
        } else if (val instanceof java.sql.Clob) {
            java.sql.Clob c = (java.sql.Clob) val;
            return "[CLOB Data: " + c.length() + " chars]";
        } else if (val instanceof byte[]) {
            return "[BINARY Data: " + ((byte[]) val).length + " bytes]";
        } else if (val instanceof java.sql.Timestamp) {
            return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format((java.util.Date) val);
        } else if (val instanceof java.sql.Date) {
            return new java.text.SimpleDateFormat("yyyy-MM-dd").format((java.util.Date) val);
        } else if (val instanceof java.sql.Time) {
            return new java.text.SimpleDateFormat("HH:mm:ss").format((java.util.Date) val);
        } else if (val instanceof java.util.Date) {
            return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format((java.util.Date) val);
        } else if (val instanceof java.time.LocalDateTime) {
            return java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").format((java.time.LocalDateTime) val);
        } else if (val instanceof java.time.LocalDate) {
            return java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd").format((java.time.LocalDate) val);
        } else if (val instanceof java.time.LocalTime) {
            return java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss").format((java.time.LocalTime) val);
        } else if (val instanceof java.time.ZonedDateTime) {
            return java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z").format((java.time.ZonedDateTime) val);
        } else if (val instanceof java.time.OffsetDateTime) {
            return java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss XXX").format((java.time.OffsetDateTime) val);
        }
        return val;
    }

    @Autowired
    private com.dbdiff.service.DatabaseExplorerService explorerService;

    @GetMapping("/connections/{id}/schemas")
    public ResponseEntity<?> getExplorerSchemas(@PathVariable String id) {
        try {
            ConnectionDetails details = connectionRepository.findById(id);
            if (details == null) return ResponseEntity.notFound().build();
            DataSource ds = connectionManagerService.getDataSource(details);
            return ResponseEntity.ok(explorerService.getSchemas(ds));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage() != null ? e.getMessage() : "Unknown error"));
        }
    }

    @GetMapping("/connections/{id}/schemas/{schema}/tables")
    public ResponseEntity<?> getExplorerTables(@PathVariable String id, @PathVariable String schema) {
        try {
            ConnectionDetails details = connectionRepository.findById(id);
            if (details == null) return ResponseEntity.notFound().build();
            DataSource ds = connectionManagerService.getDataSource(details);
            return ResponseEntity.ok(explorerService.getTables(ds, "null".equals(schema) ? null : schema));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage() != null ? e.getMessage() : "Unknown error"));
        }
    }

    @GetMapping("/connections/{id}/schemas/{schema}/views")
    public ResponseEntity<?> getExplorerViews(@PathVariable String id, @PathVariable String schema) {
        try {
            ConnectionDetails details = connectionRepository.findById(id);
            if (details == null) return ResponseEntity.notFound().build();
            DataSource ds = connectionManagerService.getDataSource(details);
            return ResponseEntity.ok(explorerService.getViews(ds, "null".equals(schema) ? null : schema));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage() != null ? e.getMessage() : "Unknown error"));
        }
    }

    @GetMapping("/connections/{id}/schemas/{schema}/tables/{table}/columns")
    public ResponseEntity<?> getExplorerColumns(@PathVariable String id, @PathVariable String schema, @PathVariable String table) {
        try {
            ConnectionDetails details = connectionRepository.findById(id);
            if (details == null) return ResponseEntity.notFound().build();
            DataSource ds = connectionManagerService.getDataSource(details);
            return ResponseEntity.ok(explorerService.getColumns(ds, "null".equals(schema) ? null : schema, table));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage() != null ? e.getMessage() : "Unknown error"));
        }
    }

    @GetMapping("/connections/{id}/schemas/{schema}/tables/{table}/indexes")
    public ResponseEntity<?> getExplorerIndexes(@PathVariable String id, @PathVariable String schema, @PathVariable String table) {
        try {
            ConnectionDetails details = connectionRepository.findById(id);
            if (details == null) return ResponseEntity.notFound().build();
            DataSource ds = connectionManagerService.getDataSource(details);
            return ResponseEntity.ok(explorerService.getIndexes(ds, "null".equals(schema) ? null : schema, table));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage() != null ? e.getMessage() : "Unknown error"));
        }
    }

    @GetMapping("/connections/{id}/schemas/{schema}/tables/{table}/foreign-keys")
    public ResponseEntity<?> getExplorerForeignKeys(@PathVariable String id, @PathVariable String schema, @PathVariable String table) {
        try {
            ConnectionDetails details = connectionRepository.findById(id);
            if (details == null) return ResponseEntity.notFound().build();
            DataSource ds = connectionManagerService.getDataSource(details);
            return ResponseEntity.ok(explorerService.getForeignKeys(ds, "null".equals(schema) ? null : schema, table));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage() != null ? e.getMessage() : "Unknown error"));
        }
    }

    @GetMapping("/connections/{id}/schemas/{schema}/tables/{table}/ddl")
    public ResponseEntity<?> getExplorerDdl(@PathVariable String id, @PathVariable String schema, @PathVariable String table) {
        try {
            ConnectionDetails details = connectionRepository.findById(id);
            if (details == null) return ResponseEntity.notFound().build();
            DataSource ds = connectionManagerService.getDataSource(details);
            return ResponseEntity.ok(Map.of("ddl", explorerService.getDdl(ds, "null".equals(schema) ? null : schema, table, details.getType())));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage() != null ? e.getMessage() : "Unknown error"));
        }
    }

    @GetMapping("/connections/{id}/schemas/{schema}/tables/{table}/stats")
    public ResponseEntity<?> getExplorerStats(@PathVariable String id, @PathVariable String schema, @PathVariable String table) {
        try {
            ConnectionDetails details = connectionRepository.findById(id);
            if (details == null) return ResponseEntity.notFound().build();
            DataSource ds = connectionManagerService.getDataSource(details);
            return ResponseEntity.ok(explorerService.getStats(ds, "null".equals(schema) ? null : schema, table, details.getType()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage() != null ? e.getMessage() : "Unknown error"));
        }
    }

    @PostMapping("/connections/{id}/schemas/{schema}/tables/{table}/preview")
    public ResponseEntity<?> previewData(@PathVariable String id, @PathVariable String schema, @PathVariable String table) {
        try {
            ConnectionDetails details = connectionRepository.findById(id);
            if (details == null) return ResponseEntity.notFound().build();
            DataSource ds = connectionManagerService.getDataSource(details);
            return ResponseEntity.ok(explorerService.previewData(ds, "null".equals(schema) ? null : schema, table));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage() != null ? e.getMessage() : "Unknown error"));
        }
    }

    @PostMapping("/excel/upload")
    public ResponseEntity<?> uploadExcel(
            @RequestParam("file") MultipartFile file,
            @RequestParam("connectionId") String connectionId) {
        try {
            ConnectionDetails details = connectionRepository.findById(connectionId);
            if (details == null) {
                throw new Exception("Connection not found");
            }
            String tableName = excelService.importExcelToDatabase(file, details);
            return ResponseEntity.ok(Map.of("success", true, "tableName", tableName));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage() != null ? e.getMessage() : "Failed to upload Excel"));
        }
    }

    @PostMapping("/excel/drop")
    public ResponseEntity<?> dropExcelTable(
            @RequestBody Map<String, String> payload) {
        try {
            String connectionId = payload.get("connectionId");
            String tableName = payload.get("tableName");
            ConnectionDetails details = connectionRepository.findById(connectionId);
            if (details == null) {
                throw new Exception("Connection not found");
            }
            excelService.dropExcelTable(details, tableName);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage() != null ? e.getMessage() : "Failed to drop Excel table"));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> safeCastMap(Object obj) {
        if (obj instanceof Map) {
            return (Map<String, Object>) obj;
        }
        return null;
    }

    private ConnectionDetails fillConnectionDetails(ConnectionDetails details) {
        if (details == null) return null;
        if (details.getId() != null && !details.getId().trim().isEmpty()) {
            ConnectionDetails existing = connectionRepository.findById(details.getId());
            if (existing != null) {
                if (details.getPassword() == null) details.setPassword(existing.getPassword());
                if (details.getSshPassword() == null) details.setSshPassword(existing.getSshPassword());
                if (details.getSshPassphrase() == null) details.setSshPassphrase(existing.getSshPassphrase());
            }
        }
        details.setPassword(decodeBase64IfNeeded(details.getPassword()));
        details.setSshPassword(decodeBase64IfNeeded(details.getSshPassword()));
        details.setSshPassphrase(decodeBase64IfNeeded(details.getSshPassphrase()));
        return details;
    }

    private ConnectionDetails mapToDetails(Map<String, Object> map) {
        if (map == null) return null;
        String id = (String) map.get("id");
        ConnectionDetails details = null;
        
        if (id != null && !id.trim().isEmpty()) {
            details = connectionRepository.findById(id);
        }
        if (details == null) {
            details = new ConnectionDetails();
            details.setId(id);
        }

        if (map.containsKey("name")) details.setName((String) map.get("name"));
        if (map.containsKey("type")) details.setType((String) map.get("type"));
        if (map.containsKey("host")) details.setHost((String) map.get("host"));
        if (map.containsKey("port") && map.get("port") != null) {
            details.setPort(map.get("port") instanceof Integer ? (Integer) map.get("port") : Integer.parseInt(map.get("port").toString()));
        }
        if (map.containsKey("database")) details.setDatabase((String) map.get("database"));
        if (map.containsKey("username")) details.setUsername((String) map.get("username"));
        
        // Decode base64 passwords from frontend (ConnectionDialog sends base64 for security)
        if (map.containsKey("password") && map.get("password") != null) {
            details.setPassword(decodeBase64IfNeeded((String) map.get("password")));
        }
        
        if (map.containsKey("schema")) details.setSchema((String) map.get("schema"));
        if (map.containsKey("sslMode")) details.setSslMode((String) map.get("sslMode"));
        if (map.containsKey("sslCaFile")) details.setSslCaFile((String) map.get("sslCaFile"));
        if (map.containsKey("sslCertFile")) details.setSslCertFile((String) map.get("sslCertFile"));
        if (map.containsKey("sslKeyFile")) details.setSslKeyFile((String) map.get("sslKeyFile"));

        if (map.containsKey("useSsh") && map.get("useSsh") != null) {
            details.setUseSsh(Boolean.TRUE.equals(map.get("useSsh")));
        }
        if (map.containsKey("sshHost")) details.setSshHost((String) map.get("sshHost"));
        if (map.containsKey("sshPort") && map.get("sshPort") != null) {
            details.setSshPort(map.get("sshPort") instanceof Integer ? (Integer) map.get("sshPort") : Integer.parseInt(map.get("sshPort").toString()));
        }
        if (map.containsKey("sshUsername")) details.setSshUsername((String) map.get("sshUsername"));
        if (map.containsKey("sshAuthMode")) details.setSshAuthMode((String) map.get("sshAuthMode"));
        
        if (map.containsKey("sshPassword") && map.get("sshPassword") != null) {
            details.setSshPassword(decodeBase64IfNeeded((String) map.get("sshPassword")));
        }
        if (map.containsKey("sshKeyFile")) details.setSshKeyFile((String) map.get("sshKeyFile"));
        if (map.containsKey("sshPassphrase") && map.get("sshPassphrase") != null) {
            details.setSshPassphrase(decodeBase64IfNeeded((String) map.get("sshPassphrase")));
        }
        if (map.containsKey("sshStrictHostKeyChecking") && map.get("sshStrictHostKeyChecking") != null) {
            details.setSshStrictHostKeyChecking(Boolean.TRUE.equals(map.get("sshStrictHostKeyChecking")));
        }

        if (map.containsKey("connectionTimeout") && map.get("connectionTimeout") != null) {
            details.setConnectionTimeout(map.get("connectionTimeout") instanceof Integer ? (Integer) map.get("connectionTimeout") : Integer.parseInt(map.get("connectionTimeout").toString()));
        }
        if (map.containsKey("socketTimeout") && map.get("socketTimeout") != null) {
            details.setSocketTimeout(map.get("socketTimeout") instanceof Integer ? (Integer) map.get("socketTimeout") : Integer.parseInt(map.get("socketTimeout").toString()));
        }
        if (map.containsKey("fetchSize") && map.get("fetchSize") != null) {
            details.setFetchSize(map.get("fetchSize") instanceof Integer ? (Integer) map.get("fetchSize") : Integer.parseInt(map.get("fetchSize").toString()));
        }
        if (map.containsKey("readOnly") && map.get("readOnly") != null) {
            details.setReadOnly(Boolean.TRUE.equals(map.get("readOnly")));
        }
        if (map.containsKey("extraProps")) details.setExtraProps((String) map.get("extraProps"));

        return details;
    }

    private String decodeBase64IfNeeded(String raw) {
        if (raw == null || raw.trim().isEmpty()) return raw;
        try {
            // Frontend sends base64. Let's try to decode it.
            byte[] decoded = java.util.Base64.getDecoder().decode(raw);
            String decodedStr = new String(decoded, java.nio.charset.StandardCharsets.UTF_8);
            if (decodedStr.contains("\uFFFD")) return raw;
            for (char ch : decodedStr.toCharArray()) {
                if (ch < 32 && ch != '\t' && ch != '\n' && ch != '\r') return raw;
            }
            return decodedStr;
        } catch (Exception e) {
            return raw;
        }
    }
}

