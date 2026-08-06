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

    // =========================================================================
    // AUTOMATED MATERIALIZED VIEW (AUTO-MV) EXTRACTOR PIPELINES
    // =========================================================================

    public Connection getClickHouseConnection(String connectionId) throws Exception {
        if (connectionId != null && !connectionId.trim().isEmpty()) {
            ConnectionDetails targetConn = connectionRepository.findById(connectionId);
            if (targetConn != null) {
                DataSource ds = connectionManagerService.getDataSource(targetConn);
                return ds.getConnection();
            }
        }

        ConnectionDetails clickhouseConn = connectionRepository.findAll().stream()
                .filter(c -> "clickhouse".equalsIgnoreCase(c.getType()))
                .findFirst()
                .orElse(null);

        if (clickhouseConn != null) {
            DataSource ds = connectionManagerService.getDataSource(clickhouseConn);
            return ds.getConnection();
        }

        ConnectionDetails fallbackConn = new ConnectionDetails();
        fallbackConn.setType("clickhouse");
        fallbackConn.setHost(System.getenv().getOrDefault("CLICKHOUSE_HOST", "clickhouse"));
        fallbackConn.setPort(Integer.parseInt(System.getenv().getOrDefault("CLICKHOUSE_PORT", "8123")));
        fallbackConn.setDatabase(System.getenv().getOrDefault("CLICKHOUSE_DATABASE", "default"));
        fallbackConn.setUsername(System.getenv().getOrDefault("CLICKHOUSE_USER", "darkosync"));
        fallbackConn.setPassword(System.getenv().getOrDefault("CLICKHOUSE_PASSWORD", "darkoSync9292"));

        DataSource ds = connectionManagerService.getDataSource(fallbackConn);
        return ds.getConnection();
    }

    public Connection getClickHouseConnection() throws Exception {
        return getClickHouseConnection(null);
    }

    public List<String> getExistingTables(String connectionId) throws Exception {
        List<String> tables = new ArrayList<>();
        try (Connection conn = getClickHouseConnection(connectionId);
             Statement stmt = conn.createStatement()) {

            String dbName = "default";
            try (ResultSet rsDb = stmt.executeQuery("SELECT currentDatabase()")) {
                if (rsDb.next() && rsDb.getString(1) != null) {
                    dbName = rsDb.getString(1);
                }
            } catch (Exception ignored) {}

            String q = "SELECT DISTINCT name FROM system.tables WHERE (database = '" + dbName + "' OR database = 'default') AND engine != 'MaterializedView' ORDER BY name";
            try (ResultSet rs = stmt.executeQuery(q)) {
                while (rs.next()) {
                    tables.add(rs.getString("name"));
                }
            }
        }
        return tables;
    }

    public Map<String, Object> inspectJsonSchema(String sourceTable, String kodeData) throws Exception {
        return inspectJsonSchema(sourceTable, kodeData, null);
    }

    public Map<String, Object> inspectJsonSchema(String sourceTable, String kodeData, String connectionId) throws Exception {
        String cleanSource = (sourceTable != null && !sourceTable.trim().isEmpty()) 
                ? sourceTable.trim().replaceAll("[^a-zA-Z0-9_]", "_") 
                : "api_test";
        Map<String, Object> result = new HashMap<>();
        List<Map<String, String>> detectedFields = new ArrayList<>();
        List<String> existingTables = getExistingTables(connectionId);

        try (Connection conn = getClickHouseConnection(connectionId);
             Statement stmt = conn.createStatement()) {

            // Fetch sample detail_data
            String sampleQuery = "SELECT detail_data FROM default." + cleanSource + " WHERE detail_data != ''";
            if (kodeData != null && !kodeData.trim().isEmpty()) {
                sampleQuery += " AND kode_data = '" + kodeData.trim().replaceAll("[^a-zA-Z0-9_-]", "") + "'";
            }
            sampleQuery += " LIMIT 5";

            List<String> rawSamples = new ArrayList<>();
            try (ResultSet rs = stmt.executeQuery(sampleQuery)) {
                while (rs.next()) {
                    String d = rs.getString("detail_data");
                    if (d != null && !d.trim().isEmpty()) {
                        rawSamples.add(d);
                    }
                }
            }

            if (!rawSamples.isEmpty()) {
                for (String rawJson : rawSamples) {
                    try {
                        JsonNode root = objectMapper.readTree(rawJson);
                        JsonNode itemsNode = null;
                        if (root.isArray() && root.size() > 0) {
                            JsonNode first = root.get(0);
                            if (first.has("data") && first.get("data").isArray() && first.get("data").size() > 0) {
                                itemsNode = first.get("data").get(0);
                            } else {
                                itemsNode = first;
                            }
                        } else if (root.isObject()) {
                            if (root.has("data") && root.get("data").isArray() && root.get("data").size() > 0) {
                                itemsNode = root.get("data").get(0);
                            } else {
                                itemsNode = root;
                            }
                        }

                        if (itemsNode != null && itemsNode.isObject()) {
                            Iterator<Map.Entry<String, JsonNode>> fields = itemsNode.fields();
                            while (fields.hasNext()) {
                                Map.Entry<String, JsonNode> entry = fields.next();
                                String k = entry.getKey();
                                JsonNode v = entry.getValue();
                                String type = inferClickHouseType(v);

                                boolean exists = detectedFields.stream().anyMatch(f -> f.get("name").equals(k));
                                if (!exists) {
                                    Map<String, String> fInfo = new HashMap<>();
                                    fInfo.put("name", k);
                                    fInfo.put("type", type);
                                    fInfo.put("jsonKey", k);
                                    detectedFields.add(fInfo);
                                }
                            }
                            break;
                        }
                    } catch (Exception ex) {
                        logger.warn("Could not parse JSON sample: {}", ex.getMessage());
                    }
                }
            }
        }

        result.put("fields", detectedFields);
        result.put("existingTables", existingTables);
        String suggestedName = "target_" + (kodeData != null ? kodeData.toLowerCase().replaceAll("[^a-z0-9_]", "_") : "api") + "_api";
        result.put("suggestedTargetTable", suggestedName);
        result.put("sampleCount", detectedFields.size());
        return result;
    }

    private String inferClickHouseType(JsonNode v) {
        if (v == null || v.isNull()) return "String";
        if (v.isBoolean()) return "UInt8";
        if (v.isIntegralNumber()) return "UInt64";
        if (v.isFloatingPointNumber()) return "Float64";
        return "String";
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> deployAutoMvPipeline(Map<String, Object> req) throws Exception {
        String sourceTable = (String) req.getOrDefault("sourceTable", "api_test");
        String kodeData = (String) req.get("kodeData");
        String targetTable = (String) req.get("targetTable");
        boolean createNewTable = Boolean.TRUE.equals(req.get("createNewTable"));
        boolean backfillHistorical = Boolean.TRUE.equals(req.get("backfillHistorical"));
        List<String> orderBy = (List<String>) req.getOrDefault("orderBy", List.of("kode_data"));
        List<Map<String, String>> fields = (List<Map<String, String>>) req.get("fields");

        String connectionId = (String) req.get("connectionId");

        if (kodeData == null || kodeData.trim().isEmpty()) {
            throw new IllegalArgumentException("kodeData is required");
        }
        if (targetTable == null || targetTable.trim().isEmpty()) {
            throw new IllegalArgumentException("targetTable is required");
        }
        if (fields == null || fields.isEmpty()) {
            throw new IllegalArgumentException("At least one field must be selected for extraction");
        }

        String cleanTarget = targetTable.trim().replaceAll("[^a-zA-Z0-9_]", "_");
        String cleanSource = sourceTable.trim().replaceAll("[^a-zA-Z0-9_]", "_");
        String mvName = cleanTarget.startsWith("mv_extractor_") ? cleanTarget : "mv_extractor_" + cleanTarget;

        try (Connection conn = getClickHouseConnection(connectionId);
             Statement stmt = conn.createStatement()) {

            stmt.execute("SET allow_simdjson = 0");

            // 1. Create Target Table if createNewTable is true
            if (createNewTable) {
                StringBuilder createTableSql = new StringBuilder();
                createTableSql.append("CREATE TABLE IF NOT EXISTS default.").append(cleanTarget).append(" (\n");
                createTableSql.append("    kode_data String,\n");
                for (Map<String, String> f : fields) {
                    String fname = f.get("name").replaceAll("[^a-zA-Z0-9_]", "_");
                    String ftype = f.getOrDefault("type", "String");
                    createTableSql.append("    ").append(fname).append(" ").append(ftype).append(",\n");
                }
                createTableSql.append("    sync_dt String DEFAULT toString(now())\n");
                createTableSql.append(") ENGINE = ReplacingMergeTree()\n");
                
                String orderCols = (orderBy != null && !orderBy.isEmpty()) 
                        ? String.join(", ", orderBy) 
                        : "kode_data";
                createTableSql.append("ORDER BY (").append(orderCols).append(")");

                logger.info("Executing Target Table Creation: {}", createTableSql);
                stmt.execute(createTableSql.toString());
            }

            // 2. Create Materialized View
            StringBuilder mvSql = new StringBuilder();
            mvSql.append("CREATE MATERIALIZED VIEW IF NOT EXISTS default.").append(mvName);
            mvSql.append(" TO default.").append(cleanTarget).append(" AS\n");
            mvSql.append("SELECT\n");
            mvSql.append("    kode_data,\n");

            for (Map<String, String> f : fields) {
                String fname = f.get("name").replaceAll("[^a-zA-Z0-9_]", "_");
                String jsonKey = f.getOrDefault("jsonKey", fname);
                String ftype = f.getOrDefault("type", "String");

                if ("UInt8".equalsIgnoreCase(ftype)) {
                    mvSql.append("    toUInt8OrZero(JSONExtractString(item, '").append(jsonKey).append("')) AS ").append(fname).append(",\n");
                } else if ("UInt64".equalsIgnoreCase(ftype) || "Int64".equalsIgnoreCase(ftype)) {
                    mvSql.append("    toUInt64OrZero(JSONExtractString(item, '").append(jsonKey).append("')) AS ").append(fname).append(",\n");
                } else if ("Float64".equalsIgnoreCase(ftype)) {
                    mvSql.append("    toFloat64OrZero(JSONExtractString(item, '").append(jsonKey).append("')) AS ").append(fname).append(",\n");
                } else {
                    mvSql.append("    JSONExtractString(item, '").append(jsonKey).append("') AS ").append(fname).append(",\n");
                }
            }
            mvSql.append("    toString(now()) AS sync_dt\n");
            mvSql.append("FROM (\n");
            mvSql.append("    SELECT kode_data, arrayJoin(JSONExtractArrayRaw(detail_data, 1, 'data')) AS item\n");
            mvSql.append("    FROM default.").append(cleanSource).append("\n");
            mvSql.append(")\n");
            mvSql.append("WHERE kode_data = '").append(kodeData.trim()).append("'");

            logger.info("Executing Materialized View Creation: {}", mvSql);
            stmt.execute(mvSql.toString());

            // 3. Backfill Historical Data if requested
            int backfilledCount = 0;
            if (backfillHistorical) {
                StringBuilder insertSql = new StringBuilder();
                insertSql.append("INSERT INTO default.").append(cleanTarget).append("\n");
                insertSql.append("SELECT\n");
                insertSql.append("    kode_data,\n");
                for (Map<String, String> f : fields) {
                    String fname = f.get("name").replaceAll("[^a-zA-Z0-9_]", "_");
                    String jsonKey = f.getOrDefault("jsonKey", fname);
                    String ftype = f.getOrDefault("type", "String");

                    if ("UInt8".equalsIgnoreCase(ftype)) {
                        insertSql.append("    toUInt8OrZero(JSONExtractString(item, '").append(jsonKey).append("')) AS ").append(fname).append(",\n");
                    } else if ("UInt64".equalsIgnoreCase(ftype) || "Int64".equalsIgnoreCase(ftype)) {
                        insertSql.append("    toUInt64OrZero(JSONExtractString(item, '").append(jsonKey).append("')) AS ").append(fname).append(",\n");
                    } else if ("Float64".equalsIgnoreCase(ftype)) {
                        insertSql.append("    toFloat64OrZero(JSONExtractString(item, '").append(jsonKey).append("')) AS ").append(fname).append(",\n");
                    } else {
                        insertSql.append("    JSONExtractString(item, '").append(jsonKey).append("') AS ").append(fname).append(",\n");
                    }
                }
                insertSql.append("    toString(now()) AS sync_dt\n");
                insertSql.append("FROM (\n");
                insertSql.append("    SELECT kode_data, arrayJoin(JSONExtractArrayRaw(detail_data, 1, 'data')) AS item\n");
                insertSql.append("    FROM default.").append(cleanSource).append("\n");
                insertSql.append("    WHERE kode_data = '").append(kodeData.trim()).append("'\n");
                insertSql.append(")");

                logger.info("Executing Historical Backfill: {}", insertSql);
                try {
                    backfilledCount = stmt.executeUpdate(insertSql.toString());
                } catch (Exception ex) {
                    logger.warn("Historical backfill completed with warning: {}", ex.getMessage());
                }
            }

            Map<String, Object> res = new HashMap<>();
            res.put("success", true);
            res.put("targetTable", cleanTarget);
            res.put("mvName", mvName);
            res.put("backfilledRecords", backfilledCount);
            res.put("message", "Auto-MV Pipeline deployed successfully for target [" + cleanTarget + "]!");
            return res;
        }
    }

    public List<Map<String, Object>> getAllAutoMvPipelines() throws Exception {
        return getAllAutoMvPipelines(null);
    }

    public List<Map<String, Object>> getAllAutoMvPipelines(String connectionId) throws Exception {
        List<Map<String, Object>> list = new ArrayList<>();
        try (Connection conn = getClickHouseConnection(connectionId);
             Statement stmt = conn.createStatement()) {

            String query = "SELECT name, create_table_query FROM system.tables WHERE database = 'default' AND engine = 'MaterializedView' AND (name LIKE 'mv_extractor_%' OR name LIKE 'mv_%') ORDER BY name";
            try (ResultSet rs = stmt.executeQuery(query)) {
                while (rs.next()) {
                    String mvName = rs.getString("name");
                    String sql = rs.getString("create_table_query");
                    if (sql == null) continue;

                    String sqlUpper = sql.toUpperCase();
                    // Filter: Strictly include only Auto-MV Extractor views (contain JSONExtract or arrayJoin or created with mv_extractor_ prefix)
                    boolean isExtractorMv = mvName.startsWith("mv_extractor_") || 
                                           (sqlUpper.contains("JSONEXTRACT") && sqlUpper.contains("ARRAYJOIN")) || 
                                           (sqlUpper.contains("JSONEXTRACT") && sqlUpper.contains("KODE_DATA"));

                    if (!isExtractorMv) {
                        // Skip generic Data Warehouse CDC Materialized Views
                        continue;
                    }

                    String targetTable = mvName.replaceFirst("^mv_(extractor_)?", "");
                    
                    if (sqlUpper.contains(" TO ")) {
                        int idx = sqlUpper.indexOf(" TO ");
                        String rest = sql.substring(idx + 4).trim();
                        String[] parts = rest.split("\\s+");
                        if (parts.length > 0) {
                            targetTable = parts[0].replace("default.", "").replace("`", "");
                        }
                    }

                    long rowCount = 0;
                    try (Statement cntStmt = conn.createStatement();
                         ResultSet cntRs = cntStmt.executeQuery("SELECT count() FROM default." + targetTable)) {
                        if (cntRs.next()) {
                            rowCount = cntRs.getLong(1);
                        }
                    } catch (Exception ignored) {}

                    Map<String, Object> map = new HashMap<>();
                    map.put("mvName", mvName);
                    map.put("targetTable", targetTable);
                    map.put("query", sql);
                    map.put("syncedRecords", rowCount);
                    list.add(map);
                }
            }
        }
        return list;
    }

    public Map<String, Object> deleteAutoMvPipeline(String mvName) throws Exception {
        return deleteAutoMvPipeline(mvName, null);
    }

    public Map<String, Object> deleteAutoMvPipeline(String mvName, String connectionId) throws Exception {
        String cleanMv = mvName.trim().replaceAll("[^a-zA-Z0-9_]", "_");
        try (Connection conn = getClickHouseConnection(connectionId);
             Statement stmt = conn.createStatement()) {
            stmt.execute("DROP VIEW IF EXISTS default." + cleanMv);
            return Map.of("success", true, "message", "Materialized View [" + cleanMv + "] deleted successfully");
        }
    }
}
