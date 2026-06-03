package com.dbdiff.model;

import lombok.Data;
import java.util.List;

@Data
public class DiffRequest {
    private ConnectionDetails sourceConnection;
    private ConnectionDetails targetConnection;
    private String tableName; // if null, use customQuery
    private String customQuerySource;
    private String customQueryTarget;
    private List<String> primaryKeys;    // Needed to match rows
    private List<String> excludeColumns; // Columns to ignore in diff
}
