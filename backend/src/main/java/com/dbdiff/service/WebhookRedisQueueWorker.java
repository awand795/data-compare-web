package com.dbdiff.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class WebhookRedisQueueWorker {

    private static final Logger logger = LoggerFactory.getLogger(WebhookRedisQueueWorker.class);

    private final WebhookRedisQueueService queueService;
    private final WebhookService webhookService;
    private final ApiSchedulerService apiSchedulerService;

    @Autowired
    public WebhookRedisQueueWorker(WebhookRedisQueueService queueService,
                                   WebhookService webhookService,
                                   @Lazy ApiSchedulerService apiSchedulerService) {
        this.queueService = queueService;
        this.webhookService = webhookService;
        this.apiSchedulerService = apiSchedulerService;
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

    /**
     * Poll darkosync:triggers:retry_zset every 5 seconds for due delayed retry tasks
     */
    @Scheduled(fixedDelay = 5000)
    public void processDelayedTriggerRetries() {
        if (!queueService.isRedisAvailable()) {
            return;
        }

        List<Map<String, Object>> dueTasks = queueService.popDueTriggerRetries(10);
        for (Map<String, Object> task : dueTasks) {
            try {
                String schedulerId = (String) task.get("schedulerId");
                @SuppressWarnings("unchecked")
                Map<String, String> dynamicParams = (Map<String, String>) task.get("dynamicParams");
                String rawPayload = (String) task.get("rawPayload");
                int round = task.get("round") != null ? ((Number) task.get("round")).intValue() : 1;

                logger.info("Executing delayed retry (round {}/3) for scheduler [{}] with params: {}",
                        round, schedulerId, dynamicParams);

                apiSchedulerService.executeTriggerWithParamsInternal(schedulerId, dynamicParams, rawPayload, round);
            } catch (Exception e) {
                logger.error("Error executing delayed trigger retry: {}", e.getMessage(), e);
                queueService.pushTriggerToDlq(task, e.getMessage());
            }
        }
    }
}
