package com.dbdiff.repository;

import com.dbdiff.model.NotificationChannel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public class NotificationChannelRepository {

    private static final Logger logger = LoggerFactory.getLogger(NotificationChannelRepository.class);
    private final JdbcTemplate jdbcTemplate;

    @Autowired
    public NotificationChannelRepository(DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    @PostConstruct
    public void initTable() {
        try {
            String sql = "CREATE TABLE IF NOT EXISTS notification_channels (" +
                    "id VARCHAR(255) PRIMARY KEY, " +
                    "name VARCHAR(255) NOT NULL, " +
                    "type VARCHAR(50) NOT NULL, " +
                    "bot_token TEXT, " +
                    "chat_id VARCHAR(255), " +
                    "webhook_url TEXT, " +
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                    ")";
            jdbcTemplate.execute(sql);
            logger.info("Successfully initialized table notification_channels");
        } catch (Exception e) {
            logger.warn("Initialization of table notification_channels skipped or failed: " + e.getMessage());
        }
    }

    private final RowMapper<NotificationChannel> mapper = (rs, rowNum) -> {
        NotificationChannel c = new NotificationChannel();
        c.setId(rs.getString("id"));
        c.setName(rs.getString("name"));
        c.setType(rs.getString("type"));
        c.setBotToken(rs.getString("bot_token"));
        c.setChatId(rs.getString("chat_id"));
        c.setWebhookUrl(rs.getString("webhook_url"));
        
        Timestamp created = rs.getTimestamp("created_at");
        if (created != null) {
            c.setCreatedAt(created.toLocalDateTime());
        }
        return c;
    };

    public List<NotificationChannel> findAll() {
        return jdbcTemplate.query("SELECT * FROM notification_channels ORDER BY created_at DESC", mapper);
    }

    public NotificationChannel findById(String id) {
        if (id == null) return null;
        List<NotificationChannel> list = jdbcTemplate.query("SELECT * FROM notification_channels WHERE id = ?", mapper, id);
        return list.isEmpty() ? null : list.get(0);
    }

    public NotificationChannel save(NotificationChannel channel) {
        if (channel.getId() == null || channel.getId().isEmpty()) {
            channel.setId(UUID.randomUUID().toString());
        }
        String sql = "INSERT INTO notification_channels (id, name, type, bot_token, chat_id, webhook_url) VALUES (?, ?, ?, ?, ?, ?)";
        jdbcTemplate.update(sql, channel.getId(), channel.getName(), channel.getType(),
                channel.getBotToken(), channel.getChatId(), channel.getWebhookUrl());
        return findById(channel.getId());
    }

    public void update(String id, NotificationChannel channel) {
        String sql = "UPDATE notification_channels SET name=?, type=?, bot_token=?, chat_id=?, webhook_url=? WHERE id=?";
        jdbcTemplate.update(sql, channel.getName(), channel.getType(),
                channel.getBotToken(), channel.getChatId(), channel.getWebhookUrl(), id);
    }

    public void delete(String id) {
        jdbcTemplate.update("DELETE FROM notification_channels WHERE id = ?", id);
    }
}
