package com.dbdiff.repository;

import com.dbdiff.model.RefreshToken;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

@Repository
public class RefreshTokenRepository {

    private static final Logger logger = LoggerFactory.getLogger(RefreshTokenRepository.class);
    private final JdbcTemplate jdbcTemplate;

    @Autowired
    public RefreshTokenRepository(DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    @PostConstruct
    public void initTable() {
        try {
            jdbcTemplate.execute("CREATE SCHEMA IF NOT EXISTS sch_sync;");
            String sql = """
                CREATE TABLE IF NOT EXISTS sch_sync.refresh_tokens (
                    id            VARCHAR(255) PRIMARY KEY,
                    app_id        VARCHAR(100) NOT NULL,
                    user_id       VARCHAR(255) NOT NULL,
                    token_hash    TEXT NOT NULL,
                    expires_at    TIMESTAMP NOT NULL,
                    revoked       BOOLEAN DEFAULT FALSE,
                    created_at    TIMESTAMP DEFAULT NOW(),
                    replaced_by   VARCHAR(255)
                );
                ALTER TABLE sch_sync.refresh_tokens ADD COLUMN IF NOT EXISTS app_id VARCHAR(100) DEFAULT 'bengkel-kim3';
                ALTER TABLE sch_sync.refresh_tokens ADD COLUMN IF NOT EXISTS user_metadata TEXT;
                CREATE INDEX IF NOT EXISTS idx_refresh_tokens_hash ON sch_sync.refresh_tokens(token_hash);
                CREATE INDEX IF NOT EXISTS idx_refresh_tokens_app_user ON sch_sync.refresh_tokens(app_id, user_id);
                """;
            jdbcTemplate.execute(sql);
            logger.info("Successfully initialized table sch_sync.refresh_tokens");
        } catch (Exception e) {
            logger.warn("Initialization of table sch_sync.refresh_tokens skipped or failed: {}", e.getMessage());
        }
    }

    private final RowMapper<RefreshToken> mapper = (rs, rowNum) -> {
        RefreshToken t = new RefreshToken();
        t.setId(rs.getString("id"));
        t.setAppId(rs.getString("app_id"));
        t.setUserId(rs.getString("user_id"));
        t.setTokenHash(rs.getString("token_hash"));

        Timestamp exp = rs.getTimestamp("expires_at");
        if (exp != null) {
            t.setExpiresAt(exp.toLocalDateTime());
        }
        t.setRevoked(rs.getBoolean("revoked"));

        Timestamp created = rs.getTimestamp("created_at");
        if (created != null) {
            t.setCreatedAt(created.toLocalDateTime());
        }
        t.setReplacedBy(rs.getString("replaced_by"));
        try {
            t.setUserMetadata(rs.getString("user_metadata"));
        } catch (SQLException ignored) {}
        return t;
    };

    public RefreshToken save(RefreshToken token) {
        if (token.getId() == null || token.getId().trim().isEmpty()) {
            token.setId(UUID.randomUUID().toString());
        }
        if (token.getAppId() == null || token.getAppId().trim().isEmpty()) {
            token.setAppId("bengkel-kim3");
        }
        String sql = """
            INSERT INTO sch_sync.refresh_tokens (id, app_id, user_id, token_hash, expires_at, revoked, created_at, replaced_by, user_metadata)
            VALUES (?, ?, ?, ?, ?, ?, NOW(), ?, ?)
            """;
        try {
            jdbcTemplate.update(sql,
                    token.getId(),
                    token.getAppId().trim().toLowerCase(),
                    token.getUserId(),
                    token.getTokenHash(),
                    Timestamp.valueOf(token.getExpiresAt()),
                    token.isRevoked(),
                    token.getReplacedBy(),
                    token.getUserMetadata());
        } catch (Exception ex) {
            // Fallback for backward compatibility if user_metadata column hasn't migrated yet
            String fallbackSql = """
                INSERT INTO sch_sync.refresh_tokens (id, app_id, user_id, token_hash, expires_at, revoked, created_at, replaced_by)
                VALUES (?, ?, ?, ?, ?, ?, NOW(), ?)
                """;
            jdbcTemplate.update(fallbackSql,
                    token.getId(),
                    token.getAppId().trim().toLowerCase(),
                    token.getUserId(),
                    token.getTokenHash(),
                    Timestamp.valueOf(token.getExpiresAt()),
                    token.isRevoked(),
                    token.getReplacedBy());
        }
        return findById(token.getId());
    }

    public RefreshToken findById(String id) {
        if (id == null || id.trim().isEmpty()) return null;
        List<RefreshToken> list = jdbcTemplate.query("SELECT * FROM sch_sync.refresh_tokens WHERE id = ?", mapper, id);
        return list.isEmpty() ? null : list.get(0);
    }

    public RefreshToken findByTokenHash(String tokenHash) {
        if (tokenHash == null || tokenHash.trim().isEmpty()) return null;
        List<RefreshToken> list = jdbcTemplate.query("SELECT * FROM sch_sync.refresh_tokens WHERE token_hash = ?", mapper, tokenHash);
        return list.isEmpty() ? null : list.get(0);
    }

    public void revokeToken(String id, String replacedBy) {
        String sql = "UPDATE sch_sync.refresh_tokens SET revoked = TRUE, replaced_by = ? WHERE id = ?";
        jdbcTemplate.update(sql, replacedBy, id);
    }

    public void revokeByTokenHash(String tokenHash) {
        String sql = "UPDATE sch_sync.refresh_tokens SET revoked = TRUE WHERE token_hash = ?";
        jdbcTemplate.update(sql, tokenHash);
    }

    public void revokeAllByUserId(String userId) {
        String sql = "UPDATE sch_sync.refresh_tokens SET revoked = TRUE WHERE user_id = ? AND revoked = FALSE";
        jdbcTemplate.update(sql, userId);
    }

    public void revokeAllByAppIdAndUserId(String appId, String userId) {
        String sql = "UPDATE sch_sync.refresh_tokens SET revoked = TRUE WHERE app_id = ? AND user_id = ? AND revoked = FALSE";
        jdbcTemplate.update(sql, appId.trim().toLowerCase(), userId);
    }

    public int deleteExpiredAndRevoked() {
        String sql = "DELETE FROM sch_sync.refresh_tokens WHERE expires_at < NOW() AND revoked = TRUE";
        return jdbcTemplate.update(sql);
    }
}
