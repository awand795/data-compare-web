package com.dbdiff.model;

import java.time.LocalDateTime;

public class ScheduleResult {
    private String id;
    private String scheduleId;
    private LocalDateTime runTime;
    private int matchCount;
    private int differentCount;
    private int sourceOnlyCount;
    private int targetOnlyCount;
    private String errorMessage;
    private String details; // JSON array of detailed results per mapping

    public String getId() {
        return this.id;
    }
    public void setId(String id) {
        this.id = id;
    }
    public String getScheduleId() {
        return this.scheduleId;
    }
    public void setScheduleId(String scheduleId) {
        this.scheduleId = scheduleId;
    }
    public LocalDateTime getRunTime() {
        return this.runTime;
    }
    public void setRunTime(LocalDateTime runTime) {
        this.runTime = runTime;
    }
    public int getMatchCount() {
        return this.matchCount;
    }
    public void setMatchCount(int matchCount) {
        this.matchCount = matchCount;
    }
    public int getDifferentCount() {
        return this.differentCount;
    }
    public void setDifferentCount(int differentCount) {
        this.differentCount = differentCount;
    }
    public int getSourceOnlyCount() {
        return this.sourceOnlyCount;
    }
    public void setSourceOnlyCount(int sourceOnlyCount) {
        this.sourceOnlyCount = sourceOnlyCount;
    }
    public int getTargetOnlyCount() {
        return this.targetOnlyCount;
    }
    public void setTargetOnlyCount(int targetOnlyCount) {
        this.targetOnlyCount = targetOnlyCount;
    }
    public String getErrorMessage() {
        return this.errorMessage;
    }
    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }
    public String getDetails() {
        return this.details;
    }
    public void setDetails(String details) {
        this.details = details;
    }
}
