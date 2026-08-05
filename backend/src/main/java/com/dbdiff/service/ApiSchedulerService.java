package com.dbdiff.service;

import com.dbdiff.model.ApiSchedulerConfig;
import com.dbdiff.model.ConnectionDetails;
import com.dbdiff.repository.ApiSchedulerRepository;
import com.dbdiff.repository.ConnectionRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

@Service
public class ApiSchedulerService {

    private static final Logger logger = LoggerFactory.getLogger(ApiSchedulerService.class);

    private final ApiSchedulerRepository repository;
    private final ConnectionRepository connectionRepository;
    private final ConnectionManagerService connectionManagerService;
    private final TaskScheduler taskScheduler;
    private final NotificationService notificationService;

    private final Map<String, List<ScheduledFuture<?>>> scheduledTasks = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .build();

    @Autowired
    public ApiSchedulerService(ApiSchedulerRepository repository,
                               ConnectionRepository connectionRepository,
                               ConnectionManagerService connectionManagerService,
                               TaskScheduler taskScheduler,
                               NotificationService notificationService) {
        this.repository = repository;
        this.connectionRepository = connectionRepository;
        this.connectionManagerService = connectionManagerService;
        this.taskScheduler = taskScheduler;
        this.notificationService = notificationService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initAllSchedulers() {
        logger.info("Initializing API Ingestion Schedulers...");
        List<ApiSchedulerConfig> all = repository.findAll();
        for (ApiSchedulerConfig config : all) {
            if (config.isActive()) {
                refreshSchedule(config.getId());
            }
        }
    }

    public void refreshSchedule(String id) {
        // Cancel existing tasks if scheduled
        List<ScheduledFuture<?>> existingList = scheduledTasks.remove(id);
        if (existingList != null) {
            for (ScheduledFuture<?> future : existingList) {
                if (future != null) {
                    future.cancel(false);
                }
            }
        }

        Optional<ApiSchedulerConfig> opt = repository.findById(id);
        if (opt.isEmpty()) return;
        ApiSchedulerConfig config = opt.get();
        if (!config.isActive() || config.getCronExpression() == null || config.getCronExpression().trim().isEmpty()) {
            return;
        }

        Runnable task = () -> executeAndSaveSchedule(id);

        // Support multiple cron expressions separated by ';', ',', or newlines
        String rawCrons = config.getCronExpression().trim();
        String[] cronArray = rawCrons.split("[;,\\n]+");
        List<ScheduledFuture<?>> futures = new ArrayList<>();

        for (String singleCron : cronArray) {
            String cron = singleCron.trim();
            if (cron.isEmpty()) continue;

            if (cron.startsWith("EVERY_")) {
                cron = convertPresetToSpringCron(cron);
            }

            try {
                ScheduledFuture<?> future = taskScheduler.schedule(task, new CronTrigger(cron));
                futures.add(future);
                logger.info("Successfully scheduled API Ingestion job [{}] with Spring Cron [{}]", config.getName(), cron);
            } catch (Exception e) {
                logger.error("Failed to schedule API Ingestion job [{}] with cron [{}]: {}", config.getName(), cron, e.getMessage());
            }
        }

        if (!futures.isEmpty()) {
            scheduledTasks.put(id, futures);
        } else {
            repository.updateLastRun(id, "FAILED", "Invalid Spring Cron expression(s)");
        }
    }

    private String convertPresetToSpringCron(String preset) {
        switch (preset.toUpperCase()) {
            case "EVERY_1M": return "0 */1 * * * *";
            case "EVERY_5M": return "0 */5 * * * *";
            case "EVERY_15M": return "0 */15 * * * *";
            case "EVERY_30M": return "0 */30 * * * *";
            case "EVERY_1H": return "0 0 * * * *";
            case "EVERY_1D": return "0 0 0 * * *";
            default: return "0 */5 * * * *";
        }
    }

    public void stopSchedule(String id) {
        List<ScheduledFuture<?>> existingList = scheduledTasks.remove(id);
        if (existingList != null) {
            for (ScheduledFuture<?> future : existingList) {
                if (future != null) {
                    future.cancel(false);
                }
            }
        }
    }

    private long parseIntervalToSeconds(String preset) {
        switch (preset.toUpperCase()) {
            case "EVERY_1M": return 60;
            case "EVERY_5M": return 300;
            case "EVERY_15M": return 900;
            case "EVERY_30M": return 1800;
            case "EVERY_1H": return 3600;
            case "EVERY_1D": return 86400;
            default: return 300;
        }
    }

    public Map<String, Object> testHttpEndpoint(ApiSchedulerConfig config) throws Exception {
        long startTime = System.currentTimeMillis();
        Map<String, Object> result = new HashMap<>();

        try {
            String fullUrl = buildFullUrl(config.getUrl(), config.getQueryParams());
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder().uri(URI.create(fullUrl));

            // Set Headers
            if (config.getHeaders() != null && !config.getHeaders().trim().isEmpty()) {
                try {
                    ObjectMapper mapper = new ObjectMapper();
                    Map<String, String> headersMap = mapper.readValue(config.getHeaders(), new TypeReference<Map<String, String>>() {});
                    headersMap.forEach(reqBuilder::header);
                } catch (Exception e) {
                    logger.warn("Failed to parse request headers JSON: {}", e.getMessage());
                }
            }

            // Set Auth
            if ("basic".equalsIgnoreCase(config.getAuthType()) && config.getAuthUsername() != null && config.getAuthPassword() != null) {
                String authStr = config.getAuthUsername() + ":" + config.getAuthPassword();
                String encodedAuth = Base64.getEncoder().encodeToString(authStr.getBytes(StandardCharsets.UTF_8));
                reqBuilder.header("Authorization", "Basic " + encodedAuth);
            } else if ("bearer".equalsIgnoreCase(config.getAuthType()) && config.getAuthToken() != null) {
                reqBuilder.header("Authorization", "Bearer " + config.getAuthToken().trim());
            }

            // Set Method & Body
            String method = config.getMethod() != null ? config.getMethod().toUpperCase() : "GET";
            String bodyContent = config.getBodyContent() != null ? config.getBodyContent() : "";

            if ("POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method)) {
                reqBuilder.method(method, HttpRequest.BodyPublishers.ofString(bodyContent));
            } else if ("DELETE".equals(method)) {
                reqBuilder.method("DELETE", HttpRequest.BodyPublishers.noBody());
            } else {
                reqBuilder.GET();
            }

            HttpResponse<String> response = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
            long durationMs = System.currentTimeMillis() - startTime;

            result.put("statusCode", response.statusCode());
            result.put("durationMs", durationMs);
            result.put("body", response.body());

            Map<String, String> respHeaders = new HashMap<>();
            response.headers().map().forEach((k, v) -> respHeaders.put(k, String.join(", ", v)));
            result.put("headers", respHeaders);

            return result;

        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - startTime;
            result.put("statusCode", 500);
            result.put("durationMs", durationMs);
            result.put("body", "Failed to connect to endpoint: " + e.getMessage());
            return result;
        }
    }

    public void executeAndSaveSchedule(String id) {
        Optional<ApiSchedulerConfig> opt = repository.findById(id);
        if (opt.isEmpty()) {
            logger.error("Schedule ID [{}] not found", id);
            return;
        }
        ApiSchedulerConfig config = opt.get();

        logger.info("Executing API Ingestion Schedule [{}] - {} {}", config.getName(), config.getMethod(), config.getUrl());
        try {
            Map<String, Object> testRes = testHttpEndpoint(config);
            int statusCode = (int) testRes.get("statusCode");
            String responseBody = (String) testRes.get("body");

            if (statusCode < 200 || statusCode >= 300) {
                String errMsg = "HTTP Request failed with status code " + statusCode + ": " + (responseBody != null ? responseBody : "");
                logger.error("Schedule [{}] failed: {}", config.getName(), errMsg);
                repository.updateLastRun(id, "FAILED", errMsg);
                sendNotificationIfConfigured(config, "FAILED", errMsg);
                return;
            }

            // Ingest Response JSON to Target Database
            if (config.getTargetConnectionId() != null && config.getTargetTable() != null && !config.getTargetTable().trim().isEmpty()) {
                saveResponseToTargetDatabase(config.getTargetConnectionId(), config.getTargetTable().trim(), config.getKodeData(), responseBody);
            }

            String successMsg = "Successfully ingested API response (HTTP " + statusCode + ", " + testRes.get("durationMs") + "ms)";
            logger.info("Schedule [{}] completed successfully", config.getName());
            repository.updateLastRun(id, "SUCCESS", successMsg);

        } catch (Exception e) {
            String errMsg = "Execution error: " + e.getMessage();
            logger.error("Error executing API Ingestion Schedule [{}]: {}", config.getName(), e.getMessage(), e);
            repository.updateLastRun(id, "FAILED", errMsg);
            sendNotificationIfConfigured(config, "FAILED", errMsg);
        }
    }

    private void sendNotificationIfConfigured(ApiSchedulerConfig config, String status, String message) {
        if (!"FAILED".equalsIgnoreCase(status) || config.getNotificationChannelId() == null || config.getNotificationChannelId().trim().isEmpty()) {
            return;
        }
        String[] channelIds = config.getNotificationChannelId().split("[;,\\n]+");
        String notificationMsg = String.format("❌ [API Failure Alert] Job: %s, Error: %s", config.getName(), message);

        for (String chanId : channelIds) {
            try {
                notificationService.sendToChannel(chanId.trim(), notificationMsg);
            } catch (Exception e) {
                logger.warn("Failed to send notification to [{}]: {}", chanId, e.getMessage());
            }
        }
    }

    public void saveResponseToTargetDatabase(String connectionId, String targetTable, String kodeData, String responseJson) throws Exception {
        ConnectionDetails connDetails = connectionRepository.findById(connectionId);
        if (connDetails == null) {
            throw new RuntimeException("Target connection ID [" + connectionId + "] not found");
        }

        if (targetTable == null || targetTable.trim().isEmpty()) {
            throw new RuntimeException("Target table name is required");
        }

        DataSource ds = connectionManagerService.getDataSource(connDetails);
        String dbType = connDetails.getType() != null ? connDetails.getType().toLowerCase() : "postgresql";
        String effectiveKodeData = (kodeData != null && !kodeData.trim().isEmpty()) ? kodeData.trim() : "API_DATA";
        String cleanTargetTable = targetTable.trim();

        List<String> recordsToInsert = extractRecordsToInsert(responseJson);
        if (recordsToInsert.isEmpty()) {
            logger.info("No records found to insert for target table {}", cleanTargetTable);
            return;
        }

        try (Connection conn = ds.getConnection()) {
            if (!conn.getAutoCommit()) {
                conn.setAutoCommit(true);
            }
            if ("clickhouse".contains(dbType)) {
                // ClickHouse Ingestion (Do NOT CREATE TABLE automatically)
                // 1. Verify Table Existence
                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery("EXISTS TABLE " + cleanTargetTable)) {
                    if (rs.next() && rs.getInt(1) == 0) {
                        throw new RuntimeException("Target table '" + cleanTargetTable + "' does not exist in ClickHouse. Table must be created manually first.");
                    }
                }

                // 2. Verify Required Standard Columns: seq, kode_data, detail_data, input_by, input_dt
                Set<String> existingCols = new HashSet<>();
                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery("DESCRIBE TABLE " + cleanTargetTable)) {
                    while (rs.next()) {
                        existingCols.add(rs.getString("name").toLowerCase());
                    }
                }

                String[] requiredCols = {"seq", "kode_data", "detail_data", "input_by", "input_dt"};
                List<String> missingCols = new ArrayList<>();
                for (String col : requiredCols) {
                    if (!existingCols.contains(col)) {
                        missingCols.add(col);
                    }
                }
                if (!missingCols.isEmpty()) {
                    throw new RuntimeException("Target table '" + cleanTargetTable + "' does not conform to standard schema. Missing required columns: " + missingCols + ". Standard schema columns required: [seq, kode_data, detail_data, input_by, input_dt].");
                }

                // Insert into ClickHouse via HTTP API — bypasses JDBC driver entirely.
                // JDBC Statement.execute() with FORMAT JSONEachRow still processes the SQL string
                // and can mangle JSON data (escaping, encoding) before it reaches ClickHouse.
                // Direct HTTP POST sends raw UTF-8 bytes exactly as ClickHouse expects.
                //
                // Settings applied:
                //   async_insert=0                                   — synchronous insert, errors surface immediately
                //   date_time_input_format=best_effort               — handles ISO 8601 timestamps with +07:00 timezone
                //   input_format_json_infer_incomplete_types_as_strings=1 — handles null-only fields in JSON type
                ConnectionDetails chCd = connectionRepository.findById(connectionId);
                String chHost = (chCd != null && chCd.getHost() != null) ? chCd.getHost() : "localhost";
                int chPort = (chCd != null && chCd.getPort() > 0) ? chCd.getPort() : 8123;
                String chDatabase = (chCd != null && chCd.getDatabase() != null && !chCd.getDatabase().isEmpty()) ? chCd.getDatabase() : "default";
                String chUser = (chCd != null && chCd.getUsername() != null) ? chCd.getUsername() : "default";
                String chPass = (chCd != null && chCd.getPassword() != null) ? chCd.getPassword() : "";

                ObjectMapper chMapper = new ObjectMapper();
                StringBuilder jsonRows = new StringBuilder();
                for (String recordJson : recordsToInsert) {
                    Map<String, Object> row = new java.util.LinkedHashMap<>();
                    row.put("kode_data", effectiveKodeData);
                    // detail_data: parse as JsonNode so Jackson serializes it as a nested JSON object (not a quoted string)
                    JsonNode detailNode;
                    try {
                        detailNode = chMapper.readTree(recordJson);
                    } catch (Exception ex) {
                        detailNode = chMapper.getNodeFactory().textNode(recordJson);
                    }
                    row.put("detail_data", detailNode);
                    row.put("input_by", "darkosync");
                    row.put("input_dt", java.time.Instant.now().toString());
                    jsonRows.append(chMapper.writeValueAsString(row)).append("\n");
                }

                String chInsertQuery = "INSERT INTO " + cleanTargetTable
                        + " (kode_data, detail_data, input_by, input_dt) FORMAT JSONEachRow";
                String chHttpUrl = "http://" + chHost + ":" + chPort
                        + "/?database=" + URLEncoder.encode(chDatabase, StandardCharsets.UTF_8)
                        + "&query=" + URLEncoder.encode(chInsertQuery, StandardCharsets.UTF_8)
                        + "&async_insert=0"
                        + "&date_time_input_format=best_effort"
                        + "&input_format_json_infer_incomplete_types_as_strings=1";

                HttpRequest.Builder chReqBuilder = HttpRequest.newBuilder()
                        .uri(URI.create(chHttpUrl))
                        .timeout(Duration.ofSeconds(120))
                        .POST(HttpRequest.BodyPublishers.ofString(jsonRows.toString(), StandardCharsets.UTF_8))
                        .header("Content-Type", "application/octet-stream");

                if (!chUser.isEmpty()) {
                    String authStr = chUser + ":" + chPass;
                    chReqBuilder.header("Authorization", "Basic " + Base64.getEncoder().encodeToString(authStr.getBytes(StandardCharsets.UTF_8)));
                }

                HttpResponse<String> chHttpResponse = httpClient.send(chReqBuilder.build(), HttpResponse.BodyHandlers.ofString());
                if (chHttpResponse.statusCode() >= 400) {
                    throw new RuntimeException("ClickHouse HTTP insert failed (HTTP " + chHttpResponse.statusCode() + "): " + chHttpResponse.body());
                }
                logger.info("ClickHouse insert OK via HTTP API: {} records into {}", recordsToInsert.size(), cleanTargetTable);

            } else {
                // PostgreSQL or standard SQL Database Ingestion (Do NOT CREATE TABLE automatically)
                DatabaseMetaData meta = conn.getMetaData();
                String schemaName = null;
                String tableName = cleanTargetTable;
                if (cleanTargetTable.contains(".")) {
                    String[] parts = cleanTargetTable.split("\\.", 2);
                    schemaName = parts[0];
                    tableName = parts[1];
                }

                // 1. Verify Table Existence
                boolean tableExists = false;
                try (ResultSet rs = meta.getTables(null, schemaName, tableName, null)) {
                    if (rs.next()) {
                        tableExists = true;
                    }
                }
                if (!tableExists && schemaName != null) {
                    try (ResultSet rs = meta.getTables(null, schemaName.toLowerCase(), tableName.toLowerCase(), null)) {
                        if (rs.next()) {
                            tableExists = true;
                        }
                    }
                }
                if (!tableExists) {
                    try (ResultSet rs = meta.getTables(null, null, tableName, null)) {
                        if (rs.next()) {
                            tableExists = true;
                        }
                    }
                }

                if (!tableExists) {
                    throw new RuntimeException("Target table '" + cleanTargetTable + "' does not exist in target database. Table must be created manually first.");
                }

                // 2. Verify Required Standard Columns: seq, kode_data, detail_data, input_by, input_dt
                Set<String> existingCols = new HashSet<>();
                try (ResultSet rsCols = meta.getColumns(null, schemaName, tableName, null)) {
                    while (rsCols.next()) {
                        existingCols.add(rsCols.getString("COLUMN_NAME").toLowerCase());
                    }
                }
                if (existingCols.isEmpty() && schemaName != null) {
                    try (ResultSet rsCols = meta.getColumns(null, schemaName.toLowerCase(), tableName.toLowerCase(), null)) {
                        while (rsCols.next()) {
                            existingCols.add(rsCols.getString("COLUMN_NAME").toLowerCase());
                        }
                    }
                }
                if (existingCols.isEmpty()) {
                    try (ResultSet rsCols = meta.getColumns(null, null, tableName.toLowerCase(), null)) {
                        while (rsCols.next()) {
                            existingCols.add(rsCols.getString("COLUMN_NAME").toLowerCase());
                        }
                    }
                }

                String[] requiredCols = {"seq", "kode_data", "detail_data", "input_by", "input_dt"};
                List<String> missingCols = new ArrayList<>();
                for (String col : requiredCols) {
                    if (!existingCols.contains(col)) {
                        missingCols.add(col);
                    }
                }
                if (!missingCols.isEmpty()) {
                    throw new RuntimeException("Target table '" + cleanTargetTable + "' does not conform to standard schema. Missing required columns: " + missingCols + ". Standard schema columns required: [seq, kode_data, detail_data, input_by, input_dt].");
                }

                // Insert into PostgreSQL (Batch Insert)
                String insertSql = "INSERT INTO " + cleanTargetTable + " (kode_data, detail_data, input_by, input_dt) VALUES (?, ?, 'darkosync', CURRENT_TIMESTAMP)";
                try (PreparedStatement pstmt = conn.prepareStatement(insertSql)) {
                    for (String recordJson : recordsToInsert) {
                        pstmt.setString(1, effectiveKodeData);
                        pstmt.setString(2, recordJson);
                        pstmt.addBatch();
                    }
                    pstmt.executeBatch();
                }
                if (!conn.getAutoCommit()) {
                    conn.commit();
                }
            }
        }
    }

    private List<String> extractRecordsToInsert(String responseJson) {
        List<String> recordsToInsert = new ArrayList<>();
        if (responseJson == null || responseJson.trim().isEmpty()) {
            return recordsToInsert;
        }
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode rootNode = mapper.readTree(responseJson.trim());

            JsonNode targetArrayNode = findArrayNode(rootNode, 0);

            if (targetArrayNode != null && targetArrayNode.isArray()) {
                for (JsonNode item : targetArrayNode) {
                    recordsToInsert.add(mapper.writeValueAsString(item));
                }
            } else if (rootNode.isObject()) {
                recordsToInsert.add(mapper.writeValueAsString(rootNode));
            } else {
                recordsToInsert.add(responseJson);
            }
        } catch (Exception e) {
            logger.warn("Could not parse responseJson as JSON tree, using raw responseJson: {}", e.getMessage());
            recordsToInsert.add(responseJson);
        }
        return recordsToInsert;
    }

    private JsonNode findArrayNode(JsonNode node, int depth) {
        if (node == null || depth > 5) return null;

        if (node.isArray()) {
            return node;
        }

        if (node.isObject()) {
            // 1. Check standard common array keys first
            String[] commonKeys = {"data", "items", "records", "results", "list", "content", "payload", "rows", "data_list"};
            for (String key : commonKeys) {
                if (node.has(key) && node.get(key).isArray()) {
                    return node.get(key);
                }
            }

            // 2. Check any array field at current level
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (field.getValue().isArray()) {
                    return field.getValue();
                }
            }

            // 3. Deep recursive search inside child objects
            fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (field.getValue().isObject()) {
                    JsonNode childArray = findArrayNode(field.getValue(), depth + 1);
                    if (childArray != null) {
                        return childArray;
                    }
                }
            }
        }
        return null;
    }

    private String buildFullUrl(String baseUrl, String queryParamsJson) {
        if (queryParamsJson == null || queryParamsJson.trim().isEmpty()) {
            return baseUrl;
        }
        try {
            ObjectMapper mapper = new ObjectMapper();
            Map<String, String> params = mapper.readValue(queryParamsJson, new TypeReference<Map<String, String>>() {});
            if (params.isEmpty()) return baseUrl;

            StringBuilder sb = new StringBuilder(baseUrl);
            if (!baseUrl.contains("?")) {
                sb.append("?");
            } else if (!baseUrl.endsWith("&") && !baseUrl.endsWith("?")) {
                sb.append("&");
            }

            boolean first = true;
            for (Map.Entry<String, String> entry : params.entrySet()) {
                if (!first) sb.append("&");
                sb.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8));
                sb.append("=");
                sb.append(URLEncoder.encode(entry.getValue() != null ? entry.getValue() : "", StandardCharsets.UTF_8));
                first = false;
            }
            return sb.toString();
        } catch (Exception e) {
            logger.warn("Failed to parse query params JSON: {}", e.getMessage());
            return baseUrl;
        }
    }
}
