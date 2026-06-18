package com.dbdiff.repository;

import com.dbdiff.model.Template;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

@Repository
public class TemplateRepository {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final RowMapper<Template> rowMapper = new RowMapper<Template>() {
        @Override
        public Template mapRow(ResultSet rs, int rowNum) throws SQLException {
            Template t = new Template();
            t.setId(rs.getString("id"));
            t.setName(rs.getString("name"));
            t.setAppMode(rs.getString("app_mode"));
            t.setSourceConnectionId(rs.getString("source_connection_id"));
            t.setTargetConnectionId(rs.getString("target_connection_id"));
            t.setTableMappings(rs.getString("table_mappings"));
            t.setCustomQuerySource(rs.getString("custom_query_source"));
            t.setCustomQueryTarget(rs.getString("custom_query_target"));
            t.setQueryPrimaryKeys(rs.getString("query_primary_keys"));
            if (rs.getTimestamp("created_at") != null) {
                t.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
            }
            return t;
        }
    };

    public List<Template> findAll() {
        return jdbcTemplate.query("SELECT * FROM templates ORDER BY created_at DESC", rowMapper);
    }

    public Template findById(String id) {
        List<Template> list = jdbcTemplate.query("SELECT * FROM templates WHERE id = ?", rowMapper, id);
        return list.isEmpty() ? null : list.get(0);
    }

    public void save(Template t) {
        String sql = "INSERT INTO templates (id, name, app_mode, source_connection_id, target_connection_id, table_mappings, custom_query_source, custom_query_target, query_primary_keys) " +
                     "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                     "ON CONFLICT (id) DO UPDATE SET " +
                     "name = EXCLUDED.name, app_mode = EXCLUDED.app_mode, " +
                     "source_connection_id = EXCLUDED.source_connection_id, " +
                     "target_connection_id = EXCLUDED.target_connection_id, " +
                     "table_mappings = EXCLUDED.table_mappings, " +
                     "custom_query_source = EXCLUDED.custom_query_source, " +
                     "custom_query_target = EXCLUDED.custom_query_target, " +
                     "query_primary_keys = EXCLUDED.query_primary_keys";
        jdbcTemplate.update(sql, t.getId(), t.getName(), t.getAppMode(), t.getSourceConnectionId(), t.getTargetConnectionId(), t.getTableMappings(), t.getCustomQuerySource(), t.getCustomQueryTarget(), t.getQueryPrimaryKeys());
    }

    public void deleteById(String id) {
        jdbcTemplate.update("DELETE FROM templates WHERE id = ?", id);
    }
}
