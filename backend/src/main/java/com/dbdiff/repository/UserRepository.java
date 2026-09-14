package com.dbdiff.repository;

import com.dbdiff.model.User;
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
public class UserRepository {

    private static final Logger logger = LoggerFactory.getLogger(UserRepository.class);
    private final JdbcTemplate jdbcTemplate;

    @Autowired
    public UserRepository(DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    @PostConstruct
    public void initTable() {
        try {
            jdbcTemplate.execute("CREATE SCHEMA IF NOT EXISTS sch_sync;");
            String sql = """
                CREATE TABLE IF NOT EXISTS sch_sync.users (
                    id             VARCHAR(255) PRIMARY KEY,
                    app_id         VARCHAR(100) NOT NULL,
                    user_type      VARCHAR(20) NOT NULL,
                    role           VARCHAR(50) NOT NULL,
                    company_id     VARCHAR(255),
                    name           VARCHAR(255) NOT NULL,
                    email          VARCHAR(255) NOT NULL,
                    password_hash  TEXT NOT NULL,
                    phone          VARCHAR(50),
                    is_active      BOOLEAN DEFAULT TRUE,
                    created_at     TIMESTAMP DEFAULT NOW(),
                    updated_at     TIMESTAMP DEFAULT NOW()
                );
                ALTER TABLE sch_sync.users ADD COLUMN IF NOT EXISTS app_id VARCHAR(100) DEFAULT 'bengkel-kim3';
                CREATE UNIQUE INDEX IF NOT EXISTS idx_users_app_email ON sch_sync.users(app_id, LOWER(email));
                """;
            jdbcTemplate.execute(sql);
            logger.info("Successfully initialized table sch_sync.users");
        } catch (Exception e) {
            logger.warn("Initialization of table sch_sync.users skipped or failed: {}", e.getMessage());
        }
    }

    private final RowMapper<User> mapper = (rs, rowNum) -> {
        User u = new User();
        u.setId(rs.getString("id"));
        u.setAppId(rs.getString("app_id"));
        u.setUserType(rs.getString("user_type"));
        u.setRole(rs.getString("role"));
        u.setCompanyId(rs.getString("company_id"));
        u.setName(rs.getString("name"));
        u.setEmail(rs.getString("email"));
        u.setPasswordHash(rs.getString("password_hash"));
        u.setPhone(rs.getString("phone"));
        u.setIsActive(rs.getBoolean("is_active"));

        Timestamp created = rs.getTimestamp("created_at");
        if (created != null) {
            u.setCreatedAt(created.toLocalDateTime());
        }
        Timestamp updated = rs.getTimestamp("updated_at");
        if (updated != null) {
            u.setUpdatedAt(updated.toLocalDateTime());
        }
        return u;
    };

    public List<User> findAll() {
        return jdbcTemplate.query("SELECT * FROM sch_sync.users ORDER BY created_at DESC", mapper);
    }

    public List<User> findByAppId(String appId) {
        return jdbcTemplate.query("SELECT * FROM sch_sync.users WHERE app_id = ? ORDER BY created_at DESC", mapper, appId);
    }

    public User findById(String id) {
        if (id == null || id.trim().isEmpty()) return null;
        List<User> list = jdbcTemplate.query("SELECT * FROM sch_sync.users WHERE id = ?", mapper, id);
        return list.isEmpty() ? null : list.get(0);
    }

    public User findByAppIdAndId(String appId, String id) {
        if (id == null || id.trim().isEmpty() || appId == null) return null;
        List<User> list = jdbcTemplate.query("SELECT * FROM sch_sync.users WHERE app_id = ? AND id = ?", mapper, appId, id);
        return list.isEmpty() ? null : list.get(0);
    }

    public User findByAppIdAndEmail(String appId, String email) {
        if (email == null || email.trim().isEmpty() || appId == null) return null;
        List<User> list = jdbcTemplate.query(
                "SELECT * FROM sch_sync.users WHERE app_id = ? AND LOWER(email) = LOWER(?)",
                mapper, appId.trim().toLowerCase(), email.trim());
        return list.isEmpty() ? null : list.get(0);
    }

    public User save(User user) {
        if (user.getId() == null || user.getId().trim().isEmpty()) {
            user.setId(UUID.randomUUID().toString());
        }
        if (user.getAppId() == null || user.getAppId().trim().isEmpty()) {
            user.setAppId("bengkel-kim3");
        }
        String sql = """
            INSERT INTO sch_sync.users (id, app_id, user_type, role, company_id, name, email, password_hash, phone, is_active, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(), NOW())
            """;
        jdbcTemplate.update(sql,
                user.getId(),
                user.getAppId().trim().toLowerCase(),
                user.getUserType(),
                user.getRole(),
                user.getCompanyId(),
                user.getName(),
                user.getEmail().trim().toLowerCase(),
                user.getPasswordHash(),
                user.getPhone(),
                user.getIsActive() != null ? user.getIsActive() : true);
        return findById(user.getId());
    }

    public void update(String id, User user) {
        String sql = """
            UPDATE sch_sync.users
            SET user_type = ?, role = ?, company_id = ?, name = ?, phone = ?, is_active = ?, updated_at = NOW()
            WHERE id = ?
            """;
        jdbcTemplate.update(sql,
                user.getUserType(),
                user.getRole(),
                user.getCompanyId(),
                user.getName(),
                user.getPhone(),
                user.getIsActive() != null ? user.getIsActive() : true,
                id);
    }

    public void updatePassword(String id, String newPasswordHash) {
        String sql = "UPDATE sch_sync.users SET password_hash = ?, updated_at = NOW() WHERE id = ?";
        jdbcTemplate.update(sql, newPasswordHash, id);
    }

    public void delete(String id) {
        jdbcTemplate.update("DELETE FROM sch_sync.users WHERE id = ?", id);
    }
}
