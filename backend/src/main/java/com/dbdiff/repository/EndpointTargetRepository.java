package com.dbdiff.repository;

import com.dbdiff.model.EndpointTarget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import jakarta.annotation.PostConstruct;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

@Repository
public class EndpointTargetRepository {

    private static final Logger logger = LoggerFactory.getLogger(EndpointTargetRepository.class);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void initTable() {
        try {
            String sql = "CREATE TABLE IF NOT EXISTS endpoint_targets (" +
                    "id VARCHAR(50) PRIMARY KEY, " +
                    "name VARCHAR(255) NOT NULL, " +
                    "url TEXT NOT NULL, " +
                    "method VARCHAR(20) DEFAULT 'POST', " +
                    "headers TEXT, " +
                    "description TEXT, " +
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                    "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                    ")";
            jdbcTemplate.execute(sql);
            logger.info("Table endpoint_targets initialized successfully.");
        } catch (Exception e) {
            logger.warn("Could not create endpoint_targets table: {}", e.getMessage());
        }
    }

    private final RowMapper<EndpointTarget> rowMapper = new RowMapper<EndpointTarget>() {
        @Override
        public EndpointTarget mapRow(ResultSet rs, int rowNum) throws SQLException {
            EndpointTarget t = new EndpointTarget();
            t.setId(rs.getString("id"));
            t.setName(rs.getString("name"));
            t.setUrl(rs.getString("url"));
            t.setMethod(rs.getString("method"));
            t.setHeaders(rs.getString("headers"));
            t.setDescription(rs.getString("description"));
            if (rs.getTimestamp("created_at") != null) {
                t.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
            }
            if (rs.getTimestamp("updated_at") != null) {
                t.setUpdatedAt(rs.getTimestamp("updated_at").toLocalDateTime());
            }
            return t;
        }
    };

    public List<EndpointTarget> findAll() {
        return jdbcTemplate.query("SELECT * FROM endpoint_targets ORDER BY name ASC", rowMapper);
    }

    public Optional<EndpointTarget> findById(String id) {
        List<EndpointTarget> list = jdbcTemplate.query("SELECT * FROM endpoint_targets WHERE id = ?", rowMapper, id);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public int insert(EndpointTarget target) {
        return jdbcTemplate.update(
                "INSERT INTO endpoint_targets (id, name, url, method, headers, description, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                target.getId(), target.getName(), target.getUrl(), target.getMethod(),
                target.getHeaders(), target.getDescription()
        );
    }

    public int update(EndpointTarget target) {
        return jdbcTemplate.update(
                "UPDATE endpoint_targets SET name = ?, url = ?, method = ?, headers = ?, description = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                target.getName(), target.getUrl(), target.getMethod(), target.getHeaders(), target.getDescription(), target.getId()
        );
    }

    public int delete(String id) {
        return jdbcTemplate.update("DELETE FROM endpoint_targets WHERE id = ?", id);
    }
}
