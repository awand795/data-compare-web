package com.dbdiff.repository;

import com.dbdiff.model.TelegramSchedule;
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
public class TelegramScheduleRepository {

    private static final Logger logger = LoggerFactory.getLogger(TelegramScheduleRepository.class);
    private final JdbcTemplate jdbcTemplate;

    @Autowired
    public TelegramScheduleRepository(DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    @PostConstruct
    public void initTable() {
        try {
            String sql = "CREATE TABLE IF NOT EXISTS telegram_schedules (" +
                    "id VARCHAR(255) PRIMARY KEY, " +
                    "name VARCHAR(255) NOT NULL, " +
                    "bot_id VARCHAR(255) NOT NULL, " +
                    "connection_id VARCHAR(255) NOT NULL, " +
                    "sql_query TEXT NOT NULL, " +
                    "template_message TEXT NOT NULL, " +
                    "recipient_mode VARCHAR(50) NOT NULL, " +
                    "master_recipient_ids TEXT, " +
                    "phone_column_name VARCHAR(255), " +
                    "cron_expression VARCHAR(255), " +
                    "max_rows INTEGER DEFAULT 50, " +
                    "is_active BOOLEAN DEFAULT TRUE, " +
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                    "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                    "last_run_at TIMESTAMP, " +
                    "last_run_status VARCHAR(50), " +
                    "last_run_message TEXT" +
                    ")";
            jdbcTemplate.execute(sql);
            
            // Alter table for view-based fields
            String[] alterCols = {
                "ALTER TABLE telegram_schedules ADD COLUMN IF NOT EXISTS schema_name VARCHAR(255)",
                "ALTER TABLE telegram_schedules ADD COLUMN IF NOT EXISTS view_name VARCHAR(255)",
                "ALTER TABLE telegram_schedules ADD COLUMN IF NOT EXISTS filter_schedule VARCHAR(50)",
                "ALTER TABLE telegram_schedules ADD COLUMN IF NOT EXISTS filter_kode_notifikasi VARCHAR(255)",
                "ALTER TABLE telegram_schedules ADD COLUMN IF NOT EXISTS filter_kode_cabang VARCHAR(255)"
            };
            for (String alter : alterCols) {
                try { jdbcTemplate.execute(alter); } catch (Exception ignored) {}
            }
            logger.info("Successfully initialized table telegram_schedules");
        } catch (Exception e) {
            logger.warn("Initialization of table telegram_schedules failed: {}", e.getMessage());
        }
    }

    private final RowMapper<TelegramSchedule> mapper = (rs, rowNum) -> {
        TelegramSchedule s = new TelegramSchedule();
        s.setId(rs.getString("id"));
        s.setName(rs.getString("name"));
        s.setBotId(rs.getString("bot_id"));
        s.setConnectionId(rs.getString("connection_id"));
        s.setSqlQuery(rs.getString("sql_query"));
        s.setTemplateMessage(rs.getString("template_message"));
        s.setRecipientMode(rs.getString("recipient_mode"));
        s.setMasterRecipientIds(rs.getString("master_recipient_ids"));
        s.setPhoneColumnName(rs.getString("phone_column_name"));
        s.setSchemaName(rs.getString("schema_name"));
        s.setViewName(rs.getString("view_name"));
        s.setFilterSchedule(rs.getString("filter_schedule"));
        s.setFilterKodeNotifikasi(rs.getString("filter_kode_notifikasi"));
        s.setFilterKodeCabang(rs.getString("filter_kode_cabang"));
        s.setCronExpression(rs.getString("cron_expression"));
        s.setMaxRows(rs.getInt("max_rows"));
        s.setActive(rs.getBoolean("is_active"));

        Timestamp created = rs.getTimestamp("created_at");
        if (created != null) s.setCreatedAt(created.toLocalDateTime());

        Timestamp updated = rs.getTimestamp("updated_at");
        if (updated != null) s.setUpdatedAt(updated.toLocalDateTime());

        Timestamp lastRun = rs.getTimestamp("last_run_at");
        if (lastRun != null) s.setLastRunAt(lastRun.toLocalDateTime());

        s.setLastRunStatus(rs.getString("last_run_status"));
        s.setLastRunMessage(rs.getString("last_run_message"));

        return s;
    };

    public List<TelegramSchedule> findAll() {
        return jdbcTemplate.query("SELECT * FROM telegram_schedules ORDER BY created_at DESC", mapper);
    }

    public List<TelegramSchedule> findAllActive() {
        return jdbcTemplate.query("SELECT * FROM telegram_schedules WHERE is_active = true", mapper);
    }

    public TelegramSchedule findById(String id) {
        List<TelegramSchedule> list = jdbcTemplate.query("SELECT * FROM telegram_schedules WHERE id = ?", mapper, id);
        return list.isEmpty() ? null : list.get(0);
    }

    public TelegramSchedule save(TelegramSchedule schedule) {
        if (schedule.getId() == null || schedule.getId().isEmpty()) {
            schedule.setId(UUID.randomUUID().toString());
        }
        LocalDateTime now = LocalDateTime.now();
        schedule.setCreatedAt(now);
        schedule.setUpdatedAt(now);

        String sql = "INSERT INTO telegram_schedules (id, name, bot_id, connection_id, sql_query, template_message, " +
                "recipient_mode, master_recipient_ids, phone_column_name, schema_name, view_name, filter_schedule, " +
                "filter_kode_notifikasi, filter_kode_cabang, cron_expression, max_rows, is_active, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        jdbcTemplate.update(sql, schedule.getId(), schedule.getName(), schedule.getBotId(), schedule.getConnectionId(),
                schedule.getSqlQuery(), schedule.getTemplateMessage(), schedule.getRecipientMode(), schedule.getMasterRecipientIds(),
                schedule.getPhoneColumnName(), schedule.getSchemaName(), schedule.getViewName(), schedule.getFilterSchedule(),
                schedule.getFilterKodeNotifikasi(), schedule.getFilterKodeCabang(), schedule.getCronExpression(),
                schedule.getMaxRows() > 0 ? schedule.getMaxRows() : 50, schedule.isActive(), Timestamp.valueOf(now), Timestamp.valueOf(now));
        return schedule;
    }

