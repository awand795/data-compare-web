package com.dbdiff.model;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class AuthApp {
    private String id;                       // slug, contoh: 'bengkel-kim3'
    private String name;                     // Nama aplikasi
    private String description;
    private String allowedRoles;             // comma-separated, contoh: 'CUSTOMER,SECURITY,SA,FOREMAN,MEKANIK,WAREHOUSE,ADMIN_INVOICE,ADMIN'
    private Integer accessTokenTtlMinutes = 15;
    private Integer refreshTokenTtlDays = 30;
    private Boolean isActive = true;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public AuthApp() {}

    public AuthApp(String id, String name, String description, String allowedRoles,
                   Integer accessTokenTtlMinutes, Integer refreshTokenTtlDays,
                   Boolean isActive, LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.allowedRoles = allowedRoles;
        this.accessTokenTtlMinutes = accessTokenTtlMinutes != null ? accessTokenTtlMinutes : 15;
        this.refreshTokenTtlDays = refreshTokenTtlDays != null ? refreshTokenTtlDays : 30;
        this.isActive = isActive != null ? isActive : true;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public List<String> getAllowedRolesList() {
        if (allowedRoles == null || allowedRoles.trim().isEmpty()) {
            return List.of();
        }
        return Arrays.stream(allowedRoles.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(String::toUpperCase)
                .collect(Collectors.toList());
    }

    public boolean isRoleAllowed(String role) {
        if (role == null) return false;
        return getAllowedRolesList().contains(role.trim().toUpperCase());
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getAllowedRoles() { return allowedRoles; }
    public void setAllowedRoles(String allowedRoles) { this.allowedRoles = allowedRoles; }

    public Integer getAccessTokenTtlMinutes() { return accessTokenTtlMinutes; }
    public void setAccessTokenTtlMinutes(Integer accessTokenTtlMinutes) { this.accessTokenTtlMinutes = accessTokenTtlMinutes; }

    public Integer getRefreshTokenTtlDays() { return refreshTokenTtlDays; }
    public void setRefreshTokenTtlDays(Integer refreshTokenTtlDays) { this.refreshTokenTtlDays = refreshTokenTtlDays; }

    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
