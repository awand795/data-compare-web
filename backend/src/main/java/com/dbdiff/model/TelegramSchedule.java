package com.dbdiff.model;

import java.time.LocalDateTime;

public class TelegramSchedule {
    private String id;
    private String name;
    private String botId;
    private String connectionId;
    private String sqlQuery;
    private String templateMessage;
    private String recipientMode; // "MASTER" or "DYNAMIC_COLUMN"
    private String masterRecipientIds; // Comma or JSON array of contact IDs / chat IDs
    private String phoneColumnName; // Column name if recipientMode is "DYNAMIC_COLUMN"
    private String schemaName; // e.g. "sch_erp_system"
    private String viewName;   // e.g. "view_all_notifikasi"
    private String filterSchedule; // "ALL", "Y", "N"
    private String filterKodeNotifikasi; // e.g. "DAILY-REPORT-SALES"
    private String filterKodeCabang; // e.g. "P001"
    private String cronExpression; // Single or comma-separated multi-cron expressions
    private int maxRows = 50;
    private boolean active = true;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime lastRunAt;
    private String lastRunStatus; // SUCCESS, FAILED, PARTIAL
    private String lastRunMessage;

    public String getSchemaName() {
        return schemaName;
    }

    public void setSchemaName(String schemaName) {
        this.schemaName = schemaName;
    }

    public String getViewName() {
        return viewName;
    }

    public void setViewName(String viewName) {
        this.viewName = viewName;
    }

    public String getFilterSchedule() {
        return filterSchedule;
    }

    public void setFilterSchedule(String filterSchedule) {
        this.filterSchedule = filterSchedule;
    }

    public String getFilterKodeNotifikasi() {
        return filterKodeNotifikasi;
    }

    public void setFilterKodeNotifikasi(String filterKodeNotifikasi) {
        this.filterKodeNotifikasi = filterKodeNotifikasi;
    }

    public String getFilterKodeCabang() {
        return filterKodeCabang;
    }

    public void setFilterKodeCabang(String filterKodeCabang) {
        this.filterKodeCabang = filterKodeCabang;
    }

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

    public String getBotId() {
        return botId;
    }

    public void setBotId(String botId) {
        this.botId = botId;
    }

    public String getConnectionId() {
        return connectionId;
    }

    public void setConnectionId(String connectionId) {
        this.connectionId = connectionId;
    }

    public String getSqlQuery() {
        return sqlQuery;
    }

    public void setSqlQuery(String sqlQuery) {
        this.sqlQuery = sqlQuery;
    }

    public String getTemplateMessage() {
        return templateMessage;
    }

    public void setTemplateMessage(String templateMessage) {
        this.templateMessage = templateMessage;
    }

    public String getRecipientMode() {
        return recipientMode;
    }

    public void setRecipientMode(String recipientMode) {
        this.recipientMode = recipientMode;
    }

    public String getMasterRecipientIds() {
        return masterRecipientIds;
    }

    public void setMasterRecipientIds(String masterRecipientIds) {
        this.masterRecipientIds = masterRecipientIds;
    }

    public String getPhoneColumnName() {
        return phoneColumnName;
    }

    public void setPhoneColumnName(String phoneColumnName) {
        this.phoneColumnName = phoneColumnName;
    }

    public String getCronExpression() {
        return cronExpression;
    }

    public void setCronExpression(String cronExpression) {
        this.cronExpression = cronExpression;
    }

    public int getMaxRows() {
        return maxRows;
    }

    public void setMaxRows(int maxRows) {
        this.maxRows = maxRows;
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

    public LocalDateTime getLastRunAt() {
        return lastRunAt;
    }

    public void setLastRunAt(LocalDateTime lastRunAt) {
        this.lastRunAt = lastRunAt;
    }

    public String getLastRunStatus() {
        return lastRunStatus;
    }

    public void setLastRunStatus(String lastRunStatus) {
        this.lastRunStatus = lastRunStatus;
    }

    public String getLastRunMessage() {
        return lastRunMessage;
    }

    public void setLastRunMessage(String lastRunMessage) {
        this.lastRunMessage = lastRunMessage;
    }
}
