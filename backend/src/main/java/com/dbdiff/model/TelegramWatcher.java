package com.dbdiff.model;

import java.time.LocalDateTime;

public class TelegramWatcher {
    private String id;
    private String name;
    private String botId;
    private String connectionId;
    private String watcherType = "POLLING_WATERMARK"; // POLLING_WATERMARK, PG_LISTEN_NOTIFY, WEBHOOK_TRIGGER

    // POLLING_WATERMARK fields
    private String sqlQuery;
    private String watermarkColumn = "id";
    private String lastWatermark;
    private int pollIntervalSeconds = 5;
    private String flagUpdateSql; // Optional: UPDATE tbl SET is_sent = 'Y' WHERE id = :id

    // PG_LISTEN_NOTIFY fields
    private String pgChannelName;

    // Template & Recipients
    private String templateMessage;
    private String recipientMode = "MASTER"; // MASTER or DYNAMIC_COLUMN
    private String masterRecipientIds;
    private String phoneColumnName;

    private boolean active = true;
    private String groupName = "General";
    private long totalSentCount = 0;
    private LocalDateTime lastTriggeredAt;
    private String lastStatus;
    private String lastMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getBotId() { return botId; }
    public void setBotId(String botId) { this.botId = botId; }

    public String getConnectionId() { return connectionId; }
    public void setConnectionId(String connectionId) { this.connectionId = connectionId; }

    public String getWatcherType() { return watcherType != null ? watcherType : "POLLING_WATERMARK"; }
    public void setWatcherType(String watcherType) { this.watcherType = watcherType; }

    public String getSqlQuery() { return sqlQuery; }
    public void setSqlQuery(String sqlQuery) { this.sqlQuery = sqlQuery; }

    public String getWatermarkColumn() { return watermarkColumn; }
    public void setWatermarkColumn(String watermarkColumn) { this.watermarkColumn = watermarkColumn; }

    public String getLastWatermark() { return lastWatermark; }
    public void setLastWatermark(String lastWatermark) { this.lastWatermark = lastWatermark; }

    public int getPollIntervalSeconds() { return pollIntervalSeconds > 0 ? pollIntervalSeconds : 5; }
    public void setPollIntervalSeconds(int pollIntervalSeconds) { this.pollIntervalSeconds = pollIntervalSeconds; }

    public String getFlagUpdateSql() { return flagUpdateSql; }
    public void setFlagUpdateSql(String flagUpdateSql) { this.flagUpdateSql = flagUpdateSql; }

    public String getPgChannelName() { return pgChannelName; }
    public void setPgChannelName(String pgChannelName) { this.pgChannelName = pgChannelName; }

    public String getTemplateMessage() { return templateMessage; }
    public void setTemplateMessage(String templateMessage) { this.templateMessage = templateMessage; }

    public String getRecipientMode() { return recipientMode != null ? recipientMode : "MASTER"; }
    public void setRecipientMode(String recipientMode) { this.recipientMode = recipientMode; }

    public String getMasterRecipientIds() { return masterRecipientIds; }
    public void setMasterRecipientIds(String masterRecipientIds) { this.masterRecipientIds = masterRecipientIds; }

    public String getPhoneColumnName() { return phoneColumnName; }
    public void setPhoneColumnName(String phoneColumnName) { this.phoneColumnName = phoneColumnName; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public String getGroupName() { return (groupName != null && !groupName.trim().isEmpty()) ? groupName.trim() : "General"; }
    public void setGroupName(String groupName) { this.groupName = groupName; }

    public long getTotalSentCount() { return totalSentCount; }
    public void setTotalSentCount(long totalSentCount) { this.totalSentCount = totalSentCount; }

    public LocalDateTime getLastTriggeredAt() { return lastTriggeredAt; }
    public void setLastTriggeredAt(LocalDateTime lastTriggeredAt) { this.lastTriggeredAt = lastTriggeredAt; }

    public String getLastStatus() { return lastStatus; }
    public void setLastStatus(String lastStatus) { this.lastStatus = lastStatus; }

    public String getLastMessage() { return lastMessage; }
    public void setLastMessage(String lastMessage) { this.lastMessage = lastMessage; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
