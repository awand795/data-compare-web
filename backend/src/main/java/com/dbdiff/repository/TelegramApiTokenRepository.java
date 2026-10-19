package com.dbdiff.repository;

import com.dbdiff.model.TelegramApiToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import jakarta.annotation.PostConstruct;
import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public class TelegramApiTokenRepository {

    private static final Logger logger = LoggerFactory.getLogger(TelegramApiTokenRepository.class);
    private final JdbcTemplate jdbcTemplate;

    @Autowired
    public TelegramApiTokenRepository(DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    @PostConstruct
    public void initTable() {
        try {
            String sql = "CREATE TABLE IF NOT EXISTS telegram_api_tokens (" +
                    "id VARCHAR(255) PRIMARY KEY, " +
                    "name VARCHAR(255) NOT NULL, " +
                    "token VARCHAR(255) UNIQUE NOT NULL, " +
                    "is_active BOOLEAN DEFAULT TRUE, " +
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                    "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                    "last_used_at TIMESTAMP" +
                    ")";
            jdbcTemplate.execute(sql);
            logger.info("Successfully initialized table telegram_api_tokens");

            Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM telegram_api_tokens", Integer.class);
            if (count != null && count == 0) {
                String initialToken = "dks_live_" + UUID.randomUUID().toString().replace("-", "");
                String insertSql = "INSERT INTO telegram_api_tokens (id, name, token, is_active, created_at, updated_at) " +
                        "VALUES (?, ?, ?, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)";
                jdbcTemplate.update(insertSql, UUID.randomUUID().toString(), "Default External API Token", initialToken);
                logger.info("Generated initial default Telegram API Token");
            }
        } catch (Exception e) {
            logger.warn("Initialization of table telegram_api_tokens failed: {}", e.getMessage());
        }
    }

    private final RowMapper<TelegramApiToken> mapper = (rs, rowNum) -> {
        TelegramApiToken t = new TelegramApiToken();
        t.setId(rs.getString("id"));
        t.setName(rs.getString("name"));
        t.setToken(rs.getString("token"));
        t.setActive(rs.getBoolean("is_active"));

        Timestamp crt = rs.getTimestamp("created_at");
        if (crt != null) t.setCreatedAt(crt.toLocalDateTime());

        Timestamp upd = rs.getTimestamp("updated_at");
        if (upd != null) t.setUpdatedAt(upd.toLocalDateTime());

        Timestamp lu = rs.getTimestamp("last_used_at");
        if (lu != null) t.setLastUsedAt(lu.toLocalDateTime());

        return t;
    };

    public List<TelegramApiToken> findAll() {
        return jdbcTemplate.query("SELECT * FROM telegram_api_tokens ORDER BY created_at DESC", mapper);
    }

    public TelegramApiToken findActive() {
        List<TelegramApiToken> list = jdbcTemplate.query("SELECT * FROM telegram_api_tokens WHERE is_active = true ORDER BY created_at DESC", mapper);
        return list.isEmpty() ? null : list.get(0);
    }

    public TelegramApiToken findByToken(String token) {
        if (token == null || token.trim().isEmpty()) return null;
        List<TelegramApiToken> list = jdbcTemplate.query("SELECT * FROM telegram_api_tokens WHERE token = ?", mapper, token.trim());
        return list.isEmpty() ? null : list.get(0);
    }

    public TelegramApiToken generateNewToken(String name) {
        String id = UUID.randomUUID().toString();
        String newToken = "dks_live_" + UUID.randomUUID().toString().replace("-", "");
        String tokenName = (name != null && !name.trim().isEmpty()) ? name.trim() : "External API Token";
        LocalDateTime now = LocalDateTime.now();

        String sql = "INSERT INTO telegram_api_tokens (id, name, token, is_active, created_at, updated_at) VALUES (?, ?, ?, true, ?, ?)";
        jdbcTemplate.update(sql, id, tokenName, newToken, Timestamp.valueOf(now), Timestamp.valueOf(now));

        TelegramApiToken token = new TelegramApiToken();
        token.setId(id);
        token.setName(tokenName);
        token.setToken(newToken);
        token.setActive(true);
        token.setCreatedAt(now);
        token.setUpdatedAt(now);
        return token;
    }

    public void toggleActive(String id, boolean active) {
        jdbcTemplate.update("UPDATE telegram_api_tokens SET is_active = ?, updated_at = ? WHERE id = ?",
                active, Timestamp.valueOf(LocalDateTime.now()), id);
    }

    public void updateLastUsed(String token) {
        jdbcTemplate.update("UPDATE telegram_api_tokens SET last_used_at = ? WHERE token = ?",
                Timestamp.valueOf(LocalDateTime.now()), token);
    }

    public void delete(String id) {
        jdbcTemplate.update("DELETE FROM telegram_api_tokens WHERE id = ?", id);
    }
}
