package com.dbdiff.model;

import lombok.Data;

@Data
public class SchemaCompareRequest {
    private ConnectionDetails sourceConnection;
    private ConnectionDetails targetConnection;
    private String tableName; // optional - null for compare-all
}
