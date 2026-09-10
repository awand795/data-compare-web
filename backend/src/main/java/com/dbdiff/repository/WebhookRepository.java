package com.dbdiff.repository;

import com.dbdiff.model.WebhookConfig;
import com.dbdiff.model.WebhookLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import jakarta.annotation.PostConstruct;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

@Repository
public class WebhookRepository {

    private static final Logger logger = LoggerFactory.getLogger(WebhookRepository.class);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void initTable() {
        try {
            String createConfigSql = """
                CREATE TABLE IF NOT EXISTS webhook_configs (
                    id VARCHAR(255) PRIMARY KEY,
                    name VARCHAR(255) NOT NULL,
                    slug VARCHAR(255) NOT NULL UNIQUE,
                    description TEXT,
                    group_name VARCHAR(100) DEFAULT 'General',
                    secret_header_name VARCHAR(255),
                    secret_header_value TEXT,
                    ip_allowlist TEXT,
                    target_connection_id VARCHAR(255),
                    target_table VARCHAR(255),
                    kode_data VARCHAR(255) DEFAULT 'WEBHOOK',
                    enable_enrichment BOOLEAN DEFAULT FALSE,
                    enrichment_filter_status VARCHAR(100) DEFAULT 'READY_TO_SHIP',
                    enrichment_target_connection_id VARCHAR(255),
                    enrichment_target_table VARCHAR(255),
                    enrichment_kode_data VARCHAR(255) DEFAULT 'GINEE_READY_TO_SHIP',
                    enrichment_ginee_access_key VARCHAR(255),
                    enrichment_ginee_secret_key VARCHAR(255),
                    notification_channel_id TEXT,
                    is_active BOOLEAN DEFAULT TRUE,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    last_triggered_at TIMESTAMP,
                    last_status VARCHAR(50),
                    last_message TEXT,
                    total_requests BIGINT DEFAULT 0,
                    success_count BIGINT DEFAULT 0,
                    failure_count BIGINT DEFAULT 0
                )
            """;
            jdbcTemplate.execute(createConfigSql);

            String createLogSql = """
                CREATE TABLE IF NOT EXISTS webhook_logs (
                    id VARCHAR(255) PRIMARY KEY,
                    webhook_id VARCHAR(255) NOT NULL,
                    webhook_slug VARCHAR(255),
                    received_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    source_ip VARCHAR(100),
                    http_method VARCHAR(50),
                    headers TEXT,
                    payload TEXT,
                    status VARCHAR(50),
                    status_code INT,
                    error_message TEXT,
                    duration_ms BIGINT,
                    target_table VARCHAR(255),
                    rows_inserted INT DEFAULT 0
                )
            """;
            jdbcTemplate.execute(createLogSql);

            try {
                jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_webhook_logs_wid ON webhook_logs(webhook_id, received_at DESC)");
            } catch (Exception ignored) {}

            String[] alterSqls = {
                "ALTER TABLE webhook_configs ADD COLUMN IF NOT EXISTS group_name VARCHAR(100) DEFAULT 'General'",
                "ALTER TABLE webhook_configs ADD COLUMN IF NOT EXISTS total_requests BIGINT DEFAULT 0",
                "ALTER TABLE webhook_configs ADD COLUMN IF NOT EXISTS success_count BIGINT DEFAULT 0",
                "ALTER TABLE webhook_configs ADD COLUMN IF NOT EXISTS failure_count BIGINT DEFAULT 0",
                "ALTER TABLE webhook_configs ADD COLUMN IF NOT EXISTS enable_enrichment BOOLEAN DEFAULT FALSE",
                "ALTER TABLE webhook_configs ADD COLUMN IF NOT EXISTS enrichment_filter_status VARCHAR(100) DEFAULT 'READY_TO_SHIP'",
                "ALTER TABLE webhook_configs ADD COLUMN IF NOT EXISTS enrichment_target_connection_id VARCHAR(255)",
                "ALTER TABLE webhook_configs ADD COLUMN IF NOT EXISTS enrichment_target_table VARCHAR(255)",
                "ALTER TABLE webhook_configs ADD COLUMN IF NOT EXISTS enrichment_kode_data VARCHAR(255) DEFAULT 'GINEE_READY_TO_SHIP'",
                "ALTER TABLE webhook_configs ADD COLUMN IF NOT EXISTS enrichment_ginee_access_key VARCHAR(255)",
                "ALTER TABLE webhook_configs ADD COLUMN IF NOT EXISTS enrichment_ginee_secret_key VARCHAR(255)",
                "ALTER TABLE webhook_configs ADD COLUMN IF NOT EXISTS trigger_api_scheduler_id VARCHAR(255)",
                "ALTER TABLE webhook_configs ADD COLUMN IF NOT EXISTS trigger_filter_key VARCHAR(255) DEFAULT 'orderStatus'",
                "ALTER TABLE webhook_configs ADD COLUMN IF NOT EXISTS trigger_filter_value VARCHAR(255) DEFAULT 'READY_TO_SHIP'",
                "ALTER TABLE webhook_configs ADD COLUMN IF NOT EXISTS trigger_param_key VARCHAR(255) DEFAULT 'orderId'",
                "ALTER TABLE webhook_configs ADD COLUMN IF NOT EXISTS trigger_param_target VARCHAR(255) DEFAULT '{{orderId}}'",
                "ALTER TABLE webhook_configs ADD COLUMN IF NOT EXISTS trigger_filter_rules TEXT",
                "ALTER TABLE webhook_configs ADD COLUMN IF NOT EXISTS trigger_param_mapping TEXT"
            };
            for (String alter : alterSqls) {
                try {
                    jdbcTemplate.execute(alter);
                } catch (Exception ignored) {}
            }

            logger.info("Successfully initialized webhook_configs and webhook_logs tables.");
        } catch (Exception e) {
            logger.warn("Initialization of webhook tables skipped or failed: {}", e.getMessage());
        }
    }

