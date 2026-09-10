package com.dbdiff.service;

import com.dbdiff.model.ConnectionDetails;
import com.dbdiff.model.WebhookConfig;
import com.dbdiff.model.WebhookLog;
import com.dbdiff.repository.ConnectionRepository;
import com.dbdiff.repository.WebhookRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

@Service
public class WebhookService {

    private static final Logger logger = LoggerFactory.getLogger(WebhookService.class);

    private final WebhookRepository webhookRepository;
    private final ConnectionRepository connectionRepository;
    private final ConnectionManagerService connectionManagerService;
    private final NotificationService notificationService;
    private final ApiSchedulerService apiSchedulerService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Autowired
    public WebhookService(WebhookRepository webhookRepository,
                          ConnectionRepository connectionRepository,
                          ConnectionManagerService connectionManagerService,
                          NotificationService notificationService,
                          @org.springframework.context.annotation.Lazy ApiSchedulerService apiSchedulerService) {
        this.webhookRepository = webhookRepository;
        this.connectionRepository = connectionRepository;
        this.connectionManagerService = connectionManagerService;
        this.notificationService = notificationService;
        this.apiSchedulerService = apiSchedulerService;
    }

    public static class WebhookProcessResult {
        private boolean success;
        private int statusCode;
        private String message;
        private int rowsInserted;
        private long durationMs;

        public WebhookProcessResult(boolean success, int statusCode, String message, int rowsInserted, long durationMs) {
            this.success = success;
            this.statusCode = statusCode;
            this.message = message;
            this.rowsInserted = rowsInserted;
            this.durationMs = durationMs;
        }

        public boolean isSuccess() { return success; }
        public int getStatusCode() { return statusCode; }
        public String getMessage() { return message; }
        public int getRowsInserted() { return rowsInserted; }
        public long getDurationMs() { return durationMs; }
    }

    /**
     * Ingest and process an incoming webhook HTTP request
     */
    public WebhookProcessResult processIncomingWebhook(String slug, HttpServletRequest request, String payload) {
        long startTime = System.currentTimeMillis();
        String clientIp = extractClientIp(request);
        String httpMethod = request.getMethod();
        String headersJson = extractHeadersAsJson(request);

        Optional<WebhookConfig> optConfig = webhookRepository.findBySlug(slug);
        if (optConfig.isEmpty()) {
            long duration = System.currentTimeMillis() - startTime;
            logger.warn("Webhook slug '{}' not found from IP: {}", slug, clientIp);
            return new WebhookProcessResult(false, 404, "Webhook endpoint with slug '" + slug + "' not found", 0, duration);
        }

        WebhookConfig config = optConfig.get();
        String webhookId = config.getId();

        // 1. Check if active
        if (!config.isActive()) {
            long duration = System.currentTimeMillis() - startTime;
            recordLog(webhookId, slug, clientIp, httpMethod, headersJson, payload, "INACTIVE", 403, "Webhook is currently disabled", duration, config.getTargetTable(), 0);
            return new WebhookProcessResult(false, 403, "Webhook is currently deactivated / disabled", 0, duration);
        }

        // 2. IP Allowlist Verification
        if (config.getIpAllowlist() != null && !config.getIpAllowlist().trim().isEmpty()) {
            if (!isIpAllowed(clientIp, config.getIpAllowlist())) {
                long duration = System.currentTimeMillis() - startTime;
                String errorMsg = "Client IP '" + clientIp + "' is not permitted in the IP allowlist";
                logger.warn("Webhook [{}] IP verification failed: {}", config.getName(), errorMsg);
                recordLog(webhookId, slug, clientIp, httpMethod, headersJson, payload, "UNAUTHORIZED", 403, errorMsg, duration, config.getTargetTable(), 0);
                webhookRepository.updateStats(webhookId, "UNAUTHORIZED", errorMsg, false);
                return new WebhookProcessResult(false, 403, errorMsg, 0, duration);
            }
        }

        // 3. Secret Header / Signature Verification
        if (config.getSecretHeaderName() != null && !config.getSecretHeaderName().trim().isEmpty()) {
            String headerName = config.getSecretHeaderName().trim();
            String expectedValue = config.getSecretHeaderValue() != null ? config.getSecretHeaderValue().trim() : "";
            String receivedValue = request.getHeader(headerName);

            if (receivedValue == null || !receivedValue.trim().equals(expectedValue)) {
                long duration = System.currentTimeMillis() - startTime;
                String errorMsg = "Missing or invalid secret header '" + headerName + "'";
                logger.warn("Webhook [{}] header verification failed: {}", config.getName(), errorMsg);
                recordLog(webhookId, slug, clientIp, httpMethod, headersJson, payload, "UNAUTHORIZED", 401, errorMsg, duration, config.getTargetTable(), 0);
                webhookRepository.updateStats(webhookId, "UNAUTHORIZED", errorMsg, false);
                return new WebhookProcessResult(false, 401, errorMsg, 0, duration);
            }
        }

        // 4. Extract and normalize records to insert
        List<String> recordsToInsert = new ArrayList<>();
        String trimmedPayload = (payload != null) ? payload.trim() : "{}";
        if (trimmedPayload.isEmpty()) {
            trimmedPayload = "{}";
        }

        // Handle JSON array vs JSON object
        if (trimmedPayload.startsWith("[") && trimmedPayload.endsWith("]")) {
            try {
                JsonNode arr = objectMapper.readTree(trimmedPayload);
                if (arr.isArray() && arr.size() > 1) {
                    for (JsonNode item : arr) {
                        recordsToInsert.add(objectMapper.writeValueAsString(item));
                    }
                } else {
                    recordsToInsert.add(trimmedPayload);
                }
            } catch (Exception e) {
                recordsToInsert.add(trimmedPayload);
            }
        } else {
            recordsToInsert.add(trimmedPayload);
        }

        // 5. Ingest into Target Database with Strict Schema Validation
        try {
            int inserted = insertIntoTargetDatabase(config, recordsToInsert);
            long duration = System.currentTimeMillis() - startTime;

            webhookRepository.updateStats(webhookId, "SUCCESS", "Processed " + inserted + " records successfully", true);
            recordLog(webhookId, slug, clientIp, httpMethod, headersJson, payload, "SUCCESS", 200, null, duration, config.getTargetTable(), inserted);

            logger.info("Webhook [{}] processed successfully: {} records inserted into '{}'", config.getName(), inserted, config.getTargetTable());

            // 6. Asynchronous Detail Enrichment (Fetch Full Order Details from Ginee API)
            if (config.isEnableEnrichment()) {
                triggerAsyncEnrichment(config, recordsToInsert, payload);
            }

            return new WebhookProcessResult(true, 200, "Webhook received and ingested successfully", inserted, duration);

        } catch (Exception ex) {
            long duration = System.currentTimeMillis() - startTime;
            String errorMsg = ex.getMessage() != null ? ex.getMessage() : "Unknown database error";
            logger.error("Webhook [{}] ingestion error: {}", config.getName(), errorMsg, ex);

            String status = errorMsg.contains("does not conform to standard schema") || errorMsg.contains("does not exist")
                    ? "INVALID_SCHEMA" : "FAILED";

            webhookRepository.updateStats(webhookId, status, errorMsg, false);
            recordLog(webhookId, slug, clientIp, httpMethod, headersJson, payload, status, 400, errorMsg, duration, config.getTargetTable(), 0);

            // Trigger Failure Alert to Telegram & Discord
            sendFailureAlert(config, errorMsg, clientIp);

            return new WebhookProcessResult(false, 400, errorMsg, 0, duration);
        }
    }

