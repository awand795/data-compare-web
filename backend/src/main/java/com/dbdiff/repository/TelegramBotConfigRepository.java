package com.dbdiff.repository;

import com.dbdiff.model.TelegramBotConfig;
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
public class TelegramBotConfigRepository {

    private static final Logger logger = LoggerFactory.getLogger(TelegramBotConfigRepository.class);
    private final JdbcTemplate jdbcTemplate;

    @Autowired
    public TelegramBotConfigRepository(DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    @PostConstruct
    public void initTable() {
        try {
            String sql = "CREATE TABLE IF NOT EXISTS telegram_bot_configs (" +
                    "id VARCHAR(255) PRIMARY KEY, " +
                    "name VARCHAR(255) NOT NULL, " +
                    "bot_token TEXT NOT NULL, " +
                    "bot_username VARCHAR(255), " +
                    "is_polling_active BOOLEAN DEFAULT TRUE, " +
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                    "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                    ")";
            jdbcTemplate.execute(sql);
            logger.info("Successfully initialized table telegram_bot_configs");

            // Auto-import existing Telegram channels from notification_channels if table is empty
            Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM telegram_bot_configs", Integer.class);
            if (count != null && count == 0) {
                try {
                    String importSql = "INSERT INTO telegram_bot_configs (id, name, bot_token, is_polling_active, created_at, updated_at) " +
                            "SELECT id, name, bot_token, true, created_at, created_at " +
                            "FROM notification_channels WHERE type = 'TELEGRAM' AND bot_token IS NOT NULL AND bot_token != ''";
                    int imported = jdbcTemplate.update(importSql);
                    if (imported > 0) {
                        logger.info("Imported {} existing Telegram bots from notification_channels", imported);
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (Exception e) {
            logger.warn("Initialization of table telegram_bot_configs failed: {}", e.getMessage());
        }
    }

    private final RowMapper<TelegramBotConfig> mapper = (rs, rowNum) -> {
        TelegramBotConfig c = new TelegramBotConfig();
        c.setId(rs.getString("id"));
        c.setName(rs.getString("name"));
        c.setBotToken(rs.getString("bot_token"));
        c.setBotUsername(rs.getString("bot_username"));
        c.setPollingActive(rs.getBoolean("is_polling_active"));

        Timestamp created = rs.getTimestamp("created_at");
        if (created != null) c.setCreatedAt(created.toLocalDateTime());

        Timestamp updated = rs.getTimestamp("updated_at");
        if (updated != null) c.setUpdatedAt(updated.toLocalDateTime());

        return c;
    };

    public List<TelegramBotConfig> findAll() {
        return jdbcTemplate.query("SELECT * FROM telegram_bot_configs ORDER BY created_at ASC", mapper);
    }

    public TelegramBotConfig findById(String id) {
        List<TelegramBotConfig> results = jdbcTemplate.query("SELECT * FROM telegram_bot_configs WHERE id = ?", mapper, id);
        return results.isEmpty() ? null : results.get(0);
    }

    public TelegramBotConfig save(TelegramBotConfig config) {
        if (config.getId() == null || config.getId().isEmpty()) {
            config.setId(UUID.randomUUID().toString());
        }
        LocalDateTime now = LocalDateTime.now();
        config.setCreatedAt(now);
        config.setUpdatedAt(now);

        String sql = "INSERT INTO telegram_bot_configs (id, name, bot_token, bot_username, is_polling_active, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?)";
        jdbcTemplate.update(sql, config.getId(), config.getName(), config.getBotToken(), config.getBotUsername(),
                config.isPollingActive(), Timestamp.valueOf(now), Timestamp.valueOf(now));
        return config;
    }

    public void update(String id, TelegramBotConfig config) {
        LocalDateTime now = LocalDateTime.now();
        String sql = "UPDATE telegram_bot_configs SET name = ?, bot_token = ?, bot_username = ?, is_polling_active = ?, updated_at = ? WHERE id = ?";
        jdbcTemplate.update(sql, config.getName(), config.getBotToken(), config.getBotUsername(), config.isPollingActive(), Timestamp.valueOf(now), id);
    }

    public void updateUsername(String id, String botUsername) {
        jdbcTemplate.update("UPDATE telegram_bot_configs SET bot_username = ?, updated_at = ? WHERE id = ?",
                botUsername, Timestamp.valueOf(LocalDateTime.now()), id);
    }

    public void setPollingStatus(String id, boolean active) {
        jdbcTemplate.update("UPDATE telegram_bot_configs SET is_polling_active = ?, updated_at = ? WHERE id = ?",
                active, Timestamp.valueOf(LocalDateTime.now()), id);
    }

    public void delete(String id) {
        jdbcTemplate.update("DELETE FROM telegram_bot_configs WHERE id = ?", id);
    }
}
