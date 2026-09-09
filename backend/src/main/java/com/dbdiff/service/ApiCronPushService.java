package com.dbdiff.service;

import com.dbdiff.model.ApiEndpoint;
import com.dbdiff.model.ConnectionDetails;
import com.dbdiff.repository.ApiEndpointRepository;
import com.dbdiff.repository.ConnectionRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

@Service
public class ApiCronPushService {

    private static final Logger logger = LoggerFactory.getLogger(ApiCronPushService.class);

    private final ApiEndpointRepository endpointRepository;
    private final ConnectionRepository connectionRepository;
    private final ConnectionManagerService connectionManagerService;
    private final NotificationService notificationService;
    private final TaskScheduler taskScheduler;

    private final Map<String, List<ScheduledFuture<?>>> scheduledTasks = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .build();

    @Autowired
    public ApiCronPushService(ApiEndpointRepository endpointRepository,
                              ConnectionRepository connectionRepository,
                              ConnectionManagerService connectionManagerService,
                              NotificationService notificationService,
                              TaskScheduler taskScheduler) {
        this.endpointRepository = endpointRepository;
        this.connectionRepository = connectionRepository;
        this.connectionManagerService = connectionManagerService;
        this.notificationService = notificationService;
        this.taskScheduler = taskScheduler;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initAllCronPushes() {
        logger.info("Initializing API Builder Scheduled Pushes (Spring Cron)...");
        List<ApiEndpoint> all = endpointRepository.findAll();
        for (ApiEndpoint ep : all) {
            if (ep.isCronEnabled() && ep.getCronExpression() != null && !ep.getCronExpression().trim().isEmpty()) {
                refreshSchedule(ep.getId());
            }
        }
    }

    public void refreshSchedule(String endpointId) {
        // Cancel existing scheduled tasks
        List<ScheduledFuture<?>> existing = scheduledTasks.remove(endpointId);
        if (existing != null) {
            for (ScheduledFuture<?> f : existing) {
                if (f != null) f.cancel(false);
            }
        }

        Optional<ApiEndpoint> opt = endpointRepository.findById(endpointId);
        if (opt.isEmpty()) return;
        ApiEndpoint ep = opt.get();
        if (!ep.isCronEnabled() || ep.getCronExpression() == null || ep.getCronExpression().trim().isEmpty()) {
            return;
        }

        Runnable task = () -> executePush(endpointId);

        String rawCrons = ep.getCronExpression().trim();
        String[] cronList = rawCrons.split("[;,\n]+");
        List<ScheduledFuture<?>> futures = new ArrayList<>();

        for (String rawCron : cronList) {
            String cron = rawCron.trim();
            if (cron.isEmpty()) continue;
            try {
                ScheduledFuture<?> future = taskScheduler.schedule(task, new CronTrigger(cron));
                futures.add(future);
                logger.info("Scheduled API Builder push for [{}] ({}) with cron: {}", ep.getName(), ep.getEndpointPath(), cron);
            } catch (Exception e) {
                logger.error("Failed to schedule API Builder push for [{}] with cron [{}]: {}", ep.getName(), cron, e.getMessage());
            }
        }

        if (!futures.isEmpty()) {
            scheduledTasks.put(endpointId, futures);
        }
    }

    public void cancelSchedule(String endpointId) {
        List<ScheduledFuture<?>> existing = scheduledTasks.remove(endpointId);
        if (existing != null) {
            for (ScheduledFuture<?> f : existing) {
                if (f != null) f.cancel(false);
            }
        }
        logger.info("Cancelled API Builder cron push for endpoint ID: {}", endpointId);
    }

    public Map<String, Object> executePush(String endpointId) {
        Optional<ApiEndpoint> opt = endpointRepository.findById(endpointId);
        if (opt.isEmpty()) {
            logger.warn("API Endpoint not found for push: {}", endpointId);
            return Map.of("success", false, "error", "Endpoint not found");
        }
        return executePushInternal(opt.get(), Collections.emptyMap());
    }

    public Map<String, Object> executePushInternal(ApiEndpoint ep, Map<String, Object> extraParams) {
        String targetUrl = ep.getTargetUrl();

        ConnectionDetails conn = connectionRepository.findById(ep.getConnectionId());
        if (conn == null) {
            String err = "Database connection not found (ID: " + ep.getConnectionId() + ") for " + ep.getName();
            endpointRepository.updatePushResult(ep.getId(), "FAILED", err);
            if (ep.isNotifyOnFailure()) {
                sendFailureNotification(ep, targetUrl, 0, err);
            }
            return Map.of("success", false, "error", err);
        }

        long start = System.currentTimeMillis();
        int rowCount = 0;
        try {
            DataSource ds = connectionManagerService.getDataSource(conn);
            NamedParameterJdbcTemplate jdbcTemplate = new NamedParameterJdbcTemplate(ds);

            Map<String, Object> paramMap = new HashMap<>();
            if (extraParams != null) {
                paramMap.putAll(extraParams);
            }

            // Extract default parameter values from endpoint parameters JSON if present
            if (ep.getParameters() != null && !ep.getParameters().trim().isEmpty()) {
                try {
                    List<Map<String, Object>> paramsList = objectMapper.readValue(ep.getParameters(), new TypeReference<>() {});
                    for (Map<String, Object> p : paramsList) {
                        String name = (String) p.get("name");
                        Object def = p.get("defaultValue");
                        if (name != null && !paramMap.containsKey(name)) {
                            paramMap.put(name, def != null ? def : "");
                        }
                    }
                } catch (Exception ignored) {}
            }

            List<Map<String, Object>> rows = jdbcTemplate.queryForList(ep.getSqlQuery(), paramMap);
            rowCount = rows.size();
            long duration = System.currentTimeMillis() - start;

            // ── CASE A: Direct SQL Schedule (No Target Endpoint URL) ─────────────
            if (targetUrl == null || targetUrl.trim().isEmpty()) {
                String successMsg = "SQL Query executed successfully: " + rowCount + " rows returned (" + duration + "ms)";
                endpointRepository.updatePushResult(ep.getId(), "SUCCESS", successMsg);
                logger.info("API Builder Scheduled SQL SUCCESS for [{}] ({}): {}", ep.getName(), ep.getEndpointPath(), successMsg);

                if (ep.isNotifyOnSuccess()) {
                    sendSuccessNotification(ep, null, rowCount, duration, rows);
                }

                Map<String, Object> res = new HashMap<>();
                res.put("success", true);
                res.put("durationMs", duration);
                res.put("rowCount", rowCount);
                res.put("message", successMsg);
                res.put("data", rows.size() > 50 ? rows.subList(0, 50) : rows);
                return res;
            }

            // ── CASE B: HTTP Push to Target Endpoint ─────────────────────────────
            String jsonPayload = objectMapper.writeValueAsString(rows);

            // Build HTTP Request
            String method = ep.getTargetMethod() != null ? ep.getTargetMethod().toUpperCase().trim() : "POST";
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(targetUrl.trim()))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json");

            // Apply custom target headers
            if (ep.getTargetHeaders() != null && !ep.getTargetHeaders().trim().isEmpty()) {
                try {
                    Map<String, Object> headerMap = objectMapper.readValue(ep.getTargetHeaders(), new TypeReference<>() {});
                    for (Map.Entry<String, Object> entry : headerMap.entrySet()) {
                        if (entry.getKey() != null && entry.getValue() != null) {
                            reqBuilder.header(entry.getKey(), String.valueOf(entry.getValue()));
                        }
                    }
                } catch (Exception ignored) {}
            }

            if ("PUT".equals(method)) {
                reqBuilder.PUT(HttpRequest.BodyPublishers.ofString(jsonPayload));
            } else {
                reqBuilder.POST(HttpRequest.BodyPublishers.ofString(jsonPayload));
            }

            HttpResponse<String> resp = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
            duration = System.currentTimeMillis() - start;

            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                String successMsg = "HTTP " + resp.statusCode() + " OK - Sent " + rowCount + " rows successfully (" + duration + "ms)";
                endpointRepository.updatePushResult(ep.getId(), "SUCCESS", successMsg);
                logger.info("API Builder Push SUCCESS for [{}] to [{}]: {}", ep.getName(), targetUrl, successMsg);

                if (ep.isNotifyOnSuccess()) {
                    sendSuccessNotification(ep, targetUrl, rowCount, duration, rows);
                }

                return Map.of(
                        "success", true,
                        "statusCode", resp.statusCode(),
                        "durationMs", duration,
                        "rowCount", rowCount,
                        "message", successMsg,
                        "responseBody", resp.body() != null && resp.body().length() > 500 ? resp.body().substring(0, 500) + "..." : (resp.body() == null ? "" : resp.body())
                );
            } else {
                String errorMsg = "HTTP " + resp.statusCode() + " - " + (resp.body() != null && resp.body().length() > 200 ? resp.body().substring(0, 200) + "..." : resp.body());
                endpointRepository.updatePushResult(ep.getId(), "FAILED", errorMsg);
                logger.warn("API Builder Push FAILED for [{}] to [{}]: {}", ep.getName(), targetUrl, errorMsg);

                if (ep.isNotifyOnFailure()) {
                    sendFailureNotification(ep, targetUrl, rowCount, errorMsg);
                }

                return Map.of(
                        "success", false,
                        "statusCode", resp.statusCode(),
                        "durationMs", duration,
                        "rowCount", rowCount,
                        "error", errorMsg,
                        "responseBody", resp.body() != null && resp.body().length() > 500 ? resp.body().substring(0, 500) + "..." : (resp.body() == null ? "" : resp.body())
                );
            }

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            String errorMsg = "Execution Error: " + (e.getMessage() != null ? e.getMessage() : e.toString());
            endpointRepository.updatePushResult(ep.getId(), "FAILED", errorMsg);
            logger.error("API Builder Push EXCEPTION for [{}] to [{}]: {}", ep.getName(), targetUrl, errorMsg, e);

            if (ep.isNotifyOnFailure()) {
                sendFailureNotification(ep, targetUrl, rowCount, errorMsg);
            }

            return Map.of(
                    "success", false,
                    "durationMs", duration,
                    "rowCount", rowCount,
                    "error", errorMsg
            );
        }
    }

    public void sendSuccessNotification(ApiEndpoint ep, String targetUrl, int rowCount, long durationMs, List<Map<String, Object>> rows) {
        String channelIds = ep.getNotificationChannelId();
        if (channelIds == null || channelIds.trim().isEmpty()) {
            return;
        }

        String nowStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        String targetInfo = (targetUrl != null && !targetUrl.trim().isEmpty())
                ? "• <b>Target Webhook:</b> " + targetUrl + " [" + (ep.getTargetMethod() != null ? ep.getTargetMethod().toUpperCase() : "POST") + "]\n"
                : "• <b>Mode:</b> Direct SQL Schedule (Eksekusi Database)\n";

        // Generate small preview
        StringBuilder preview = new StringBuilder();
        if (rows != null && !rows.isEmpty()) {
            int limit = Math.min(rows.size(), 3);
            for (int i = 0; i < limit; i++) {
                try {
                    preview.append(objectMapper.writeValueAsString(rows.get(i))).append("\n");
                } catch (Exception ignored) {}
            }
            if (rows.size() > 3) {
                preview.append("... (+").append(rows.size() - 3).append(" baris lainnya)\n");
            }
        } else {
            preview.append("(0 baris returned / query executed)");
        }

        String message = String.format(
                "✅ <b>[API Builder] Jadwal Eksekusi Berhasil</b>\n\n" +
                "• <b>API Endpoint:</b> %s (<code>%s</code>)\n" +
                "%s" +
                "• <b>Jumlah Baris:</b> %d baris\n" +
                "• <b>Durasi:</b> %d ms\n" +
                "• <b>Waktu:</b> %s\n\n" +
                "<b>Preview Hasil:</b>\n<pre>%s</pre>",
                ep.getName(), ep.getEndpointPath(),
                targetInfo,
                rowCount,
                durationMs,
                nowStr,
                preview.toString().trim()
        );

        String[] channels = channelIds.split("[;,]+");
        for (String chan : channels) {
            String clean = chan.trim();
            if (!clean.isEmpty()) {
                try {
                    notificationService.sendToChannel(clean, message);
                } catch (Exception e) {
                    logger.error("Failed to send success alert to channel [{}]: {}", clean, e.getMessage());
                }
            }
        }
    }

    public void sendFailureNotification(ApiEndpoint ep, String targetUrl, int rowCount, String error) {
        String channelIds = ep.getNotificationChannelId();
        if (channelIds == null || channelIds.trim().isEmpty()) {
            return;
        }

        String nowStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        String targetInfo = (targetUrl != null && !targetUrl.trim().isEmpty())
                ? "• <b>Target Webhook:</b> " + targetUrl + " [" + (ep.getTargetMethod() != null ? ep.getTargetMethod().toUpperCase() : "POST") + "]\n"
                : "• <b>Mode:</b> Direct SQL Schedule\n";

        String message = String.format(
                "🚨 <b>[API Builder Alert] Jadwal Eksekusi Gagal</b>\n\n" +
                "• <b>API Endpoint:</b> %s (<code>%s</code>)\n" +
                "%s" +
                "• <b>Baris:</b> %d baris\n" +
                "• <b>Waktu:</b> %s\n" +
                "• <b>Status / Error:</b> <pre>%s</pre>\n\n" +
                "<i>Mohon periksa sintaks SQL atau status target endpoint Anda.</i>",
                ep.getName(), ep.getEndpointPath(),
                targetInfo,
                rowCount,
                nowStr,
                error
        );

        String[] channels = channelIds.split("[;,]+");
        for (String chan : channels) {
            String clean = chan.trim();
            if (!clean.isEmpty()) {
                try {
                    notificationService.sendToChannel(clean, message);
                } catch (Exception e) {
                    logger.error("Failed to send push failure alert to channel [{}]: {}", clean, e.getMessage());
                }
            }
        }
    }
}
