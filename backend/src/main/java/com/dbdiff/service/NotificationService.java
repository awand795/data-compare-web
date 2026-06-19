package com.dbdiff.service;

import com.dbdiff.model.NotificationChannel;
import com.dbdiff.repository.NotificationChannelRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.util.HashMap;
import java.util.Map;

import org.springframework.http.client.SimpleClientHttpRequestFactory;

@Service
public class NotificationService {

    private static final Logger logger = LoggerFactory.getLogger(NotificationService.class);

    private final RestTemplate restTemplate;

    public NotificationService() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10000); // 10 seconds
        factory.setReadTimeout(10000);    // 10 seconds
        this.restTemplate = new RestTemplate(factory);
    }
    
    @Autowired
    private NotificationChannelRepository channelRepository;

    public void sendToChannel(String channelId, String message) {
        NotificationChannel channel = channelRepository.findById(channelId);
        if (channel == null) {
            logger.warn("Notification channel not found: {}. Message not sent.", channelId);
            return;
        }

        if ("TELEGRAM".equals(channel.getType())) {
            sendTelegramMessage(channel.getBotToken(), channel.getChatId(), message);
        } else if ("DISCORD".equals(channel.getType())) {
            sendDiscordMessage(channel.getWebhookUrl(), message);
        }
    }

    public void sendTelegramMessage(String botToken, String chatId, String message) {
        if (botToken == null || botToken.isEmpty() || chatId == null || chatId.isEmpty()) {
            return;
        }
        try {
            String url = String.format("https://api.telegram.org/bot%s/sendMessage", botToken);
            Map<String, Object> payload = new HashMap<>();
            payload.put("chat_id", chatId);
            payload.put("text", message);
            payload.put("parse_mode", "HTML");

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(payload, headers);

            restTemplate.postForEntity(url, request, String.class);
        } catch (Exception e) {
            logger.error("Failed to send Telegram message: {}", e.getMessage(), e);
        }
    }

    public void sendDiscordMessage(String webhookUrl, String message) {
        if (webhookUrl == null || webhookUrl.isEmpty()) {
            return;
        }
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("content", message);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(payload, headers);

            restTemplate.postForEntity(webhookUrl, request, String.class);
        } catch (Exception e) {
            logger.error("Failed to send Discord message: {}", e.getMessage(), e);
        }
    }
}
