package com.dbdiff.service;

import com.dbdiff.model.DiffCell;
import com.dbdiff.model.DiffRequest;
import com.dbdiff.model.DiffResult;
import com.dbdiff.model.DiffRow;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.OutputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;

@Service
public class DataComparisonService {

    @Autowired
    private ConnectionManagerService connectionManagerService;

    @Autowired
    private DatabaseMetaDataService metaDataService;

    // ─────────────────────────────────────────────────────────────────────────
    // compare() — non-streaming, untuk sync dan operasi kecil
    // ─────────────────────────────────────────────────────────────────────────
    public DiffResult compare(DiffRequest request) {
        DataSource sourceDs = connectionManagerService.getDataSource(request.getSourceConnection());
        DataSource targetDs = connectionManagerService.getDataSource(request.getTargetConnection());

        JdbcTemplate sourceJdbc = new JdbcTemplate(sourceDs);
        JdbcTemplate targetJdbc = new JdbcTemplate(targetDs);

        List<String> pksForOrder = new ArrayList<>();
        if (request.getPrimaryKeys() != null) pksForOrder.addAll(request.getPrimaryKeys());
        if (pksForOrder.isEmpty() && request.getTableName() != null) {
            List<String> dbPks = metaDataService.getPrimaryKeys(sourceDs, request.getTableName(), request.getSourceConnection().getSchema());
            if (dbPks != null && !dbPks.isEmpty()) pksForOrder = dbPks;
        }

        String sourceQuery = buildQuery(request.getTableName(), request.getCustomQuerySource(), pksForOrder);
        String targetQuery = buildQuery(request.getTableName(), request.getCustomQueryTarget(), pksForOrder);

        System.out.println("COMPARE DATA: Source Query = " + sourceQuery);
        System.out.println("COMPARE DATA: Target Query = " + targetQuery);

        List<Map<String, Object>> sourceData;
        List<Map<String, Object>> targetData;

        boolean isSameDb = isSameDatabase(request);

        if (isSameDb) {
            long start = System.currentTimeMillis();
            sourceData = sourceJdbc.queryForList(sourceQuery);
            System.out.println("COMPARE DATA: Source " + sourceData.size() + " rows in " + (System.currentTimeMillis() - start) + "ms");
            start = System.currentTimeMillis();
            targetData = targetJdbc.queryForList(targetQuery);
            System.out.println("COMPARE DATA: Target " + targetData.size() + " rows in " + (System.currentTimeMillis() - start) + "ms");
        } else {
            ExecutorService ex = Executors.newFixedThreadPool(2);
            Future<List<Map<String, Object>>> sf = ex.submit(() -> sourceJdbc.queryForList(sourceQuery));
            Future<List<Map<String, Object>>> tf = ex.submit(() -> targetJdbc.queryForList(targetQuery));
            try {
                sourceData = sf.get(300, TimeUnit.SECONDS);
                targetData = tf.get(300, TimeUnit.SECONDS);
            } catch (Exception e) {
                sf.cancel(true); tf.cancel(true);
                throw new RuntimeException("Parallel query failed: " + e.getMessage(), e);
            } finally {
                ex.shutdown();
            }
        }

        Set<String> excludeSet = buildExcludeSet(request);
        List<String> columns = extractColumns(sourceData, targetData, excludeSet);
        List<String> pks = resolvePks(request, sourceDs, columns);

        Map<String, Map<String, Object>> sourceMap = mapByKeys(sourceData, pks);
        Map<String, Map<String, Object>> targetMap = mapByKeys(targetData, pks);

        DiffResult result = new DiffResult();
        result.setColumns(columns);
        result.setTotalSourceRows(sourceData.size());
        result.setTotalTargetRows(targetData.size());

        List<DiffRow> diffRows = new ArrayList<>(Math.max(sourceMap.size(), targetMap.size()));
        int differences = 0;

        Set<String> allKeys = new LinkedHashSet<>(sourceMap.size() + targetMap.size());
        allKeys.addAll(sourceMap.keySet());
        allKeys.addAll(targetMap.keySet());

        for (String key : allKeys) {
            DiffRow diffRow = buildDiffRow(key, sourceMap.get(key), targetMap.get(key), columns);
            diffRows.add(diffRow);
            if (diffRow.getStatus() != DiffRow.Status.MATCH) differences++;
        }

        result.setRows(diffRows);
        result.setTotalDifferences(differences);
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // compareAndStream() — entry point untuk /api/compare
    // ─────────────────────────────────────────────────────────────────────────
    public void compareAndStream(DiffRequest request, OutputStream out) throws IOException {
        JsonFactory factory = new JsonFactory();
        try (JsonGenerator gen = factory.createGenerator(out, com.fasterxml.jackson.core.JsonEncoding.UTF8)) {
            gen.disable(JsonGenerator.Feature.FLUSH_PASSED_TO_STREAM);
            int[] rowCount = {0};

            processStream(request, new DiffRowConsumer() {
                @Override
                public void onColumns(List<String> columns) throws Exception {
                    gen.writeStartObject();
                    gen.writeStringField("type", "columns");
                    gen.writeArrayFieldStart("data");
                    for (String col : columns) gen.writeString(col);
                    gen.writeEndArray();
                    gen.writeEndObject();
                    gen.writeRaw('\n');
                    gen.flush();
                }

                @Override
                public void onRow(DiffRow row) throws Exception {
                    gen.writeStartObject();
                    gen.writeStringField("type", "row");
                    gen.writeObjectFieldStart("data");
                    gen.writeStringField("rowKey", row.getRowKey());
                    gen.writeObjectFieldStart("cells");
                    for (Map.Entry<String, DiffCell> e : row.getCells().entrySet()) {
                        gen.writeObjectFieldStart(e.getKey());
                        gen.writeObjectField("sourceValue", e.getValue().getSourceValue());
                        gen.writeObjectField("targetValue", e.getValue().getTargetValue());
                        gen.writeBooleanField("isDifferent", e.getValue().isDifferent());
                        gen.writeEndObject();
                    }
                    gen.writeEndObject();
                    gen.writeStringField("status", row.getStatus().name());
                    gen.writeEndObject();
                    gen.writeEndObject();
                    gen.writeRaw('\n');
                    rowCount[0]++;
                    if (rowCount[0] % 500 == 0) gen.flush();
                }

                @Override
                public void onTotals(int totalSource, int totalTarget, int totalDiffs) throws Exception {
                    gen.flush();
                    gen.writeStartObject();
                    gen.writeStringField("type", "summary");
                    gen.writeObjectFieldStart("data");
                    gen.writeNumberField("totalSourceRows", totalSource);
                    gen.writeNumberField("totalTargetRows", totalTarget);
                    gen.writeNumberField("totalDifferences", totalDiffs);
                    gen.writeEndObject();
                    gen.writeEndObject();
                    gen.writeRaw('\n');
                    gen.flush();
                }
            });
        } catch (Exception e) {
            throw new IOException("Failed to stream JSON", e);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // processStream() — FIX UTAMA
    //
    // Masalah sebelumnya:
    //   - Source == Target → pool sama → deadlock saat buka 2 koneksi berurutan
    //     (koneksi 1 cursor masih terbuka, koneksi 2 menunggu → timeout)
    //
    // Solusi:
    //   - Jika source == target DB: gunakan SATU koneksi untuk keduanya,
    //     jalankan source dulu → buffer → lalu jalankan target di koneksi sama.
    //   - Jika beda DB: buka koneksi terpisah dari masing-masing pool (aman).
    // ─────────────────────────────────────────────────────────────────────────
    public void processStream(DiffRequest request, DiffRowConsumer consumer) throws Exception {
        long startTime = System.currentTimeMillis();

        DataSource sourceDs = connectionManagerService.getDataSource(request.getSourceConnection());
        DataSource targetDs = connectionManagerService.getDataSource(request.getTargetConnection());

        Set<String> excludeSet = buildExcludeSet(request);
        List<String> pkInput = (request.getPrimaryKeys() != null) ? request.getPrimaryKeys() : new ArrayList<>();
        
        List<String> exactPks = new ArrayList<>(pkInput);
        if (exactPks.isEmpty() && request.getTableName() != null) {
            List<String> dbPks = metaDataService.getPrimaryKeys(sourceDs, request.getTableName(), request.getSourceConnection().getSchema());
            if (dbPks != null && !dbPks.isEmpty()) exactPks = dbPks;
        }

        String sourceQuery = buildQuery(request.getTableName(), request.getCustomQuerySource(), exactPks);
        String targetQuery = buildQuery(request.getTableName(), request.getCustomQueryTarget(), exactPks);

        System.out.println("STREAM COMPARE: O(1) Merge-Join");
        System.out.println("STREAM COMPARE: Source Query = " + sourceQuery);
        System.out.println("STREAM COMPARE: Target Query = " + targetQuery);

        List<String> columns = new ArrayList<>();
        int[] totalSourceRows = {0};
        int[] totalTargetRows = {0};
        int[] differences = {0};

        try (Connection sConn = sourceDs.getConnection(); 
             Connection tConn = targetDs.getConnection()) {
            
            sConn.setAutoCommit(false);
            tConn.setAutoCommit(false);

            try (PreparedStatement psSource = sConn.prepareStatement(sourceQuery, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
                 PreparedStatement psTarget = tConn.prepareStatement(targetQuery, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
                
                psSource.setFetchSize(5000);
                psTarget.setFetchSize(5000);

                try (ResultSet rsSource = psSource.executeQuery(); 
                     ResultSet rsTarget = psTarget.executeQuery()) {
                    
                    ResultSetMetaData meta = rsSource.getMetaData();
                    columns = extractColumnsFromMeta(meta, excludeSet);
                    exactPks = resolveExactPks(pkInput, columns, request, sourceDs);
                    consumer.onColumns(columns);
                    System.out.println("STREAM COMPARE: " + columns.size() + " kolom, PKs=" + exactPks);

                    int[] colIdx = resolveColumnIndices(meta, columns);
                    
                    boolean hasSource = rsSource.next();
                    boolean hasTarget = rsTarget.next();

                    while (hasSource || hasTarget) {
                        if (hasSource && hasTarget) {
                            int cmp = compareKeys(rsSource, rsTarget, exactPks);
                            if (cmp == 0) {
                                String sKey = buildKeyFromRs(rsSource, exactPks);
                                Object[] sRow = getRow(rsSource, columns.size(), colIdx);
                                Object[] tRow = getRow(rsTarget, columns.size(), colIdx);
                                DiffRow diffRow = buildDiffRowFromArrays(sKey, sRow, tRow, columns);
                                if (diffRow.getStatus() != DiffRow.Status.MATCH) differences[0]++;
                                consumer.onRow(diffRow);
                                totalSourceRows[0]++;
                                totalTargetRows[0]++;
                                hasSource = rsSource.next();
                                hasTarget = rsTarget.next();
                            } else if (cmp < 0) {
                                String sKey = buildKeyFromRs(rsSource, exactPks);
                                Object[] sRow = getRow(rsSource, columns.size(), colIdx);
                                DiffRow diffRow = buildSourceOnlyRow(sKey, sRow, columns);
                                differences[0]++;
                                consumer.onRow(diffRow);
                                totalSourceRows[0]++;
                                hasSource = rsSource.next();
                            } else {
                                String tKey = buildKeyFromRs(rsTarget, exactPks);
                                Object[] tRow = getRow(rsTarget, columns.size(), colIdx);
                                DiffRow diffRow = buildTargetOnlyRow(tKey, tRow, columns);
                                differences[0]++;
                                consumer.onRow(diffRow);
                                totalTargetRows[0]++;
                                hasTarget = rsTarget.next();
                            }
                        } else if (hasSource) {
                            String sKey = buildKeyFromRs(rsSource, exactPks);
                            Object[] sRow = getRow(rsSource, columns.size(), colIdx);
                            DiffRow diffRow = buildSourceOnlyRow(sKey, sRow, columns);
                            differences[0]++;
                            consumer.onRow(diffRow);
                            totalSourceRows[0]++;
                            hasSource = rsSource.next();
                        } else {
                            String tKey = buildKeyFromRs(rsTarget, exactPks);
                            Object[] tRow = getRow(rsTarget, columns.size(), colIdx);
                            DiffRow diffRow = buildTargetOnlyRow(tKey, tRow, columns);
                            differences[0]++;
                            consumer.onRow(diffRow);
                            totalTargetRows[0]++;
                            hasTarget = rsTarget.next();
                        }
                    }
                }
            }
            sConn.commit();
            tConn.commit();
        }

        System.out.println("STREAM COMPARE: SELESAI. Total=" + (System.currentTimeMillis() - startTime) + "ms");
        consumer.onTotals(totalSourceRows[0], totalTargetRows[0], differences[0]);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // syncData()
    // ─────────────────────────────────────────────────────────────────────────
    public Map<String, Object> syncData(DiffRequest request) {
        DiffResult diff = compare(request);
        DataSource targetDs = connectionManagerService.getDataSource(request.getTargetConnection());
        JdbcTemplate targetJdbc = new JdbcTemplate(targetDs);

        List<String> pks = request.getPrimaryKeys();
        if (pks == null || pks.isEmpty()) throw new IllegalArgumentException("Primary keys required for sync.");

        String tableName = request.getTableName();
        List<String> columns = diff.getColumns();
        int inserted = 0, updated = 0, deleted = 0;

        for (DiffRow row : diff.getRows()) {
            if (row.getStatus() == DiffRow.Status.SOURCE_ONLY) {
                String sql = "INSERT INTO " + tableName + " (" + String.join(", ", columns) + ") VALUES (" +
                        columns.stream().map(c -> "?").collect(Collectors.joining(", ")) + ")";
                targetJdbc.update(sql, columns.stream().map(c -> row.getCells().get(c).getSourceValue()).toArray());
                inserted++;
            } else if (row.getStatus() == DiffRow.Status.DIFFERENT) {
                List<String> updCols = columns.stream().filter(c -> !pks.contains(c)).collect(Collectors.toList());
                String sql = "UPDATE " + tableName + " SET " +
                        updCols.stream().map(c -> c + " = ?").collect(Collectors.joining(", ")) +
                        " WHERE " + pks.stream().map(pk -> pk + " = ?").collect(Collectors.joining(" AND "));
                List<Object> args = new ArrayList<>();
                updCols.forEach(c -> args.add(row.getCells().get(c).getSourceValue()));
                pks.forEach(pk -> args.add(row.getCells().get(pk).getSourceValue()));
                targetJdbc.update(sql, args.toArray());
                updated++;
            } else if (row.getStatus() == DiffRow.Status.TARGET_ONLY) {
                String sql = "DELETE FROM " + tableName + " WHERE " +
                        pks.stream().map(pk -> pk + " = ?").collect(Collectors.joining(" AND "));
                targetJdbc.update(sql, pks.stream().map(pk -> row.getCells().get(pk).getTargetValue()).toArray());
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

    // ─────────────────────────────────────────────────────────────────────────
    // Helper methods
    // ─────────────────────────────────────────────────────────────────────────

    private boolean isSameDatabase(DiffRequest request) {
        return request.getSourceConnection().getJdbcUrl().trim()
                .equalsIgnoreCase(request.getTargetConnection().getJdbcUrl().trim());
    }

    private List<String> extractColumnsFromMeta(ResultSetMetaData meta, Set<String> excludeSet) throws Exception {
        List<String> cols = new ArrayList<>();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            String name = meta.getColumnLabel(i);
            if (!excludeSet.contains(name.toLowerCase())) cols.add(name);
        }
        return cols;
    }

    private List<String> resolveExactPks(List<String> pkInput, List<String> columns,
                                          DiffRequest request, DataSource sourceDs) {
        List<String> resolved = pkInput;
        if (resolved.isEmpty() && request.getTableName() != null) {
            List<String> dbPks = metaDataService.getPrimaryKeys(sourceDs, request.getTableName(),
                    request.getSourceConnection().getSchema());
            if (dbPks != null && !dbPks.isEmpty()) resolved = dbPks;
        }
        if (resolved.isEmpty() && !columns.isEmpty()) resolved = new ArrayList<>(columns);

        List<String> exactPks = new ArrayList<>();
        for (String pk : resolved) {
            for (String col : columns) {
                if (col.equalsIgnoreCase(pk)) { exactPks.add(col); break; }
            }
        }
        if (exactPks.isEmpty()) exactPks.addAll(resolved);
        return exactPks;
    }

    private int[] resolveColumnIndices(ResultSetMetaData meta, List<String> columns) throws Exception {
        Map<String, Integer> labelToIdx = new HashMap<>();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            labelToIdx.put(meta.getColumnLabel(i).toLowerCase(), i);
        }
        int[] idx = new int[columns.size()];
        for (int i = 0; i < columns.size(); i++) {
            Integer ci = labelToIdx.get(columns.get(i).toLowerCase());
            idx[i] = (ci != null) ? ci : (i + 1);
        }
        return idx;
    }

    private String buildKeyFromRs(ResultSet rs, List<String> exactPks) throws Exception {
        StringBuilder sb = new StringBuilder(64);
        for (int i = 0; i < exactPks.size(); i++) {
            if (i > 0) sb.append('|');
            sb.append(rs.getString(exactPks.get(i)));
        }
        return sb.toString();
    }

    private DiffRow buildDiffRowFromArrays(String key, Object[] sRow, Object[] tRow, List<String> columns) {
        DiffRow row = new DiffRow();
        row.setRowKey(key);
        Map<String, DiffCell> cells = new LinkedHashMap<>(columns.size());
        boolean isDiff = false;
        for (int i = 0; i < columns.size(); i++) {
            boolean d = !normalizedEquals(sRow[i], tRow[i]);
            if (d) isDiff = true;
            cells.put(columns.get(i), new DiffCell(sRow[i], tRow[i], d));
        }
        row.setStatus(isDiff ? DiffRow.Status.DIFFERENT : DiffRow.Status.MATCH);
        row.setCells(cells);
        return row;
    }

    private DiffRow buildSourceOnlyRow(String key, Object[] sRow, List<String> columns) {
        DiffRow row = new DiffRow();
        row.setRowKey(key);
        Map<String, DiffCell> cells = new LinkedHashMap<>(columns.size());
        for (int i = 0; i < columns.size(); i++) cells.put(columns.get(i), new DiffCell(sRow[i], null, true));
        row.setStatus(DiffRow.Status.SOURCE_ONLY);
        row.setCells(cells);
        return row;
    }

    private DiffRow buildTargetOnlyRow(String key, Object[] tRow, List<String> columns) {
        DiffRow row = new DiffRow();
        row.setRowKey(key);
        Map<String, DiffCell> cells = new LinkedHashMap<>(columns.size());
        for (int i = 0; i < columns.size(); i++) cells.put(columns.get(i), new DiffCell(null, tRow[i], true));
        row.setStatus(DiffRow.Status.TARGET_ONLY);
        row.setCells(cells);
        return row;
    }

    private DiffRow buildDiffRow(String key, Map<String, Object> sRow, Map<String, Object> tRow, List<String> columns) {
        DiffRow diffRow = new DiffRow();
        diffRow.setRowKey(key);
        Map<String, DiffCell> cells = new LinkedHashMap<>(columns.size());
        boolean isDiff = false;
        if (sRow != null && tRow != null) {
            for (String col : columns) {
                boolean d = !normalizedEquals(sRow.get(col), tRow.get(col));
                if (d) isDiff = true;
                cells.put(col, new DiffCell(sRow.get(col), tRow.get(col), d));
            }
            diffRow.setStatus(isDiff ? DiffRow.Status.DIFFERENT : DiffRow.Status.MATCH);
        } else if (sRow != null) {
            for (String col : columns) cells.put(col, new DiffCell(sRow.get(col), null, true));
            diffRow.setStatus(DiffRow.Status.SOURCE_ONLY);
        } else {
            for (String col : columns) cells.put(col, new DiffCell(null, tRow.get(col), true));
            diffRow.setStatus(DiffRow.Status.TARGET_ONLY);
        }
        diffRow.setCells(cells);
        return diffRow;
    }

    private Set<String> buildExcludeSet(DiffRequest request) {
        Set<String> set = new HashSet<>();
        if (request.getExcludeColumns() != null) {
            for (String col : request.getExcludeColumns()) {
                if (col != null && !col.isBlank()) set.add(col.trim().toLowerCase());
            }
        }
        return set;
    }

    private List<String> resolvePks(DiffRequest request, DataSource sourceDs, List<String> columns) {
        List<String> pks = request.getPrimaryKeys();
        if (pks == null) pks = new ArrayList<>();
        if (pks.isEmpty() && request.getTableName() != null) {
            List<String> dbPks = metaDataService.getPrimaryKeys(sourceDs, request.getTableName(),
                    request.getSourceConnection().getSchema());
            if (dbPks != null) pks = dbPks;
        }
        if (pks.isEmpty() && !columns.isEmpty()) pks = new ArrayList<>(columns);
        return pks;
    }

    private boolean normalizedEquals(Object a, Object b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        if (a == b) return true;
        if (a instanceof Number && b instanceof Number) {
            if (a.getClass() == b.getClass()) return a.equals(b);
            double da = ((Number) a).doubleValue(), db = ((Number) b).doubleValue();
            if (Double.compare(da, db) != 0) return false;
            if (a instanceof java.math.BigDecimal || b instanceof java.math.BigDecimal) {
                return new java.math.BigDecimal(a.toString()).compareTo(new java.math.BigDecimal(b.toString())) == 0;
            }
            return true;
        }
        if (a instanceof String && b instanceof String) return ((String) a).trim().equals(((String) b).trim());
        return a.toString().trim().equals(b.toString().trim());
    }

    private String buildQuery(String tableName, String customQuery, List<String> pks) {
        if (customQuery != null && !customQuery.trim().isEmpty()) {
            if (!customQuery.toUpperCase().contains("ORDER BY") && pks != null && !pks.isEmpty()) {
                return customQuery.trim() + " ORDER BY " + String.join(", ", pks);
            }
            return customQuery.trim();
        }
        if (tableName != null && !tableName.trim().isEmpty()) {
            String q = "SELECT * FROM " + tableName.trim();
            if (pks != null && !pks.isEmpty()) {
                q += " ORDER BY " + String.join(", ", pks);
            }
            return q;
        }
        throw new IllegalArgumentException("Either tableName or customQuery must be provided");
    }

    private List<String> extractColumns(List<Map<String, Object>> source, List<Map<String, Object>> target,
                                         Set<String> excludeSet) {
        Set<String> cols = new LinkedHashSet<>();
        if (!source.isEmpty()) cols.addAll(source.get(0).keySet());
        if (!target.isEmpty()) cols.addAll(target.get(0).keySet());
        if (excludeSet.isEmpty()) return new ArrayList<>(cols);
        return cols.stream().filter(c -> !excludeSet.contains(c.toLowerCase())).collect(Collectors.toList());
    }

    private Map<String, Map<String, Object>> mapByKeys(List<Map<String, Object>> data, List<String> pks) {
        if (data.isEmpty()) return new LinkedHashMap<>();
        Map<String, Object> firstRow = data.get(0);
        List<String> exactPks = new ArrayList<>(pks.size());
        for (String pk : pks) {
            String exact = firstRow.containsKey(pk) ? pk :
                    firstRow.keySet().stream().filter(k -> k.equalsIgnoreCase(pk)).findFirst().orElse(null);
            if (exact != null) exactPks.add(exact);
        }
        Map<String, Map<String, Object>> map = new LinkedHashMap<>(data.size() * 4 / 3 + 1);
        for (Map<String, Object> row : data) map.put(buildKey(row, exactPks), row);
        return map;
    }

    private String buildKey(Map<String, Object> row, List<String> exactPks) {
        StringBuilder sb = new StringBuilder(128);
        for (int i = 0; i < exactPks.size(); i++) {
            if (i > 0) sb.append('|');
            sb.append(row.get(exactPks.get(i)));
        }
        return sb.toString();
    }

    private Object[] getRow(ResultSet rs, int size, int[] colIdx) throws Exception {
        Object[] row = new Object[size];
        for (int i = 0; i < size; i++) {
            try { row[i] = rs.getObject(colIdx[i]); } catch (Exception e) {}
        }
        return row;
    }

    @SuppressWarnings("unchecked")
    private int compareKeys(ResultSet rsS, ResultSet rsT, List<String> exactPks) throws Exception {
        for (String pk : exactPks) {
            Object sObj = rsS.getObject(pk);
            Object tObj = rsT.getObject(pk);
            
            if (sObj == null && tObj == null) continue;
            if (sObj == null) return -1;
            if (tObj == null) return 1;
            
            if (sObj instanceof Comparable && tObj instanceof Comparable && sObj.getClass() == tObj.getClass()) {
                int cmp = ((Comparable<Object>) sObj).compareTo(tObj);
                if (cmp != 0) return cmp;
            } else if (sObj instanceof Number && tObj instanceof Number) {
                double sVal = ((Number) sObj).doubleValue();
                double tVal = ((Number) tObj).doubleValue();
                int cmp = Double.compare(sVal, tVal);
                if (cmp != 0) return cmp;
            } else {
                int cmp = sObj.toString().compareTo(tObj.toString());
                if (cmp != 0) return cmp;
            }
        }
        return 0;
    }
}
