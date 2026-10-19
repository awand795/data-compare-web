package com.dbdiff.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class WebhookRedisQueueWorker {

    private static final Logger logger = LoggerFactory.getLogger(WebhookRedisQueueWorker.class);

    private final WebhookRedisQueueService queueService;
    private final WebhookService webhookService;

    @Autowired
    public WebhookRedisQueueWorker(WebhookRedisQueueService queueService, WebhookService webhookService) {
        this.queueService = queueService;
        this.webhookService = webhookService;
    }

    /**
     * Poll darkosync-redis queue every 200ms and process pending webhooks
     */
    @Scheduled(fixedDelay = 200)
    public void processQueue() {
        if (!queueService.isRedisAvailable()) {
            return;
        }

        int processedInCycle = 0;
        while (processedInCycle < 50) {
            Map<String, Object> event = queueService.popWebhook();
            if (event == null) {
                break;
            }

            try {
                String slug = (String) event.get("slug");
                String payload = (String) event.get("payload");
                String headersJson = (String) event.get("headersJson");
                String clientIp = (String) event.get("clientIp");
                String httpMethod = (String) event.get("httpMethod");

                webhookService.processQueuedWebhook(slug, payload, headersJson, clientIp, httpMethod);
                processedInCycle++;
            } catch (Exception e) {
                logger.error("Error processing queued webhook event from Redis: {}", e.getMessage(), e);
                queueService.pushToDeadLetterQueue(event, e.getMessage());
            }
        }
    }
}
