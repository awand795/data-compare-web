package com.dbdiff.controller;

import com.dbdiff.model.TelegramApiToken;
import com.dbdiff.repository.TelegramApiTokenRepository;
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

    @Autowired
    public TelegramExternalController(TelegramApiTokenRepository apiTokenRepository,
                                      TelegramSenderService senderService) {
        this.apiTokenRepository = apiTokenRepository;
        this.senderService = senderService;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // PUBLIC API FOR EXTERNAL INTEGRATION
    // ─────────────────────────────────────────────────────────────────────────────

    @PostMapping({"/api/v1/telegram/external/send", "/api/telegram/external/send"})
    public ResponseEntity<?> handleExternalSend(
            HttpServletRequest request,
            @RequestBody Map<String, Object> payload) {

        // 1. Authenticate API Token
        String token = extractToken(request);
        if (token == null || token.trim().isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                    "success", false,
                    "error", "Unauthorized",
                    "message", "Missing API Token. Please provide 'Authorization: Bearer <token>' or 'X-API-Key: <token>' header."
            ));
        }

        TelegramApiToken tokenEntity = apiTokenRepository.findByToken(token);
        if (tokenEntity == null || !tokenEntity.isActive()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                    "success", false,
                    "error", "Unauthorized",
                    "message", "Invalid or inactive API Token."
            ));
        }

        // Update token last used
        apiTokenRepository.updateLastUsed(tokenEntity.getToken());

        return executePayload(payload);
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
