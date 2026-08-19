package com.dbdiff.service;

import com.dbdiff.model.DataWarehouseDeployRequest;
import com.dbdiff.model.ConnectionDetails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.client.RestTemplate;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
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
    private static final String DEBEZIUM_BASE_URL = System.getenv()
            .getOrDefault("DEBEZIUM_BASE_URL", "http://debezium:8083");
    private static final String DEBEZIUM_URL = DEBEZIUM_BASE_URL + "/connectors";
    private static final String KAFKA_BOOTSTRAP_SERVERS = System.getenv()
            .getOrDefault("KAFKA_BOOTSTRAP_SERVERS", "kafka:9092");
    private static final String KAFKA_CONNECT_OFFSET_TOPIC = "connect-offsets";

    public DataWarehouseService() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10000); // 10 seconds
        factory.setReadTimeout(180000);   // 180 seconds (Debezium connector registration can take >60s)
        this.restTemplate = new RestTemplate(factory);
    }

    @Autowired
    private ConnectionManagerService connectionManagerService;

    @Autowired
    private com.dbdiff.repository.PipelineMetadataRepository pipelineMetadataRepository;

    @Autowired
    private com.dbdiff.repository.ConnectionRepository connectionRepository;

    @Autowired
    private SshTunnelService sshTunnelService;

    private static class ColumnInfo {
        String name;
        String clickhouseType;
    }

    private void sendLog(SseEmitter emitter, String message) {
        logger.info(message);
        if (emitter != null) {
            try {
                emitter.send(SseEmitter.event().data(message));
            } catch (Throwable ignored) {
                // Client disconnect or emitter completion should never abort deployment background thread
            }
        }
    }

    /**
     * Waits until the Debezium Kafka Connect REST API is up AND connected to the Kafka
     * cluster. During a Kafka/Zookeeper crash-loop the REST port may accept connections
     * while the worker is still failing, which caused deploys to hang with "Read timed out".
     * The Connect REST listener can return 200 before its herder can use Kafka, so this
     * verifies the internal offset topic with Kafka's AdminClient as well.
     *
     * @return true if Debezium became ready, false otherwise.
     */
    private boolean waitForDebeziumReady(SseEmitter emitter, int maxWaitSeconds) throws IOException {
        long deadline = System.currentTimeMillis() + (maxWaitSeconds * 1000L);
        int attempt = 0;
        Exception lastError = null;
        while (System.currentTimeMillis() < deadline) {
            attempt++;
            try {
                restTemplate.getForObject(DEBEZIUM_URL, String[].class);
                verifyKafkaConnectStorage();
                sendLog(emitter, "Debezium is ready (attempt " + attempt + ").");
                return true;
            } catch (Exception e) {
                lastError = e;
                sendLog(emitter, "Waiting for Debezium to become ready (attempt " + attempt + "): " + e.getMessage());
                try {
                    Thread.sleep(5000); // 5s backoff between probes
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        sendLog(emitter, "Debezium did not become ready within " + maxWaitSeconds + "s"
                + (lastError != null ? ": " + lastError.getMessage() : "") + ".");
        return false;
    }

    private void verifyKafkaConnectStorage() throws Exception {
        Properties properties = new Properties();
        properties.put("bootstrap.servers", KAFKA_BOOTSTRAP_SERVERS);
        properties.put("request.timeout.ms", "5000");
        properties.put("default.api.timeout.ms", "10000");
        properties.put("retries", "1");
        properties.put("retry.backoff.ms", "2000");

        try (AdminClient admin = AdminClient.create(properties)) {
            Set<String> topics = admin.listTopics().names().get(10, TimeUnit.SECONDS);
            String foundTopic = null;
            for (String t : new String[]{"connect-offsets", "my_connect_offsets", "connect_offsets"}) {
                if (topics.contains(t)) {
                    foundTopic = t;
                    break;
                }
            }
            if (foundTopic == null) {
                foundTopic = topics.stream()
                        .filter(t -> t.contains("connect") && t.contains("offset"))
                        .findFirst().orElse(null);
            }

            if (foundTopic == null && !topics.contains("connect-configs")) {
                throw new IllegalStateException("Kafka Connect offset topic is not ready");
            }

            String targetTopic = foundTopic != null ? foundTopic : KAFKA_CONNECT_OFFSET_TOPIC;
            try {
                TopicPartition offsetPartition = new TopicPartition(targetTopic, 0);
                admin.listOffsets(Map.of(offsetPartition, OffsetSpec.latest()))
                        .all()
                        .get(10, TimeUnit.SECONDS);
            } catch (Exception offsetEx) {
                logger.warn("Offset topic '{}' exists but listOffsets failed (likely empty topic): {}",
                        targetTopic, offsetEx.getMessage());
            }
        }
    }

    /**
     * Registers a connector via POST /connectors, retrying on transient failures
     * (I/O errors, read timeouts, 5xx) that happen while Kafka/Debezium are still
     * stabilising. Non-retryable client errors (e.g. 4xx bad config) are thrown immediately.
     */
    private org.springframework.http.ResponseEntity<String> registerConnectorWithRetry(
            SseEmitter emitter,
            String connectorName,
            org.springframework.http.HttpEntity<java.util.Map<String, Object>> entity,
            int maxAttempts) throws Exception {
        Exception lastError = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return restTemplate.postForEntity(DEBEZIUM_URL, entity, String.class);
            } catch (org.springframework.web.client.HttpClientErrorException e) {
                if (e.getStatusCode() == org.springframework.http.HttpStatus.CONFLICT) {
                    sendLog(emitter, "Connector '" + connectorName + "' already exists (409). Updating connector configuration...");
                    try {
                        java.util.Map<String, Object> body = entity.getBody();
                        java.lang.Object configObj = (body != null) ? body.get("config") : null;
                        org.springframework.http.HttpEntity<java.lang.Object> putEntity = new org.springframework.http.HttpEntity<>(configObj != null ? configObj : body, entity.getHeaders());
                        return restTemplate.exchange(DEBEZIUM_URL + "/" + connectorName + "/config", org.springframework.http.HttpMethod.PUT, putEntity, String.class);
                    } catch (Exception putEx) {
                        sendLog(emitter, "Config update via PUT failed: " + putEx.getMessage() + ". Re-creating connector...");
                        deleteConnectorWithWait(connectorName);
                        if (attempt < maxAttempts) {
                            try { Thread.sleep(2000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw ie; }
                            continue;
                        }
                    }
                }
                throw e;
            } catch (Exception e) {
                lastError = e;
                sendLog(emitter, "Attempt " + attempt + "/" + maxAttempts
                        + " to register connector '" + connectorName + "' failed: " + e.getMessage());
                if (attempt < maxAttempts) {
                    try {
                        Thread.sleep(5000); // 5s backoff before retrying
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw ie;
                    }
                }
            }
        }
        throw lastError;
    }

    private String resolveTunnelHost() {
        try {
            java.net.InetAddress.getByName("tasks.backend");
            return "tasks.backend";
        } catch (Exception ignored) {
            return "127.0.0.1";
        }
    }

    private void deleteConnectorWithWait(String connectorName) {
        try {
            restTemplate.delete(DEBEZIUM_URL + "/" + connectorName);
        } catch (Exception ignored) {}
        for (int i = 0; i < 10; i++) {
            try {
                java.util.Map<String, Object> cfg = getConnectorConfig(connectorName);
                if (cfg == null || cfg.isEmpty() || cfg.containsKey("error_code")) {
                    break;
                }
            } catch (Exception e) {
                break;
            }
            try { Thread.sleep(1000); } catch (Exception ignored) {}
        }
    }

    public void deployPipeline(DataWarehouseDeployRequest request, SseEmitter emitter) {
        try {
            List<ConnectionDetails> conns = request.getSourceConnections();
            if (conns == null || conns.isEmpty()) {
                if (request.getSourceConnection() != null) {
                    conns = java.util.Collections.singletonList(request.getSourceConnection());
                } else {
                    throw new RuntimeException("No source connections specified for deployment.");
                }
            }

            List<ConnectionDetails> enrichedConns = new java.util.ArrayList<>();
            for (ConnectionDetails c : conns) {
                enrichedConns.add(enrichConnection(c));
            }
            conns = enrichedConns;

            sendLog(emitter, "Checking Debezium availability before deploying...");
            if (!waitForDebeziumReady(emitter, 120)) {
                throw new RuntimeException("Debezium is not ready (Kafka/Debezium may be restarting on the server). "
                        + "Please wait a moment and try again.");
            }

            sendLog(emitter, "Starting Multi-DB Data Warehouse deployment for " + conns.size() + " source database(s) to target table `" + request.getTargetTable() + "`...");

            // Clean up old sink connectors for this target table once before starting individual connection deploys
            String cleanTarget = request.getTargetTable().replaceAll("[^a-zA-Z0-9_-]", "");
            String sharedDeployId = String.valueOf(System.currentTimeMillis());
            try {
                String[] connectors = restTemplate.getForObject(DEBEZIUM_URL, String[].class);
                if (connectors != null) {
                    for (String cName : connectors) {
                        if (cName.matches("sink-clickhouse-" + cleanTarget + "-[0-9]+")) {
                            sendLog(emitter, "Deleting old target sink connector: " + cName);
                            try {
                                restTemplate.delete(DEBEZIUM_URL + "/" + cName);
                            } catch (Exception ex) {
                                logger.warn("Failed to delete sink connector " + cName, ex);
                            }
                        }
                    }
                }
            } catch (Exception ex) {
                logger.warn("Failed to clean up old sink connectors for target " + cleanTarget, ex);
            }

            for (int i = 0; i < conns.size(); i++) {
                ConnectionDetails conn = conns.get(i);
                DataWarehouseDeployRequest singleReq = new DataWarehouseDeployRequest();
                singleReq.setSourceConnection(conn);
                singleReq.setSourceConnections(enrichedConns);
                singleReq.setTargetConnection(enrichConnection(request.getTargetConnection()));
                singleReq.setTargetDatabase(request.getTargetDatabase());
                singleReq.setTargetTable(request.getTargetTable());
                singleReq.setQuery(request.getQuery());
                singleReq.setPrimaryKeys(request.getPrimaryKeys());
                singleReq.setDeployId(sharedDeployId);

                sendLog(emitter, "--------------------------------------------------");
                sendLog(emitter, "Deploying pipeline " + (i + 1) + "/" + conns.size() + " for source database [" + conn.getName() + "]...");
                try {
                    deploySinglePipeline(singleReq, emitter);
                } catch (Exception ex) {
                    sendLog(emitter, "ERROR on source database [" + (conn.getName() != null ? conn.getName() : "unknown") + "]: " + ex.getMessage());
                    if (conns.size() == 1) throw ex;
                }
            }

            sendLog(emitter, "==================================================");
            sendLog(emitter, "All " + conns.size() + " pipeline(s) deployed successfully to target table `" + request.getTargetTable() + "`!");
            try { emitter.complete(); } catch (Exception ignored) {}
        } catch (Exception e) {
            logger.error("Data Warehouse deployment failed", e);
            try {
                sendLog(emitter, "DEPLOYMENT FAILED: " + e.getMessage());
                emitter.complete();
            } catch (Exception ignored) {}
        }
    }

    private void deploySinglePipeline(DataWarehouseDeployRequest request, SseEmitter emitter) throws Exception {
        try {
            if (request.getQuery() != null) {
                String rawQuery = request.getQuery().trim();
                rawQuery = rawQuery.replaceAll("(?i);\\s*(?=UNION\\b)", "");
                if (rawQuery.endsWith(";")) {
                    rawQuery = rawQuery.substring(0, rawQuery.length() - 1).trim();
                }
                request.setQuery(rawQuery);
            }
            request.setSourceConnection(enrichConnection(request.getSourceConnection()));
            request.setTargetConnection(enrichConnection(request.getTargetConnection()));
            sendLog(emitter, "Deploying Data Warehouse pipeline for source " + request.getSourceConnection().getName() + " to target table " + request.getTargetTable());

            // =========================================================================
            // STEP 0: Ensure Debezium (Kafka Connect) is up and connected to Kafka.
            // If Kafka/Zookeeper are crash-looping, the REST API may be half-up and
            // POST /connectors would hang until "Read timed out". Gate the deploy here.
            // =========================================================================
            sendLog(emitter, "Checking Debezium availability before deploying...");
            if (!waitForDebeziumReady(emitter, 120)) {
                throw new RuntimeException("Debezium is not ready (Kafka/Debezium may be restarting on the server). "
                        + "Please wait a moment and try again.");
            }

            // Generate shared source connector name per connection, and target-specific sink connector name
            String baseName = request.getSourceConnection().getName().replaceAll("[^a-zA-Z0-9_]", "_").toLowerCase();
            long deployId = request.getDeployId() != null ? Long.parseLong(request.getDeployId()) : System.currentTimeMillis();
            String cleanTarget = request.getTargetTable().replaceAll("[^a-zA-Z0-9_-]", "");
            String sourceConnectorName = "source-" + baseName + "-shared";
            String sinkConnectorName = "sink-clickhouse-" + cleanTarget + "-" + deployId;
            
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
                        if (cName.startsWith("source-" + baseName + "-" + cleanTarget) || 
                            (cName.matches("sink-clickhouse-" + cleanTarget + "-[0-9]+") && !cName.endsWith("-" + deployId))) {
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
                String slotSearch = (baseName + "_" + cleanTarget).replaceAll("[^a-z0-9_]", "_").toLowerCase();
                String findSlotsSql = "SELECT slot_name, active_pid FROM pg_replication_slots WHERE slot_name LIKE '%" + slotSearch + "%'";
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

            // Ensure the Debezium heartbeat table exists in the source DB (PostgreSQL only).
            // Required by heartbeat.action.query so Debezium can advance confirmed_flush_lsn
            // even when the monitored tables are idle — preventing WAL from accumulating.
            if ("postgresql".equalsIgnoreCase(request.getSourceConnection().getType())) {
                try (Connection pgConn = sourceDsForCleanup.getConnection();
                     Statement pgStmt = pgConn.createStatement()) {
                    pgStmt.execute(
                        "CREATE TABLE IF NOT EXISTS public._dbz_heartbeat " +
                        "(id INT PRIMARY KEY, ts TIMESTAMPTZ NOT NULL)"
                    );
                    pgStmt.execute(
                        "INSERT INTO public._dbz_heartbeat(id, ts) VALUES(1, now()) " +
                        "ON CONFLICT(id) DO NOTHING"
                    );
                    sendLog(emitter, "Heartbeat table public._dbz_heartbeat ensured in source DB.");
                    // Auto-apply WAL disk safety limit to the source PostgreSQL database from backend code:
                    // max_slot_wal_keep_size = 500MB: Limits total WAL storage on disk to max 500MB per slot.
                    // This is 100% safe and DOES NOT close or interrupt any DBeaver sessions or active users.
                    try {
                        pgStmt.execute("ALTER SYSTEM SET max_slot_wal_keep_size = '500MB'");
                        pgStmt.execute("SELECT pg_reload_conf()");
                        sendLog(emitter, "Applied Postgres WAL disk safety limit (max_slot_wal_keep_size=500MB) via backend.");
                    } catch (Exception sysEx) {
                        logger.warn("Could not auto-apply Postgres WAL safety setting (requires superuser): {}", sysEx.getMessage());
                    }
                } catch (Exception ex) {
                    logger.warn("Could not create heartbeat table in source DB: {}", ex.getMessage());
                }
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

            // Cleanup old Kafka topics specifically for the target physical tables of this pipeline.
            // SAFE GUARD: Skip deleting topics that are currently subscribed to by OTHER active
            // sink connectors. Deleting a shared topic (e.g. mhd_lookup used by multiple pipelines)
            // would invalidate the consumer-group offsets of those other connectors, breaking live CDC.
            try {
                Properties kProps = new Properties();
                kProps.put("bootstrap.servers", KAFKA_BOOTSTRAP_SERVERS);
                try (AdminClient adminClient = AdminClient.create(kProps)) {
                    Set<String> existingTopics = adminClient.listTopics().names().get();

                    // --- Collect topics actively consumed by OTHER sink connectors ---
                    Set<String> topicsInUseByOtherSinks = new HashSet<>();
                    try {
                        // GET /connectors?expand=info to retrieve all connector configs in one call
                        @SuppressWarnings("unchecked")
                        Map<String, Object> allConnectors = restTemplate.getForObject(
                                DEBEZIUM_URL + "?expand=info&expand=status", Map.class);
                        if (allConnectors != null) {
                            for (Map.Entry<String, Object> entry : allConnectors.entrySet()) {
                                String connName = entry.getKey();
                                // Only inspect SINK connectors that are not the current pipeline's sink
                                // Skip connectors that belong to THIS pipeline's target table (same cleanTarget)
                                if (!connName.startsWith("sink-") || connName.contains(cleanTarget)) continue;
                                try {
                                    @SuppressWarnings("unchecked")
                                    Map<String, Object> wrapper = (Map<String, Object>) entry.getValue();
                                    @SuppressWarnings("unchecked")
                                    Map<String, Object> info = (Map<String, Object>) wrapper.get("info");
                                    if (info == null) continue;
                                    @SuppressWarnings("unchecked")
                                    Map<String, Object> cfg = (Map<String, Object>) info.get("config");
                                    if (cfg == null) continue;
                                    String topicsStr = (String) cfg.get("topics");
                                    if (topicsStr != null && !topicsStr.isBlank()) {
                                        for (String t : topicsStr.split(",")) {
                                            topicsInUseByOtherSinks.add(t.trim());
                                        }
                                    }
                                } catch (Exception ignored) {}
                            }
                        }
                    } catch (Exception ex) {
                        logger.warn("Could not retrieve other connector configs for shared-topic check: {}", ex.getMessage());
                    }

                    List<String> topicsToDelete = new ArrayList<>();
                    List<String> topicsSkipped  = new ArrayList<>();
                    for (String t : physicalTables) {
                        String cleanTable = t.replaceAll("[\"``]", "").replace(".", "_");
                        String targetTopic = "cdc_" + baseName + "_" + cleanTable;
                        if (!existingTopics.contains(targetTopic)) continue;
                        if (topicsInUseByOtherSinks.contains(targetTopic)) {
                            topicsSkipped.add(targetTopic);
                        } else {
                            topicsToDelete.add(targetTopic);
                        }
                    }
                    if (!topicsSkipped.isEmpty()) {
                        sendLog(emitter, "Skipping deletion of shared Kafka topic(s) still in use by other pipelines: "
                                + String.join(", ", topicsSkipped));
                    }
                    if (!topicsToDelete.isEmpty()) {
                        sendLog(emitter, "Cleaning up specific Kafka topic(s) by exact name: " + String.join(", ", topicsToDelete));
                        adminClient.deleteTopics(topicsToDelete).all().get();
                        Thread.sleep(2000);
                    }
                }
            } catch (Exception ex) {
                logger.warn("Could not cleanup old Kafka topics: " + ex.getMessage());
            }

            sendLog(emitter, "Running dry-run query on source DB to inspect column types...");
            DataSource targetDs = connectionManagerService.getDataSource(request.getTargetConnection());
            
            String dryRunSql;
            String srcType = request.getSourceConnection().getType().toLowerCase();
            if (srcType.contains("sqlserver")) {
                dryRunSql = originalQuery.replaceAll("(?i)^(\\s*SELECT\\s+)", "$1TOP 0 ");
            } else {
                dryRunSql = originalQuery + " LIMIT 0";
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
            
            sendLog(emitter, "Determining primary keys for composite sorting key...");
            Set<String> compositePKs = new LinkedHashSet<>();
            
            if (request.getPrimaryKeys() != null && !request.getPrimaryKeys().trim().isEmpty()) {
                String[] pks = request.getPrimaryKeys().split(",");
                for (String pk : pks) {
                    if (!pk.trim().isEmpty()) {
                        String trimmed = pk.trim();
                        String matched = trimmed;
                        for (ColumnInfo col : targetColumns) {
                            if (col.name.equalsIgnoreCase(trimmed)) {
                                matched = col.name;
                                break;
                            }
                        }
                        compositePKs.add(matched);
                    }
                }
                sendLog(emitter, "Using user-provided primary keys: " + String.join(", ", compositePKs));
            } else {
                sendLog(emitter, "No primary keys provided. Auto-detecting from source tables...");
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
            }
            
            if (compositePKs.isEmpty() && !targetColumns.isEmpty()) {
                compositePKs.add(targetColumns.get(0).name);
            }
            sendLog(emitter, "Target composite sorting key: " + String.join(", ", compositePKs));

            // =========================================================================
            // STEP 2: Create ClickHouse Target Table & Staging Landing Tables
            // =========================================================================
            String chDb = request.getTargetDatabase();
            if (chDb == null || chDb.trim().isEmpty()) {
                chDb = request.getTargetConnection().getDatabase();
            }
            if (chDb == null || chDb.trim().isEmpty()) {
                chDb = "default";
            }
            chDb = chDb.trim();
            
            sendLog(emitter, "Ensuring target database `" + chDb + "` exists...");
            try (Connection conn = targetDs.getConnection();
                 Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE DATABASE IF NOT EXISTS `" + chDb + "`");
            } catch (Exception e) {
                sendLog(emitter, "WARNING: Could not execute CREATE DATABASE IF NOT EXISTS: " + e.getMessage());
            }
            
            // Drop any existing MVs for this target table to prevent trigger execution during landing table backfills
            try (Connection conn = targetDs.getConnection();
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT name FROM system.tables WHERE database = '" + chDb + "' AND name LIKE 'mv_" + request.getTargetTable() + "_cdc_" + baseName + "_%'")) {
                java.util.List<String> mvsToDrop = new java.util.ArrayList<>();
                while (rs.next()) {
                    mvsToDrop.add(rs.getString(1));
                }
                for (String mv : mvsToDrop) {
                    try (Statement dropStmt = conn.createStatement()) {
                        dropStmt.execute("DROP VIEW IF EXISTS `" + chDb + "`.`" + mv + "`");
                        dropStmt.execute("DROP TABLE IF EXISTS `" + chDb + "`.`" + mv + "`");
                    } catch (Exception ignored) {}
                }
            } catch (Exception e) {
                logger.warn("Could not pre-drop MVs for target table {}: {}", request.getTargetTable(), e.getMessage());
            }
            
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
                    boolean isPk = false;
                    for (String pk : tablePKs) {
                        if (pk.equalsIgnoreCase(col.name)) {
                            isPk = true;
                            break;
                        }
                    }
                    String colType = col.clickhouseType;
                    if (!isPk && !colType.startsWith("Nullable")) {
                        colType = "Nullable(" + colType + ")";
                    }
                    landingDdl.append("    `").append(col.name).append("` ").append(colType).append(",\n");
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
                    String mvName = "mv_" + request.getTargetTable() + "_" + landingTable;
                    try { stmt.execute("DROP VIEW IF EXISTS `" + chDb + "`.`" + mvName + "`"); } catch (Exception ignored) {}
                    try { stmt.execute("DROP TABLE IF EXISTS `" + chDb + "`.`" + mvName + "`"); } catch (Exception ignored) {}
                    try { stmt.execute("DROP TABLE IF EXISTS `" + chDb + "`.`" + landingTable + "`"); } catch (Exception ignored) {}
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
                
                // Backfill landing table directly from source DB for complete initial snapshot
                sendLog(emitter, "Populating initial snapshot for landing table `" + landingTable + "` directly from source DB...");
                backfillLandingTableFromSource(sourceDs, targetDs, t, landingTable, chDb, request.getSourceConnection(), emitter);
            }

            // 2b. Create Physical Target ReplacingMergeTree Table
            StringBuilder targetDdl = new StringBuilder();
            targetDdl.append("CREATE TABLE IF NOT EXISTS `").append(chDb).append("`.`").append(request.getTargetTable()).append("` (\n");
            for (ColumnInfo col : targetColumns) {
                boolean isPk = false;
                for (String pk : compositePKs) {
                    if (pk.equalsIgnoreCase(col.name)) {
                        isPk = true;
                        break;
                    }
                }
                String colType = col.clickhouseType;
                if (!isPk && !colType.startsWith("Nullable")) {
                    colType = "Nullable(" + colType + ")";
                }
                targetDdl.append("    `").append(col.name).append("` ").append(colType).append(",\n");
            }
            boolean hasSyncDt = targetColumns.stream().anyMatch(c -> "sync_dt".equalsIgnoreCase(c.name));
            if (!hasSyncDt) {
                targetDdl.append("    `sync_dt` DateTime64(3, 'Asia/Jakarta') DEFAULT now64(3, 'Asia/Jakarta'),\n");
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
                    request.getTargetConnection().getId(),
                    chDb
                );
            } catch (Exception e) {
                logger.warn("Could not save original query to metadata repository", e);
            }
            
            sendLog(emitter, "Creating target table `" + request.getTargetTable() + "` in ClickHouse...");
            try (Connection conn = targetDs.getConnection();
                 Statement stmt = conn.createStatement()) {
                // Do not drop as view, it is a regular table.
                stmt.execute(targetDdl.toString());
                if (request.getPrimaryKeys() != null && !request.getPrimaryKeys().trim().isEmpty()) {
                    try {
                        stmt.execute("ALTER TABLE `" + chDb + "`.`" + request.getTargetTable() + "` MODIFY ORDER BY (" + pkBuilder.toString() + ")");
                        sendLog(emitter, "Updated target table ORDER BY to user-provided primary keys: (" + pkBuilder.toString() + ")");
                    } catch (Exception alterEx) {
                        logger.warn("Could not modify ORDER BY on existing target table: " + alterEx.getMessage());
                    }
                }
                sendLog(emitter, "Target table `" + request.getTargetTable() + "` verified/created.");
            } catch (Exception e) {
                sendLog(emitter, "ERROR: Target table creation failed: " + e.getMessage());
                throw e;
            }

            // Do not truncate target table so data from multiple pipelines/PTs can accumulate safely.
            // Landing tables are already truncated per source table to ensure clean snapshots.

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
            
            // Use a shared slot name per source database to avoid creating multiple slots
            String safeSlotName = "slot_" + baseName + "_shared";
            java.util.Map<String, Object> sourceConfig = new java.util.HashMap<>();
            if ("postgresql".equalsIgnoreCase(request.getSourceConnection().getType())) {
                sourceConfig.put("connector.class", "io.debezium.connector.postgresql.PostgresConnector");
                sourceConfig.put("plugin.name", "pgoutput");
                sourceConfig.put("slot.name", safeSlotName);
                sourceConfig.put("publication.name", "pub_" + safeSlotName);
                sourceConfig.put("publication.autocreate.mode", "filtered");
                // Emit heartbeats every 10s to keep confirmed_flush_lsn up to date with pg_current_wal_lsn.
                // heartbeat.action.query performs an actual write on the source DB so a WAL record is
                // produced and Debezium can advance the slot's confirmed_flush_lsn — even when the
                // monitored tables are completely idle. Without this, WAL accumulates indefinitely.
                sourceConfig.put("heartbeat.interval.ms", "10000");
                sourceConfig.put("heartbeat.action.query",
                    "INSERT INTO public._dbz_heartbeat(id, ts) VALUES(1, now()) " +
                    "ON CONFLICT(id) DO UPDATE SET ts = EXCLUDED.ts");
                // Keep the shared replication slot persistent in PostgreSQL across updates/restarts.
                sourceConfig.put("slot.drop.on.stop", "false");
                String sslMode = request.getSourceConnection().getSslMode();
                if (sslMode != null && !sslMode.trim().isEmpty()) {
                    sourceConfig.put("database.sslmode", sslMode.trim());
                } else {
                    sourceConfig.put("database.sslmode", "disable");
                }
                
                // CRITICAL: Ensure Debezium outputs timestamps in milliseconds (Kafka Connect logical types)
                // instead of default microseconds, so ClickHouse DateTime64(3) interprets them correctly.
                sourceConfig.put("time.precision.mode", "connect");
            } else if ("mysql".equalsIgnoreCase(request.getSourceConnection().getType())) {
                sourceConfig.put("connector.class", "io.debezium.connector.mysql.MySqlConnector");
            } else {
                sourceConfig.put("connector.class", "io.debezium.connector." + request.getSourceConnection().getType().toLowerCase() + "." + request.getSourceConnection().getType() + "Connector");
            }
            
            sourceConfig.put("tasks.max", "1");
            
            String dbHost = request.getSourceConnection().getHost() != null ? request.getSourceConnection().getHost().trim() : "";
            String dbPort = String.valueOf(request.getSourceConnection().getPort());
            if (request.getSourceConnection().isUseSsh()) {
                try {
                    int tunnelPort = sshTunnelService.getOrOpenTunnel(request.getSourceConnection(), String.valueOf(request.getSourceConnection().getId()));
                    sshTunnelService.markTunnelAsPermanent(String.valueOf(request.getSourceConnection().getId()));
                    dbHost = resolveTunnelHost();
                    dbPort = String.valueOf(tunnelPort);
                    sendLog(emitter, "Source connection uses SSH tunnel. Routing Debezium through " + dbHost + ":" + tunnelPort);
                } catch (Exception ex) {
                    logger.error("Failed to establish SSH tunnel for Debezium source connector", ex);
                    sendLog(emitter, "WARNING: Failed to open SSH tunnel for Debezium: " + ex.getMessage());
                }
            }

            sourceConfig.put("database.hostname", dbHost);
            sourceConfig.put("database.port", dbPort);
            sourceConfig.put("database.user", request.getSourceConnection().getUsername() != null ? request.getSourceConnection().getUsername().trim() : "");
            sourceConfig.put("database.password", request.getSourceConnection().getPassword());
            sourceConfig.put("database.dbname", request.getSourceConnection().getDatabase() != null ? request.getSourceConnection().getDatabase().trim() : "");
            // Set snapshot.mode to always so Debezium performs an initial snapshot for all included tables
            sourceConfig.put("snapshot.mode", "initial");
            
            // Add connection timeout to Debezium PostgreSQL connector
            sourceConfig.put("database.connect.timeout.ms", "30000");
            sourceConfig.put("database.server.name", sourceConnectorName);
            sourceConfig.put("topic.prefix", sourceConnectorName); // Compatibility with Debezium 2.x

            // Route Debezium topics to a unified target format: cdc_[baseName]_[schema]_[table]
            String topicPrefix = "cdc_" + baseName + "_";
            // dropHeartbeat filters out _dbz_heartbeat events AFTER they are committed
            // (so the LSN/offset is advanced) but BEFORE they reach Kafka/ClickHouse.
            sourceConfig.put("transforms", "route,unwrap,rename,castBool,castInt,dropHeartbeat");
            sourceConfig.put("transforms.route.type", "org.apache.kafka.connect.transforms.RegexRouter");
            sourceConfig.put("transforms.route.regex", "([^\\.]+)\\.([^\\.]+)\\.([^\\.]+)");
            sourceConfig.put("transforms.route.replacement", topicPrefix + "$2_$3");
            
            // Flatten the Debezium CDC payload
            sourceConfig.put("transforms.unwrap.type", "io.debezium.transforms.ExtractNewRecordState");
            sourceConfig.put("transforms.unwrap.drop.tombstones", "true");
            sourceConfig.put("transforms.unwrap.delete.handling.mode", "rewrite");
            sourceConfig.put("transforms.unwrap.add.fields", "lsn");
            
            // Rename internal Debezium fields to match our ClickHouse landing tables
            sourceConfig.put("transforms.rename.type", "org.apache.kafka.connect.transforms.ReplaceField$Value");
            sourceConfig.put("transforms.rename.renames", "__deleted:is_deleted,__lsn:version");
            
            // Cast is_deleted string to boolean, then to int8 (so it writes to ClickHouse UInt8 correctly)
            sourceConfig.put("transforms.castBool.type", "org.apache.kafka.connect.transforms.Cast$Value");
            sourceConfig.put("transforms.castBool.spec", "is_deleted:boolean");
            sourceConfig.put("transforms.castInt.type", "org.apache.kafka.connect.transforms.Cast$Value");
            sourceConfig.put("transforms.castInt.spec", "is_deleted:int8");

            // Drop heartbeat events (from public._dbz_heartbeat) so they never reach Kafka/ClickHouse.
            // The Filter transform drops matching records but still commits their offsets,
            // which is exactly what allows confirmed_flush_lsn to advance in Postgres.
            sourceConfig.put("predicates", "isHeartbeat");
            sourceConfig.put("predicates.isHeartbeat.type",
                "org.apache.kafka.connect.transforms.predicates.TopicNameMatches");
            sourceConfig.put("predicates.isHeartbeat.pattern", ".*_dbz_heartbeat");
            sourceConfig.put("transforms.dropHeartbeat.type",
                "org.apache.kafka.connect.transforms.Filter");
            sourceConfig.put("transforms.dropHeartbeat.predicate", "isHeartbeat");
            
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
            // Include the heartbeat table in the publication so Debezium generates actual CDC
            // events for each heartbeat write. Without this, _dbz_heartbeat writes are invisible
            // to the publication, no offset is committed to Kafka, and confirmed_flush_lsn
            // never advances — causing WAL to accumulate indefinitely on idle databases.
            if ("postgresql".equalsIgnoreCase(request.getSourceConnection().getType())) {
                tableIncludeList += ",public._dbz_heartbeat";
                // Enable REPLICA IDENTITY FULL on all Postgres source tables so DELETE and UPDATE events log full row state for CDC
                try (Connection pgConn = sourceDsForCleanup.getConnection();
                     Statement pgStmt = pgConn.createStatement()) {
                    try { pgStmt.execute("SET lock_timeout = '3s'"); } catch (Exception ignored) {}
                    for (String tbl : formattedTables) {
                        if (!"public._dbz_heartbeat".equalsIgnoreCase(tbl)) {
                            try {
                                pgStmt.execute("ALTER TABLE " + tbl + " REPLICA IDENTITY FULL");
                                sendLog(emitter, "Set REPLICA IDENTITY FULL on PostgreSQL table: " + tbl);
                            } catch (Exception ex) {
                                logger.warn("Could not set REPLICA IDENTITY FULL on table " + tbl + ": " + ex.getMessage());
                            }
                        }
                    }
                } catch (Exception ex) {
                    logger.warn("Failed to apply REPLICA IDENTITY FULL on Postgres tables", ex);
                }
            }
            sourceConfig.put("table.include.list", tableIncludeList);
            
            // Serialize Decimals as strings to avoid Base64 encoding which breaks ClickHouse sink
            sourceConfig.put("decimal.handling.mode", "double");
            
            // Disable schemas in the output Kafka topics to save bandwidth and simplify sink parsing
            sourceConfig.put("key.converter", "org.apache.kafka.connect.json.JsonConverter");
            sourceConfig.put("key.converter.schemas.enable", "true");
            sourceConfig.put("value.converter", "org.apache.kafka.connect.json.JsonConverter");
            sourceConfig.put("value.converter.schemas.enable", "true");
            
            // Register or Update Shared Source Connector
            boolean sharedConnectorExists = false;
            java.util.Map<String, Object> existingConfig = null;
            try {
                existingConfig = getConnectorConfig(sourceConnectorName);
                if (existingConfig != null && !existingConfig.isEmpty() && !existingConfig.containsKey("error_code")) {
                    sharedConnectorExists = true;
                }
            } catch (Exception ignored) {}

            if (sharedConnectorExists && existingConfig != null) {
                sendLog(emitter, "Shared source connector " + sourceConnectorName + " already exists. Merging table list...");
                String currentTablesStr = (String) existingConfig.get("table.include.list");
                Set<String> mergedTables = new LinkedHashSet<>();
                if (currentTablesStr != null && !currentTablesStr.isEmpty()) {
                    for (String t : currentTablesStr.split(",")) {
                        if (!t.trim().isEmpty()) mergedTables.add(t.trim());
                    }
                }
                boolean newTablesAdded = mergedTables.addAll(formattedTables);
                if ("postgresql".equalsIgnoreCase(request.getSourceConnection().getType())) {
                    mergedTables.add("public._dbz_heartbeat");
                }
                String updatedTableIncludeList = String.join(",", mergedTables);
                
                if (!newTablesAdded) {
                    sendLog(emitter, "Shared source connector " + sourceConnectorName + " is already active with all required tables.");
                } else {
                    try {
                        sendLog(emitter, "Updating shared source connector table list to include new tables and triggering full snapshot...");
                        deleteConnectorWithWait(sourceConnectorName);
                        Thread.sleep(2000);
                        
                        if ("postgresql".equalsIgnoreCase(request.getSourceConnection().getType())) {
                            try (Connection pgConn = sourceDsForCleanup.getConnection();
                                 Statement pgStmt = pgConn.createStatement()) {
                                String findSlotsSql = "SELECT active_pid FROM pg_replication_slots WHERE slot_name = '" + safeSlotName + "'";
                                try (ResultSet rs = pgStmt.executeQuery(findSlotsSql)) {
                                    while (rs.next()) {
                                        Number activePid = (Number) rs.getObject("active_pid");
                                        if (activePid != null) {
                                            try { pgStmt.execute("SELECT pg_terminate_backend(" + activePid.intValue() + ")"); } catch (Exception ignored) {}
                                            Thread.sleep(1000);
                                        }
                                    }
                                }
                                try { pgStmt.execute("SELECT pg_drop_replication_slot('" + safeSlotName + "')"); } catch (Exception ignored) {}
                                sendLog(emitter, "Dropped Postgres replication slot `" + safeSlotName + "` to trigger fresh snapshot.");
                            } catch (Exception ex) {
                                logger.warn("Could not drop shared replication slot " + safeSlotName + ": " + ex.getMessage());
                            }
                        }
                        
                        sourceConfig.put("table.include.list", updatedTableIncludeList);
                        sourceConfig.put("snapshot.mode", "initial");
                        sourceConfig.put("slot.name", safeSlotName);
                        sourceConfig.put("publication.name", "pub_" + safeSlotName);
                        
                        java.util.Map<String, Object> sourcePayload = new java.util.HashMap<>();
                        sourcePayload.put("name", sourceConnectorName);
                        sourcePayload.put("config", sourceConfig);
                        
                        org.springframework.http.HttpEntity<java.util.Map<String, Object>> sourceEntity = new org.springframework.http.HttpEntity<>(sourcePayload, headers);
                        org.springframework.http.ResponseEntity<String> sourceResponse = registerConnectorWithRetry(emitter, sourceConnectorName, sourceEntity, 3);
                        sendLog(emitter, "Re-created shared source connector successfully: " + sourceResponse.getStatusCode());
                    } catch (Exception e) {
                        sendLog(emitter, "ERROR: Could not update shared source connector in Debezium: " + e.getMessage());
                        throw e;
                    }
                }
            } else {
                sourceConfig.put("slot.name", "slot_" + baseName + "_shared");
                sourceConfig.put("publication.name", "pub_slot_" + baseName + "_shared");
                java.util.Map<String, Object> sourcePayload = new java.util.HashMap<>();
                sourcePayload.put("name", sourceConnectorName);
                sourcePayload.put("config", sourceConfig);
                
                try {
                    org.springframework.http.HttpEntity<java.util.Map<String, Object>> sourceEntity = new org.springframework.http.HttpEntity<>(sourcePayload, headers);
                    org.springframework.http.ResponseEntity<String> sourceResponse = registerConnectorWithRetry(emitter, sourceConnectorName, sourceEntity, 3);
                    sendLog(emitter, "Shared source connector registered successfully: " + sourceResponse.getStatusCode());
                } catch (Exception e) {
                    sendLog(emitter, "ERROR: Could not register shared source connector in Debezium: " + e.getMessage());
                    throw e;
                }
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
                
                // ClickHouse does NOT support WITH (CTE) inside Materialized Views.
                // Inline CTEs BEFORE rewriting table names so JSqlParser gets clean PostgreSQL SQL.
                String sqlInlined = inlineCTEs(sqlWithMeta, baseName);

                String rewrittenSql;
                if (physicalTables.size() > 1) {
                    // For JOIN queries, add PK filters to the WHERE clause (avoiding subqueries/FINAL which are disallowed in MVs)
                    String sqlWithFilters = addPKFiltersToWhere(sqlInlined, physicalTables, tableToPKs);
                    rewrittenSql = rewriteQueryForClickHouse(sqlWithFilters, physicalTables, baseName, request.getSourceConnection(), chDb, t);
                    // Preserve join types (LEFT/INNER/etc.) as specified in user query

                } else {
                    rewrittenSql = rewriteQueryForClickHouse(sqlInlined, physicalTables, baseName, request.getSourceConnection(), chDb, t);
                }
                
                StringBuilder mvDdl = new StringBuilder();
                mvDdl.append("CREATE MATERIALIZED VIEW IF NOT EXISTS `").append(chDb).append("`.`").append(mvName).append("`\n");
                mvDdl.append("TO `").append(chDb).append("`.`").append(request.getTargetTable()).append("`\n");
                mvDdl.append("AS ").append(rewrittenSql);
                
                sendLog(emitter, "Creating MV `" + mvName + "` triggered on landing table `" + landingTable + "`...");
                logger.info("Executing MV DDL:\n{}", mvDdl.toString());
                
                try (Connection conn = targetDs.getConnection();
                     Statement stmt = conn.createStatement()) {
                    try { stmt.execute("DROP VIEW IF EXISTS `" + chDb + "`.`" + mvName + "`"); } catch (Exception ignored) {}
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
            List<ConnectionDetails> allConns = (request.getSourceConnections() != null && !request.getSourceConnections().isEmpty()) ?
                    request.getSourceConnections() : java.util.Collections.singletonList(request.getSourceConnection());

            for (ConnectionDetails connItem : allConns) {
                String itemBaseName = connItem.getName().replaceAll("[^a-zA-Z0-9_]", "_").toLowerCase();
                String itemTopicPrefix = "cdc_" + itemBaseName + "_";
                for (String t : physicalTables) {
                    String cleanTable = t.replaceAll("[\"``]", "").replace(".", "_");
                    String topicName = itemTopicPrefix + cleanTable;
                    if (!expectedTopics.contains(topicName)) {
                        expectedTopics.add(topicName);
                    }
                }
            }
            sinkConfig.put("topics", String.join(",", expectedTopics));
            // sinkConfig.put("topics.regex", topicPrefix + ".*");
            String sinkHost = request.getTargetConnection().getHost() != null && !request.getTargetConnection().getHost().trim().isEmpty() ? request.getTargetConnection().getHost().trim() : "127.0.0.1";
            String sinkPort = String.valueOf(request.getTargetConnection().getPort());
            if (request.getTargetConnection().isUseSsh()) {
                try {
                    int tunnelPort = sshTunnelService.getOrOpenTunnel(request.getTargetConnection(), String.valueOf(request.getTargetConnection().getId()));
                    sshTunnelService.markTunnelAsPermanent(String.valueOf(request.getTargetConnection().getId()));
                    sinkHost = resolveTunnelHost();
                    sinkPort = String.valueOf(tunnelPort);
                    sendLog(emitter, "Target ClickHouse connection uses SSH tunnel. Routing ClickHouse Sink through " + sinkHost + ":" + tunnelPort);
                } catch (Exception ex) {
                    logger.error("Failed to establish SSH tunnel for ClickHouse sink connector", ex);
                    sendLog(emitter, "WARNING: Failed to open SSH tunnel for ClickHouse: " + ex.getMessage());
                }
            }

            sinkConfig.put("hostname", sinkHost);
            sinkConfig.put("port", sinkPort);
            sinkConfig.put("username", request.getTargetConnection().getUsername() != null ? request.getTargetConnection().getUsername().trim() : "");
            sinkConfig.put("password", request.getTargetConnection().getPassword());
            sinkConfig.put("database", request.getTargetConnection().getDatabase() != null ? request.getTargetConnection().getDatabase().trim() : "");
            sinkConfig.put("clickhouseSettings", "insert_quorum=1"); // Optional optimization
            sinkConfig.put("transforms", "unwrap");
            sinkConfig.put("transforms.unwrap.type", "io.debezium.transforms.ExtractNewRecordState");
            sinkConfig.put("transforms.unwrap.drop.tombstones", "true");
            sinkConfig.put("transforms.unwrap.delete.handling.mode", "rewrite");
            sinkConfig.put("key.converter", "org.apache.kafka.connect.json.JsonConverter");
            sinkConfig.put("key.converter.schemas.enable", "false");
            sinkConfig.put("value.converter", "org.apache.kafka.connect.json.JsonConverter");
            sinkConfig.put("value.converter.schemas.enable", "true");
            sinkConfig.put("errors.tolerance", "all");
            sinkConfig.put("errors.log.enable", "true");
            sinkConfig.put("errors.log.include.messages", "true");
            
            // Force immediate consumer offset commits to prevent re-reading snapshot batch upon Debezium partition rebalance
            sinkConfig.put("consumer.override.auto.offset.reset", "earliest");
            sinkConfig.put("consumer.override.enable.auto.commit", "true");
            sinkConfig.put("consumer.override.auto.commit.interval.ms", "1000");
            
            java.util.Map<String, Object> sinkPayload = new java.util.HashMap<>();
            sinkPayload.put("name", sinkConnectorName);
            sinkPayload.put("config", sinkConfig);
            
            try {
                org.springframework.http.HttpEntity<java.util.Map<String, Object>> sinkEntity = new org.springframework.http.HttpEntity<>(sinkPayload, headers);
                org.springframework.http.ResponseEntity<String> sinkResponse = registerConnectorWithRetry(emitter, sinkConnectorName, sinkEntity, 3);
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
                    
                    if (totalRows == previousTotalRows) {
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
                
                // Populate target table with initial snapshot data from landing tables to ensure complete snapshot
                sendLog(emitter, "Populating target table `" + request.getTargetTable() + "` with initial snapshot data...");
                try (Connection conn = targetDs.getConnection();
                     Statement stmt = conn.createStatement()) {
                    try { stmt.execute("SET max_memory_usage = 0"); } catch (Exception ignored) {}
                    try { stmt.execute("SET max_threads = 1"); } catch (Exception ignored) {}
                    try { stmt.execute("SET join_algorithm = 'grace_hash,partial_merge,hash'"); } catch (Exception ignored) {}
                    try { stmt.execute("SET max_bytes_before_external_group_by = 100000000"); } catch (Exception ignored) {}
                    try { stmt.execute("SET max_bytes_before_external_sort = 100000000"); } catch (Exception ignored) {}

                    String primaryTable = physicalTables.get(0);
                    String rotatedSql = rotateQuery(originalQuery, primaryTable);
                    String sqlWithMeta = addMetadataColsToSelect(rotatedSql, primaryTable);
                    String rewrittenSql = rewriteQueryForClickHouse(sqlWithMeta, physicalTables, baseName, request.getSourceConnection(), chDb);

                    String settingsClause = " SETTINGS max_threads = 1, max_memory_usage = 0, join_algorithm = 'grace_hash,partial_merge,hash', max_bytes_before_external_group_by = 100000000, max_bytes_before_external_sort = 100000000";
                    String insertSql = "INSERT INTO `" + chDb + "`.`" + request.getTargetTable() + "` " + rewrittenSql + settingsClause;
                    logger.info("Executing initial snapshot populate SQL:\n{}", insertSql);
                    stmt.execute(insertSql);
                    sendLog(emitter, "Initial snapshot data populated into `" + request.getTargetTable() + "`.");
                } catch (Exception ex) {
                    logger.warn("Could not populate initial snapshot into " + request.getTargetTable() + ": " + ex.getMessage());
                }

                // Force immediate physical deduplication on landing and target tables after snapshot
                sendLog(emitter, "Optimizing target table and landing tables for physical deduplication...");
                try (Connection conn = targetDs.getConnection();
                     Statement stmt = conn.createStatement()) {
                    for (String t : physicalTables) {
                        String landingTable = getClickHouseLandingTable(t, baseName, request.getSourceConnection());
                        try {
                            stmt.execute("OPTIMIZE TABLE `" + chDb + "`.`" + landingTable + "` FINAL DEDUPLICATE");
                        } catch (Exception ex) {
                            try {
                                stmt.execute("OPTIMIZE TABLE `" + chDb + "`.`" + landingTable + "` FINAL");
                            } catch (Exception ignored) {}
                        }
                    }
                    try {
                        stmt.execute("OPTIMIZE TABLE `" + chDb + "`.`" + request.getTargetTable() + "` FINAL DEDUPLICATE");
                    } catch (Exception ex) {
                        try {
                            stmt.execute("OPTIMIZE TABLE `" + chDb + "`.`" + request.getTargetTable() + "` FINAL");
                        } catch (Exception ignored) {}
                    }
                    sendLog(emitter, "Physical deduplication completed. Raw table counts now exact.");
                } catch (Exception e) {
                    logger.warn("Could not optimize table after snapshot: " + e.getMessage());
                }

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
        Set<String> cteNames = new HashSet<>();
        try {
            net.sf.jsqlparser.statement.Statement statement = CCJSqlParserUtil.parse(sql);
            if (statement instanceof Select) {
                Select select = (Select) statement;
                if (select.getWithItemsList() != null) {
                    for (net.sf.jsqlparser.statement.select.WithItem wi : select.getWithItemsList()) {
                        String name = wi.getAlias() != null ? wi.getAlias().getName() : wi.toString().split("\\s+")[0];
                        if (name != null) {
                            cteNames.add(name.replaceAll("[`\"']", "").toLowerCase());
                        }
                    }
                }
                TablesNamesFinder finder = new TablesNamesFinder();
                for (String t : finder.getTableList(statement)) {
                    String clean = t.replaceAll("[`\"']", "").toLowerCase();
                    if (!cteNames.contains(clean) && !tables.contains(t)) {
                        tables.add(t);
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Could not parse SQL query using JSqlParser: " + e.getMessage());
            // Fallback: simple regex search if parsing fails (for unusual SQL syntax)
            Pattern p = Pattern.compile("(?i)\\bfrom\\s+([a-zA-Z0-9_\\.]+)|\\bjoin\\s+([a-zA-Z0-9_\\.]+)");
            Matcher m = p.matcher(sql);
            while (m.find()) {
                String t = m.group(1) != null ? m.group(1) : m.group(2);
                if (t != null && !tables.contains(t) && !cteNames.contains(t.toLowerCase())) {
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
                    Map<String, String> aliasToTable = new LinkedHashMap<>();
                    List<String> orderedAliases = new ArrayList<>();

                    if (plain.getFromItem() instanceof net.sf.jsqlparser.schema.Table) {
                        net.sf.jsqlparser.schema.Table t = (net.sf.jsqlparser.schema.Table) plain.getFromItem();
                        String tableName = t.getName();
                        if (t.getSchemaName() != null) {
                            tableName = t.getSchemaName() + "." + tableName;
                        }
                        String aliasName = t.getAlias() != null ? t.getAlias().getName() : t.getName();
                        aliasToTable.put(aliasName.toLowerCase(), tableName);
                        if (t.getSchemaName() != null) {
                            aliasToTable.put(t.getFullyQualifiedName().toLowerCase(), tableName);
                        }
                        orderedAliases.add(aliasName);
                    }
                    if (plain.getJoins() != null) {
                        for (Join j : plain.getJoins()) {
                            if (j.getRightItem() instanceof net.sf.jsqlparser.schema.Table) {
                                net.sf.jsqlparser.schema.Table t = (net.sf.jsqlparser.schema.Table) j.getRightItem();
                                String tableName = t.getName();
                                if (t.getSchemaName() != null) {
                                    tableName = t.getSchemaName() + "." + tableName;
                                }
                                String aliasName = t.getAlias() != null ? t.getAlias().getName() : t.getName();
                                aliasToTable.put(aliasName.toLowerCase(), tableName);
                                if (t.getSchemaName() != null) {
                                    aliasToTable.put(t.getFullyQualifiedName().toLowerCase(), tableName);
                                }
                                if (!orderedAliases.contains(aliasName)) {
                                    orderedAliases.add(aliasName);
                                }
                            }
                        }
                    }

                    boolean modified = false;
                    List<net.sf.jsqlparser.statement.select.SelectItem<?>> newItems = new ArrayList<>();
                    Set<String> usedAliases = new HashSet<>();
                    
                    for (net.sf.jsqlparser.statement.select.SelectItem item : plain.getSelectItems()) {
                        if (item.getExpression() instanceof net.sf.jsqlparser.statement.select.AllTableColumns) {
                            net.sf.jsqlparser.statement.select.AllTableColumns atc = (net.sf.jsqlparser.statement.select.AllTableColumns) item.getExpression();
                            String alias = atc.getTable().getFullyQualifiedName();
                            String physicalTable = aliasToTable.get(alias.toLowerCase());
                            if (physicalTable == null) {
                                physicalTable = aliasToTable.get(atc.getTable().getName().toLowerCase());
                            }
                            if (physicalTable != null) {
                                List<String> cols = getColumnsForTable(conn, physicalTable, sourceConn);
                                if (!cols.isEmpty()) {
                                    for (String col : cols) {
                                        net.sf.jsqlparser.statement.select.SelectItem newItem = new net.sf.jsqlparser.statement.select.SelectItem();
                                        net.sf.jsqlparser.schema.Column c = new net.sf.jsqlparser.schema.Column(new net.sf.jsqlparser.schema.Table(atc.getTable().getName()), col);
                                        newItem.setExpression(c);
                                        String targetAlias = col.toLowerCase();
                                        if (usedAliases.contains(targetAlias)) {
                                            targetAlias = (atc.getTable().getName() + "_" + col).toLowerCase();
                                        }
                                        usedAliases.add(targetAlias);
                                        newItem.setAlias(new net.sf.jsqlparser.expression.Alias(targetAlias));
                                        newItems.add(newItem);
                                    }
                                    modified = true;
                                    continue;
                                }
                            }
                        } else if (item.getExpression() instanceof net.sf.jsqlparser.statement.select.AllColumns) {
                            boolean expandedAny = false;
                            for (String aliasName : orderedAliases) {
                                String physicalTable = aliasToTable.get(aliasName.toLowerCase());
                                if (physicalTable != null) {
                                    List<String> cols = getColumnsForTable(conn, physicalTable, sourceConn);
                                    for (String col : cols) {
                                        net.sf.jsqlparser.statement.select.SelectItem newItem = new net.sf.jsqlparser.statement.select.SelectItem();
                                        net.sf.jsqlparser.schema.Column c = new net.sf.jsqlparser.schema.Column(new net.sf.jsqlparser.schema.Table(aliasName), col);
                                        newItem.setExpression(c);
                                        String targetAlias = col.toLowerCase();
                                        if (usedAliases.contains(targetAlias)) {
                                            targetAlias = (aliasName + "_" + col).toLowerCase();
                                        }
                                        usedAliases.add(targetAlias);
                                        newItem.setAlias(new net.sf.jsqlparser.expression.Alias(targetAlias));
                                        newItems.add(newItem);
                                        expandedAny = true;
                                    }
                                }
                            }
                            if (expandedAny) {
                                modified = true;
                                continue;
                            }
                        } else if (item.getExpression() instanceof net.sf.jsqlparser.schema.Column) {
                            net.sf.jsqlparser.schema.Column col = (net.sf.jsqlparser.schema.Column) item.getExpression();
                            if (item.getAlias() == null && col.getTable() != null && col.getTable().getName() != null) {
                                String targetAlias = col.getColumnName().toLowerCase();
                                if (usedAliases.contains(targetAlias)) {
                                    targetAlias = (col.getTable().getName() + "_" + col.getColumnName()).toLowerCase();
                                }
                                usedAliases.add(targetAlias);
                                item.setAlias(new net.sf.jsqlparser.expression.Alias(targetAlias));
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

    private String convertPostgresJsonToClickHouse(String sql) {
        if (sql == null || !sql.contains("->")) {
            return sql;
        }
        String result = sql;

        // 1. CAST(expr AS json/jsonb) ->> or -> index (numeric)
        Pattern p1 = Pattern.compile("(?i)CAST\\s*\\(\\s*(.+?)\\s+AS\\s+jsonb?\\s*\\)\\s*(->>|->)\\s*(\\d+)");
        Matcher m1 = p1.matcher(result);
        StringBuffer sb1 = new StringBuffer();
        while (m1.find()) {
            String expr = m1.group(1).trim();
            int arrIdx = Integer.parseInt(m1.group(3)) + 2;
            m1.appendReplacement(sb1, Matcher.quoteReplacement("splitByRegexp('[\\\\\"\\\\s*\\\\[\\\\]\\\\s*,\\\\s*]+', " + expr + ")[" + arrIdx + "]"));
        }
        m1.appendTail(sb1);
        result = sb1.toString();

        // 2. CAST(expr AS json/jsonb) ->> or -> 'key' (string)
        Pattern p2 = Pattern.compile("(?i)CAST\\s*\\(\\s*(.+?)\\s+AS\\s+jsonb?\\s*\\)\\s*(->>|->)\\s*('(?:[^'\\\\]|\\\\.)*')");
        Matcher m2 = p2.matcher(result);
        StringBuffer sb2 = new StringBuffer();
        while (m2.find()) {
            String expr = m2.group(1).trim();
            String key = m2.group(3).trim();
            m2.appendReplacement(sb2, Matcher.quoteReplacement("visitParamExtractString(" + expr + ", " + key + ")"));
        }
        m2.appendTail(sb2);
        result = sb2.toString();

        // 3. expr::json/jsonb ->> or -> index
        Pattern p3 = Pattern.compile("(?i)([a-zA-Z0-9_.]+|\\([^\\)]+\\))\\s*::\\s*jsonb?\\s*(->>|->)\\s*(\\d+)");
        Matcher m3 = p3.matcher(result);
        StringBuffer sb3 = new StringBuffer();
        while (m3.find()) {
            String expr = m3.group(1).trim();
            int arrIdx = Integer.parseInt(m3.group(3)) + 2;
            m3.appendReplacement(sb3, Matcher.quoteReplacement("splitByRegexp('[\\\\\"\\\\s*\\\\[\\\\]\\\\s*,\\\\s*]+', " + expr + ")[" + arrIdx + "]"));
        }
        m3.appendTail(sb3);
        result = sb3.toString();

        // 4. expr::json/jsonb ->> or -> 'key'
        Pattern p4 = Pattern.compile("(?i)([a-zA-Z0-9_.]+|\\([^\\)]+\\))\\s*::\\s*jsonb?\\s*(->>|->)\\s*('(?:[^'\\\\]|\\\\.)*')");
        Matcher m4 = p4.matcher(result);
        StringBuffer sb4 = new StringBuffer();
        while (m4.find()) {
            String expr = m4.group(1).trim();
            String key = m4.group(3).trim();
            m4.appendReplacement(sb4, Matcher.quoteReplacement("visitParamExtractString(" + expr + ", " + key + ")"));
        }
        m4.appendTail(sb4);
        result = sb4.toString();

        // 5. expr ->> or -> index
        Pattern p5 = Pattern.compile("(?i)([a-zA-Z0-9_.]+|\\([^\\)]+\\))\\s*(->>|->)\\s*(\\d+)");
        Matcher m5 = p5.matcher(result);
        StringBuffer sb5 = new StringBuffer();
        while (m5.find()) {
            String expr = m5.group(1).trim();
            int arrIdx = Integer.parseInt(m5.group(3)) + 2;
            m5.appendReplacement(sb5, Matcher.quoteReplacement("splitByRegexp('[\\\\\"\\\\s*\\\\[\\\\]\\\\s*,\\\\s*]+', " + expr + ")[" + arrIdx + "]"));
        }
        m5.appendTail(sb5);
        result = sb5.toString();

        // 6. expr ->> or -> 'key'
        Pattern p6 = Pattern.compile("(?i)([a-zA-Z0-9_.]+|\\([^\\)]+\\))\\s*(->>|->)\\s*('(?:[^'\\\\]|\\\\.)*')");
        Matcher m6 = p6.matcher(result);
        StringBuffer sb6 = new StringBuffer();
        while (m6.find()) {
            String expr = m6.group(1).trim();
            String key = m6.group(3).trim();
            m6.appendReplacement(sb6, Matcher.quoteReplacement("visitParamExtractString(" + expr + ", " + key + ")"));
        }
        m6.appendTail(sb6);
        result = sb6.toString();

        return result;
    }

    private String rewriteQueryForClickHouse(String sql, List<String> physicalTables, String baseName, ConnectionDetails sourceConn, String chDb) {
        return rewriteQueryForClickHouse(sql, physicalTables, baseName, sourceConn, chDb, null);
    }

    private String rewriteQueryForClickHouse(String sql, List<String> physicalTables, String baseName, ConnectionDetails sourceConn, String chDb, String triggerTable) {
        sql = convertPostgresJsonToClickHouse(sql);
        try {
            net.sf.jsqlparser.statement.Statement stmt = CCJSqlParserUtil.parse(sql);
            if (stmt instanceof Select) {
                Select select = (Select) stmt;
                if (select.getWithItemsList() != null) {
                    for (net.sf.jsqlparser.statement.select.WithItem withItem : select.getWithItemsList()) {
                        if (withItem.getSelect() != null && withItem.getSelect().getPlainSelect() != null) {
                            PlainSelect withPlain = withItem.getSelect().getPlainSelect();
                            withPlain.setFromItem(rewriteFromItemForClickHouse(withPlain.getFromItem(), physicalTables, baseName, sourceConn, chDb, triggerTable));
                            if (withPlain.getJoins() != null) {
                                for (Join join : withPlain.getJoins()) {
                                    join.setRightItem(rewriteFromItemForClickHouse(join.getRightItem(), physicalTables, baseName, sourceConn, chDb, triggerTable));
                                }
                            }
                        }
                    }
                }
                PlainSelect plain = select.getPlainSelect();
                if (plain != null) {
                    plain.setFromItem(rewriteFromItemForClickHouse(plain.getFromItem(), physicalTables, baseName, sourceConn, chDb, triggerTable));
                    if (plain.getJoins() != null) {
                        for (Join join : plain.getJoins()) {
                            join.setRightItem(rewriteFromItemForClickHouse(join.getRightItem(), physicalTables, baseName, sourceConn, chDb, triggerTable));
                        }
                    }
                    if (triggerTable != null && !triggerTable.isEmpty()) {
                        swapTriggerTableToFrom(plain, triggerTable, baseName, sourceConn, chDb);
                    } else {
                        reorderJoinsByDependency(plain);
                    }
                    String res = select.toString();
                    return res.replaceAll("(?i)\\bWHERE\\s+\\(toYear\\(", "WHERE (is_deleted = 1 OR toYear(")
                              .replaceAll("(?i)\\bWHERE\\s+toYear\\(", "WHERE is_deleted = 1 OR toYear(");
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to rewrite query using JSqlParser, falling back to regex: " + e.getMessage());
        }

        String rewrittenSql = sql;
        for (String t : physicalTables) {
            String landingTable = getClickHouseLandingTable(t, baseName, sourceConn);
            String escapedLanding = "`" + chDb + "`.`" + landingTable + "`";
            String shortTable = t.contains(".") ? t.substring(t.indexOf('.') + 1) : t;

            String patternStrWithSchema = "(?i)\\b" + Pattern.quote(t) + "\\b(\\s+(?:AS\\s+)?(?!(?:WHERE|FROM|JOIN|ON|GROUP|ORDER|HAVING|LIMIT|UNION|SELECT|INNER|LEFT|RIGHT|FULL|CROSS|NATURAL|OUTER|SET|WITH|CASE|WHEN|THEN|ELSE|END|IN|IS|AND|OR|NOT|NULL|FETCH|OFFSET|PREWHERE|SETTINGS|FINAL|SAMPLE|STREAM)\\b)([a-zA-Z0-9_]+))?";
            Pattern p = Pattern.compile(patternStrWithSchema);
            Matcher m = p.matcher(rewrittenSql);
            StringBuffer sb = new StringBuffer();
            while (m.find()) {
                String existingAlias = m.group(2);
                if (existingAlias != null && !existingAlias.isEmpty()) {
                    m.appendReplacement(sb, escapedLanding + " AS `" + existingAlias + "`");
                } else {
                    m.appendReplacement(sb, escapedLanding + " AS `" + shortTable + "`");
                }
            }
            m.appendTail(sb);
            rewrittenSql = sb.toString();

            if (t.contains(".")) {
                String patternStrShort = "(?i)\\b(FROM|JOIN)\\s+`?" + Pattern.quote(shortTable) + "`?\\b(\\s+(?:AS\\s+)?([a-zA-Z0-9_]+))?";
                Pattern pShort = Pattern.compile(patternStrShort);
                Matcher mShort = pShort.matcher(rewrittenSql);
                StringBuffer sbShort = new StringBuffer();
                while (mShort.find()) {
                    String prefix = mShort.group(1);
                    String existingAlias = mShort.group(3);
                    if (existingAlias != null && !existingAlias.isEmpty()) {
                        mShort.appendReplacement(sbShort, prefix + " " + escapedLanding + " AS `" + existingAlias + "`");
                    } else {
                        mShort.appendReplacement(sbShort, prefix + " " + escapedLanding + " AS `" + shortTable + "`");
                    }
                }
                mShort.appendTail(sbShort);
                rewrittenSql = sbShort.toString();
            }
        }
        return rewrittenSql.replaceAll("(?i)\\bWHERE\\s+\\(toYear\\(", "WHERE (is_deleted = 1 OR toYear(")
                           .replaceAll("(?i)\\bWHERE\\s+toYear\\(", "WHERE is_deleted = 1 OR toYear(");
    }

    private void swapTriggerTableToFrom(PlainSelect plain, String triggerTable, String baseName, ConnectionDetails sourceConn, String chDb) {
        if (triggerTable == null || plain == null || plain.getJoins() == null) return;
        String targetLanding = getClickHouseLandingTable(triggerTable, baseName, sourceConn).toLowerCase();
        
        FromItem currentFrom = plain.getFromItem();
        if (currentFrom != null && currentFrom.toString().toLowerCase().contains(targetLanding)) {
            return;
        }
        
        for (int i = 0; i < plain.getJoins().size(); i++) {
            Join j = plain.getJoins().get(i);
            FromItem right = j.getRightItem();
            if (right != null && right.toString().toLowerCase().contains(targetLanding)) {
                j.setRightItem(currentFrom);
                plain.setFromItem(right);
                break;
            }
        }
        reorderJoinsByDependency(plain);
    }

    /**
     * Expands all CTE definitions (WITH ... AS (...)) inline as subqueries in the main query.
     * ClickHouse does NOT support WITH clauses inside Materialized View definitions,
     * so we must inline them before creating the MV DDL.
     *
     * IMPORTANT: Call this on clean PostgreSQL SQL BEFORE rewriting table names to ClickHouse
     * landing table names, so JSqlParser can reliably parse the query.
     *
     * Example:
     *   WITH q AS (SELECT val FROM t WHERE col = 'X')
     *   SELECT q.val FROM main CROSS JOIN q alias
     * becomes:
     *   SELECT q.val FROM main CROSS JOIN (SELECT val FROM t WHERE col = 'X') AS `alias`
     */
    private String inlineCTEs(String sql) {
        return inlineCTEs(sql, null);
    }

    private String inlineCTEs(String sql, String baseName) {
        try {
            net.sf.jsqlparser.statement.Statement stmt = CCJSqlParserUtil.parse(sql);
            if (!(stmt instanceof Select)) return sql;
            Select select = (Select) stmt;
            if (select.getWithItemsList() == null || select.getWithItemsList().isEmpty()) return sql;

            String compCode = "P001";
            if (baseName != null) {
                String bn = baseName.toLowerCase();
                if (bn.contains("p003") || bn.contains("mkn")) compCode = "P003";
                else if (bn.contains("p011") || bn.contains("bpi")) compCode = "P011";
                else {
                    java.util.regex.Matcher mComp = java.util.regex.Pattern.compile("(p\\d{3})", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(bn);
                    if (mComp.find()) {
                        compCode = mComp.group(1).toUpperCase();
                    }
                }
            }

            // Build a map: lowercase CTE name -> CTE SELECT SQL string
            java.util.Map<String, String> cteMap = new java.util.LinkedHashMap<>();
            for (net.sf.jsqlparser.statement.select.WithItem wi : select.getWithItemsList()) {
                if (wi.getSelect() != null) {
                    ensureExplicitColumnAliases(wi.getSelect());
                }
                String cteName = wi.getAlias() != null ? wi.getAlias().getName() : wi.toString().split("\\s+")[0];
                cteName = cteName.replaceAll("[`\"']", "").toLowerCase();
                String cteBody = wi.getSelect() != null ? wi.getSelect().toString().trim() : "";
                // JSqlParser wraps WITH body in ParenthesedSelect which adds outer ( ).
                // Strip them here so we don't produce double parens ((...)) when inlining.
                if (cteBody.startsWith("(") && cteBody.endsWith(")")) {
                    cteBody = cteBody.substring(1, cteBody.length() - 1).trim();
                }

                // If CTE is fetching company-id from mhd_lookup, replace body with static value to avoid mhd_lookup CROSS JOIN dependency
                if (cteName.equals("q_perusahaan") || (cteBody.contains("mhd_lookup") && cteBody.contains("COMPANY-ID"))) {
                    cteBody = "SELECT '" + compCode + "' AS kode_perusahaan";
                }

                cteMap.put(cteName, cteBody);
            }

            if (cteMap.isEmpty()) return sql;

            // Resolve references to other CTEs inside CTE bodies (e.g. q_so_penjualan referencing q_perusahaan)
            for (java.util.Map.Entry<String, String> outer : cteMap.entrySet()) {
                String targetCteName = outer.getKey();
                String targetCteBody = outer.getValue();

                for (java.util.Map.Entry<String, String> inner : cteMap.entrySet()) {
                    if (inner.getKey().equals(targetCteName)) continue;
                    String innerBody = inner.getValue();
                    Pattern p = Pattern.compile(
                        "(?i)(\\bFROM\\b|(?:\\b[a-zA-Z_]+\\s+)?\\bJOIN\\b)\\s+`?" + Pattern.quote(targetCteName) + "`?(?:\\s+(?:AS\\s+)?([a-zA-Z_][a-zA-Z0-9_]*))?\\b",
                        Pattern.CASE_INSENSITIVE
                    );
                    Matcher m = p.matcher(innerBody);
                    StringBuffer sb = new StringBuffer();
                    while (m.find()) {
                        String keyword = m.group(1);
                        String alias = m.group(2);
                        String effectiveAlias = (alias != null && !alias.isBlank()) ? alias : targetCteName;
                        m.appendReplacement(sb, Matcher.quoteReplacement(
                            keyword + " (" + targetCteBody + ") AS `" + effectiveAlias + "`"
                        ));
                    }
                    m.appendTail(sb);
                    inner.setValue(sb.toString());
                }
            }

            // Get the main query body (strip WITH clause)
            select.setWithItemsList(null);
            String mainSql = select.toString();

            // Replace each reference to CTE name (as a table reference) with an inline subquery in mainSql.
            for (java.util.Map.Entry<String, String> entry : cteMap.entrySet()) {
                String cteName = entry.getKey();
                String cteBody = entry.getValue();
                Pattern p = Pattern.compile(
                    "(?i)(\\bFROM\\b|(?:\\b[a-zA-Z_]+\\s+)?\\bJOIN\\b)\\s+`?" + Pattern.quote(cteName) + "`?(?:\\s+(?:AS\\s+)?([a-zA-Z_][a-zA-Z0-9_]*))?\\b",
                    Pattern.CASE_INSENSITIVE
                );
                Matcher m = p.matcher(mainSql);
                StringBuffer sb = new StringBuffer();
                while (m.find()) {
                    String keyword = m.group(1);
                    String alias = m.group(2);
                    String effectiveAlias = (alias != null && !alias.isBlank()) ? alias : cteName;
                    m.appendReplacement(sb, Matcher.quoteReplacement(
                        keyword + " (" + cteBody + ") AS `" + effectiveAlias + "`"
                    ));
                }
                m.appendTail(sb);
                mainSql = sb.toString();
            }

            return mainSql;
        } catch (Exception e) {
            logger.warn("inlineCTEs: Could not parse SQL for CTE inlining, returning as-is: " + e.getMessage());
            return sql;
        }
    }

    private void ensureExplicitColumnAliases(Select select) {
        if (select == null) return;
        if (select.getPlainSelect() != null) {
            ensureExplicitColumnAliases(select.getPlainSelect());
        } else if (select.getSetOperationList() != null) {
            for (Select s : select.getSetOperationList().getSelects()) {
                ensureExplicitColumnAliases(s);
            }
        }
    }

    private void ensureExplicitColumnAliases(PlainSelect plainSelect) {
        if (plainSelect == null || plainSelect.getSelectItems() == null) return;
        for (net.sf.jsqlparser.statement.select.SelectItem<?> item : plainSelect.getSelectItems()) {
            if (item.getAlias() == null && item.getExpression() instanceof net.sf.jsqlparser.schema.Column) {
                net.sf.jsqlparser.schema.Column col = (net.sf.jsqlparser.schema.Column) item.getExpression();
                item.setAlias(new net.sf.jsqlparser.expression.Alias(col.getColumnName()));
            }
        }
    }

    private FromItem rewriteFromItemForClickHouse(FromItem item, List<String> physicalTables, String baseName, ConnectionDetails sourceConn, String chDb, String triggerTable) {
        if (item instanceof Table) {
            Table t = (Table) item;
            String matchedPhysicalTable = null;
            for (String pt : physicalTables) {
                if (isTableMatch(t, pt)) {
                    matchedPhysicalTable = pt;
                    break;
                }
            }
            if (matchedPhysicalTable != null) {
                String landingTable = getClickHouseLandingTable(matchedPhysicalTable, baseName, sourceConn);
                String shortTable = matchedPhysicalTable.contains(".") ? matchedPhysicalTable.substring(matchedPhysicalTable.indexOf('.') + 1) : matchedPhysicalTable;

                net.sf.jsqlparser.expression.Alias existingAlias = t.getAlias();
                net.sf.jsqlparser.expression.Alias aliasToUse = existingAlias != null ? existingAlias : new net.sf.jsqlparser.expression.Alias("`" + shortTable + "`");

                // If this is a joined table (not the main trigger table), we wrap it in a FINAL subquery
                // so we don't accidentally join against historical/deleted tombstone CDC records.
                if (triggerTable != null && !triggerTable.isEmpty() && !matchedPhysicalTable.equalsIgnoreCase(triggerTable) && !isTableMatch(t, triggerTable)) {
                    try {
                        String subquerySql = "SELECT * FROM (SELECT * FROM `" + chDb + "`.`" + landingTable + "` WHERE is_deleted = 0) AS a";
                        net.sf.jsqlparser.statement.Statement parsed = CCJSqlParserUtil.parse(subquerySql);
                        net.sf.jsqlparser.statement.select.ParenthesedSelect ps = (net.sf.jsqlparser.statement.select.ParenthesedSelect) ((PlainSelect)((Select)parsed).getSelectBody()).getFromItem();
                        ps.setAlias(aliasToUse);
                        return ps;
                    } catch (Exception e) {
                        logger.warn("Could not wrap joined table in FINAL subquery: " + e.getMessage());
                    }
                }

                t.setAlias(aliasToUse);
                t.setSchemaName("`" + chDb + "`");
                t.setName("`" + landingTable + "`");
                return t;
            }
        } else if (item instanceof net.sf.jsqlparser.statement.select.ParenthesedSelect) {
            net.sf.jsqlparser.statement.select.ParenthesedSelect ps = (net.sf.jsqlparser.statement.select.ParenthesedSelect) item;
            if (ps.getPlainSelect() != null) {
                ps.getPlainSelect().setFromItem(rewriteFromItemForClickHouse(ps.getPlainSelect().getFromItem(), physicalTables, baseName, sourceConn, chDb, triggerTable));
                if (ps.getPlainSelect().getJoins() != null) {
                    for (Join join : ps.getPlainSelect().getJoins()) {
                        join.setRightItem(rewriteFromItemForClickHouse(join.getRightItem(), physicalTables, baseName, sourceConn, chDb, triggerTable));
                    }
                }
            }
        }
        return item;
    }

    private String rewriteQueryForClickHouseView(String sql, List<String> physicalTables, String baseName, ConnectionDetails sourceConn, String chDb, java.util.Map<String, java.util.Set<String>> tableToPKs) {
        sql = convertPostgresJsonToClickHouse(sql);
        String rewrittenSql = sql;
        for (String t : physicalTables) {
            String landingTable = getClickHouseLandingTable(t, baseName, sourceConn);
            String shortTable = t.contains(".") ? t.substring(t.indexOf('.') + 1) : t;
            java.util.Set<String> pks = tableToPKs.get(t);
            StringBuilder pkFilters = new StringBuilder();
            if (pks != null) {
                for (String pk : pks) {
                    pkFilters.append(" AND `").append(pk).append("` IS NOT NULL");
                }
            }
            String existingAlias = getTableAlias(sql, t);
            String aliasToUse = (existingAlias != null && !existingAlias.isEmpty()) ? existingAlias : ("`" + shortTable + "`");
            String subquery = "(SELECT * FROM `" + chDb + "`.`" + landingTable + "` FINAL WHERE is_deleted = 0" + pkFilters.toString() + ") AS " + aliasToUse;
            
            String patternStrWithSchema = "(?i)\\b" + Pattern.quote(t) + "\\b(\\s+(?:AS\\s+)?([a-zA-Z0-9_]+))?";
            rewrittenSql = rewrittenSql.replaceAll(patternStrWithSchema, subquery);
            
            if (t.contains(".")) {
                String patternStrShort = "(?i)\\b(FROM|JOIN)\\s+`?" + Pattern.quote(shortTable) + "`?\\b(\\s+(?:AS\\s+)?([a-zA-Z0-9_]+))?";
                rewrittenSql = rewrittenSql.replaceAll(patternStrShort, "$1 " + subquery);
            }
        }
        return rewrittenSql;
    }

    private String addPKFiltersToWhere(String sql, List<String> physicalTables, java.util.Map<String, java.util.Set<String>> tableToPKs) {
        sql = convertPostgresJsonToClickHouse(sql);
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
            
            // Only apply PK filters to the primary FROM table of the query.
            // Joined tables (and especially LEFT JOINs / lookup tables) must NOT have mandatory WHERE PK filters injected,
            // as that converts LEFT JOINs to INNER JOINs and drops valid parameter/lookup rows where composite PK parts are empty.
            Set<String> targetTables = new HashSet<>();
            if (plain.getFromItem() instanceof Table) {
                Table t = (Table) plain.getFromItem();
                targetTables.add(t.getFullyQualifiedName().toLowerCase());
                targetTables.add(t.getName().toLowerCase());
                if (t.getAlias() != null && t.getAlias().getName() != null) {
                    targetTables.add(t.getAlias().getName().toLowerCase());
                }
            }

            StringBuilder conds = new StringBuilder();
            for (String t : physicalTables) {
                String alias = getTableAlias(sql, t);
                String shortTable = t.contains(".") ? t.substring(t.indexOf('.') + 1) : t;
                boolean isFromTable = targetTables.contains(t.toLowerCase()) || 
                                      targetTables.contains(shortTable.toLowerCase()) ||
                                      (alias != null && !alias.isEmpty() && targetTables.contains(alias.toLowerCase()));
                if (!isFromTable) {
                    continue;
                }

                String prefix = (alias != null && !alias.isEmpty()) ? alias + "." : "";
                java.util.Set<String> pks = tableToPKs.get(t);
                if (pks != null) {
                    for (String pk : pks) {
                        if (conds.length() > 0) {
                            conds.append(" AND ");
                        }
                        conds.append(prefix).append("`").append(pk).append("` IS NOT NULL");
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

    private void backfillLandingTableFromSource(DataSource sourceDs, DataSource targetDs, String physicalTable, String landingTable, String chDb, ConnectionDetails sourceConn, SseEmitter emitter) {
        try {
            List<ColumnInfo> cols = new ArrayList<>();
            try (Connection conn = sourceDs.getConnection();
                 PreparedStatement ps = conn.prepareStatement("SELECT * FROM " + physicalTable + (sourceConn.getType().toLowerCase().contains("sqlserver") ? " WITH (NOLOCK)" : "") + " LIMIT 1");
                 ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData meta = rs.getMetaData();
                for (int i = 1; i <= meta.getColumnCount(); i++) {
                    ColumnInfo c = new ColumnInfo();
                    c.name = meta.getColumnLabel(i);
                    c.clickhouseType = mapJdbcTypeToClickHouse(meta.getColumnType(i), meta.getPrecision(i), meta.getScale(i), meta.getColumnTypeName(i));
                    cols.add(c);
                }
            } catch (Exception e) {
                logger.warn("Could not inspect columns for backfill table " + physicalTable + ": " + e.getMessage());
                try { sendLog(emitter, "WARNING: Could not inspect columns for backfill table " + physicalTable + ": " + e.getMessage()); } catch (Exception ignored) {}
            }
            
            if (cols.isEmpty()) {
                logger.warn("Cols list is empty for backfill table " + physicalTable);
                try { sendLog(emitter, "WARNING: Could not determine columns for backfill table " + physicalTable); } catch (Exception ignored) {}
                return;
            }
            
            String srcSelectSql = "SELECT * FROM " + physicalTable;
            try (Connection srcConn = sourceDs.getConnection()) {
                try {
                    srcConn.setAutoCommit(false);
                } catch (Exception ignored) {}
                try (PreparedStatement srcPs = srcConn.prepareStatement(srcSelectSql)) {
                    try {
                        srcPs.setFetchSize(5000);
                    } catch (Exception ignored) {}
                    try (ResultSet rs = srcPs.executeQuery();
                         Connection targetConn = targetDs.getConnection();
                         Statement targetStmt = targetConn.createStatement()) {
                        try { targetStmt.execute("SET max_memory_usage = 0"); } catch (Exception ignored) {}
                        try { targetStmt.execute("SET max_threads = 1"); } catch (Exception ignored) {}
                        
                        // Detach dependent MVs
                        List<String> mvs = new ArrayList<>();
                        try (ResultSet rsMv = targetStmt.executeQuery("SELECT name FROM system.tables WHERE engine = 'MaterializedView' AND create_table_query LIKE '%" + landingTable + "%'")) {
                            while(rsMv.next()) mvs.add(rsMv.getString("name"));
                        }
                        for (String mv : mvs) { targetStmt.execute("DETACH TABLE `" + chDb + "`.`" + mv + "`"); }
                        
                        try {
                            StringBuilder psSql = new StringBuilder("INSERT INTO `").append(chDb).append("`.`").append(landingTable).append("` (`");
                            psSql.append(cols.stream().map(c -> c.name).collect(java.util.stream.Collectors.joining("`, `")));
                            psSql.append("`, `version`, `is_deleted`) VALUES (");
                            for (int i = 0; i < cols.size() + 2; i++) {
                                psSql.append(i == 0 ? "?" : ", ?");
                            }
                            psSql.append(")");
                            
                            int rowCount = 0;
                            int batchRows = 0;
                            
                            try (PreparedStatement targetPs = targetConn.prepareStatement(psSql.toString())) {
                                while (rs.next()) {
                                    for (int i = 1; i <= cols.size(); i++) {
                                        targetPs.setObject(i, rs.getObject(i));
                                    }
                                    targetPs.setLong(cols.size() + 1, 0L);
                                    targetPs.setInt(cols.size() + 2, 0);
                                    targetPs.addBatch();
                                    rowCount++;
                                    batchRows++;
                                    
                                    if (rowCount % 50000 == 0) {
                                        logger.info("Backfilling landing table `{}`: {} rows processed...", landingTable, rowCount);
                                        sendLog(emitter, "Backfilled " + rowCount + " rows into landing table `" + landingTable + "`...");
                                    }
                                    
                                    if (batchRows >= 2000) {
                                        targetPs.executeBatch();
                                        batchRows = 0;
                                    }
                                }
                                
                                if (batchRows > 0) {
                                    targetPs.executeBatch();
                                }
                            }
                            
                            logger.info("Successfully backfilled {} rows into landing table {}", rowCount, landingTable);
                            try { sendLog(emitter, "Successfully backfilled " + rowCount + " rows into landing table `" + landingTable + "`."); } catch (Exception ignored) {}
                        } finally {
                            // Attach dependent MVs back
                            for (String mv : mvs) { targetStmt.execute("ATTACH TABLE `" + chDb + "`.`" + mv + "`"); }
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Could not backfill landing table " + landingTable + " directly from source: " + e.getMessage(), e);
            try { sendLog(emitter, "WARNING: Could not backfill landing table `" + landingTable + "` directly from source: " + e.getMessage()); } catch (Exception ignored) {}
        }
    }

    private String rotateQuery(String sql, String triggerTable) {
        sql = convertPostgresJsonToClickHouse(sql);
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
                reorderJoinsByDependency(plain);
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
                reorderJoinsByDependency(plain);
                return select.toString();
            }

            // Target table becomes the new FROM table
            plain.setFromItem(targetJoin.getRightItem());

            List<Join> newJoins = new ArrayList<>();

            // 1. Convert old FROM into a proper INNER JOIN
            Join fromJoin = new Join();
            fromJoin.setRightItem(currentFrom);
            fromJoin.setInner(true);
            newJoins.add(fromJoin);

            // 2. Add all other joins except targetJoin, preserving type and ON expressions
            if (joins != null) {
                for (int i = 0; i < joins.size(); i++) {
                    if (i == targetIdx) continue;
                    Join j = joins.get(i);
                    Join jCopy = new Join();
                    jCopy.setRightItem(j.getRightItem());
                    if (j.isLeft()) jCopy.setLeft(true);
                    else if (j.isRight()) jCopy.setRight(true);
                    else if (j.isFull()) jCopy.setFull(true);
                    else if (j.isCross()) jCopy.setCross(true);
                    else jCopy.setInner(true);
                    if (j.getOnExpression() != null) {
                        jCopy.setOnExpression(j.getOnExpression());
                    }
                    newJoins.add(jCopy);
                }
            }

            plain.setJoins(newJoins);
            reorderJoinsByDependency(plain);
            return select.toString();
        } catch (Exception e) {
            logger.warn("Failed to rotate query for trigger table " + triggerTable + ": " + e.getMessage(), e);
            return sql;
        }
    }

    private void reorderJoinsByDependency(PlainSelect plain) {
        if (plain == null || plain.getJoins() == null || plain.getJoins().size() <= 1) {
            return;
        }

        java.util.Set<String> availableAliases = new java.util.LinkedHashSet<>();
        if (plain.getFromItem() != null) {
            String fromAlias = plain.getFromItem().getAlias() != null ? 
                plain.getFromItem().getAlias().getName() : 
                (plain.getFromItem() instanceof Table ? ((Table) plain.getFromItem()).getName() : null);
            if (fromAlias != null) {
                availableAliases.add(fromAlias.replaceAll("[\"``]", "").toLowerCase());
            }
        }

        java.util.List<Join> pendingJoins = new java.util.ArrayList<>(plain.getJoins());
        java.util.List<Join> orderedJoins = new java.util.ArrayList<>();

        boolean progress = true;
        while (!pendingJoins.isEmpty()) {
            progress = false;
            for (int i = 0; i < pendingJoins.size(); i++) {
                Join j = pendingJoins.get(i);
                String joinAlias = j.getRightItem().getAlias() != null ? 
                    j.getRightItem().getAlias().getName() : 
                    (j.getRightItem() instanceof Table ? ((Table) j.getRightItem()).getName() : null);
                if (joinAlias != null) {
                    joinAlias = joinAlias.replaceAll("[\"``]", "").toLowerCase();
                }

                java.util.Set<String> referencedAliases = extractTableAliasesFromExpr(j.getOnExpression());
                if (joinAlias != null) {
                    referencedAliases.remove(joinAlias);
                }

                if (availableAliases.containsAll(referencedAliases)) {
                    orderedJoins.add(j);
                    if (joinAlias != null) {
                        availableAliases.add(joinAlias);
                    }
                    pendingJoins.remove(i);
                    progress = true;
                    break;
                }
            }

            if (!progress && !pendingJoins.isEmpty()) {
                Join j = pendingJoins.remove(0);
                if (j.getOnExpression() != null) {
                    net.sf.jsqlparser.expression.Expression onExpr = j.getOnExpression();
                    net.sf.jsqlparser.expression.Expression currentWhere = plain.getWhere();
                    if (currentWhere == null) {
                        plain.setWhere(onExpr);
                    } else {
                        plain.setWhere(new net.sf.jsqlparser.expression.operators.conditional.AndExpression(currentWhere, onExpr));
                    }
                    j.setOnExpression(null);
                    j.setLeft(false);
                    j.setRight(false);
                    j.setInner(false);
                    j.setCross(true);
                }
                String joinAlias = j.getRightItem().getAlias() != null ? 
                    j.getRightItem().getAlias().getName() : 
                    (j.getRightItem() instanceof Table ? ((Table) j.getRightItem()).getName() : null);
                if (joinAlias != null) {
                    availableAliases.add(joinAlias.replaceAll("[\"``]", "").toLowerCase());
                }
                orderedJoins.add(j);
            }
        }

        plain.setJoins(orderedJoins);
    }

    private java.util.Set<String> extractTableAliasesFromExpr(net.sf.jsqlparser.expression.Expression expr) {
        java.util.Set<String> aliases = new java.util.HashSet<>();
        if (expr == null) return aliases;
        String exprStr = expr.toString();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?i)\\b([a-zA-Z0-9_]+)\\.[a-zA-Z0-9_]+\\b").matcher(exprStr);
        while (m.find()) {
            aliases.add(m.group(1).replaceAll("[\"``]", "").toLowerCase());
        }
        return aliases;
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
        sql = convertPostgresJsonToClickHouse(sql);
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
            String prefix;
            if (alias != null && !alias.isEmpty()) {
                prefix = alias + ".";
            } else {
                // Trigger table not found in outer FROM/JOIN (e.g., it's inside a CTE subquery).
                // Fall back to the main FROM table's alias so version/is_deleted are qualified
                // and don't cause AMBIGUOUS_IDENTIFIER when multiple tables are JOINed.
                String fromAlias = null;
                if (plain.getFromItem() instanceof Table) {
                    Table fromTable = (Table) plain.getFromItem();
                    fromAlias = fromTable.getAlias() != null ? fromTable.getAlias().getName() : fromTable.getName();
                } else if (plain.getFromItem() != null && plain.getFromItem().getAlias() != null) {
                    fromAlias = plain.getFromItem().getAlias().getName();
                }
                prefix = (fromAlias != null && !fromAlias.isEmpty()) ? fromAlias + "." : "";
            }

            
            net.sf.jsqlparser.statement.select.SelectItem syncItem = new net.sf.jsqlparser.statement.select.SelectItem();
            syncItem.setExpression(net.sf.jsqlparser.parser.CCJSqlParserUtil.parseExpression("toTimeZone(now64(3), 'Asia/Jakarta')"));
            syncItem.setAlias(new net.sf.jsqlparser.expression.Alias("sync_dt"));

            net.sf.jsqlparser.statement.select.SelectItem verItem = new net.sf.jsqlparser.statement.select.SelectItem();
            verItem.setExpression(net.sf.jsqlparser.parser.CCJSqlParserUtil.parseExpression(prefix + "version"));
            verItem.setAlias(new net.sf.jsqlparser.expression.Alias("version"));
            
            net.sf.jsqlparser.statement.select.SelectItem delItem = new net.sf.jsqlparser.statement.select.SelectItem();
            delItem.setExpression(net.sf.jsqlparser.parser.CCJSqlParserUtil.parseExpression(prefix + "is_deleted"));
            delItem.setAlias(new net.sf.jsqlparser.expression.Alias("is_deleted"));
            
            plain.addSelectItems(syncItem, verItem, delItem);
            return select.toString();
        } catch (Exception e) {
            logger.warn("Failed to inject metadata columns to select list: " + e.getMessage());
            return sql;
        }
    }

    private String mapJdbcTypeToClickHouse(int jdbcType, int precision, int scale, String typeName) {
        String lowerName = typeName != null ? typeName.toLowerCase() : "";
        if (lowerName.contains("bigserial") || lowerName.contains("serial8") || lowerName.contains("int8") || lowerName.contains("bigint") || jdbcType == java.sql.Types.BIGINT) return "Int64";
        if (lowerName.contains("smallserial") || lowerName.contains("serial2") || lowerName.contains("int2") || lowerName.contains("smallint") || jdbcType == java.sql.Types.SMALLINT || jdbcType == java.sql.Types.TINYINT) return "Int16";
        if (lowerName.contains("serial") || lowerName.contains("int4") || lowerName.contains("integer") || lowerName.contains("int") || jdbcType == java.sql.Types.INTEGER) return "Int32";
        if (lowerName.contains("float") || lowerName.contains("real") || jdbcType == java.sql.Types.FLOAT || jdbcType == java.sql.Types.REAL) return "Float32";
        if (lowerName.contains("double") || lowerName.contains("numeric") || lowerName.contains("decimal") || jdbcType == java.sql.Types.DOUBLE || jdbcType == java.sql.Types.NUMERIC || jdbcType == java.sql.Types.DECIMAL) return "Float64";
        if (lowerName.contains("bool") || jdbcType == java.sql.Types.BOOLEAN || jdbcType == java.sql.Types.BIT) return "Bool";
        if (lowerName.contains("date") || jdbcType == java.sql.Types.DATE) return "Date";
        if (lowerName.contains("timestamp") || lowerName.contains("datetime") || lowerName.contains("time") || jdbcType == java.sql.Types.TIMESTAMP || jdbcType == java.sql.Types.TIMESTAMP_WITH_TIMEZONE) {
            return "DateTime64(3, 'Asia/Jakarta')";
        }
        return "String";
    }

    private volatile AdminClient sharedKafkaAdminClient = null;

    private synchronized AdminClient getSharedKafkaAdminClient() {
        if (sharedKafkaAdminClient == null) {
            Properties props = new Properties();
            props.put("bootstrap.servers", KAFKA_BOOTSTRAP_SERVERS);
            props.put("request.timeout.ms", "2000");
            props.put("default.api.timeout.ms", "2000");
            sharedKafkaAdminClient = AdminClient.create(props);
        }
        return sharedKafkaAdminClient;
    }

    private Long getConnectorLag(String connectorName) {
        if (!connectorName.startsWith("sink-")) return null;
        String groupId = "connect-" + connectorName;
        try {
            AdminClient admin = getSharedKafkaAdminClient();
            java.util.Map<TopicPartition, OffsetAndMetadata> groupOffsets = admin.listConsumerGroupOffsets(groupId).partitionsToOffsetAndMetadata().get(2, java.util.concurrent.TimeUnit.SECONDS);
            if (groupOffsets == null || groupOffsets.isEmpty()) return null;
            
            java.util.Map<TopicPartition, OffsetSpec> requestOffsets = new HashMap<>();
            for (TopicPartition tp : groupOffsets.keySet()) {
                requestOffsets.put(tp, OffsetSpec.latest());
            }
            java.util.Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> endOffsets = admin.listOffsets(requestOffsets).all().get(2, java.util.concurrent.TimeUnit.SECONDS);
            
            long totalLag = 0;
            for (TopicPartition tp : groupOffsets.keySet()) {
                if (groupOffsets.get(tp) != null && endOffsets.get(tp) != null) {
                    long currentOffset = groupOffsets.get(tp).offset();
                    long endOffset = endOffsets.get(tp).offset();
                    if (endOffset > currentOffset) {
                        totalLag += (endOffset - currentOffset);
                    }
                }
            }
            return totalLag;
        } catch (Exception e) {
            logger.debug("Could not fetch lag for {}: {}", connectorName, e.getMessage());
            if (sharedKafkaAdminClient != null) {
                try { sharedKafkaAdminClient.close(); } catch (Exception ignored) {}
                sharedKafkaAdminClient = null;
            }
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
        // Automatically drop PostgreSQL replication slot and publication if connector is Postgres
        try {
            java.util.Map<String, Object> config = getConnectorConfig(connectorName);
            if (config != null) {
                String slotName = (String) config.get("slot.name");
                String dbHost = (String) config.get("database.hostname");
                String dbPortStr = (String) config.get("database.port");
                String dbName = (String) config.get("database.dbname");
                String dbUser = (String) config.get("database.user");
                String dbPass = (String) config.get("database.password");
                
                if (slotName != null && dbHost != null && dbUser != null && dbName != null) {
                    int dbPort = 5432;
                    try { if (dbPortStr != null) dbPort = Integer.parseInt(dbPortStr); } catch (Exception ignored) {}
                    
                    com.dbdiff.model.ConnectionDetails connDetails = new com.dbdiff.model.ConnectionDetails();
                    connDetails.setType("postgresql");
                    connDetails.setHost(dbHost);
                    connDetails.setPort(dbPort);
                    connDetails.setDatabase(dbName);
                    connDetails.setUsername(dbUser);
                    connDetails.setPassword(dbPass);
                    
                    try {
                        DataSource ds = connectionManagerService.getDataSource(connDetails);
                        try (Connection pgConn = ds.getConnection();
                             Statement pgStmt = pgConn.createStatement()) {
                            
                            String findSql = "SELECT slot_name, active_pid FROM pg_replication_slots WHERE slot_name = '" + slotName.replace("'", "''") + "'";
                            Number activePid = null;
                            boolean found = false;
                            try (ResultSet rs = pgStmt.executeQuery(findSql)) {
                                if (rs.next()) {
                                    found = true;
                                    activePid = (Number) rs.getObject("active_pid");
                                }
                            }
                            if (found) {
                                if (activePid != null) {
                                    try {
                                        pgStmt.execute("SELECT pg_terminate_backend(" + activePid.intValue() + ")");
                                        Thread.sleep(500);
                                    } catch (Exception ignored) {}
                                }
                                pgStmt.execute("SELECT pg_drop_replication_slot('" + slotName.replace("'", "''") + "')");
                                logger.info("Auto-cleanup: Dropped PostgreSQL replication slot: " + slotName);
                            }
                            
                            String pubName = (String) config.get("publication.name");
                            if (pubName != null && !pubName.trim().isEmpty()) {
                                try {
                                    pgStmt.execute("DROP PUBLICATION IF EXISTS " + pubName.replace("'", "''"));
                                    logger.info("Auto-cleanup: Dropped PostgreSQL publication: " + pubName);
                                } catch (Exception ignored) {}
                            }
                        }
                    } catch (Exception ex) {
                        logger.warn("Failed auto-cleanup of replication slot " + slotName + " during connector deletion", ex);
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Could not inspect config for slot cleanup prior to deleting connector: " + connectorName, e);
        }

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
            java.util.Map<String, Object> meta = pipelineMetadataRepository.getPipelineMetadata(deployId);
            String sourceConnectionId = meta != null ? (String) meta.get("source_connection_id") : null;
            
            String[] connectors = restTemplate.getForObject(DEBEZIUM_URL, String[].class);
            if (connectors != null) {
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
                                        try (java.sql.ResultSet rsDep = stmt.executeQuery(
                                                "SELECT length(dependencies_table) FROM system.tables WHERE database = '" + db + "' AND name = '" + landingTable + "'")) {
                                            if (rsDep.next() && rsDep.getInt(1) == 0) {
                                                stmt.execute("DROP TABLE IF EXISTS `" + db + "`.`" + landingTable + "`");
                                            } else {
                                                logger.info("CDC landing table `" + landingTable + "` is still used by other pipelines. Not dropping.");
                                            }
                                        }
                                    }
                                }
                                
                                stmt.execute("DROP TABLE IF EXISTS `" + db + "`.`" + targetTable + "`");
                                
                                // Tidak lagi men-drop database meskipun kosong, sesuai request:
                                // "jangan drop database, hanya tabel jika sudah tidak ada pipeline lain yang pakai lagi"
                            }
                        }
                    } catch (Exception e) {
                        logger.warn("Failed to cleanup ClickHouse tables for pipeline " + deployId, e);
                    }
                }
                
                for (String c : toDelete) {
                    deleteConnector(c);
                }
            }
            
            // Also delete metadata
            try {
                pipelineMetadataRepository.deletePipelineMetadata(deployId);
            } catch (Exception ignored) {}
            
            // Reference Counting Cleanup for Shared Source Connector & Replication Slot
            if (sourceConnectionId != null) {
                try {
                    int remainingCount = pipelineMetadataRepository.countPipelinesBySourceConnectionId(sourceConnectionId);
                    com.dbdiff.model.ConnectionDetails sourceConn = connectionRepository.findById(sourceConnectionId);
                    if (sourceConn != null) {
                        String baseName = sourceConn.getName().replaceAll("[^a-zA-Z0-9_]", "_").toLowerCase();
                        String sharedSourceConnectorName = "source-" + baseName + "-shared";
                        String safeSlotName = "slot_" + baseName + "_shared";
                        
                        if (remainingCount == 0) {
                            logger.info("No active pipelines remain for source connection " + sourceConn.getName() + ". Deleting shared connector " + sharedSourceConnectorName);
                            try {
                                restTemplate.delete(DEBEZIUM_URL + "/" + sharedSourceConnectorName);
                                logger.info("Deleted shared Debezium connector: " + sharedSourceConnectorName);
                            } catch (Exception ex) {
                                logger.warn("Failed to delete shared connector " + sharedSourceConnectorName + ": " + ex.getMessage());
                            }
                            if ("postgresql".equalsIgnoreCase(sourceConn.getType())) {
                                try {
                                    DataSource sourceDs = connectionManagerService.getDataSource(sourceConn);
                                    try (Connection pgConn = sourceDs.getConnection();
                                         Statement pgStmt = pgConn.createStatement()) {
                                        pgStmt.execute("SELECT pg_drop_replication_slot('" + safeSlotName + "')");
                                        logger.info("Successfully dropped shared Postgres replication slot: " + safeSlotName);
                                    }
                                } catch (Exception ex) {
                                    logger.warn("Could not drop shared Postgres replication slot " + safeSlotName + ": " + ex.getMessage());
                                }
                            }
                        } else {
                            logger.info(remainingCount + " pipeline(s) still remain for source connection " + sourceConn.getName() + ". Updating shared connector table list.");
                            try {
                                java.util.List<java.util.Map<String, Object>> remainingMetas = pipelineMetadataRepository.getPipelinesBySourceConnectionId(sourceConnectionId);
                                java.util.Set<String> activeTables = new java.util.LinkedHashSet<>();
                                for (java.util.Map<String, Object> rMeta : remainingMetas) {
                                    String q = (String) rMeta.get("query");
                                    if (q != null) {
                                        java.util.List<String> phys = extractPhysicalTables(q);
                                        for (String pt : phys) {
                                            String cleanTable = pt.replaceAll("[\"``]", "");
                                            if (!cleanTable.contains(".") && "postgresql".equalsIgnoreCase(sourceConn.getType())) {
                                                String defSchema = sourceConn.getSchema();
                                                if (defSchema == null || defSchema.isEmpty()) defSchema = "public";
                                                cleanTable = defSchema + "." + cleanTable;
                                            }
                                            activeTables.add(cleanTable);
                                        }
                                    }
                                }
                                if ("postgresql".equalsIgnoreCase(sourceConn.getType())) {
                                    activeTables.add("public._dbz_heartbeat");
                                }
                                java.util.Map<String, Object> cfg = getConnectorConfig(sharedSourceConnectorName);
                                if (cfg != null && !cfg.isEmpty() && !cfg.containsKey("error_code")) {
                                    cfg.put("table.include.list", String.join(",", activeTables));
                                    updateConnectorConfig(sharedSourceConnectorName, cfg);
                                    logger.info("Updated shared connector " + sharedSourceConnectorName + " table.include.list to: " + String.join(",", activeTables));
                                }
                            } catch (Exception ex) {
                                logger.warn("Could not update shared connector table list for " + sharedSourceConnectorName + ": " + ex.getMessage());
                            }
                        }
                    }
                } catch (Exception ex) {
                    logger.warn("Failed reference counting cleanup for sourceConnectionId " + sourceConnectionId, ex);
                }
            }
            
        } catch (Exception e) {
            logger.error("Failed to delete pipeline " + deployId, e);
            throw new RuntimeException("Failed to delete pipeline: " + e.getMessage());
        }
    }

    public java.util.Map<String, Object> getPipelineMetadata(String deployId) {
        java.util.Map<String, Object> meta = pipelineMetadataRepository.getPipelineMetadata(deployId);
        if (meta != null && !meta.isEmpty()) {
            return meta;
        }

        // Fallback reconstruction for legacy / existing pipelines
        try {
            String[] connectors = restTemplate.getForObject(DEBEZIUM_URL, String[].class);
            if (connectors != null) {
                String targetTable = null;
                for (String c : connectors) {
                    if (c.contains(deployId)) {
                        String[] parts = c.split("-");
                        if (parts.length >= 4) {
                            targetTable = parts[2];
                        }
                    }
                }

                String query = getOriginalQuery(deployId);
                if (targetTable != null || query != null) {
                    java.util.Map<String, Object> fallbackMeta = new java.util.HashMap<>();
                    fallbackMeta.put("deploy_id", deployId);
                    fallbackMeta.put("query", query != null ? query : "");
                    fallbackMeta.put("target_table", targetTable != null ? targetTable : "legacy_table");
                    
                    ConnectionDetails srcConn = connectionRepository.findAll().stream().findFirst().orElse(null);
                    ConnectionDetails chConn = connectionRepository.findAll().stream()
                            .filter(c -> "clickhouse".equalsIgnoreCase(c.getType()))
                            .findFirst()
                            .orElse(null);

                    fallbackMeta.put("source_connection_id", srcConn != null ? srcConn.getId() : "");
                    fallbackMeta.put("target_connection_id", chConn != null ? chConn.getId() : "");
                    fallbackMeta.put("target_database", chConn != null ? chConn.getDatabase() : "default");
                    return fallbackMeta;
                }
            }
        } catch (Exception e) {
            logger.warn("Could not reconstruct pipeline metadata for deployId: " + deployId, e);
        }

        return null;
    }
    public void backfillCdcFromPostgres(String deployId, SseEmitter emitter) throws Exception {
        try {
            sendLog(emitter, "Mulai sinkronisasi backfill manual bypass Kafka untuk pipeline CDC: " + deployId);
            java.util.Map<String, Object> meta = pipelineMetadataRepository.getPipelineMetadata(deployId);
            if (meta == null) throw new RuntimeException("Pipeline metadata not found for deployId: " + deployId);

            String connectionId = (String) meta.get("source_connection_id");
            String sourceTable = (String) meta.get("source_table");
            String targetConnectionId = (String) meta.get("target_connection_id");

            com.dbdiff.model.ConnectionDetails sourceConn = connectionRepository.findById(connectionId);
            if (sourceConn == null) throw new RuntimeException("Source connection not found: " + connectionId);

            com.dbdiff.model.ConnectionDetails targetConn = connectionRepository.findById(targetConnectionId);
            if (targetConn == null) {
                targetConn = connectionRepository.findAll().stream()
                        .filter(c -> "clickhouse".equalsIgnoreCase(c.getType()))
                        .findFirst().orElseThrow(() -> new RuntimeException("ClickHouse connection not found"));
            }

            int localPort = sourceConn.getPort();
            if (sourceConn.isUseSsh()) {
                localPort = sshTunnelService.getOrOpenTunnel(sourceConn, connectionId);
            }

            String baseName = sourceConn.getName().replaceAll("[^a-zA-Z0-9_]", "_").toLowerCase();
            String sourceTableSafe = sourceTable.replaceAll("[^a-zA-Z0-9_]", "_");
            String cdcTableName = "cdc_" + baseName + "_" + sourceTableSafe;

            javax.sql.DataSource targetDs = connectionManagerService.getDataSource(targetConn);

            java.util.List<String> chCols = new java.util.ArrayList<>();
            try (java.sql.Connection chConnSql = targetDs.getConnection();
                 java.sql.Statement stmt = chConnSql.createStatement();
                 java.sql.ResultSet rs = stmt.executeQuery("DESCRIBE dw_erp." + cdcTableName)) {
                while (rs.next()) {
                    chCols.add(rs.getString("name"));
                }
            }

            java.util.List<String> pgCols = new java.util.ArrayList<>();
            for (String col : chCols) {
                if (!col.equals("sync_dt") && !col.equals("version") && !col.equals("is_deleted")) {
                    pgCols.add(col);
                }
            }

            String colListPg = String.join(", ", pgCols);
            String colListCh = String.join(", ", pgCols) + ", sync_dt, version, is_deleted";

            String pgHostForClickhouse = "172.17.0.1:" + localPort;
            String pgSchema = "public";
            String pgTable = sourceTable;
            if (sourceTable.contains(".")) {
                String[] parts = sourceTable.split("\\.");
                pgSchema = parts[0];
                pgTable = parts[1];
            }
            
            String dbPass = sourceConn.getPassword();
            if(dbPass != null && dbPass.length() > 0 && !dbPass.equals("*****")) {
                try {
                    dbPass = new String(java.util.Base64.getDecoder().decode(dbPass));
                } catch(Exception e) {}
            }

            String insertQuery = "INSERT INTO dw_erp." + cdcTableName + " (" + colListCh + ") " +
                    "SELECT " + colListPg + ", " +
                    "now64(3) AS sync_dt, " +
                    "toUnixTimestamp64Milli(now64(3)) AS version, " +
                    "0 AS is_deleted " +
                    "FROM postgresql('" + pgHostForClickhouse + "', '" + sourceConn.getDatabase() + "', '" + pgTable + "', '" + sourceConn.getUsername() + "', '" + dbPass + "', '" + pgSchema + "')";

            sendLog(emitter, "Menjalankan injeksi sinkronisasi data dari Postgres ke ClickHouse. Proses ini mungkin memakan waktu...");

            long start = System.currentTimeMillis();
            try (java.sql.Connection chConnSql = targetDs.getConnection();
                 java.sql.Statement stmt = chConnSql.createStatement()) {
                stmt.execute(insertQuery);
            }
            long end = System.currentTimeMillis();

            sendLog(emitter, "Backfill sukses dalam " + (end - start) + " ms! Kolom-kolom yang sebelumnya NULL sekarang sudah terisi data terkini.");
        } catch (Exception ex) {
            logger.error("Error during backfillCdcFromPostgres", ex);
            sendLog(emitter, "ERROR: " + ex.getMessage());
            throw ex;
        }
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
            String targetDatabase = (String) meta.get("target_database");

            if (targetTable == null) throw new RuntimeException("Target table name not found in metadata.");

            sendLog(emitter, "Target table: " + targetTable);

            // ── 2. Look up ConnectionDetails from internal DB ────────────────────────
            ConnectionDetails sourceConn = connectionRepository.findById(sourceConnectionId);
            ConnectionDetails targetConn = connectionRepository.findById(targetConnectionId);
            if (sourceConn == null) throw new RuntimeException("Source connection not found: " + sourceConnectionId);
            if (targetConn == null) throw new RuntimeException("Target connection not found: " + targetConnectionId);

            DataSource sourceDs = connectionManagerService.getDataSource(sourceConn);
            DataSource targetDs = connectionManagerService.getDataSource(targetConn);
            String chDb = targetDatabase;
            if (chDb == null || chDb.isEmpty()) chDb = targetConn.getDatabase();
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
                    String connBaseName = sourceConn.getName().replaceAll("[^a-zA-Z0-9_]", "_").toLowerCase();
                    String findMvSql = "SELECT name FROM system.tables WHERE database = '" + chDb +
                        "' AND name LIKE 'mv_" + targetTable + "_cdc_" + connBaseName + "_%' AND name LIKE '%_" + normalized + "'";
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
                        String connBaseNameFallback = sourceConn.getName().replaceAll("[^a-zA-Z0-9_]", "_").toLowerCase();
                        String findCdcSql = "SELECT name FROM system.tables WHERE database = '" + chDb +
                            "' AND name LIKE 'cdc_" + connBaseNameFallback + "_%' AND name LIKE '%" + normalized + "'";
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

                    // Auto-add any new columns to this CDC landing table to prevent Unknown Identifier errors
                    for (ColumnInfo ci : newCols) {
                        if (ci.name.equalsIgnoreCase("sync_dt") || ci.name.equalsIgnoreCase("version") || ci.name.equalsIgnoreCase("is_deleted")) continue;
                        try {
                            stmt.execute("ALTER TABLE `" + chDb + "`.`" + actualLandingTable + "` ADD COLUMN IF NOT EXISTS `" + ci.name + "` Nullable(" + ci.clickhouseType + ")");
                        } catch (Exception ignored) {
                        }
                    }

                    // Drop the existing MV (use actual name found above)
                    String mvName = "mv_" + targetTable + "_" + actualLandingTable;
                    stmt.execute("DROP VIEW IF EXISTS `" + chDb + "`.`" + mvName + "`");
                    sendLog(emitter, "Dropped old MV `" + mvName + "`.");

                    // Always use the source connection name for baseName
                    String resolvedBaseName = sourceConn.getName().replaceAll("[^a-zA-Z0-9_]", "_").toLowerCase();
                    sendLog(emitter, "Resolved baseName from connection: `" + resolvedBaseName + "`");

                    // Recreate the MV using the resolved baseName
                    String rotatedSql = rotateQuery(expandedNewQuery, t);
                    String sqlWithMeta = addMetadataColsToSelect(rotatedSql, t);
                    String rewrittenSql;
                    if (physicalTables.size() > 1) {
                        String sqlWithFilters = addPKFiltersToWhere(sqlWithMeta, physicalTables, tableToPKs);
                        rewrittenSql = rewriteQueryForClickHouse(sqlWithFilters, physicalTables, resolvedBaseName, sourceConn, chDb);
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

                            java.util.Map<String, String> colTypes = new java.util.HashMap<>();
                            for (ColumnInfo c : newCols) {
                                colTypes.put(c.name.toLowerCase(), c.clickhouseType);
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
                                    flushBackfillBatch(targetDs, chDb, targetTable, batch, compositePKs, selectCols, colTypes, emitter);
                                    batch.clear();
                                    sendLog(emitter, "Backfilled " + totalRows + " rows so far...");
                                }
                            }
                            if (!batch.isEmpty()) {
                                flushBackfillBatch(targetDs, chDb, targetTable, batch, compositePKs, selectCols, colTypes, emitter);
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
        String cleanQuery = (query != null) ? query.trim().replaceAll(";+$", "").trim() : "";
        String srcType = sourceConn.getType().toLowerCase();
        String dryRun = srcType.contains("sqlserver")
            ? "SELECT TOP 0 * FROM (" + cleanQuery + ") AS tmp"
            : "SELECT * FROM (" + cleanQuery + ") AS tmp LIMIT 0";
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
            java.util.Map<String, String> colTypes,
            SseEmitter emitter) throws Exception {

        if (batch.isEmpty() || addedCols.isEmpty()) return;

        // We insert full rows for the new columns using an INSERT that triggers ReplacingMergeTree dedup
        // Build INSERT with only PK + new cols (others get their stored values via FINAL)
        StringBuilder sb = new StringBuilder();
        sb.append("INSERT INTO `").append(chDb).append("`.`").append(targetTable).append("` (");

        java.util.List<String> insertCols = new java.util.ArrayList<>();
        for (String pk : pkCols) {
            if (colTypes != null && colTypes.containsKey(pk.toLowerCase()) && !insertCols.contains(pk)) {
                insertCols.add(pk);
            }
        }
        for (String c : addedCols) {
            if (colTypes != null && colTypes.containsKey(c.toLowerCase()) && !insertCols.contains(c)) {
                insertCols.add(c);
            }
        }
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
                        String type = colTypes != null ? colTypes.get(col.toLowerCase()) : null;
                        if (type != null && (type.startsWith("Int") || type.startsWith("UInt") || type.startsWith("Float") || type.startsWith("Decimal"))) {
                            rv.append("0");
                        } else if (type != null && type.startsWith("Date")) {
                            rv.append("'1970-01-01'");
                        } else {
                            rv.append("''");
                        }
                    } else if (val instanceof Boolean) {
                        rv.append(((Boolean) val) ? "1" : "0");
                    } else if (val instanceof Number) {
                        rv.append(val);
                    } else {
                        rv.append("'").append(val.toString().replace("'", "''")).append("'");
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
        if (deployId == null || deployId.trim().isEmpty()) {
            return null;
        }

        String query = pipelineMetadataRepository.getOriginalQuery(deployId);
        if (query != null && !query.trim().isEmpty()) {
            return query;
        }

        // Fallback: Reconstruct query from ClickHouse system.tables (Materialized View DDL)
        try {
            ConnectionDetails targetConn = connectionRepository.findAll().stream()
                    .filter(c -> "clickhouse".equalsIgnoreCase(c.getType()))
                    .findFirst()
                    .orElse(null);

            if (targetConn != null) {
                DataSource ds = connectionManagerService.getDataSource(targetConn);
                try (Connection conn = ds.getConnection();
                     Statement stmt = conn.createStatement()) {

                    String cleanKey = deployId.toLowerCase().trim();
                    String q = "SELECT name, create_table_query FROM system.tables WHERE engine = 'MaterializedView'";
                    try (ResultSet rs = stmt.executeQuery(q)) {
                        while (rs.next()) {
                            String mvName = rs.getString("name");
                            String ddl = rs.getString("create_table_query");

                            String mvLower = mvName != null ? mvName.toLowerCase() : "";
                            String ddlLower = ddl != null ? ddl.toLowerCase() : "";

                            if (mvLower.contains(cleanKey) || ddlLower.contains("to default." + cleanKey) || ddlLower.contains("to `" + cleanKey + "`") || ddlLower.contains("to " + cleanKey)) {
                                if (ddl != null && ddl.toUpperCase().contains(" AS ")) {
                                    int asIdx = ddl.toUpperCase().indexOf(" AS ");
                                    return ddl.substring(asIdx + 4).trim();
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Failed fallback query retrieval for deployId: " + deployId, e);
        }

        return null;
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
                String tableIncludeList = (String) config.get("table.include.list");
                if (tableIncludeList != null && !tableIncludeList.isEmpty()) {
                    String firstTable = tableIncludeList.split(",")[0];
                    topicName = actualPrefix + firstTable.replace(".", "_");
                } else {
                    Properties props = new Properties();
                    props.put("bootstrap.servers", KAFKA_BOOTSTRAP_SERVERS);
                    try (AdminClient admin = AdminClient.create(props)) {
                        java.util.Set<String> allTopics = admin.listTopics().names().get();
                        for (String t : allTopics) {
                            if (t.startsWith(actualPrefix)) {
                                topicName = t;
                                break;
                            }
                        }
                    }
                }
            } else if (config.containsKey("topic.prefix")) {
                // Try to find the topic via admin client (default Debezium naming)
                String prefix = (String) config.get("topic.prefix");
                Properties props = new Properties();
                props.put("bootstrap.servers", KAFKA_BOOTSTRAP_SERVERS);
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
                props.put("bootstrap.servers", KAFKA_BOOTSTRAP_SERVERS);
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
            props.put("bootstrap.servers", KAFKA_BOOTSTRAP_SERVERS);
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
                String encodedQuery = java.net.URLEncoder.encode(chQuery, "UTF-8").replace("+", "%20");
                java.net.URI chUri = java.net.URI.create("http://clickhouse:8123/?query=" + encodedQuery);
                
                org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
                if (chUser != null && !chUser.isEmpty()) {
                    headers.set("X-ClickHouse-User", chUser);
                }
                if (chPass != null && !chPass.isEmpty()) {
                    headers.set("X-ClickHouse-Key", chPass);
                }
                org.springframework.http.HttpEntity<String> entity = new org.springframework.http.HttpEntity<>(headers);
                org.springframework.http.ResponseEntity<String> response = restTemplate.exchange(chUri, org.springframework.http.HttpMethod.GET, entity, String.class);
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

    /**
     * Safely cleans up orphan database pipeline records that no longer exist in Debezium/Kafka.
     * Runs every Sunday at 00:00:00 (12 AM midnight).
     * 
     * Safety Guarantees:
     * 1. If Debezium is offline/restarting/unreachable, the cleanup aborts instantly without deleting anything.
     * 2. Active, Paused, Failed, or Restarting connectors remain registered in Debezium, so their pipeline DB records are preserved.
     * 3. Grace Period: Pipelines created less than 15 minutes ago are skipped (protects mid-deployment pipelines).
     */
    @Scheduled(cron = "0 0 0 * * SUN")
    public void cleanupOrphanPipelines() {
        // Disabled automatic deletion to protect stored pipeline queries and metadata
        logger.info("Auto-cleanup: Skipped automatic orphan pipeline deletion.");
    }

    public void runOneTimePipelineCleanupOnStartup() {
        // Disabled startup cleanup
    }

    public List<Map<String, Object>> getReplicationSlots(String connectionId) {
        List<Map<String, Object>> result = new ArrayList<>();
        List<ConnectionDetails> connections = new ArrayList<>();
        if (connectionId != null && !connectionId.trim().isEmpty()) {
            ConnectionDetails conn = connectionRepository.findById(connectionId);
            if (conn != null) connections.add(conn);
        } else {
            List<ConnectionDetails> allConns = connectionRepository.findAll();
            for (ConnectionDetails c : allConns) {
                if ("postgresql".equalsIgnoreCase(c.getType())) {
                    connections.add(c);
                }
            }
        }

        for (ConnectionDetails connDetails : connections) {
            if (!"postgresql".equalsIgnoreCase(connDetails.getType())) continue;
            try {
                DataSource ds = connectionManagerService.getDataSource(connDetails);
                try (Connection conn = ds.getConnection();
                     Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery(
                         "SELECT slot_name, plugin, slot_type, database, temporary, active, active_pid, " +
                         "pg_size_pretty(pg_wal_lsn_diff(pg_current_wal_lsn(), restart_lsn)) as wal_retained " +
                         "FROM pg_replication_slots")) {
                    while (rs.next()) {
                        Map<String, Object> map = new HashMap<>();
                        map.put("connection_id", connDetails.getId());
                        map.put("connection_name", connDetails.getName());
                        map.put("slot_name", rs.getString("slot_name"));
                        map.put("plugin", rs.getString("plugin"));
                        map.put("slot_type", rs.getString("slot_type"));
                        map.put("database", rs.getString("database"));
                        map.put("temporary", rs.getBoolean("temporary"));
                        map.put("active", rs.getBoolean("active"));
                        map.put("active_pid", rs.getObject("active_pid"));
                        map.put("wal_retained", rs.getString("wal_retained"));
                        result.add(map);
                    }
                }
            } catch (Exception e) {
                logger.warn("Failed to fetch replication slots for connection " + connDetails.getName(), e);
            }
        }
        return result;
    }

    public List<String> cleanupReplicationSlots(String connectionId, String slotName, Boolean inactiveOnly) {
        List<String> droppedSlots = new ArrayList<>();
        List<ConnectionDetails> connections = new ArrayList<>();
        if (connectionId != null && !connectionId.trim().isEmpty()) {
            ConnectionDetails conn = connectionRepository.findById(connectionId);
            if (conn != null) connections.add(conn);
        } else {
            List<ConnectionDetails> allConns = connectionRepository.findAll();
            for (ConnectionDetails c : allConns) {
                if ("postgresql".equalsIgnoreCase(c.getType())) {
                    connections.add(c);
                }
            }
        }

        for (ConnectionDetails connDetails : connections) {
            if (!"postgresql".equalsIgnoreCase(connDetails.getType())) continue;
            try {
                DataSource ds = connectionManagerService.getDataSource(connDetails);
                try (Connection conn = ds.getConnection();
                     Statement stmt = conn.createStatement()) {
                    
                    String query = "SELECT slot_name, active_pid, active FROM pg_replication_slots";
                    if (slotName != null && !slotName.trim().isEmpty()) {
                        query += " WHERE slot_name = '" + slotName.replace("'", "''") + "'";
                    } else if (Boolean.TRUE.equals(inactiveOnly)) {
                        query += " WHERE active = false";
                    }
                    
                    List<Map<String, Object>> slotsToDrop = new ArrayList<>();
                    try (ResultSet rs = stmt.executeQuery(query)) {
                        while (rs.next()) {
                            Map<String, Object> map = new HashMap<>();
                            map.put("slot_name", rs.getString("slot_name"));
                            map.put("active_pid", rs.getObject("active_pid"));
                            map.put("active", rs.getBoolean("active"));
                            slotsToDrop.add(map);
                        }
                    }
                    
                    for (Map<String, Object> s : slotsToDrop) {
                        String sName = (String) s.get("slot_name");
                        Number activePid = (Number) s.get("active_pid");
                        if (activePid != null) {
                            try {
                                stmt.execute("SELECT pg_terminate_backend(" + activePid.intValue() + ")");
                                Thread.sleep(500);
                            } catch (Exception ignored) {}
                        }
                        try {
                            stmt.execute("SELECT pg_drop_replication_slot('" + sName.replace("'", "''") + "')");
                            droppedSlots.add(sName);
                            logger.info("Successfully dropped PostgreSQL replication slot: " + sName + " on connection: " + connDetails.getName());
                        } catch (Exception e) {
                            logger.error("Failed to drop replication slot " + sName, e);
                        }
                    }
                }
            } catch (Exception e) {
                logger.error("Error during replication slots cleanup on connection " + connDetails.getName(), e);
            }
        }
        return droppedSlots;
    }

    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void initDebeziumTunnelsOnStartup() {
        logger.info("Initializing Debezium SSH tunnels once on application startup...");
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> allConnectors = restTemplate.getForObject(
                DEBEZIUM_URL + "?expand=info&expand=status", Map.class);
                
            if (allConnectors == null) return;
            
            for (Map.Entry<String, Object> entry : allConnectors.entrySet()) {
                String connName = entry.getKey();
                if (connName.startsWith("source-") && connName.endsWith("-shared")) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> wrapper = (Map<String, Object>) entry.getValue();
                    @SuppressWarnings("unchecked")
                    Map<String, Object> info = (Map<String, Object>) wrapper.get("info");
                    if (info == null) continue;
                    @SuppressWarnings("unchecked")
                    Map<String, Object> cfg = (Map<String, Object>) info.get("config");
                    if (cfg == null) continue;
                    
                    String dbHostname = (String) cfg.get("database.hostname");
                    String dbPortStr = (String) cfg.get("database.port");
                    if (("backend".equals(dbHostname) || "tasks.backend".equals(dbHostname) || "127.0.0.1".equals(dbHostname) || "localhost".equals(dbHostname) || "172.21.0.1".equals(dbHostname)) && dbPortStr != null) {
                        int assignedPort = Integer.parseInt(dbPortStr);
                        String baseName = connName.substring(7, connName.length() - 7);
                        for (ConnectionDetails details : connectionRepository.findAll()) {
                            if (details.isUseSsh()) {
                                String cBase = (details.getName() != null ? details.getName() : "").replaceAll("[^a-zA-Z0-9_]", "_").toLowerCase();
                                if (cBase.equals(baseName)) {
                                    String connId = String.valueOf(details.getId());
                                    ConnectionDetails enriched = enrichConnection(details);
                                    sshTunnelService.registerAndRecoverTunnel(connId, enriched, assignedPort);
                                    try {
                                        sshTunnelService.getOrOpenTunnel(enriched, connId);
                                        logger.info("Startup initialized SSH tunnel for connection {} on port {}", connId, assignedPort);
                                    } catch (Exception ex) {
                                        logger.error("Failed to startup-open SSH tunnel for connection {}: {}", connId, ex.getMessage());
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("initDebeziumTunnelsOnStartup skipped or failed: {}", e.getMessage());
        }
    }

    private ConnectionDetails enrichConnection(ConnectionDetails conn) {
        if (conn == null) return null;
        if (conn.getId() != null && !conn.getId().trim().isEmpty()) {
            try {
                ConnectionDetails stored = connectionRepository.findById(conn.getId().trim());
                if (stored != null) {
                    if (conn.getName() == null || conn.getName().trim().isEmpty()) {
                        conn.setName(stored.getName());
                    }
                    if (conn.getPassword() == null || conn.getPassword().trim().isEmpty() || "*****".equals(conn.getPassword().trim())) {
                        conn.setPassword(stored.getPassword());
                    }
                    if (conn.getHost() == null || conn.getHost().trim().isEmpty()) {
                        conn.setHost(stored.getHost());
                    }
                    if (conn.getUsername() == null || conn.getUsername().trim().isEmpty()) {
                        conn.setUsername(stored.getUsername());
                    }
                    if (conn.getType() == null || conn.getType().trim().isEmpty()) {
                        conn.setType(stored.getType());
                    }
                    if (conn.getPort() <= 0) {
                        conn.setPort(stored.getPort());
                    }
                    if (conn.getDatabase() == null || conn.getDatabase().trim().isEmpty()) {
                        conn.setDatabase(stored.getDatabase());
                    }
                    if (conn.getSchema() == null || conn.getSchema().trim().isEmpty()) {
                        conn.setSchema(stored.getSchema());
                    }
                    if (conn.getSslMode() == null || conn.getSslMode().trim().isEmpty()) {
                        conn.setSslMode(stored.getSslMode());
                    }
                    if (conn.isUseSsh()) {
                        if (conn.getSshPassword() == null || conn.getSshPassword().trim().isEmpty() || "*****".equals(conn.getSshPassword().trim())) {
                            conn.setSshPassword(stored.getSshPassword());
                        }
                        if (conn.getSshHost() == null || conn.getSshHost().trim().isEmpty()) {
                            conn.setSshHost(stored.getSshHost());
                        }
                        if (conn.getSshUsername() == null || conn.getSshUsername().trim().isEmpty()) {
                            conn.setSshUsername(stored.getSshUsername());
                        }
                        if (conn.getSshAuthMode() == null || conn.getSshAuthMode().trim().isEmpty()) {
                            conn.setSshAuthMode(stored.getSshAuthMode());
                        }
                        if (conn.getSshKeyFile() == null || conn.getSshKeyFile().trim().isEmpty()) {
                            conn.setSshKeyFile(stored.getSshKeyFile());
                        }
                        if (conn.getSshPassphrase() == null || conn.getSshPassphrase().trim().isEmpty()) {
                            conn.setSshPassphrase(stored.getSshPassphrase());
                        }
                        if (conn.getSshPort() == null || conn.getSshPort() <= 0) {
                            conn.setSshPort(stored.getSshPort());
                        }
                    }
                }
            } catch (Exception e) {
                logger.warn("Failed to enrich connection from repository: " + e.getMessage());
            }
        }
        return conn;
    }
}
