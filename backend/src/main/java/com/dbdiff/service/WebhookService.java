package com.dbdiff.service;

import com.dbdiff.model.ConnectionDetails;
import com.dbdiff.model.WebhookConfig;
import com.dbdiff.model.WebhookLog;
import com.dbdiff.repository.ConnectionRepository;
import com.dbdiff.repository.WebhookRepository;
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

@Service
public class WebhookService {

    private static final Logger logger = LoggerFactory.getLogger(WebhookService.class);

    private final WebhookRepository webhookRepository;
    private final ConnectionRepository connectionRepository;
    private final ConnectionManagerService connectionManagerService;
    private final NotificationService notificationService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Autowired
    public WebhookService(WebhookRepository webhookRepository,
                          ConnectionRepository connectionRepository,
                          ConnectionManagerService connectionManagerService,
                          NotificationService notificationService) {
        this.webhookRepository = webhookRepository;
        this.connectionRepository = connectionRepository;
        this.connectionManagerService = connectionManagerService;
        this.notificationService = notificationService;
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
        String connectionId = config.getTargetConnectionId();
        String targetTable = config.getTargetTable();
        String kodeData = config.getKodeData();

        if (connectionId == null || connectionId.trim().isEmpty()) {
            throw new RuntimeException("Target connection ID is not configured for this webhook");
        }
        if (targetTable == null || targetTable.trim().isEmpty()) {
            throw new RuntimeException("Target table is not configured for this webhook");
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
}
