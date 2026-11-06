package com.dbdiff.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

@Service
public class WebhookRedisQueueService {

    private static final Logger logger = LoggerFactory.getLogger(WebhookRedisQueueService.class);
    public static final String WEBHOOK_QUEUE_KEY = "darkosync:webhooks:queue";
    public static final String WEBHOOK_DLQ_KEY = "darkosync:webhooks:dlq";
    public static final String TRIGGER_RETRY_ZSET_KEY = "darkosync:triggers:retry_zset";
    public static final String TRIGGER_DLQ_KEY = "darkosync:triggers:dlq";

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
     * Schedule a failed trigger for delayed retry in Redis (ZSET by timestamp, 60s delay)
     */
    public boolean scheduleTriggerRetry(String schedulerId, Map<String, String> dynamicParams, String rawPayload, int round, String error) {
        try {
            long delayMs = 60000L; // 60 seconds delay
            long retryAt = System.currentTimeMillis() + delayMs;

            Map<String, Object> task = new LinkedHashMap<>();
            task.put("id", UUID.randomUUID().toString());
            task.put("schedulerId", schedulerId);
            task.put("dynamicParams", dynamicParams != null ? dynamicParams : Collections.emptyMap());
            task.put("rawPayload", rawPayload);
            task.put("round", round);
            task.put("maxRounds", 3);
            task.put("scheduledAt", LocalDateTime.now().toString());
            task.put("retryAt", retryAt);
            task.put("lastError", error);

            String json = objectMapper.writeValueAsString(task);
            Boolean added = redisTemplate.opsForZSet().add(TRIGGER_RETRY_ZSET_KEY, json, (double) retryAt);
            logger.warn("Enqueued trigger for scheduler [{}] into Redis Delayed Retry Queue (round {}/3, ready in 60s)",
                    schedulerId, round);
            return Boolean.TRUE.equals(added);
        } catch (Exception e) {
            logger.error("Failed to enqueue delayed trigger retry to Redis: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * Pop items from Delayed Retry ZSET whose retryAt <= now
     */
    public List<Map<String, Object>> popDueTriggerRetries(int limit) {
        List<Map<String, Object>> result = new ArrayList<>();
        try {
            long now = System.currentTimeMillis();
            Set<String> dueItems = redisTemplate.opsForZSet().rangeByScore(TRIGGER_RETRY_ZSET_KEY, 0, (double) now, 0, limit);
            if (dueItems == null || dueItems.isEmpty()) {
                return result;
            }

            for (String json : dueItems) {
                Long removed = redisTemplate.opsForZSet().remove(TRIGGER_RETRY_ZSET_KEY, json);
                if (removed != null && removed > 0) {
                    try {
                        Map<String, Object> map = objectMapper.readValue(json, Map.class);
                        result.add(map);
                    } catch (Exception parseEx) {
                        logger.error("Failed to parse trigger retry item JSON: {}", parseEx.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Error polling due trigger retries from Redis: {}", e.getMessage());
        }
        return result;
    }

    /**
     * Push permanently failed trigger task into Dead Letter Queue (DLQ)
     */
    public void pushTriggerToDlq(Map<String, Object> task, String finalError) {
        try {
            task.put("failedPermanentlyAt", LocalDateTime.now().toString());
            task.put("finalError", finalError);
            String json = objectMapper.writeValueAsString(task);
            redisTemplate.opsForList().leftPush(TRIGGER_DLQ_KEY, json);
            logger.error("Trigger task for scheduler [{}] pushed to DLQ [{}] after exhausting all delayed rounds",
                    task.get("schedulerId"), TRIGGER_DLQ_KEY);
        } catch (Exception e) {
            logger.error("Failed to push trigger task to DLQ: {}", e.getMessage(), e);
        }
    }

    /**
     * Pop next trigger task from DLQ (FIFO rightPop) for replay
     */
    public Map<String, Object> popTriggerFromDlq() {
        try {
            String json = redisTemplate.opsForList().rightPop(TRIGGER_DLQ_KEY);
            if (json == null || json.isEmpty()) {
                return null;
            }
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            logger.warn("Error popping task from Trigger DLQ: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Re-enqueue a failed task back to DLQ with incremented attempt
     */
    public void requeueTriggerToDlq(Map<String, Object> task, String error) {
        try {
            int replayCount = task.get("dlqReplayCount") != null ? ((Number) task.get("dlqReplayCount")).intValue() + 1 : 1;
            task.put("dlqReplayCount", replayCount);
            task.put("lastDlqReplayAt", LocalDateTime.now().toString());
            task.put("lastDlqError", error);
            String json = objectMapper.writeValueAsString(task);
            redisTemplate.opsForList().leftPush(TRIGGER_DLQ_KEY, json);
            logger.warn("Re-enqueued task back to DLQ [{}]. Total DLQ replay attempts: {}", TRIGGER_DLQ_KEY, replayCount);
        } catch (Exception e) {
            logger.error("Failed to re-enqueue task to DLQ: {}", e.getMessage(), e);
        }
    }

    /**
     * Get number of trigger tasks currently in DLQ
     */
    public long getTriggerDlqSize() {
        try {
            Long size = redisTemplate.opsForList().size(TRIGGER_DLQ_KEY);
            return size != null ? size : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }

    /**
     * Get number of trigger tasks currently in Delayed Retry ZSet
     */
    public long getTriggerRetryZSetSize() {
        try {
            Long size = redisTemplate.opsForZSet().zCard(TRIGGER_RETRY_ZSET_KEY);
            return size != null ? size : 0L;
        } catch (Exception e) {
            return 0L;
        }
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
