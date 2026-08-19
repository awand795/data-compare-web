package com.dbdiff.repository;

import com.dbdiff.model.SystemAlertSchedule;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javax.sql.DataSource;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

@Repository
public class SystemAlertScheduleRepository {

    private static final Logger logger = LoggerFactory.getLogger(SystemAlertScheduleRepository.class);
    private final JdbcTemplate jdbcTemplate;

    @Autowired
    public SystemAlertScheduleRepository(DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    @PostConstruct
    public void initTable() {
        try {
            String sql = "CREATE TABLE IF NOT EXISTS system_alert_schedules (" +
                    "id VARCHAR(255) PRIMARY KEY, " +
                    "name VARCHAR(255) NOT NULL, " +
                    "target_disk VARCHAR(255) DEFAULT '/dev/sda2', " +
                    "disk_threshold_percent INT DEFAULT 70, " +
                    "ram_threshold_percent INT DEFAULT 80, " +
                    "check_disk BOOLEAN DEFAULT TRUE, " +
                    "check_ram BOOLEAN DEFAULT TRUE, " +
                    "cron_expression VARCHAR(255) NOT NULL DEFAULT '0 */5 * * * *', " +
                    "channel_ids TEXT, " +
                    "is_active BOOLEAN NOT NULL DEFAULT TRUE, " +
                    "cooldown_minutes INT DEFAULT 30, " +
                    "last_run TIMESTAMP, " +
                    "last_status VARCHAR(50), " +
                    "last_alert_time TIMESTAMP, " +
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                    ")";
            jdbcTemplate.execute(sql);
            logger.info("Successfully initialized table system_alert_schedules");
        } catch (Exception e) {
            logger.warn("Initialization of table system_alert_schedules skipped or failed: " + e.getMessage());
        }
    }

    private final RowMapper<SystemAlertSchedule> mapper = (rs, rowNum) -> {
        SystemAlertSchedule s = new SystemAlertSchedule();
        s.setId(rs.getString("id"));
        s.setName(rs.getString("name"));
        s.setTargetDisk(rs.getString("target_disk"));
        s.setDiskThresholdPercent(rs.getInt("disk_threshold_percent"));
        s.setRamThresholdPercent(rs.getInt("ram_threshold_percent"));
        s.setCheckDisk(rs.getBoolean("check_disk"));
        s.setCheckRam(rs.getBoolean("check_ram"));
        s.setCronExpression(rs.getString("cron_expression"));
        s.setChannelIds(rs.getString("channel_ids"));
        s.setActive(rs.getBoolean("is_active"));
        s.setCooldownMinutes(rs.getInt("cooldown_minutes"));

        Timestamp lr = rs.getTimestamp("last_run");
        if (lr != null) s.setLastRun(lr.toLocalDateTime());

        s.setLastStatus(rs.getString("last_status"));

        Timestamp lat = rs.getTimestamp("last_alert_time");
        if (lat != null) s.setLastAlertTime(lat.toLocalDateTime());

        Timestamp ca = rs.getTimestamp("created_at");
        if (ca != null) s.setCreatedAt(ca.toLocalDateTime());

        return s;
    };

    public List<SystemAlertSchedule> findAll() {
        return jdbcTemplate.query("SELECT * FROM system_alert_schedules ORDER BY created_at DESC", mapper);
    }

    public SystemAlertSchedule findById(String id) {
        if (id == null) return null;
        List<SystemAlertSchedule> list = jdbcTemplate.query("SELECT * FROM system_alert_schedules WHERE id = ?", mapper, id);
        return list.isEmpty() ? null : list.get(0);
    }

    public SystemAlertSchedule save(SystemAlertSchedule schedule) {
        if (schedule.getId() == null || schedule.getId().isEmpty()) {
            schedule.setId(UUID.randomUUID().toString());
        }
        String sql = "INSERT INTO system_alert_schedules (id, name, target_disk, disk_threshold_percent, ram_threshold_percent, check_disk, check_ram, cron_expression, channel_ids, is_active, cooldown_minutes) " +
                     "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        jdbcTemplate.update(sql,
                schedule.getId(),
                schedule.getName(),
                schedule.getTargetDisk(),
                schedule.getDiskThresholdPercent(),
                schedule.getRamThresholdPercent(),
                schedule.isCheckDisk(),
                schedule.isCheckRam(),
                schedule.getCronExpression(),
                schedule.getChannelIds(),
                schedule.isActive(),
                schedule.getCooldownMinutes());
        return findById(schedule.getId());
    }

    public void update(String id, SystemAlertSchedule schedule) {
        String sql = "UPDATE system_alert_schedules SET name=?, target_disk=?, disk_threshold_percent=?, ram_threshold_percent=?, check_disk=?, check_ram=?, cron_expression=?, channel_ids=?, is_active=?, cooldown_minutes=? WHERE id=?";
        jdbcTemplate.update(sql,
                schedule.getName(),
                schedule.getTargetDisk(),
                schedule.getDiskThresholdPercent(),
                schedule.getRamThresholdPercent(),
                schedule.isCheckDisk(),
                schedule.isCheckRam(),
                schedule.getCronExpression(),
                schedule.getChannelIds(),
                schedule.isActive(),
                schedule.getCooldownMinutes(),
                id);
    }

    public void updateStatusAndLastRun(String id, String status) {
        String sql = "UPDATE system_alert_schedules SET last_run = CURRENT_TIMESTAMP, last_status = ? WHERE id = ?";
        jdbcTemplate.update(sql, status, id);
    }

    public void updateAlertTime(String id) {
        String sql = "UPDATE system_alert_schedules SET last_alert_time = CURRENT_TIMESTAMP WHERE id = ?";
        jdbcTemplate.update(sql, id);
    }

    public void setActive(String id, boolean active) {
        jdbcTemplate.update("UPDATE system_alert_schedules SET is_active = ? WHERE id = ?", active, id);
    }

    public void delete(String id) {
        jdbcTemplate.update("DELETE FROM system_alert_schedules WHERE id = ?", id);
    }
}
