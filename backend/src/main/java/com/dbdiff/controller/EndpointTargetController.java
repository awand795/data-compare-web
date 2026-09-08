package com.dbdiff.controller;

import com.dbdiff.model.EndpointTarget;
import com.dbdiff.repository.EndpointTargetRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

@RestController
@RequestMapping("/api/endpoint-targets")
public class EndpointTargetController {

    private static final Logger logger = LoggerFactory.getLogger(EndpointTargetController.class);

    @Autowired
    private EndpointTargetRepository repository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .build();

    @GetMapping
    public ResponseEntity<List<EndpointTarget>> getAll() {
        return ResponseEntity.ok(repository.findAll());
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getById(@PathVariable String id) {
        return repository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody EndpointTarget target) {
        if (target.getName() == null || target.getName().trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Name is required"));
        }
        if (target.getUrl() == null || target.getUrl().trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "URL is required"));
        }
        if (target.getId() == null || target.getId().trim().isEmpty()) {
            target.setId(UUID.randomUUID().toString());
        }
        target.setName(target.getName().trim());
        target.setUrl(target.getUrl().trim());
        if (target.getMethod() == null || target.getMethod().trim().isEmpty()) {
            target.setMethod("POST");
        } else {
            target.setMethod(target.getMethod().trim().toUpperCase());
        }

        repository.insert(target);
        return ResponseEntity.ok(repository.findById(target.getId()).orElse(target));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable String id, @RequestBody EndpointTarget target) {
        Optional<EndpointTarget> existing = repository.findById(id);
        if (existing.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        if (target.getName() == null || target.getName().trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Name is required"));
        }
        if (target.getUrl() == null || target.getUrl().trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "URL is required"));
        }
        target.setId(id);
        target.setName(target.getName().trim());
        target.setUrl(target.getUrl().trim());
        if (target.getMethod() == null || target.getMethod().trim().isEmpty()) {
            target.setMethod("POST");
        } else {
            target.setMethod(target.getMethod().trim().toUpperCase());
        }

        repository.update(target);
        return ResponseEntity.ok(repository.findById(id).orElse(target));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable String id) {
        repository.delete(id);
        return ResponseEntity.ok(Map.of("message", "Endpoint target deleted successfully"));
    }

    @PostMapping("/test")
    public ResponseEntity<?> testConnection(@RequestBody Map<String, Object> req) {
        String url = (String) req.get("url");
        String method = (String) req.getOrDefault("method", "GET");
        String headersRaw = (String) req.get("headers");

        if (url == null || url.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "URL is required"));
        }

        long start = System.currentTimeMillis();
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url.trim()))
                    .timeout(Duration.ofSeconds(10));

            // Parse headers if any
            if (headersRaw != null && !headersRaw.trim().isEmpty()) {
                try {
                    Map<String, Object> parsed = objectMapper.readValue(headersRaw, new TypeReference<>() {});
                    for (Map.Entry<String, Object> entry : parsed.entrySet()) {
                        if (entry.getKey() != null && entry.getValue() != null) {
                            builder.header(entry.getKey(), String.valueOf(entry.getValue()));
                        }
                    }
                } catch (Exception ignored) {}
            }

            String m = method.toUpperCase();
            if ("GET".equals(m)) {
                builder.GET();
            } else if ("HEAD".equals(m)) {
                builder.method("HEAD", HttpRequest.BodyPublishers.noBody());
            } else if ("POST".equals(m)) {
                builder.POST(HttpRequest.BodyPublishers.ofString("{}"));
            } else if ("PUT".equals(m)) {
                builder.PUT(HttpRequest.BodyPublishers.ofString("{}"));
            } else {
                builder.method(m, HttpRequest.BodyPublishers.noBody());
            }

            HttpResponse<String> resp = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            long duration = System.currentTimeMillis() - start;

            return ResponseEntity.ok(Map.of(
                    "statusCode", resp.statusCode(),
                    "durationMs", duration,
                    "body", resp.body() != null && resp.body().length() > 500 ? resp.body().substring(0, 500) + "..." : (resp.body() == null ? "" : resp.body()),
                    "success", resp.statusCode() >= 200 && resp.statusCode() < 400
            ));
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            return ResponseEntity.ok(Map.of(
                    "statusCode", 500,
                    "durationMs", duration,
                    "error", e.getMessage() != null ? e.getMessage() : e.toString(),
                    "success", false
            ));
        }
    }
}
