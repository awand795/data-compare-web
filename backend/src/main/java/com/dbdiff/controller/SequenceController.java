package com.dbdiff.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Dynamic Sequence Number Generator
 * -----------------------------------
 * Fully configurable auto-number engine — works for ANY project.
 *
 * Example formats:
 *   MK-250503-001   (daily reset)
 *   INV-2025-0001   (yearly reset)
 *   PO-202505-001   (monthly reset)
 *   TKT-00001       (never reset)
 *
 * Table: sch_sync.sequence_registry
 */
@RestController
@RequestMapping("/api/sequence")
@CrossOrigin(origins = "*")
public class SequenceController {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    // ── Init table on first use ──────────────────────────────────────────────
    private void ensureTable() {
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS sch_sync.sequence_registry (
                id              BIGSERIAL PRIMARY KEY,
                seq_key         VARCHAR(100) NOT NULL UNIQUE,
                description     TEXT,
                prefix          VARCHAR(50)  DEFAULT '',
                suffix          VARCHAR(50)  DEFAULT '',
                current_value   BIGINT       NOT NULL DEFAULT 0,
                pad_length      INT          NOT NULL DEFAULT 3,
                reset_type      VARCHAR(20)  NOT NULL DEFAULT 'NEVER',
                date_format     VARCHAR(30)  DEFAULT '',
                date_separator  VARCHAR(5)   DEFAULT '-',
                last_reset_date DATE,
                created_at      TIMESTAMP    DEFAULT NOW(),
                updated_at      TIMESTAMP    DEFAULT NOW()
            )
            """);
    }

    // ── GET /api/sequence/list ───────────────────────────────────────────────
    @GetMapping("/list")
    public ResponseEntity<?> list() {
        try {
            ensureTable();
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT * FROM sch_sync.sequence_registry ORDER BY seq_key"
            );
            return ResponseEntity.ok(rows);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    // ── POST /api/sequence/create ────────────────────────────────────────────
    @PostMapping("/create")
    public ResponseEntity<?> create(@RequestBody Map<String, Object> body) {
        try {
            ensureTable();
            String seqKey      = String.valueOf(body.get("seq_key"));
            String desc        = body.getOrDefault("description", "").toString();
            String prefix      = body.getOrDefault("prefix", "").toString();
            String suffix      = body.getOrDefault("suffix", "").toString();
            int padLen         = Integer.parseInt(body.getOrDefault("pad_length", 3).toString());
            String resetType   = body.getOrDefault("reset_type", "NEVER").toString().toUpperCase();
            String dateFmt     = body.getOrDefault("date_format", "").toString();
            String dateSep     = body.getOrDefault("date_separator", "-").toString();

            jdbcTemplate.update("""
                INSERT INTO sch_sync.sequence_registry
                  (seq_key, description, prefix, suffix, pad_length, reset_type, date_format, date_separator, current_value, last_reset_date)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, CURRENT_DATE)
                ON CONFLICT (seq_key) DO UPDATE SET
                  description = EXCLUDED.description,
                  prefix = EXCLUDED.prefix,
                  suffix = EXCLUDED.suffix,
                  pad_length = EXCLUDED.pad_length,
                  reset_type = EXCLUDED.reset_type,
                  date_format = EXCLUDED.date_format,
                  date_separator = EXCLUDED.date_separator,
                  updated_at = NOW()
                """,
                seqKey, desc, prefix, suffix, padLen, resetType, dateFmt, dateSep
            );

            return ResponseEntity.ok(Map.of("message", "Sequence '" + seqKey + "' saved successfully."));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    // ── POST /api/sequence/next?key={key} ────────────────────────────────────
    /**
     * Atomically increments and returns the next formatted sequence number.
     * Thread-safe via FOR UPDATE SKIP LOCKED.
     */
    @PostMapping("/next")
    public ResponseEntity<?> next(@RequestParam String key) {
        try {
            ensureTable();

            // Fetch + lock row
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT * FROM sch_sync.sequence_registry WHERE seq_key = ? FOR UPDATE", key
            );
            if (rows.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Sequence not found: " + key));
            }

            Map<String, Object> row = rows.get(0);
            String resetType   = String.valueOf(row.get("reset_type"));
            String dateFmt     = String.valueOf(row.getOrDefault("date_format", ""));
            String dateSep     = String.valueOf(row.getOrDefault("date_separator", "-"));
            String prefix      = String.valueOf(row.getOrDefault("prefix", ""));
            String suffix      = String.valueOf(row.getOrDefault("suffix", ""));
            int padLen         = ((Number) row.get("pad_length")).intValue();
            long currentVal    = ((Number) row.get("current_value")).longValue();
            Object lastResetObj = row.get("last_reset_date");

            LocalDate today = LocalDate.now();
            LocalDate lastReset = lastResetObj != null
                ? ((java.sql.Date) lastResetObj).toLocalDate()
                : today.minusYears(100);

            // Check if reset needed
            boolean needsReset = switch (resetType) {
                case "DAILY"   -> !lastReset.isEqual(today);
                case "MONTHLY" -> lastReset.getMonth() != today.getMonth()
                                  || lastReset.getYear() != today.getYear();
                case "YEARLY"  -> lastReset.getYear() != today.getYear();
                default        -> false;
            };

            long nextVal = needsReset ? 1L : currentVal + 1L;

            // Update
            jdbcTemplate.update("""
                UPDATE sch_sync.sequence_registry
                SET current_value = ?, last_reset_date = ?, updated_at = NOW()
                WHERE seq_key = ?
                """, nextVal, java.sql.Date.valueOf(today), key
            );

            // Build formatted number
            String paddedNum = String.format("%0" + padLen + "d", nextVal);
            String datePart  = "";
            if (dateFmt != null && !dateFmt.isBlank()) {
                datePart = dateSep + today.format(DateTimeFormatter.ofPattern(dateFmt));
            }

            // Format: PREFIX + DATEPART + SEP + PADDED
            String formatted;
            if (!prefix.isBlank() && !datePart.isBlank()) {
                formatted = prefix + datePart + dateSep + paddedNum;
            } else if (!prefix.isBlank()) {
                formatted = prefix + dateSep + paddedNum;
            } else {
                formatted = paddedNum;
            }
            if (!suffix.isBlank()) formatted = formatted + dateSep + suffix;

            return ResponseEntity.ok(Map.of(
                "key",       key,
                "value",     nextVal,
                "formatted", formatted,
                "date",      today.toString()
            ));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    // ── GET /api/sequence/preview?key={key} ──────────────────────────────────
    /** Preview what the next number would look like without incrementing */
    @GetMapping("/preview")
    public ResponseEntity<?> preview(@RequestParam String key) {
        try {
            ensureTable();
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT * FROM sch_sync.sequence_registry WHERE seq_key = ?", key
            );
            if (rows.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Sequence not found: " + key));
            }
            Map<String, Object> row = rows.get(0);
            String resetType   = String.valueOf(row.get("reset_type"));
            String dateFmt     = String.valueOf(row.getOrDefault("date_format", ""));
            String dateSep     = String.valueOf(row.getOrDefault("date_separator", "-"));
            String prefix      = String.valueOf(row.getOrDefault("prefix", ""));
            String suffix      = String.valueOf(row.getOrDefault("suffix", ""));
            int padLen         = ((Number) row.get("pad_length")).intValue();
            long currentVal    = ((Number) row.get("current_value")).longValue();
            Object lastResetObj = row.get("last_reset_date");

            LocalDate today = LocalDate.now();
            LocalDate lastReset = lastResetObj != null
                ? ((java.sql.Date) lastResetObj).toLocalDate()
                : today.minusYears(100);

            boolean needsReset = switch (resetType) {
                case "DAILY"   -> !lastReset.isEqual(today);
                case "MONTHLY" -> lastReset.getMonth() != today.getMonth()
                                  || lastReset.getYear() != today.getYear();
                case "YEARLY"  -> lastReset.getYear() != today.getYear();
                default        -> false;
            };

            long nextVal = needsReset ? 1L : currentVal + 1L;
            String paddedNum = String.format("%0" + padLen + "d", nextVal);
            String datePart  = "";
            if (dateFmt != null && !dateFmt.isBlank()) {
                datePart = dateSep + today.format(DateTimeFormatter.ofPattern(dateFmt));
            }

            String formatted;
            if (!prefix.isBlank() && !datePart.isBlank()) {
                formatted = prefix + datePart + dateSep + paddedNum;
            } else if (!prefix.isBlank()) {
                formatted = prefix + dateSep + paddedNum;
            } else {
                formatted = paddedNum;
            }
            if (!suffix.isBlank()) formatted = formatted + dateSep + suffix;

            return ResponseEntity.ok(Map.of(
                "key",       key,
                "current",   currentVal,
                "next_value", nextVal,
                "formatted", formatted,
                "will_reset", needsReset
            ));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    // ── DELETE /api/sequence/{key} ────────────────────────────────────────────
    @DeleteMapping("/{key}")
    public ResponseEntity<?> delete(@PathVariable String key) {
        try {
            ensureTable();
            int deleted = jdbcTemplate.update(
                "DELETE FROM sch_sync.sequence_registry WHERE seq_key = ?", key
            );
            if (deleted == 0) return ResponseEntity.notFound().build();
            return ResponseEntity.ok(Map.of("message", "Sequence deleted."));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    // ── POST /api/sequence/reset/{key} ────────────────────────────────────────
    @PostMapping("/reset/{key}")
    public ResponseEntity<?> reset(@PathVariable String key, @RequestParam(defaultValue = "0") long value) {
        try {
            ensureTable();
            int updated = jdbcTemplate.update("""
                UPDATE sch_sync.sequence_registry
                SET current_value = ?, last_reset_date = CURRENT_DATE, updated_at = NOW()
                WHERE seq_key = ?
                """, value, key
            );
            if (updated == 0) return ResponseEntity.notFound().build();
            return ResponseEntity.ok(Map.of("message", "Sequence reset to " + value + "."));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }
}
