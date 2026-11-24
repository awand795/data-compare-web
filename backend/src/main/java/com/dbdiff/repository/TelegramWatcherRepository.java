package com.dbdiff.repository;

import com.dbdiff.model.TelegramWatcher;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

@Repository
public class TelegramWatcherRepository {

    private static final Logger logger = LoggerFactory.getLogger(TelegramWatcherRepository.class);
    private final JdbcTemplate jdbcTemplate;

    @Autowired
    public TelegramWatcherRepository(DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    @PostConstruct
    public void initTable() {
        try {
            String sql = "CREATE TABLE IF NOT EXISTS telegram_watchers (" +
                    "id VARCHAR(255) PRIMARY KEY, " +
                    "name VARCHAR(255) NOT NULL, " +
                    "bot_id VARCHAR(255) NOT NULL, " +
                    "connection_id VARCHAR(255), " +
                    "watcher_type VARCHAR(50) NOT NULL DEFAULT 'POLLING_WATERMARK', " +
                    "sql_query TEXT, " +
                    "watermark_column VARCHAR(255), " +
                    "last_watermark VARCHAR(500), " +
                    "poll_interval_seconds INTEGER DEFAULT 5, " +
                    "flag_update_sql TEXT, " +
                    "pg_channel_name VARCHAR(255), " +
                    "template_message TEXT NOT NULL, " +
                    "recipient_mode VARCHAR(50) NOT NULL DEFAULT 'MASTER', " +
                    "master_recipient_ids TEXT, " +
                    "phone_column_name VARCHAR(255), " +
                    "is_active BOOLEAN DEFAULT TRUE, " +
                    "group_name VARCHAR(255) DEFAULT 'General', " +
                    "total_sent_count BIGINT DEFAULT 0, " +
                    "last_triggered_at TIMESTAMP, " +
                    "last_status VARCHAR(50), " +
                    "last_message TEXT, " +
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                    "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                    ")";
            jdbcTemplate.execute(sql);
            try {
                jdbcTemplate.execute("ALTER TABLE telegram_watchers ADD COLUMN IF NOT EXISTS group_name VARCHAR(255) DEFAULT 'General'");
            } catch (Exception ignored) {}
        } catch (Exception e) {
            logger.warn("Initialization of table telegram_watchers failed: {}", e.getMessage());
        }
    }

    private final RowMapper<TelegramWatcher> mapper = (rs, rowNum) -> {
        TelegramWatcher w = new TelegramWatcher();
        w.setId(rs.getString("id"));
        w.setName(rs.getString("name"));
        w.setBotId(rs.getString("bot_id"));
        w.setConnectionId(rs.getString("connection_id"));
        w.setWatcherType(rs.getString("watcher_type"));
        w.setSqlQuery(rs.getString("sql_query"));
        w.setWatermarkColumn(rs.getString("watermark_column"));
        w.setLastWatermark(rs.getString("last_watermark"));
        w.setPollIntervalSeconds(rs.getInt("poll_interval_seconds"));
        w.setFlagUpdateSql(rs.getString("flag_update_sql"));
        w.setPgChannelName(rs.getString("pg_channel_name"));
        w.setTemplateMessage(rs.getString("template_message"));
        w.setRecipientMode(rs.getString("recipient_mode"));
        w.setMasterRecipientIds(rs.getString("master_recipient_ids"));
        w.setPhoneColumnName(rs.getString("phone_column_name"));
        w.setActive(rs.getBoolean("is_active"));
        try {
            String grp = rs.getString("group_name");
            w.setGroupName(grp != null && !grp.trim().isEmpty() ? grp : "General");
        } catch (Exception ignored) {
            w.setGroupName("General");
        }
        w.setTotalSentCount(rs.getLong("total_sent_count"));

        Timestamp lastTrig = rs.getTimestamp("last_triggered_at");
        if (lastTrig != null) w.setLastTriggeredAt(lastTrig.toLocalDateTime());
        w.setLastStatus(rs.getString("last_status"));
        w.setLastMessage(rs.getString("last_message"));

        Timestamp created = rs.getTimestamp("created_at");
        if (created != null) w.setCreatedAt(created.toLocalDateTime());
        Timestamp updated = rs.getTimestamp("updated_at");
        if (updated != null) w.setUpdatedAt(updated.toLocalDateTime());
        return w;
    };

