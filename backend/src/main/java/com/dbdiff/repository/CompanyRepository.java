package com.dbdiff.repository;

import com.dbdiff.model.Company;
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
public class CompanyRepository {

    private static final Logger logger = LoggerFactory.getLogger(CompanyRepository.class);
    private final JdbcTemplate jdbcTemplate;

    @Autowired
    public CompanyRepository(DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    @PostConstruct
    public void initTable() {
        try {
            jdbcTemplate.execute("CREATE SCHEMA IF NOT EXISTS sch_sync;");
            String sql = """
                CREATE TABLE IF NOT EXISTS sch_sync.companies (
                    id            VARCHAR(255) PRIMARY KEY,
                    app_id        VARCHAR(100) NOT NULL,
                    name          VARCHAR(255) NOT NULL,
                    phone         VARCHAR(50),
                    email         VARCHAR(255),
                    address       TEXT,
                    created_at    TIMESTAMP DEFAULT NOW()
                );
                ALTER TABLE sch_sync.companies ADD COLUMN IF NOT EXISTS app_id VARCHAR(100) DEFAULT 'bengkel-kim3';
                CREATE INDEX IF NOT EXISTS idx_companies_app_id ON sch_sync.companies(app_id);
                """;
            jdbcTemplate.execute(sql);
            logger.info("Successfully initialized table sch_sync.companies");
        } catch (Exception e) {
            logger.warn("Initialization of table sch_sync.companies skipped or failed: {}", e.getMessage());
        }
    }

    private final RowMapper<Company> mapper = (rs, rowNum) -> {
        Company c = new Company();
        c.setId(rs.getString("id"));
        c.setAppId(rs.getString("app_id"));
        c.setName(rs.getString("name"));
        c.setPhone(rs.getString("phone"));
        c.setEmail(rs.getString("email"));
        c.setAddress(rs.getString("address"));

        Timestamp created = rs.getTimestamp("created_at");
        if (created != null) {
            c.setCreatedAt(created.toLocalDateTime());
        }
        return c;
    };

    public List<Company> findAll() {
        return jdbcTemplate.query("SELECT * FROM sch_sync.companies ORDER BY created_at DESC", mapper);
    }

    public List<Company> findByAppId(String appId) {
        return jdbcTemplate.query("SELECT * FROM sch_sync.companies WHERE app_id = ? ORDER BY created_at DESC", mapper, appId);
    }

    public Company findById(String id) {
        if (id == null || id.trim().isEmpty()) return null;
        List<Company> list = jdbcTemplate.query("SELECT * FROM sch_sync.companies WHERE id = ?", mapper, id);
        return list.isEmpty() ? null : list.get(0);
    }

    public Company findByIdAndAppId(String id, String appId) {
        if (id == null || id.trim().isEmpty() || appId == null) return null;
        List<Company> list = jdbcTemplate.query("SELECT * FROM sch_sync.companies WHERE id = ? AND app_id = ?", mapper, id, appId);
        return list.isEmpty() ? null : list.get(0);
    }

    public Company findByNameAndAppId(String name, String appId) {
        if (name == null || name.trim().isEmpty() || appId == null) return null;
        List<Company> list = jdbcTemplate.query(
                "SELECT * FROM sch_sync.companies WHERE LOWER(name) = LOWER(?) AND app_id = ?",
                mapper, name.trim(), appId);
        return list.isEmpty() ? null : list.get(0);
    }

    public Company save(Company company) {
        if (company.getId() == null || company.getId().trim().isEmpty()) {
            company.setId(UUID.randomUUID().toString());
        }
        if (company.getAppId() == null || company.getAppId().trim().isEmpty()) {
            company.setAppId("bengkel-kim3");
        }
        String sql = """
            INSERT INTO sch_sync.companies (id, app_id, name, phone, email, address, created_at)
            VALUES (?, ?, ?, ?, ?, ?, NOW())
            """;
        jdbcTemplate.update(sql, company.getId(), company.getAppId(), company.getName(), company.getPhone(),
                company.getEmail(), company.getAddress());
        return findById(company.getId());
    }

    public void update(String id, Company company) {
        String sql = "UPDATE sch_sync.companies SET name = ?, phone = ?, email = ?, address = ? WHERE id = ?";
        jdbcTemplate.update(sql, company.getName(), company.getPhone(), company.getEmail(), company.getAddress(), id);
    }

    public void delete(String id) {
        jdbcTemplate.update("DELETE FROM sch_sync.companies WHERE id = ?", id);
    }
}
