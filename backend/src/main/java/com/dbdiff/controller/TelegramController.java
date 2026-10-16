package com.dbdiff.controller;

import com.dbdiff.model.*;
import com.dbdiff.repository.*;
import com.dbdiff.service.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/telegram")
@CrossOrigin(origins = "*", allowedHeaders = "*", methods = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE, RequestMethod.OPTIONS})
public class TelegramController {

    private final TelegramBotConfigRepository botConfigRepository;
    private final TelegramContactRepository contactRepository;
    private final TelegramScheduleRepository scheduleRepository;
    private final TelegramLogRepository logRepository;
    private final TelegramPollingService pollingService;
    private final TelegramSenderService senderService;
    private final TelegramSchedulerService schedulerService;

    @Autowired
    public TelegramController(TelegramBotConfigRepository botConfigRepository,
                              TelegramContactRepository contactRepository,
                              TelegramScheduleRepository scheduleRepository,
                              TelegramLogRepository logRepository,
                              TelegramPollingService pollingService,
                              TelegramSenderService senderService,
                              TelegramSchedulerService schedulerService) {
        this.botConfigRepository = botConfigRepository;
        this.contactRepository = contactRepository;
        this.scheduleRepository = scheduleRepository;
        this.logRepository = logRepository;
        this.pollingService = pollingService;
        this.senderService = senderService;
        this.schedulerService = schedulerService;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // BOTS CONFIGURATION
    // ─────────────────────────────────────────────────────────────────────────────

    @GetMapping("/bots")
    public ResponseEntity<List<TelegramBotConfig>> getBots() {
        return ResponseEntity.ok(botConfigRepository.findAll());
    }

    @PostMapping("/bots")
    public ResponseEntity<?> createBot(@RequestBody TelegramBotConfig config) {
        if (config.getBotToken() == null || config.getBotToken().trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", true, "message", "Bot Token is required"));
        }
        // Fetch username automatically if not provided
        if (config.getBotUsername() == null || config.getBotUsername().isEmpty()) {
            Map<String, Object> info = pollingService.getBotInfo(config.getBotToken());
            if (info.containsKey("username")) {
                config.setBotUsername((String) info.get("username"));
            }
        }
        TelegramBotConfig saved = botConfigRepository.save(config);
        return ResponseEntity.ok(saved);
    }

    @PutMapping("/bots/{id}")
    public ResponseEntity<?> updateBot(@PathVariable String id, @RequestBody TelegramBotConfig config) {
        botConfigRepository.update(id, config);
        return ResponseEntity.ok(botConfigRepository.findById(id));
    }

    @PostMapping("/bots/test")
    public ResponseEntity<?> testBotToken(@RequestBody Map<String, String> payload) {
        String token = payload.get("botToken");
        if (token == null || token.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Token is empty"));
        }
        Map<String, Object> info = pollingService.getBotInfo(token);
        if (info.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Failed to connect to Telegram. Invalid token or network error."));
        }
        return ResponseEntity.ok(Map.of("success", true, "info", info));
    }

    @PutMapping("/bots/{id}/polling")
    public ResponseEntity<?> togglePolling(@PathVariable String id, @RequestBody Map<String, Boolean> payload) {
        boolean active = payload.getOrDefault("active", true);
        botConfigRepository.setPollingStatus(id, active);
        return ResponseEntity.ok(Map.of("success", true, "active", active));
    }

    @DeleteMapping("/bots/{id}")
    public ResponseEntity<?> deleteBot(@PathVariable String id) {
        botConfigRepository.delete(id);
        return ResponseEntity.ok(Map.of("success", true));
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // CONTACTS
    // ─────────────────────────────────────────────────────────────────────────────

    @GetMapping("/contacts")
    public ResponseEntity<List<TelegramContact>> getContacts(@RequestParam(required = false) String botId) {
        if (botId != null && !botId.trim().isEmpty()) {
            return ResponseEntity.ok(contactRepository.findByBotId(botId));
        }
        return ResponseEntity.ok(contactRepository.findAll());
    }

    @PostMapping("/contacts")
    public ResponseEntity<?> createContact(@RequestBody TelegramContact contact) {
        if (contact.getBotId() == null || contact.getChatId() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", true, "message", "botId and chatId are required"));
        }
        TelegramContact saved = contactRepository.upsertContact(contact);
        return ResponseEntity.ok(saved);
    }

    @PutMapping("/contacts/{id}")
    public ResponseEntity<?> updateContact(@PathVariable String id, @RequestBody TelegramContact contact) {
        contactRepository.updateManual(id, contact.getPhoneNumber(), contact.getFirstName(), contact.getLastName());
        return ResponseEntity.ok(contactRepository.findById(id));
    }

    @PutMapping("/contacts/{id}/toggle")
    public ResponseEntity<?> toggleContactActive(@PathVariable String id, @RequestBody Map<String, Boolean> payload) {
        boolean active = payload.getOrDefault("active", true);
        contactRepository.toggleActive(id, active);
        return ResponseEntity.ok(Map.of("success", true, "active", active));
    }

    @DeleteMapping("/contacts/{id}")
    public ResponseEntity<?> deleteContact(@PathVariable String id) {
        contactRepository.delete(id);
        return ResponseEntity.ok(Map.of("success", true));
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // PREVIEW & INSTANT SEND
    // ─────────────────────────────────────────────────────────────────────────────

    @PostMapping("/preview")
    public ResponseEntity<?> previewTemplate(@RequestBody Map<String, Object> payload) {
        String connectionId = (String) payload.get("connectionId");
        String sqlQuery = (String) payload.get("sqlQuery");
        String template = (String) payload.get("templateMessage");

        try {
            List<Map<String, Object>> rows = senderService.executeQuery(connectionId, sqlQuery, 1);
            if (rows.isEmpty()) {
                return ResponseEntity.ok(Map.of(
                        "success", true,
                        "rowCount", 0,
                        "renderedPreview", "(Query berhasil dijalankan tetapi menghasilkan 0 baris data)",
                        "sampleRow", Collections.emptyMap()
                ));
            }

            Map<String, Object> firstRow = rows.get(0);
            String rendered = senderService.renderTemplate(template, firstRow);
            List<String> chunks = senderService.splitMessageIfNeeded(rendered);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "rowCount", 1,
                    "renderedPreview", rendered,
                    "chunks", chunks,
                    "chunkCount", chunks.size(),
                    "columns", new ArrayList<>(firstRow.keySet()),
                    "sampleRow", firstRow
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "Gagal mengeksekusi preview SQL: " + e.getMessage()
            ));
        }
    }

    @PostMapping("/send-manual")
    public ResponseEntity<?> sendManual(@RequestBody Map<String, Object> payload) {
        String botId = (String) payload.get("botId");
        String connectionId = (String) payload.get("connectionId");
        String sqlQuery = (String) payload.get("sqlQuery");
        String template = (String) payload.get("templateMessage");
        String recipientMode = (String) payload.getOrDefault("recipientMode", "MASTER");
        String masterRecipientIds = (String) payload.get("masterRecipientIds");
        String phoneColumnName = (String) payload.get("phoneColumnName");
        int maxRows = payload.containsKey("maxRows") ? ((Number) payload.get("maxRows")).intValue() : 50;

        try {
            TelegramLog log = senderService.executeAndSend(
                    null,
                    "Manual Send from Dashboard",
                    botId,
                    connectionId,
                    sqlQuery,
                    template,
                    recipientMode,
                    masterRecipientIds,
                    phoneColumnName,
                    maxRows,
                    "MANUAL"
            );
            return ResponseEntity.ok(log);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "Failed to send messages: " + e.getMessage()
            ));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // SCHEDULES
    // ─────────────────────────────────────────────────────────────────────────────

    @GetMapping("/schedules")
    public ResponseEntity<List<TelegramSchedule>> getSchedules() {
        return ResponseEntity.ok(scheduleRepository.findAll());
    }

    @PostMapping("/schedules")
    public ResponseEntity<?> createSchedule(@RequestBody TelegramSchedule schedule) {
        TelegramSchedule saved = scheduleRepository.save(schedule);
        schedulerService.registerSchedule(saved);
        return ResponseEntity.ok(saved);
    }

    @PutMapping("/schedules/{id}")
    public ResponseEntity<?> updateSchedule(@PathVariable String id, @RequestBody TelegramSchedule schedule) {
        scheduleRepository.update(id, schedule);
        schedulerService.reloadSchedule(id);
        return ResponseEntity.ok(scheduleRepository.findById(id));
    }

    @PutMapping("/schedules/{id}/toggle")
    public ResponseEntity<?> toggleScheduleActive(@PathVariable String id, @RequestBody Map<String, Boolean> payload) {
        boolean active = payload.getOrDefault("active", true);
        scheduleRepository.toggleActive(id, active);
        schedulerService.reloadSchedule(id);
        return ResponseEntity.ok(Map.of("success", true, "active", active));
    }

    @PostMapping("/schedules/{id}/run-now")
    public ResponseEntity<?> runScheduleNow(@PathVariable String id) {
        TelegramLog log = schedulerService.executeScheduledTask(id);
        return ResponseEntity.ok(log != null ? log : Map.of("success", false, "message", "Schedule execution returned null"));
    }

    @DeleteMapping("/schedules/{id}")
    public ResponseEntity<?> deleteSchedule(@PathVariable String id) {
        schedulerService.cancelSchedule(id);
        scheduleRepository.delete(id);
        return ResponseEntity.ok(Map.of("success", true));
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // LOGS
    // ─────────────────────────────────────────────────────────────────────────────

    @GetMapping("/logs")
    public ResponseEntity<List<TelegramLog>> getLogs(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate
    ) {
        if (search != null || status != null || startDate != null || endDate != null) {
            return ResponseEntity.ok(logRepository.findWithFilters(search, status, startDate, endDate));
        }
        return ResponseEntity.ok(logRepository.findAll());
    }

    @DeleteMapping("/logs")
    public ResponseEntity<?> clearLogs() {
        logRepository.clearAll();
        return ResponseEntity.ok(Map.of("success", true));
    }
}
