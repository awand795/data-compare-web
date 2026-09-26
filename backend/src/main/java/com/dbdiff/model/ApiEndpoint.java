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
    private String requiredAppId; // Optional: restrict endpoint to specific Auth App
    private String groupName;
    private String authToken;
    private String securityMode = "API_KEY"; // PUBLIC, JWT_AUTH, API_KEY, HYBRID (evaluated in getSecurityMode)
    private String allowedRoles; // Comma-separated list of allowed user roles (e.g. "ADMIN, SPV, MEKANIK")
    private String successMessage;
    private String validationRules;

    // ── Direct Auth Handling (Login, Register, Refresh Token) ──────────────
    private String authAction = "NONE"; // NONE, LOGIN, REGISTER, REFRESH_TOKEN
    private String passwordParam = "password";
    private String passwordHashColumn = "password_hash";
    private Integer tokenTtlMinutes = 15;
    private Integer refreshTokenTtlDays = 30;

    // ── Direct Database File / Photo Upload & Compression ─────────────────
    private boolean enableFileUpload = false;
    private String fileParamName = "foto";
    private String allowedExtensions = "jpg,jpeg,png,webp";
    private Integer maxFileSizeMb = 10;
    private boolean autoCompressImage = true;
    private Integer imageQualityPercent = 80;
    private Integer imageMaxWidth = 1920;
    private Integer imageMaxHeight = 1920;

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

    public String getSuccessMessage() { return successMessage; }
    public void setSuccessMessage(String successMessage) { this.successMessage = successMessage; }

    public String getValidationRules() { return validationRules; }
    public void setValidationRules(String validationRules) { this.validationRules = validationRules; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public String getRequiredAppId() { return requiredAppId; }
    public void setRequiredAppId(String requiredAppId) { this.requiredAppId = requiredAppId; }

    public String getAuthAction() { return authAction != null ? authAction.toUpperCase() : "NONE"; }
    public void setAuthAction(String authAction) { this.authAction = authAction; }

    public String getPasswordParam() { return passwordParam != null && !passwordParam.trim().isEmpty() ? passwordParam.trim() : "password"; }
    public void setPasswordParam(String passwordParam) { this.passwordParam = passwordParam; }

    public String getPasswordHashColumn() { return passwordHashColumn != null && !passwordHashColumn.trim().isEmpty() ? passwordHashColumn.trim() : "password_hash"; }
    public void setPasswordHashColumn(String passwordHashColumn) { this.passwordHashColumn = passwordHashColumn; }

    public Integer getTokenTtlMinutes() { return tokenTtlMinutes != null && tokenTtlMinutes > 0 ? tokenTtlMinutes : 15; }
    public void setTokenTtlMinutes(Integer tokenTtlMinutes) { this.tokenTtlMinutes = tokenTtlMinutes; }

    public Integer getRefreshTokenTtlDays() { return refreshTokenTtlDays != null && refreshTokenTtlDays > 0 ? refreshTokenTtlDays : 30; }
    public void setRefreshTokenTtlDays(Integer refreshTokenTtlDays) { this.refreshTokenTtlDays = refreshTokenTtlDays; }

    public String getSecurityMode() {
        if (securityMode != null && !securityMode.trim().isEmpty()) return securityMode.toUpperCase();
        if (isPublic) return "PUBLIC";
        return "API_KEY";
    }
    public void setSecurityMode(String securityMode) {
        this.securityMode = securityMode;
        if ("PUBLIC".equalsIgnoreCase(securityMode)) {
            this.isPublic = true;
        } else {
            this.isPublic = false;
        }
    }

    public String getAllowedRoles() { return allowedRoles; }
    public void setAllowedRoles(String allowedRoles) { this.allowedRoles = allowedRoles; }

    public boolean isEnableFileUpload() { return enableFileUpload; }
    public void setEnableFileUpload(boolean enableFileUpload) { this.enableFileUpload = enableFileUpload; }

    public String getFileParamName() { return fileParamName != null && !fileParamName.trim().isEmpty() ? fileParamName.trim() : "foto"; }
    public void setFileParamName(String fileParamName) { this.fileParamName = fileParamName; }

    public String getAllowedExtensions() { return allowedExtensions != null && !allowedExtensions.trim().isEmpty() ? allowedExtensions.trim() : "jpg,jpeg,png,webp"; }
    public void setAllowedExtensions(String allowedExtensions) { this.allowedExtensions = allowedExtensions; }

    public Integer getMaxFileSizeMb() { return maxFileSizeMb != null && maxFileSizeMb > 0 ? maxFileSizeMb : 10; }
    public void setMaxFileSizeMb(Integer maxFileSizeMb) { this.maxFileSizeMb = maxFileSizeMb; }

    public boolean isAutoCompressImage() { return autoCompressImage; }
    public void setAutoCompressImage(boolean autoCompressImage) { this.autoCompressImage = autoCompressImage; }

    public Integer getImageQualityPercent() { return imageQualityPercent != null && imageQualityPercent > 0 ? Math.min(100, Math.max(1, imageQualityPercent)) : 80; }
    public void setImageQualityPercent(Integer imageQualityPercent) { this.imageQualityPercent = imageQualityPercent; }

    public Integer getImageMaxWidth() { return imageMaxWidth != null && imageMaxWidth > 0 ? imageMaxWidth : 1920; }
    public void setImageMaxWidth(Integer imageMaxWidth) { this.imageMaxWidth = imageMaxWidth; }

    public Integer getImageMaxHeight() { return imageMaxHeight != null && imageMaxHeight > 0 ? imageMaxHeight : 1920; }
    public void setImageMaxHeight(Integer imageMaxHeight) { this.imageMaxHeight = imageMaxHeight; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
