package com.dbdiff.repository;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class AppGroupRepository {
    private static final Logger logger = LoggerFactory.getLogger(AppGroupRepository.class);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void init() {
        try {
            jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS app_groups (
                    id SERIAL PRIMARY KEY,
                    module VARCHAR(50) NOT NULL,
                    name VARCHAR(100) NOT NULL,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    CONSTRAINT uq_app_groups_module_name UNIQUE (module, name)
                );
            """);
            logger.info("Initialized app_groups table in database.");
        } catch (Exception e) {
            logger.warn("Could not create app_groups table: {}", e.getMessage());
        }
    }

    public List<String> getGroups(String module) {
        String sql = "SELECT name FROM app_groups WHERE module = ? ORDER BY name ASC";
        return jdbcTemplate.queryForList(sql, String.class, module);
    }

    public void addGroup(String module, String name) {
        if (name == null || name.trim().isEmpty() || "General".equalsIgnoreCase(name.trim())) {
            return;
        }
        String sql = """
            INSERT INTO app_groups (module, name) 
            VALUES (?, ?) 
            ON CONFLICT (module, name) DO NOTHING
        """;
        jdbcTemplate.update(sql, module, name.trim());
    }

    public void renameGroup(String module, String oldName, String newName) {
        if (oldName == null || newName == null || newName.trim().isEmpty()) {
            return;
        }
        String sql = "UPDATE app_groups SET name = ? WHERE module = ? AND name = ?";
        jdbcTemplate.update(sql, newName.trim(), module, oldName.trim());
    }

    public void deleteGroup(String module, String name) {
        if (name == null || name.trim().isEmpty()) {
            return;
        }
        String sql = "DELETE FROM app_groups WHERE module = ? AND name = ?";
        jdbcTemplate.update(sql, module, name.trim());
    }
}