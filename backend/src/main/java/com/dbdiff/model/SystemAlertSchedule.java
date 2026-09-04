package com.dbdiff.model;

import java.time.LocalDateTime;

public class SystemAlertSchedule {
    private String id;
    private String name;
    private String targetDisk = "all";
    private int diskThresholdPercent = 70;
    private int ramThresholdPercent = 80;
    private boolean checkDisk = true;
    private boolean checkRam = true;
    private String cronExpression;
    private String channelIds;
    private boolean active = true;
    private int cooldownMinutes = 30;
    private LocalDateTime lastRun;
    private String lastStatus;
    private LocalDateTime lastAlertTime;
    private LocalDateTime createdAt;

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

    public String getTargetDisk() {
        return targetDisk;
    }

    public void setTargetDisk(String targetDisk) {
        this.targetDisk = targetDisk;
    }

    public int getDiskThresholdPercent() {
        return diskThresholdPercent;
    }

    public void setDiskThresholdPercent(int diskThresholdPercent) {
        this.diskThresholdPercent = diskThresholdPercent;
    }

    public int getRamThresholdPercent() {
        return ramThresholdPercent;
    }

    public void setRamThresholdPercent(int ramThresholdPercent) {
        this.ramThresholdPercent = ramThresholdPercent;
    }

    public boolean isCheckDisk() {
        return checkDisk;
    }

    public void setCheckDisk(boolean checkDisk) {
        this.checkDisk = checkDisk;
    }

    public boolean isCheckRam() {
        return checkRam;
    }

    public void setCheckRam(boolean checkRam) {
        this.checkRam = checkRam;
    }

    public String getCronExpression() {
        return cronExpression;
    }

    public void setCronExpression(String cronExpression) {
        this.cronExpression = cronExpression;
    }

    public String getChannelIds() {
        return channelIds;
    }

    public void setChannelIds(String channelIds) {
        this.channelIds = channelIds;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public int getCooldownMinutes() {
        return cooldownMinutes;
    }

    public void setCooldownMinutes(int cooldownMinutes) {
        this.cooldownMinutes = cooldownMinutes;
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

    public LocalDateTime getLastAlertTime() {
        return lastAlertTime;
    }

    public void setLastAlertTime(LocalDateTime lastAlertTime) {
        this.lastAlertTime = lastAlertTime;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
