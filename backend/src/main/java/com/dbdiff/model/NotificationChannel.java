package com.dbdiff.model;

import java.time.LocalDateTime;

public class NotificationChannel {
    private String id;
    private String name;
    private String type; // TELEGRAM or DISCORD
    private String botToken;
    private String chatId;
    private String webhookUrl;
    private LocalDateTime createdAt;

    public String getId() {
        return this.id;
    }
    public void setId(String id) {
        this.id = id;
    }
    public String getName() {
        return this.name;
    }
    public void setName(String name) {
        this.name = name;
    }
    public String getType() {
        return this.type;
    }
    public void setType(String type) {
        this.type = type;
    }
    public String getBotToken() {
        return this.botToken;
    }
    public void setBotToken(String botToken) {
        this.botToken = botToken;
    }
    public String getChatId() {
        return this.chatId;
    }
    public void setChatId(String chatId) {
        this.chatId = chatId;
    }
    public String getWebhookUrl() {
        return this.webhookUrl;
    }
    public void setWebhookUrl(String webhookUrl) {
        this.webhookUrl = webhookUrl;
    }
    public LocalDateTime getCreatedAt() {
        return this.createdAt;
    }
    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
