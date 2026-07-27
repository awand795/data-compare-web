package com.dbdiff.service;

import com.dbdiff.repository.ApiShareTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class KeepAliveService {

    private static final Logger logger = LoggerFactory.getLogger(KeepAliveService.class);
    private final JdbcTemplate jdbcTemplate;

    @Autowired
    private ApiShareTokenRepository apiShareTokenRepository;

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

    /**
     * Runs every Sunday at 00:00:00 (12 AM midnight) to clean up expired or already used share tokens.
     */
    @Scheduled(cron = "0 0 0 * * SUN")
    public void cleanupExpiredShareTokens() {
        try {
            if (apiShareTokenRepository != null) {
                int deleted = apiShareTokenRepository.cleanupExpiredOrUsedTokens();
                if (deleted > 0) {
                    logger.info("Auto-cleanup: Removed {} expired/used API share tokens.", deleted);
                }
            }
        } catch (Exception e) {
            logger.warn("Auto-cleanup share tokens failed: {}", e.getMessage());
        }
    }

    /**
     * Runs a one-time cleanup on backend startup today.
     */
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void runOneTimeCleanupOnStartup() {
        logger.info("Running initial one-time share token cleanup on backend startup...");
        cleanupExpiredShareTokens();
    }
}
