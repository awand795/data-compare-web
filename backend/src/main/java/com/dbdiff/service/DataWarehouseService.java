package com.dbdiff.service;

import com.dbdiff.model.DataWarehouseDeployRequest;
import com.dbdiff.model.ConnectionDetails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.sql.DataSource;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.util.TablesNamesFinder;

@Service
public class DataWarehouseService {
    private static final Logger logger = LoggerFactory.getLogger(DataWarehouseService.class);
    private final RestTemplate restTemplate = new RestTemplate();
    private static final String DEBEZIUM_URL = "http://debezium:8083/connectors";

    @Autowired
    private ConnectionManagerService connectionManagerService;

    private static class ColumnInfo {
        String name;
        String clickhouseType;
    }

    private void sendLog(SseEmitter emitter, String message) throws IOException {
        logger.info(message);
        emitter.send(SseEmitter.event().data(message));
    }

    public void deployPipeline(DataWarehouseDeployRequest request, SseEmitter emitter) {
        try {
            sendLog(emitter, "Deploying Data Warehouse pipeline for source " + request.getSourceConnection().getName() + " to target table " + request.getTargetTable());
            
            // Generate unique names for connectors
            String baseName = request.getSourceConnection().getName().replaceAll("[^a-zA-Z0-9_-]", "").toLowerCase();
            String sourceConnectorName = "source-" + baseName + "-" + System.currentTimeMillis();
            String sinkConnectorName = "sink-clickhouse-" + request.getTargetTable().replaceAll("[^a-zA-Z0-9_-]", "") + "-" + System.currentTimeMillis();
            
            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);

            // =========================================================================
            // STEP 1: Parse Query, Introspect Schema and PKs
            // =========================================================================
            sendLog(emitter, "Parsing source query to detect physical tables...");
            DataSource sourceDs = connectionManagerService.getDataSource(request.getSourceConnection());
            String originalQuery = expandWildcardsAndAlias(request.getQuery(), sourceDs, request.getSourceConnection());
            List<String> physicalTables = extractPhysicalTables(originalQuery);
            if (physicalTables.isEmpty()) {
                throw new RuntimeException("Could not extract any source tables from the query. Please verify the query syntax is correct.");
            }
            sendLog(emitter, "Detected source tables: " + String.join(", ", physicalTables));

            sendLog(emitter, "Running dry-run query on source DB to inspect column types...");
            DataSource targetDs = connectionManagerService.getDataSource(request.getTargetConnection());
            
            String dryRunSql;
            String srcType = request.getSourceConnection().getType().toLowerCase();
            if (srcType.contains("sqlserver")) {
                dryRunSql = "SELECT TOP 0 * FROM (" + originalQuery + ") AS tmp";
            } else {
                dryRunSql = "SELECT * FROM (" + originalQuery + ") AS tmp LIMIT 0";
            }
            
            List<ColumnInfo> targetColumns = new ArrayList<>();
            try (Connection conn = sourceDs.getConnection();
                 PreparedStatement ps = conn.prepareStatement(dryRunSql);
                 ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData meta = rs.getMetaData();
                int colCount = meta.getColumnCount();
                for (int i = 1; i <= colCount; i++) {
                    ColumnInfo col = new ColumnInfo();
                    col.name = meta.getColumnLabel(i);
                    col.clickhouseType = mapJdbcTypeToClickHouse(
                        meta.getColumnType(i),
                        meta.getPrecision(i),
                        meta.getScale(i),
                        meta.getColumnTypeName(i)
                    );
                    targetColumns.add(col);
                }
            } catch (Exception e) {
                sendLog(emitter, "ERROR: Dry-run schema check failed: " + e.getMessage());
                throw new RuntimeException("Failed to analyze schema of source query: " + e.getMessage(), e);
            }
            
            sendLog(emitter, "Extracting primary keys from source tables for composite sorting key...");
            Set<String> compositePKs = new LinkedHashSet<>();
            try (Connection conn = sourceDs.getConnection()) {
                DatabaseMetaData metaData = conn.getMetaData();
                for (String t : physicalTables) {
                    String schemaName = null;
                    String tableName = t;
                    if (t.contains(".")) {
                        int dotIdx = t.indexOf('.');
                        schemaName = t.substring(0, dotIdx);
                        tableName = t.substring(dotIdx + 1);
                    } else {
                        schemaName = request.getSourceConnection().getSchema();
                    }
                    tableName = tableName.replaceAll("[\"``]", "");
                    if (schemaName != null) {
                        schemaName = schemaName.replaceAll("[\"``]", "");
                    }
                    try (ResultSet pkRs = metaData.getPrimaryKeys(null, schemaName, tableName)) {
                        while (pkRs.next()) {
                            String pkCol = pkRs.getString("COLUMN_NAME");
                            if (pkCol != null) {
                                // Match casing against target columns
                                String matchedCol = pkCol;
                                for (ColumnInfo col : targetColumns) {
                                    if (col.name.equalsIgnoreCase(pkCol)) {
                                        matchedCol = col.name;
                                        break;
                                    }
                                }
                                compositePKs.add(matchedCol);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                sendLog(emitter, "WARNING: Primary key extraction failed: " + e.getMessage());
            }
            
            if (compositePKs.isEmpty() && !targetColumns.isEmpty()) {
                compositePKs.add(targetColumns.get(0).name);
            }
            sendLog(emitter, "Target composite sorting key: " + String.join(", ", compositePKs));

            // =========================================================================
            // STEP 2: Create ClickHouse Target Table & Staging Landing Tables
            // =========================================================================
            String chDb = request.getTargetConnection().getDatabase();
            if (chDb == null || chDb.isEmpty()) chDb = "default";
            
            // 2a. Pre-create ClickHouse landing tables to avoid MV compilation errors
            sendLog(emitter, "Pre-creating ClickHouse landing tables for CDC...");
            for (String t : physicalTables) {
                String landingTable = getClickHouseLandingTable(t, baseName, request.getSourceConnection());
                
                List<ColumnInfo> landingCols = new ArrayList<>();
                try (Connection conn = sourceDs.getConnection()) {
                    DatabaseMetaData metaData = conn.getMetaData();
                    String schemaName = null;
                    String tableName = t;
                    if (t.contains(".")) {
                        int dotIdx = t.indexOf('.');
                        schemaName = t.substring(0, dotIdx);
                        tableName = t.substring(dotIdx + 1);
                    } else {
                        schemaName = request.getSourceConnection().getSchema();
                    }
                    tableName = tableName.replaceAll("[\"``]", "");
                    if (schemaName != null) {
                        schemaName = schemaName.replaceAll("[\"``]", "");
                    }
                    
                    try (ResultSet colRs = metaData.getColumns(null, schemaName, tableName, "%")) {
                        while (colRs.next()) {
                            ColumnInfo col = new ColumnInfo();
                            col.name = colRs.getString("COLUMN_NAME");
                            col.clickhouseType = mapJdbcTypeToClickHouse(
                                colRs.getInt("DATA_TYPE"),
                                colRs.getInt("COLUMN_SIZE"),
                                colRs.getInt("DECIMAL_DIGITS"),
                                colRs.getString("TYPE_NAME")
                            );
                            landingCols.add(col);
                        }
                    }
                }
                
                if (landingCols.isEmpty()) {
                    String schemaPrefix = t.contains(".") ? "" : 
                        ("postgresql".equalsIgnoreCase(request.getSourceConnection().getType()) ? 
                            (request.getSourceConnection().getSchema() != null ? request.getSourceConnection().getSchema() + "." : "public.") : "");
                    String tableDdlSql = "SELECT * FROM " + schemaPrefix + t + " LIMIT 0";
                    try (Connection conn = sourceDs.getConnection();
                         PreparedStatement ps = conn.prepareStatement(tableDdlSql);
                         ResultSet rs = ps.executeQuery()) {
                        ResultSetMetaData meta = rs.getMetaData();
                        for (int i = 1; i <= meta.getColumnCount(); i++) {
                            ColumnInfo col = new ColumnInfo();
                            col.name = meta.getColumnLabel(i);
                            col.clickhouseType = mapJdbcTypeToClickHouse(
                                meta.getColumnType(i),
                                meta.getPrecision(i),
                                meta.getScale(i),
                                meta.getColumnTypeName(i)
                            );
                            landingCols.add(col);
                        }
                    }
                }
                
                Set<String> tablePKs = new LinkedHashSet<>();
                try (Connection conn = sourceDs.getConnection()) {
                    DatabaseMetaData metaData = conn.getMetaData();
                    String schemaName = null;
                    String tableName = t;
                    if (t.contains(".")) {
                        int dotIdx = t.indexOf('.');
                        schemaName = t.substring(0, dotIdx);
                        tableName = t.substring(dotIdx + 1);
                    } else {
                        schemaName = request.getSourceConnection().getSchema();
                    }
                    tableName = tableName.replaceAll("[\"``]", "");
                    if (schemaName != null) {
                        schemaName = schemaName.replaceAll("[\"``]", "");
                    }
                    try (ResultSet pkRs = metaData.getPrimaryKeys(null, schemaName, tableName)) {
                        while (pkRs.next()) {
                            String pkCol = pkRs.getString("COLUMN_NAME");
                            if (pkCol != null) {
                                tablePKs.add(pkCol);
                            }
                        }
                    }
                } catch (Exception e) {}
                
                if (tablePKs.isEmpty() && !landingCols.isEmpty()) {
                    tablePKs.add(landingCols.get(0).name);
                }
                
                StringBuilder landingDdl = new StringBuilder();
                landingDdl.append("CREATE TABLE IF NOT EXISTS `").append(chDb).append("`.`").append(landingTable).append("` (\n");
                for (ColumnInfo col : landingCols) {
                    landingDdl.append("    `").append(col.name).append("` ").append(col.clickhouseType).append(",\n");
                }
                landingDdl.append("    `version` UInt64 DEFAULT 0,\n");
                landingDdl.append("    `is_deleted` UInt8 DEFAULT 0\n");
                landingDdl.append(") ENGINE = ReplacingMergeTree(version)\n");
                
                StringBuilder lpkBuilder = new StringBuilder();
                for (String pk : tablePKs) {
                    if (lpkBuilder.length() > 0) lpkBuilder.append(", ");
                    lpkBuilder.append("`").append(pk).append("`");
                }
                landingDdl.append("ORDER BY (").append(lpkBuilder.toString()).append(")");
                
                sendLog(emitter, "Executing ClickHouse DDL for landing table `" + landingTable + "`...");
                try (Connection conn = targetDs.getConnection();
                     Statement stmt = conn.createStatement()) {
                    stmt.execute(landingDdl.toString());
                } catch (Exception e) {
                    sendLog(emitter, "WARNING: Could not pre-create landing table `" + landingTable + "`: " + e.getMessage());
                }
            }

            // 2b. Create Physical Target ReplacingMergeTree Table
            StringBuilder targetDdl = new StringBuilder();
            targetDdl.append("CREATE TABLE IF NOT EXISTS `").append(chDb).append("`.`").append(request.getTargetTable()).append("` (\n");
            for (ColumnInfo col : targetColumns) {
                targetDdl.append("    `").append(col.name).append("` ").append(col.clickhouseType).append(",\n");
            }
            targetDdl.append("    `version` UInt64 DEFAULT 0,\n");
            targetDdl.append("    `is_deleted` UInt8 DEFAULT 0\n");
            targetDdl.append(") ENGINE = ReplacingMergeTree(version)\n");
            
            StringBuilder pkBuilder = new StringBuilder();
            for (String pk : compositePKs) {
                if (pkBuilder.length() > 0) pkBuilder.append(", ");
                pkBuilder.append("`").append(pk).append("`");
            }
            targetDdl.append("ORDER BY (").append(pkBuilder.toString()).append(")");
            
            sendLog(emitter, "Creating target table `" + request.getTargetTable() + "` in ClickHouse...");
            try (Connection conn = targetDs.getConnection();
                 Statement stmt = conn.createStatement()) {
                stmt.execute(targetDdl.toString());
                sendLog(emitter, "Target table `" + request.getTargetTable() + "` verified/created.");
            } catch (Exception e) {
                sendLog(emitter, "ERROR: Target table creation failed: " + e.getMessage());
                throw e;
            }

            // =========================================================================
            // STEP 3: Configure Debezium Source Connector
            // =========================================================================
            sendLog(emitter, "Configuring Debezium Source Connector (" + sourceConnectorName + ")...");
            
            java.util.Map<String, Object> sourceConfig = new java.util.HashMap<>();
            if ("postgresql".equalsIgnoreCase(request.getSourceConnection().getType())) {
                sourceConfig.put("connector.class", "io.debezium.connector.postgresql.PostgresConnector");
                sourceConfig.put("plugin.name", "pgoutput");
                // Force a fresh snapshot by using a unique slot name for every deployment
                String safeSlotName = sourceConnectorName.replaceAll("[^a-z0-9_]", "_").toLowerCase();
                sourceConfig.put("slot.name", safeSlotName);
            } else if ("mysql".equalsIgnoreCase(request.getSourceConnection().getType())) {
                sourceConfig.put("connector.class", "io.debezium.connector.mysql.MySqlConnector");
            } else {
                sourceConfig.put("connector.class", "io.debezium.connector." + request.getSourceConnection().getType().toLowerCase() + "." + request.getSourceConnection().getType() + "Connector");
            }
            
            sourceConfig.put("tasks.max", "1");
            sourceConfig.put("database.hostname", request.getSourceConnection().getHost() != null ? request.getSourceConnection().getHost().trim() : "");
            sourceConfig.put("database.port", String.valueOf(request.getSourceConnection().getPort()));
            sourceConfig.put("database.user", request.getSourceConnection().getUsername() != null ? request.getSourceConnection().getUsername().trim() : "");
            sourceConfig.put("database.password", request.getSourceConnection().getPassword());
            sourceConfig.put("database.dbname", request.getSourceConnection().getDatabase() != null ? request.getSourceConnection().getDatabase().trim() : "");
            sourceConfig.put("database.server.name", sourceConnectorName);
            sourceConfig.put("topic.prefix", sourceConnectorName); // Compatibility with Debezium 2.x

            // Route Debezium topics to a unified target format: cdc_[baseName]_[schema]_[table]
            String topicPrefix = "cdc_" + baseName + "_";
            sourceConfig.put("transforms", "route,unwrap,rename");
            sourceConfig.put("transforms.route.type", "org.apache.kafka.connect.transforms.RegexRouter");
            sourceConfig.put("transforms.route.regex", "([^\\.]+)\\.([^\\.]+)\\.([^\\.]+)");
            sourceConfig.put("transforms.route.replacement", topicPrefix + "$2_$3");
            
            // Flatten the Debezium CDC payload
            sourceConfig.put("transforms.unwrap.type", "io.debezium.transforms.ExtractNewRecordState");
            sourceConfig.put("transforms.unwrap.drop.tombstones", "false");
            sourceConfig.put("transforms.unwrap.delete.handling.mode", "rewrite");
            sourceConfig.put("transforms.unwrap.add.fields", "ts_ms");
            
            // Rename internal Debezium fields to match our ClickHouse landing tables
            sourceConfig.put("transforms.rename.type", "org.apache.kafka.connect.transforms.ReplaceField$Value");
            sourceConfig.put("transforms.rename.renames", "__deleted:is_deleted,__ts_ms:version");
            
            List<String> formattedTables = new ArrayList<>();
            for (String t : physicalTables) {
                String cleanTable = t.replaceAll("[\"``]", "");
                if (cleanTable.contains(".")) {
                    formattedTables.add(cleanTable);
                } else {
                    if ("postgresql".equalsIgnoreCase(request.getSourceConnection().getType())) {
                        String defaultSchema = request.getSourceConnection().getSchema();
                        if (defaultSchema == null || defaultSchema.isEmpty()) defaultSchema = "public";
                        formattedTables.add(defaultSchema + "." + cleanTable);
                    } else if ("mysql".equalsIgnoreCase(request.getSourceConnection().getType())) {
                        String db = request.getSourceConnection().getDatabase();
                        formattedTables.add(db + "." + cleanTable);
                    } else {
                        formattedTables.add(cleanTable);
                    }
                }
            }
            String tableIncludeList = String.join(",", formattedTables);
            sourceConfig.put("table.include.list", tableIncludeList);
            
            // Serialize Decimals as strings to avoid Base64 encoding which breaks ClickHouse sink
            sourceConfig.put("decimal.handling.mode", "double");
            
            // Disable schemas in the output Kafka topics to save bandwidth and simplify sink parsing
            sourceConfig.put("key.converter", "org.apache.kafka.connect.json.JsonConverter");
            sourceConfig.put("key.converter.schemas.enable", "false");
            sourceConfig.put("value.converter", "org.apache.kafka.connect.json.JsonConverter");
            sourceConfig.put("value.converter.schemas.enable", "false");
            
            java.util.Map<String, Object> sourcePayload = new java.util.HashMap<>();
            sourcePayload.put("name", sourceConnectorName);
            sourcePayload.put("config", sourceConfig);
            
            try {
                org.springframework.http.HttpEntity<java.util.Map<String, Object>> sourceEntity = new org.springframework.http.HttpEntity<>(sourcePayload, headers);
                org.springframework.http.ResponseEntity<String> sourceResponse = restTemplate.postForEntity(DEBEZIUM_URL, sourceEntity, String.class);
                sendLog(emitter, "Source connector registered successfully: " + sourceResponse.getStatusCode());
            } catch (Exception e) {
                sendLog(emitter, "ERROR: Could not register source connector in Debezium: " + e.getMessage());
                throw e; 
            }

            // STEP 3.5: Wait for Kafka topics to initialize
            sendLog(emitter, "Waiting for Kafka topics to initialize...");
            Thread.sleep(3000);

            // =========================================================================
            // STEP 4: Create Materialized Views for All Source Tables
            // =========================================================================
            sendLog(emitter, "Generating Dual-Join Materialized Views for automatic updates...");
            for (String t : physicalTables) {
                String landingTable = getClickHouseLandingTable(t, baseName, request.getSourceConnection());
                String mvName = "mv_" + request.getTargetTable() + "_" + landingTable;
                
                String rotatedSql = rotateQuery(originalQuery, t);
                String sqlWithMeta = addMetadataColsToSelect(rotatedSql, t);
                String rewrittenSql = rewriteQueryForClickHouse(sqlWithMeta, physicalTables, baseName, request.getSourceConnection(), chDb);
                
                StringBuilder mvDdl = new StringBuilder();
                mvDdl.append("CREATE MATERIALIZED VIEW IF NOT EXISTS `").append(chDb).append("`.`").append(mvName).append("`\n");
                mvDdl.append("TO `").append(chDb).append("`.`").append(request.getTargetTable()).append("`\n");
                mvDdl.append("AS ").append(rewrittenSql);
                
                sendLog(emitter, "Creating MV `" + mvName + "` triggered on landing table `" + landingTable + "`...");
                logger.info("Executing MV DDL:\n{}", mvDdl.toString());
                
                try (Connection conn = targetDs.getConnection();
                     Statement stmt = conn.createStatement()) {
                    stmt.execute(mvDdl.toString());
                    sendLog(emitter, "Materialized View `" + mvName + "` registered successfully.");
                } catch (Exception e) {
                    sendLog(emitter, "ERROR: Failed to create Materialized View `" + mvName + "`: " + e.getMessage());
                    throw e;
                }
            }

            // =========================================================================
            // STEP 5: Configure ClickHouse Sink Connector
            // =========================================================================
            sendLog(emitter, "Configuring ClickHouse Sink Connector (" + sinkConnectorName + ")...");
            
            java.util.Map<String, Object> sinkConfig = new java.util.HashMap<>();
            sinkConfig.put("connector.class", "com.clickhouse.kafka.connect.ClickHouseSinkConnector");
            sinkConfig.put("tasks.max", "1");
            sinkConfig.put("topics.regex", topicPrefix + ".*");
            sinkConfig.put("hostname", request.getTargetConnection().getHost() != null && !request.getTargetConnection().getHost().trim().isEmpty() ? request.getTargetConnection().getHost().trim() : "war.darkosuite.com");
            sinkConfig.put("port", String.valueOf(request.getTargetConnection().getPort()));
            sinkConfig.put("username", request.getTargetConnection().getUsername() != null ? request.getTargetConnection().getUsername().trim() : "");
            sinkConfig.put("password", request.getTargetConnection().getPassword());
            sinkConfig.put("database", request.getTargetConnection().getDatabase() != null ? request.getTargetConnection().getDatabase().trim() : "");
            sinkConfig.put("clickhouseSettings", "insert_quorum=1"); // Optional optimization
            sinkConfig.put("key.converter", "org.apache.kafka.connect.json.JsonConverter");
            sinkConfig.put("key.converter.schemas.enable", "false");
            sinkConfig.put("value.converter", "org.apache.kafka.connect.json.JsonConverter");
            sinkConfig.put("value.converter.schemas.enable", "false");
            sinkConfig.put("errors.tolerance", "all"); // Skip poison pill messages from previous failed runs
            
            java.util.Map<String, Object> sinkPayload = new java.util.HashMap<>();
            sinkPayload.put("name", sinkConnectorName);
            sinkPayload.put("config", sinkConfig);
            
            try {
                org.springframework.http.HttpEntity<java.util.Map<String, Object>> sinkEntity = new org.springframework.http.HttpEntity<>(sinkPayload, headers);
                org.springframework.http.ResponseEntity<String> sinkResponse = restTemplate.postForEntity(DEBEZIUM_URL, sinkEntity, String.class);
                sendLog(emitter, "Sink connector registered successfully: " + sinkResponse.getStatusCode());
            } catch (Exception e) {
                sendLog(emitter, "WARNING: Could not register ClickHouse Sink Connector: " + e.getMessage());
            }

            sendLog(emitter, "Pipeline deployment completed successfully.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Deployment interrupted", e);
        } catch (Exception e) {
            logger.error("Failed to deploy pipeline", e);
            try {
                emitter.send(SseEmitter.event().data("DEPLOYMENT FAILED: " + e.getMessage()));
            } catch (IOException ioException) {
                // Ignore
            }
            throw new RuntimeException("Failed to deploy pipeline: " + e.getMessage(), e);
        }
    }

    private List<String> extractPhysicalTables(String sql) {
        List<String> tables = new ArrayList<>();
        try {
            net.sf.jsqlparser.statement.Statement statement = CCJSqlParserUtil.parse(sql);
            if (statement instanceof Select) {
                TablesNamesFinder finder = new TablesNamesFinder();
                tables.addAll(finder.getTableList(statement));
            }
        } catch (Exception e) {
            logger.warn("Could not parse SQL query using JSqlParser: " + e.getMessage());
            // Fallback: simple regex search if parsing fails (for unusual SQL syntax)
            Pattern p = Pattern.compile("(?i)\\bfrom\\s+([a-zA-Z0-9_\\.]+)|\\bjoin\\s+([a-zA-Z0-9_\\.]+)");
            Matcher m = p.matcher(sql);
            while (m.find()) {
                String t = m.group(1) != null ? m.group(1) : m.group(2);
                if (t != null && !tables.contains(t)) {
                    tables.add(t);
                }
            }
        }
        return tables;
    }

    private String getClickHouseLandingTable(String sourceTable, String baseName, ConnectionDetails sourceConn) {
        String clean = sourceTable.replaceAll("[\"``]", "");
        String schema = "";
        String table = clean;
        if (clean.contains(".")) {
            int dotIdx = clean.indexOf('.');
            schema = clean.substring(0, dotIdx);
            table = clean.substring(dotIdx + 1);
        } else {
            schema = "postgresql".equalsIgnoreCase(sourceConn.getType()) ? 
                (sourceConn.getSchema() != null ? sourceConn.getSchema() : "public") : 
                (sourceConn.getDatabase() != null ? sourceConn.getDatabase() : "");
        }
        
        schema = schema.replaceAll("[^a-zA-Z0-9_]", "_");
        table = table.replaceAll("[^a-zA-Z0-9_]", "_");
        
        return "cdc_" + baseName + "_" + schema + "_" + table;
    }

    private List<String> getColumnsForTable(Connection conn, String physicalTable, ConnectionDetails sourceConn) {
        List<String> cols = new ArrayList<>();
        try {
            String schemaName = null;
            String tableName = physicalTable;
            if (physicalTable.contains(".")) {
                int dotIdx = physicalTable.indexOf('.');
                schemaName = physicalTable.substring(0, dotIdx);
                tableName = physicalTable.substring(dotIdx + 1);
            }
            tableName = tableName.replaceAll("[\"``]", "");
            if (schemaName != null) {
                schemaName = schemaName.replaceAll("[\"``]", "");
            } else {
                schemaName = "postgresql".equalsIgnoreCase(sourceConn.getType()) ? 
                    (sourceConn.getSchema() != null ? sourceConn.getSchema() : "public") : 
                    (sourceConn.getDatabase() != null ? sourceConn.getDatabase() : "");
            }
            if (schemaName == null || schemaName.isEmpty()) schemaName = null;
            
            DatabaseMetaData metaData = conn.getMetaData();
            try (ResultSet rs = metaData.getColumns(null, schemaName, tableName, "%")) {
                while (rs.next()) {
                    cols.add(rs.getString("COLUMN_NAME"));
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to get columns for table " + physicalTable, e);
        }
        return cols;
    }

    private String expandWildcardsAndAlias(String sql, DataSource sourceDs, ConnectionDetails sourceConn) {
        try (Connection conn = sourceDs.getConnection()) {
            net.sf.jsqlparser.statement.Statement stmt = CCJSqlParserUtil.parse(sql);
            if (stmt instanceof Select) {
                Select select = (Select) stmt;
                net.sf.jsqlparser.statement.select.PlainSelect plain = select.getPlainSelect();
                if (plain != null) {
                    Map<String, String> aliasToTable = new HashMap<>();
                    String defaultTable = null;
                    if (plain.getFromItem() instanceof net.sf.jsqlparser.schema.Table) {
                        net.sf.jsqlparser.schema.Table t = (net.sf.jsqlparser.schema.Table) plain.getFromItem();
                        String tableName = t.getName();
                        if (t.getSchemaName() != null) {
                            tableName = t.getSchemaName() + "." + tableName;
                        }
                        defaultTable = tableName;
                        if (t.getAlias() != null) {
                            aliasToTable.put(t.getAlias().getName().toLowerCase(), tableName);
                        } else {
                            aliasToTable.put(t.getName().toLowerCase(), tableName);
                        }
                    }
                    if (plain.getJoins() != null) {
                        for (Join j : plain.getJoins()) {
                            if (j.getRightItem() instanceof net.sf.jsqlparser.schema.Table) {
                                net.sf.jsqlparser.schema.Table t = (net.sf.jsqlparser.schema.Table) j.getRightItem();
                                String tableName = t.getName();
                                if (t.getSchemaName() != null) {
                                    tableName = t.getSchemaName() + "." + tableName;
                                }
                                if (defaultTable == null) defaultTable = tableName;
                                if (t.getAlias() != null) {
                                    aliasToTable.put(t.getAlias().getName().toLowerCase(), tableName);
                                } else {
                                    aliasToTable.put(t.getName().toLowerCase(), tableName);
                                }
                            }
                        }
                    }

                    boolean modified = false;
                    List<net.sf.jsqlparser.statement.select.SelectItem<?>> newItems = new ArrayList<>();
                    
                    for (net.sf.jsqlparser.statement.select.SelectItem item : plain.getSelectItems()) {
                        if (item.getExpression() instanceof net.sf.jsqlparser.statement.select.AllTableColumns) {
                            net.sf.jsqlparser.statement.select.AllTableColumns atc = (net.sf.jsqlparser.statement.select.AllTableColumns) item.getExpression();
                            String alias = atc.getTable().getName();
                            String physicalTable = aliasToTable.get(alias.toLowerCase());
                            if (physicalTable != null) {
                                List<String> cols = getColumnsForTable(conn, physicalTable, sourceConn);
                                if (!cols.isEmpty()) {
                                    for (String col : cols) {
                                        net.sf.jsqlparser.statement.select.SelectItem newItem = new net.sf.jsqlparser.statement.select.SelectItem();
                                        net.sf.jsqlparser.schema.Column c = new net.sf.jsqlparser.schema.Column(new net.sf.jsqlparser.schema.Table(alias), col);
                                        newItem.setExpression(c);
                                        // Use lowercase alias to match the JDBC driver output for target table schema
                                        newItem.setAlias(new net.sf.jsqlparser.expression.Alias(col.toLowerCase()));
                                        newItems.add(newItem);
                                    }
                                    modified = true;
                                    continue;
                                }
                            }
                        } else if (item.getExpression() instanceof net.sf.jsqlparser.statement.select.AllColumns) {
                            boolean expandedAny = false;
                            for (Map.Entry<String, String> entry : aliasToTable.entrySet()) {
                                String aliasOrTable = entry.getKey();
                                String physicalTable = entry.getValue();
                                List<String> cols = getColumnsForTable(conn, physicalTable, sourceConn);
                                for (String col : cols) {
                                    net.sf.jsqlparser.statement.select.SelectItem newItem = new net.sf.jsqlparser.statement.select.SelectItem();
                                    net.sf.jsqlparser.schema.Column c = new net.sf.jsqlparser.schema.Column(new net.sf.jsqlparser.schema.Table(aliasOrTable), col);
                                    newItem.setExpression(c);
                                    // Use lowercase alias to match the JDBC driver output for target table schema
                                    newItem.setAlias(new net.sf.jsqlparser.expression.Alias(col.toLowerCase()));
                                    newItems.add(newItem);
                                    expandedAny = true;
                                }
                            }
                            if (expandedAny) {
                                modified = true;
                                continue;
                            }
                        } else if (item.getExpression() instanceof net.sf.jsqlparser.schema.Column) {
                            net.sf.jsqlparser.schema.Column col = (net.sf.jsqlparser.schema.Column) item.getExpression();
                            if (item.getAlias() == null && col.getTable() != null && col.getTable().getName() != null) {
                                item.setAlias(new net.sf.jsqlparser.expression.Alias(col.getColumnName().toLowerCase()));
                                modified = true;
                            }
                        }
                        
                        // Force any existing alias to lowercase to match PostgreSQL JDBC unquoted identifier behavior
                        if (item.getAlias() != null) {
                            String oldAlias = item.getAlias().getName();
                            if (!oldAlias.equals(oldAlias.toLowerCase())) {
                                net.sf.jsqlparser.expression.Alias newAlias = new net.sf.jsqlparser.expression.Alias(oldAlias.toLowerCase());
                                newAlias.setUseAs(item.getAlias().isUseAs());
                                item.setAlias(newAlias);
                                modified = true;
                            }
                        }
                        
                        newItems.add(item);
                    }
                    if (modified) {
                        plain.getSelectItems().clear();
                        plain.getSelectItems().addAll(newItems);
                        return select.toString();
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Could not auto-alias SQL query using JSqlParser: " + e.getMessage());
        }
        return sql;
    }

    private String rewriteQueryForClickHouse(String sql, List<String> physicalTables, String baseName, ConnectionDetails sourceConn, String chDb) {
        String rewrittenSql = sql;
        for (String t : physicalTables) {
            String landingTable = getClickHouseLandingTable(t, baseName, sourceConn);
            String escapedLanding = "`" + chDb + "`.`" + landingTable + "`";
            
            String patternStrWithSchema = "\\b" + Pattern.quote(t) + "\\b";
            rewrittenSql = rewrittenSql.replaceAll("(?i)" + patternStrWithSchema, escapedLanding);
            
            if (t.contains(".")) {
                String shortTable = t.substring(t.indexOf('.') + 1);
                String patternStrShort = "\\b" + Pattern.quote(shortTable) + "\\b";
                rewrittenSql = rewrittenSql.replaceAll("(?i)(?<!\\.)" + patternStrShort + "(?!\\.)", escapedLanding);
            }
        }
        return rewrittenSql;
    }

    private String rotateQuery(String sql, String triggerTable) {
        try {
            net.sf.jsqlparser.statement.Statement stmt = CCJSqlParserUtil.parse(sql);
            if (!(stmt instanceof Select)) {
                return sql;
            }
            Select select = (Select) stmt;
            PlainSelect plain = select.getPlainSelect();
            if (plain == null) {
                return sql;
            }
            
            FromItem currentFrom = plain.getFromItem();
            List<Join> joins = plain.getJoins();
            
            if (isTableMatch(currentFrom, triggerTable)) {
                return select.toString();
            }
            
            Join targetJoin = null;
            int targetIdx = -1;
            if (joins != null) {
                for (int i = 0; i < joins.size(); i++) {
                    Join j = joins.get(i);
                    if (isTableMatch(j.getRightItem(), triggerTable)) {
                        targetJoin = j;
                        targetIdx = i;
                        break;
                    }
                }
            }
            
            if (targetJoin == null) {
                return select.toString();
            }
            
            Join newJoin = new Join();
            newJoin.setRightItem(currentFrom);
            newJoin.setLeft(true);
            newJoin.setOnExpression(targetJoin.getOnExpression());
            
            plain.setFromItem(targetJoin.getRightItem());
            
            List<Join> newJoins = new ArrayList<>(joins);
            newJoins.remove(targetIdx);
            newJoins.add(newJoin);
            plain.setJoins(newJoins);
            
            return select.toString();
        } catch (Exception e) {
            logger.warn("Failed to rotate query for trigger table " + triggerTable + ": " + e.getMessage());
            return sql;
        }
    }

    private boolean isTableMatch(FromItem item, String tableName) {
        if (item instanceof Table) {
            Table t = (Table) item;
            String fqn = t.getFullyQualifiedName();
            return fqn.equalsIgnoreCase(tableName) || t.getName().equalsIgnoreCase(tableName);
        }
        return false;
    }

    private String getTableAlias(String sql, String tableName) {
        try {
            net.sf.jsqlparser.statement.Statement stmt = CCJSqlParserUtil.parse(sql);
            if (stmt instanceof Select) {
                Select select = (Select) stmt;
                PlainSelect plain = select.getPlainSelect();
                if (plain != null) {
                    FromItem fromItem = plain.getFromItem();
                    if (isTableMatch(fromItem, tableName) && fromItem.getAlias() != null) {
                        return fromItem.getAlias().getName();
                    }
                    List<Join> joins = plain.getJoins();
                    if (joins != null) {
                        for (Join j : joins) {
                            if (isTableMatch(j.getRightItem(), tableName) && j.getRightItem().getAlias() != null) {
                                return j.getRightItem().getAlias().getName();
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {}
        return null;
    }

    private String addMetadataColsToSelect(String sql, String triggerTable) {
        try {
            net.sf.jsqlparser.statement.Statement stmt = CCJSqlParserUtil.parse(sql);
            if (!(stmt instanceof Select)) {
                return sql;
            }
            Select select = (Select) stmt;
            PlainSelect plain = select.getPlainSelect();
            if (plain == null) {
                return sql;
            }
            
            String alias = getTableAlias(sql, triggerTable);
            String prefix = (alias != null && !alias.isEmpty()) ? alias + "." : "";
            
            net.sf.jsqlparser.statement.select.SelectItem verItem = new net.sf.jsqlparser.statement.select.SelectItem();
            verItem.setExpression(net.sf.jsqlparser.parser.CCJSqlParserUtil.parseExpression(prefix + "version"));
            verItem.setAlias(new net.sf.jsqlparser.expression.Alias("version"));
            
            net.sf.jsqlparser.statement.select.SelectItem delItem = new net.sf.jsqlparser.statement.select.SelectItem();
            delItem.setExpression(net.sf.jsqlparser.parser.CCJSqlParserUtil.parseExpression(prefix + "is_deleted"));
            delItem.setAlias(new net.sf.jsqlparser.expression.Alias("is_deleted"));
            
            plain.addSelectItems(verItem, delItem);
            return select.toString();
        } catch (Exception e) {
            logger.warn("Failed to inject metadata columns to select list: " + e.getMessage());
            return sql;
        }
    }

    private String mapJdbcTypeToClickHouse(int jdbcType, int precision, int scale, String typeName) {
        String lowerName = typeName.toLowerCase();
        if (lowerName.contains("int2") || lowerName.contains("smallint")) return "Int16";
        if (lowerName.contains("int4") || lowerName.contains("integer") || lowerName.contains("int")) return "Int32";
        if (lowerName.contains("int8") || lowerName.contains("bigint")) return "Int64";
        if (lowerName.contains("float") || lowerName.contains("real")) return "Float32";
        if (lowerName.contains("double") || lowerName.contains("numeric") || lowerName.contains("decimal")) {
            if (precision > 0 && precision <= 38) {
                return "Decimal(" + precision + ", " + (scale >= 0 ? scale : 0) + ")";
            }
            return "Decimal(18, 4)";
        }
        if (lowerName.contains("bool")) return "UInt8";
        if (lowerName.contains("date")) return "Date";
        if (lowerName.contains("timestamp") || lowerName.contains("datetime") || lowerName.contains("time")) {
            return "DateTime64(3)";
        }
        return "String";
    }
}

