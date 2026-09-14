package com.dbdiff.controller;

import com.dbdiff.model.AuthApp;
import com.dbdiff.repository.AuthAppRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/auth-apps")
@CrossOrigin(origins = "*")
public class AuthAppController {

    private static final Logger logger = LoggerFactory.getLogger(AuthAppController.class);
    private final AuthAppRepository authAppRepository;

    @Autowired
    public AuthAppController(AuthAppRepository authAppRepository) {
        this.authAppRepository = authAppRepository;
    }

    public static String toSlug(String input) {
        if (input == null || input.trim().isEmpty()) return "app";
        String slug = input.trim().toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        return slug.isEmpty() ? "app" : slug;
    }

    @GetMapping
    public ResponseEntity<List<AuthApp>> list() {
        return ResponseEntity.ok(authAppRepository.findAll());
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getById(@PathVariable("id") String id) {
        AuthApp app = authAppRepository.findById(id);
        if (app == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("success", false, "error", "Auth App '" + id + "' tidak ditemukan"));
        }
        return ResponseEntity.ok(app);
    }

    @PostMapping
    public ResponseEntity<?> save(@RequestBody Map<String, Object> body) {
        try {
            String name = (String) body.get("name");
            if (name == null || name.trim().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("success", false, "error", "Nama Auth App wajib diisi"));
            }

            String id = (String) body.get("id");
            if (id == null || id.trim().isEmpty()) {
                id = toSlug(name);
            } else {
                id = toSlug(id);
            }

            String description = (String) body.get("description");
            String allowedRoles = (String) body.get("allowedRoles");
            if (allowedRoles == null || allowedRoles.trim().isEmpty()) {
                allowedRoles = "CUSTOMER,SECURITY,SA,FOREMAN,MEKANIK,WAREHOUSE,ADMIN_INVOICE,ADMIN";
            }

            Integer accessTtl = 15;
            if (body.get("accessTokenTtlMinutes") != null) {
                try {
                    accessTtl = Integer.parseInt(body.get("accessTokenTtlMinutes").toString());
                } catch (NumberFormatException ignored) {}
            }

            Integer refreshTtl = 30;
            if (body.get("refreshTokenTtlDays") != null) {
                try {
                    refreshTtl = Integer.parseInt(body.get("refreshTokenTtlDays").toString());
                } catch (NumberFormatException ignored) {}
            }

            Boolean isActive = true;
            if (body.get("isActive") != null) {
                isActive = Boolean.parseBoolean(body.get("isActive").toString());
            }

            AuthApp app = new AuthApp(id, name.trim(), description, allowedRoles.trim(),
                    accessTtl, refreshTtl, isActive, null, null);

            AuthApp saved = authAppRepository.save(app);
            return ResponseEntity.ok(saved);
        } catch (Exception e) {
            logger.error("Error saving Auth App", e);
            return ResponseEntity.internalServerError().body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable("id") String id) {
        try {
            AuthApp existing = authAppRepository.findById(id);
            if (existing == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("success", false, "error", "Auth App tidak ditemukan"));
            }
            authAppRepository.delete(id);
            return ResponseEntity.ok(Map.of("success", true, "message", "Auth App '" + id + "' berhasil dihapus"));
        } catch (Exception e) {
            logger.error("Error deleting Auth App", e);
            return ResponseEntity.internalServerError().body(Map.of("success", false, "error", e.getMessage()));
        }
    }
}