    private final RowMapper<WebhookConfig> configRowMapper = new RowMapper<WebhookConfig>() {
        @Override
        public WebhookConfig mapRow(ResultSet rs, int rowNum) throws SQLException {
            WebhookConfig cfg = new WebhookConfig();
            cfg.setId(rs.getString("id"));
            cfg.setName(rs.getString("name"));
            cfg.setSlug(rs.getString("slug"));
            cfg.setDescription(rs.getString("description"));
            try {
                String grp = rs.getString("group_name");
                cfg.setGroupName(grp != null && !grp.trim().isEmpty() ? grp : "General");
            } catch (SQLException ignored) {}
            cfg.setSecretHeaderName(rs.getString("secret_header_name"));
            cfg.setSecretHeaderValue(rs.getString("secret_header_value"));
            cfg.setIpAllowlist(rs.getString("ip_allowlist"));
            cfg.setTargetConnectionId(rs.getString("target_connection_id"));
            cfg.setTargetTable(rs.getString("target_table"));
            cfg.setKodeData(rs.getString("kode_data"));

            try {
                cfg.setEnableEnrichment(rs.getBoolean("enable_enrichment"));
                cfg.setEnrichmentFilterStatus(rs.getString("enrichment_filter_status"));
                cfg.setEnrichmentTargetConnectionId(rs.getString("enrichment_target_connection_id"));
                cfg.setEnrichmentTargetTable(rs.getString("enrichment_target_table"));
                cfg.setEnrichmentKodeData(rs.getString("enrichment_kode_data"));
                cfg.setEnrichmentGineeAccessKey(rs.getString("enrichment_ginee_access_key"));
                cfg.setEnrichmentGineeSecretKey(rs.getString("enrichment_ginee_secret_key"));
                cfg.setTriggerApiSchedulerId(rs.getString("trigger_api_scheduler_id"));
                cfg.setTriggerFilterKey(rs.getString("trigger_filter_key"));
                cfg.setTriggerFilterValue(rs.getString("trigger_filter_value"));
                cfg.setTriggerParamKey(rs.getString("trigger_param_key"));
                cfg.setTriggerParamTarget(rs.getString("trigger_param_target"));
                cfg.setTriggerFilterRules(rs.getString("trigger_filter_rules"));
                cfg.setTriggerParamMapping(rs.getString("trigger_param_mapping"));
            } catch (SQLException ignored) {}

            cfg.setNotificationChannelId(rs.getString("notification_channel_id"));
            cfg.setActive(rs.getBoolean("is_active"));

            if (rs.getTimestamp("created_at") != null) {
                cfg.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
            }
            if (rs.getTimestamp("updated_at") != null) {
                cfg.setUpdatedAt(rs.getTimestamp("updated_at").toLocalDateTime());
            }
            if (rs.getTimestamp("last_triggered_at") != null) {
                cfg.setLastTriggeredAt(rs.getTimestamp("last_triggered_at").toLocalDateTime());
            }
            cfg.setLastStatus(rs.getString("last_status"));
            cfg.setLastMessage(rs.getString("last_message"));
            cfg.setTotalRequests(rs.getLong("total_requests"));
            cfg.setSuccessCount(rs.getLong("success_count"));
            cfg.setFailureCount(rs.getLong("failure_count"));
            return cfg;
        }
    };

