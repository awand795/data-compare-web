package com.dbdiff.service;

import com.dbdiff.model.DiffCell;
import com.dbdiff.model.DiffRequest;
import com.dbdiff.model.DiffResult;
import com.dbdiff.model.DiffRow;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

@Service
public class DataComparisonService {

    @Autowired
    private ConnectionManagerService connectionManagerService;

    @Autowired
    private DatabaseMetaDataService metaDataService;

    public DiffResult compare(DiffRequest request) {
        DataSource sourceDs = connectionManagerService.getDataSource(request.getSourceConnection());
        DataSource targetDs = connectionManagerService.getDataSource(request.getTargetConnection());

        JdbcTemplate sourceJdbc = new JdbcTemplate(sourceDs);
        JdbcTemplate targetJdbc = new JdbcTemplate(targetDs);

        // Removed setFetchSize as it may cause issues with some JDBC drivers when autoCommit is true

        String sourceQuery = buildQuery(request.getTableName(), request.getCustomQuerySource());
        String targetQuery = buildQuery(request.getTableName(), request.getCustomQueryTarget());

        System.out.println("COMPARE DATA: Table = " + request.getTableName());
        System.out.println("COMPARE DATA: Source Query = " + sourceQuery);
        System.out.println("COMPARE DATA: Target Query = " + targetQuery);

        List<Map<String, Object>> sourceData;
        List<Map<String, Object>> targetData;

        boolean isSameDb = request.getSourceConnection().getJdbcUrl().trim().equalsIgnoreCase(
                request.getTargetConnection().getJdbcUrl().trim());

        if (isSameDb) {
            System.out.println("COMPARE DATA: Running queries sequentially on the same database host...");
            long start = System.currentTimeMillis();
            try {
                sourceData = sourceJdbc.queryForList(sourceQuery);
                System.out.println("COMPARE DATA: Source Query returned " + sourceData.size() + " rows in " + (System.currentTimeMillis() - start) + " ms");
            } catch (Exception e) {
                System.err.println("COMPARE DATA: Source Query failed: " + e.getMessage());
                throw new RuntimeException("Source database query failed: " + e.getMessage(), e);
            }

            start = System.currentTimeMillis();
            try {
                targetData = targetJdbc.queryForList(targetQuery);
                System.out.println("COMPARE DATA: Target Query returned " + targetData.size() + " rows in " + (System.currentTimeMillis() - start) + " ms");
            } catch (Exception e) {
                System.err.println("COMPARE DATA: Target Query failed: " + e.getMessage());
                throw new RuntimeException("Target database query failed: " + e.getMessage(), e);
            }
        } else {
            System.out.println("COMPARE DATA: Running queries in parallel on different database hosts...");
            ExecutorService localExecutor = Executors.newFixedThreadPool(2);
            Future<List<Map<String, Object>>> sourceFuture = localExecutor.submit(() -> sourceJdbc.queryForList(sourceQuery));
            Future<List<Map<String, Object>>> targetFuture = localExecutor.submit(() -> targetJdbc.queryForList(targetQuery));

            long start = System.currentTimeMillis();
            try {
                sourceData = sourceFuture.get(300, TimeUnit.SECONDS);
                targetData = targetFuture.get(300, TimeUnit.SECONDS);
                System.out.println("COMPARE DATA: Parallel queries finished in " + (System.currentTimeMillis() - start) + " ms. Source rows: " + sourceData.size() + ", Target rows: " + targetData.size());
            } catch (Exception e) {
                sourceFuture.cancel(true);
                targetFuture.cancel(true);
                System.err.println("COMPARE DATA: Parallel query execution failed: " + e.getMessage());
                throw new RuntimeException("Parallel query execution failed: " + e.getMessage(), e);
            } finally {
                localExecutor.shutdown();
            }
        }

        // Build excluded columns set (case-insensitive)
        Set<String> excludeSet = new HashSet<>();
        if (request.getExcludeColumns() != null) {
            for (String col : request.getExcludeColumns()) {
                if (col != null && !col.isBlank()) {
                    excludeSet.add(col.trim().toLowerCase());
                }
            }
        }

        List<String> columns = extractColumns(sourceData, targetData, excludeSet);
        List<String> pks = request.getPrimaryKeys();

        if (pks == null || pks.isEmpty()) {
            if (request.getTableName() != null) {
                pks = metaDataService.getPrimaryKeys(sourceDs, request.getTableName(), request.getSourceConnection().getSchema());
            }
            if (pks == null) pks = new ArrayList<>();
            if (pks.isEmpty() && !columns.isEmpty()) {
                pks = new ArrayList<>(columns); // Fallback to ALL columns if no PK is found (e.g. for Views)
            }
        }

        Map<String, Map<String, Object>> sourceMap = mapByKeys(sourceData, pks);
        Map<String, Map<String, Object>> targetMap = mapByKeys(targetData, pks);

        DiffResult result = new DiffResult();
        result.setColumns(columns);
        result.setTotalSourceRows(sourceData.size());
        result.setTotalTargetRows(targetData.size());

        // Pre-allocate with estimated capacity for performance
        List<DiffRow> diffRows = new ArrayList<>(Math.max(sourceMap.size(), targetMap.size()));
        int differences = 0;

        Set<String> allKeys = new LinkedHashSet<>(sourceMap.size() + targetMap.size());
        allKeys.addAll(sourceMap.keySet());
        allKeys.addAll(targetMap.keySet());

        int colCount = columns.size();

        for (String key : allKeys) {
            Map<String, Object> sRow = sourceMap.get(key);
            Map<String, Object> tRow = targetMap.get(key);

            DiffRow diffRow = new DiffRow();
            diffRow.setRowKey(key);
            Map<String, DiffCell> cells = new LinkedHashMap<>(colCount);

            boolean isRowDiff = false;

            if (sRow != null && tRow != null) {
                for (String col : columns) {
                    Object sVal = sRow.get(col);
                    Object tVal = tRow.get(col);
                    boolean isCellDiff = !normalizedEquals(sVal, tVal);
                    if (isCellDiff) isRowDiff = true;
                    cells.put(col, new DiffCell(sVal, tVal, isCellDiff));
                }
                diffRow.setStatus(isRowDiff ? DiffRow.Status.DIFFERENT : DiffRow.Status.MATCH);
            } else if (sRow != null) {
                for (String col : columns) {
                    cells.put(col, new DiffCell(sRow.get(col), null, true));
                }
                diffRow.setStatus(DiffRow.Status.SOURCE_ONLY);
                isRowDiff = true;
            } else {
                for (String col : columns) {
                    cells.put(col, new DiffCell(null, tRow.get(col), true));
                }
                diffRow.setStatus(DiffRow.Status.TARGET_ONLY);
                isRowDiff = true;
            }

            diffRow.setCells(cells);
            diffRows.add(diffRow);

            if (isRowDiff) {
                differences++;
            }
        }

        result.setRows(diffRows);
        result.setTotalDifferences(differences);

        return result;
    }