    public List<TelegramWatcher> findAll() {
        return jdbcTemplate.query("SELECT * FROM telegram_watchers ORDER BY created_at DESC", mapper);
    }

    public List<TelegramWatcher> findAllActive() {
        return jdbcTemplate.query("SELECT * FROM telegram_watchers WHERE is_active = TRUE ORDER BY created_at DESC", mapper);
    }

    public TelegramWatcher findById(String id) {
        List<TelegramWatcher> list = jdbcTemplate.query("SELECT * FROM telegram_watchers WHERE id = ?", mapper, id);
        return list.isEmpty() ? null : list.get(0);
    }

    public TelegramWatcher save(TelegramWatcher w) {
        if (w.getId() == null || w.getId().trim().isEmpty()) {
            w.setId("tgw-" + UUID.randomUUID().toString().substring(0, 8));
        }
        jdbcTemplate.update(
            "INSERT INTO telegram_watchers (id, name, bot_id, connection_id, watcher_type, sql_query, watermark_column, last_watermark, poll_interval_seconds, flag_update_sql, pg_channel_name, template_message, recipient_mode, master_recipient_ids, phone_column_name, is_active, group_name, total_sent_count, created_at, updated_at) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
            w.getId(), w.getName(), w.getBotId(), w.getConnectionId(), w.getWatcherType(),
            w.getSqlQuery(), w.getWatermarkColumn(), w.getLastWatermark(), w.getPollIntervalSeconds(),
            w.getFlagUpdateSql(), w.getPgChannelName(), w.getTemplateMessage(),
            w.getRecipientMode(), w.getMasterRecipientIds(), w.getPhoneColumnName(), w.isActive(), w.getGroupName()
        );
        return findById(w.getId());
    }

    public TelegramWatcher update(String id, TelegramWatcher w) {
        jdbcTemplate.update(
            "UPDATE telegram_watchers SET name = ?, bot_id = ?, connection_id = ?, watcher_type = ?, sql_query = ?, watermark_column = ?, last_watermark = ?, poll_interval_seconds = ?, flag_update_sql = ?, pg_channel_name = ?, template_message = ?, recipient_mode = ?, master_recipient_ids = ?, phone_column_name = ?, is_active = ?, group_name = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
            w.getName(), w.getBotId(), w.getConnectionId(), w.getWatcherType(),
            w.getSqlQuery(), w.getWatermarkColumn(), w.getLastWatermark(), w.getPollIntervalSeconds(),
            w.getFlagUpdateSql(), w.getPgChannelName(), w.getTemplateMessage(),
            w.getRecipientMode(), w.getMasterRecipientIds(), w.getPhoneColumnName(), w.isActive(), w.getGroupName(),
            id
        );
        return findById(id);
    }

    public void updateRuntimeState(String id, String lastWatermark, long deltaSent, String status, String message) {
        jdbcTemplate.update(
            "UPDATE telegram_watchers SET last_watermark = COALESCE(?, last_watermark), total_sent_count = total_sent_count + ?, last_triggered_at = CURRENT_TIMESTAMP, last_status = ?, last_message = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
            lastWatermark, deltaSent, status, message, id
        );
    }

    public void resetWatermark(String id, String newWatermark) {
        jdbcTemplate.update(
            "UPDATE telegram_watchers SET last_watermark = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
            newWatermark, id
        );
    }

    public void toggleActive(String id, boolean active) {
        jdbcTemplate.update("UPDATE telegram_watchers SET is_active = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?", active, id);
    }

    public void delete(String id) {
        jdbcTemplate.update("DELETE FROM telegram_watchers WHERE id = ?", id);
    }
}
