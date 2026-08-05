package com.dbdiff.repository;

import com.dbdiff.model.ApiSchedulerConfig;
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
public class ApiSchedulerRepository {

    private static final Logger logger = LoggerFactory.getLogger(ApiSchedulerRepository.class);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void initTable() {
        try {
            String sql = "CREATE TABLE IF NOT EXISTS api_scheduler_configs (" +
                    "id VARCHAR(64) PRIMARY KEY, " +
                    "name VARCHAR(255) NOT NULL, " +
                    "method VARCHAR(10) NOT NULL, " +
                    "url TEXT NOT NULL, " +
                    "query_params TEXT, " +
                    "headers TEXT, " +
                    "auth_type VARCHAR(20), " +
                    "auth_username VARCHAR(255), " +
                    "auth_password VARCHAR(255), " +
                    "auth_token TEXT, " +
                    "body_type VARCHAR(20), " +
                    "body_content TEXT, " +
                    "target_connection_id VARCHAR(64), " +
                    "target_table VARCHAR(255), " +
                    "kode_data VARCHAR(255), " +
                    "cron_expression VARCHAR(100), " +
                    "notification_channel_id VARCHAR(64), " +
                    "is_active BOOLEAN DEFAULT TRUE, " +
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                    "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                    "last_run_at TIMESTAMP, " +
                    "last_run_status VARCHAR(50), " +
                    "last_run_message TEXT" +
                    ")";
            jdbcTemplate.execute(sql);
            try {
                jdbcTemplate.execute("ALTER TABLE api_scheduler_configs ADD COLUMN notification_channel_id VARCHAR(64)");
            } catch (Exception ignored) {}
            logger.info("Successfully initialized table api_scheduler_configs");
        } catch (Exception e) {
            logger.warn("Initialization of table api_scheduler_configs skipped or failed: " + e.getMessage());
        }
    }

    private final RowMapper<ApiSchedulerConfig> rowMapper = new RowMapper<ApiSchedulerConfig>() {
        @Override
        public ApiSchedulerConfig mapRow(ResultSet rs, int rowNum) throws SQLException {
            ApiSchedulerConfig cfg = new ApiSchedulerConfig();
            cfg.setId(rs.getString("id"));
            cfg.setName(rs.getString("name"));
            cfg.setMethod(rs.getString("method"));
            cfg.setUrl(rs.getString("url"));
            cfg.setQueryParams(rs.getString("query_params"));
            cfg.setHeaders(rs.getString("headers"));
            cfg.setAuthType(rs.getString("auth_type"));
            cfg.setAuthUsername(rs.getString("auth_username"));
            cfg.setAuthPassword(rs.getString("auth_password"));
            cfg.setAuthToken(rs.getString("auth_token"));
            cfg.setBodyType(rs.getString("body_type"));
            cfg.setBodyContent(rs.getString("body_content"));
            cfg.setTargetConnectionId(rs.getString("target_connection_id"));
            cfg.setTargetTable(rs.getString("target_table"));
            cfg.setKodeData(rs.getString("kode_data"));
            cfg.setCronExpression(rs.getString("cron_expression"));
            cfg.setNotificationChannelId(rs.getString("notification_channel_id"));
            cfg.setActive(rs.getBoolean("is_active"));

            if (rs.getTimestamp("created_at") != null) {
                cfg.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
            }
            if (rs.getTimestamp("updated_at") != null) {
                cfg.setUpdatedAt(rs.getTimestamp("updated_at").toLocalDateTime());
            }
            if (rs.getTimestamp("last_run_at") != null) {
                cfg.setLastRunAt(rs.getTimestamp("last_run_at").toLocalDateTime());
            }
            cfg.setLastRunStatus(rs.getString("last_run_status"));
            cfg.setLastRunMessage(rs.getString("last_run_message"));
            return cfg;
        }
    };

    public List<ApiSchedulerConfig> findAll() {
        return jdbcTemplate.query("SELECT * FROM api_scheduler_configs ORDER BY created_at DESC", rowMapper);
    }

    public Optional<ApiSchedulerConfig> findById(String id) {
        List<ApiSchedulerConfig> list = jdbcTemplate.query("SELECT * FROM api_scheduler_configs WHERE id = ?", rowMapper, id);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public int insert(ApiSchedulerConfig cfg) {
        String sql = "INSERT INTO api_scheduler_configs (" +
                "id, name, method, url, query_params, headers, auth_type, auth_username, auth_password, auth_token, " +
                "body_type, body_content, target_connection_id, target_table, kode_data, cron_expression, notification_channel_id, is_active, " +
                "created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)";
        return jdbcTemplate.update(sql,
                cfg.getId(), cfg.getName(), cfg.getMethod(), cfg.getUrl(), cfg.getQueryParams(), cfg.getHeaders(),
                cfg.getAuthType(), cfg.getAuthUsername(), cfg.getAuthPassword(), cfg.getAuthToken(),
                cfg.getBodyType(), cfg.getBodyContent(), cfg.getTargetConnectionId(), cfg.getTargetTable(),
                cfg.getKodeData(), cfg.getCronExpression(), cfg.getNotificationChannelId(), cfg.isActive());
    }

    public int update(ApiSchedulerConfig cfg) {
        String sql = "UPDATE api_scheduler_configs SET " +
                "name = ?, method = ?, url = ?, query_params = ?, headers = ?, auth_type = ?, auth_username = ?, " +
                "auth_password = ?, auth_token = ?, body_type = ?, body_content = ?, target_connection_id = ?, " +
                "target_table = ?, kode_data = ?, cron_expression = ?, notification_channel_id = ?, is_active = ?, updated_at = CURRENT_TIMESTAMP " +
                "WHERE id = ?";
        return jdbcTemplate.update(sql,
                cfg.getName(), cfg.getMethod(), cfg.getUrl(), cfg.getQueryParams(), cfg.getHeaders(),
                cfg.getAuthType(), cfg.getAuthUsername(), cfg.getAuthPassword(), cfg.getAuthToken(),
                cfg.getBodyType(), cfg.getBodyContent(), cfg.getTargetConnectionId(), cfg.getTargetTable(),
                cfg.getKodeData(), cfg.getCronExpression(), cfg.getNotificationChannelId(), cfg.isActive(), cfg.getId());
    }

    public int updateLastRun(String id, String status, String message) {
        String sql = "UPDATE api_scheduler_configs SET last_run_at = CURRENT_TIMESTAMP, last_run_status = ?, last_run_message = ? WHERE id = ?";
        return jdbcTemplate.update(sql, status, message, id);
    }

    public int deleteById(String id) {
        return jdbcTemplate.update("DELETE FROM api_scheduler_configs WHERE id = ?", id);
    }
}
