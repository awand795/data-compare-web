package com.dbdiff.service;

import com.dbdiff.model.*;
import com.dbdiff.repository.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class TelegramWatcherService {

    private static final Logger logger = LoggerFactory.getLogger(TelegramWatcherService.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{\\{\\s*([a-zA-Z0-9_.-]+)\\s*\\}\\}");

    private final TaskScheduler taskScheduler;
    private final TelegramWatcherRepository watcherRepository;
    private final TelegramBotConfigRepository botConfigRepository;
    private final TelegramContactRepository contactRepository;
    private final TelegramLogRepository logRepository;
    private final ConnectionRepository connectionRepository;
    private final ConnectionManagerService connectionManagerService;
    private final TelegramSenderService senderService;

    private final Map<String, ScheduledFuture<?>> pollingTasks = new ConcurrentHashMap<>();
    private final Map<String, AtomicBoolean> pgListenerFlags = new ConcurrentHashMap<>();
    private final Map<String, Thread> pgListenerThreads = new ConcurrentHashMap<>();

    @Autowired
    public TelegramWatcherService(TaskScheduler taskScheduler,
                                  TelegramWatcherRepository watcherRepository,
                                  TelegramBotConfigRepository botConfigRepository,
                                  TelegramContactRepository contactRepository,
                                  TelegramLogRepository logRepository,
                                  ConnectionRepository connectionRepository,
                                  ConnectionManagerService connectionManagerService,
                                  TelegramSenderService senderService) {
        this.taskScheduler = taskScheduler;
        this.watcherRepository = watcherRepository;
        this.botConfigRepository = botConfigRepository;
        this.contactRepository = contactRepository;
        this.logRepository = logRepository;
        this.connectionRepository = connectionRepository;
        this.connectionManagerService = connectionManagerService;
        this.senderService = senderService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initWatchers() {
        try {
            List<TelegramWatcher> active = watcherRepository.findAllActive();
            logger.info("Initializing {} active Telegram real-time watchers...", active.size());
            for (TelegramWatcher w : active) {
                registerWatcher(w);
            }
        } catch (Exception e) {
            logger.error("Failed to initialize Telegram watchers: {}", e.getMessage());
        }
    }

    @PreDestroy
    public void shutdown() {
        new ArrayList<>(pollingTasks.keySet()).forEach(this::unregisterWatcher);
        new ArrayList<>(pgListenerFlags.keySet()).forEach(this::unregisterWatcher);
    }

    public synchronized void registerWatcher(TelegramWatcher w) {
        unregisterWatcher(w.getId());
        if (!w.isActive()) return;

        String type = w.getWatcherType().toUpperCase();
        if ("POLLING_WATERMARK".equals(type)) {
            int intervalSec = Math.max(2, w.getPollIntervalSeconds());
            ScheduledFuture<?> future = taskScheduler.scheduleWithFixedDelay(
                    () -> executePollingCycle(w.getId(), false),
                    Duration.ofSeconds(intervalSec)
            );
            pollingTasks.put(w.getId(), future);
            logger.info("Registered Telegram POLLING_WATERMARK watcher '{}' every {}s", w.getName(), intervalSec);
        } else if ("PG_LISTEN_NOTIFY".equals(type)) {
            AtomicBoolean running = new AtomicBoolean(true);
            pgListenerFlags.put(w.getId(), running);
            Thread t = new Thread(() -> runPgListenLoop(w.getId(), running), "pg-listen-" + w.getId());
            t.setDaemon(true);
            pgListenerThreads.put(w.getId(), t);
            t.start();
            logger.info("Started Telegram PG_LISTEN_NOTIFY listener '{}' on channel '{}'", w.getName(), w.getPgChannelName());
        }
    }

    public synchronized void unregisterWatcher(String watcherId) {
        ScheduledFuture<?> future = pollingTasks.remove(watcherId);
        if (future != null) {
            try { future.cancel(false); } catch (Exception ignored) {}
        }
        AtomicBoolean flag = pgListenerFlags.remove(watcherId);
        if (flag != null) {
            flag.set(false);
        }
        Thread t = pgListenerThreads.remove(watcherId);
        if (t != null) {
            try { t.interrupt(); } catch (Exception ignored) {}
        }
    }

    public Map<String, Object> executePollingCycle(String watcherId, boolean manualRun) {
        TelegramWatcher w = watcherRepository.findById(watcherId);
        if (w == null || (!w.isActive() && !manualRun)) {
            return Map.of("success", false, "message", "Watcher not found or inactive");
        }
        if (w.getConnectionId() == null || w.getSqlQuery() == null || w.getSqlQuery().trim().isEmpty()) {
            return Map.of("success", false, "message", "Connection ID and SQL Query are required");
        }

        try {
            ConnectionDetails conn = connectionRepository.findById(w.getConnectionId());
            if (conn == null) {
                throw new IllegalArgumentException("Connection not found: " + w.getConnectionId());
            }
            DataSource ds = connectionManagerService.getDataSource(conn);
            NamedParameterJdbcTemplate jdbc = new NamedParameterJdbcTemplate(ds);

            String baseSql = w.getSqlQuery().trim().replaceAll(";+$", "");
            String wmCol = w.getWatermarkColumn() != null ? w.getWatermarkColumn().trim() : "";
            String lastWm = w.getLastWatermark() != null ? w.getLastWatermark().trim() : "";

            // First-run safety: if watermarkColumn is set and lastWatermark is empty (and not manualRun),
            // capture current MAX(watermarkColumn) as baseline so historical rows don't flood Telegram.
            if (!wmCol.isEmpty() && lastWm.isEmpty() && !manualRun && !baseSql.contains(":last_watermark")) {
                String maxSql = "SELECT MAX(_w." + wmCol + ") FROM (" + baseSql + ") _w";
                Object maxVal = jdbc.queryForObject(maxSql, Collections.emptyMap(), Object.class);
                String initWm = maxVal != null ? maxVal.toString() : "0";
                watcherRepository.updateRuntimeState(w.getId(), initWm, 0, "INIT", "Baseline watermark initialized at " + initWm);
                return Map.of("success", true, "initializedWatermark", initWm, "rowsProcessed", 0);
            }

            List<Map<String, Object>> rows;
            if (baseSql.contains(":last_watermark")) {
                Map<String, Object> sqlParams = new HashMap<>();
                sqlParams.put("last_watermark", parseWatermarkValue(lastWm));
                rows = jdbc.queryForList(baseSql, sqlParams);
            } else if (!wmCol.isEmpty() && !lastWm.isEmpty()) {
                String wrappedSql = "SELECT * FROM (" + baseSql + ") _w WHERE _w." + wmCol + " > :last_watermark ORDER BY _w." + wmCol + " ASC";
                Map<String, Object> sqlParams = new HashMap<>();
                sqlParams.put("last_watermark", parseWatermarkValue(lastWm));
                rows = jdbc.queryForList(wrappedSql, sqlParams);
            } else {
                rows = jdbc.queryForList(baseSql, Collections.emptyMap());
            }

            if (rows.isEmpty()) {
                return Map.of("success", true, "rowsProcessed", 0, "messagesSent", 0);
            }

            if (rows.size() > 100) {
                rows = rows.subList(0, 100);
            }

            Map<String, Object> sendRes = processRows(w, rows, jdbc);
            String newestWm = lastWm;
            if (!wmCol.isEmpty()) {
                Object lastRowVal = getRowValueIgnoreCase(rows.get(rows.size() - 1), wmCol);
                if (lastRowVal != null) newestWm = lastRowVal.toString();
            }

            int sent = ((Number) sendRes.getOrDefault("messagesSent", 0)).intValue();
            int failed = ((Number) sendRes.getOrDefault("messagesFailed", 0)).intValue();
            String status = failed == 0 ? "SUCCESS" : (sent > 0 ? "PARTIAL" : "FAILED");
            watcherRepository.updateRuntimeState(w.getId(), newestWm, sent, status,
                    String.format("Processed %d new row(s): %d sent, %d failed", rows.size(), sent, failed));

            return sendRes;
        } catch (Exception ex) {
            logger.warn("Telegram watcher '{}' polling failed: {}", w.getName(), ex.getMessage());
            watcherRepository.updateRuntimeState(w.getId(), null, 0, "FAILED", ex.getMessage());
            return Map.of("success", false, "message", ex.getMessage() != null ? ex.getMessage() : "Error");
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> triggerByWebhook(String watcherId, Object payload) {
        TelegramWatcher w = watcherRepository.findById(watcherId);
        if (w == null) {
            return Map.of("success", false, "error", "Watcher not found: " + watcherId);
        }
        if (!w.isActive()) {
            return Map.of("success", false, "error", "Watcher is inactive");
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        if (payload instanceof List) {
            for (Object item : (List<?>) payload) {
                if (item instanceof Map) rows.add((Map<String, Object>) item);
            }
        } else if (payload instanceof Map) {
            Map<String, Object> mapPayload = (Map<String, Object>) payload;
            if (mapPayload.get("rows") instanceof List) {
                for (Object item : (List<?>) mapPayload.get("rows")) {
                    if (item instanceof Map) rows.add((Map<String, Object>) item);
                }
            } else if (mapPayload.get("data") instanceof Map) {
                rows.add((Map<String, Object>) mapPayload.get("data"));
            } else {
                rows.add(mapPayload);
            }
        }

        if (rows.isEmpty()) {
            return Map.of("success", false, "error", "Empty row payload");
        }

        List<Map<String, Object>> resolvedRows = enrichRowsFromViewQuery(w, rows);
        if (resolvedRows.isEmpty()) {
            return Map.of("success", true, "rowsProcessed", 0, "messagesSent", 0, "message", "VIEW lookup returned 0 matching rows");
        }

        Map<String, Object> res = processRows(w, resolvedRows, null);
        int sent = ((Number) res.getOrDefault("messagesSent", 0)).intValue();
        int failed = ((Number) res.getOrDefault("messagesFailed", 0)).intValue();
        String status = failed == 0 ? "SUCCESS" : (sent > 0 ? "PARTIAL" : "FAILED");
        watcherRepository.updateRuntimeState(w.getId(), null, sent, status,
                String.format("API Trigger: %d row(s), %d sent, %d failed", resolvedRows.size(), sent, failed));
        return res;
    }

    private void runPgListenLoop(String watcherId, AtomicBoolean running) {
        while (running.get()) {
            TelegramWatcher w = watcherRepository.findById(watcherId);
            if (w == null || !w.isActive() || w.getPgChannelName() == null || w.getPgChannelName().trim().isEmpty()) {
                return;
            }
            String channel = w.getPgChannelName().trim().replaceAll("[^a-zA-Z0-9_]", "");
            if (channel.isEmpty()) return;

            ConnectionDetails details = connectionRepository.findById(w.getConnectionId());
            if (details == null) {
                try { Thread.sleep(10000); } catch (InterruptedException e) { return; }
                continue;
            }

            try {
                DataSource ds = connectionManagerService.getDataSource(details);
                try (Connection conn = ds.getConnection();
                     Statement stmt = conn.createStatement()) {
                    stmt.execute("LISTEN " + channel);
                    Class<?> pgConnClass = Class.forName("org.postgresql.PGConnection");
                    Object pgConn = conn.unwrap(pgConnClass);
                    java.lang.reflect.Method getNotificationsMethod = pgConnClass.getMethod("getNotifications", int.class);
                    java.lang.reflect.Method getParamMethod = Class.forName("org.postgresql.PGNotification").getMethod("getParameter");
                    logger.info("PG_LISTEN_NOTIFY active on channel '{}' for watcher '{}'", channel, w.getName());

                    while (running.get()) {
                        Object[] notifications = (Object[]) getNotificationsMethod.invoke(pgConn, 3000);
                        if (notifications != null && notifications.length > 0) {
                            List<Map<String, Object>> batchRows = new ArrayList<>();
                            for (Object n : notifications) {
                                Object paramObj = getParamMethod.invoke(n);
                                String param = paramObj != null ? paramObj.toString() : null;
                                if (param != null && !param.trim().isEmpty()) {
                                    try {
                                        if (param.trim().startsWith("[")) {
                                            List<Map<String, Object>> list = mapper.readValue(param, new TypeReference<List<Map<String, Object>>>() {});
                                            batchRows.addAll(list);
                                        } else {
                                            Map<String, Object> row = mapper.readValue(param, new TypeReference<Map<String, Object>>() {});
                                            batchRows.add(row);
                                        }
                                    } catch (Exception parseEx) {
                                        batchRows.add(Map.of("payload", param));
                                    }
                                }
                            }
                            if (!batchRows.isEmpty()) {
                                TelegramWatcher latest = watcherRepository.findById(watcherId);
                                if (latest != null && latest.isActive()) {
                                    List<Map<String, Object>> resolvedRows = enrichRowsFromViewQuery(latest, batchRows);
                                    if (!resolvedRows.isEmpty()) {
                                        Map<String, Object> res = processRows(latest, resolvedRows, null);
                                        int sent = ((Number) res.getOrDefault("messagesSent", 0)).intValue();
                                        int failed = ((Number) res.getOrDefault("messagesFailed", 0)).intValue();
                                        watcherRepository.updateRuntimeState(watcherId, null, sent,
                                                failed == 0 ? "SUCCESS" : "PARTIAL",
                                                String.format("PG NOTIFY (%s): %d sent, %d failed", channel, sent, failed));
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (Exception ex) {
                if (!running.get()) return;
                logger.warn("PG_LISTEN_NOTIFY error for watcher '{}': {} (reconnecting in 5s)", watcherId, ex.getMessage());
                watcherRepository.updateRuntimeState(watcherId, null, 0, "RETRYING", ex.getMessage());
                try { Thread.sleep(5000); } catch (InterruptedException ie) { return; }
            }
        }
    }

    private Map<String, Object> processRows(TelegramWatcher w, List<Map<String, Object>> rows, NamedParameterJdbcTemplate jdbc) {
        TelegramBotConfig bot = botConfigRepository.findById(w.getBotId());
        if (bot == null || bot.getBotToken() == null || bot.getBotToken().trim().isEmpty()) {
            throw new IllegalArgumentException("Telegram Bot not found or token empty: " + w.getBotId());
        }

        int sentCount = 0;
        int failedCount = 0;
        String flagSql = w.getFlagUpdateSql() != null ? w.getFlagUpdateSql().trim() : "";

        for (Map<String, Object> row : rows) {
            String message = renderTemplate(w.getTemplateMessage(), row);
            List<String> chatIds = resolveRecipients(w, row);
            boolean rowSentAny = false;

            for (String chatId : chatIds) {
                boolean ok = senderService.sendTelegramMessage(bot.getBotToken(), chatId, message);
                if (ok) {
                    sentCount++;
                    rowSentAny = true;
                } else {
                    failedCount++;
                }
            }

            if (rowSentAny && !flagSql.isEmpty() && jdbc != null) {
                try {
                    jdbc.update(flagSql, row);
                } catch (Exception flagEx) {
                    logger.warn("Flag update SQL failed for watcher '{}': {}", w.getName(), flagEx.getMessage());
                }
            }
        }

        // Save to Telegram Logs
        try {
            TelegramLog logEntry = new TelegramLog();
            logEntry.setExecutionId("tgw-" + UUID.randomUUID().toString().substring(0, 8));
            logEntry.setScheduleId(w.getId());
            logEntry.setScheduleName("[Watcher] " + w.getName());
            logEntry.setBotName(bot.getName());
            logEntry.setTriggerType(w.getWatcherType());
            logEntry.setMessagesSent(sentCount);
            logEntry.setMessagesFailed(failedCount);
            logEntry.setStatus(failedCount == 0 && sentCount > 0 ? "BERHASIL" : (sentCount > 0 ? "PARTIAL" : "GAGAL"));
            logEntry.setDetails(String.format("%s: %d baris data baru -> %d terkirim, %d gagal",
                    w.getWatcherType(), rows.size(), sentCount, failedCount));
            logRepository.save(logEntry);
        } catch (Exception ignored) {}

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", failedCount == 0);
        out.put("rowsProcessed", rows.size());
        out.put("messagesSent", sentCount);
        out.put("messagesFailed", failedCount);
        return out;
    }

    private List<Map<String, Object>> enrichRowsFromViewQuery(TelegramWatcher w, List<Map<String, Object>> incomingRows) {
        String sql = w.getSqlQuery() != null ? w.getSqlQuery().trim() : "";
        String connId = w.getConnectionId() != null ? w.getConnectionId().trim() : "";
        if (sql.isEmpty() || connId.isEmpty()) {
            return incomingRows;
        }
        ConnectionDetails conn = connectionRepository.findById(connId);
        if (conn == null) {
            return incomingRows;
        }
        DataSource ds = connectionManagerService.getDataSource(conn);
        NamedParameterJdbcTemplate jdbc = new NamedParameterJdbcTemplate(ds);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> inRow : incomingRows) {
            try {
                List<Map<String, Object>> fetched = jdbc.queryForList(sql, inRow);
                result.addAll(fetched);
            } catch (Exception ex) {
                logger.warn("VIEW lookup SQL failed for watcher '{}': {}", w.getName(), ex.getMessage());
            }
        }
        return result;
    }

    private List<String> resolveRecipients(TelegramWatcher w, Map<String, Object> row) {
        List<String> result = new ArrayList<>();
        if ("DYNAMIC_COLUMN".equalsIgnoreCase(w.getRecipientMode())) {
            String col = w.getPhoneColumnName();
            if (col != null && !col.trim().isEmpty()) {
                Object val = getRowValueIgnoreCase(row, col.trim());
                if (val != null) {
                    List<TelegramContact> allContacts = contactRepository.findAll();
                    for (String part : val.toString().split("[,;\\s]+")) {
                        String trimmed = part.trim();
                        if (!trimmed.isEmpty()) {
                            String normTarget = normalizePhone(trimmed);
                            Optional<TelegramContact> matched = allContacts.stream()
                                    .filter(c -> trimmed.equals(c.getChatId())
                                            || trimmed.equals(c.getPhoneNumber())
                                            || (!normTarget.isEmpty() && normTarget.equals(normalizePhone(c.getPhoneNumber()))))
                                    .findFirst();
                            result.add(matched.map(TelegramContact::getChatId).orElse(trimmed));
                        }
                    }
                }
            }
        } else {
            String rawIds = w.getMasterRecipientIds();
            if (rawIds != null && !rawIds.trim().isEmpty()) {
                for (String token : rawIds.split("[,;\\s]+")) {
                    String trimmed = token.trim();
                    if (trimmed.isEmpty()) continue;
                    TelegramContact contact = contactRepository.findById(trimmed);
                    if (contact != null && contact.getChatId() != null) {
                        result.add(contact.getChatId());
                    } else {
                        result.add(trimmed);
                    }
                }
            }
        }
        return result;
    }

    private String renderTemplate(String template, Map<String, Object> row) {
        if (template == null) return "";
        Matcher m = PLACEHOLDER_PATTERN.matcher(template);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String key = m.group(1);
            Object val = getRowValueIgnoreCase(row, key);
            m.appendReplacement(sb, Matcher.quoteReplacement(val != null ? val.toString() : ""));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private Object getRowValueIgnoreCase(Map<String, Object> row, String key) {
        if (row == null || key == null) return null;
        if (row.containsKey(key)) return row.get(key);
        for (Map.Entry<String, Object> e : row.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(key)) {
                return e.getValue();
            }
        }
        return null;
    }

    private String normalizePhone(String raw) {
        if (raw == null) return "";
        String digits = raw.replaceAll("[^0-9]", "");
        if (digits.startsWith("0")) return "62" + digits.substring(1);
        return digits;
    }

    private Object parseWatermarkValue(String wm) {
        if (wm == null || wm.trim().isEmpty()) return 0L;
        String t = wm.trim();
        try {
            return Long.parseLong(t);
        } catch (NumberFormatException ignored) {}
        return t;
    }
}
