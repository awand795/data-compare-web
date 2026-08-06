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

                // 2. Verify Required Standard Columns & Extract Column Types
                Set<String> existingCols = new HashSet<>();
                Map<String, String> colTypes = new HashMap<>();
                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery("DESCRIBE TABLE " + cleanTargetTable)) {
                    while (rs.next()) {
                        String colName = rs.getString("name").toLowerCase();
                        existingCols.add(colName);
                        colTypes.put(colName, rs.getString("type").toLowerCase());
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
                ConnectionDetails chCd = connectionRepository.findById(connectionId);
                String chHost = (chCd != null && chCd.getHost() != null) ? chCd.getHost() : "localhost";
                int chPort = (chCd != null && chCd.getPort() > 0) ? chCd.getPort() : 8123;
                String chDatabase = (chCd != null && chCd.getDatabase() != null && !chCd.getDatabase().isEmpty()) ? chCd.getDatabase() : "default";
                String chUser = (chCd != null && chCd.getUsername() != null) ? chCd.getUsername() : "default";
                String chPass = (chCd != null && chCd.getPassword() != null) ? chCd.getPassword() : "";

                // 3. Note: TRUNCATE TABLE is removed to allow accumulating data from multiple runs.

                // detail_data formatting depends on the ClickHouse column type:
                //   - JSON / Object('json') column: FORMAT JSONEachRow REQUIRES the raw JSON object
                //     starting with '{'. Sending a quoted string "{\"...\"}" fails with
                //     Code 117 "JSON object should start with '{'" (CAST(string, JSON) is rejected).
                //   - String / Nullable(String) column: REQUIRES a quoted string; sending a raw
                //     JSON object {...} fails with Code 117 "Cannot parse JSON object here".
                String detailType = colTypes.getOrDefault("detail_data", "string");
                boolean isJsonColumn = detailType.contains("json") || detailType.contains("object");
                logger.info("ClickHouse target table {} detail_data column type: {} (jsonColumn={})",
                        cleanTargetTable, detailType, isJsonColumn);

                ObjectMapper chMapper = new ObjectMapper();
                StringBuilder jsonRows = new StringBuilder();
                // ClickHouse has no AUTO_INCREMENT — seq must be supplied explicitly.
                // Use epoch-millis * 1000 + rowIndex so seq is always unique across runs.
                long seqBase = System.currentTimeMillis() * 1000L;
                int rowIndex = 0;
                for (String recordJson : recordsToInsert) {
                    Map<String, Object> row = new java.util.LinkedHashMap<>();
                    row.put("seq", seqBase + rowIndex);
                    rowIndex++;
                    row.put("kode_data", effectiveKodeData);
                    if (isJsonColumn) {
                        // JSON column -> pass the parsed JSON object/array so the value starts with '{'/'['.
                        try {
                            row.put("detail_data", chMapper.readTree(recordJson));
                        } catch (Exception ex) {
                            row.put("detail_data", recordJson);
                        }
                    } else {
                        // String column -> pass the RAW JSON string from the API response as-is.
                        // Do NOT re-serialize via Jackson so that numeric types (0 vs 0.0),
                        // null values, and field ordering are preserved exactly as returned by the API.
                        row.put("detail_data", recordJson);
                    }
                    row.put("input_by", "darkosync");
                    row.put("input_dt", java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
                    jsonRows.append(chMapper.writeValueAsString(row)).append("\n");
                }

                String chInsertQuery = "INSERT INTO " + cleanTargetTable
                        + " (seq, kode_data, detail_data, input_by, input_dt) FORMAT JSONEachRow";
                // allow_simdjson=0: on the production ClickHouse server the simdjson parser fails with
                // UNSUPPORTED_ARCHITECTURE (all JSON parsing breaks, incl. the JSON column type).
                // Forcing the fallback parser (RapidJSON) makes JSONEachRow inserts work reliably.
                String chHttpUrl = "http://" + chHost + ":" + chPort
                        + "/?database=" + URLEncoder.encode(chDatabase, StandardCharsets.UTF_8)
                        + "&query=" + URLEncoder.encode(chInsertQuery, StandardCharsets.UTF_8)
                        + "&async_insert=0"
                        + "&date_time_input_format=best_effort"
                        + "&allow_simdjson=0"
                        + "&input_format_null_as_default=1"
                        + "&input_format_json_read_numbers_as_strings=1"
                        + "&input_format_json_try_infer_numbers_from_strings=1"
                        + "&input_format_json_defaults_for_missing_elements_in_named_tuple=1"
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
        if (responseJson == null || responseJson.trim().isEmpty()) return recordsToInsert;
        String trimmed = responseJson.trim();

        // Unwrap double-encoded JSON string if needed
        if (trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            try {
                JsonNode unwrapped = objectMapper.readTree(trimmed);
                if (unwrapped != null && unwrapped.isTextual()) {
                    trimmed = unwrapped.asText().trim();
                }
            } catch (Exception ignored) {}
        }

        // Bundle complete API response into JSON array format [{...}]
        String bundledJson;
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            bundledJson = trimmed;
        } else if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            bundledJson = "[" + trimmed + "]";
        } else {
            try {
                JsonNode parsed = objectMapper.readTree(trimmed);
                bundledJson = "[" + objectMapper.writeValueAsString(parsed) + "]";
            } catch (Exception e) {
                bundledJson = "[\"" + trimmed.replace("\"", "\\\"") + "\"]";
            }
        }

        logger.info("Bundled API response into complete JSON array for data_detail (length: {})", bundledJson.length());
        recordsToInsert.add(bundledJson);
        return recordsToInsert;
    }

    /**
     * Extracts raw JSON substrings for each element in the target array by
     * scanning the original JSON string with Jackson's streaming parser.
     * The raw substrings are exact byte-for-byte copies from the original
     * API response, so numeric format, null values and field order are preserved.
     */
    private List<String> extractRawArrayItemsFromBestField(String json, JsonNode targetArray) {
        List<String> results = new ArrayList<>();
        if (targetArray == null || targetArray.size() == 0) return results;
        int expected = targetArray.size();
        try {
            byte[] bytes = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            com.fasterxml.jackson.core.JsonFactory factory = objectMapper.getFactory();
            try (com.fasterxml.jackson.core.JsonParser parser = factory.createParser(bytes)) {
                int depth = 0;
                boolean inTargetArray = false;
                int arrayDepth = -1;
                long itemStart = -1;
                int itemDepth = -1;
                List<String> current = new ArrayList<>();

                com.fasterxml.jackson.core.JsonToken token;
                while ((token = parser.nextToken()) != null) {
                    switch (token) {
                        case START_ARRAY:
                            depth++;
                            if (!inTargetArray) {
                                // Peek: collect items speculatively
                                inTargetArray = true;
                                arrayDepth = depth;
                                current = new ArrayList<>();
                            }
                            break;
                        case END_ARRAY:
                            if (inTargetArray && depth == arrayDepth) {
                                inTargetArray = false;
                                if (current.size() == expected) {
                                    return current; // found the right array
                                }
                                // wrong array — reset and keep looking
                                current = new ArrayList<>();
                                arrayDepth = -1;
                            }
                            depth--;
                            break;
                        case START_OBJECT:
                            depth++;
                            if (inTargetArray && depth == arrayDepth + 1 && itemStart < 0) {
                                // Start of array element
                                itemStart = parser.getTokenLocation().getByteOffset();
                                itemDepth = depth;
                            }
                            break;
                        case END_OBJECT:
                            if (inTargetArray && itemStart >= 0 && depth == itemDepth) {
                                long itemEnd = parser.getTokenLocation().getByteOffset() + 1;
                                String rawItem = new String(bytes, (int) itemStart, (int)(itemEnd - itemStart),
                                        java.nio.charset.StandardCharsets.UTF_8);
                                current.add(rawItem);
                                itemStart = -1;
                                itemDepth = -1;
                            }
                            depth--;
                            break;
                        default:
                            break;
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("extractRawArrayItemsFromBestField failed: {}", e.getMessage());
        }
        return results;
    }



    /**
     * Recursively finds the "most likely" data array anywhere in the JSON tree so any API
     * response shape can be ingested:
     *  - arrays of objects/arrays are preferred over arrays of scalars
     *  - larger arrays are preferred over smaller ones
     *  - ties keep the shallowest array (first found, top-down traversal)
     *  - double-encoded JSON string fields are unwrapped before searching
     */
    private JsonNode findBestArrayNode(JsonNode node) {
        return findBestArrayNode(node, 0, new int[]{0}, new JsonNode[]{null});
    }

    private JsonNode findBestArrayNode(JsonNode node, int depth, int[] bestScore, JsonNode[] bestNode) {
        if (node == null || depth > 100) return bestNode[0];

        if (node.isArray()) {
            int score = scoreArray(node);
            if (score > bestScore[0]) {
                bestScore[0] = score;
                bestNode[0] = node;
            }
            return bestNode[0]; // array elements are the records themselves — do not descend
        }

        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                JsonNode value = field.getValue();
                // Unwrap JSON-encoded string fields (double-encoded payloads)
                if (value != null && value.isTextual()) {
                    try {
                        JsonNode unwrapped = objectMapper.readTree(value.asText());
                        if (unwrapped != null && (unwrapped.isArray() || unwrapped.isObject())) {
                            value = unwrapped;
                        }
                    } catch (Exception ignored) {
                        // plain string value — keep as-is
                    }
                }
                findBestArrayNode(value, depth + 1, bestScore, bestNode);
            }
        }
        return bestNode[0];
    }

    private int scoreArray(JsonNode arr) {
        int size = arr.size();
        if (size == 0) return 1; // empty arrays stay candidates with the lowest priority
        boolean hasObjectElements = false;
        for (JsonNode el : arr) {
            if (el != null && (el.isObject() || el.isArray())) {
                hasObjectElements = true;
                break;
            }
        }
        // Arrays of objects/arrays dominate scalar arrays; any non-empty array beats an empty one.
        return (hasObjectElements ? 1000 : 1) + Math.min(size, 1000);
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
