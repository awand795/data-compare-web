package com.dbdiff.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;

public class ScheduleConfig {
    private String id;
    private String name;
    private String sourceConnectionId;
    private String targetConnectionId;
    private String sourceTable;
    private String targetTable;

    // Mapping properties
    private String customQuerySource;
    private String customQueryTarget;
    private String primaryKeys; // JSON array string
    private String excludeColumns; // JSON array string
    private String sortColumns; // JSON array string

    private String cronExpression;

    // Legacy notification fields
    private String telegramBotToken;
    private String telegramChatId;
    private String discordWebhookUrl;

    // New notification profile IDs
    private String telegramChannelId;
    private String discordChannelId;

    private boolean saveFullData;
    
    private boolean active;

    private String mappings; // JSON array of TableMapping objects
    private LocalDateTime createdAt;
    private LocalDateTime lastRun;

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
    public String getSourceConnectionId() {
        return this.sourceConnectionId;
    }
    public void setSourceConnectionId(String sourceConnectionId) {
        this.sourceConnectionId = sourceConnectionId;
    }
    public String getTargetConnectionId() {
        return this.targetConnectionId;
    }
    public void setTargetConnectionId(String targetConnectionId) {
        this.targetConnectionId = targetConnectionId;
    }
    public String getSourceTable() {
        return this.sourceTable;
    }
    public void setSourceTable(String sourceTable) {
        this.sourceTable = sourceTable;
    }
    public String getTargetTable() {
        return this.targetTable;
    }
    public void setTargetTable(String targetTable) {
        this.targetTable = targetTable;
    }
    public String getCustomQuerySource() {
        return this.customQuerySource;
    }
    public void setCustomQuerySource(String customQuerySource) {
        this.customQuerySource = customQuerySource;
    }
    public String getCustomQueryTarget() {
        return this.customQueryTarget;
    }
    public void setCustomQueryTarget(String customQueryTarget) {
        this.customQueryTarget = customQueryTarget;
    }
    public String getPrimaryKeys() {
        return this.primaryKeys;
    }
    public void setPrimaryKeys(String primaryKeys) {
        this.primaryKeys = primaryKeys;
    }
    public String getExcludeColumns() {
        return this.excludeColumns;
    }
    public void setExcludeColumns(String excludeColumns) {
        this.excludeColumns = excludeColumns;
    }
    public String getSortColumns() {
        return this.sortColumns;
    }
    public void setSortColumns(String sortColumns) {
        this.sortColumns = sortColumns;
    }
    public String getCronExpression() {
        return this.cronExpression;
    }
    public void setCronExpression(String cronExpression) {
        this.cronExpression = cronExpression;
    }
    public String getTelegramBotToken() {
        return this.telegramBotToken;
    }
    public void setTelegramBotToken(String telegramBotToken) {
        this.telegramBotToken = telegramBotToken;
    }
    public String getTelegramChatId() {
        return this.telegramChatId;
    }
    public void setTelegramChatId(String telegramChatId) {
        this.telegramChatId = telegramChatId;
    }
    public String getDiscordWebhookUrl() {
        return this.discordWebhookUrl;
    }
    public void setDiscordWebhookUrl(String discordWebhookUrl) {
        this.discordWebhookUrl = discordWebhookUrl;
    }
    public String getTelegramChannelId() {
        return this.telegramChannelId;
    }
    public void setTelegramChannelId(String telegramChannelId) {
        this.telegramChannelId = telegramChannelId;
    }
    public String getDiscordChannelId() {
        return this.discordChannelId;
    }
    public void setDiscordChannelId(String discordChannelId) {
        this.discordChannelId = discordChannelId;
    }
    public boolean isSaveFullData() {
        return this.saveFullData;
    }
    public void setSaveFullData(boolean saveFullData) {
        this.saveFullData = saveFullData;
    }

    @JsonProperty("isActive")
    public boolean isActive() {
        return this.active;
    }
    public void setActive(boolean active) {
        this.active = active;
    }

    public String getMappings() {
        return this.mappings;
    }
    public void setMappings(String mappings) {
        this.mappings = mappings;
    }
    public LocalDateTime getCreatedAt() {
        return this.createdAt;
    }
    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
    public LocalDateTime getLastRun() {
        return this.lastRun;
    }
    public void setLastRun(LocalDateTime lastRun) {
        this.lastRun = lastRun;
    }
}
