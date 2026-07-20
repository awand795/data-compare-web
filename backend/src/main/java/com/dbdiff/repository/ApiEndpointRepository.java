package com.dbdiff.repository;

import com.dbdiff.model.ApiEndpoint;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

@Repository
public class ApiEndpointRepository {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final RowMapper<ApiEndpoint> rowMapper = new RowMapper<ApiEndpoint>() {
        @Override
        public ApiEndpoint mapRow(ResultSet rs, int rowNum) throws SQLException {
            ApiEndpoint api = new ApiEndpoint();
            api.setId(rs.getString("id"));
            api.setName(rs.getString("name"));
            api.setMethod(rs.getString("method"));
            api.setEndpointPath(rs.getString("endpoint_path"));
            api.setConnectionId(rs.getString("connection_id"));
            api.setSqlQuery(rs.getString("sql_query"));
            api.setParameters(rs.getString("parameters"));
            api.setEnablePagination(rs.getBoolean("enable_pagination"));
            api.setPublic(rs.getBoolean("is_public"));
            api.setAuthToken(rs.getString("auth_token"));
            
            if (rs.getTimestamp("created_at") != null) {
                api.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
            }
            if (rs.getTimestamp("updated_at") != null) {
                api.setUpdatedAt(rs.getTimestamp("updated_at").toLocalDateTime());
            }
            return api;
        }
    };

    public List<ApiEndpoint> findAll() {
        return jdbcTemplate.query("SELECT * FROM api_endpoints ORDER BY created_at DESC", rowMapper);
    }

    public Optional<ApiEndpoint> findById(String id) {
        List<ApiEndpoint> results = jdbcTemplate.query("SELECT * FROM api_endpoints WHERE id = ?", rowMapper, id);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public Optional<ApiEndpoint> findByPathAndMethod(String path, String method) {
        List<ApiEndpoint> results = jdbcTemplate.query(
                "SELECT * FROM api_endpoints WHERE endpoint_path = ? AND method = ?", 
                rowMapper, path, method);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public int insert(ApiEndpoint api) {
        return jdbcTemplate.update(
            "INSERT INTO api_endpoints (id, name, method, endpoint_path, connection_id, sql_query, parameters, enable_pagination, is_public, auth_token, created_at, updated_at) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
            api.getId(), api.getName(), api.getMethod(), api.getEndpointPath(),
            api.getConnectionId(), api.getSqlQuery(), api.getParameters(),
            api.isEnablePagination(), api.isPublic(), api.getAuthToken()
        );
    }

    public int update(ApiEndpoint api) {
        return jdbcTemplate.update(
            "UPDATE api_endpoints SET name = ?, method = ?, endpoint_path = ?, connection_id = ?, sql_query = ?, parameters = ?, enable_pagination = ?, is_public = ?, auth_token = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
            api.getName(), api.getMethod(), api.getEndpointPath(),
            api.getConnectionId(), api.getSqlQuery(), api.getParameters(),
            api.isEnablePagination(), api.isPublic(), api.getAuthToken(), api.getId()
        );
    }

    public int deleteById(String id) {
        return jdbcTemplate.update("DELETE FROM api_endpoints WHERE id = ?", id);
    }
}
