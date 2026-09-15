package com.dbdiff.model;

import java.time.LocalDateTime;

public class RefreshToken {
    private String id;
    private String appId; // Scoped per Auth App (e.g. 'bengkel-kim3')
    private String userId;
    private String tokenHash;
    private LocalDateTime expiresAt;
    private boolean revoked = false;
    private LocalDateTime createdAt;
    private String replacedBy;
    private String userMetadata;

    public RefreshToken() {}

    public RefreshToken(String id, String appId, String userId, String tokenHash, LocalDateTime expiresAt, boolean revoked, LocalDateTime createdAt, String replacedBy) {
        this.id = id;
        this.appId = appId;
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.revoked = revoked;
        this.createdAt = createdAt;
        this.replacedBy = replacedBy;
    }

    public RefreshToken(String id, String appId, String userId, String tokenHash, LocalDateTime expiresAt, boolean revoked, LocalDateTime createdAt, String replacedBy, String userMetadata) {
        this.id = id;
        this.appId = appId;
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.revoked = revoked;
        this.createdAt = createdAt;
        this.replacedBy = replacedBy;
        this.userMetadata = userMetadata;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getAppId() { return appId; }
    public void setAppId(String appId) { this.appId = appId; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getTokenHash() { return tokenHash; }
    public void setTokenHash(String tokenHash) { this.tokenHash = tokenHash; }

    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }

    public boolean isRevoked() { return revoked; }
    public void setRevoked(boolean revoked) { this.revoked = revoked; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public String getReplacedBy() { return replacedBy; }
    public void setReplacedBy(String replacedBy) { this.replacedBy = replacedBy; }

    public String getUserMetadata() { return userMetadata; }
    public void setUserMetadata(String userMetadata) { this.userMetadata = userMetadata; }
}
