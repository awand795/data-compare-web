package com.dbdiff.service;

import com.dbdiff.model.ApiSchedulerConfig;
import com.dbdiff.model.ConnectionDetails;
import com.dbdiff.repository.ApiSchedulerRepository;
import com.dbdiff.repository.ConnectionRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.sql.PreparedStatement;
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

        // 1. Build URL with Query Parameters
        String fullUrl = config.getUrl() != null ? config.getUrl().trim() : "";
        if (config.getQueryParams() != null && !config.getQueryParams().trim().isEmpty()) {
            try {
                Map<String, String> queryMap = objectMapper.readValue(config.getQueryParams(), new TypeReference<Map<String, String>>() {});
                if (!queryMap.isEmpty()) {
                    StringBuilder sb = new StringBuilder(fullUrl);
                    sb.append(fullUrl.contains("?") ? "&" : "?");
                    int i = 0;
                    for (Map.Entry<String, String> entry : queryMap.entrySet()) {
                        if (i > 0) sb.append("&");
                        sb.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
                          .append("=")
                          .append(URLEncoder.encode(entry.getValue() != null ? entry.getValue() : "", StandardCharsets.UTF_8));
                        i++;
                    }
                    fullUrl = sb.toString();
                }
            } catch (Exception e) {
                logger.warn("Failed to parse queryParams JSON: " + e.getMessage());
            }
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(fullUrl))
                .timeout(Duration.ofSeconds(30));

        // 2. Set Headers
        builder.header("User-Agent", "Darkosync-ApiScheduler/1.0");
        if (config.getHeaders() != null && !config.getHeaders().trim().isEmpty()) {
            try {
                Map<String, String> headerMap = objectMapper.readValue(config.getHeaders(), new TypeReference<Map<String, String>>() {});
                for (Map.Entry<String, String> entry : headerMap.entrySet()) {
                    if (entry.getKey() != null && !entry.getKey().trim().isEmpty()) {
                        builder.header(entry.getKey().trim(), entry.getValue() != null ? entry.getValue() : "");
                    }
                }
            } catch (Exception e) {
                logger.warn("Failed to parse headers JSON: " + e.getMessage());
            }
        }

        // 3. Set Auth
        if ("basic".equalsIgnoreCase(config.getAuthType())) {
            String userPass = (config.getAuthUsername() != null ? config.getAuthUsername() : "") + ":" + (config.getAuthPassword() != null ? config.getAuthPassword() : "");
            String encoded = Base64.getEncoder().encodeToString(userPass.getBytes(StandardCharsets.UTF_8));
            builder.header("Authorization", "Basic " + encoded);
        } else if ("bearer".equalsIgnoreCase(config.getAuthType()) && config.getAuthToken() != null && !config.getAuthToken().trim().isEmpty()) {
            builder.header("Authorization", "Bearer " + config.getAuthToken().trim());
        }

        // 4. Set Method & Body
        String method = config.getMethod() != null ? config.getMethod().toUpperCase().trim() : "GET";
        String bodyPayload = config.getBodyContent() != null ? config.getBodyContent() : "";

        if ("POST".equals(method)) {
            if ("json".equalsIgnoreCase(config.getBodyType())) builder.header("Content-Type", "application/json");
            builder.POST(HttpRequest.BodyPublishers.ofString(bodyPayload, StandardCharsets.UTF_8));
        } else if ("PUT".equals(method)) {
            if ("json".equalsIgnoreCase(config.getBodyType())) builder.header("Content-Type", "application/json");
            builder.PUT(HttpRequest.BodyPublishers.ofString(bodyPayload, StandardCharsets.UTF_8));
        } else if ("PATCH".equals(method)) {
            if ("json".equalsIgnoreCase(config.getBodyType())) builder.header("Content-Type", "application/json");
            builder.method("PATCH", HttpRequest.BodyPublishers.ofString(bodyPayload, StandardCharsets.UTF_8));
        } else if ("DELETE".equals(method)) {
            builder.DELETE();
        } else {
            builder.GET();
        }

        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        long duration = System.currentTimeMillis() - startTime;

        Map<String, String> respHeaders = new LinkedHashMap<>();
        response.headers().map().forEach((k, v) -> respHeaders.put(k, String.join(", ", v)));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("statusCode", response.statusCode());
        result.put("durationMs", duration);
        result.put("body", response.body());
        result.put("headers", respHeaders);
        return result;
    }

    public void executeAndSaveSchedule(String id) {
        Optional<ApiSchedulerConfig> opt = repository.findById(id);
        if (opt.isEmpty()) return;
        ApiSchedulerConfig config = opt.get();

        logger.info("Executing API Ingestion Schedule [{}]...", config.getName());
        try {
            Map<String, Object> testRes = testHttpEndpoint(config);
            int statusCode = (int) testRes.get("statusCode");
            String responseBody = (String) testRes.get("body");

            if (statusCode < 200 || statusCode >= 300) {
                String errMsg = "API call returned HTTP status " + statusCode;
                logger.warn("Schedule [{}] failed: {}", config.getName(), errMsg);
                repository.updateLastRun(id, "FAILED", errMsg);
                sendNotificationIfConfigured(config, "FAILED", errMsg);
                return;
            }

            // Save response to target database
            if (config.getTargetConnectionId() != null && !config.getTargetConnectionId().trim().isEmpty() &&
                config.getTargetTable() != null && !config.getTargetTable().trim().isEmpty()) {
                
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
        // Only send notification if status is FAILED (API fetch error or DB insert failure)
        if (!"FAILED".equalsIgnoreCase(status)) {
            return;
        }
        if (config.getNotificationChannelId() == null || config.getNotificationChannelId().trim().isEmpty()) {
            return;
        }
        String rawChannels = config.getNotificationChannelId().trim();
        String[] channelIds = rawChannels.split("[;,\\n]+");

        String notificationMsg = String.format(
            "❌ <b>[API Ingestion Failure Alert]</b>\n" +
            "<b>Job Name:</b> %s\n" +
            "<b>Method & URL:</b> %s %s\n" +
            "<b>Status:</b> FAILED\n" +
            "<b>Target Table:</b> %s\n" +
            "<b>Kode Data:</b> %s\n" +
            "<b>Error Detail:</b> %s\n" +
            "<b>Timestamp:</b> %s",
            config.getName(),
            config.getMethod(),
            config.getUrl(),
            config.getTargetTable() != null ? config.getTargetTable() : "-",
            config.getKodeData() != null ? config.getKodeData() : "-",
            message,
            java.time.LocalDateTime.now().toString()
        );

        for (String chanId : channelIds) {
            String trimmedId = chanId.trim();
            if (trimmedId.isEmpty()) continue;
            try {
                notificationService.sendToChannel(trimmedId, notificationMsg);
                logger.info("Sent API Scheduler FAILURE alert notification to channel [{}]", trimmedId);
            } catch (Exception e) {
                logger.warn("Failed to send API Scheduler failure notification to channel [{}]: {}", trimmedId, e.getMessage());
            }
        }
    }

    public void saveResponseToTargetDatabase(String connectionId, String targetTable, String kodeData, String responseJson) throws Exception {
        ConnectionDetails connDetails = connectionRepository.findById(connectionId);
        if (connDetails == null) {
            throw new RuntimeException("Target connection ID [" + connectionId + "] not found");
        }

        DataSource ds = connectionManagerService.getDataSource(connDetails);
        String dbType = connDetails.getType() != null ? connDetails.getType().toLowerCase() : "postgresql";
        String effectiveKodeData = (kodeData != null && !kodeData.trim().isEmpty()) ? kodeData.trim() : "API_DATA";

        try (Connection conn = ds.getConnection()) {
            if ("clickhouse".contains(dbType)) {
                // ClickHouse ingestion
                String ddl = "CREATE TABLE IF NOT EXISTS " + targetTable + " (" +
                        "kode_data String, " +
                        "detail_data String, " +
                        "input_by String DEFAULT 'darkosync', " +
                        "input_dt DateTime DEFAULT now() " +
                        ") ENGINE = MergeTree() ORDER BY (input_dt, kode_data)";
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute(ddl);
                }

                String insertSql = "INSERT INTO " + targetTable + " (kode_data, detail_data, input_by, input_dt) VALUES (?, ?, 'darkosync', now())";
                try (PreparedStatement pstmt = conn.prepareStatement(insertSql)) {
                    pstmt.setString(1, effectiveKodeData);
                    pstmt.setString(2, responseJson);
                    pstmt.executeUpdate();
                }
            } else {
                // PostgreSQL or standard SQL database
                String ddl = "CREATE TABLE IF NOT EXISTS " + targetTable + " (" +
                        "kode_data VARCHAR(255), " +
                        "detail_data TEXT, " +
                        "input_by VARCHAR(100) DEFAULT 'darkosync', " +
                        "input_dt TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                        ")";
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute(ddl);
                }

                String insertSql = "INSERT INTO " + targetTable + " (kode_data, detail_data, input_by, input_dt) VALUES (?, ?, 'darkosync', CURRENT_TIMESTAMP)";
                try (PreparedStatement pstmt = conn.prepareStatement(insertSql)) {
                    pstmt.setString(1, effectiveKodeData);
                    pstmt.setString(2, responseJson);
                    pstmt.executeUpdate();
                }
            }
        }
    }
}
