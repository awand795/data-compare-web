package com.dbdiff.repository;

import com.dbdiff.model.WalAlertSchedule;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

@Repository
public class WalAlertScheduleRepository {

    private final JdbcTemplate jdbcTemplate;

    @Autowired
    public WalAlertScheduleRepository(DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    private final RowMapper<WalAlertSchedule> mapper = (rs, rowNum) -> {
        WalAlertSchedule s = new WalAlertSchedule();
        s.setId(rs.getString("id"));
        s.setName(rs.getString("name"));
        s.setConnectionId(rs.getString("connection_id"));
        s.setThresholdMb(rs.getInt("threshold_mb"));
        s.setCronExpression(rs.getString("cron_expression"));
        s.setChannelIds(rs.getString("channel_ids"));
        s.setActive(rs.getBoolean("is_active"));

        Timestamp lr = rs.getTimestamp("last_run");
        if (lr != null) s.setLastRun(lr.toLocalDateTime());

        s.setLastStatus(rs.getString("last_status"));

        Timestamp lat = rs.getTimestamp("last_alert_time");
        if (lat != null) s.setLastAlertTime(lat.toLocalDateTime());

        Timestamp ca = rs.getTimestamp("created_at");
        if (ca != null) s.setCreatedAt(ca.toLocalDateTime());

        return s;
    };

    public List<WalAlertSchedule> findAll() {
        return jdbcTemplate.query("SELECT * FROM wal_alert_schedules ORDER BY created_at DESC", mapper);
    }

    public WalAlertSchedule findById(String id) {
        if (id == null) return null;
        List<WalAlertSchedule> list = jdbcTemplate.query("SELECT * FROM wal_alert_schedules WHERE id = ?", mapper, id);
        return list.isEmpty() ? null : list.get(0);
    }

    public WalAlertSchedule save(WalAlertSchedule schedule) {
        if (schedule.getId() == null || schedule.getId().isEmpty()) {
            schedule.setId(UUID.randomUUID().toString());
        }
        String sql = "INSERT INTO wal_alert_schedules (id, name, connection_id, threshold_mb, cron_expression, channel_ids, is_active) " +
                     "VALUES (?, ?, ?, ?, ?, ?, ?)";
        jdbcTemplate.update(sql,
                schedule.getId(),
                schedule.getName(),
                schedule.getConnectionId(),
                schedule.getThresholdMb(),
                schedule.getCronExpression(),
                schedule.getChannelIds(),
                schedule.isActive());
        return findById(schedule.getId());
    }

    public void update(String id, WalAlertSchedule schedule) {
        String sql = "UPDATE wal_alert_schedules SET name=?, connection_id=?, threshold_mb=?, cron_expression=?, channel_ids=?, is_active=? WHERE id=?";
        jdbcTemplate.update(sql,
                schedule.getName(),
                schedule.getConnectionId(),
                schedule.getThresholdMb(),
                schedule.getCronExpression(),
                schedule.getChannelIds(),
                schedule.isActive(),
                id);
    }

    public void updateStatusAndLastRun(String id, String status) {
        String sql = "UPDATE wal_alert_schedules SET last_run = CURRENT_TIMESTAMP, last_status = ? WHERE id = ?";
        jdbcTemplate.update(sql, status, id);
    }

    public void updateAlertTime(String id) {
        String sql = "UPDATE wal_alert_schedules SET last_alert_time = CURRENT_TIMESTAMP WHERE id = ?";
        jdbcTemplate.update(sql, id);
    }

    public void setActive(String id, boolean active) {
        jdbcTemplate.update("UPDATE wal_alert_schedules SET is_active = ? WHERE id = ?", active, id);
    }

    public void delete(String id) {
        jdbcTemplate.update("DELETE FROM wal_alert_schedules WHERE id = ?", id);
    }
}
