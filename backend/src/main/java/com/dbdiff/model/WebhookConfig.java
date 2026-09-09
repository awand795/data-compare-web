package com.dbdiff.model;

import java.time.LocalDateTime;

public class WebhookConfig {
    private String id;
    private String name;
    private String slug; // Unique path segment for webhook URL
    private String description;
    private String groupName = "General";

    // Security & Auth
    private String secretHeaderName; // e.g. "X-Ginee-Signature" or "Authorization"
    private String secretHeaderValue; // expected secret token / signature
    private String ipAllowlist; // Comma-separated IPs or CIDR blocks

    // Target Storage
    private String targetConnectionId;
    private String targetTable;
    private String kodeData = "WEBHOOK";

    // Detail Data Enrichment (Ginee API Fetcher)
    private boolean enableEnrichment = false;
    private String enrichmentFilterStatus = "READY_TO_SHIP";
    private String enrichmentTargetConnectionId;
    private String enrichmentTargetTable;
    private String enrichmentKodeData = "GINEE_READY_TO_SHIP";
    private String enrichmentGineeAccessKey;
    private String enrichmentGineeSecretKey;

    // Alerts
    private String notificationChannelId; // Comma-separated channel IDs (Telegram/Discord)

    private boolean active = true;

    // Tracking & Stats
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime lastTriggeredAt;
    private String lastStatus; // SUCCESS, FAILED, UNAUTHORIZED, INVALID_SCHEMA
    private String lastMessage;
    private long totalRequests = 0;
    private long successCount = 0;
    private long failureCount = 0;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSlug() {
        return slug;
    }

    public void setSlug(String slug) {
        this.slug = slug;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getGroupName() {
        return groupName;
    }

    public void setGroupName(String groupName) {
        this.groupName = groupName;
    }

    public String getSecretHeaderName() {
        return secretHeaderName;
    }

    public void setSecretHeaderName(String secretHeaderName) {
        this.secretHeaderName = secretHeaderName;
    }

    public String getSecretHeaderValue() {
        return secretHeaderValue;
    }

    public void setSecretHeaderValue(String secretHeaderValue) {
        this.secretHeaderValue = secretHeaderValue;
    }

    public String getIpAllowlist() {
        return ipAllowlist;
    }

    public void setIpAllowlist(String ipAllowlist) {
        this.ipAllowlist = ipAllowlist;
    }

    public String getTargetConnectionId() {
        return targetConnectionId;
    }

    public void setTargetConnectionId(String targetConnectionId) {
        this.targetConnectionId = targetConnectionId;
    }

    public String getTargetTable() {
        return targetTable;
    }

    public void setTargetTable(String targetTable) {
        this.targetTable = targetTable;
    }

    public String getKodeData() {
        return kodeData;
    }

    public void setKodeData(String kodeData) {
        this.kodeData = kodeData;
    }

    public String getNotificationChannelId() {
        return notificationChannelId;
    }

    public void setNotificationChannelId(String notificationChannelId) {
        this.notificationChannelId = notificationChannelId;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public LocalDateTime getLastTriggeredAt() {
        return lastTriggeredAt;
    }

    public void setLastTriggeredAt(LocalDateTime lastTriggeredAt) {
        this.lastTriggeredAt = lastTriggeredAt;
    }

    public String getLastStatus() {
        return lastStatus;
    }

    public void setLastStatus(String lastStatus) {
        this.lastStatus = lastStatus;
    }

    public String getLastMessage() {
        return lastMessage;
    }

    public void setLastMessage(String lastMessage) {
        this.lastMessage = lastMessage;
    }

    public long getTotalRequests() {
        return totalRequests;
    }

    public void setTotalRequests(long totalRequests) {
        this.totalRequests = totalRequests;
    }

    public long getSuccessCount() {
        return successCount;
    }

    public void setSuccessCount(long successCount) {
        this.successCount = successCount;
    }

    public long getFailureCount() {
        return failureCount;
    }

    public void setFailureCount(long failureCount) {
        this.failureCount = failureCount;
    }

    public boolean isEnableEnrichment() {
        return enableEnrichment;
    }

    public void setEnableEnrichment(boolean enableEnrichment) {
        this.enableEnrichment = enableEnrichment;
    }

    public String getEnrichmentFilterStatus() {
        return enrichmentFilterStatus;
    }

    public void setEnrichmentFilterStatus(String enrichmentFilterStatus) {
        this.enrichmentFilterStatus = enrichmentFilterStatus;
    }

    public String getEnrichmentTargetConnectionId() {
        return enrichmentTargetConnectionId;
    }

    public void setEnrichmentTargetConnectionId(String enrichmentTargetConnectionId) {
        this.enrichmentTargetConnectionId = enrichmentTargetConnectionId;
    }

    public String getEnrichmentTargetTable() {
        return enrichmentTargetTable;
    }

    public void setEnrichmentTargetTable(String enrichmentTargetTable) {
        this.enrichmentTargetTable = enrichmentTargetTable;
    }

    public String getEnrichmentKodeData() {
        return enrichmentKodeData;
    }

    public void setEnrichmentKodeData(String enrichmentKodeData) {
        this.enrichmentKodeData = enrichmentKodeData;
    }

    public String getEnrichmentGineeAccessKey() {
        return enrichmentGineeAccessKey;
    }

    public void setEnrichmentGineeAccessKey(String enrichmentGineeAccessKey) {
        this.enrichmentGineeAccessKey = enrichmentGineeAccessKey;
    }

    public String getEnrichmentGineeSecretKey() {
        return enrichmentGineeSecretKey;
    }

    public void setEnrichmentGineeSecretKey(String enrichmentGineeSecretKey) {
        this.enrichmentGineeSecretKey = enrichmentGineeSecretKey;
    }
}
