package com.dbdiff.service;

import com.dbdiff.model.DiffCell;
import com.dbdiff.model.DiffRequest;
import com.dbdiff.model.DiffResult;
import com.dbdiff.model.DiffRow;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class DataComparisonService {

    private static final Logger logger = LoggerFactory.getLogger(DataComparisonService.class);

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

        boolean useSurrogateKey = pksForOrder.isEmpty();
        if (useSurrogateKey && request.getSortColumns() != null && !request.getSortColumns().isEmpty()) {
            pksForOrder = request.getSortColumns();
            useSurrogateKey = false;
        }
        String sourceQuery = buildQuery(request.getTableName(), request.getCustomQuerySource(), pksForOrder, request.getSortColumns(), useSurrogateKey);
        String targetQuery = buildQuery(request.getTableName(), request.getCustomQueryTarget(), pksForOrder, request.getSortColumns(), useSurrogateKey);

        if (useSurrogateKey) {
            pksForOrder = Collections.singletonList("__rn__");
        }

        logger.info("COMPARE DATA: Source Query = {}", sourceQuery);
        logger.info("COMPARE DATA: Target Query = {}", targetQuery);

        List<Map<String, Object>> sourceData;
        List<Map<String, Object>> targetData;

        long start = System.currentTimeMillis();
        sourceData = fetchWithCursor(sourceDs, sourceQuery);
        logger.info("COMPARE DATA: Source {} rows in {}ms", sourceData.size(), (System.currentTimeMillis() - start));
        start = System.currentTimeMillis();
        targetData = fetchWithCursor(targetDs, targetQuery);
        logger.info("COMPARE DATA: Target {} rows in {}ms", targetData.size(), (System.currentTimeMillis() - start));

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

    private static final int MAX_SYNC_ROWS = 100_000;

    private List<Map<String, Object>> fetchWithCursor(DataSource ds, String sql) {
        List<Map<String, Object>> results = new ArrayList<>(Math.min(10_000, MAX_SYNC_ROWS));
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql,
                 ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
            conn.setAutoCommit(false);
            ps.setFetchSize(5000);
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData meta = rs.getMetaData();
                int colCount = meta.getColumnCount();
                String[] cols = new String[colCount];
                for (int i = 1; i <= colCount; i++) cols[i - 1] = meta.getColumnLabel(i);
                int rowNum = 0;
                while (rs.next() && rowNum < MAX_SYNC_ROWS) {
                    Map<String, Object> row = new LinkedHashMap<>(colCount);
                    for (int i = 0; i < colCount; i++) row.put(cols[i], rs.getObject(i + 1));
                    results.add(row);
                    rowNum++;
                }
            } finally {
                try { conn.rollback(); } catch (Exception ignored) {}
                try { conn.setAutoCommit(true); } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            throw new RuntimeException("Cursor fetch failed: " + e.getMessage(), e);
        }
        return results;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // compareAndStream() — entry point untuk /api/compare
    // ─────────────────────────────────────────────────────────────────────────
    public void compareAndStream(DiffRequest request, OutputStream out) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        try (JsonGenerator gen = mapper.getFactory().createGenerator(out, com.fasterxml.jackson.core.JsonEncoding.UTF8)) {
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
                    if (rowCount[0] % 5000 == 0) gen.flush();
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
        List<String> dbPks = new ArrayList<>();
        
        // Validation is now done synchronously in controller before stream
        List<String> exactPks = new ArrayList<>(pkInput);
        if (exactPks.isEmpty() && request.getTableName() != null) {
            List<String> fetched = metaDataService.getPrimaryKeys(sourceDs, request.getTableName(), request.getSourceConnection().getSchema());
            if (fetched != null && !fetched.isEmpty()) {
                exactPks = fetched;
                dbPks = fetched;
            }
        }
        
        boolean useSurrogateKey = exactPks.isEmpty();
        if (useSurrogateKey && request.getSortColumns() != null && !request.getSortColumns().isEmpty()) {
            exactPks = request.getSortColumns();
            useSurrogateKey = false;
        }

        // When no keys AND no sort columns → use ALL columns as composite key.
        // This means: sort by all columns + merge-join by all columns.
        //  - Identical rows → compareKeys returns 0 → MATCH
        //  - Any value difference (e.g. price 6000 vs 5000) → rows sort differently
        //    → appear as SOURCE_ONLY + TARGET_ONLY so user sees exact change.
        // This avoids ROW_NUMBER() which is non-deterministic and would hide real diffs.
        List<String> effectiveSortColumns = request.getSortColumns();
        if (useSurrogateKey && (effectiveSortColumns == null || effectiveSortColumns.isEmpty())) {
            List<String> allCols = new ArrayList<>();
            
            // 1. Try JDBC Metadata if table name is provided
            if (request.getTableName() != null && !request.getTableName().isEmpty()) {
                logger.info("STREAM COMPARE: Attempting to fetch columns for table='{}', schema='{}'", request.getTableName(), request.getSourceConnection().getSchema());
                allCols = metaDataService.getColumns(sourceDs, request.getTableName(), request.getSourceConnection().getSchema());
            }

            // 2. Fallback: Dry-run query LIMIT 0 to get ResultSetMetaData (works for Custom Queries / Views)
            if (allCols == null || allCols.isEmpty()) {
                String baseQuery = (request.getCustomQuerySource() != null && !request.getCustomQuerySource().isEmpty()) 
                    ? request.getCustomQuerySource() 
                    : "SELECT * FROM " + request.getTableName();
                logger.info("STREAM COMPARE: Fetching columns via LIMIT 0 dry-run query...");
                try (Connection conn = sourceDs.getConnection();
                     java.sql.Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery("SELECT * FROM (" + baseQuery + ") AS tmp LIMIT 0")) {
                    java.sql.ResultSetMetaData meta = rs.getMetaData();
                    for (int i = 1; i <= meta.getColumnCount(); i++) {
                        allCols.add(meta.getColumnLabel(i));
                    }
                } catch (Exception e) {
                    logger.warn("STREAM COMPARE: Failed dry-run column extraction: {}", e.getMessage());
                }
            }

            if (allCols != null && !allCols.isEmpty()) {
                exactPks = new ArrayList<>(allCols);
                effectiveSortColumns = new ArrayList<>(allCols);
                useSurrogateKey = false;
                logger.info("STREAM COMPARE: ✅ Composite key mode — {} columns: {}", allCols.size(), allCols);
            } else {
                logger.warn("STREAM COMPARE: ⚠️ getColumns returned EMPTY — falling back to ROW_NUMBER (non-deterministic!)");
            }
        }

        String sourceQuery = buildQuery(request.getTableName(), request.getCustomQuerySource(), exactPks, effectiveSortColumns, useSurrogateKey);
        String targetQuery = buildQuery(request.getTableName(), request.getCustomQueryTarget(), exactPks, effectiveSortColumns, useSurrogateKey);

        logger.info("STREAM COMPARE: useSurrogateKey={}, exactPks.size={}", useSurrogateKey, exactPks.size());
        logger.info("STREAM COMPARE: Source Query = {}", sourceQuery);
        logger.info("STREAM COMPARE: Target Query = {}", targetQuery);

        List<String> columns = new ArrayList<>();
        int[] totalSourceRows = {0};
        int[] totalTargetRows = {0};
        int[] differences = {0};

        try (Connection sConn = sourceDs.getConnection(); 
             Connection tConn = targetDs.getConnection()) {
            
            sConn.setAutoCommit(false);
            tConn.setAutoCommit(false);

            try {
                try (PreparedStatement psSource = sConn.prepareStatement(sourceQuery, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
                     PreparedStatement psTarget = tConn.prepareStatement(targetQuery, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
                    
                    psSource.setFetchSize(5000);
                    psTarget.setFetchSize(5000);

                    try (ResultSet rsSource = psSource.executeQuery(); 
                         ResultSet rsTarget = psTarget.executeQuery()) {
                        
                        ResultSetMetaData meta = rsSource.getMetaData();
                        columns = extractColumnsFromMeta(meta, excludeSet);
                        if (useSurrogateKey) {
                            exactPks = Collections.singletonList("__rn__");
                        } else {
                            exactPks = resolveExactPks(pkInput, dbPks, columns);
                        }
                        consumer.onColumns(columns);
                        logger.info("STREAM COMPARE: {} kolom, PKs={}", columns.size(), exactPks);

                        int[] colIdx = resolveColumnIndices(meta, columns);
                        int[] pkIdx = resolveColumnIndices(meta, exactPks);
                        
                        boolean hasSource = rsSource.next();
                        boolean hasTarget = rsTarget.next();

                        while (hasSource || hasTarget) {
                            if (hasSource && hasTarget) {
                                int cmp = compareKeys(rsSource, rsTarget, pkIdx);
                                if (cmp == 0) {
                                    String sKey = buildKeyFromRs(rsSource, pkIdx);
                                    Object[] sRow = getRow(rsSource, columns.size(), colIdx);
                                    Object[] tRow = getRow(rsTarget, columns.size(), colIdx);
                                    DiffRow diffRow = buildDiffRowFromArrays(sKey, sRow, tRow, columns);
                                    if (diffRow.getStatus() != DiffRow.Status.MATCH) differences[0]++;
                                    if (request.isReturnMatchedRows() || diffRow.getStatus() != DiffRow.Status.MATCH) {
                                        consumer.onRow(diffRow);
                                    }
                                    totalSourceRows[0]++;
                                    totalTargetRows[0]++;
                                    hasSource = rsSource.next();
                                    hasTarget = rsTarget.next();
                                } else if (cmp < 0) {
                                    String sKey = buildKeyFromRs(rsSource, pkIdx);
                                    Object[] sRow = getRow(rsSource, columns.size(), colIdx);
                                    DiffRow diffRow = buildSourceOnlyRow(sKey, sRow, columns);
                                    differences[0]++;
                                    consumer.onRow(diffRow);
                                    totalSourceRows[0]++;
                                    hasSource = rsSource.next();
                                } else {
                                    String tKey = buildKeyFromRs(rsTarget, pkIdx);
                                    Object[] tRow = getRow(rsTarget, columns.size(), colIdx);
                                    DiffRow diffRow = buildTargetOnlyRow(tKey, tRow, columns);
                                    differences[0]++;
                                    consumer.onRow(diffRow);
                                    totalTargetRows[0]++;
                                    hasTarget = rsTarget.next();
                                }
                            } else if (hasSource) {
                                String sKey = buildKeyFromRs(rsSource, pkIdx);
                                Object[] sRow = getRow(rsSource, columns.size(), colIdx);
                                DiffRow diffRow = buildSourceOnlyRow(sKey, sRow, columns);
                                differences[0]++;
                                consumer.onRow(diffRow);
                                totalSourceRows[0]++;
                                hasSource = rsSource.next();
                            } else {
                                String tKey = buildKeyFromRs(rsTarget, pkIdx);
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
            } finally {
                try { sConn.rollback(); } catch (Exception ignored) {}
                try { tConn.rollback(); } catch (Exception ignored) {}
            }
        }

        logger.info("STREAM COMPARE: SELESAI. Total={}ms", (System.currentTimeMillis() - startTime));
        consumer.onTotals(totalSourceRows[0], totalTargetRows[0], differences[0]);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // compareBatch() — paginated comparison dengan LIMIT/OFFSET
    // ─────────────────────────────────────────────────────────────────────────
    public Map<String, Object> compareBatch(DiffRequest request, int batchSize, int offset) {
        long startTime = System.currentTimeMillis();
        DataSource sourceDs = connectionManagerService.getDataSource(request.getSourceConnection());
        DataSource targetDs = connectionManagerService.getDataSource(request.getTargetConnection());

        List<String> exactPks = new ArrayList<>();
        if (request.getPrimaryKeys() != null) exactPks.addAll(request.getPrimaryKeys());
        if (exactPks.isEmpty() && request.getTableName() != null) {
            List<String> fetched = metaDataService.getPrimaryKeys(sourceDs, request.getTableName(), request.getSourceConnection().getSchema());
            if (fetched != null && !fetched.isEmpty()) exactPks = fetched;
        }

        boolean useSurrogateKey = exactPks.isEmpty();
        if (useSurrogateKey && request.getSortColumns() != null && !request.getSortColumns().isEmpty()) {
            exactPks = request.getSortColumns();
            useSurrogateKey = false;
        }

        // Same deterministic ordering fix as processStream
        List<String> effectiveSortColumns = request.getSortColumns();
        if (useSurrogateKey && (effectiveSortColumns == null || effectiveSortColumns.isEmpty()) && request.getTableName() != null) {
            List<String> allCols = metaDataService.getColumns(sourceDs, request.getTableName(), request.getSourceConnection().getSchema());
            if (allCols != null && !allCols.isEmpty()) {
                exactPks = allCols;
                effectiveSortColumns = allCols;
                useSurrogateKey = false;
                logger.info("BATCH COMPARE: No keys provided — using ALL {} columns as composite key for accurate diff", allCols.size());
            }
        }

        String sourceQuery = buildQuery(request.getTableName(), request.getCustomQuerySource(), exactPks, effectiveSortColumns, useSurrogateKey);
        String targetQuery = buildQuery(request.getTableName(), request.getCustomQueryTarget(), exactPks, effectiveSortColumns, useSurrogateKey);

        // Add LIMIT/OFFSET
        sourceQuery = addLimitOffset(sourceQuery, batchSize, offset);
        targetQuery = addLimitOffset(targetQuery, batchSize, offset);

        JdbcTemplate sourceJdbc = new JdbcTemplate(sourceDs);
        JdbcTemplate targetJdbc = new JdbcTemplate(targetDs);

        logger.info("BATCH COMPARE: offset={} batchSize={}", offset, batchSize);
        logger.info("BATCH COMPARE: Source Query = {}", sourceQuery);
        logger.info("BATCH COMPARE: Target Query = {}", targetQuery);

        List<Map<String, Object>> sourceData = sourceJdbc.queryForList(sourceQuery);
        List<Map<String, Object>> targetData = targetJdbc.queryForList(targetQuery);

        Set<String> excludeSet = buildExcludeSet(request);
        List<String> columns = extractColumns(sourceData, targetData, excludeSet);
        List<String> pks = resolvePks(request, sourceDs, columns);

        Map<String, Map<String, Object>> sourceMap = mapByKeys(sourceData, pks);
        Map<String, Map<String, Object>> targetMap = mapByKeys(targetData, pks);

        int matchCount = 0, differentCount = 0, sourceOnlyCount = 0, targetOnlyCount = 0;
        List<DiffRow> rows = new ArrayList<>(Math.max(sourceMap.size(), targetMap.size()));

        Set<String> allKeys = new LinkedHashSet<>();
        allKeys.addAll(sourceMap.keySet());
        allKeys.addAll(targetMap.keySet());

        for (String key : allKeys) {
            DiffRow row = buildDiffRow(key, sourceMap.get(key), targetMap.get(key), columns);
            rows.add(row);
            switch (row.getStatus()) {
                case MATCH: matchCount++; break;
                case DIFFERENT: differentCount++; break;
                case SOURCE_ONLY: sourceOnlyCount++; break;
                case TARGET_ONLY: targetOnlyCount++; break;
            }
        }

        boolean hasMore = sourceData.size() >= batchSize || targetData.size() >= batchSize;

        // Filter MATCH rows if user only wants diffs (Only Diff mode)
        if (!request.isReturnMatchedRows()) {
            List<DiffRow> filtered = new ArrayList<>();
            for (DiffRow row : rows) {
                if (row.getStatus() != DiffRow.Status.MATCH) {
                    filtered.add(row);
                }
            }
            rows = filtered;
        }

        Map<String, Object> result = new HashMap<>();
        result.put("columns", columns);
        result.put("rows", rows);
        result.put("matchCount", matchCount);
        result.put("differentCount", differentCount);
        result.put("sourceOnlyCount", sourceOnlyCount);
        result.put("targetOnlyCount", targetOnlyCount);
        result.put("hasMore", hasMore);
        result.put("nextOffset", offset + Math.max(sourceData.size(), targetData.size()));
        result.put("sourceBatchRows", sourceData.size());
        result.put("targetBatchRows", targetData.size());
        result.put("elapsedMs", System.currentTimeMillis() - startTime);

        logger.info("BATCH COMPARE: SELESAI offset={} rows={} diff={} hasMore={} elapsed={}ms",
                offset, rows.size(), differentCount + sourceOnlyCount + targetOnlyCount, hasMore, result.get("elapsedMs"));

        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // countRows() — count rows in source & target (for batch progress)
    // ─────────────────────────────────────────────────────────────────────────
    public Map<String, Object> countRows(DiffRequest request) {
        DataSource sourceDs = connectionManagerService.getDataSource(request.getSourceConnection());
        DataSource targetDs = connectionManagerService.getDataSource(request.getTargetConnection());
        JdbcTemplate sourceJdbc = new JdbcTemplate(sourceDs);
        JdbcTemplate targetJdbc = new JdbcTemplate(targetDs);

        String sourceQuery, targetQuery;
        if (request.getCustomQuerySource() != null && !request.getCustomQuerySource().trim().isEmpty()) {
            sourceQuery = "SELECT COUNT(*) FROM (" + request.getCustomQuerySource().trim() + ") _cnt";
        } else if (request.getTableName() != null) {
            sourceQuery = "SELECT COUNT(*) FROM " + formatTableName(request.getTableName());
        } else {
            sourceQuery = "SELECT COUNT(*) FROM (" + request.getCustomQuerySource() + ") _cnt";
        }

        if (request.getCustomQueryTarget() != null && !request.getCustomQueryTarget().trim().isEmpty()) {
            targetQuery = "SELECT COUNT(*) FROM (" + request.getCustomQueryTarget().trim() + ") _cnt";
        } else if (request.getTableName() != null) {
            targetQuery = "SELECT COUNT(*) FROM " + formatTableName(request.getTableName());
        } else {
            targetQuery = "SELECT COUNT(*) FROM (" + request.getCustomQueryTarget() + ") _cnt";
        }

        int sourceCount = sourceJdbc.queryForObject(sourceQuery, Integer.class);
        int targetCount = targetJdbc.queryForObject(targetQuery, Integer.class);

        Map<String, Object> result = new HashMap<>();
        result.put("sourceCount", sourceCount);
        result.put("targetCount", targetCount);
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // addLimitOffset() — append LIMIT/OFFSET to a SQL query
    // ─────────────────────────────────────────────────────────────────────────
    private String addLimitOffset(String query, int limit, int offset) {
        String trimmed = query.trim();
        // Remove trailing semicolon if present
        if (trimmed.endsWith(";")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
        }
        // For PostgreSQL
        return trimmed + " LIMIT " + limit + " OFFSET " + offset;
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

    private String formatTableName(String tableName) {
        if (tableName == null || tableName.trim().isEmpty()) return "";
        if (tableName.contains("\"")) return tableName;
        if (tableName.contains(".")) {
            String[] parts = tableName.split("\\.", 2);
            return "\"" + parts[0] + "\".\"" + parts[1] + "\"";
        }
        return "\"" + tableName + "\"";
    }

    private List<String> extractColumnsFromMeta(ResultSetMetaData meta, Set<String> excludeSet) throws Exception {
        List<String> cols = new ArrayList<>();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            String name = meta.getColumnLabel(i);
            if (!excludeSet.contains(name.toLowerCase()) && !"__rn__".equalsIgnoreCase(name)) cols.add(name);
        }
        return cols;
    }

    private List<String> resolveExactPks(List<String> pkInput, List<String> dbPks, List<String> columns) {
        List<String> resolved = !pkInput.isEmpty() ? pkInput : dbPks;
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

    private String buildKeyFromRs(ResultSet rs, int[] exactPkIdx) throws Exception {
        StringBuilder sb = new StringBuilder(64);
        for (int i = 0; i < exactPkIdx.length; i++) {
            if (i > 0) sb.append('|');
            sb.append(rs.getString(exactPkIdx[i]));
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
        if (pks.isEmpty() && request.getSortColumns() != null && !request.getSortColumns().isEmpty()) {
            pks = request.getSortColumns();
        }
        if (pks.isEmpty() && !columns.isEmpty()) pks = new ArrayList<>(columns);
        return pks;
    }

    private boolean normalizedEquals(Object a, Object b) {
        // Both null → equal
        if (a == null && b == null) return true;
        
        // Null vs empty string → treat as equal (Excel imports empty cells as "" but original may have NULL)
        if (a == null) return b.toString().trim().isEmpty();
        if (b == null) return a.toString().trim().isEmpty();
        
        // Same reference or equals → equal
        if (a.equals(b)) return true;
        
        String strA = a.toString().trim();
        String strB = b.toString().trim();
        
        // Trimmed strings match → equal
        if (strA.equalsIgnoreCase(strB)) return true;
        
        // Try to parse both as epoch millis (long) — handles "1780375745783"
        try {
            long epochA = Long.parseLong(strA);
            long epochB = Long.parseLong(strB);
            if (epochA == epochB) return true;
        } catch (Exception ignored) {}
        
        // Date comparison: normalize to epoch millis
        // Handles: java.sql.Timestamp vs epoch-millis-string from Excel import
        if (a instanceof java.util.Date || b instanceof java.util.Date) {
            long dateEpochA = a instanceof java.util.Date ? ((java.util.Date) a).getTime() : tryParseEpoch(strA);
            long dateEpochB = b instanceof java.util.Date ? ((java.util.Date) b).getTime() : tryParseEpoch(strB);
            if (dateEpochA != Long.MIN_VALUE && dateEpochB != Long.MIN_VALUE && dateEpochA == dateEpochB) return true;
        }
        
        // Try matching one value as epoch millis string vs the other as a date-format string
        // e.g. "1780375745783" (epoch) vs "2026-06-05 15:22:52.683" (Timestamp.toString())
        long epochA = tryParseEpoch(strA);
        long epochB = tryParseEpoch(strB);
        if (epochA != Long.MIN_VALUE || epochB != Long.MIN_VALUE) {
            long epoch = epochA != Long.MIN_VALUE ? epochA : epochB;
            String dateStr = epochA != Long.MIN_VALUE ? strB : strA;
            java.time.format.DateTimeFormatter[] dtFormatters = {
                java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME,
                java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
                java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.S"),
                java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SS"),
                java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"),
            };
            for (java.time.format.DateTimeFormatter fmt : dtFormatters) {
                try {
                    long dateMillis = java.time.LocalDateTime.parse(dateStr, fmt)
                        .atZone(java.time.ZoneId.of("UTC"))
                        .toInstant().toEpochMilli();
                    if (epoch == dateMillis) return true;
                } catch (Exception ignored2) {}
            }
            // Also try date-only formats (midnight)
            try {
                long dateMillis = java.time.LocalDate.parse(dateStr)
                    .atStartOfDay(java.time.ZoneId.of("UTC"))
                    .toInstant().toEpochMilli();
                if (epoch == dateMillis) return true;
            } catch (Exception ignored2) {}
        }
        
        // Numeric comparison: compare as BigDecimal
        try {
            java.math.BigDecimal bdA = new java.math.BigDecimal(strA);
            java.math.BigDecimal bdB = new java.math.BigDecimal(strB);
            if (bdA.compareTo(bdB) == 0) return true;
        } catch (Exception ignored) {
        }
        
        return false;
    }

        private String buildQuery(String tableName, String customQuery, List<String> pks, List<String> sortColumns, boolean useSurrogateKey) {
        String orderByClause = "";
        if (sortColumns != null && !sortColumns.isEmpty()) {
            orderByClause = sortColumns.stream().map(c -> c + " NULLS FIRST").collect(java.util.stream.Collectors.joining(", "));
        } else if (pks != null && !pks.isEmpty()) {
            orderByClause = pks.stream().map(c -> c + " NULLS FIRST").collect(java.util.stream.Collectors.joining(", "));
        }

        boolean hasOrderBy = !orderByClause.isEmpty();

        if (customQuery != null && !customQuery.trim().isEmpty()) {
            String q = customQuery.trim();
            if (useSurrogateKey) {
                String window = hasOrderBy ? " ORDER BY " + orderByClause : "";
                q = "SELECT ROW_NUMBER() OVER (" + window + ") as __rn__, tmp.* FROM (" + q + ") as tmp ORDER BY __rn__";
            } else if (!q.toUpperCase().contains("ORDER BY") && hasOrderBy) {
                q = q + " ORDER BY " + orderByClause;
            }
            return q;
        }

        String safeTable = formatTableName(tableName);

        if (useSurrogateKey) {
            String window = hasOrderBy ? " ORDER BY " + orderByClause : "";
            return "SELECT ROW_NUMBER() OVER (" + window + ") as __rn__, * FROM " + safeTable + " ORDER BY __rn__";
        }

        String orderBy = hasOrderBy ? " ORDER BY " + orderByClause : "";
        return "SELECT * FROM " + safeTable + orderBy;
    }

    private List<String> extractColumns(List<Map<String, Object>> source, List<Map<String, Object>> target,
                                         Set<String> excludeSet) {
        Set<String> cols = new LinkedHashSet<>();
        if (!source.isEmpty()) cols.addAll(source.get(0).keySet());
        if (!target.isEmpty()) cols.addAll(target.get(0).keySet());
        cols.remove("__rn__");
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
    }    @SuppressWarnings("unchecked")
    private int compareKeys(ResultSet rsS, ResultSet rsT, int[] exactPkIdx) throws Exception {
        for (int idx : exactPkIdx) {
            Object sObj = rsS.getObject(idx);
            Object tObj = rsT.getObject(idx);
            
            // NULL handling — matches SQL "NULLS FIRST" ordering
            if (sObj == null && tObj == null) continue;
            if (sObj == null) return -1;
            if (tObj == null) return 1;
            
            // Same Java type + Comparable → use native comparison
            // This correctly handles Timestamp, Date, String, Boolean, etc.
            if (sObj.getClass().equals(tObj.getClass()) && sObj instanceof Comparable) {
                int cmp = ((Comparable<Object>) sObj).compareTo(tObj);
                if (cmp != 0) return cmp;
                continue;
            }
            
            // Both Numbers but different types (e.g. Integer vs BigDecimal)
            if (sObj instanceof Number && tObj instanceof Number) {
                try {
                    java.math.BigDecimal bdS = toBigDecimal(sObj);
                    java.math.BigDecimal bdT = toBigDecimal(tObj);
                    int cmp = bdS.compareTo(bdT);
                    if (cmp != 0) return cmp;
                    continue;
                } catch (Exception ignored) {}
            }
            
            // Fallback: string comparison
            int cmp = sObj.toString().trim().compareTo(tObj.toString().trim());
            if (cmp != 0) return cmp;
        }
        return 0;
    }
    
    private boolean isNumericString(Object obj) {
        if (obj == null) return false;
        String s = obj.toString().trim();
        if (s.isEmpty()) return false;
        try {
            new java.math.BigDecimal(s);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
    
    private java.math.BigDecimal toBigDecimal(Object obj) {
        if (obj instanceof Number) {
            return new java.math.BigDecimal(obj.toString());
        }
        return new java.math.BigDecimal(obj.toString().trim());
    }
    
    private long tryParseEpoch(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            return Long.MIN_VALUE;
        }
    }
}
