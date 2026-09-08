package com.dbdiff.controller;

import com.dbdiff.model.WebhookConfig;
import com.dbdiff.model.WebhookLog;
import com.dbdiff.repository.AppGroupRepository;
import com.dbdiff.repository.WebhookRepository;
import com.dbdiff.service.WebhookService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@CrossOrigin(origins = "*")
public class WebhookController {

    private static final Logger logger = LoggerFactory.getLogger(WebhookController.class);

    private final WebhookRepository webhookRepository;
    private final WebhookService webhookService;
    private final AppGroupRepository appGroupRepository;

    @Autowired
    public WebhookController(WebhookRepository webhookRepository,
                             WebhookService webhookService,
                             AppGroupRepository appGroupRepository) {
        this.webhookRepository = webhookRepository;
        this.webhookService = webhookService;
        this.appGroupRepository = appGroupRepository;
    }

    // ==========================================
    // Public Inbound Webhook Listener Endpoints
    // ==========================================

    @PostMapping(value = {"/api/v1/webhooks/catch/{slug}", "/api/webhooks/catch/{slug}"})
    public ResponseEntity<?> receiveWebhook(@PathVariable String slug,
                                           HttpServletRequest request,
                                           @RequestBody(required = false) String payload) {
        WebhookService.WebhookProcessResult result = webhookService.processIncomingWebhook(slug, request, payload);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", result.isSuccess());
        resp.put("statusCode", result.getStatusCode());
        resp.put("message", result.getMessage());
        resp.put("rowsInserted", result.getRowsInserted());
        resp.put("durationMs", result.getDurationMs());

        return ResponseEntity.status(result.getStatusCode()).body(resp);
    }

    @GetMapping(value = {"/api/v1/webhooks/catch/{slug}", "/api/webhooks/catch/{slug}"})
    public ResponseEntity<?> verifyWebhookEndpoint(@PathVariable String slug, HttpServletRequest request) {
        Optional<WebhookConfig> opt = webhookRepository.findBySlug(slug);
        if (opt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Webhook endpoint '" + slug + "' not found"));
        }
        WebhookConfig config = opt.get();
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("status", config.isActive() ? "active" : "inactive");
        resp.put("name", config.getName());
        resp.put("slug", config.getSlug());
        resp.put("message", "Darkosync Webhook listener endpoint is ready to receive POST requests");
        return ResponseEntity.ok(resp);
    }

    // ==========================================
    // Webhook Management Endpoints
    // ==========================================

    @GetMapping("/api/webhooks")
    public ResponseEntity<List<WebhookConfig>> getAllWebhooks() {
        return ResponseEntity.ok(webhookRepository.findAll());
    }

    @GetMapping("/api/webhooks/{id}")
    public ResponseEntity<?> getWebhookById(@PathVariable String id) {
        Optional<WebhookConfig> opt = webhookRepository.findById(id);
        if (opt.isPresent()) {
            return ResponseEntity.ok(opt.get());
        }
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Webhook not found"));
    }

