package com.dbdiff.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;

public class ApiEndpoint {
    private String id;
    private String name;
    private String method;
    private String endpointPath;
    private String connectionId;
    private String sqlQuery;
    private String parameters;
    private boolean enablePagination;
    
    @JsonProperty("isPublic")
    @JsonAlias({"public", "isPublic"})
    private boolean isPublic;

    @JsonProperty("allowRawSql")
    @JsonAlias({"allowRawSql"})
    private boolean allowRawSql;

    private String ipAllowlist;
    private String groupName;
    private String authToken;

    // ── Scheduled Push (Spring Cron) & Failure Notification ──────────────────
    private boolean cronEnabled = false;
    private String cronExpression;
    private String targetEndpointId; // Optional reference to EndpointTarget
    private String targetUrl;
    private String targetMethod = "POST";
    private String targetHeaders;
    private String notificationChannelId; // Telegram / Discord channel IDs (separated by ;)
    private boolean notifyOnSuccess = false;
    private boolean notifyOnFailure = true;
    private LocalDateTime lastPushAt;
    private String lastPushStatus; // SUCCESS or FAILED
    private String lastPushMessage;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getMethod() { return method; }
    public void setMethod(String method) { this.method = method; }

    public String getEndpointPath() { return endpointPath; }
    public void setEndpointPath(String endpointPath) { this.endpointPath = endpointPath; }

    public String getConnectionId() { return connectionId; }
    public void setConnectionId(String connectionId) { this.connectionId = connectionId; }

    public String getSqlQuery() { return sqlQuery; }
    public void setSqlQuery(String sqlQuery) { this.sqlQuery = sqlQuery; }

    public String getParameters() { return parameters; }
    public void setParameters(String parameters) { this.parameters = parameters; }

    public boolean isEnablePagination() { return enablePagination; }
    public void setEnablePagination(boolean enablePagination) { this.enablePagination = enablePagination; }

    @JsonProperty("isPublic")
    public boolean isPublic() { return isPublic; }

    @JsonProperty("isPublic")
    public void setPublic(boolean aPublic) { isPublic = aPublic; }

    @JsonProperty("allowRawSql")
    public boolean isAllowRawSql() { return allowRawSql; }

    @JsonProperty("allowRawSql")
    public void setAllowRawSql(boolean allowRawSql) { this.allowRawSql = allowRawSql; }

    public String getIpAllowlist() { return ipAllowlist; }
    public void setIpAllowlist(String ipAllowlist) { this.ipAllowlist = ipAllowlist; }

    public String getGroupName() { return groupName != null && !groupName.trim().isEmpty() ? groupName : "General"; }
    public void setGroupName(String groupName) { this.groupName = groupName; }

    public String getAuthToken() { return authToken; }
    public void setAuthToken(String authToken) { this.authToken = authToken; }

    public boolean isCronEnabled() { return cronEnabled; }
    public void setCronEnabled(boolean cronEnabled) { this.cronEnabled = cronEnabled; }

    public String getCronExpression() { return cronExpression; }
    public void setCronExpression(String cronExpression) { this.cronExpression = cronExpression; }

    public String getTargetEndpointId() { return targetEndpointId; }
    public void setTargetEndpointId(String targetEndpointId) { this.targetEndpointId = targetEndpointId; }

    public String getTargetUrl() { return targetUrl; }
    public void setTargetUrl(String targetUrl) { this.targetUrl = targetUrl; }

    public String getTargetMethod() { return targetMethod != null && !targetMethod.trim().isEmpty() ? targetMethod.toUpperCase() : "POST"; }
    public void setTargetMethod(String targetMethod) { this.targetMethod = targetMethod; }

    public String getTargetHeaders() { return targetHeaders; }
    public void setTargetHeaders(String targetHeaders) { this.targetHeaders = targetHeaders; }

    public String getNotificationChannelId() { return notificationChannelId; }
    public void setNotificationChannelId(String notificationChannelId) { this.notificationChannelId = notificationChannelId; }

    public boolean isNotifyOnSuccess() { return notifyOnSuccess; }
    public void setNotifyOnSuccess(boolean notifyOnSuccess) { this.notifyOnSuccess = notifyOnSuccess; }

    public boolean isNotifyOnFailure() { return notifyOnFailure; }
    public void setNotifyOnFailure(boolean notifyOnFailure) { this.notifyOnFailure = notifyOnFailure; }

    public LocalDateTime getLastPushAt() { return lastPushAt; }
    public void setLastPushAt(LocalDateTime lastPushAt) { this.lastPushAt = lastPushAt; }

    public String getLastPushStatus() { return lastPushStatus; }
    public void setLastPushStatus(String lastPushStatus) { this.lastPushStatus = lastPushStatus; }

    public String getLastPushMessage() { return lastPushMessage; }
    public void setLastPushMessage(String lastPushMessage) { this.lastPushMessage = lastPushMessage; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
