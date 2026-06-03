package com.dbdiff.model;

import lombok.Data;

@Data
public class QueryRequest {
    private ConnectionDetails connection;
    private String query;
}
