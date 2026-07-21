package com.dbdiff.model;

import java.time.LocalDateTime;

public class ApiShareToken {
    private String id;
    private String apiEndpointId;
    private String token;
    private boolean used;
    private LocalDateTime createdAt;
    private LocalDateTime usedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getApiEndpointId() { return apiEndpointId; }
    public void setApiEndpointId(String apiEndpointId) { this.apiEndpointId = apiEndpointId; }

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }

    public boolean isUsed() { return used; }
    public void setUsed(boolean used) { this.used = used; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUsedAt() { return usedAt; }
    public void setUsedAt(LocalDateTime usedAt) { this.usedAt = usedAt; }
}
