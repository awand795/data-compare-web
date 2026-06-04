package com.dbdiff.service;

import com.dbdiff.model.DiffRow;
import java.util.List;

public interface DiffRowConsumer {
    void onColumns(List<String> columns) throws Exception;
    void onRow(DiffRow row) throws Exception;
    void onTotals(int totalSource, int totalTarget, int totalDiffs) throws Exception;
}
