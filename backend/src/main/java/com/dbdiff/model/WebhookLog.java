package com.dbdiff.model;

import java.time.LocalDateTime;

public class WebhookLog {
    private String id;
    private String webhookId;
    private String webhookSlug;
    private LocalDateTime receivedAt;
    private String sourceIp;
    private String httpMethod;
    private String headers;
    private String payload;
    private String status; // SUCCESS, FAILED, UNAUTHORIZED, INVALID_SCHEMA, INACTIVE
    private int statusCode;
    private String errorMessage;
    private long durationMs;
    private String targetTable;
    private int rowsInserted;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getWebhookId() {
        return webhookId;
    }

    public void setWebhookId(String webhookId) {
        this.webhookId = webhookId;
    }

    public String getWebhookSlug() {
        return webhookSlug;
    }

    public void setWebhookSlug(String webhookSlug) {
        this.webhookSlug = webhookSlug;
    }

    public LocalDateTime getReceivedAt() {
        return receivedAt;
    }

    public void setReceivedAt(LocalDateTime receivedAt) {
        this.receivedAt = receivedAt;
    }

    public String getSourceIp() {
        return sourceIp;
    }

    public void setSourceIp(String sourceIp) {
        this.sourceIp = sourceIp;
    }

    public String getHttpMethod() {
        return httpMethod;
    }

    public void setHttpMethod(String httpMethod) {
        this.httpMethod = httpMethod;
    }

    public String getHeaders() {
        return headers;
    }

    public void setHeaders(String headers) {
        this.headers = headers;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public void setStatusCode(int statusCode) {
        this.statusCode = statusCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(long durationMs) {
        this.durationMs = durationMs;
    }

    public String getTargetTable() {
        return targetTable;
    }

    public void setTargetTable(String targetTable) {
        this.targetTable = targetTable;
    }

    public int getRowsInserted() {
        return rowsInserted;
    }

    public void setRowsInserted(int rowsInserted) {
        this.rowsInserted = rowsInserted;
    }
}
