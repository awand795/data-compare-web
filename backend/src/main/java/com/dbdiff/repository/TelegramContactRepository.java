package com.dbdiff.repository;

import com.dbdiff.model.TelegramContact;
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
public class TelegramContactRepository {

    private static final Logger logger = LoggerFactory.getLogger(TelegramContactRepository.class);
    private final JdbcTemplate jdbcTemplate;

    @Autowired
    public TelegramContactRepository(DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    @PostConstruct
    public void initTable() {
        try {
            String sql = "CREATE TABLE IF NOT EXISTS telegram_contacts (" +
                    "id VARCHAR(255) PRIMARY KEY, " +
                    "bot_id VARCHAR(255) NOT NULL, " +
                    "chat_id VARCHAR(255) NOT NULL, " +
                    "phone_number VARCHAR(50), " +
                    "first_name VARCHAR(255), " +
                    "last_name VARCHAR(255), " +
                    "username VARCHAR(255), " +
                    "is_active BOOLEAN DEFAULT TRUE, " +
                    "registered_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                    "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                    "CONSTRAINT uq_telegram_contact_bot_chat UNIQUE (bot_id, chat_id)" +
                    ")";
            jdbcTemplate.execute(sql);
            logger.info("Successfully initialized table telegram_contacts");
        } catch (Exception e) {
            logger.warn("Initialization of table telegram_contacts failed: {}", e.getMessage());
        }
    }

    private final RowMapper<TelegramContact> mapper = (rs, rowNum) -> {
        TelegramContact c = new TelegramContact();
        c.setId(rs.getString("id"));
        c.setBotId(rs.getString("bot_id"));
        c.setChatId(rs.getString("chat_id"));
        c.setPhoneNumber(rs.getString("phone_number"));
        c.setFirstName(rs.getString("first_name"));
        c.setLastName(rs.getString("last_name"));
        c.setUsername(rs.getString("username"));
        c.setActive(rs.getBoolean("is_active"));

        Timestamp reg = rs.getTimestamp("registered_at");
        if (reg != null) c.setRegisteredAt(reg.toLocalDateTime());

        Timestamp upd = rs.getTimestamp("updated_at");
        if (upd != null) c.setUpdatedAt(upd.toLocalDateTime());

        return c;
    };

    public List<TelegramContact> findAll() {
        return jdbcTemplate.query("SELECT * FROM telegram_contacts ORDER BY registered_at DESC", mapper);
    }

    public List<TelegramContact> findByBotId(String botId) {
        return jdbcTemplate.query("SELECT * FROM telegram_contacts WHERE bot_id = ? ORDER BY registered_at DESC", mapper, botId);
    }

    public TelegramContact findById(String id) {
        List<TelegramContact> list = jdbcTemplate.query("SELECT * FROM telegram_contacts WHERE id = ?", mapper, id);
        return list.isEmpty() ? null : list.get(0);
    }

    public TelegramContact findByBotIdAndChatId(String botId, String chatId) {
        List<TelegramContact> list = jdbcTemplate.query("SELECT * FROM telegram_contacts WHERE bot_id = ? AND chat_id = ?", mapper, botId, chatId);
        return list.isEmpty() ? null : list.get(0);
    }

    public TelegramContact findByBotIdAndPhone(String botId, String rawPhone) {
        if (rawPhone == null) return null;
        String clean = rawPhone.replaceAll("[^0-9]", "");
        if (clean.length() < 7) return null;

        // Extract core digits without 0 or 62 prefix (e.g. "8123456789")
        String corePhone = clean;
        if (corePhone.startsWith("62")) {
            corePhone = corePhone.substring(2);
        } else if (corePhone.startsWith("0")) {
            corePhone = corePhone.substring(1);
        }

        String sql = "SELECT * FROM telegram_contacts WHERE bot_id = ? AND (" +
                "phone_number = ? OR phone_number = ? OR phone_number = ? OR " +
                "phone_number LIKE ? OR ? LIKE ('%' || phone_number))";
        String p1 = clean;
        String p2 = "0" + corePhone;
        String p3 = "62" + corePhone;
        String pLike = "%" + corePhone;

        List<TelegramContact> list = jdbcTemplate.query(sql, mapper, botId, p1, p2, p3, pLike, clean);
        return list.isEmpty() ? null : list.get(0);
    }

    public TelegramContact upsertContact(TelegramContact contact) {
        TelegramContact existing = findByBotIdAndChatId(contact.getBotId(), contact.getChatId());
        LocalDateTime now = LocalDateTime.now();
        if (existing != null) {
            contact.setId(existing.getId());
            contact.setUpdatedAt(now);
            String updateSql = "UPDATE telegram_contacts SET phone_number = COALESCE(?, phone_number), " +
                    "first_name = ?, last_name = ?, username = ?, updated_at = ? WHERE id = ?";
            jdbcTemplate.update(updateSql, contact.getPhoneNumber(), contact.getFirstName(), contact.getLastName(),
                    contact.getUsername(), Timestamp.valueOf(now), existing.getId());
            return contact;
        } else {
            if (contact.getId() == null || contact.getId().isEmpty()) {
                contact.setId(UUID.randomUUID().toString());
            }
            contact.setRegisteredAt(now);
            contact.setUpdatedAt(now);
            String insertSql = "INSERT INTO telegram_contacts (id, bot_id, chat_id, phone_number, first_name, last_name, username, is_active, registered_at, updated_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
            jdbcTemplate.update(insertSql, contact.getId(), contact.getBotId(), contact.getChatId(), contact.getPhoneNumber(),
                    contact.getFirstName(), contact.getLastName(), contact.getUsername(), contact.isActive(),
                    Timestamp.valueOf(now), Timestamp.valueOf(now));
            return contact;
        }
    }

    public void updateManual(String id, String phoneNumber, String firstName, String lastName) {
        jdbcTemplate.update("UPDATE telegram_contacts SET phone_number = ?, first_name = ?, last_name = ?, updated_at = ? WHERE id = ?",
                phoneNumber, firstName, lastName, Timestamp.valueOf(LocalDateTime.now()), id);
    }

    public void toggleActive(String id, boolean active) {
        jdbcTemplate.update("UPDATE telegram_contacts SET is_active = ?, updated_at = ? WHERE id = ?",
                active, Timestamp.valueOf(LocalDateTime.now()), id);
    }

    public void delete(String id) {
        jdbcTemplate.update("DELETE FROM telegram_contacts WHERE id = ?", id);
    }
}
