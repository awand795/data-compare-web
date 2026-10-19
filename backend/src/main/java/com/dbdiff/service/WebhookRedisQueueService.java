package com.dbdiff.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class WebhookRedisQueueService {

    private static final Logger logger = LoggerFactory.getLogger(WebhookRedisQueueService.class);
    public static final String WEBHOOK_QUEUE_KEY = "darkosync:webhooks:queue";
    public static final String WEBHOOK_DLQ_KEY = "darkosync:webhooks:dlq";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Autowired
    public WebhookRedisQueueService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Push incoming webhook event to Redis FIFO Queue
     */
    public boolean pushWebhook(String slug, String payload, String headersJson, String clientIp, String httpMethod) {
        try {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("id", UUID.randomUUID().toString());
            event.put("slug", slug);
            event.put("payload", payload);
            event.put("headersJson", headersJson);
            event.put("clientIp", clientIp);
            event.put("httpMethod", httpMethod);
            event.put("queuedAt", LocalDateTime.now().toString());

            String jsonString = objectMapper.writeValueAsString(event);
            redisTemplate.opsForList().leftPush(WEBHOOK_QUEUE_KEY, jsonString);
            logger.info("Buffered webhook '{}' into Redis queue [{}]", slug, WEBHOOK_QUEUE_KEY);
            return true;
        } catch (Exception e) {
            logger.warn("Failed to push webhook to Redis queue: {}. Falling back to direct processing.", e.getMessage());
            return false;
        }
    }

    /**
     * Pop next webhook event from Redis Queue (FIFO)
     */
    public Map<String, Object> popWebhook() {
        try {
            String json = redisTemplate.opsForList().rightPop(WEBHOOK_QUEUE_KEY);
            if (json == null || json.isEmpty()) {
                return null;
            }
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            logger.warn("Error popping from Redis queue: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Push failed event to Dead Letter Queue for auditing
     */
    public void pushToDeadLetterQueue(Map<String, Object> event, String errorMessage) {
        try {
            event.put("failedAt", LocalDateTime.now().toString());
            event.put("errorMessage", errorMessage);
            String json = objectMapper.writeValueAsString(event);
            redisTemplate.opsForList().leftPush(WEBHOOK_DLQ_KEY, json);
        } catch (Exception ignored) {}
    }

    /**
     * Get number of webhooks currently waiting in Redis queue
     */
    public long getQueueSize() {
        try {
            Long size = redisTemplate.opsForList().size(WEBHOOK_QUEUE_KEY);
            return size != null ? size : 0L;
        } catch (Exception e) {
            return -1L;
        }
    }

    /**
     * Check if Redis server is reachable
     */
    public boolean isRedisAvailable() {
        try {
            String ping = redisTemplate.getConnectionFactory().getConnection().ping();
            return "PONG".equalsIgnoreCase(ping);
        } catch (Exception e) {
            return false;
        }
    }
}
