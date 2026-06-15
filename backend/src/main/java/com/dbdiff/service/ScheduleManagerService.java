package com.dbdiff.service;

import com.dbdiff.model.ScheduleConfig;
import com.dbdiff.model.ScheduleResult;
import com.dbdiff.model.ScheduleResultRow;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class ScheduleManagerService {

    private final JdbcTemplate jdbcTemplate;

    @Autowired
    public ScheduleManagerService(DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    private final RowMapper<ScheduleConfig> scheduleMapper = (rs, rowNum) -> {
        ScheduleConfig s = new ScheduleConfig();
        s.setId(rs.getString("id"));
        s.setName(rs.getString("name"));
        s.setSourceConnectionId(rs.getString("source_connection_id"));
        s.setTargetConnectionId(rs.getString("target_connection_id"));
        s.setSourceTable(rs.getString("source_table"));
        s.setTargetTable(rs.getString("target_table"));
                s.setCronExpression(rs.getString("cron_expression"));
        s.setTelegramBotToken(rs.getString("telegram_bot_token"));
        s.setTelegramChatId(rs.getString("telegram_chat_id"));
        s.setDiscordWebhookUrl(rs.getString("discord_webhook_url"));
        s.setTelegramChannelId(rs.getString("telegram_channel_id"));
        s.setDiscordChannelId(rs.getString("discord_channel_id"));
        s.setCustomQuerySource(rs.getString("custom_query_source"));
        s.setCustomQueryTarget(rs.getString("custom_query_target"));
        s.setPrimaryKeys(rs.getString("primary_keys"));
        s.setExcludeColumns(rs.getString("exclude_columns"));
        s.setSortColumns(rs.getString("sort_columns"));
        s.setMappings(rs.getString("mappings"));
        s.setSaveFullData(rs.getBoolean("save_full_data"));
        s.setActive(rs.getBoolean("is_active"));
        
        Timestamp created = rs.getTimestamp("created_at");
        if (created != null) s.setCreatedAt(created.toLocalDateTime());
        
        Timestamp lastRun = rs.getTimestamp("last_run");
        if (lastRun != null) s.setLastRun(lastRun.toLocalDateTime());
        return s;
    };

    public List<ScheduleConfig> getAllSchedules() {
        return jdbcTemplate.query("SELECT * FROM schedules ORDER BY created_at DESC", scheduleMapper);
    }

    public ScheduleConfig getSchedule(String id) {
        List<ScheduleConfig> list = jdbcTemplate.query("SELECT * FROM schedules WHERE id = ?", scheduleMapper, id);
        return list.isEmpty() ? null : list.get(0);
    }

    public ScheduleConfig createSchedule(ScheduleConfig config) {
        if (config.getId() == null || config.getId().isEmpty()) {
            config.setId(UUID.randomUUID().toString());
        }
        
                String sql = "INSERT INTO schedules (id, name, source_connection_id, target_connection_id, source_table, target_table, " +
                "cron_expression, telegram_bot_token, telegram_chat_id, discord_webhook_url, telegram_channel_id, discord_channel_id, " +
                "custom_query_source, custom_query_target, primary_keys, exclude_columns, sort_columns, mappings, save_full_data, is_active) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
                
        jdbcTemplate.update(sql, config.getId(), config.getName(), config.getSourceConnectionId(), config.getTargetConnectionId(),
                config.getSourceTable(), config.getTargetTable(), config.getCronExpression(), config.getTelegramBotToken(),
                config.getTelegramChatId(), config.getDiscordWebhookUrl(), config.getTelegramChannelId(), config.getDiscordChannelId(),
                config.getCustomQuerySource(), config.getCustomQueryTarget(), config.getPrimaryKeys(), config.getExcludeColumns(), config.getSortColumns(),
                config.getMappings(), config.isSaveFullData(), config.isActive());
                
        return getSchedule(config.getId());
    }

    public ScheduleConfig updateSchedule(String id, ScheduleConfig config) {
                String sql = "UPDATE schedules SET name=?, source_connection_id=?, target_connection_id=?, source_table=?, target_table=?, " +
                "cron_expression=?, telegram_bot_token=?, telegram_chat_id=?, discord_webhook_url=?, telegram_channel_id=?, discord_channel_id=?, " +
                "custom_query_source=?, custom_query_target=?, primary_keys=?, exclude_columns=?, sort_columns=?, mappings=?, save_full_data=?, is_active=? " +
                "WHERE id=?";
                
        jdbcTemplate.update(sql, config.getName(), config.getSourceConnectionId(), config.getTargetConnectionId(),
                config.getSourceTable(), config.getTargetTable(), config.getCronExpression(), config.getTelegramBotToken(),
                config.getTelegramChatId(), config.getDiscordWebhookUrl(), config.getTelegramChannelId(), config.getDiscordChannelId(),
                config.getCustomQuerySource(), config.getCustomQueryTarget(), config.getPrimaryKeys(), config.getExcludeColumns(), config.getSortColumns(),
                config.getMappings(), config.isSaveFullData(), config.isActive(), id);
                
        return getSchedule(id);
    }

    public void deleteSchedule(String id) {
        jdbcTemplate.update("DELETE FROM schedules WHERE id = ?", id);
    }

    public void updateLastRun(String id, LocalDateTime time) {
        jdbcTemplate.update("UPDATE schedules SET last_run = ? WHERE id = ?", Timestamp.valueOf(time), id);
    }

    public void saveResult(ScheduleResult result) {
        String sql = "INSERT INTO schedule_results (id, schedule_id, run_time, match_count, different_count, source_only_count, target_only_count, error_message, details) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        jdbcTemplate.update(sql, result.getId(), result.getScheduleId(), Timestamp.valueOf(result.getRunTime()),
                result.getMatchCount(), result.getDifferentCount(), result.getSourceOnlyCount(), result.getTargetOnlyCount(), result.getErrorMessage(), result.getDetails());
    }

    public void updateResult(ScheduleResult result) {
        String sql = "UPDATE schedule_results SET match_count=?, different_count=?, source_only_count=?, target_only_count=?, error_message=?, details=? WHERE id=?";
        jdbcTemplate.update(sql, result.getMatchCount(), result.getDifferentCount(), result.getSourceOnlyCount(), result.getTargetOnlyCount(),
                result.getErrorMessage(), result.getDetails(), result.getId());
    }
    
    public void saveResultRow(ScheduleResultRow row) {
        String sql = "INSERT INTO schedule_result_rows (result_id, row_key, status, data_json, table_name) VALUES (?, ?, ?, ?, ?)";
        jdbcTemplate.update(sql, row.getResultId(), row.getRowKey(), row.getStatus(), row.getDataJson(), row.getTableName());
    }

    /**
     * Batch insert multiple result rows in a single DB round-trip to reduce I/O pressure.
     * Use this instead of calling saveResultRow() in a loop.
     */
    public void saveResultRowsBatch(List<ScheduleResultRow> rows) {
        if (rows == null || rows.isEmpty()) return;
        String sql = "INSERT INTO schedule_result_rows (result_id, row_key, status, data_json, table_name) VALUES (?, ?, ?, ?, ?)";
        List<Object[]> batchArgs = new ArrayList<>(rows.size());
        for (ScheduleResultRow row : rows) {
            batchArgs.add(new Object[]{row.getResultId(), row.getRowKey(), row.getStatus(), row.getDataJson(), row.getTableName()});
        }
        jdbcTemplate.batchUpdate(sql, batchArgs);
    }

    public List<ScheduleResult> getResultsForSchedule(String scheduleId) {
        return jdbcTemplate.query("SELECT * FROM schedule_results WHERE schedule_id = ? ORDER BY run_time DESC LIMIT 50", (rs, rowNum) -> {
            ScheduleResult r = new ScheduleResult();
            r.setId(rs.getString("id"));
            r.setScheduleId(rs.getString("schedule_id"));
            r.setRunTime(rs.getTimestamp("run_time").toLocalDateTime());
            r.setMatchCount(rs.getInt("match_count"));
            r.setDifferentCount(rs.getInt("different_count"));
            r.setSourceOnlyCount(rs.getInt("source_only_count"));
            r.setTargetOnlyCount(rs.getInt("target_only_count"));
            r.setErrorMessage(rs.getString("error_message"));
            r.setDetails(rs.getString("details"));
            return r;
        }, scheduleId);
    }
    
    public List<ScheduleResultRow> getRowsForResult(String resultId) {
        return getRowsForResult(resultId, null);
    }
    
    public List<ScheduleResultRow> getRowsForResult(String resultId, String tableName) {
        String sql = "SELECT * FROM schedule_result_rows WHERE result_id = ?";
        if (tableName != null && !tableName.isEmpty()) {
            sql += " AND table_name = ?";
        }
        sql += " ORDER BY id ASC";
        
        if (tableName != null && !tableName.isEmpty()) {
            return jdbcTemplate.query(sql, (rs, rowNum) -> mapRow(rs), resultId, tableName);
        }
        return jdbcTemplate.query(sql, (rs, rowNum) -> mapRow(rs), resultId);
    }
    
    private ScheduleResultRow mapRow(ResultSet rs) throws SQLException {
        ScheduleResultRow r = new ScheduleResultRow();
        r.setId(rs.getLong("id"));
        r.setResultId(rs.getString("result_id"));
        r.setRowKey(rs.getString("row_key"));
        r.setStatus(rs.getString("status"));
        r.setDataJson(rs.getString("data_json"));
        try { r.setTableName(rs.getString("table_name")); } catch (Exception ignored) {}
        return r;
    }
}
