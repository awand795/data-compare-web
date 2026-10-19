package com.dbdiff.repository;

import com.dbdiff.model.TelegramLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import jakarta.annotation.PostConstruct;
import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Repository
public class TelegramLogRepository {

    private static final Logger logger = LoggerFactory.getLogger(TelegramLogRepository.class);
    private final JdbcTemplate jdbcTemplate;

    @Autowired
    public TelegramLogRepository(DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    @PostConstruct
    public void initTable() {
        try {
            String sql = "CREATE TABLE IF NOT EXISTS telegram_logs (" +
                    "id VARCHAR(255) PRIMARY KEY, " +
                    "execution_id VARCHAR(255), " +
                    "schedule_id VARCHAR(255), " +
                    "schedule_name VARCHAR(255), " +
                    "bot_id VARCHAR(255), " +
                    "bot_name VARCHAR(255), " +
                    "trigger_type VARCHAR(50), " +
                    "total_rows INTEGER DEFAULT 0, " +
                    "messages_sent INTEGER DEFAULT 0, " +
                    "messages_failed INTEGER DEFAULT 0, " +
                    "status VARCHAR(50), " +
                    "details TEXT, " +
                    "recipient_name VARCHAR(255), " +
                    "recipient_phone VARCHAR(50), " +
                    "message_text TEXT, " +
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                    ")";
            jdbcTemplate.execute(sql);

            // Ensure new columns exist on existing database tables
            try { jdbcTemplate.execute("ALTER TABLE telegram_logs ADD COLUMN IF NOT EXISTS execution_id VARCHAR(255)"); } catch (Exception ignored) {}
            try { jdbcTemplate.execute("ALTER TABLE telegram_logs ADD COLUMN IF NOT EXISTS recipient_name VARCHAR(255)"); } catch (Exception ignored) {}
            try { jdbcTemplate.execute("ALTER TABLE telegram_logs ADD COLUMN IF NOT EXISTS recipient_phone VARCHAR(50)"); } catch (Exception ignored) {}
            try { jdbcTemplate.execute("ALTER TABLE telegram_logs ADD COLUMN IF NOT EXISTS message_text TEXT"); } catch (Exception ignored) {}

            logger.info("Successfully initialized table telegram_logs with execution_id and per-message columns");
        } catch (Exception e) {
            logger.warn("Initialization of table telegram_logs failed: {}", e.getMessage());
        }
    }

    private final RowMapper<TelegramLog> mapper = (rs, rowNum) -> {
        TelegramLog l = new TelegramLog();
        l.setId(rs.getString("id"));
        try { l.setExecutionId(rs.getString("execution_id")); } catch (Exception ignored) {}
        l.setScheduleId(rs.getString("schedule_id"));
        l.setScheduleName(rs.getString("schedule_name"));
        l.setBotId(rs.getString("bot_id"));
        l.setBotName(rs.getString("bot_name"));
        l.setTriggerType(rs.getString("trigger_type"));
        l.setTotalRows(rs.getInt("total_rows"));
        l.setMessagesSent(rs.getInt("messages_sent"));
        l.setMessagesFailed(rs.getInt("messages_failed"));
        l.setStatus(rs.getString("status"));
        l.setDetails(rs.getString("details"));

        try { l.setRecipientName(rs.getString("recipient_name")); } catch (Exception ignored) {}
        try { l.setRecipientPhone(rs.getString("recipient_phone")); } catch (Exception ignored) {}
        try { l.setMessageText(rs.getString("message_text")); } catch (Exception ignored) {}

        Timestamp created = rs.getTimestamp("created_at");
        if (created != null) l.setCreatedAt(created.toLocalDateTime());

        return l;
    };

    public List<TelegramLog> findAll() {
        return jdbcTemplate.query("SELECT * FROM telegram_logs ORDER BY created_at DESC LIMIT 1000", mapper);
    }

    public List<TelegramLog> findWithFilters(String search, String status, String startDate, String endDate) {
        StringBuilder sql = new StringBuilder("SELECT * FROM telegram_logs WHERE 1=1 ");
        List<Object> params = new ArrayList<>();

        if (status != null && !status.trim().isEmpty() && !status.equalsIgnoreCase("ALL")) {
            sql.append("AND (UPPER(status) = ? OR (? = 'BERHASIL' AND UPPER(status) = 'SUCCESS') OR (? = 'GAGAL' AND UPPER(status) = 'FAILED')) ");
            String st = status.trim().toUpperCase();
            params.add(st);
            params.add(st);
            params.add(st);
        }

        if (search != null && !search.trim().isEmpty()) {
            String q = "%" + search.trim() + "%";
            sql.append("AND (bot_name ILIKE ? OR recipient_phone ILIKE ? OR recipient_name ILIKE ? OR message_text ILIKE ? OR schedule_name ILIKE ? OR details ILIKE ?) ");
            params.add(q);
            params.add(q);
            params.add(q);
            params.add(q);
            params.add(q);
            params.add(q);
        }

        if (startDate != null && !startDate.trim().isEmpty()) {
            sql.append("AND created_at >= ?::timestamp ");
            params.add(startDate.trim() + " 00:00:00");
        }

        if (endDate != null && !endDate.trim().isEmpty()) {
            sql.append("AND created_at <= ?::timestamp ");
            params.add(endDate.trim() + " 23:59:59");
        }

        sql.append("ORDER BY created_at DESC LIMIT 1000");

        try {
            return jdbcTemplate.query(sql.toString(), mapper, params.toArray());
        } catch (Exception e) {
            logger.warn("Failed to query logs with filters: {}", e.getMessage());
            return findAll();
        }
    }

    public TelegramLog save(TelegramLog log) {
        if (log.getId() == null || log.getId().isEmpty()) {
            log.setId(UUID.randomUUID().toString());
        }
        if (log.getCreatedAt() == null) {
            log.setCreatedAt(LocalDateTime.now());
        }

        String sql = "INSERT INTO telegram_logs (id, execution_id, schedule_id, schedule_name, bot_id, bot_name, " +
                "trigger_type, total_rows, messages_sent, messages_failed, status, details, " +
                "recipient_name, recipient_phone, message_text, created_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        jdbcTemplate.update(sql, log.getId(), log.getExecutionId(), log.getScheduleId(), log.getScheduleName(), log.getBotId(), log.getBotName(),
                log.getTriggerType(), log.getTotalRows(), log.getMessagesSent(), log.getMessagesFailed(),
                log.getStatus(), log.getDetails(),
                log.getRecipientName(), log.getRecipientPhone(), log.getMessageText(),
                Timestamp.valueOf(log.getCreatedAt()));
        return log;
    }

    public void clearAll() {
        jdbcTemplate.update("DELETE FROM telegram_logs");
    }
}
