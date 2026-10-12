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
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                    ")";
            jdbcTemplate.execute(sql);
            logger.info("Successfully initialized table telegram_logs");
        } catch (Exception e) {
            logger.warn("Initialization of table telegram_logs failed: {}", e.getMessage());
        }
    }

    private final RowMapper<TelegramLog> mapper = (rs, rowNum) -> {
        TelegramLog l = new TelegramLog();
        l.setId(rs.getString("id"));
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

        Timestamp created = rs.getTimestamp("created_at");
        if (created != null) l.setCreatedAt(created.toLocalDateTime());

        return l;
    };

    public List<TelegramLog> findAll() {
        return jdbcTemplate.query("SELECT * FROM telegram_logs ORDER BY created_at DESC LIMIT 200", mapper);
    }

    public TelegramLog save(TelegramLog log) {
        if (log.getId() == null || log.getId().isEmpty()) {
            log.setId(UUID.randomUUID().toString());
        }
        LocalDateTime now = LocalDateTime.now();
        log.setCreatedAt(now);

        String sql = "INSERT INTO telegram_logs (id, schedule_id, schedule_name, bot_id, bot_name, " +
                "trigger_type, total_rows, messages_sent, messages_failed, status, details, created_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        jdbcTemplate.update(sql, log.getId(), log.getScheduleId(), log.getScheduleName(), log.getBotId(), log.getBotName(),
                log.getTriggerType(), log.getTotalRows(), log.getMessagesSent(), log.getMessagesFailed(),
                log.getStatus(), log.getDetails(), Timestamp.valueOf(now));
        return log;
    }

    public void clearAll() {
        jdbcTemplate.update("DELETE FROM telegram_logs");
    }
}
