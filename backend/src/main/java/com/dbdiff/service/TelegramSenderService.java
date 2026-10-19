package com.dbdiff.service;

import com.dbdiff.model.*;
import com.dbdiff.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class TelegramSenderService {

    private static final Logger logger = LoggerFactory.getLogger(TelegramSenderService.class);
    private static final int MAX_TELEGRAM_MSG_LEN = 4000; // Safety limit below 4096

    private final TelegramBotConfigRepository botConfigRepository;
    private final TelegramContactRepository contactRepository;
    private final TelegramLogRepository logRepository;
    private final ConnectionRepository connectionRepository;
    private final ConnectionManagerService connectionManagerService;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Autowired
    public TelegramSenderService(TelegramBotConfigRepository botConfigRepository,
                                 TelegramContactRepository contactRepository,
                                 TelegramLogRepository logRepository,
                                 ConnectionRepository connectionRepository,
                                 ConnectionManagerService connectionManagerService) {
        this.botConfigRepository = botConfigRepository;
        this.contactRepository = contactRepository;
        this.logRepository = logRepository;
        this.connectionRepository = connectionRepository;
        this.connectionManagerService = connectionManagerService;
        this.objectMapper = new ObjectMapper();

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(8000);
        factory.setReadTimeout(12000);
        this.restTemplate = new RestTemplate(factory);
    }

    /**
     * Executes SQL query from specified connection and returns rows.
     */
    public List<Map<String, Object>> executeQuery(String connectionId, String sql, int maxRows) throws Exception {
        ConnectionDetails details = connectionRepository.findById(connectionId);
        if (details == null) {
            throw new IllegalArgumentException("Connection not found with id: " + connectionId);
        }

        DataSource ds = connectionManagerService.getDataSource(details);
        List<Map<String, Object>> rows = new ArrayList<>();

        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {

            if (maxRows > 0) {
                ps.setMaxRows(maxRows);
            }

            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData meta = rs.getMetaData();
                int colCount = meta.getColumnCount();
                List<String> columnNames = new ArrayList<>();
                for (int i = 1; i <= colCount; i++) {
                    columnNames.add(meta.getColumnLabel(i));
                }

                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (String col : columnNames) {
                        row.put(col, rs.getObject(col));
                    }
                    rows.add(row);
                    if (maxRows > 0 && rows.size() >= maxRows) {
                        break;
                    }
                }
            }
        }
        return rows;
    }

    public String renderTemplate(String template, Map<String, Object> row) {
        if (row == null) return "";

        // If template is empty or {{pesan}}, directly use the pre-formatted 'pesan' column from the view
        if (template == null || template.trim().isEmpty() || template.trim().equalsIgnoreCase("{{pesan}}")) {
            Object directPesan = row.get("pesan");
            if (directPesan == null) directPesan = row.get("PESAN");
            if (directPesan != null) {
                return String.valueOf(directPesan);
            }
        }

        // Build case-insensitive lookup map
        Map<String, String> lookup = new HashMap<>();
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            lookup.put(entry.getKey().toLowerCase(), entry.getValue() != null ? String.valueOf(entry.getValue()) : "");
        }

        Pattern pattern = Pattern.compile("\\{\\{\\s*([a-zA-Z0-9_]+)\\s*\\}\\}");
        Matcher matcher = pattern.matcher(template);
        StringBuffer sb = new StringBuffer();

        while (matcher.find()) {
            String colName = matcher.group(1).toLowerCase();
            String replacement = lookup.getOrDefault(colName, "");
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * Splits long text intelligently so Telegram does not truncate messages.
     */
    public List<String> splitMessageIfNeeded(String text) {
        if (text == null || text.length() <= MAX_TELEGRAM_MSG_LEN) {
            return Collections.singletonList(text != null ? text : "");
        }

        List<String> parts = new ArrayList<>();
        int length = text.length();
        int start = 0;

        while (start < length) {
            int end = Math.min(start + MAX_TELEGRAM_MSG_LEN, length);

            // If not at the end of text, find nearest newline or whitespace to split cleanly
            if (end < length) {
                int lastNewline = text.lastIndexOf('\n', end);
                if (lastNewline > start + 500) {
                    end = lastNewline;
                } else {
                    int lastSpace = text.lastIndexOf(' ', end);
                    if (lastSpace > start + 500) {
                        end = lastSpace;
                    }
                }
            }

            parts.add(text.substring(start, end).trim());
            start = end;
            while (start < length && (text.charAt(start) == '\n' || text.charAt(start) == ' ')) {
                start++;
            }
        }

        // Add part numbering if more than 1 part
        if (parts.size() > 1) {
            List<String> numberedParts = new ArrayList<>();
            for (int i = 0; i < parts.size(); i++) {
                numberedParts.add(String.format("%s\n\n<i>[Bagian %d/%d]</i>", parts.get(i), i + 1, parts.size()));
            }
            return numberedParts;
        }

        return parts;
    }

    /**
     * Main send dispatcher: executes query, renders templates, sends to recipients, logs result.
     */
    public TelegramLog executeAndSend(String scheduleId, String scheduleName, String botId,
                                      String connectionId, String sqlQuery, String template,
                                      String recipientMode, String masterRecipientIds,
                                      String phoneColumnName, int maxRows, String triggerType) {

        String executionId = UUID.randomUUID().toString();
        TelegramLog log = new TelegramLog();
        log.setExecutionId(executionId);
        log.setScheduleId(scheduleId);
        log.setScheduleName(scheduleName != null ? scheduleName : "Manual Execution");
        log.setBotId(botId);
        log.setTriggerType(triggerType);

        TelegramBotConfig bot = botConfigRepository.findById(botId);
        if (bot == null) {
            log.setBotName("Unknown");
            log.setStatus("FAILED");
            log.setDetails("Bot configuration not found for ID: " + botId);
            return logRepository.save(log);
        }
        log.setBotName(bot.getName());

        List<Map<String, Object>> rows;
        try {
            int limit = maxRows > 0 ? maxRows : 50;
            rows = executeQuery(connectionId, sqlQuery, limit);
        } catch (Exception e) {
            logger.error("Failed to execute query for Telegram send: {}", e.getMessage(), e);
            log.setStatus("GAGAL");
            log.setRecipientName("-");
            log.setRecipientPhone("-");
            log.setMessageText("-");
            log.setDetails("Query execution failed: " + e.getMessage());
            return logRepository.save(log);
        }

        log.setTotalRows(rows.size());
        if (rows.isEmpty()) {
            log.setStatus("BERHASIL");
            log.setRecipientName("-");
            log.setRecipientPhone("-");
            log.setMessageText("-");
            log.setDetails("Query dieksekusi berhasil, namun tidak ada baris data (0 baris). Tidak ada pesan yang dikirim.");
            return logRepository.save(log);
        }

        // Resolve Master Recipients if in MASTER mode
        List<TelegramContact> masterContacts = new ArrayList<>();
        if ("MASTER".equalsIgnoreCase(recipientMode) && masterRecipientIds != null && !masterRecipientIds.trim().isEmpty()) {
            String[] ids = masterRecipientIds.split(",");
            for (String rawId : ids) {
                String id = rawId.trim();
                if (id.isEmpty()) continue;
                TelegramContact c = contactRepository.findById(id);
                if (c == null) {
                    // Try looking up by chatId
                    c = contactRepository.findByBotIdAndChatId(botId, id);
                }
                if (c != null && c.isActive() && c.getChatId() != null && !c.getChatId().isEmpty()) {
                    masterContacts.add(c);
                }
            }
        }

        int sentCount = 0;
        int failedCount = 0;
        List<String> errorDetails = new ArrayList<>();

        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            Map<String, Object> row = rows.get(rowIndex);
            String renderedMsg = renderTemplate(template, row);
            List<String> msgChunks = splitMessageIfNeeded(renderedMsg);

            if ("MASTER".equalsIgnoreCase(recipientMode)) {
                if (masterContacts.isEmpty()) {
                    failedCount++;
                    TelegramLog rowLog = new TelegramLog();
                    rowLog.setExecutionId(executionId);
                    rowLog.setScheduleId(scheduleId);
                    rowLog.setScheduleName(scheduleName != null ? scheduleName : "Manual Execution");
                    rowLog.setBotId(botId);
                    rowLog.setBotName(bot.getName());
                    rowLog.setTriggerType(triggerType);
                    rowLog.setRecipientName("Master Mode");
                    rowLog.setRecipientPhone("-");
                    rowLog.setMessageText(renderedMsg);
                    rowLog.setStatus("GAGAL");
                    rowLog.setDetails("Belum ada kontak master yang dipilih/aktif");
                    rowLog.setMessagesSent(0);
                    rowLog.setMessagesFailed(1);
                    rowLog.setTotalRows(1);
                    logRepository.save(rowLog);
                } else {
                    for (TelegramContact c : masterContacts) {
                        boolean allChunksSuccess = true;
                        String sendErr = null;
                        for (String chunk : msgChunks) {
                            boolean success = sendTelegramMessage(bot.getBotToken(), c.getChatId(), chunk);
                            if (!success) {
                                allChunksSuccess = false;
                                sendErr = "Gagal kirim ke Telegram API (chat_id: " + c.getChatId() + ")";
                            }
                            try { Thread.sleep(100); } catch (InterruptedException ignored) {}
                        }

                        TelegramLog rowLog = new TelegramLog();
                        rowLog.setExecutionId(executionId);
                        rowLog.setScheduleId(scheduleId);
                        rowLog.setScheduleName(scheduleName != null ? scheduleName : "Manual Execution");
                        rowLog.setBotId(botId);
                        rowLog.setBotName(bot.getName());
                        rowLog.setTriggerType(triggerType);
                        rowLog.setRecipientName(c.getFirstName() != null ? c.getFirstName() : "Master Contact");
                        rowLog.setRecipientPhone(c.getPhoneNumber() != null ? c.getPhoneNumber() : "-");
                        rowLog.setMessageText(renderedMsg);
                        if (allChunksSuccess) {
                            sentCount++;
                            rowLog.setStatus("BERHASIL");
                            rowLog.setDetails("Terkirim ke Telegram (chat_id: " + c.getChatId() + ")");
                            rowLog.setMessagesSent(1);
                            rowLog.setMessagesFailed(0);
                        } else {
                            failedCount++;
                            rowLog.setStatus("GAGAL");
                            rowLog.setDetails(sendErr != null ? sendErr : "Gagal kirim via Telegram API");
                            rowLog.setMessagesSent(0);
                            rowLog.setMessagesFailed(1);
                        }
                        rowLog.setTotalRows(1);
                        logRepository.save(rowLog);
                    }
                }
            } else if ("DYNAMIC_COLUMN".equalsIgnoreCase(recipientMode)) {
                String targetCol = (phoneColumnName != null && !phoneColumnName.trim().isEmpty()) ? phoneColumnName : "user_hp";
                Object rawPhone = row.get(targetCol);
                if (rawPhone == null) rawPhone = row.get("user_hp");
                if (rawPhone == null) rawPhone = row.get("USER_HP");
                if (rawPhone == null) {
                    // Check case-insensitive
                    for (Map.Entry<String, Object> entry : row.entrySet()) {
                        if (entry.getKey().equalsIgnoreCase(targetCol) || entry.getKey().equalsIgnoreCase("user_hp")) {
                            rawPhone = entry.getValue();
                            break;
                        }
                    }
                }

                Object userName = row.get("user_name");
                if (userName == null) userName = row.get("USER_NAME");
                String userLabel = userName != null ? String.valueOf(userName) : ("Row " + (rowIndex + 1));

                if (rawPhone != null && !String.valueOf(rawPhone).trim().isEmpty()) {
                    String cleanPhone = String.valueOf(rawPhone).replaceAll("[^0-9]", "");
                    TelegramContact c = contactRepository.findByBotIdAndPhone(botId, cleanPhone);
                    if (c != null && c.isActive() && c.getChatId() != null && !c.getChatId().trim().isEmpty()) {
                        boolean allChunksSuccess = true;
                        String sendErr = null;
                        for (String chunk : msgChunks) {
                            boolean success = sendTelegramMessage(bot.getBotToken(), c.getChatId(), chunk);
                            if (!success) {
                                allChunksSuccess = false;
                                sendErr = "Gagal kirim ke Telegram API (chat_id: " + c.getChatId() + ")";
                            }
                            try { Thread.sleep(100); } catch (InterruptedException ignored) {}
                        }

                        TelegramLog rowLog = new TelegramLog();
                        rowLog.setExecutionId(executionId);
                        rowLog.setScheduleId(scheduleId);
                        rowLog.setScheduleName(scheduleName != null ? scheduleName : "Manual Execution");
                        rowLog.setBotId(botId);
                        rowLog.setBotName(bot.getName());
                        rowLog.setTriggerType(triggerType);
                        rowLog.setRecipientName(userLabel);
                        rowLog.setRecipientPhone(cleanPhone);
                        rowLog.setMessageText(renderedMsg);
                        if (allChunksSuccess) {
                            sentCount++;
                            rowLog.setStatus("BERHASIL");
                            rowLog.setDetails("Terkirim ke Telegram (chat_id: " + c.getChatId() + ")");
                            rowLog.setMessagesSent(1);
                            rowLog.setMessagesFailed(0);
                        } else {
                            failedCount++;
                            errorDetails.add(String.format("[%s - HP: %s]: GAGAL - %s", userLabel, cleanPhone, sendErr));
                            rowLog.setStatus("GAGAL");
                            rowLog.setDetails(sendErr != null ? sendErr : "Gagal kirim via Telegram API");
                            rowLog.setMessagesSent(0);
                            rowLog.setMessagesFailed(1);
                        }
                        rowLog.setTotalRows(1);
                        logRepository.save(rowLog);
                    } else {
                        failedCount++;
                        String err = "Belum klik START pada bot Telegram ini";
                        errorDetails.add(String.format("[%s - HP: %s]: GAGAL - %s", userLabel, cleanPhone, err));

                        TelegramLog rowLog = new TelegramLog();
                        rowLog.setExecutionId(executionId);
                        rowLog.setScheduleId(scheduleId);
                        rowLog.setScheduleName(scheduleName != null ? scheduleName : "Manual Execution");
                        rowLog.setBotId(botId);
                        rowLog.setBotName(bot.getName());
                        rowLog.setTriggerType(triggerType);
                        rowLog.setRecipientName(userLabel);
                        rowLog.setRecipientPhone(cleanPhone);
                        rowLog.setMessageText(renderedMsg);
                        rowLog.setStatus("GAGAL");
                        rowLog.setDetails(err);
                        rowLog.setMessagesSent(0);
                        rowLog.setMessagesFailed(1);
                        rowLog.setTotalRows(1);
                        logRepository.save(rowLog);
                    }
                } else {
                    failedCount++;
                    String err = "Nomor HP kosong di data view";
                    errorDetails.add(String.format("[%s]: GAGAL - %s", userLabel, err));

                    TelegramLog rowLog = new TelegramLog();
                    rowLog.setExecutionId(executionId);
                    rowLog.setScheduleId(scheduleId);
                    rowLog.setScheduleName(scheduleName != null ? scheduleName : "Manual Execution");
                    rowLog.setBotId(botId);
                    rowLog.setBotName(bot.getName());
                    rowLog.setTriggerType(triggerType);
                    rowLog.setRecipientName(userLabel);
                    rowLog.setRecipientPhone("-");
                    rowLog.setMessageText(renderedMsg);
                    rowLog.setStatus("GAGAL");
                    rowLog.setDetails(err);
                    rowLog.setMessagesSent(0);
                    rowLog.setMessagesFailed(1);
                    rowLog.setTotalRows(1);
                    logRepository.save(rowLog);
                }
            }
        }

        log.setMessagesSent(sentCount);
        log.setMessagesFailed(failedCount);
        if (failedCount == 0 && sentCount > 0) {
            log.setStatus("BERHASIL");
            log.setDetails(String.format("Successfully sent %d message(s) across %d row(s).", sentCount, rows.size()));
        } else if (sentCount > 0 && failedCount > 0) {
            log.setStatus("PARTIAL");
            log.setDetails(String.format("Sent %d message(s), %d failed. Issues: %s",
                    sentCount, failedCount, String.join("; ", errorDetails.subList(0, Math.min(5, errorDetails.size())))));
        } else {
            log.setStatus(sentCount == 0 && failedCount == 0 ? "BERHASIL" : "GAGAL");
            log.setDetails(errorDetails.isEmpty() ? "No recipients matched or found." : String.join("; ", errorDetails));
        }

        return log;
    }

    private boolean sendTelegramMessage(String botToken, String chatId, String message) {
        if (botToken == null || botToken.isEmpty() || chatId == null || chatId.isEmpty()) {
            return false;
        }
        String url = String.format("https://api.telegram.org/bot%s/sendMessage", botToken);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        // Detect if message is primarily HTML or Markdown
        boolean hasHtmlTags = message.contains("<b>") || message.contains("<i>") || message.contains("<code>") || message.contains("<pre>");
        String primaryParseMode = hasHtmlTags ? "HTML" : "Markdown";

        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("chat_id", chatId);
            payload.put("text", message);
            payload.put("parse_mode", primaryParseMode);

            restTemplate.postForEntity(url, new HttpEntity<>(payload, headers), String.class);
            return true;
        } catch (Exception e) {
            // Fallback 1: If Markdown failed, try without parse_mode (pure plain text)
            try {
                Map<String, Object> fallbackPayload = new HashMap<>();
                fallbackPayload.put("chat_id", chatId);
                fallbackPayload.put("text", message);

                restTemplate.postForEntity(url, new HttpEntity<>(fallbackPayload, headers), String.class);
                return true;
            } catch (Exception ex) {
                logger.warn("Telegram sendMessage failed for chat {}: {}", chatId, ex.getMessage());
                return false;
            }
        }
    }
}
