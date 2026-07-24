package com.dbdiff.repository;

import com.dbdiff.model.ApiShareToken;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

@Repository
public class ApiShareTokenRepository {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final RowMapper<ApiShareToken> rowMapper = new RowMapper<ApiShareToken>() {
        @Override
        public ApiShareToken mapRow(ResultSet rs, int rowNum) throws SQLException {
            ApiShareToken token = new ApiShareToken();
            token.setId(rs.getString("id"));
            token.setApiEndpointId(rs.getString("api_endpoint_id"));
            token.setToken(rs.getString("token"));
            token.setUsed(rs.getBoolean("is_used"));
            
            if (rs.getTimestamp("created_at") != null) {
                token.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
            }
            if (rs.getTimestamp("used_at") != null) {
                token.setUsedAt(rs.getTimestamp("used_at").toLocalDateTime());
            }
            return token;
        }
    };

    public Optional<ApiShareToken> findByToken(String token) {
        List<ApiShareToken> results = jdbcTemplate.query(
                "SELECT * FROM api_share_tokens WHERE token = ?", rowMapper, token);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public int insert(ApiShareToken shareToken) {
        return jdbcTemplate.update(
                "INSERT INTO api_share_tokens (id, api_endpoint_id, token, is_used, created_at, used_at) VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP, ?)",
                shareToken.getId(), shareToken.getApiEndpointId(), shareToken.getToken(), shareToken.isUsed(), null);
    }

    public int markAsUsed(String token) {
        return jdbcTemplate.update(
                "UPDATE api_share_tokens SET is_used = true, used_at = CURRENT_TIMESTAMP WHERE token = ? AND is_used = false",
                token);
    }

    public int recordView(String token) {
        return jdbcTemplate.update(
                "UPDATE api_share_tokens SET used_at = CURRENT_TIMESTAMP WHERE token = ?",
                token);
    }

    public int deleteByApiEndpointId(String apiEndpointId) {
        return jdbcTemplate.update("DELETE FROM api_share_tokens WHERE api_endpoint_id = ?", apiEndpointId);
    }
}
