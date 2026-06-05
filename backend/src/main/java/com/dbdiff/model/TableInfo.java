package com.dbdiff.model;

public class TableInfo {
    private String name;
    private String type;

    public String getName() {
        return this.name;
    }
    public void setName(String name) {
        this.name = name;
    }
    public String getType() {
        return this.type;
    }
    public void setType(String type) {
        this.type = type;
    }
    public TableInfo() {}
    public TableInfo(String name, String type) { this.name = name; this.type = type; }
}
