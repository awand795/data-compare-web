package com.dbdiff.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class KeepAliveService {

    private static final Logger logger = LoggerFactory.getLogger(KeepAliveService.class);
    private final JdbcTemplate jdbcTemplate;

    public KeepAliveService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Runs every 4 minutes (240,000 ms) to keep the Aiven database from auto-sleeping.
     * This ensures there is always active query traffic, preventing idle timeouts.
     */
    @Scheduled(fixedRate = 240000)
    public void pingDatabase() {
        try {
            jdbcTemplate.execute("SELECT 1");
            logger.debug("Keep-alive ping executed successfully to prevent DB sleep.");
        } catch (Exception e) {
            logger.warn("Keep-alive ping failed: {}", e.getMessage());
        }
    }
}
