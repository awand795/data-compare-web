package com.dbdiff.controller;

import com.dbdiff.model.TelegramApiToken;
import com.dbdiff.model.TelegramBotConfig;
import com.dbdiff.model.TelegramLog;
import com.dbdiff.model.TelegramSchedule;
import com.dbdiff.repository.TelegramApiTokenRepository;
import com.dbdiff.repository.TelegramBotConfigRepository;
import com.dbdiff.repository.TelegramScheduleRepository;
import com.dbdiff.service.TelegramSchedulerService;
import com.dbdiff.service.TelegramSenderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;

@RestController
@CrossOrigin(origins = "*", allowedHeaders = "*", methods = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE, RequestMethod.OPTIONS})
public class TelegramExternalController {

    private static final Logger logger = LoggerFactory.getLogger(TelegramExternalController.class);

    private final TelegramApiTokenRepository apiTokenRepository;
    private final TelegramSenderService senderService;
    private final TelegramBotConfigRepository botConfigRepository;
    private final TelegramScheduleRepository scheduleRepository;
    private final TelegramSchedulerService schedulerService;

    @Autowired
    public TelegramExternalController(TelegramApiTokenRepository apiTokenRepository,
                                      TelegramSenderService senderService,
                                      TelegramBotConfigRepository botConfigRepository,
                                      TelegramScheduleRepository scheduleRepository,
                                      TelegramSchedulerService schedulerService) {
        this.apiTokenRepository = apiTokenRepository;
        this.senderService = senderService;
        this.botConfigRepository = botConfigRepository;
        this.scheduleRepository = scheduleRepository;
        this.schedulerService = schedulerService;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // PUBLIC API FOR EXTERNAL INTEGRATION
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * 1. Send Telegram messages on-demand via direct query / view / dynamic template
     */
    @PostMapping({"/api/v1/telegram/external/send", "/api/telegram/external/send"})
    public ResponseEntity<?> handleExternalSend(
            HttpServletRequest request,
            @RequestBody Map<String, Object> payload) {

        Map<String, Object> authError = new LinkedHashMap<>();
        if (!authenticateRequest(request, authError)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(authError);
        }

        return executePayload(payload);
    }

    /**
     * 2. List all available active Telegram bots for external clients
     */
    @GetMapping({"/api/v1/telegram/external/bots", "/api/telegram/external/bots"})
    public ResponseEntity<?> handleExternalListBots(HttpServletRequest request) {
        Map<String, Object> authError = new LinkedHashMap<>();
        if (!authenticateRequest(request, authError)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(authError);
        }

        List<TelegramBotConfig> allBots = botConfigRepository.findAll();
        List<Map<String, Object>> botSummaries = new ArrayList<>();
        for (TelegramBotConfig b : allBots) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", b.getId());
            item.put("name", b.getName());
            item.put("botUsername", b.getBotUsername());
            item.put("isPollingActive", b.isPollingActive());
            botSummaries.add(item);
        }

        return ResponseEntity.ok(Map.of(
                "success", true,
                "total", botSummaries.size(),
                "bots", botSummaries
        ));
    }

    /**
     * 3. List all Telegram schedules (active and inactive) with current status
     */
    @GetMapping({"/api/v1/telegram/external/schedules", "/api/telegram/external/schedules"})
    public ResponseEntity<?> handleExternalListSchedules(
            HttpServletRequest request,
            @RequestParam(required = false) Boolean activeOnly) {

        Map<String, Object> authError = new LinkedHashMap<>();
        if (!authenticateRequest(request, authError)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(authError);
        }

        List<TelegramSchedule> list = (activeOnly != null && activeOnly)
                ? scheduleRepository.findAllActive()
                : scheduleRepository.findAll();

        return ResponseEntity.ok(Map.of(
                "success", true,
                "total", list.size(),
                "schedules", list
        ));
    }