    private final RowMapper<WebhookLog> logRowMapper = new RowMapper<WebhookLog>() {
        @Override
        public WebhookLog mapRow(ResultSet rs, int rowNum) throws SQLException {
            WebhookLog log = new WebhookLog();
            log.setId(rs.getString("id"));
            log.setWebhookId(rs.getString("webhook_id"));
            log.setWebhookSlug(rs.getString("webhook_slug"));
            if (rs.getTimestamp("received_at") != null) {
                log.setReceivedAt(rs.getTimestamp("received_at").toLocalDateTime());
            }
            log.setSourceIp(rs.getString("source_ip"));
            log.setHttpMethod(rs.getString("http_method"));
            log.setHeaders(rs.getString("headers"));
            log.setPayload(rs.getString("payload"));
            log.setStatus(rs.getString("status"));
            log.setStatusCode(rs.getInt("status_code"));
            log.setErrorMessage(rs.getString("error_message"));
            log.setDurationMs(rs.getLong("duration_ms"));
            log.setTargetTable(rs.getString("target_table"));
            log.setRowsInserted(rs.getInt("rows_inserted"));
            return log;
        }
    };

    public List<WebhookConfig> findAll() {
        return jdbcTemplate.query("SELECT * FROM webhook_configs ORDER BY created_at DESC", configRowMapper);
    }