    private int insertIntoTargetDatabase(WebhookConfig config, List<String> recordsToInsert) throws Exception {
        return insertIntoStorage(config.getTargetConnectionId(), config.getTargetTable(), config.getKodeData(), recordsToInsert);
    }

    public int insertIntoStorage(String connectionId, String targetTable, String kodeData, List<String> recordsToInsert) throws Exception {
        if (connectionId == null || connectionId.trim().isEmpty()) {
            throw new RuntimeException("Target connection ID is not configured");
        }
        if (targetTable == null || targetTable.trim().isEmpty()) {
            throw new RuntimeException("Target table is not configured");
        }

        ConnectionDetails connDetails = connectionRepository.findById(connectionId);
        if (connDetails == null) {
            throw new RuntimeException("Target connection ID [" + connectionId + "] not found in registered connections");
        }

        DataSource ds = connectionManagerService.getDataSource(connDetails);
        String dbType = connDetails.getType() != null ? connDetails.getType().toLowerCase() : "postgresql";
        String effectiveKodeData = (kodeData != null && !kodeData.trim().isEmpty()) ? kodeData.trim() : "WEBHOOK";
        String cleanTargetTable = targetTable.trim();

        if (recordsToInsert.isEmpty()) {
            return 0;
        }

        try (Connection conn = ds.getConnection()) {
            if (!conn.getAutoCommit()) {
                conn.setAutoCommit(true);
            }

            if ("clickhouse".contains(dbType)) {
                // 1. Verify ClickHouse Table Existence
                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery("EXISTS TABLE " + cleanTargetTable)) {
                    if (rs.next() && rs.getInt(1) == 0) {
                        throw new RuntimeException("Target table '" + cleanTargetTable + "' does not exist in ClickHouse. Table must be created manually first.");
                    }
                }

                // 2. Verify Standard 5 Columns
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

                // 3. ClickHouse HTTP API Ingestion
                String chHost = (connDetails.getHost() != null) ? connDetails.getHost() : "localhost";
                int chPort = (connDetails.getPort() > 0) ? connDetails.getPort() : 8123;
                String chDatabase = (connDetails.getDatabase() != null && !connDetails.getDatabase().isEmpty()) ? connDetails.getDatabase() : "default";
                String chUser = (connDetails.getUsername() != null) ? connDetails.getUsername() : "default";
                String chPass = (connDetails.getPassword() != null) ? connDetails.getPassword() : "";

                String detailType = colTypes.getOrDefault("detail_data", "string");
                boolean isJsonColumn = detailType.contains("json") || detailType.contains("object");

                StringBuilder jsonRows = new StringBuilder();
                long seqBase = System.currentTimeMillis() * 1000L;
                int rowIndex = 0;
                for (String recordJson : recordsToInsert) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("seq", seqBase + rowIndex);
                    rowIndex++;
                    row.put("kode_data", effectiveKodeData);
                    if (isJsonColumn) {
                        try {
                            row.put("detail_data", objectMapper.readTree(recordJson));
                        } catch (Exception ex) {
                            row.put("detail_data", recordJson);
                        }
                    } else {
                        row.put("detail_data", recordJson);
                    }
                    row.put("input_by", "darkosync");
                    row.put("input_dt", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
                    jsonRows.append(objectMapper.writeValueAsString(row)).append("\n");
                }

                String chInsertQuery = "INSERT INTO " + cleanTargetTable
                        + " (seq, kode_data, detail_data, input_by, input_dt) FORMAT JSONEachRow";

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
                        .timeout(Duration.ofSeconds(60))
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

                return recordsToInsert.size();

            } else {
                // PostgreSQL or Standard SQL Database Ingestion
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
                    if (rs.next()) tableExists = true;
                }
                if (!tableExists && schemaName != null) {
                    try (ResultSet rs = meta.getTables(null, schemaName.toLowerCase(), tableName.toLowerCase(), null)) {
                        if (rs.next()) tableExists = true;
                    }
                }
                if (!tableExists) {
                    try (ResultSet rs = meta.getTables(null, null, tableName.toLowerCase(), null)) {
                        if (rs.next()) tableExists = true;
                    }
                }
                if (!tableExists) {
                    throw new RuntimeException("Target table '" + cleanTargetTable + "' does not exist in target database. Table must be created manually first.");
                }

                // 2. Verify Standard 5 Columns: seq, kode_data, detail_data, input_by, input_dt
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

                // 3. Insert into PostgreSQL (Batch Insert)
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

                return recordsToInsert.size();
            }
        }
    }

    public void sendFailureAlert(WebhookConfig config, String errorMsg, String sourceIp) {
        if (config.getNotificationChannelId() == null || config.getNotificationChannelId().trim().isEmpty()) {
            return;
        }

        ConnectionDetails connDetails = connectionRepository.findById(config.getTargetConnectionId());
        String connName = (connDetails != null) ? connDetails.getName() : (config.getTargetConnectionId() != null ? config.getTargetConnectionId() : "N/A");
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        String alertMessage = String.format(
                "🚨 <b>[Darkosync Webhook Alert] Ingestion Failed</b>\n\n" +
                "<b>Webhook:</b> %s (<code>%s</code>)\n" +
                "<b>Target Connection:</b> %s\n" +
                "<b>Target Table:</b> <code>%s</code>\n" +
                "<b>Source IP:</b> <code>%s</code>\n" +
                "<b>Time:</b> %s\n\n" +
                "<b>Error Reason:</b>\n<code>%s</code>\n\n" +
                "<i>Please check table existence and strict schema: [seq, kode_data, detail_data, input_by, input_dt].</i>",
                escapeHtml(config.getName()),
                escapeHtml(config.getSlug()),
                escapeHtml(connName),
                escapeHtml(config.getTargetTable() != null ? config.getTargetTable() : "N/A"),
                escapeHtml(sourceIp != null ? sourceIp : "Unknown"),
                timestamp,
                escapeHtml(errorMsg)
        );

        String[] channelIds = config.getNotificationChannelId().split(",");
        for (String chanId : channelIds) {
            String trimmed = chanId.trim();
            if (!trimmed.isEmpty()) {
                try {
                    notificationService.sendToChannel(trimmed, alertMessage);
                } catch (Exception e) {
                    logger.warn("Failed to send webhook failure alert to channel [{}]: {}", trimmed, e.getMessage());
                }
            }
        }
    }

    public void sendTestAlert(WebhookConfig config) {
        if (config.getNotificationChannelId() == null || config.getNotificationChannelId().trim().isEmpty()) {
            throw new RuntimeException("No notification channels configured for this webhook");
        }

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        String testMessage = String.format(
                "🧪 <b>[Darkosync Webhook Test Notification]</b>\n\n" +
                "<b>Webhook:</b> %s (<code>%s</code>)\n" +
                "<b>Status:</b> Alert Channel Connected & Verified\n" +
                "<b>Target Table:</b> <code>%s</code>\n" +
                "<b>Time:</b> %s\n\n" +
                "<i>This is a test alert verifying your Telegram/Discord channel integration.</i>",
                escapeHtml(config.getName()),
                escapeHtml(config.getSlug()),
                escapeHtml(config.getTargetTable() != null ? config.getTargetTable() : "N/A"),
                timestamp
        );

        String[] channelIds = config.getNotificationChannelId().split(",");
        for (String chanId : channelIds) {
            String trimmed = chanId.trim();
            if (!trimmed.isEmpty()) {
                notificationService.sendToChannel(trimmed, testMessage);
            }
        }
    }

    private void recordLog(String webhookId, String webhookSlug, String sourceIp, String method,
                           String headers, String payload, String status, int statusCode,
                           String errorMessage, long durationMs, String targetTable, int rowsInserted) {
        try {
            WebhookLog log = new WebhookLog();
            log.setId(UUID.randomUUID().toString());
            log.setWebhookId(webhookId);
            log.setWebhookSlug(webhookSlug);
            log.setReceivedAt(LocalDateTime.now());
            log.setSourceIp(sourceIp);
            log.setHttpMethod(method);
            log.setHeaders(headers);
            log.setPayload(payload != null && payload.length() > 50000 ? payload.substring(0, 50000) + "... [truncated]" : payload);
            log.setStatus(status);
            log.setStatusCode(statusCode);
            log.setErrorMessage(errorMessage);
            log.setDurationMs(durationMs);
            log.setTargetTable(targetTable);
            log.setRowsInserted(rowsInserted);
            webhookRepository.insertLog(log);
        } catch (Exception e) {
            logger.warn("Failed to write webhook log: {}", e.getMessage());
        }
    }

    private String extractClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.trim().isEmpty()) {
            return xForwardedFor.split(",")[0].trim();
        }
        String xRealIp = request.getHeader("X-Real-IP");
        if (xRealIp != null && !xRealIp.trim().isEmpty()) {
            return xRealIp.trim();
        }
        return request.getRemoteAddr();
    }

    private boolean isIpAllowed(String clientIp, String allowlistStr) {
        if (allowlistStr == null || allowlistStr.trim().isEmpty()) return true;
        if (clientIp == null) return false;

        String[] entries = allowlistStr.split(",");
        for (String entry : entries) {
            String allowed = entry.trim();
            if (allowed.isEmpty()) continue;
            if ("*".equals(allowed) || "0.0.0.0/0".equals(allowed)) return true;
            if (clientIp.equalsIgnoreCase(allowed)) return true;
            if ("127.0.0.1".equals(clientIp) && ("localhost".equalsIgnoreCase(allowed) || "::1".equals(allowed))) return true;
            if ("::1".equals(clientIp) && ("localhost".equalsIgnoreCase(allowed) || "127.0.0.1".equals(allowed))) return true;
        }
        return false;
    }

    private String extractHeadersAsJson(HttpServletRequest request) {
        try {
            Map<String, String> headerMap = new LinkedHashMap<>();
            Enumeration<String> headerNames = request.getHeaderNames();
            if (headerNames != null) {
                while (headerNames.hasMoreElements()) {
                    String name = headerNames.nextElement();
                    headerMap.put(name, request.getHeader(name));
                }
            }
            return objectMapper.writeValueAsString(headerMap);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String escapeHtml(String input) {
        if (input == null) return "";
        return input.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    // ========================================================
    // Detail Data Enrichment (Ginee REST API Integration)
    // ========================================================

    /**
     * Trigger asynchronous detail enrichment / API Scheduler trigger in background thread
     */
    private void triggerAsyncEnrichment(WebhookConfig config, List<String> records, String rawPayload) {
        if (!config.isEnableEnrichment()) return;

        // 1. Check if linked API Scheduler is configured (New Flexible Architecture)
        if (config.getTriggerApiSchedulerId() != null && !config.getTriggerApiSchedulerId().trim().isEmpty()) {
            CompletableFuture.runAsync(() -> {
                try {
                    processApiSchedulerTrigger(config, records, rawPayload);
                } catch (Exception e) {
                    logger.error("Error in Webhook Trigger background task for [{}]: {}", config.getName(), e.getMessage(), e);
                }
            });
            return;
        }

        // 2. Legacy fallback: Process direct Ginee Enrichment
        if (config.getEnrichmentTargetConnectionId() == null || config.getEnrichmentTargetConnectionId().trim().isEmpty()) {
            logger.warn("Enrichment skipped for webhook [{}]: Target Connection ID for detail data is empty", config.getName());
            return;
        }
        if (config.getEnrichmentTargetTable() == null || config.getEnrichmentTargetTable().trim().isEmpty()) {
            logger.warn("Enrichment skipped for webhook [{}]: Target Table for detail data is empty", config.getName());
            return;
        }

        CompletableFuture.runAsync(() -> {
            try {
                processGineeEnrichment(config, records, rawPayload);
            } catch (Exception e) {
                logger.error("Error in Ginee enrichment background task for webhook [{}]: {}", config.getName(), e.getMessage(), e);
            }
        });
    }

    public static class FilterRule {
        private String key;
        private String value;
        public FilterRule() {}
        public FilterRule(String key, String value) {
            this.key = key;
            this.value = value;
        }
        public String getKey() { return key; }
        public void setKey(String key) { this.key = key; }
        public String getValue() { return value; }
        public void setValue(String value) { this.value = value; }
    }

    public static class ParamMapping {
        private String targetParam;
        private String sourceJsonPath;
        private String sourceType;
        public ParamMapping() {}
        public ParamMapping(String targetParam, String sourceJsonPath) {
            this.targetParam = targetParam;
            this.sourceJsonPath = sourceJsonPath;
        }
        public String getTargetParam() { return targetParam; }
        public void setTargetParam(String targetParam) { this.targetParam = targetParam; }
        public String getSourceJsonPath() { return sourceJsonPath; }
        public void setSourceJsonPath(String sourceJsonPath) { this.sourceJsonPath = sourceJsonPath; }
        public String getSourceType() { return sourceType; }
        public void setSourceType(String sourceType) { this.sourceType = sourceType; }
    }

    private List<FilterRule> parseFilterRules(WebhookConfig config) {
        List<FilterRule> rules = new ArrayList<>();
        if (config.getTriggerFilterRules() != null && !config.getTriggerFilterRules().trim().isEmpty()) {
            try {
                rules = objectMapper.readValue(config.getTriggerFilterRules(), new TypeReference<List<FilterRule>>() {});
            } catch (Exception e) {
                logger.warn("Failed parsing triggerFilterRules JSON: {}", e.getMessage());
            }
        }
        if (rules.isEmpty()) {
            String filterKey = config.getTriggerFilterKey();
            String filterVal = config.getTriggerFilterValue();
            if (filterKey != null && !filterKey.trim().isEmpty()) {
                rules.add(new FilterRule(filterKey.trim(), filterVal != null ? filterVal.trim() : "*"));
            }
        }
        rules.removeIf(r -> r.getKey() == null || r.getKey().trim().isEmpty());
        return rules;
    }

    private List<ParamMapping> parseParamMappings(WebhookConfig config) {
        List<ParamMapping> mappings = new ArrayList<>();
        if (config.getTriggerParamMapping() != null && !config.getTriggerParamMapping().trim().isEmpty()) {
            try {
                mappings = objectMapper.readValue(config.getTriggerParamMapping(), new TypeReference<List<ParamMapping>>() {});
            } catch (Exception e) {
                logger.warn("Failed parsing triggerParamMapping JSON: {}", e.getMessage());
            }
        }
        if (mappings.isEmpty()) {
            String paramKey = config.getTriggerParamKey();
            String paramTarget = config.getTriggerParamTarget();
            String target = (paramTarget != null && !paramTarget.trim().isEmpty())
                    ? paramTarget.replace("{", "").replace("}", "").trim()
                    : "orderId";
            String src = (paramKey != null && !paramKey.trim().isEmpty()) ? paramKey.trim() : "orderId";
            mappings.add(new ParamMapping(target, src));
        }
        mappings.removeIf(m -> m.getTargetParam() == null || m.getTargetParam().trim().isEmpty());
        return mappings;
    }

    /**
     * Process generic API Scheduler Trigger: inspects incoming JSON matching multiple trigger filter rules (key=value),
     * extracts mapped parameters via dynamic JSON paths, and calls ApiSchedulerService with parameter substitution.
     */
    private void processApiSchedulerTrigger(WebhookConfig config, List<String> records, String rawPayload) {
        List<FilterRule> filterRules = parseFilterRules(config);
        List<ParamMapping> paramMappings = parseParamMappings(config);

        List<Map<String, String>> matchedDynamicParamsList = new ArrayList<>();
        Set<String> seenSignatures = new HashSet<>();

        List<String> itemsToInspect = new ArrayList<>(records);
        if (rawPayload != null && !rawPayload.trim().isEmpty() && !itemsToInspect.contains(rawPayload)) {
            itemsToInspect.add(rawPayload);
        }

        for (String itemStr : itemsToInspect) {
            try {
                JsonNode root = objectMapper.readTree(itemStr);
                collectMatchingParams(root, filterRules, paramMappings, matchedDynamicParamsList, seenSignatures);
            } catch (Exception e) {
                logger.debug("Failed parsing record as JSON for trigger: {}", e.getMessage());
            }
        }

        if (matchedDynamicParamsList.isEmpty()) {
            logger.info("Webhook [{}]: No records matched trigger condition rules (rules count: {})", config.getName(), filterRules.size());
            return;
        }

        logger.info("Webhook [{}]: Found {} matching item(s) to trigger API Schedulers",
                config.getName(), matchedDynamicParamsList.size());

        if (apiSchedulerService == null) {
            logger.error("Webhook [{}]: ApiSchedulerService not available to execute trigger", config.getName());
            return;
        }

        List<String> schedulerIds = config.getTriggerApiSchedulerIdList();
        if (schedulerIds.isEmpty()) {
            logger.warn("Webhook [{}]: Trigger active but no API Schedulers selected", config.getName());
            return;
        }

        for (Map<String, String> dynamicParams : matchedDynamicParamsList) {
            for (String schedId : schedulerIds) {
                try {
                    apiSchedulerService.executeTriggerWithParams(schedId, dynamicParams, rawPayload);
                } catch (Exception ex) {
                    logger.error("Error executing triggered API Scheduler [{}] with params {}: {}",
                            schedId, dynamicParams, ex.getMessage(), ex);
                }
            }
        }
    }

    private void collectMatchingParams(JsonNode node, List<FilterRule> filterRules, List<ParamMapping> paramMappings,
                                       List<Map<String, String>> matchedList, Set<String> signatures) {
        if (node == null) return;

        if (node.isArray()) {
            for (JsonNode child : node) {
                collectMatchingParams(child, filterRules, paramMappings, matchedList, signatures);
            }
            return;
        }

        // Check if payload or data is embedded stringified JSON
        if (node.hasNonNull("payload") && node.get("payload").isTextual()) {
            try {
                JsonNode inner = objectMapper.readTree(node.get("payload").asText());
                collectMatchingParams(inner, filterRules, paramMappings, matchedList, signatures);
            } catch (Exception ignored) {}
        }
        if (node.hasNonNull("data") && node.get("data").isTextual()) {
            try {
                JsonNode inner = objectMapper.readTree(node.get("data").asText());
                collectMatchingParams(inner, filterRules, paramMappings, matchedList, signatures);
            } catch (Exception ignored) {}
        }

        // Check if current node satisfies ALL filter rules
        boolean allMatched = true;
        for (FilterRule rule : filterRules) {
            String actualVal = extractValueByPath(node, rule.getKey());
            if (!isStatusMatched(actualVal, rule.getValue())) {
                allMatched = false;
                break;
            }
        }

        if (allMatched) {
            Map<String, String> dynamicParams = new LinkedHashMap<>();
            for (ParamMapping m : paramMappings) {
                String val = extractValueByPath(node, m.getSourceJsonPath());
                if (!val.isEmpty()) {
                    dynamicParams.put(m.getTargetParam(), val.trim());
                }
            }

            if (!dynamicParams.isEmpty()) {
                // Ensure common aliases are available
                String primaryVal = dynamicParams.values().iterator().next();
                if (!dynamicParams.containsKey("pk")) dynamicParams.put("pk", primaryVal);
                if (!dynamicParams.containsKey("value")) dynamicParams.put("value", primaryVal);
                if (!dynamicParams.containsKey("orderId") && (dynamicParams.containsKey("order_id") || dynamicParams.containsKey("orderIds"))) {
                    dynamicParams.put("orderId", primaryVal);
                }

                String signature = dynamicParams.toString();
                if (!signatures.contains(signature)) {
                    signatures.add(signature);
                    matchedList.add(dynamicParams);
                }
            }
        }

        // Check nested containers
        if (node.has("data") && node.get("data").isObject()) {
            collectMatchingParams(node.get("data"), filterRules, paramMappings, matchedList, signatures);
        } else if (node.has("data") && node.get("data").isArray()) {
            collectMatchingParams(node.get("data"), filterRules, paramMappings, matchedList, signatures);
        }
        if (node.has("orders") && node.get("orders").isArray()) {
            collectMatchingParams(node.get("orders"), filterRules, paramMappings, matchedList, signatures);
        }
    }

    private String extractValueByPath(JsonNode root, String path) {
        if (root == null || path == null || path.trim().isEmpty()) return "";
        String cleanPath = path.trim();

        // Direct field check
        String direct = findDirectValue(root, cleanPath);
        if (!direct.isEmpty()) return direct;

        // Check inside payload or data if present as object
        if (root.has("payload") && root.get("payload").isObject()) {
            String val = findDirectValue(root.get("payload"), cleanPath);
            if (!val.isEmpty()) return val;
        }
        if (root.has("data") && root.get("data").isObject()) {
            String val = findDirectValue(root.get("data"), cleanPath);
            if (!val.isEmpty()) return val;
        }

        // Unpack stringified JSON
        if (root.hasNonNull("payload") && root.get("payload").isTextual()) {
            try {
                JsonNode inner = objectMapper.readTree(root.get("payload").asText());
                String res = extractValueByPath(inner, cleanPath);
                if (!res.isEmpty()) return res;
            } catch (Exception ignored) {}
        }
        if (root.hasNonNull("data") && root.get("data").isTextual()) {
            try {
                JsonNode inner = objectMapper.readTree(root.get("data").asText());
                String res = extractValueByPath(inner, cleanPath);
                if (!res.isEmpty()) return res;
            } catch (Exception ignored) {}
        }

        // Dot notation or array traversal
        String normalizedPath = cleanPath.replace("[", ".").replace("]", "");
        String[] tokens = normalizedPath.split("\\.");
        JsonNode curr = root;

        for (int i = 0; i < tokens.length; i++) {
            String token = tokens[i].trim();
            if (token.isEmpty()) continue;
            if (curr == null || curr.isMissingNode() || curr.isNull()) {
                return "";
            }

            if (curr.hasNonNull("payload") && curr.get("payload").isTextual()) {
                try {
                    curr = objectMapper.readTree(curr.get("payload").asText());
                } catch (Exception ignored) {}
            }

            if (curr.isArray()) {
                try {
                    int idx = Integer.parseInt(token);
                    if (idx >= 0 && idx < curr.size()) {
                        curr = curr.get(idx);
                        continue;
                    }
                } catch (NumberFormatException ignored) {}
                if (curr.size() > 0) {
                    curr = curr.get(0);
                    i--; // re-eval on first element
                    continue;
                } else {
                    return "";
                }
            }

            JsonNode next = findChildNode(curr, token);
            if (next != null && !next.isMissingNode() && !next.isNull()) {
                curr = next;
            } else if (curr.has("payload") && curr.get("payload").isObject()) {
                next = findChildNode(curr.get("payload"), token);
                if (next != null && !next.isMissingNode() && !next.isNull()) {
                    curr = next;
                } else {
                    return "";
                }
            } else if (curr.has("data") && curr.get("data").isObject()) {
                next = findChildNode(curr.get("data"), token);
                if (next != null && !next.isMissingNode() && !next.isNull()) {
                    curr = next;
                } else {
                    return "";
                }
            } else {
                return "";
            }
        }

        if (curr != null && !curr.isMissingNode() && !curr.isNull()) {
            if (curr.isValueNode()) {
                return curr.asText();
            } else if (curr.isArray() && curr.size() > 0) {
                return curr.get(0).asText();
            } else {
                return curr.toString();
            }
        }

        return "";
    }

    private JsonNode findChildNode(JsonNode node, String key) {
        if (node == null || !node.isObject()) return null;
        if (node.hasNonNull(key)) return node.get(key);
        String snake = toSnakeCase(key);
        if (node.hasNonNull(snake)) return node.get(snake);
        String camel = toCamelCase(key);
        if (node.hasNonNull(camel)) return node.get(camel);

        Iterator<String> it = node.fieldNames();
        while (it.hasNext()) {
            String f = it.next();
            if (f.equalsIgnoreCase(key) || f.equalsIgnoreCase(snake) || f.equalsIgnoreCase(camel)) {
                return node.get(f);
            }
        }
        return null;
    }

    private String findDirectValue(JsonNode node, String key) {
        JsonNode child = findChildNode(node, key);
        if (child != null && child.isValueNode()) {
            return child.asText();
        }
        return "";
    }

    private String toSnakeCase(String str) {
        if (str == null) return "";
        return str.replaceAll("([a-z])([A-Z]+)", "$1_$2").toLowerCase();
    }

    private String toCamelCase(String str) {
        if (str == null || !str.contains("_")) return str != null ? str : "";
        StringBuilder sb = new StringBuilder();
        boolean nextUpper = false;
        for (char c : str.toCharArray()) {
            if (c == '_') {
                nextUpper = true;
            } else if (nextUpper) {
                sb.append(Character.toUpperCase(c));
                nextUpper = false;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private String capitalize(String str) {
        if (str == null || str.isEmpty()) return "";
        return Character.toUpperCase(str.charAt(0)) + str.substring(1);
    }

    /**
     * Process Ginee enrichment: parse orderIds matching filter status and fetch full details
     */
    private void processGineeEnrichment(WebhookConfig config, List<String> records, String rawPayload) {
        String filterStatus = config.getEnrichmentFilterStatus();
        Set<String> orderIds = new LinkedHashSet<>();

        List<String> itemsToInspect = new ArrayList<>(records);
        if (rawPayload != null && !rawPayload.trim().isEmpty() && !itemsToInspect.contains(rawPayload)) {
            itemsToInspect.add(rawPayload);
        }

        for (String itemStr : itemsToInspect) {
            try {
                JsonNode root = objectMapper.readTree(itemStr);
                inspectJsonForOrders(root, filterStatus, orderIds);
            } catch (Exception e) {
                logger.debug("Failed parsing record as JSON for enrichment: {}", e.getMessage());
            }
        }

        if (orderIds.isEmpty()) {
            logger.info("Webhook [{}]: No orders matched enrichment filter '{}'", config.getName(), filterStatus);
            return;
        }

        logger.info("Webhook [{}]: Found {} order(s) matching filter '{}' for Ginee detail enrichment: {}",
                config.getName(), orderIds.size(), filterStatus, orderIds);

        // Resolve credentials
        String accessKey = (config.getEnrichmentGineeAccessKey() != null && !config.getEnrichmentGineeAccessKey().trim().isEmpty())
                ? config.getEnrichmentGineeAccessKey().trim()
                : System.getenv("GINEE_ACCESS_KEY");

        String secretKey = (config.getEnrichmentGineeSecretKey() != null && !config.getEnrichmentGineeSecretKey().trim().isEmpty())
                ? config.getEnrichmentGineeSecretKey().trim()
                : System.getenv("GINEE_SECRET_KEY");

        String baseUrl = System.getenv("GINEE_BASE_URL");
        if (baseUrl == null || baseUrl.trim().isEmpty()) {
            baseUrl = "https://api.ginee.com";
        }
        baseUrl = baseUrl.replaceAll("/+$", "");

        String country = System.getenv("GINEE_COUNTRY");
        if (country == null || country.trim().isEmpty()) {
            country = "ID";
        }

        if (accessKey == null || accessKey.trim().isEmpty() || secretKey == null || secretKey.trim().isEmpty()) {
            logger.warn("Webhook [{}]: Ginee credentials (accessKey or secretKey) missing. Cannot fetch order details.", config.getName());
            return;
        }

        List<String> orderIdList = new ArrayList<>(orderIds);
        // Batch in chunks of 50
        for (int i = 0; i < orderIdList.size(); i += 50) {
            List<String> chunk = orderIdList.subList(i, Math.min(i + 50, orderIdList.size()));
            fetchAndStoreGineeChunk(config, baseUrl, country, accessKey, secretKey, chunk);
        }
    }

    private void inspectJsonForOrders(JsonNode node, String filterStatus, Set<String> collectedOrderIds) {
        if (node == null) return;

        if (node.isArray()) {
            for (JsonNode child : node) {
                inspectJsonForOrders(child, filterStatus, collectedOrderIds);
            }
            return;
        }

        // Check if there is an inner payload object
        JsonNode payloadObj = node.has("payload") && node.get("payload").isObject() ? node.get("payload") : node;

        String orderStatus = "";
        if (payloadObj.hasNonNull("orderStatus")) {
            orderStatus = payloadObj.get("orderStatus").asText();
        } else if (payloadObj.hasNonNull("status")) {
            orderStatus = payloadObj.get("status").asText();
        } else if (payloadObj.hasNonNull("order_status")) {
            orderStatus = payloadObj.get("order_status").asText();
        } else if (node.hasNonNull("orderStatus")) {
            orderStatus = node.get("orderStatus").asText();
        }

        String orderId = "";
        if (payloadObj.hasNonNull("orderId")) {
            orderId = payloadObj.get("orderId").asText();
        } else if (payloadObj.hasNonNull("order_id")) {
            orderId = payloadObj.get("order_id").asText();
        } else if (node.hasNonNull("orderId")) {
            orderId = node.get("orderId").asText();
        } else if (node.hasNonNull("order_id")) {
            orderId = node.get("order_id").asText();
        }

        if (!orderId.trim().isEmpty() && isStatusMatched(orderStatus, filterStatus)) {
            collectedOrderIds.add(orderId.trim());
        }

        // In case of nested lists like 'orders' or 'data'
        if (node.has("data")) {
            inspectJsonForOrders(node.get("data"), filterStatus, collectedOrderIds);
        }
        if (node.has("orders")) {
            inspectJsonForOrders(node.get("orders"), filterStatus, collectedOrderIds);
        }
    }

    private boolean isStatusMatched(String actualStatus, String filterConfig) {
        if (filterConfig == null || filterConfig.trim().isEmpty() || "*".equals(filterConfig.trim())) {
            return true;
        }
        if (actualStatus == null || actualStatus.trim().isEmpty()) {
            return false;
        }
        String cleanActual = actualStatus.trim();
        String[] filters = filterConfig.split(",");
        for (String f : filters) {
            if (f.trim().equalsIgnoreCase(cleanActual)) {
                return true;
            }
        }
        return false;
    }

    private void fetchAndStoreGineeChunk(WebhookConfig config, String baseUrl, String country,
                                         String accessKey, String secretKey, List<String> orderIds) {
        try {
            String path = "/openapi/order/v1/batch-get";
            String signStr = "POST$" + path + "$";

            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secretKey.trim().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hmac = mac.doFinal(signStr.getBytes(StandardCharsets.UTF_8));
            String signature = Base64.getEncoder().encodeToString(hmac);

            Map<String, Object> reqMap = Map.of("orderIds", orderIds);
            String reqBody = objectMapper.writeValueAsString(reqMap);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + path))
                    .header("Authorization", accessKey.trim() + ":" + signature)
                    .header("X-Advai-Country", country.trim())
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(reqBody, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                logger.error("Webhook [{}] Ginee order/v1/batch-get returned HTTP {}: {}", config.getName(), resp.statusCode(), resp.body());
                return;
            }

            JsonNode respJson = objectMapper.readTree(resp.body());
            String code = respJson.path("code").asText();
            if (!"SUCCESS".equalsIgnoreCase(code)) {
                logger.warn("Webhook [{}] Ginee API code was not SUCCESS: {}", config.getName(), resp.body());
                return;
            }

            JsonNode dataNode = respJson.path("data");
            List<String> detailRecords = new ArrayList<>();

            if (dataNode.isArray()) {
                for (JsonNode order : dataNode) {
                    detailRecords.add(objectMapper.writeValueAsString(order));
                }
            } else if (dataNode.has("orders") && dataNode.path("orders").isArray()) {
                for (JsonNode order : dataNode.path("orders")) {
                    detailRecords.add(objectMapper.writeValueAsString(order));
                }
            } else if (dataNode.isObject() && !dataNode.isEmpty()) {
                detailRecords.add(objectMapper.writeValueAsString(dataNode));
            }

            if (!detailRecords.isEmpty()) {
                String effectiveKode = (config.getEnrichmentKodeData() != null && !config.getEnrichmentKodeData().trim().isEmpty())
                        ? config.getEnrichmentKodeData().trim() : "GINEE_READY_TO_SHIP";

                int inserted = insertIntoStorage(
                        config.getEnrichmentTargetConnectionId(),
                        config.getEnrichmentTargetTable(),
                        effectiveKode,
                        detailRecords
                );
                logger.info("Webhook [{}] successfully enriched and stored {} order details into '{}' ({})",
                        config.getName(), inserted, config.getEnrichmentTargetTable(), config.getEnrichmentTargetConnectionId());
            } else {
                logger.warn("Webhook [{}] Ginee API returned SUCCESS but no order details found in response for orderIds: {}", config.getName(), orderIds);
            }

        } catch (Exception e) {
            logger.error("Webhook [{}] failed during Ginee order fetch chunk: {}", config.getName(), e.getMessage(), e);
        }
    }
}
