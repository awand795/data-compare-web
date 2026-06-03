package com.dbdiff.model;

import lombok.Data;
import java.util.List;
import java.util.Map;

@Data
public class DiffResult {
    private List<String> columns;
    private List<DiffRow> rows;
    private int totalSourceRows;
    private int totalTargetRows;
    private int totalDifferences;
}
