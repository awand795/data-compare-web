package com.dbdiff.repository;

import com.dbdiff.model.ClickHouseOptimizeSchedule;
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
public class ClickHouseOptimizeScheduleRepository {

    private static final Logger logger = LoggerFactory.getLogger(ClickHouseOptimizeScheduleRepository.class);
    private final JdbcTemplate jdbcTemplate;

    @Autowired
    public ClickHouseOptimizeScheduleRepository(DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    @PostConstruct
    public void initTable() {
        try {
            String sql = "CREATE TABLE IF NOT EXISTS clickhouse_optimize_schedules (" +
                    "id VARCHAR(255) PRIMARY KEY, " +
                    "name VARCHAR(255) NOT NULL, " +
                    "connection_id VARCHAR(255), " +
                    "database_name VARCHAR(255) NOT NULL DEFAULT 'dw_erp', " +
                    "include_cdc_tables BOOLEAN NOT NULL DEFAULT TRUE, " +
                    "table_pattern VARCHAR(255), " +
                    "cron_expression VARCHAR(255) NOT NULL DEFAULT '0 0 3 1 */5 *', " +
                    "is_active BOOLEAN NOT NULL DEFAULT TRUE, " +
                    "last_run TIMESTAMP, " +
                    "last_status VARCHAR(50), " +
                    "last_summary TEXT, " +
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                    ")";
            jdbcTemplate.execute(sql);
            logger.info("Successfully initialized table clickhouse_optimize_schedules");

            Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM clickhouse_optimize_schedules", Integer.class);
            if (count != null && count == 0) {
                ClickHouseOptimizeSchedule defaultSchedule = new ClickHouseOptimizeSchedule();
                defaultSchedule.setId("default-ch-5month-optimize");
                defaultSchedule.setName("All & CDC Version Cleanup (Every 5 Months @ 03:00 WIB)");
                defaultSchedule.setDatabaseName("dw_erp");
                defaultSchedule.setIncludeCdcTables(true);
                defaultSchedule.setCronExpression("0 0 3 1 */5 *");
                defaultSchedule.setActive(true);
                save(defaultSchedule);
                logger.info("Seeded default 5-month ClickHouse Optimize schedule");
            }
        } catch (Exception e) {
            logger.warn("Initialization of table clickhouse_optimize_schedules skipped or failed: {}", e.getMessage());
        }
    }

    private final RowMapper<ClickHouseOptimizeSchedule> mapper = (rs, rowNum) -> {
        ClickHouseOptimizeSchedule s = new ClickHouseOptimizeSchedule();
        s.setId(rs.getString("id"));
        s.setName(rs.getString("name"));
        s.setConnectionId(rs.getString("connection_id"));
        s.setDatabaseName(rs.getString("database_name"));
        s.setIncludeCdcTables(rs.getBoolean("include_cdc_tables"));
        s.setTablePattern(rs.getString("table_pattern"));
        s.setCronExpression(rs.getString("cron_expression"));
        s.setActive(rs.getBoolean("is_active"));

        Timestamp lr = rs.getTimestamp("last_run");
        if (lr != null) s.setLastRun(lr.toLocalDateTime());

        s.setLastStatus(rs.getString("last_status"));
        s.setLastSummary(rs.getString("last_summary"));

        Timestamp ca = rs.getTimestamp("created_at");
        if (ca != null) s.setCreatedAt(ca.toLocalDateTime());

        return s;
    };

    public List<ClickHouseOptimizeSchedule> findAll() {
        return jdbcTemplate.query("SELECT * FROM clickhouse_optimize_schedules ORDER BY created_at DESC", mapper);
    }

    public ClickHouseOptimizeSchedule findById(String id) {
        if (id == null) return null;
        List<ClickHouseOptimizeSchedule> list = jdbcTemplate.query(
                "SELECT * FROM clickhouse_optimize_schedules WHERE id = ?", mapper, id);
        return list.isEmpty() ? null : list.get(0);
    }

    public ClickHouseOptimizeSchedule save(ClickHouseOptimizeSchedule s) {
        if (s.getId() == null || s.getId().isEmpty()) {
            s.setId(UUID.randomUUID().toString());
        }
        String sql = "INSERT INTO clickhouse_optimize_schedules " +
                "(id, name, connection_id, database_name, include_cdc_tables, table_pattern, cron_expression, is_active) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        jdbcTemplate.update(sql,
                s.getId(),
                s.getName(),
                s.getConnectionId(),
                s.getDatabaseName() != null && !s.getDatabaseName().isEmpty() ? s.getDatabaseName() : "dw_erp",
                s.isIncludeCdcTables(),
                s.getTablePattern(),
                s.getCronExpression(),
                s.isActive());
        return findById(s.getId());
    }

    public void update(String id, ClickHouseOptimizeSchedule s) {
        String sql = "UPDATE clickhouse_optimize_schedules " +
                "SET name=?, connection_id=?, database_name=?, include_cdc_tables=?, table_pattern=?, cron_expression=?, is_active=? " +
                "WHERE id=?";
        jdbcTemplate.update(sql,
                s.getName(),
                s.getConnectionId(),
                s.getDatabaseName() != null && !s.getDatabaseName().isEmpty() ? s.getDatabaseName() : "dw_erp",
                s.isIncludeCdcTables(),
                s.getTablePattern(),
                s.getCronExpression(),
                s.isActive(),
                id);
    }

    public void setActive(String id, boolean active) {
        jdbcTemplate.update("UPDATE clickhouse_optimize_schedules SET is_active=? WHERE id=?", active, id);
    }

    public void updateExecutionResult(String id, String status, String summary) {
        jdbcTemplate.update(
                "UPDATE clickhouse_optimize_schedules SET last_run = CURRENT_TIMESTAMP, last_status = ?, last_summary = ? WHERE id = ?",
                status, summary, id);
    }

    public void delete(String id) {
        jdbcTemplate.update("DELETE FROM clickhouse_optimize_schedules WHERE id = ?", id);
    }
}
