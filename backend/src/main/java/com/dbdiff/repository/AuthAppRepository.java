package com.dbdiff.repository;

import com.dbdiff.model.AuthApp;
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

@Repository
public class AuthAppRepository {

    private static final Logger logger = LoggerFactory.getLogger(AuthAppRepository.class);
    private final JdbcTemplate jdbcTemplate;

    @Autowired
    public AuthAppRepository(DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    @PostConstruct
    public void initTable() {
        try {
            jdbcTemplate.execute("CREATE SCHEMA IF NOT EXISTS sch_sync;");
            String sql = """
                CREATE TABLE IF NOT EXISTS sch_sync.auth_apps (
                    id                        VARCHAR(100) PRIMARY KEY,
                    name                      VARCHAR(255) NOT NULL,
                    description               TEXT,
                    allowed_roles             TEXT NOT NULL,
                    access_token_ttl_minutes  INT DEFAULT 15,
                    refresh_token_ttl_days    INT DEFAULT 30,
                    is_active                 BOOLEAN DEFAULT TRUE,
                    created_at                TIMESTAMP DEFAULT NOW(),
                    updated_at                TIMESTAMP DEFAULT NOW()
                );
                """;
            jdbcTemplate.execute(sql);

            // Default seed app for Bengkel KIM3
            jdbcTemplate.execute("""
                INSERT INTO sch_sync.auth_apps (id, name, description, allowed_roles, access_token_ttl_minutes, refresh_token_ttl_days, is_active)
                VALUES ('bengkel-kim3', 'Bengkel KIM3 Fleet & Operations', 'Customer fleet portal and workshop internal ops', 'CUSTOMER,SECURITY,SA,FOREMAN,MEKANIK,WAREHOUSE,ADMIN_INVOICE,ADMIN', 15, 30, true)
                ON CONFLICT (id) DO NOTHING;
                """);

            logger.info("Successfully initialized table sch_sync.auth_apps with default seed");
        } catch (Exception e) {
            logger.warn("Initialization of table sch_sync.auth_apps skipped or failed: {}", e.getMessage());
        }
    }

    private final RowMapper<AuthApp> mapper = (rs, rowNum) -> {
        AuthApp app = new AuthApp();
        app.setId(rs.getString("id"));
        app.setName(rs.getString("name"));
        app.setDescription(rs.getString("description"));
        app.setAllowedRoles(rs.getString("allowed_roles"));
        app.setAccessTokenTtlMinutes(rs.getInt("access_token_ttl_minutes"));
        app.setRefreshTokenTtlDays(rs.getInt("refresh_token_ttl_days"));
        app.setIsActive(rs.getBoolean("is_active"));

        Timestamp created = rs.getTimestamp("created_at");
        if (created != null) app.setCreatedAt(created.toLocalDateTime());

        Timestamp updated = rs.getTimestamp("updated_at");
        if (updated != null) app.setUpdatedAt(updated.toLocalDateTime());

        return app;
    };

    public List<AuthApp> findAll() {
        return jdbcTemplate.query("SELECT * FROM sch_sync.auth_apps ORDER BY created_at ASC", mapper);
    }

    public AuthApp findById(String id) {
        if (id == null || id.trim().isEmpty()) return null;
        List<AuthApp> list = jdbcTemplate.query("SELECT * FROM sch_sync.auth_apps WHERE id = ?", mapper, id.trim().toLowerCase());
        return list.isEmpty() ? null : list.get(0);
    }

    public AuthApp save(AuthApp app) {
        String sql = """
            INSERT INTO sch_sync.auth_apps 
                (id, name, description, allowed_roles, access_token_ttl_minutes, refresh_token_ttl_days, is_active, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, NOW(), NOW())
            ON CONFLICT (id) DO UPDATE SET
                name = EXCLUDED.name,
                description = EXCLUDED.description,
                allowed_roles = EXCLUDED.allowed_roles,
                access_token_ttl_minutes = EXCLUDED.access_token_ttl_minutes,
                refresh_token_ttl_days = EXCLUDED.refresh_token_ttl_days,
                is_active = EXCLUDED.is_active,
                updated_at = NOW()
            """;
        jdbcTemplate.update(sql,
                app.getId().trim().toLowerCase(),
                app.getName().trim(),
                app.getDescription(),
                app.getAllowedRoles() != null ? app.getAllowedRoles().trim() : "CUSTOMER,ADMIN",
                app.getAccessTokenTtlMinutes() != null ? app.getAccessTokenTtlMinutes() : 15,
                app.getRefreshTokenTtlDays() != null ? app.getRefreshTokenTtlDays() : 30,
                app.getIsActive() != null ? app.getIsActive() : true);
        return findById(app.getId());
    }

    public void delete(String id) {
        jdbcTemplate.update("DELETE FROM sch_sync.auth_apps WHERE id = ?", id.trim().toLowerCase());
    }
}