    @PostMapping("/api/webhooks")
    public ResponseEntity<?> createWebhook(@RequestBody WebhookConfig config) {
        try {
            if (config.getId() == null || config.getId().trim().isEmpty()) {
                config.setId(UUID.randomUUID().toString());
            }
            if (config.getName() == null || config.getName().trim().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Webhook name is required"));
            }
            if (config.getSlug() == null || config.getSlug().trim().isEmpty()) {
                config.setSlug(generateSlug(config.getName()));
            } else {
                config.setSlug(sanitizeSlug(config.getSlug()));
            }

            // Check slug uniqueness
            Optional<WebhookConfig> existing = webhookRepository.findBySlug(config.getSlug());
            if (existing.isPresent()) {
                config.setSlug(config.getSlug() + "-" + System.currentTimeMillis() % 10000);
            }

            if (config.getGroupName() == null || config.getGroupName().trim().isEmpty()) {
                config.setGroupName("General");
            }
            if (appGroupRepository != null) {
                appGroupRepository.addGroup("WEBHOOK", config.getGroupName());
            }

            webhookRepository.insert(config);
            return ResponseEntity.ok(config);
        } catch (Exception e) {
            logger.error("Failed to create webhook: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage() != null ? e.getMessage() : "Unknown error"));
        }
    }

    @PutMapping("/api/webhooks/{id}")
    public ResponseEntity<?> updateWebhook(@PathVariable String id, @RequestBody WebhookConfig config) {
        try {
            config.setId(id);
            if (config.getName() == null || config.getName().trim().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Webhook name is required"));
            }
            if (config.getSlug() == null || config.getSlug().trim().isEmpty()) {
                config.setSlug(generateSlug(config.getName()));
            } else {
                config.setSlug(sanitizeSlug(config.getSlug()));
            }

            // Check slug uniqueness for other configs
            Optional<WebhookConfig> existing = webhookRepository.findBySlug(config.getSlug());
            if (existing.isPresent() && !existing.get().getId().equals(id)) {
                return ResponseEntity.badRequest().body(Map.of("error", "Slug '" + config.getSlug() + "' is already in use by another webhook"));
            }

            if (config.getGroupName() == null || config.getGroupName().trim().isEmpty()) {
                config.setGroupName("General");
            }
            if (appGroupRepository != null) {
                appGroupRepository.addGroup("WEBHOOK", config.getGroupName());
            }

            webhookRepository.update(config);
            return ResponseEntity.ok(config);
        } catch (Exception e) {
            logger.error("Failed to update webhook {}: {}", id, e.getMessage(), e);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage() != null ? e.getMessage() : "Unknown error"));
        }
    }

    @DeleteMapping("/api/webhooks/{id}")
    public ResponseEntity<?> deleteWebhook(@PathVariable String id) {
        try {
            webhookRepository.delete(id);
            return ResponseEntity.ok(Map.of("message", "Webhook deleted successfully", "id", id));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage() != null ? e.getMessage() : "Unknown error"));
        }
    }

    @PatchMapping("/api/webhooks/{id}/toggle-active")
    public ResponseEntity<?> toggleActive(@PathVariable String id, @RequestBody Map<String, Boolean> body) {
        Boolean active = body.get("active");
        if (active == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "active field is required"));
        }
        try {
            webhookRepository.updateActive(id, active);
            Optional<WebhookConfig> updated = webhookRepository.findById(id);
            return ResponseEntity.ok(updated.orElse(null));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage() != null ? e.getMessage() : "Unknown error"));
        }
    }

    @PatchMapping("/api/webhooks/{id}/group")
    public ResponseEntity<?> updateGroupName(@PathVariable String id, @RequestBody Map<String, String> body) {
        String groupName = body.get("groupName");
        try {
            webhookRepository.updateGroupName(id, groupName);
            if (appGroupRepository != null && groupName != null) {
                appGroupRepository.addGroup("WEBHOOK", groupName);
            }
            Optional<WebhookConfig> updated = webhookRepository.findById(id);
            return ResponseEntity.ok(updated.orElse(null));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage() != null ? e.getMessage() : "Unknown error"));
        }
    }

    @PutMapping("/api/webhooks/groups/rename")
    public ResponseEntity<?> renameGroup(@RequestBody Map<String, String> body) {
        String oldName = body.get("oldName");
        String newName = body.get("newName");
        try {
            int affected = webhookRepository.renameGroup(oldName, newName);
            if (appGroupRepository != null) {
                appGroupRepository.renameGroup("WEBHOOK", oldName, newName);
            }
            return ResponseEntity.ok(Map.of("message", "Group renamed successfully", "affectedRows", affected));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage() != null ? e.getMessage() : "Unknown error"));
        }
    }

    @GetMapping("/api/webhooks/{id}/logs")
    public ResponseEntity<List<WebhookLog>> getWebhookLogs(@PathVariable String id,
                                                          @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(webhookRepository.findLogsByWebhookId(id, limit));
    }

    @DeleteMapping("/api/webhooks/{id}/logs")
    public ResponseEntity<?> clearWebhookLogs(@PathVariable String id) {
        webhookRepository.clearLogsByWebhookId(id);
        return ResponseEntity.ok(Map.of("message", "Logs cleared successfully"));
    }

    @PostMapping("/api/webhooks/{id}/test-alert")
    public ResponseEntity<?> testAlert(@PathVariable String id) {
        Optional<WebhookConfig> opt = webhookRepository.findById(id);
        if (opt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Webhook not found"));
        }
        try {
            webhookService.sendTestAlert(opt.get());
            return ResponseEntity.ok(Map.of("message", "Test alert successfully sent to configured Telegram/Discord channels"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage() != null ? e.getMessage() : "Failed to send test alert"));
        }
    }

    private String sanitizeSlug(String slug) {
        return slug.trim().toLowerCase()
                .replaceAll("[^a-z0-9-_]", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "");
    }

    private String generateSlug(String name) {
        String base = sanitizeSlug(name);
        if (base.isEmpty()) {
            base = "webhook-" + UUID.randomUUID().toString().substring(0, 8);
        }
        return base;
    }
}
