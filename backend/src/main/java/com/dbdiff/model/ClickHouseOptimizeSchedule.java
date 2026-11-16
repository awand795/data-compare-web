package com.dbdiff.model;

import java.time.LocalDateTime;

public class ClickHouseOptimizeSchedule {
    private String id;
    private String name;
    private String connectionId;
    private String databaseName = "dw_erp";
    private boolean includeCdcTables = true;
    private String tablePattern;
    private String cronExpression = "0 0 3 1 */5 *";
    private boolean active = true;
    private LocalDateTime lastRun;
    private String lastStatus;
    private String lastSummary;
    private LocalDateTime createdAt;

    // Transient field populated by controller for UI display
    private String nextRun;

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

    public String getConnectionId() {
        return connectionId;
    }

    public void setConnectionId(String connectionId) {
        this.connectionId = connectionId;
    }

    public String getDatabaseName() {
        return databaseName;
    }

    public void setDatabaseName(String databaseName) {
        this.databaseName = databaseName;
    }

    public boolean isIncludeCdcTables() {
        return includeCdcTables;
    }

    public void setIncludeCdcTables(boolean includeCdcTables) {
        this.includeCdcTables = includeCdcTables;
    }

    public String getTablePattern() {
        return tablePattern;
    }

    public void setTablePattern(String tablePattern) {
        this.tablePattern = tablePattern;
    }

    public String getCronExpression() {
        return cronExpression;
    }

    public void setCronExpression(String cronExpression) {
        this.cronExpression = cronExpression;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public LocalDateTime getLastRun() {
        return lastRun;
    }

    public void setLastRun(LocalDateTime lastRun) {
        this.lastRun = lastRun;
    }

    public String getLastStatus() {
        return lastStatus;
    }

    public void setLastStatus(String lastStatus) {
        this.lastStatus = lastStatus;
    }

    public String getLastSummary() {
        return lastSummary;
    }

    public void setLastSummary(String lastSummary) {
        this.lastSummary = lastSummary;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public String getNextRun() {
        return nextRun;
    }

    public void setNextRun(String nextRun) {
        this.nextRun = nextRun;
    }
}
