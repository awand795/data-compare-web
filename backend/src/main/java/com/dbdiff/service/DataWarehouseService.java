package com.dbdiff.service;

import com.dbdiff.model.DataWarehouseDeployRequest;
import com.dbdiff.model.ConnectionDetails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import java.util.Properties;
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
import org.springframework.http.client.SimpleClientHttpRequestFactory;

@Service
public class DataWarehouseService {
    private static final Logger logger = LoggerFactory.getLogger(DataWarehouseService.class);
    private final RestTemplate restTemplate;
    private static final String DEBEZIUM_URL = "http://debezium:8083/connectors";

    public DataWarehouseService() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000); // 5 seconds
        factory.setReadTimeout(10000);   // 10 seconds
        this.restTemplate = new RestTemplate(factory);
    }

    @Autowired
    private ConnectionManagerService connectionManagerService;

    @Autowired
    private com.dbdiff.repository.PipelineMetadataRepository pipelineMetadataRepository;

    @Autowired
    private com.dbdiff.repository.ConnectionRepository connectionRepository;

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
            
            // Generate unique names for connectors based on a single deployment ID
            String baseName = request.getSourceConnection().getName().replaceAll("[^a-zA-Z0-9_]", "_").toLowerCase();
            long deployId = System.currentTimeMillis();
            String sourceConnectorName = "source-" + baseName + "-" + deployId;
            String sinkConnectorName = "sink-clickhouse-" + request.getTargetTable().replaceAll("[^a-zA-Z0-9_-]", "") + "-" + deployId;
            
            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);

            // =========================================================================
            // STEP 0.5: Cleanup Old Connectors and Replication Slots
            // =========================================================================
            sendLog(emitter, "Cleaning up old pipeline connectors and replication slots...");
            try {
                // Fetch all registered connectors from Debezium
                String[] connectors = restTemplate.getForObject(DEBEZIUM_URL, String[].class);
                if (connectors != null) {
                    for (String cName : connectors) {
                        // Check if it belongs to this pipeline target or source
                        if (cName.startsWith("source-" + baseName) || 
                            cName.startsWith("sink-clickhouse-" + request.getTargetTable().replaceAll("[^a-zA-Z0-9_-]", ""))) {
                            sendLog(emitter, "Deleting old connector: " + cName);
                            try {
                                restTemplate.delete(DEBEZIUM_URL + "/" + cName);
                            } catch (Exception ex) {
                                logger.warn("Failed to delete connector " + cName, ex);
                            }
                        }
                    }
                }
            } catch (Exception ex) {
                logger.warn("Failed to fetch/delete old connectors from Debezium", ex);
            }

            // Cleanup replication slots in Postgres source DB
            DataSource sourceDsForCleanup = connectionManagerService.getDataSource(request.getSourceConnection());
            try (Connection pgConn = sourceDsForCleanup.getConnection();
                 Statement pgStmt = pgConn.createStatement()) {
                String findSlotsSql = "SELECT slot_name, active_pid FROM pg_replication_slots WHERE slot_name LIKE '%" + baseName.replaceAll("-", "_") + "%'";
                List<Map<String, Object>> activeSlots = new ArrayList<>();
                try (ResultSet rs = pgStmt.executeQuery(findSlotsSql)) {
                    while (rs.next()) {
                        Map<String, Object> map = new HashMap<>();
                        map.put("slot_name", rs.getString("slot_name"));
                        map.put("active_pid", rs.getObject("active_pid"));
                        activeSlots.add(map);
                    }
                }
                
                for (Map<String, Object> slot : activeSlots) {
                    String slotName = (String) slot.get("slot_name");
                    Number activePid = (Number) slot.get("active_pid");
                    if (activePid != null) {
                        sendLog(emitter, "Terminating active slot PID " + activePid + " for slot " + slotName);
                        pgStmt.execute("SELECT pg_terminate_backend(" + activePid.intValue() + ")");
                        Thread.sleep(1000);
                    }
                    sendLog(emitter, "Dropping pg replication slot: " + slotName);
                    pgStmt.execute("SELECT pg_drop_replication_slot('" + slotName + "')");
                }
            } catch (Exception ex) {
                logger.warn("Failed to clean up old replication slots in Postgres", ex);
            }

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
                                boolean found = false;
                                for (ColumnInfo col : targetColumns) {
                                    if (col.name.equalsIgnoreCase(pkCol)) {
                                        matchedCol = col.name;
                                        found = true;
                                        break;
                                    }
                                }
                                if (!found) {
                                    // Try to find if there is an alias ending with this pkCol (e.g. b_seq for seq)
                                    for (ColumnInfo col : targetColumns) {
                                        if (col.name.toLowerCase().endsWith("_" + pkCol.toLowerCase())) {
                                            matchedCol = col.name;
                                            found = true;
                                            break;
                                        }
                                    }
                                }
                                // Only add to compositePKs if the column actually exists in the target table
                                if (found) {
                                    compositePKs.add(matchedCol);
                                } else {
                                    logger.warn("Primary key column '" + pkCol + "' from table '" + tableName + "' was not found in the SELECT query. It will be omitted from the target ClickHouse ORDER BY clause.");
                                }
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
            java.util.Map<String, java.util.Set<String>> tableToPKs = new java.util.HashMap<>();
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
                tableToPKs.put(t, tablePKs);
                
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
                
                // Truncate existing landing table to ensure snapshot counts start fresh
                try (Connection conn = targetDs.getConnection();
                     Statement stmt = conn.createStatement()) {
                    stmt.execute("TRUNCATE TABLE `" + chDb + "`.`" + landingTable + "`");
                    sendLog(emitter, "Truncated existing landing table `" + landingTable + "`.");
                } catch (Exception e) {
                    // Ignore
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
            
            // Save the original query + connection metadata to repository
            try {
                pipelineMetadataRepository.savePipelineMetadata(
                    String.valueOf(deployId),
                    request.getQuery(),
                    request.getSourceConnection().getId(),
                    request.getTargetTable(),
                    request.getTargetConnection().getId()
                );
            } catch (Exception e) {
                logger.warn("Could not save original query to metadata repository", e);
            }
            
            sendLog(emitter, "Creating target table `" + request.getTargetTable() + "` in ClickHouse...");
            try (Connection conn = targetDs.getConnection();
                 Statement stmt = conn.createStatement()) {
                // Do not drop as view, it is a regular table.
                stmt.execute(targetDdl.toString());
                sendLog(emitter, "Target table `" + request.getTargetTable() + "` verified/created.");
            } catch (Exception e) {
                sendLog(emitter, "ERROR: Target table creation failed: " + e.getMessage());
                throw e;
            }

            // Truncate target table
            try (Connection conn = targetDs.getConnection();
                 Statement stmt = conn.createStatement()) {
                stmt.execute("TRUNCATE TABLE `" + chDb + "`.`" + request.getTargetTable() + "`");
                sendLog(emitter, "Truncated target table `" + request.getTargetTable() + "`.");
            } catch (Exception e) {
                // Ignore
            }

            // Create a convenience VIEW that automatically applies FINAL and filters deleted rows
            String viewName = "v_" + request.getTargetTable();
            String viewDdl = "CREATE OR REPLACE VIEW `" + chDb + "`.`" + viewName + "` AS " +
                             "SELECT * FROM `" + chDb + "`.`" + request.getTargetTable() + "` FINAL WHERE is_deleted = 0";
            sendLog(emitter, "Creating convenience target VIEW `" + viewName + "` in ClickHouse...");
            try (Connection conn = targetDs.getConnection();
                 Statement stmt = conn.createStatement()) {
                stmt.execute(viewDdl);
                sendLog(emitter, "Convenience VIEW `" + viewName + "` created successfully.");
            } catch (Exception e) {
                sendLog(emitter, "WARNING: Convenience VIEW creation failed: " + e.getMessage());
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
            sendLog(emitter, "Generating Materialized Views for automatic updates...");
            for (String t : physicalTables) {
                String landingTable = getClickHouseLandingTable(t, baseName, request.getSourceConnection());
                String mvName = "mv_" + request.getTargetTable() + "_" + landingTable;
                
                String rotatedSql = rotateQuery(originalQuery, t);
                String sqlWithMeta = addMetadataColsToSelect(rotatedSql, t);
                
                String rewrittenSql;
                if (physicalTables.size() > 1) {
                    // For JOIN queries, add PK filters to the WHERE clause (avoiding subqueries/FINAL which are disallowed in MVs)
                    String sqlWithFilters = addPKFiltersToWhere(sqlWithMeta, physicalTables, tableToPKs);
                    rewrittenSql = rewriteQueryForClickHouse(sqlWithFilters, physicalTables, baseName, request.getSourceConnection(), chDb);
                    // Change LEFT JOIN -> INNER JOIN to prevent ghost rows
                    rewrittenSql = rewrittenSql.replaceAll("(?i)\\bLEFT\\s+JOIN\\b", "INNER JOIN");
                } else {
                    rewrittenSql = rewriteQueryForClickHouse(sqlWithMeta, physicalTables, baseName, request.getSourceConnection(), chDb);
                }
                
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
            List<String> expectedTopics = new java.util.ArrayList<>();
            for (String t : formattedTables) {
                String cleanTable = t.replace(".", "_");
                expectedTopics.add(topicPrefix + cleanTable);
            }
            sinkConfig.put("topics", String.join(",", expectedTopics));
            // sinkConfig.put("topics.regex", topicPrefix + ".*");
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

            // Give connectors time to initialize and start consuming before polling
            sendLog(emitter, "Waiting for connectors to initialize (10s)...");
            Thread.sleep(10000);

            // =========================================================================
            // STEP 6: Wait for Snapshot to Complete
            // =========================================================================
            {
                sendLog(emitter, "Waiting for initial snapshot to complete and populate the target table...");
                
                // Poll landing table row counts until they stabilize (unchanged for 3 consecutive checks)
                int pollIntervalMs = 5000; // check every 5 seconds
                int maxWaitSeconds = 300; // 5 minutes max
                int stableCount = 0;
                int requiredStableChecks = 3;
                long previousTotalRows = -1;
                long startTime = System.currentTimeMillis();
                
                while (stableCount < requiredStableChecks && (System.currentTimeMillis() - startTime) < maxWaitSeconds * 1000L) {
                    Thread.sleep(pollIntervalMs);
                    
                    long totalRows = 0;
                    try (Connection conn = targetDs.getConnection();
                         Statement stmt = conn.createStatement()) {
                        for (String t : physicalTables) {
                            String landingTable = getClickHouseLandingTable(t, baseName, request.getSourceConnection());
                            try (java.sql.ResultSet rs = stmt.executeQuery("SELECT count() FROM `" + chDb + "`.`" + landingTable + "`")) {
                                if (rs.next()) totalRows += rs.getLong(1);
                            }
                        }
                    }
                    
                    if (totalRows == previousTotalRows && totalRows > 0) {
                        stableCount++;
                        long elapsed = (System.currentTimeMillis() - startTime) / 1000;
                        sendLog(emitter, "Landing tables stable (" + totalRows + " total rows, check " + stableCount + "/" + requiredStableChecks + ", " + elapsed + "s elapsed)");
                    } else {
                        stableCount = 0;
                        long elapsed = (System.currentTimeMillis() - startTime) / 1000;
                        sendLog(emitter, "Snapshot in progress... (" + totalRows + " total rows in landing tables, " + elapsed + "s elapsed)");
                    }
                    previousTotalRows = totalRows;
                }
                
                // Give some extra time for the sink connector to flush all data to ClickHouse
                sendLog(emitter, "Waiting for sink connector to flush data to ClickHouse...");
                Thread.sleep(10000);
                sendLog(emitter, "Target table populated successfully with initial snapshot data.");
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

    private String rewriteQueryForClickHouseView(String sql, List<String> physicalTables, String baseName, ConnectionDetails sourceConn, String chDb, java.util.Map<String, java.util.Set<String>> tableToPKs) {
        String rewrittenSql = sql;
        for (String t : physicalTables) {
            String landingTable = getClickHouseLandingTable(t, baseName, sourceConn);
            java.util.Set<String> pks = tableToPKs.get(t);
            StringBuilder pkFilters = new StringBuilder();
            if (pks != null) {
                for (String pk : pks) {
                    pkFilters.append(" AND not(isNull(`").append(pk).append("`)) AND toString(`").append(pk).append("`) != ''");
                }
            }
            String subquery = "(SELECT * FROM `" + chDb + "`.`" + landingTable + "` FINAL WHERE is_deleted = 0" + pkFilters.toString() + ")";
            
            String patternStrWithSchema = "\\b" + Pattern.quote(t) + "\\b";
            rewrittenSql = rewrittenSql.replaceAll("(?i)" + patternStrWithSchema, subquery);
            
            if (t.contains(".")) {
                String shortTable = t.substring(t.indexOf('.') + 1);
                String patternStrShort = "\\b" + Pattern.quote(shortTable) + "\\b";
                rewrittenSql = rewrittenSql.replaceAll("(?i)(?<!\\.)" + patternStrShort + "(?!\\.)", subquery);
            }
        }
        return rewrittenSql;
    }

    private String addPKFiltersToWhere(String sql, List<String> physicalTables, java.util.Map<String, java.util.Set<String>> tableToPKs) {
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
            
            StringBuilder conds = new StringBuilder();
            for (String t : physicalTables) {
                String alias = getTableAlias(sql, t);
                String prefix = (alias != null && !alias.isEmpty()) ? alias + "." : "";
                java.util.Set<String> pks = tableToPKs.get(t);
                if (pks != null) {
                    for (String pk : pks) {
                        if (conds.length() > 0) {
                            conds.append(" AND ");
                        }
                        conds.append("not(isNull(").append(prefix).append("`").append(pk).append("`))");
                        conds.append(" AND toString(").append(prefix).append("`").append(pk).append("`) != ''");
                    }
                }
            }
            
            if (conds.length() > 0) {
                net.sf.jsqlparser.expression.Expression newExpr = CCJSqlParserUtil.parseCondExpression(conds.toString());
                net.sf.jsqlparser.expression.Expression currentWhere = plain.getWhere();
                if (currentWhere == null) {
                    plain.setWhere(newExpr);
                } else {
                    net.sf.jsqlparser.expression.operators.conditional.AndExpression and = new net.sf.jsqlparser.expression.operators.conditional.AndExpression(currentWhere, newExpr);
                    plain.setWhere(and);
                }
            }
            
            return select.toString();
        } catch (Exception e) {
            logger.warn("Failed to inject PK filters to WHERE clause: " + e.getMessage());
            return sql;
        }
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

    private Long getConnectorLag(String connectorName) {
        if (!connectorName.startsWith("sink-")) return null;
        String groupId = "connect-" + connectorName;
        Properties props = new Properties();
        props.put("bootstrap.servers", "kafka:9092");
        try (AdminClient admin = AdminClient.create(props)) {
            java.util.Map<TopicPartition, OffsetAndMetadata> groupOffsets = admin.listConsumerGroupOffsets(groupId).partitionsToOffsetAndMetadata().get();
            if (groupOffsets.isEmpty()) return null;
            
            java.util.Map<TopicPartition, OffsetSpec> requestOffsets = new HashMap<>();
            for (TopicPartition tp : groupOffsets.keySet()) {
                requestOffsets.put(tp, OffsetSpec.latest());
            }
            java.util.Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> endOffsets = admin.listOffsets(requestOffsets).all().get();
            
            long totalLag = 0;
            for (TopicPartition tp : groupOffsets.keySet()) {
                long currentOffset = groupOffsets.get(tp).offset();
                long endOffset = endOffsets.get(tp).offset();
                if (endOffset > currentOffset) {
                    totalLag += (endOffset - currentOffset);
                }
            }
            return totalLag;
        } catch (Exception e) {
            logger.warn("Could not fetch lag for " + connectorName + ": " + e.getMessage());
            return null;
        }
    }

    public java.util.List<java.util.Map<String, Object>> getPipelinesStatus() {
        try {
            String url = DEBEZIUM_URL + "?expand=status&expand=info";
            org.springframework.http.ResponseEntity<java.util.Map> response = restTemplate.getForEntity(url, java.util.Map.class);
            if (response.getBody() != null) {
                java.util.Map<String, java.util.Map<String, Object>> body = response.getBody();
                java.util.List<java.util.Map<String, Object>> pipelines = new ArrayList<>();
                for (java.util.Map.Entry<String, java.util.Map<String, Object>> entry : body.entrySet()) {
                    String name = entry.getKey();
                    java.util.Map<String, Object> details = entry.getValue();
                    java.util.Map<String, Object> statusObj = (java.util.Map<String, Object>) details.get("status");
                    
                    java.util.Map<String, Object> pipelineInfo = new HashMap<>();
                    pipelineInfo.put("name", name);
                    pipelineInfo.put("type", name.startsWith("sink-") ? "SINK" : "SOURCE");
                    
                    if (statusObj != null) {
                        java.util.Map<String, Object> connectorStatus = (java.util.Map<String, Object>) statusObj.get("connector");
                        pipelineInfo.put("state", connectorStatus != null ? connectorStatus.get("state") : "UNKNOWN");
                        pipelineInfo.put("worker_id", connectorStatus != null ? connectorStatus.get("worker_id") : "");
                        
                        List<Map<String, Object>> tasks = (List<Map<String, Object>>) statusObj.get("tasks");
                        if (tasks != null && !tasks.isEmpty()) {
                            pipelineInfo.put("task_state", tasks.get(0).get("state"));
                            if (tasks.get(0).containsKey("trace")) {
                                pipelineInfo.put("trace", tasks.get(0).get("trace"));
                            }
                        }

                        if (name.startsWith("sink-") && "RUNNING".equals(pipelineInfo.get("task_state"))) {
                            Long lag = getConnectorLag(name);
                            if (lag != null) pipelineInfo.put("lag", lag);
                        }
                    } else {
                        pipelineInfo.put("state", "UNKNOWN");
                    }
                    pipelines.add(pipelineInfo);
                }
                return pipelines;
            }
        } catch (Exception e) {
            logger.error("Failed to fetch pipelines status", e);
        }
        return Collections.emptyList();
    }

    public void manageConnector(String connectorName, String action) {
        String url = DEBEZIUM_URL + "/" + connectorName + "/" + action;
        try {
            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            headers.set("Accept", "application/json");
            headers.set("Content-Type", "application/json");
            org.springframework.http.HttpEntity<String> entity = new org.springframework.http.HttpEntity<>("", headers);

            if ("restart".equalsIgnoreCase(action)) {
                restTemplate.exchange(url + "?includeTasks=true&onlyFailed=true", org.springframework.http.HttpMethod.POST, entity, String.class);
            } else if ("pause".equalsIgnoreCase(action) || "resume".equalsIgnoreCase(action)) {
                restTemplate.exchange(url, org.springframework.http.HttpMethod.PUT, entity, String.class);
            } else {
                throw new IllegalArgumentException("Invalid action: " + action);
            }
        } catch (Exception e) {
            logger.error("Failed to " + action + " connector " + connectorName, e);
            throw new RuntimeException("Failed to " + action + " connector: " + e.getMessage());
        }
    }

    public void deleteConnector(String connectorName) {
        String url = DEBEZIUM_URL + "/" + connectorName;
        try {
            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            headers.set("Accept", "application/json");
            headers.set("Content-Type", "application/json");
            org.springframework.http.HttpEntity<String> entity = new org.springframework.http.HttpEntity<>("", headers);
            restTemplate.exchange(url, org.springframework.http.HttpMethod.DELETE, entity, String.class);
        } catch (Exception e) {
            logger.error("Failed to delete connector " + connectorName, e);
            throw new RuntimeException("Failed to delete connector: " + e.getMessage());
        }
    }

    public void deletePipeline(String deployId) {
        try {
            String[] connectors = restTemplate.getForObject(DEBEZIUM_URL, String[].class);
            if (connectors == null) return;
            
            java.util.List<String> toDelete = new java.util.ArrayList<>();
            String sinkConnector = null;
            String targetTable = null;
            
            for (String c : connectors) {
                if (c.endsWith("-" + deployId)) {
                    toDelete.add(c);
                    if (c.startsWith("sink-clickhouse-")) {
                        sinkConnector = c;
                        targetTable = c.substring("sink-clickhouse-".length(), c.lastIndexOf("-" + deployId));
                    }
                }
            }
            
            if (sinkConnector != null && targetTable != null) {
                try {
                    java.util.Map<String, Object> config = getConnectorConfig(sinkConnector);
                    String hostname = (String) config.get("hostname");
                    String port = (String) config.get("port");
                    String db = (String) config.get("database");
                    String username = (String) config.get("username");
                    String password = (String) config.get("password");
                    
                    if (hostname != null && port != null && db != null) {
                        ConnectionDetails chDetails = new ConnectionDetails();
                        chDetails.setType("clickhouse");
                        chDetails.setHost(hostname);
                        chDetails.setPort(Integer.parseInt(port));
                        chDetails.setDatabase(db);
                        chDetails.setUsername(username);
                        chDetails.setPassword(password);
                        
                        DataSource ds = connectionManagerService.getDataSource(chDetails);
                        try (java.sql.Connection conn = ds.getConnection();
                             java.sql.Statement stmt = conn.createStatement()) {
                             
                            stmt.execute("DROP VIEW IF EXISTS `" + db + "`.`v_" + targetTable + "`");
                            
                            String findMVs = "SELECT name FROM system.tables WHERE database = '" + db + "' AND name LIKE 'mv_%'";
                            java.util.List<String> mvsToDrop = new java.util.ArrayList<>();
                            try (java.sql.ResultSet rs = stmt.executeQuery(findMVs)) {
                                while (rs.next()) {
                                    String name = rs.getString("name");
                                    if (name.startsWith("mv_" + targetTable + "_")) {
                                        mvsToDrop.add(name);
                                    }
                                }
                            }
                            
                            for (String mv : mvsToDrop) {
                                stmt.execute("DROP VIEW IF EXISTS `" + db + "`.`" + mv + "`");
                                String prefix = "mv_" + targetTable + "_";
                                if (mv.startsWith(prefix)) {
                                    String landingTable = mv.substring(prefix.length());
                                    // Cek apakah masih ada MV lain yang memakai tabel CDC/Landing ini
                                    try (java.sql.ResultSet rsDep = stmt.executeQuery(
                                            "SELECT count() FROM system.dependencies WHERE database = '" + db + "' AND table = '" + landingTable + "'")) {
                                        if (rsDep.next() && rsDep.getInt(1) == 0) {
                                            // Tidak ada yang pakai lagi, aman untuk dihapus
                                            stmt.execute("DROP TABLE IF EXISTS `" + db + "`.`" + landingTable + "`");
                                        } else {
                                            sendLog(emitter, "CDC landing table `" + landingTable + "` is still used by other pipelines. Not dropping.");
                                        }
                                    }
                                }
                            }
                            
                            stmt.execute("DROP TABLE IF EXISTS `" + db + "`.`" + targetTable + "`");
                        }
                    }
                } catch (Exception e) {
                    logger.warn("Failed to cleanup ClickHouse tables for pipeline " + deployId, e);
                }
            }
            
            for (String c : toDelete) {
                deleteConnector(c);
            }
            
            // Also delete metadata
            try {
                pipelineMetadataRepository.deletePipelineMetadata(deployId);
            } catch (Exception ignored) {}
            
        } catch (Exception e) {
            logger.error("Failed to delete pipeline " + deployId, e);
            throw new RuntimeException("Failed to delete pipeline: " + e.getMessage());
        }
    }

    public java.util.Map<String, Object> getPipelineMetadata(String deployId) {
        return pipelineMetadataRepository.getPipelineMetadata(deployId);
    }

    public void updatePipelineQuery(String deployId, String newQuery, SseEmitter emitter) throws Exception {
        try {
            sendLog(emitter, "Starting query update for pipeline: " + deployId);

            // ── 1. Load stored metadata ──────────────────────────────────────────────
            java.util.Map<String, Object> meta = pipelineMetadataRepository.getPipelineMetadata(deployId);
            if (meta == null) throw new RuntimeException("Pipeline metadata not found for deployId: " + deployId);

            String oldQuery = (String) meta.get("query");
            String targetTable = (String) meta.get("target_table");
            String sourceConnectionId = (String) meta.get("source_connection_id");
            String targetConnectionId = (String) meta.get("target_connection_id");

            if (targetTable == null) throw new RuntimeException("Target table name not found in metadata.");

            sendLog(emitter, "Target table: " + targetTable);

            // ── 2. Look up ConnectionDetails from internal DB ────────────────────────
            ConnectionDetails sourceConn = connectionRepository.findById(sourceConnectionId);
            ConnectionDetails targetConn = connectionRepository.findById(targetConnectionId);
            if (sourceConn == null) throw new RuntimeException("Source connection not found: " + sourceConnectionId);
            if (targetConn == null) throw new RuntimeException("Target connection not found: " + targetConnectionId);

            DataSource sourceDs = connectionManagerService.getDataSource(sourceConn);
            DataSource targetDs = connectionManagerService.getDataSource(targetConn);
            String chDb = targetConn.getDatabase();
            if (chDb == null || chDb.isEmpty()) chDb = "default";

            // ── 3. Run dry-run on old and new queries to get column lists ────────────
            sendLog(emitter, "Analyzing old query schema...");
            java.util.List<ColumnInfo> oldCols = getQueryColumns(oldQuery, sourceDs, sourceConn);
            sendLog(emitter, "Analyzing new query schema...");
            String expandedNewQuery = expandWildcardsAndAlias(newQuery, sourceDs, sourceConn);
            java.util.List<ColumnInfo> newCols = getQueryColumns(expandedNewQuery, sourceDs, sourceConn);

            // ── 4. Compute diff ──────────────────────────────────────────────────────
            java.util.Set<String> oldColNames = new java.util.LinkedHashSet<>();
            java.util.Map<String, ColumnInfo> oldColMap = new java.util.LinkedHashMap<>();
            for (ColumnInfo c : oldCols) { oldColNames.add(c.name); oldColMap.put(c.name, c); }

            java.util.Set<String> newColNames = new java.util.LinkedHashSet<>();
            java.util.Map<String, ColumnInfo> newColMap = new java.util.LinkedHashMap<>();
            for (ColumnInfo c : newCols) { newColNames.add(c.name); newColMap.put(c.name, c); }

            java.util.List<String> addedCols = new java.util.ArrayList<>();
            java.util.List<String> removedCols = new java.util.ArrayList<>();
            for (String n : newColNames) { if (!oldColNames.contains(n)) addedCols.add(n); }
            for (String o : oldColNames) { if (!newColNames.contains(o)) removedCols.add(o); }

            if (addedCols.isEmpty() && removedCols.isEmpty()) {
                sendLog(emitter, "No column changes detected. Saving updated query...");
                pipelineMetadataRepository.updateQuery(deployId, newQuery);
                sendLog(emitter, "Query saved successfully. No schema changes needed.");
                emitter.complete();
                return;
            }

            sendLog(emitter, "Columns to ADD: " + (addedCols.isEmpty() ? "none" : String.join(", ", addedCols)));
            sendLog(emitter, "Columns to DROP: " + (removedCols.isEmpty() ? "none" : String.join(", ", removedCols)));

            // ── 5. ALTER target table: add/drop columns ──────────────────────────────
            try (java.sql.Connection conn = targetDs.getConnection();
                 java.sql.Statement stmt = conn.createStatement()) {

                for (String col : removedCols) {
                    sendLog(emitter, "Dropping column `" + col + "` from target table...");
                    stmt.execute("ALTER TABLE `" + chDb + "`.`" + targetTable + "` DROP COLUMN IF EXISTS `" + col + "`");
                }
                for (String col : addedCols) {
                    ColumnInfo ci = newColMap.get(col);
                    
                    // Menentukan posisi kolom (AFTER/FIRST) berdasarkan urutannya di query baru
                    String afterClause = "";
                    for (int i = 0; i < newCols.size(); i++) {
                        if (newCols.get(i).name.equals(col)) {
                            if (i == 0) {
                                afterClause = " FIRST";
                            } else {
                                afterClause = " AFTER `" + newCols.get(i - 1).name + "`";
                            }
                            break;
                        }
                    }

                    sendLog(emitter, "Adding column `" + col + "` (" + ci.clickhouseType + ") to target table...");
                    stmt.execute("ALTER TABLE `" + chDb + "`.`" + targetTable + "` ADD COLUMN IF NOT EXISTS `" + col + "` Nullable(" + ci.clickhouseType + ")" + afterClause);
                }
            }
            sendLog(emitter, "Target table schema updated.");

            // ── Recreate convenience VIEW so new/dropped columns are reflected ────────
            try (java.sql.Connection vConn = targetDs.getConnection();
                 java.sql.Statement vStmt = vConn.createStatement()) {
                String viewDdl = "CREATE OR REPLACE VIEW `" + chDb + "`.`v_" + targetTable + "` AS " +
                    "SELECT * FROM `" + chDb + "`.`" + targetTable + "` FINAL WHERE is_deleted = 0";
                vStmt.execute(viewDdl);
                sendLog(emitter, "View `v_" + targetTable + "` recreated with updated columns.");
            } catch (Exception ve) {
                sendLog(emitter, "WARNING: Could not recreate view v_" + targetTable + ": " + ve.getMessage());
            }

            // ── 6. Recreate Materialized Views with new column list ──────────────────
            sendLog(emitter, "Recreating Materialized Views with new column mapping...");
            java.util.List<String> physicalTables = extractPhysicalTables(expandedNewQuery);

            java.util.Map<String, java.util.Set<String>> tableToPKs = new java.util.HashMap<>();
            for (String t : physicalTables) {
                java.util.Set<String> pks = new java.util.LinkedHashSet<>();
                try (java.sql.Connection conn = sourceDs.getConnection()) {
                    java.sql.DatabaseMetaData metaData = conn.getMetaData();
                    String schemaName = t.contains(".") ? t.substring(0, t.indexOf('.')) : sourceConn.getSchema();
                    String tableName = t.contains(".") ? t.substring(t.indexOf('.')+1) : t;
                    tableName = tableName.replaceAll("[\"``]", "");
                    if (schemaName != null) schemaName = schemaName.replaceAll("[\"``]", "");
                    try (java.sql.ResultSet pkRs = metaData.getPrimaryKeys(null, schemaName, tableName)) {
                        while (pkRs.next()) { String pk = pkRs.getString("COLUMN_NAME"); if (pk != null) pks.add(pk); }
                    }
                } catch (Exception ignored) {}
                tableToPKs.put(t, pks);
            }

            try (java.sql.Connection conn = targetDs.getConnection();
                 java.sql.Statement stmt = conn.createStatement()) {

                for (String t : physicalTables) {
                    // ── Find the ACTUAL landing table in ClickHouse by querying system.tables ──
                    // CDC tables are named: cdc_{baseName}_{schema}_{table}
                    // We search for tables ending with the normalized physical table name suffix.
                    String normalized = t.replace(".", "_").replaceAll("[^a-zA-Z0-9_]", "_");
                    String actualLandingTable = null;
                    String actualMvName = null;

                    // First: find existing MV for this targetTable that references this physical table suffix
                    String findMvSql = "SELECT name FROM system.tables WHERE database = '" + chDb +
                        "' AND name LIKE 'mv_" + targetTable + "_%' AND name LIKE '%_" + normalized + "'";
                    try (java.sql.ResultSet rs = stmt.executeQuery(findMvSql)) {
                        if (rs.next()) {
                            actualMvName = rs.getString("name");
                            // Extract landing table from MV name: mv_{targetTable}_{landingTable}
                            String prefix = "mv_" + targetTable + "_";
                            if (actualMvName.startsWith(prefix)) {
                                actualLandingTable = actualMvName.substring(prefix.length());
                            }
                            sendLog(emitter, "Found existing MV: `" + actualMvName + "` → landing table: `" + actualLandingTable + "`");
                        }
                    } catch (Exception e) {
                        sendLog(emitter, "WARNING: Could not query system.tables: " + e.getMessage());
                    }

                    // Fallback: search for cdc_* table ending with the normalized name
                    if (actualLandingTable == null) {
                        String findCdcSql = "SELECT name FROM system.tables WHERE database = '" + chDb +
                            "' AND name LIKE 'cdc_%' AND name LIKE '%" + normalized + "'";
                        try (java.sql.ResultSet rs2 = stmt.executeQuery(findCdcSql)) {
                            if (rs2.next()) {
                                actualLandingTable = rs2.getString("name");
                                sendLog(emitter, "Found CDC landing table via fallback search: `" + actualLandingTable + "`");
                            }
                        } catch (Exception e2) {
                            sendLog(emitter, "WARNING: Fallback CDC table search failed: " + e2.getMessage());
                        }
                    }

                    if (actualLandingTable == null) {
                        sendLog(emitter, "ERROR: Could not find CDC landing table for physical table: " + t + ". Skipping MV recreation for this table.");
                        continue;
                    }

                    // Drop the existing MV (use actual name found above)
                    String mvName = "mv_" + targetTable + "_" + actualLandingTable;
                    stmt.execute("DROP VIEW IF EXISTS `" + chDb + "`.`" + mvName + "`");
                    sendLog(emitter, "Dropped old MV `" + mvName + "`.");

                    // Extract baseName from the actual landing table name: cdc_{baseName}_{normalized_physical_table}
                    // landing = cdc_{baseName}_{normalized_schema}_{table}  →  baseName is everything after "cdc_" and before "_{normalized}"
                    String cdcPrefix = "cdc_";
                    String resolvedBaseName = targetTable; // fallback
                    if (actualLandingTable.startsWith(cdcPrefix) && actualLandingTable.endsWith("_" + normalized)) {
                        resolvedBaseName = actualLandingTable.substring(cdcPrefix.length(), actualLandingTable.length() - ("_" + normalized).length());
                        sendLog(emitter, "Resolved baseName: `" + resolvedBaseName + "`");
                    }

                    // Recreate the MV using the resolved baseName
                    String rotatedSql = rotateQuery(expandedNewQuery, t);
                    String sqlWithMeta = addMetadataColsToSelect(rotatedSql, t);
                    String rewrittenSql;
                    if (physicalTables.size() > 1) {
                        String sqlWithFilters = addPKFiltersToWhere(sqlWithMeta, physicalTables, tableToPKs);
                        rewrittenSql = rewriteQueryForClickHouse(sqlWithFilters, physicalTables, resolvedBaseName, sourceConn, chDb);
                        rewrittenSql = rewrittenSql.replaceAll("(?i)\\bLEFT\\s+JOIN\\b", "INNER JOIN");
                    } else {
                        rewrittenSql = rewriteQueryForClickHouse(sqlWithMeta, physicalTables, resolvedBaseName, sourceConn, chDb);
                    }
                    String mvDdl = "CREATE MATERIALIZED VIEW IF NOT EXISTS `" + chDb + "`.`" + mvName + "`\nTO `" + chDb + "`.`" + targetTable + "`\nAS " + rewrittenSql;
                    stmt.execute(mvDdl);
                    sendLog(emitter, "Recreated MV `" + mvName + "` successfully.");
                }
            }


            // ── 7. Backfill NEW columns from source for existing rows ────────────────
            if (!addedCols.isEmpty()) {
                sendLog(emitter, "Starting column backfill for " + addedCols.size() + " new column(s)...");

                // Build FULL SELECT: Kita harus menarik SEMUA kolom karena sifat ClickHouse ReplacingMergeTree 
                // adalah me-replace seluruh baris saat deduplikasi. Jika kolom lama tidak di-insert, akan jadi NULL/kosong.
                java.util.Set<String> compositePKs = new java.util.LinkedHashSet<>();
                for (java.util.Set<String> pks : tableToPKs.values()) compositePKs.addAll(pks);
                if (compositePKs.isEmpty() && !newCols.isEmpty()) compositePKs.add(newCols.get(0).name);

                java.util.List<String> selectCols = new java.util.ArrayList<>();
                for (ColumnInfo c : newCols) {
                    selectCols.add(c.name);
                }

                // Run the new query on source to get PK + new-col values
                String srcType = sourceConn.getType().toLowerCase();
                String limitedQuery = srcType.contains("sqlserver")
                    ? "SELECT TOP 0 * FROM (" + expandedNewQuery + ") AS tmp" // just schema check first
                    : "SELECT * FROM (" + expandedNewQuery + ") AS tmp LIMIT 0";

                // Actual data fetch: source returns full rows, we pick what we need
                sendLog(emitter, "Fetching data from source for backfill (this may take a while for large tables)...");
                int batchSize = 500;
                int totalRows = 0;

                try (java.sql.Connection srcConn = sourceDs.getConnection()) {
                    srcConn.setAutoCommit(false); // Essential for streaming large results in PG/JDBC
                    try (java.sql.Statement srcStmt = srcConn.createStatement()) {
                        if (srcType.contains("mysql")) {
                            srcStmt.setFetchSize(Integer.MIN_VALUE);
                        } else {
                            srcStmt.setFetchSize(500);
                        }
                        try (java.sql.ResultSet rs = srcStmt.executeQuery(expandedNewQuery)) {

                            java.sql.ResultSetMetaData rsMeta = rs.getMetaData();

                            // Find column indices (case-insensitive mapping)
                            java.util.Map<String, Integer> colIndex = new java.util.LinkedHashMap<>();
                            for (int i = 1; i <= rsMeta.getColumnCount(); i++) {
                                colIndex.put(rsMeta.getColumnLabel(i).toLowerCase(), i);
                            }

                            // Check all requested cols exist
                            for (String col : selectCols) {
                                if (!colIndex.containsKey(col.toLowerCase())) {
                                    sendLog(emitter, "WARNING: Column `" + col + "` not found in source result set. Skipping backfill for this column.");
                                    addedCols.remove(col);
                                }
                            }

                            // Build ClickHouse batch update using ALTER TABLE UPDATE
                            java.util.List<java.util.Map<String, Object>> batch = new java.util.ArrayList<>();
                            while (rs.next()) {
                                java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
                                for (String col : selectCols) {
                                    Integer idx = colIndex.get(col.toLowerCase());
                                    if (idx != null) row.put(col, rs.getObject(idx));
                                }
                                batch.add(row);
                                totalRows++;

                                if (batch.size() >= batchSize) {
                                    flushBackfillBatch(targetDs, chDb, targetTable, batch, compositePKs, selectCols, emitter);
                                    batch.clear();
                                    sendLog(emitter, "Backfilled " + totalRows + " rows so far...");
                                }
                            }
                            if (!batch.isEmpty()) {
                                flushBackfillBatch(targetDs, chDb, targetTable, batch, compositePKs, selectCols, emitter);
                            }
                        }
                    }
                }
                sendLog(emitter, "Backfill complete. " + totalRows + " rows processed.");
            }

            // ── 8. Update stored query ───────────────────────────────────────────────
            pipelineMetadataRepository.updateQuery(deployId, newQuery);
            sendLog(emitter, "✅ Query updated and saved. Schema evolution complete!");
            emitter.complete();

        } catch (Exception e) {
            logger.error("Failed to update pipeline query " + deployId, e);
            try { sendLog(emitter, "ERROR: " + e.getMessage()); emitter.complete(); } catch (Exception ignored) {}
            throw e;
        }
    }

    private java.util.List<ColumnInfo> getQueryColumns(String query, DataSource sourceDs, ConnectionDetails sourceConn) throws Exception {
        String srcType = sourceConn.getType().toLowerCase();
        String dryRun = srcType.contains("sqlserver")
            ? "SELECT TOP 0 * FROM (" + query + ") AS tmp"
            : "SELECT * FROM (" + query + ") AS tmp LIMIT 0";
        java.util.List<ColumnInfo> cols = new java.util.ArrayList<>();
        try (java.sql.Connection conn = sourceDs.getConnection();
             java.sql.PreparedStatement ps = conn.prepareStatement(dryRun);
             java.sql.ResultSet rs = ps.executeQuery()) {
            java.sql.ResultSetMetaData meta = rs.getMetaData();
            for (int i = 1; i <= meta.getColumnCount(); i++) {
                ColumnInfo c = new ColumnInfo();
                c.name = meta.getColumnLabel(i);
                c.clickhouseType = mapJdbcTypeToClickHouse(meta.getColumnType(i), meta.getPrecision(i), meta.getScale(i), meta.getColumnTypeName(i));
                cols.add(c);
            }
        }
        return cols;
    }

    private void flushBackfillBatch(DataSource targetDs, String chDb, String targetTable,
            java.util.List<java.util.Map<String, Object>> batch,
            java.util.Set<String> pkCols, java.util.List<String> addedCols,
            SseEmitter emitter) throws Exception {

        if (batch.isEmpty() || addedCols.isEmpty()) return;

        // We insert full rows for the new columns using an INSERT that triggers ReplacingMergeTree dedup
        // Build INSERT with only PK + new cols (others get their stored values via FINAL)
        StringBuilder sb = new StringBuilder();
        sb.append("INSERT INTO `").append(chDb).append("`.`").append(targetTable).append("` (");

        java.util.List<String> insertCols = new java.util.ArrayList<>(pkCols);
        for (String c : addedCols) { if (!insertCols.contains(c)) insertCols.add(c); }
        // Add version and is_deleted so RMT dedup works
        insertCols.add("version");
        insertCols.add("is_deleted");

        sb.append(insertCols.stream().map(c -> "`" + c + "`").collect(java.util.stream.Collectors.joining(", ")));
        sb.append(") VALUES ");

        java.util.List<String> rowValues = new java.util.ArrayList<>();
        for (java.util.Map<String, Object> row : batch) {
            StringBuilder rv = new StringBuilder("(");
            for (int i = 0; i < insertCols.size(); i++) {
                if (i > 0) rv.append(", ");
                String col = insertCols.get(i);
                if ("version".equals(col)) {
                    rv.append(System.currentTimeMillis());
                } else if ("is_deleted".equals(col)) {
                    rv.append("0");
                } else {
                    Object val = row.get(col);
                    if (val == null) {
                        rv.append("NULL");
                    } else if (val instanceof Boolean) {
                        rv.append(((Boolean) val) ? "1" : "0");
                    } else if (val instanceof Number) {
                        rv.append(val);
                    } else {
                        rv.append("'").append(val.toString().replace("'", "\\'")).append("'");
                    }
                }
            }
            rv.append(")");
            rowValues.add(rv.toString());
        }
        sb.append(String.join(", ", rowValues));

        try (java.sql.Connection conn = targetDs.getConnection();
             java.sql.Statement stmt = conn.createStatement()) {
            stmt.execute(sb.toString());
        }
    }

    public String getOriginalQuery(String deployId) {
        return pipelineMetadataRepository.getOriginalQuery(deployId);
    }


    public void renamePipeline(String deployId, String newName) {
        try {
            String[] connectors = restTemplate.getForObject(DEBEZIUM_URL, String[].class);
            String sinkConnector = null;
            String oldTargetTable = null;
            if (connectors != null) {
                for (String c : connectors) {
                    if (c.matches("sink-.*-" + deployId)) {
                        sinkConnector = c;
                        String[] parts = c.split("-");
                        StringBuilder tb = new StringBuilder();
                        for(int i=2; i<parts.length-1; i++) {
                            if(i>2) tb.append("-");
                            tb.append(parts[i]);
                        }
                        oldTargetTable = tb.toString();
                        break;
                    }
                }
            }
            if (sinkConnector == null || oldTargetTable == null) throw new RuntimeException("Pipeline not found");
            
            java.util.Map<String, Object> config = getConnectorConfig(sinkConnector);
            String host = (String) config.get("hostname");
            String port = (String) config.get("port");
            String db = (String) config.get("database");
            String user = (String) config.get("username");
            String pass = (String) config.get("password");
            if (db == null) db = "default";
            
            ConnectionDetails chDetails = new ConnectionDetails();
            chDetails.setType("clickhouse");
            chDetails.setHost(host);
            chDetails.setPort(Integer.parseInt(port));
            chDetails.setDatabase(db);
            chDetails.setUsername(user);
            chDetails.setPassword(pass);
            
            javax.sql.DataSource ds = connectionManagerService.getDataSource(chDetails);
            try (java.sql.Connection conn = ds.getConnection();
                 java.sql.Statement stmt = conn.createStatement()) {
                 
                 stmt.execute("RENAME TABLE `" + db + "`.`" + oldTargetTable + "` TO `" + db + "`.`" + newName + "`");
                 
                 stmt.execute("DROP VIEW IF EXISTS `" + db + "`.`v_" + oldTargetTable + "`");
                 stmt.execute("CREATE OR REPLACE VIEW `" + db + "`.`v_" + newName + "` AS SELECT * FROM `" + db + "`.`" + newName + "` FINAL WHERE is_deleted = 0");
                 
                 java.util.List<String> mvs = new java.util.ArrayList<>();
                 try (java.sql.ResultSet rs = stmt.executeQuery("SELECT name, create_table_query FROM system.tables WHERE database = '" + db + "' AND name LIKE 'mv_" + oldTargetTable + "_%'")) {
                     while (rs.next()) {
                         mvs.add(rs.getString("name") + "|||" + rs.getString("create_table_query"));
                     }
                 }
                 
                 for (String mvData : mvs) {
                     String[] parts = mvData.split("\\|\\|\\|");
                     String oldMvName = parts[0];
                     String createSql = parts[1];
                     
                     stmt.execute("DROP VIEW IF EXISTS `" + db + "`.`" + oldMvName + "`");
                     
                     String newMvName = oldMvName.replaceFirst("mv_" + oldTargetTable + "_", "mv_" + newName + "_");
                     String newCreateSql = createSql
                            .replaceAll("(?i)CREATE MATERIALIZED VIEW (`?)" + java.util.regex.Pattern.quote(db) + "(`?)\\.(`?)" + java.util.regex.Pattern.quote(oldMvName) + "(`?)", "CREATE MATERIALIZED VIEW $1" + db + "$2.$3" + newMvName + "$4")
                            .replaceAll("(?i)TO (`?)" + java.util.regex.Pattern.quote(db) + "(`?)\\.(`?)" + java.util.regex.Pattern.quote(oldTargetTable) + "(`?)", "TO $1" + db + "$2.$3" + newName + "$4");
                            
                     stmt.execute(newCreateSql);
                 }
            }
            
            deleteConnector(sinkConnector);
            String newConnectorName = "sink-clickhouse-" + newName + "-" + deployId;
            java.util.Map<String, Object> newPayload = new java.util.HashMap<>();
            newPayload.put("name", newConnectorName);
            
            // Remove 'name' from config if it exists, as Kafka Connect does not expect it in the config block for creation
            config.remove("name");
            newPayload.put("config", config);
            
            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
            org.springframework.http.HttpEntity<java.util.Map<String, Object>> entity = new org.springframework.http.HttpEntity<>(newPayload, headers);
            restTemplate.postForEntity(DEBEZIUM_URL, entity, String.class);
            
            pipelineMetadataRepository.updateTargetTable(deployId, newName);
            
        } catch (Exception e) {
            logger.error("Failed to rename pipeline " + deployId, e);
            throw new RuntimeException("Failed to rename pipeline: " + e.getMessage());
        }
    }

    public java.util.Map<String, Object> getConnectorConfig(String connectorName) {
        String url = DEBEZIUM_URL + "/" + connectorName + "/config";
        try {
            return restTemplate.getForObject(url, java.util.Map.class);
        } catch (Exception e) {
            logger.error("Failed to get config for connector " + connectorName, e);
            throw new RuntimeException("Failed to get config: " + e.getMessage());
        }
    }

    public void updateConnectorConfig(String connectorName, java.util.Map<String, Object> config) {
        String url = DEBEZIUM_URL + "/" + connectorName + "/config";
        try {
            restTemplate.put(url, config);
        } catch (Exception e) {
            logger.error("Failed to update config for connector " + connectorName, e);
            throw new RuntimeException("Failed to update config: " + e.getMessage());
        }
    }

    public java.util.List<java.util.Map<String, Object>> peekTopicData(String connectorName) {
        try {
            java.util.Map<String, Object> config = getConnectorConfig(connectorName);
            String topicName = null;
            if (config.containsKey("topics")) {
                topicName = ((String) config.get("topics")).split(",")[0];
            } else if (config.containsKey("transforms.route.replacement")) {
                // Topic is routed/renamed via RegexRouter (e.g. cdc_basename_$2_$3)
                String replacement = (String) config.get("transforms.route.replacement");
                String actualPrefix = replacement.substring(0, replacement.indexOf("$2"));
                Properties props = new Properties();
                props.put("bootstrap.servers", "kafka:9092");
                try (AdminClient admin = AdminClient.create(props)) {
                    java.util.Set<String> allTopics = admin.listTopics().names().get();
                    for (String t : allTopics) {
                        if (t.startsWith(actualPrefix)) {
                            topicName = t;
                            break;
                        }
                    }
                }
            } else if (config.containsKey("topic.prefix")) {
                // Try to find the topic via admin client (default Debezium naming)
                String prefix = (String) config.get("topic.prefix");
                Properties props = new Properties();
                props.put("bootstrap.servers", "kafka:9092");
                try (AdminClient admin = AdminClient.create(props)) {
                    java.util.Set<String> allTopics = admin.listTopics().names().get();
                    for (String t : allTopics) {
                        if (t.startsWith(prefix + ".")) {
                            topicName = t;
                            break;
                        }
                    }
                }
            } else if (config.containsKey("topics.regex")) {
                String regex = (String) config.get("topics.regex");
                Pattern p = Pattern.compile(regex);
                Properties props = new Properties();
                props.put("bootstrap.servers", "kafka:9092");
                try (AdminClient admin = AdminClient.create(props)) {
                    java.util.Set<String> allTopics = admin.listTopics().names().get();
                    for (String t : allTopics) {
                        if (p.matcher(t).matches()) {
                            topicName = t;
                            break;
                        }
                    }
                }
            }

            if (topicName == null) {
                return Collections.singletonList(java.util.Map.of("error", "Could not determine topic name for this connector. Ensure the connector is running and topics are created."));
            }

            Properties props = new Properties();
            props.put("bootstrap.servers", "kafka:9092");
            props.put("group.id", "peek-consumer-" + System.currentTimeMillis());
            props.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
            props.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
            props.put("auto.offset.reset", "earliest");
            props.put("max.poll.records", "10");

            List<java.util.Map<String, Object>> messages = new ArrayList<>();
            try (org.apache.kafka.clients.consumer.KafkaConsumer<String, String> consumer = new org.apache.kafka.clients.consumer.KafkaConsumer<>(props)) {
                
                // Get partitions and seek to end minus 10
                java.util.List<org.apache.kafka.common.PartitionInfo> partitionInfos = consumer.partitionsFor(topicName);
                if (partitionInfos != null) {
                    java.util.List<TopicPartition> partitions = new ArrayList<>();
                    for (org.apache.kafka.common.PartitionInfo pi : partitionInfos) {
                        partitions.add(new TopicPartition(pi.topic(), pi.partition()));
                    }
                    consumer.assign(partitions);
                    consumer.seekToEnd(partitions);
                    for (TopicPartition tp : partitions) {
                        long pos = consumer.position(tp);
                        if (pos > 10) {
                            consumer.seek(tp, pos - 10);
                        } else {
                            consumer.seek(tp, 0);
                        }
                    }
                    
                    org.apache.kafka.clients.consumer.ConsumerRecords<String, String> records = consumer.poll(java.time.Duration.ofMillis(2000));
                    for (org.apache.kafka.clients.consumer.ConsumerRecord<String, String> record : records) {
                        java.util.Map<String, Object> msg = new HashMap<>();
                        msg.put("partition", record.partition());
                        msg.put("offset", record.offset());
                        msg.put("key", record.key());
                        msg.put("value", record.value());
                        msg.put("timestamp", record.timestamp());
                        messages.add(msg);
                    }
                }
            }
            if (messages.isEmpty()) {
                messages.add(java.util.Map.of("error", "No messages found in topic: " + topicName));
            }
            return messages;
        } catch (Exception e) {
            logger.error("Failed to peek topic data for connector " + connectorName, e);
            return Collections.singletonList(java.util.Map.of("error", "Error peeking topic: " + e.getMessage()));
        }
    }

    public java.util.Map<String, Object> getSnapshotProgress(String deployId) {
        java.util.Map<String, Object> result = new java.util.HashMap<>();
        try {
            String[] connectors = restTemplate.getForObject(DEBEZIUM_URL, String[].class);
            String actualSource = null;
            String actualSink = null;
            if (connectors != null) {
                for (String c : connectors) {
                    if (c.matches("source-.*-" + deployId)) actualSource = c;
                    if (c.matches("sink-.*-" + deployId)) actualSink = c;
                }
            }
            
            if (actualSource == null || actualSink == null) {
                return java.util.Map.of("error", "Connectors not found for deployId: " + deployId);
            }
            
            java.util.Map<String, Object> sourceConfig = getConnectorConfig(actualSource);
            String plugin = (String) sourceConfig.get("connector.class");
            String host = (String) sourceConfig.get("database.hostname");
            String port = String.valueOf(sourceConfig.get("database.port"));
            String db = (String) sourceConfig.get("database.dbname");
            String user = (String) sourceConfig.get("database.user");
            String pass = (String) sourceConfig.get("database.password");
            if (pass == null) pass = "";
            String tableInclude = (String) sourceConfig.get("table.include.list");
            if (tableInclude == null) tableInclude = "";
            String tableName = tableInclude.contains(".") ? tableInclude.split("\\.")[1] : tableInclude;
            
            String sourceUrl = "";
            String sourceQuery = "";
            if (plugin != null && plugin.contains("postgres")) {
                sourceUrl = "jdbc:postgresql://" + host + ":" + port + "/" + db;
                sourceQuery = "SELECT reltuples::bigint FROM pg_class WHERE relname = '" + tableName + "'";
            } else if (plugin != null && plugin.contains("mysql")) {
                sourceUrl = "jdbc:mysql://" + host + ":" + port + "/" + db;
                sourceQuery = "SELECT table_rows FROM information_schema.tables WHERE table_name = '" + tableName + "'";
            } else if (plugin != null && plugin.contains("sqlserver")) {
                sourceUrl = "jdbc:sqlserver://" + host + ":" + port + ";databaseName=" + db + ";encrypt=false;";
                sourceQuery = "SELECT sum(row_count) FROM sys.dm_db_partition_stats WHERE object_id=OBJECT_ID('" + tableName + "')";
            }
            
            long sourceCount = -1;
            if (!sourceUrl.isEmpty()) {
                try (java.sql.Connection conn = java.sql.DriverManager.getConnection(sourceUrl, user, pass);
                     java.sql.Statement stmt = conn.createStatement();
                     java.sql.ResultSet rs = stmt.executeQuery(sourceQuery)) {
                    if (rs.next()) {
                        sourceCount = rs.getLong(1);
                    }
                } catch(Exception ex) {
                    logger.warn("Could not get source count: " + ex.getMessage());
                }
            }
            
            String[] parts = actualSink.split("-");
            StringBuilder targetTableBuilder = new StringBuilder();
            for(int i = 2; i < parts.length - 1; i++) {
                if(i > 2) targetTableBuilder.append("-");
                targetTableBuilder.append(parts[i]);
            }
            String targetTable = targetTableBuilder.toString();
            
            long targetCount = -1;
            try {
                java.util.Map<String, Object> sinkConfig = getConnectorConfig(actualSink);
                String chUser = sinkConfig != null ? (String) sinkConfig.get("username") : null;
                String chPass = sinkConfig != null ? (String) sinkConfig.get("password") : null;
                
                String chQuery = "SELECT sum(rows) FROM system.parts WHERE table = '" + targetTable + "' AND active = 1";
                String chUrl = "http://clickhouse:8123/?query=" + java.net.URLEncoder.encode(chQuery, "UTF-8");
                
                org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
                if (chUser != null && !chUser.isEmpty()) {
                    headers.set("X-ClickHouse-User", chUser);
                }
                if (chPass != null && !chPass.isEmpty()) {
                    headers.set("X-ClickHouse-Key", chPass);
                }
                org.springframework.http.HttpEntity<String> entity = new org.springframework.http.HttpEntity<>(headers);
                org.springframework.http.ResponseEntity<String> response = restTemplate.exchange(chUrl, org.springframework.http.HttpMethod.GET, entity, String.class);
                String chResponse = response.getBody();
                
                if (chResponse != null && !chResponse.trim().isEmpty()) {
                    targetCount = Long.parseLong(chResponse.trim());
                } else {
                    targetCount = 0;
                }
            } catch (Exception ex) {
                logger.warn("Could not get target count: " + ex.getMessage());
            }
            
            result.put("sourceCount", sourceCount);
            result.put("targetCount", targetCount);
            if (sourceCount > 0 && targetCount >= 0) {
                double pct = ((double) targetCount / sourceCount) * 100.0;
                if (pct > 100.0) pct = 100.0;
                result.put("percentage", Math.round(pct * 100.0) / 100.0);
            } else {
                result.put("percentage", 0.0);
            }
            result.put("snapshotCompleted", (targetCount >= sourceCount && sourceCount != -1) || targetCount > 0 && sourceCount == -1);
            
            return result;
        } catch (Exception e) {
            logger.error("Failed to get snapshot progress", e);
            return java.util.Map.of("error", e.getMessage());
        }
    }
}
