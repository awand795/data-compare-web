package com.dbdiff.model;

import java.time.LocalDateTime;

public class EndpointTarget {
    private String id;
    private String name;
    private String url;
    private String method; // POST, GET, PUT, PATCH, DELETE
    private String headers; // JSON or key-value format
    private String description;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public EndpointTarget() {}

    public EndpointTarget(String id, String name, String url, String method, String headers, String description) {
        this.id = id;
        this.name = name;
        this.url = url;
        this.method = method;
        this.headers = headers;
        this.description = description;
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

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getMethod() {
        return method != null && !method.trim().isEmpty() ? method.toUpperCase() : "POST";
    }

    public void setMethod(String method) {
        this.method = method;
    }

    public String getHeaders() {
        return headers;
    }

    public void setHeaders(String headers) {
        this.headers = headers;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
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
}
