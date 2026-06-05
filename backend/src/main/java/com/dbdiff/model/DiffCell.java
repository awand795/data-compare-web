package com.dbdiff.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public class DiffCell {
    public DiffCell() {}
    public DiffCell(Object sourceValue, Object targetValue, boolean isDifferent) { this.sourceValue = sourceValue; this.targetValue = targetValue; this.isDifferent = isDifferent; }
    private Object sourceValue;
    private Object targetValue;

    @JsonProperty("isDifferent")
    private boolean isDifferent;

    public Object getSourceValue() {
        return this.sourceValue;
    }
    public void setSourceValue(Object sourceValue) {
        this.sourceValue = sourceValue;
    }
    public Object getTargetValue() {
        return this.targetValue;
    }
    public void setTargetValue(Object targetValue) {
        this.targetValue = targetValue;
    }
    public boolean isDifferent() {
        return this.isDifferent;
    }
    public void setDifferent(boolean isDifferent) {
        this.isDifferent = isDifferent;
    }
}