    public Map<String, Object> syncData(DiffRequest request) {
        DiffResult diff = compare(request);
        DataSource targetDs = connectionManagerService.getDataSource(request.getTargetConnection());
        JdbcTemplate targetJdbc = new JdbcTemplate(targetDs);

        int inserted = 0;
        int updated = 0;
        int deleted = 0;

        List<String> pks = request.getPrimaryKeys();
        if (pks == null || pks.isEmpty()) {
            throw new IllegalArgumentException("Primary keys are required for synchronization.");
        }

        String tableName = request.getTableName();
        List<String> columns = diff.getColumns();

        for (DiffRow row : diff.getRows()) {
            if (row.getStatus() == DiffRow.Status.SOURCE_ONLY) {
                // INSERT
                StringBuilder sql = new StringBuilder("INSERT INTO ");
                sql.append(tableName).append(" (");
                sql.append(String.join(", ", columns));
                sql.append(") VALUES (");
                sql.append(columns.stream().map(c -> "?").collect(Collectors.joining(", ")));
                sql.append(")");

                Object[] args = columns.stream()
                        .map(c -> row.getCells().get(c).getSourceValue())
                        .toArray();
                targetJdbc.update(sql.toString(), args);
                inserted++;
            } else if (row.getStatus() == DiffRow.Status.DIFFERENT) {
                // UPDATE
                List<String> updateCols = columns.stream()
                        .filter(c -> !pks.contains(c))
                        .collect(Collectors.toList());
                
                StringBuilder sql = new StringBuilder("UPDATE ");
                sql.append(tableName).append(" SET ");
                sql.append(updateCols.stream().map(c -> c + " = ?").collect(Collectors.joining(", ")));
                sql.append(" WHERE ");
                sql.append(pks.stream().map(pk -> pk + " = ?").collect(Collectors.joining(" AND ")));

                List<Object> args = new ArrayList<>();
                for (String c : updateCols) {
                    args.add(row.getCells().get(c).getSourceValue());
                }
                for (String pk : pks) {
                    args.add(row.getCells().get(pk).getSourceValue());
                }
                targetJdbc.update(sql.toString(), args.toArray());
                updated++;
            } else if (row.getStatus() == DiffRow.Status.TARGET_ONLY) {
                // DELETE
                StringBuilder sql = new StringBuilder("DELETE FROM ");
                sql.append(tableName).append(" WHERE ");
                sql.append(pks.stream().map(pk -> pk + " = ?").collect(Collectors.joining(" AND ")));

                Object[] args = pks.stream()
                        .map(pk -> row.getCells().get(pk).getTargetValue())
                        .toArray();
                targetJdbc.update(sql.toString(), args);
                deleted++;
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("inserted", inserted);
        result.put("updated", updated);
        result.put("deleted", deleted);
        return result;
    }

    /**
     * Normalize comparison: handles number type mismatches (e.g. Long vs Integer vs BigDecimal),
     * and trims trailing whitespace from strings. Optimized for high speed and minimal garbage collection.
     */
    private boolean normalizedEquals(Object a, Object b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        if (a == b) return true;

        if (a instanceof Number && b instanceof Number) {
            Number na = (Number) a;
            Number nb = (Number) b;
            if (na.getClass() == nb.getClass()) {
                return na.equals(nb);
            }
            double da = na.doubleValue();
            double db = nb.doubleValue();
            if (Double.compare(da, db) != 0) {
                return false;
            }
            // Fallback for extreme precision numbers (e.g. large BigDecimals)
            if (a instanceof java.math.BigDecimal || b instanceof java.math.BigDecimal) {
                return new java.math.BigDecimal(a.toString()).compareTo(
                        new java.math.BigDecimal(b.toString())) == 0;
            }
            return true;
        }

        if (a instanceof String && b instanceof String) {
            return ((String) a).trim().equals(((String) b).trim());
        }

        return a.toString().trim().equals(b.toString().trim());
    }

    private String buildQuery(String tableName, String customQuery) {
        if (customQuery != null && !customQuery.trim().isEmpty()) {
            return customQuery.trim();
        }
        if (tableName != null && !tableName.trim().isEmpty()) {
            return "SELECT * FROM " + tableName.trim();
        }
        throw new IllegalArgumentException("Either tableName or customQuery must be provided");
    }

    private List<String> extractColumns(
            List<Map<String, Object>> source,
            List<Map<String, Object>> target,
            Set<String> excludeSet) {

        Set<String> cols = new LinkedHashSet<>();
        if (!source.isEmpty()) cols.addAll(source.get(0).keySet());
        if (!target.isEmpty()) cols.addAll(target.get(0).keySet());

        if (excludeSet.isEmpty()) return new ArrayList<>(cols);

        return cols.stream()
                .filter(c -> !excludeSet.contains(c.toLowerCase()))
                .collect(Collectors.toList());
    }

    private Map<String, Map<String, Object>> mapByKeys(List<Map<String, Object>> data, List<String> pks) {
        if (data.isEmpty()) {
            return new LinkedHashMap<>();
        }

        // Pre-resolve column names to their exact case in the row keys once (O(columns) instead of O(rows * columns * columns))
        Map<String, Object> firstRow = data.get(0);
        List<String> exactPks = new ArrayList<>(pks.size());
        for (String pk : pks) {
            String exactPk = null;
            if (firstRow.containsKey(pk)) {
                exactPk = pk;
            } else {
                for (String key : firstRow.keySet()) {
                    if (key.equalsIgnoreCase(pk)) {
                        exactPk = key;
                        break;
                    }
                }
            }
            if (exactPk != null) {
                exactPks.add(exactPk);
            }
        }

        // Map rows using a fast StringBuilder
        Map<String, Map<String, Object>> map = new LinkedHashMap<>(data.size() * 4 / 3 + 1);
        StringBuilder sb = new StringBuilder(128);
        for (Map<String, Object> row : data) {
            sb.setLength(0);
            for (int i = 0; i < exactPks.size(); i++) {
                if (i > 0) sb.append("|");
                Object val = row.get(exactPks.get(i));
                sb.append(val);
            }
            map.put(sb.toString(), row);
        }
        return map;
    }
}
