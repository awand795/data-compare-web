package com.dbdiff.model;

import java.time.LocalDateTime;

public class Company {
    private String id;
    private String appId; // Scoped per Auth App (e.g. 'bengkel-kim3')
    private String name;
    private String phone;
    private String email;
    private String address;
    private LocalDateTime createdAt;

    public Company() {}

    public Company(String id, String appId, String name, String phone, String email, String address, LocalDateTime createdAt) {
        this.id = id;
        this.appId = appId;
        this.name = name;
        this.phone = phone;
        this.email = email;
        this.address = address;
        this.createdAt = createdAt;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getAppId() { return appId; }
    public void setAppId(String appId) { this.appId = appId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