    public void update(String id, TelegramSchedule schedule) {
        LocalDateTime now = LocalDateTime.now();
        String sql = "UPDATE telegram_schedules SET name = ?, bot_id = ?, connection_id = ?, sql_query = ?, " +
                "template_message = ?, recipient_mode = ?, master_recipient_ids = ?, phone_column_name = ?, " +
                "schema_name = ?, view_name = ?, filter_schedule = ?, filter_kode_notifikasi = ?, filter_kode_cabang = ?, " +
                "cron_expression = ?, max_rows = ?, is_active = ?, updated_at = ? WHERE id = ?";
        jdbcTemplate.update(sql, schedule.getName(), schedule.getBotId(), schedule.getConnectionId(), schedule.getSqlQuery(),
                schedule.getTemplateMessage(), schedule.getRecipientMode(), schedule.getMasterRecipientIds(),
                schedule.getPhoneColumnName(), schedule.getSchemaName(), schedule.getViewName(), schedule.getFilterSchedule(),
                schedule.getFilterKodeNotifikasi(), schedule.getFilterKodeCabang(), schedule.getCronExpression(),
                schedule.getMaxRows() > 0 ? schedule.getMaxRows() : 50, schedule.isActive(), Timestamp.valueOf(now), id);
    }

    public void updateLastRun(String id, LocalDateTime lastRunAt, String status, String message) {
        String sql = "UPDATE telegram_schedules SET last_run_at = ?, last_run_status = ?, last_run_message = ?, updated_at = ? WHERE id = ?";
        jdbcTemplate.update(sql, Timestamp.valueOf(lastRunAt), status, message, Timestamp.valueOf(LocalDateTime.now()), id);
    }

    public void toggleActive(String id, boolean active) {
        jdbcTemplate.update("UPDATE telegram_schedules SET is_active = ?, updated_at = ? WHERE id = ?",
                active, Timestamp.valueOf(LocalDateTime.now()), id);
    }

    public void delete(String id) {
        jdbcTemplate.update("DELETE FROM telegram_schedules WHERE id = ?", id);
    }
}