    public Optional<WebhookConfig> findById(String id) {
        List<WebhookConfig> list = jdbcTemplate.query("SELECT * FROM webhook_configs WHERE id = ?", configRowMapper, id);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public Optional<WebhookConfig> findBySlug(String slug) {
        if (slug == null) return Optional.empty();
        List<WebhookConfig> list = jdbcTemplate.query("SELECT * FROM webhook_configs WHERE LOWER(slug) = LOWER(?)", configRowMapper, slug.trim());
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public int insert(WebhookConfig cfg) {
        String groupName = (cfg.getGroupName() != null && !cfg.getGroupName().trim().isEmpty()) ? cfg.getGroupName().trim() : "General";
        String kodeData = (cfg.getKodeData() != null && !cfg.getKodeData().trim().isEmpty()) ? cfg.getKodeData().trim() : "WEBHOOK";
        String filterStatus = (cfg.getEnrichmentFilterStatus() != null && !cfg.getEnrichmentFilterStatus().trim().isEmpty())
                ? cfg.getEnrichmentFilterStatus().trim() : "READY_TO_SHIP";
        String enrichKode = (cfg.getEnrichmentKodeData() != null && !cfg.getEnrichmentKodeData().trim().isEmpty())
                ? cfg.getEnrichmentKodeData().trim() : "GINEE_READY_TO_SHIP";
        String triggerFilterKey = (cfg.getTriggerFilterKey() != null && !cfg.getTriggerFilterKey().trim().isEmpty())
                ? cfg.getTriggerFilterKey().trim() : "orderStatus";
        String triggerFilterValue = (cfg.getTriggerFilterValue() != null && !cfg.getTriggerFilterValue().trim().isEmpty())
                ? cfg.getTriggerFilterValue().trim() : "READY_TO_SHIP";
        String triggerParamKey = (cfg.getTriggerParamKey() != null && !cfg.getTriggerParamKey().trim().isEmpty())
                ? cfg.getTriggerParamKey().trim() : "orderId";
        String triggerParamTarget = (cfg.getTriggerParamTarget() != null && !cfg.getTriggerParamTarget().trim().isEmpty())
                ? cfg.getTriggerParamTarget().trim() : "{{orderId}}";
        String triggerFilterRules = cfg.getTriggerFilterRules();
        String triggerParamMapping = cfg.getTriggerParamMapping();

        String sql = """
            INSERT INTO webhook_configs (
                id, name, slug, description, group_name,
                secret_header_name, secret_header_value, ip_allowlist,
                target_connection_id, target_table, kode_data,
                enable_enrichment, enrichment_filter_status, enrichment_target_connection_id,
                enrichment_target_table, enrichment_kode_data, enrichment_ginee_access_key, enrichment_ginee_secret_key,
                trigger_api_scheduler_id, trigger_filter_key, trigger_filter_value, trigger_param_key, trigger_param_target,
                trigger_filter_rules, trigger_param_mapping,
                notification_channel_id, is_active,
                created_at, updated_at, total_requests, success_count, failure_count
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, 0, 0)
        """;
        return jdbcTemplate.update(sql,
                cfg.getId(), cfg.getName(), cfg.getSlug().trim(), cfg.getDescription(), groupName,
                cfg.getSecretHeaderName(), cfg.getSecretHeaderValue(), cfg.getIpAllowlist(),
                cfg.getTargetConnectionId(), cfg.getTargetTable(), kodeData,
                cfg.isEnableEnrichment(), filterStatus, cfg.getEnrichmentTargetConnectionId(),
                cfg.getEnrichmentTargetTable(), enrichKode, cfg.getEnrichmentGineeAccessKey(), cfg.getEnrichmentGineeSecretKey(),
                cfg.getTriggerApiSchedulerId(), triggerFilterKey, triggerFilterValue, triggerParamKey, triggerParamTarget,
                triggerFilterRules, triggerParamMapping,
                cfg.getNotificationChannelId(), cfg.isActive());
    }

    public int update(WebhookConfig cfg) {
        String groupName = (cfg.getGroupName() != null && !cfg.getGroupName().trim().isEmpty()) ? cfg.getGroupName().trim() : "General";
        String kodeData = (cfg.getKodeData() != null && !cfg.getKodeData().trim().isEmpty()) ? cfg.getKodeData().trim() : "WEBHOOK";
        String filterStatus = (cfg.getEnrichmentFilterStatus() != null && !cfg.getEnrichmentFilterStatus().trim().isEmpty())
                ? cfg.getEnrichmentFilterStatus().trim() : "READY_TO_SHIP";
        String enrichKode = (cfg.getEnrichmentKodeData() != null && !cfg.getEnrichmentKodeData().trim().isEmpty())
                ? cfg.getEnrichmentKodeData().trim() : "GINEE_READY_TO_SHIP";
        String triggerFilterKey = (cfg.getTriggerFilterKey() != null && !cfg.getTriggerFilterKey().trim().isEmpty())
                ? cfg.getTriggerFilterKey().trim() : "orderStatus";
        String triggerFilterValue = (cfg.getTriggerFilterValue() != null && !cfg.getTriggerFilterValue().trim().isEmpty())
                ? cfg.getTriggerFilterValue().trim() : "READY_TO_SHIP";
        String triggerParamKey = (cfg.getTriggerParamKey() != null && !cfg.getTriggerParamKey().trim().isEmpty())
                ? cfg.getTriggerParamKey().trim() : "orderId";
        String triggerParamTarget = (cfg.getTriggerParamTarget() != null && !cfg.getTriggerParamTarget().trim().isEmpty())
                ? cfg.getTriggerParamTarget().trim() : "{{orderId}}";
        String triggerFilterRules = cfg.getTriggerFilterRules();
        String triggerParamMapping = cfg.getTriggerParamMapping();

        String sql = """
            UPDATE webhook_configs SET
                name = ?, slug = ?, description = ?, group_name = ?,
                secret_header_name = ?, secret_header_value = ?, ip_allowlist = ?,
                target_connection_id = ?, target_table = ?, kode_data = ?,
                enable_enrichment = ?, enrichment_filter_status = ?, enrichment_target_connection_id = ?,
                enrichment_target_table = ?, enrichment_kode_data = ?, enrichment_ginee_access_key = ?, enrichment_ginee_secret_key = ?,
                trigger_api_scheduler_id = ?, trigger_filter_key = ?, trigger_filter_value = ?, trigger_param_key = ?, trigger_param_target = ?,
                trigger_filter_rules = ?, trigger_param_mapping = ?,
                notification_channel_id = ?, is_active = ?, updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
        """;
        return jdbcTemplate.update(sql,
                cfg.getName(), cfg.getSlug().trim(), cfg.getDescription(), groupName,
                cfg.getSecretHeaderName(), cfg.getSecretHeaderValue(), cfg.getIpAllowlist(),
                cfg.getTargetConnectionId(), cfg.getTargetTable(), kodeData,
                cfg.isEnableEnrichment(), filterStatus, cfg.getEnrichmentTargetConnectionId(),
                cfg.getEnrichmentTargetTable(), enrichKode, cfg.getEnrichmentGineeAccessKey(), cfg.getEnrichmentGineeSecretKey(),
                cfg.getTriggerApiSchedulerId(), triggerFilterKey, triggerFilterValue, triggerParamKey, triggerParamTarget,
                triggerFilterRules, triggerParamMapping,
                cfg.getNotificationChannelId(), cfg.isActive(), cfg.getId());
    }

    public int updateActive(String id, boolean active) {
        return jdbcTemplate.update("UPDATE webhook_configs SET is_active = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?", active, id);
    }

    public int updateStats(String id, String status, String message, boolean success) {
        String sql;
        if (success) {
            sql = """
                UPDATE webhook_configs SET
                    last_triggered_at = CURRENT_TIMESTAMP,
                    last_status = ?,
                    last_message = ?,
                    total_requests = COALESCE(total_requests, 0) + 1,
                    success_count = COALESCE(success_count, 0) + 1,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
            """;
        } else {
            sql = """
                UPDATE webhook_configs SET
                    last_triggered_at = CURRENT_TIMESTAMP,
                    last_status = ?,
                    last_message = ?,
                    total_requests = COALESCE(total_requests, 0) + 1,
                    failure_count = COALESCE(failure_count, 0) + 1,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
            """;
        }
        return jdbcTemplate.update(sql, status, message, id);
    }

    public int updateGroupName(String id, String groupName) {
        String grp = (groupName != null && !groupName.trim().isEmpty()) ? groupName.trim() : "General";
        return jdbcTemplate.update("UPDATE webhook_configs SET group_name = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?", grp, id);
    }

    public int renameGroup(String oldName, String newName) {
        if (oldName == null || newName == null) return 0;
        return jdbcTemplate.update("UPDATE webhook_configs SET group_name = ?, updated_at = CURRENT_TIMESTAMP WHERE group_name = ?", newName.trim(), oldName.trim());
    }

    public int delete(String id) {
        // Also remove logs
        try {
            jdbcTemplate.update("DELETE FROM webhook_logs WHERE webhook_id = ?", id);
        } catch (Exception ignored) {}
        return jdbcTemplate.update("DELETE FROM webhook_configs WHERE id = ?", id);
    }

    // --- Webhook Logs Operations ---

    public int insertLog(WebhookLog log) {
        String sql = """
            INSERT INTO webhook_logs (
                id, webhook_id, webhook_slug, received_at, source_ip, http_method,
                headers, payload, status, status_code, error_message, duration_ms,
                target_table, rows_inserted
            ) VALUES (?, ?, ?, CURRENT_TIMESTAMP, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;
        return jdbcTemplate.update(sql,
                log.getId(), log.getWebhookId(), log.getWebhookSlug(),
                log.getSourceIp(), log.getHttpMethod(), log.getHeaders(),
                log.getPayload(), log.getStatus(), log.getStatusCode(),
                log.getErrorMessage(), log.getDurationMs(), log.getTargetTable(),
                log.getRowsInserted());
    }

    public List<WebhookLog> findLogsByWebhookId(String webhookId, int limit) {
        int max = (limit > 0 && limit <= 500) ? limit : 50;
        String sql = "SELECT * FROM webhook_logs WHERE webhook_id = ? ORDER BY received_at DESC LIMIT ?";
        return jdbcTemplate.query(sql, logRowMapper, webhookId, max);
    }

    public int clearLogsByWebhookId(String webhookId) {
        return jdbcTemplate.update("DELETE FROM webhook_logs WHERE webhook_id = ?", webhookId);
    }
}