    /**
     * 4. Create a new automated Telegram schedule with cron expression via API
     */
    @PostMapping({"/api/v1/telegram/external/schedules", "/api/telegram/external/schedules"})
    public ResponseEntity<?> handleExternalCreateSchedule(
            HttpServletRequest request,
            @RequestBody Map<String, Object> body) {

        Map<String, Object> authError = new LinkedHashMap<>();
        if (!authenticateRequest(request, authError)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(authError);
        }

        if (body == null) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "Payload body is required"));
        }

        String name = (String) body.get("name");
        if (name == null || name.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "Parameter 'name' is required"));
        }

        String cron = (String) body.get("cronExpression");
        if (cron == null || cron.trim().isEmpty()) {
            cron = (String) body.get("cron");
        }
        if (cron == null || cron.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "Parameter 'cronExpression' is required (e.g. '0 0 8 * * *')"));
        }

        // Resolve Bot ID or Bot Name
        String botIdentifier = (String) body.get("botId");
        if (botIdentifier == null || botIdentifier.trim().isEmpty()) {
            botIdentifier = (String) body.get("bot");
        }
        if (botIdentifier == null || botIdentifier.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "Parameter 'botId' or 'bot' is required"));
        }

        String finalBotId = botIdentifier;
        List<TelegramBotConfig> bots = botConfigRepository.findAll();
        for (TelegramBotConfig b : bots) {
            if (b.getName().equalsIgnoreCase(botIdentifier) || b.getId().equalsIgnoreCase(botIdentifier)) {
                finalBotId = b.getId();
                break;
            }
        }

        // Resolve Connection ID or Database Name
        String connIdentifier = (String) body.get("connectionId");
        if (connIdentifier == null || connIdentifier.trim().isEmpty()) {
            connIdentifier = (String) body.get("database");
        }
        if (connIdentifier == null || connIdentifier.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "Parameter 'connectionId' or 'database' is required"));
        }

        String sqlQuery = (String) body.get("sqlQuery");
        if (sqlQuery == null || sqlQuery.trim().isEmpty()) {
            sqlQuery = (String) body.get("query");
        }

        String viewName = (String) body.get("viewName");
        if (viewName == null || viewName.trim().isEmpty()) {
            viewName = (String) body.get("view");
        }

        if ((sqlQuery == null || sqlQuery.trim().isEmpty()) && (viewName == null || viewName.trim().isEmpty())) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "Either 'sqlQuery' or 'viewName' is required"));
        }

        String template = (String) body.get("templateMessage");
        if (template == null) {
            template = (String) body.get("template");
        }
        if (template == null) {
            template = "";
        }

        String recipientMode = (String) body.getOrDefault("recipientMode", "DYNAMIC_COLUMN");
        String phoneColumn = (String) body.get("phoneColumnName");
        if (phoneColumn == null || phoneColumn.trim().isEmpty()) {
            phoneColumn = (String) body.get("recipient_column");
        }
        if (phoneColumn == null || phoneColumn.trim().isEmpty()) {
            phoneColumn = "user_hp";
        }

        int maxRows = 100;
        if (body.containsKey("maxRows") && body.get("maxRows") instanceof Number) {
            maxRows = ((Number) body.get("maxRows")).intValue();
        } else if (body.containsKey("max_rows") && body.get("max_rows") instanceof Number) {
            maxRows = ((Number) body.get("max_rows")).intValue();
        }

        boolean active = true;
        if (body.containsKey("active") && body.get("active") instanceof Boolean) {
            active = (Boolean) body.get("active");
        }

        TelegramSchedule s = new TelegramSchedule();
        s.setName(name);
        s.setBotId(finalBotId);
        s.setConnectionId(connIdentifier);
        s.setSqlQuery(sqlQuery != null ? sqlQuery : "");
        s.setViewName(viewName);
        s.setTemplateMessage(template);
        s.setRecipientMode(recipientMode);
        s.setPhoneColumnName(phoneColumn);
        s.setCronExpression(cron);
        s.setMaxRows(maxRows);
        s.setActive(active);

        TelegramSchedule saved = scheduleRepository.save(s);
        schedulerService.registerSchedule(saved);

        logger.info("External API created and registered new Telegram Schedule '{}' with cron '{}'", saved.getName(), saved.getCronExpression());

        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Telegram schedule created and registered successfully",
                "schedule", saved
        ));
    }

    /**
     * 5. Toggle active status of a schedule via API
     */
    @PutMapping({"/api/v1/telegram/external/schedules/{id}/toggle", "/api/telegram/external/schedules/{id}/toggle"})
    public ResponseEntity<?> handleExternalToggleSchedule(
            HttpServletRequest request,
            @PathVariable String id,
            @RequestBody Map<String, Boolean> body) {

        Map<String, Object> authError = new LinkedHashMap<>();
        if (!authenticateRequest(request, authError)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(authError);
        }

        TelegramSchedule existing = scheduleRepository.findById(id);
        if (existing == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("success", false, "error", "Schedule not found with id: " + id));
        }

        boolean active = body != null && body.getOrDefault("active", true);
        scheduleRepository.toggleActive(id, active);
        schedulerService.reloadSchedule(id);

        return ResponseEntity.ok(Map.of(
                "success", true,
                "id", id,
                "active", active,
                "message", active ? "Schedule activated" : "Schedule paused"
        ));
    }

    /**
     * 6. Trigger immediate execution of a schedule (Run Now)
     */
    @PostMapping({"/api/v1/telegram/external/schedules/{id}/run-now", "/api/telegram/external/schedules/{id}/run-now"})
    public ResponseEntity<?> handleExternalRunScheduleNow(
            HttpServletRequest request,
            @PathVariable String id) {

        Map<String, Object> authError = new LinkedHashMap<>();
        if (!authenticateRequest(request, authError)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(authError);
        }

        TelegramSchedule existing = scheduleRepository.findById(id);
        if (existing == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("success", false, "error", "Schedule not found with id: " + id));
        }

        TelegramLog log = schedulerService.executeScheduledTask(id);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Schedule executed on demand",
                "log", log != null ? log : "Completed"
        ));
    }

    /**
     * 7. Delete a schedule via external API
     */
    @DeleteMapping({"/api/v1/telegram/external/schedules/{id}", "/api/telegram/external/schedules/{id}"})
    public ResponseEntity<?> handleExternalDeleteSchedule(
            HttpServletRequest request,
            @PathVariable String id) {

        Map<String, Object> authError = new LinkedHashMap<>();
        if (!authenticateRequest(request, authError)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(authError);
        }

        TelegramSchedule existing = scheduleRepository.findById(id);
        if (existing == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "success", false,
                    "error", "Schedule not found with id: " + id
            ));
        }

        try {
            schedulerService.cancelSchedule(id);
            scheduleRepository.delete(id);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Schedule '" + existing.getName() + "' (ID: " + id + ") has been deleted successfully"
            ));
        } catch (Exception ex) {
            logger.error("Failed to delete schedule {}: {}", id, ex.getMessage(), ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "success", false,
                    "error", "Failed to delete schedule: " + ex.getMessage()
            ));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // ADMIN UI ENDPOINTS (FOR DASHBOARD)
    // ─────────────────────────────────────────────────────────────────────────────

    @GetMapping("/api/telegram/external/tokens")
    public ResponseEntity<?> getTokens() {
        return ResponseEntity.ok(apiTokenRepository.findAll());
    }

    @PostMapping("/api/telegram/external/tokens/generate")
    public ResponseEntity<?> generateToken(@RequestBody(required = false) Map<String, String> body) {
        String name = (body != null && body.containsKey("name")) ? body.get("name") : "External API Token";
        TelegramApiToken newToken = apiTokenRepository.generateNewToken(name);
        return ResponseEntity.ok(newToken);
    }

    @PutMapping("/api/telegram/external/tokens/{id}/toggle")
    public ResponseEntity<?> toggleTokenActive(@PathVariable String id, @RequestBody Map<String, Boolean> body) {
        boolean active = body.getOrDefault("active", true);
        apiTokenRepository.toggleActive(id, active);
        return ResponseEntity.ok(Map.of("success", true, "active", active));
    }

    @DeleteMapping("/api/telegram/external/tokens/{id}")
    public ResponseEntity<?> deleteToken(@PathVariable String id) {
        apiTokenRepository.delete(id);
        return ResponseEntity.ok(Map.of("success", true));
    }

    @PostMapping("/api/telegram/external/test")
    public ResponseEntity<?> testFromDashboard(@RequestBody Map<String, Object> payload) {
        return executePayload(payload);
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // HELPER METHODS
    // ─────────────────────────────────────────────────────────────────────────────

    private boolean authenticateRequest(HttpServletRequest request, Map<String, Object> errorResp) {
        String token = extractToken(request);
        if (token == null || token.trim().isEmpty()) {
            errorResp.put("success", false);
            errorResp.put("error", "Unauthorized");
            errorResp.put("message", "Missing API Token. Please provide 'Authorization: Bearer <token>' or 'X-API-Key: <token>' header.");
            return false;
        }

        TelegramApiToken tokenEntity = apiTokenRepository.findByToken(token);
        if (tokenEntity == null || !tokenEntity.isActive()) {
            errorResp.put("success", false);
            errorResp.put("error", "Unauthorized");
            errorResp.put("message", "Invalid or inactive API Token.");
            return false;
        }

        apiTokenRepository.updateLastUsed(tokenEntity.getToken());
        return true;
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<?> executePayload(Map<String, Object> payload) {
        if (payload == null) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "Payload body is required"));
        }

        String dbIdentifier = (String) payload.get("database");
        if (dbIdentifier == null || dbIdentifier.trim().isEmpty()) {
            dbIdentifier = (String) payload.get("connection_id");
        }
        if (dbIdentifier == null || dbIdentifier.trim().isEmpty()) {
            dbIdentifier = (String) payload.get("connection");
        }

        String botIdentifier = (String) payload.get("bot");
        if (botIdentifier == null || botIdentifier.trim().isEmpty()) {
            botIdentifier = (String) payload.get("bot_id");
        }

        String sqlQuery = (String) payload.get("query");
        if (sqlQuery == null || sqlQuery.trim().isEmpty()) {
            sqlQuery = (String) payload.get("sql");
        }

        String viewName = (String) payload.get("view");
        String schemaName = (String) payload.get("schema");

        Map<String, Object> filters = null;
        if (payload.get("filters") instanceof Map) {
            filters = (Map<String, Object>) payload.get("filters");
        }

        Map<String, Object> params = null;
        if (payload.get("params") instanceof Map) {
            params = (Map<String, Object>) payload.get("params");
        }

        String recipientColumn = (String) payload.get("recipient_column");
        if (recipientColumn == null || recipientColumn.trim().isEmpty()) {
            recipientColumn = (String) payload.get("phone_column");
        }

        String messageColumn = (String) payload.get("message_column");
        String template = (String) payload.get("template");

        List<String> staticRecipients = null;
        if (payload.get("recipients") instanceof List) {
            staticRecipients = (List<String>) payload.get("recipients");
        }

        int maxRows = 50;
        if (payload.containsKey("max_rows") && payload.get("max_rows") instanceof Number) {
            maxRows = ((Number) payload.get("max_rows")).intValue();
        }

        try {
            Map<String, Object> result = senderService.executeExternalApi(
                    botIdentifier,
                    dbIdentifier,
                    sqlQuery,
                    viewName,
                    schemaName,
                    filters,
                    params,
                    recipientColumn,
                    messageColumn,
                    template,
                    staticRecipients,
                    maxRows
            );
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", "Bad Request",
                    "message", e.getMessage()
            ));
        } catch (Exception e) {
            logger.error("Failed to execute external Telegram API: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "success", false,
                    "error", "Execution Error",
                    "message", "Failed to execute query: " + e.getMessage()
            ));
        }
    }

    private String extractToken(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.trim().toLowerCase().startsWith("bearer ")) {
            return authHeader.substring(7).trim();
        }
        String apiKey = request.getHeader("X-API-Key");
        if (apiKey != null && !apiKey.trim().isEmpty()) {
            return apiKey.trim();
        }
        String paramToken = request.getParameter("token");
        if (paramToken != null && !paramToken.trim().isEmpty()) {
            return paramToken.trim();
        }
        return null;
    }
}
