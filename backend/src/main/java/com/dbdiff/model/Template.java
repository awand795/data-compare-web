package com.dbdiff.model;

import java.time.LocalDateTime;

public class Template {
    private String id;
    private String name;
    private String appMode;
    private String sourceConnectionId;
    private String targetConnectionId;
    private String tableMappings; // JSON string
    private String customQuerySource;
    private String customQueryTarget;
    private LocalDateTime createdAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getAppMode() { return appMode; }
    public void setAppMode(String appMode) { this.appMode = appMode; }
    public String getSourceConnectionId() { return sourceConnectionId; }
    public void setSourceConnectionId(String sourceConnectionId) { this.sourceConnectionId = sourceConnectionId; }
    public String getTargetConnectionId() { return targetConnectionId; }
    public void setTargetConnectionId(String targetConnectionId) { this.targetConnectionId = targetConnectionId; }
    public String getTableMappings() { return tableMappings; }
    public void setTableMappings(String tableMappings) { this.tableMappings = tableMappings; }
    public String getCustomQuerySource() { return customQuerySource; }
    public void setCustomQuerySource(String customQuerySource) { this.customQuerySource = customQuerySource; }
    public String getCustomQueryTarget() { return customQueryTarget; }
    public void setCustomQueryTarget(String customQueryTarget) { this.customQueryTarget = customQueryTarget; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
