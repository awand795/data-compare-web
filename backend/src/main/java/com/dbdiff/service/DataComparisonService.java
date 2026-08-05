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

    private static final int MAX_UNMATCHED_BUFFER = 10_000;

    private String buildDryRunQuery(String baseQuery, String type) {
        String dbType = type != null ? type.toLowerCase() : "postgresql";
        if ("sqlserver".equals(dbType)) {
            return "SELECT TOP 0 * FROM (" + baseQuery + ") AS tmp";
        }
        return "SELECT * FROM (" + baseQuery + ") AS tmp LIMIT 0";
    }

    // ─────────────────────────────────────────────────────────────────────────
    // compare() — non-streaming, untuk sync dan operasi kecil
    // ─────────────────────────────────────────────────────────────────────────
    public DiffResult compare(DiffRequest request) {
        sanitizeCustomQueries(request);
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

        List<String> effectiveSortColumns = request.getSortColumns();
        if (useSurrogateKey && (effectiveSortColumns == null || effectiveSortColumns.isEmpty())) {
            List<String> allCols = new ArrayList<>();
            if (request.getTableName() != null && !request.getTableName().isEmpty()) {
                logger.info("COMPARE: Attempting to fetch columns for table='{}', schema='{}'", request.getTableName(), request.getSourceConnection().getSchema());
                allCols = metaDataService.getColumns(sourceDs, request.getTableName(), request.getSourceConnection().getSchema());
            }
            if (allCols == null || allCols.isEmpty()) {
                String baseQuery = (request.getCustomQuerySource() != null && !request.getCustomQuerySource().isEmpty()) 
                    ? request.getCustomQuerySource() 
                    : "SELECT * FROM " + request.getTableName();
                logger.info("COMPARE: Fetching columns via JDBC PreparedStatement getMetaData...");
                try (Connection conn = sourceDs.getConnection();
                     PreparedStatement ps = conn.prepareStatement(baseQuery)) {
                    java.sql.ResultSetMetaData meta = ps.getMetaData();
                    if (meta != null) {
                        for (int i = 1; i <= meta.getColumnCount(); i++) {
                            allCols.add(meta.getColumnLabel(i));
                        }
                    }
                } catch (Exception e) {
                    logger.warn("COMPARE: Failed getMetaData column extraction: {}", e.getMessage());
                }
                
                if (allCols == null || allCols.isEmpty()) {
                    logger.info("COMPARE: Fallback to LIMIT 0 dry-run query...");
                    try (Connection conn = sourceDs.getConnection();
                         java.sql.Statement stmt = conn.createStatement();
                         ResultSet rs = stmt.executeQuery(buildDryRunQuery(baseQuery, request.getSourceConnection().getType()))) {
                        java.sql.ResultSetMetaData meta = rs.getMetaData();
                        for (int i = 1; i <= meta.getColumnCount(); i++) {
                            allCols.add(meta.getColumnLabel(i));
                        }
                    } catch (Exception e) {
                        logger.warn("COMPARE: Failed dry-run query LIMIT 0: {}", e.getMessage());
                    }
                }
            }
            if (allCols != null && !allCols.isEmpty()) {
                pksForOrder = new ArrayList<>(allCols);
                effectiveSortColumns = new ArrayList<>(allCols);
                useSurrogateKey = false;
                logger.info("COMPARE: ✅ Composite key mode — {} columns: {}", allCols.size(), allCols);
            } else {
                logger.warn("COMPARE: ⚠️ getColumns returned EMPTY — falling back to ROW_NUMBER (non-deterministic!)");
            }
        }

        String sourceDbType = request.getSourceConnection().getType() != null ? request.getSourceConnection().getType().toLowerCase() : "postgresql";
        String targetDbType = request.getTargetConnection().getType() != null ? request.getTargetConnection().getType().toLowerCase() : "postgresql";
        String sourceQuery = buildQuery(request.getTableName(), request.getCustomQuerySource(), pksForOrder, effectiveSortColumns, useSurrogateKey, false, sourceDbType);
        String targetQuery = buildQuery(request.getTableName(), request.getCustomQueryTarget(), pksForOrder, effectiveSortColumns, useSurrogateKey, false, targetDbType);

        if (useSurrogateKey) {
            pksForOrder = Collections.singletonList("__rn__");
        }

        logger.info("COMPARE DATA: Source Query = {}", sourceQuery);
        logger.info("COMPARE DATA: Target Query = {}", targetQuery);

        List<Map<String, Object>> sourceData;
        List<Map<String, Object>> targetData;

        long start = System.currentTimeMillis();
        sourceData = fetchWithCursor(sourceDs, sourceQuery);
        if (sourceData.size() >= MAX_SYNC_ROWS) {
            logger.warn("COMPARE DATA: Source capped at {} rows — use streaming compare for larger datasets", MAX_SYNC_ROWS);
        }
        logger.info("COMPARE DATA: Source {} rows in {}ms", sourceData.size(), (System.currentTimeMillis() - start));
        start = System.currentTimeMillis();
        targetData = fetchWithCursor(targetDs, targetQuery);
        if (targetData.size() >= MAX_SYNC_ROWS) {
            logger.warn("COMPARE DATA: Target capped at {} rows — use streaming compare for larger datasets", MAX_SYNC_ROWS);
        }
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

    private static final int MAX_SYNC_ROWS = 3_000;
    private static final int MAX_STREAM_ROWS = Integer.MAX_VALUE; // Safety cap — streaming does not accumulate rows in memory, so no practical limit

    private List<Map<String, Object>> fetchWithCursor(DataSource ds, String sql) {
        List<Map<String, Object>> results = new ArrayList<>(Math.min(5_000, MAX_SYNC_ROWS));
        try (Connection conn = ds.getConnection()) {
            conn.setAutoCommit(false);
            boolean success = false;
            try {
                try (PreparedStatement ps = conn.prepareStatement(sql,
                        ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
                    ps.setQueryTimeout(3600);
                    ps.setFetchSize(1000);
                    try (ResultSet rs = ps.executeQuery()) {
                        ResultSetMetaData meta = rs.getMetaData();
                        int colCount = meta.getColumnCount();
                        String[] cols = new String[colCount];
                        for (int i = 1; i <= colCount; i++) cols[i - 1] = meta.getColumnLabel(i);
                        int rowNum = 0;
                        while (rs.next() && rowNum < MAX_SYNC_ROWS) {
                            Map<String, Object> row = new LinkedHashMap<>(colCount);
                            for (int i = 0; i < colCount; i++) row.put(cols[i], getSafeObject(rs, i + 1));
                            results.add(row);
                            rowNum++;
                        }
                    }
                }
                success = true;
            } finally {
                // Commit on success so that any side-effect functions invoked inside the
                // query (e.g. INSERT via SELECT ... function()) are persisted.
                // Only rollback when the query itself threw an exception.
                if (success) {
                    try { conn.commit(); } catch (Exception ignored) {}
                } else {
                    try { conn.rollback(); } catch (Exception ignored) {}
                }
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
            final boolean[] totalsSent = {false};
            final long[] sCount = {0};
            final long[] tCount = {0};
            final long[] diffCount = {0};

            try {
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
                public void onMatchRow(String key, Object[] values, List<String> columns) throws Exception {
                    sCount[0]++;
                    tCount[0]++;
                    // Ultra-fast compact JSON for MATCH rows without allocating DiffRow/DiffCell
                    gen.writeStartObject();
                    gen.writeStringField("type", "m");
                    gen.writeStringField("k", key);
                    gen.writeArrayFieldStart("v");
                    for (Object val : values) {
                        gen.writeObject(val);
                    }
                    gen.writeEndArray();
                    gen.writeEndObject();
                    gen.writeRaw('\n');
                    rowCount[0]++;
                    if (rowCount[0] % 10000 == 0) gen.flush(); // Increased from 5000 to 10000
                    if (rowCount[0] >= MAX_STREAM_ROWS) {
                        logger.warn("STREAM COMPARE: Stopped at {} rows to prevent OOM", MAX_STREAM_ROWS);
                        throw new StreamLimitReachedException(MAX_STREAM_ROWS);
                    }
                }

                @Override
                public void onRow(DiffRow row) throws Exception {
                    if (row.getStatus() == DiffRow.Status.MATCH) {
                        sCount[0]++;
                        tCount[0]++;
                        gen.writeStartObject();
                        gen.writeStringField("type", "m");
                        gen.writeStringField("k", row.getRowKey());
                        gen.writeArrayFieldStart("v");
                        for (DiffCell cell : row.getCells().values()) gen.writeObject(cell.getSourceValue());
                        gen.writeEndArray();
                        gen.writeEndObject();
                        gen.writeRaw('\n');
                        rowCount[0]++;
                        if (rowCount[0] % 10000 == 0) gen.flush();
                        return;
                    }
                    
                    if (row.getStatus() == DiffRow.Status.DIFFERENT) {
                        sCount[0]++;
                        tCount[0]++;
                        diffCount[0]++;
                    } else if (row.getStatus() == DiffRow.Status.SOURCE_ONLY) {
                        sCount[0]++;
                        diffCount[0]++;
                    } else if (row.getStatus() == DiffRow.Status.TARGET_ONLY) {
                        tCount[0]++;
                        diffCount[0]++;
                    }

                    // Full format for DIFF/SOURCE_ONLY/TARGET_ONLY
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
                    if (rowCount[0] % 10000 == 0) gen.flush(); // Increased from 5000 to 10000
                    if (rowCount[0] >= MAX_STREAM_ROWS) {
                        logger.warn("STREAM COMPARE: Stopped at {} rows to prevent OOM", MAX_STREAM_ROWS);
                        throw new StreamLimitReachedException(MAX_STREAM_ROWS);
                    }
                }

                @Override
                public void onTotals(int totalSource, int totalTarget, int totalDiffs) throws Exception {
                    totalsSent[0] = true;
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
            } finally {
                if (!totalsSent[0]) {
                    try {
                        gen.flush();
                        gen.writeStartObject();
                        gen.writeStringField("type", "summary");
                        gen.writeObjectFieldStart("data");
                        gen.writeNumberField("totalSourceRows", sCount[0]);
                        gen.writeNumberField("totalTargetRows", tCount[0]);
                        gen.writeNumberField("totalDifferences", diffCount[0]);
                        gen.writeEndObject();
                        gen.writeEndObject();
                        gen.writeRaw('\n');
                        gen.flush();
                    } catch (Exception ex) {
                        logger.warn("Failed to write partial summary: {}", ex.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            throw new IOException("Failed to stream JSON", e);
        }
    }

    /**
     * Thrown to signal the streaming compare has reached its row safety cap.
     * This prevents OOM on extremely large tables.
     */
    public static class StreamLimitReachedException extends RuntimeException {
        private final int limit;
        public StreamLimitReachedException(int limit) {
            super("Stream limit reached: " + limit + " rows");
            this.limit = limit;
        }
        public int getLimit() { return limit; }
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
        sanitizeCustomQueries(request);
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

            // 2. Fallback: Dry-run query to get ResultSetMetaData (works for Custom Queries / Views)
            if (allCols == null || allCols.isEmpty()) {
                String baseQuery = (request.getCustomQuerySource() != null && !request.getCustomQuerySource().isEmpty()) 
                    ? request.getCustomQuerySource() 
                    : "SELECT * FROM " + request.getTableName();
                logger.info("STREAM COMPARE: Fetching columns via JDBC PreparedStatement getMetaData...");
                try (Connection conn = sourceDs.getConnection();
                     PreparedStatement ps = conn.prepareStatement(baseQuery)) {
                    java.sql.ResultSetMetaData meta = ps.getMetaData();
                    if (meta != null) {
                        for (int i = 1; i <= meta.getColumnCount(); i++) {
                            allCols.add(meta.getColumnLabel(i));
                        }
                    }
                } catch (Exception e) {
                    logger.warn("STREAM COMPARE: Failed getMetaData column extraction: {}", e.getMessage());
                }

                if (allCols == null || allCols.isEmpty()) {
                    logger.info("STREAM COMPARE: Fallback to LIMIT 0 dry-run query...");
                    try (Connection conn = sourceDs.getConnection();
                         java.sql.Statement stmt = conn.createStatement();
                         ResultSet rs = stmt.executeQuery(buildDryRunQuery(baseQuery, request.getSourceConnection().getType()))) {
                        java.sql.ResultSetMetaData meta = rs.getMetaData();
                        for (int i = 1; i <= meta.getColumnCount(); i++) {
                            allCols.add(meta.getColumnLabel(i));
                        }
                    } catch (Exception e) {
                        logger.warn("STREAM COMPARE: Failed dry-run query LIMIT 0: {}", e.getMessage());
                    }
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

        boolean useMd5Fallback = !useSurrogateKey && exactPks != null && exactPks.equals(effectiveSortColumns) && exactPks.size() > 5;
        String sourceDbType = request.getSourceConnection().getType() != null ? request.getSourceConnection().getType().toLowerCase() : "postgresql";
        String targetDbType = request.getTargetConnection().getType() != null ? request.getTargetConnection().getType().toLowerCase() : "postgresql";
        String sourceQuery = buildQuery(request.getTableName(), request.getCustomQuerySource(), exactPks, effectiveSortColumns, useSurrogateKey, useMd5Fallback, sourceDbType);
        String targetQuery = buildQuery(request.getTableName(), request.getCustomQueryTarget(), exactPks, effectiveSortColumns, useSurrogateKey, useMd5Fallback, targetDbType);

        logger.info("STREAM COMPARE: Source DB type={}, Target DB type={}", sourceDbType, targetDbType);
        logger.info("STREAM COMPARE: PK column={}, exactPks={}", request.getPrimaryKeys(), exactPks);
        logger.info("STREAM COMPARE: Source Query FINAL = {}", sourceQuery);
        logger.info("STREAM COMPARE: Target Query FINAL = {}", targetQuery);

        List<String> columns = new ArrayList<>();
        int[] totalSourceRows = {0};
        int[] totalTargetRows = {0};
        int[] differences = {0};

        String sourceId = request.getSourceConnection().getStableIdentifier();
        String targetId = request.getTargetConnection().getStableIdentifier();

        java.util.concurrent.Semaphore sem1;
        java.util.concurrent.Semaphore sem2;
        int permits1;
        int permits2;

        if (sourceId.equals(targetId)) {
            sem1 = connectionManagerService.getSemaphoreForPool(sourceId);
            sem2 = null;
            permits1 = 1;
            permits2 = 0;
        } else {
            if (sourceId.compareTo(targetId) < 0) {
                sem1 = connectionManagerService.getSemaphoreForPool(sourceId);
                sem2 = connectionManagerService.getSemaphoreForPool(targetId);
            } else {
                sem1 = connectionManagerService.getSemaphoreForPool(targetId);
                sem2 = connectionManagerService.getSemaphoreForPool(sourceId);
            }
            permits1 = 1;
            permits2 = 1;
        }

        boolean acq1 = false;
        boolean acq2 = false;
        try {
            acq1 = sem1.tryAcquire(permits1, 5, java.util.concurrent.TimeUnit.MINUTES);
            if (!acq1) {
                throw new RuntimeException("Database comparison pool is too busy. Please try again later.");
            }
            if (sem2 != null) {
                acq2 = sem2.tryAcquire(permits2, 5, java.util.concurrent.TimeUnit.MINUTES);
                if (!acq2) {
                    throw new RuntimeException("Database comparison pool is too busy. Please try again later.");
                }
            }

            try (Connection sConn = sourceDs.getConnection();
                 Connection tConn = targetDs.getConnection()) {
                sConn.setAutoCommit(false);
                tConn.setAutoCommit(false);
                boolean querySuccess = false;
                try {
                    try (PreparedStatement psSource = sConn.prepareStatement(sourceQuery, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
                         PreparedStatement psTarget = tConn.prepareStatement(targetQuery, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
                        
                        String sName = sConn.getMetaData().getDatabaseProductName().toLowerCase();
                        String tName = tConn.getMetaData().getDatabaseProductName().toLowerCase();
                        psSource.setFetchSize(sName.contains("mysql") ? Integer.MIN_VALUE : 10000);
                        psTarget.setFetchSize(tName.contains("mysql") ? Integer.MIN_VALUE : 10000);
                        psSource.setQueryTimeout(3600); // 1 hour safety timeout
                        psTarget.setQueryTimeout(3600); // 1 hour safety timeout

                        try (ResultSet rsSource = psSource.executeQuery(); 
                             ResultSet rsTarget = psTarget.executeQuery()) {
                            
                            ResultSetMetaData sMeta = rsSource.getMetaData();
                            ResultSetMetaData tMeta = rsTarget.getMetaData();
                            columns = extractColumnsFromMeta(sMeta, excludeSet);
                            if (useSurrogateKey) {
                                exactPks = Collections.singletonList("__rn__");
                            } else {
                                exactPks = resolveExactPks(pkInput, dbPks, columns);
                            }
                            consumer.onColumns(columns);
                            logger.info("STREAM COMPARE: {} kolom, PKs={}", columns.size(), exactPks);

                            int[] sColIdx = resolveColumnIndices(sMeta, columns);
                            int[] tColIdx = resolveColumnIndices(tMeta, columns);
                            int[] sPkIdx = resolveColumnIndices(sMeta, exactPks);
                            int[] tPkIdx = resolveColumnIndices(tMeta, exactPks);
                            
                            // === HashMap-based comparison (toleran terhadap perbedaan collation) ===
                            // Phase 1: stream seluruh source → simpan ke HashMap
                            Map<String, Object[]> sourceHashMap = new LinkedHashMap<>(100_000);
                            while (rsSource.next()) {
                                String sKey = buildKeyFromRs(rsSource, sPkIdx);
                                Object[] sRow = getRow(rsSource, columns.size(), sColIdx);
                                sourceHashMap.put(sKey, sRow);
                                totalSourceRows[0]++;
                            }
                            logger.info("STREAM COMPARE: Source loaded {} rows into HashMap", sourceHashMap.size());

                            // Phase 2: stream target → lookup di sourceHashMap
                            Set<String> matchedKeys = new HashSet<>(sourceHashMap.size());
                            while (rsTarget.next()) {
                                String tKey = buildKeyFromRs(rsTarget, tPkIdx);
                                Object[] tRow = getRow(rsTarget, columns.size(), tColIdx);
                                totalTargetRows[0]++;

                                Object[] sRow = sourceHashMap.get(tKey);
                                if (sRow != null) {
                                    matchedKeys.add(tKey);
                                    boolean allEqual = true;
                                    for (int i = 0; i < columns.size(); i++) {
                                        if (!normalizedEquals(sRow[i], tRow[i])) { allEqual = false; break; }
                                    }
                                    if (allEqual) {
                                        if (request.isReturnMatchedRows()) {
                                            consumer.onMatchRow(tKey, sRow, columns);
                                        }
                                    } else {
                                        differences[0]++;
                                        DiffRow diffRow = buildDiffRowFromArrays(tKey, sRow, tRow, columns);
                                        consumer.onRow(diffRow);
                                    }
                                } else {
                                    differences[0]++;
                                    DiffRow diffRow = buildTargetOnlyRow(tKey, tRow, columns);
                                    consumer.onRow(diffRow);
                                }
                            }

                            // Phase 3: SOURCE_ONLY = keys source yang tidak pernah match dengan target
                            for (Map.Entry<String, Object[]> entry : sourceHashMap.entrySet()) {
                                if (!matchedKeys.contains(entry.getKey())) {
                                    differences[0]++;
                                    DiffRow diffRow = buildSourceOnlyRow(entry.getKey(), entry.getValue(), columns);
                                    consumer.onRow(diffRow);
                                }
                            }
                            logger.info("STREAM COMPARE: Done. source={} target={} diff={}", 
                                totalSourceRows[0], totalTargetRows[0], differences[0]);
                        }
                    }
                    querySuccess = true;
                } finally {
                    // Commit on success so that side-effect functions called inside the query
                    // (e.g. SELECT function_that_inserts()) are persisted to the database.
                    // Only rollback when the query itself threw an exception.
                    if (querySuccess) {
                        try { sConn.commit(); } catch (Exception ignored) {}
                        try { tConn.commit(); } catch (Exception ignored) {}
                    } else {
                        try { sConn.rollback(); } catch (Exception ignored) {}
                        try { tConn.rollback(); } catch (Exception ignored) {}
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Comparison interrupted while waiting for database semaphore", e);
        } catch (StreamLimitReachedException e) {
            logger.warn("STREAM COMPARE: Hit row limit {}. Sending partial totals.", e.getLimit());
            // Jangan re-throw — biarkan finally block release semaphore, lalu panggil onTotals di bawah
        } finally {
            if (sem2 != null && acq2) {
                sem2.release(permits2);
            }
            if (acq1) {
                sem1.release(permits1);
            }
        }

        logger.info("STREAM COMPARE: SELESAI. Total={}ms", (System.currentTimeMillis() - startTime));
        consumer.onTotals(totalSourceRows[0], totalTargetRows[0], differences[0]);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // compareBatch() — paginated comparison dengan LIMIT/OFFSET
    // ─────────────────────────────────────────────────────────────────────────
    public Map<String, Object> compareBatch(DiffRequest request, int batchSize, int offset) {
        sanitizeCustomQueries(request);
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

        if (useSurrogateKey) {
            throw new IllegalArgumentException("Batch mode requires primary key or sort columns for accuracy. Please configure them before comparing.");
        }

        // Same deterministic ordering fix as processStream
        List<String> effectiveSortColumns = request.getSortColumns();
        if (useSurrogateKey && (effectiveSortColumns == null || effectiveSortColumns.isEmpty())) {
            List<String> allCols = new ArrayList<>();
            if (request.getTableName() != null && !request.getTableName().isEmpty()) {
                logger.info("BATCH COMPARE: Attempting to fetch columns for table='{}', schema='{}'", request.getTableName(), request.getSourceConnection().getSchema());
                allCols = metaDataService.getColumns(sourceDs, request.getTableName(), request.getSourceConnection().getSchema());
            }
            if (allCols == null || allCols.isEmpty()) {
                String baseQuery = (request.getCustomQuerySource() != null && !request.getCustomQuerySource().isEmpty()) 
                    ? request.getCustomQuerySource() 
                    : "SELECT * FROM " + request.getTableName();
                logger.info("BATCH COMPARE: Fetching columns via JDBC PreparedStatement getMetaData...");
                try (Connection conn = sourceDs.getConnection();
                     PreparedStatement ps = conn.prepareStatement(baseQuery)) {
                    java.sql.ResultSetMetaData meta = ps.getMetaData();
                    if (meta != null) {
                        for (int i = 1; i <= meta.getColumnCount(); i++) {
                            allCols.add(meta.getColumnLabel(i));
                        }
                    }
                } catch (Exception e) {
                    logger.warn("BATCH COMPARE: Failed getMetaData column extraction: {}", e.getMessage());
                }

                if (allCols == null || allCols.isEmpty()) {
                    logger.info("BATCH COMPARE: Fallback to LIMIT 0 dry-run query...");
                    try (Connection conn = sourceDs.getConnection();
                         java.sql.Statement stmt = conn.createStatement();
                         ResultSet rs = stmt.executeQuery(buildDryRunQuery(baseQuery, request.getSourceConnection().getType()))) {
                        java.sql.ResultSetMetaData meta = rs.getMetaData();
                        for (int i = 1; i <= meta.getColumnCount(); i++) {
                            allCols.add(meta.getColumnLabel(i));
                        }
                    } catch (Exception e) {
                        logger.warn("BATCH COMPARE: Failed dry-run query LIMIT 0: {}", e.getMessage());
                    }
                }
            }
            if (allCols != null && !allCols.isEmpty()) {
                exactPks = new ArrayList<>(allCols);
                effectiveSortColumns = new ArrayList<>(allCols);
                useSurrogateKey = false;
                logger.info("BATCH COMPARE: ✅ Composite key mode — {} columns: {}", allCols.size(), allCols);
            } else {
                logger.warn("BATCH COMPARE: ⚠️ getColumns returned EMPTY — falling back to ROW_NUMBER (non-deterministic!)");
            }
        }

        boolean useMd5Fallback = !useSurrogateKey && exactPks != null && exactPks.equals(effectiveSortColumns) && exactPks.size() > 5;
        String sourceDbType = request.getSourceConnection().getType() != null ? request.getSourceConnection().getType().toLowerCase() : "postgresql";
        String targetDbType = request.getTargetConnection().getType() != null ? request.getTargetConnection().getType().toLowerCase() : "postgresql";
        String sourceQuery = buildQuery(request.getTableName(), request.getCustomQuerySource(), exactPks, effectiveSortColumns, useSurrogateKey, useMd5Fallback, sourceDbType);
        // Ambil batch dari SOURCE saja
        sourceQuery = addLimitOffset(sourceQuery, batchSize, offset);

        logger.info("BATCH COMPARE: offset={} batchSize={}", offset, batchSize);
        logger.info("BATCH COMPARE: Source Query = {}", sourceQuery);

        List<Map<String, Object>> sourceData = fetchWithCursor(sourceDs, sourceQuery);
        Set<String> excludeSet = buildExcludeSet(request);
        int sourceSize = sourceData.size();

        List<Map<String, Object>> targetData = new ArrayList<>();
        if (sourceSize > 0) {
            final List<String> finalExactPks = exactPks;
            List<Object> sourcePkValues = sourceData.stream()
                .map(row -> finalExactPks.size() == 1 ? row.get(finalExactPks.get(0)) : buildKey(row, finalExactPks))
                .collect(java.util.stream.Collectors.toList());

            String baseQuery = request.getCustomQueryTarget() != null && !request.getCustomQueryTarget().trim().isEmpty()
                ? request.getCustomQueryTarget() 
                : "SELECT * FROM " + formatTableName(request.getTableName()) + ("clickhouse".equals(targetDbType) ? " FINAL" : "");
                
            String targetKeyQuery = buildInClauseQuery(baseQuery, exactPks, sourcePkValues, targetDbType);
            logger.info("BATCH COMPARE: Target Query = {}", targetKeyQuery);
            targetData = fetchWithCursor(targetDs, targetKeyQuery);
        }

        List<String> columns = extractColumns(sourceData, targetData, excludeSet);
        Map<String, Map<String, Object>> sourceMap = mapByKeys(sourceData, exactPks);
        int targetSize = targetData.size();

        int matchCount = 0, differentCount = 0, sourceOnlyCount = 0, targetOnlyCount = 0;
        List<DiffRow> rows = new ArrayList<>(Math.min(1000, Math.max(sourceSize, targetSize)));

        Set<String> matchedSourceKeys = new HashSet<>();
        for (Map<String, Object> tRow : targetData) {
            String key = buildKey(tRow, exactPks);
            Map<String, Object> sRow = sourceMap.get(key);
            DiffRow row = buildDiffRow(key, sRow, tRow, columns);
            if (sRow != null) matchedSourceKeys.add(key);
            switch (row.getStatus()) {
                case MATCH: matchCount++; break;
                case DIFFERENT: differentCount++; break;
                case SOURCE_ONLY: sourceOnlyCount++; break;
                case TARGET_ONLY: targetOnlyCount++; break;
            }
            if (request.isReturnMatchedRows() || row.getStatus() != DiffRow.Status.MATCH) {
                rows.add(row);
            }
        }

        for (Map.Entry<String, Map<String, Object>> entry : sourceMap.entrySet()) {
            if (!matchedSourceKeys.contains(entry.getKey())) {
                DiffRow row = buildDiffRow(entry.getKey(), entry.getValue(), null, columns);
                sourceOnlyCount++;
                rows.add(row);
            }
        }

        int effectiveCap = Math.min(batchSize, MAX_SYNC_ROWS);
        boolean hasMore = sourceSize >= effectiveCap;

        Map<String, Object> result = new HashMap<>();
        result.put("columns", columns);
        result.put("rows", rows);
        result.put("matchCount", matchCount);
        result.put("differentCount", differentCount);
        result.put("sourceOnlyCount", sourceOnlyCount);
        result.put("targetOnlyCount", targetOnlyCount);
        result.put("hasMore", hasMore);
        result.put("nextOffset", offset + sourceSize);
        result.put("sourceBatchRows", sourceSize);
        result.put("targetBatchRows", targetSize);
        result.put("elapsedMs", System.currentTimeMillis() - startTime);

        boolean isDifferentDB = !request.getSourceConnection().getStableIdentifier().equals(request.getTargetConnection().getStableIdentifier());
        if (isDifferentDB) {
            result.put("warning", "Cross-DB Comparison Warning: Untuk data yang sangat besar atau jika ada perbedaan struktur collation, direkomendasikan menggunakan mode Streaming Compare (/api/compare) daripada Batch Mode, karena Streaming menggunakan algoritma merge-join cursor yang lebih handal dan aman dari pergeseran baris.");
        }

        logger.info("BATCH COMPARE: SELESAI offset={} rows={} diff={} hasMore={} elapsed={}ms",
                offset, rows.size(), differentCount + sourceOnlyCount + targetOnlyCount, hasMore, result.get("elapsedMs"));

        return result;
    }

    public Map<String, Object> compareBatchBySourceKeys(DiffRequest request, int batchSize, int offset) {
        long startTime = System.currentTimeMillis();
        sanitizeCustomQueries(request);
        DataSource sourceDs = connectionManagerService.getDataSource(request.getSourceConnection());
        DataSource targetDs = connectionManagerService.getDataSource(request.getTargetConnection());

        List<String> exactPks = resolvePks(request, sourceDs, null);
        boolean useSurrogateKey = exactPks.isEmpty();
        if (useSurrogateKey) {
            throw new IllegalArgumentException("compareBatchBySourceKeys requires primary keys to be set.");
        }

        List<String> effectiveSortColumns = request.getSortColumns();
        boolean useMd5Fallback = false;

        String sourceDbType = request.getSourceConnection().getType() != null ? request.getSourceConnection().getType().toLowerCase() : "postgresql";
        String targetDbType = request.getTargetConnection().getType() != null ? request.getTargetConnection().getType().toLowerCase() : "postgresql";
        
        String sourceQuery = buildQuery(request.getTableName(), request.getCustomQuerySource(), exactPks, effectiveSortColumns, useSurrogateKey, useMd5Fallback, sourceDbType);
        sourceQuery = addLimitOffset(sourceQuery, batchSize, offset);

        logger.info("BATCH COMPARE BY KEYS: Source Query = {}", sourceQuery);

        List<Map<String, Object>> sourceData = fetchWithCursor(sourceDs, sourceQuery);
        Set<String> excludeSet = buildExcludeSet(request);
        int sourceSize = sourceData.size();
        
        List<Map<String, Object>> targetData = new ArrayList<>();
        if (sourceSize > 0) {
            List<Object> sourcePkValues = sourceData.stream()
                .map(row -> exactPks.size() == 1 ? row.get(exactPks.get(0)) : buildKey(row, exactPks))
                .collect(java.util.stream.Collectors.toList());

            String baseQuery = request.getCustomQueryTarget() != null && !request.getCustomQueryTarget().trim().isEmpty()
                ? request.getCustomQueryTarget() 
                : "SELECT * FROM " + formatTableName(request.getTableName()) + ("clickhouse".equals(targetDbType) ? " FINAL" : "");
                
            String targetKeyQuery = buildInClauseQuery(baseQuery, exactPks, sourcePkValues, targetDbType);
            logger.info("BATCH COMPARE BY KEYS: Target Query = {}", targetKeyQuery);
            targetData = fetchWithCursor(targetDs, targetKeyQuery);
        }

        List<String> columns = extractColumns(sourceData, targetData, excludeSet);
        Map<String, Map<String, Object>> sourceMap = mapByKeys(sourceData, exactPks);
        int targetSize = targetData.size();

        int matchCount = 0, differentCount = 0, sourceOnlyCount = 0, targetOnlyCount = 0;
        List<DiffRow> rows = new ArrayList<>(Math.min(1000, Math.max(sourceSize, targetSize)));

        Set<String> matchedSourceKeys = new HashSet<>();
        for (Map<String, Object> tRow : targetData) {
            String key = buildKey(tRow, exactPks);
            Map<String, Object> sRow = sourceMap.get(key);
            DiffRow row = buildDiffRow(key, sRow, tRow, columns);
            if (sRow != null) matchedSourceKeys.add(key);
            switch (row.getStatus()) {
                case MATCH: matchCount++; break;
                case DIFFERENT: differentCount++; break;
                case SOURCE_ONLY: sourceOnlyCount++; break;
                case TARGET_ONLY: targetOnlyCount++; break;
            }
            if (request.isReturnMatchedRows() || row.getStatus() != DiffRow.Status.MATCH) {
                rows.add(row);
            }
        }

        for (Map.Entry<String, Map<String, Object>> entry : sourceMap.entrySet()) {
            if (!matchedSourceKeys.contains(entry.getKey())) {
                DiffRow row = buildDiffRow(entry.getKey(), entry.getValue(), null, columns);
                sourceOnlyCount++;
                rows.add(row);
            }
        }

        int effectiveCap = Math.min(batchSize, MAX_SYNC_ROWS);
        boolean hasMore = sourceSize >= effectiveCap;

        Map<String, Object> result = new HashMap<>();
        result.put("columns", columns);
        result.put("rows", rows);
        result.put("matchCount", matchCount);
        result.put("differentCount", differentCount);
        result.put("sourceOnlyCount", sourceOnlyCount);
        result.put("targetOnlyCount", targetOnlyCount);
        result.put("hasMore", hasMore);
        result.put("nextOffset", offset + sourceSize);
        result.put("sourceBatchRows", sourceSize);
        result.put("targetBatchRows", targetSize);
        result.put("elapsedMs", System.currentTimeMillis() - startTime);

        logger.info("BATCH COMPARE BY KEYS: SELESAI offset={} rows={} diff={} hasMore={} elapsed={}ms",
                offset, rows.size(), differentCount + sourceOnlyCount + targetOnlyCount, hasMore, result.get("elapsedMs"));

        return result;
    }

    private String buildInClauseQuery(String baseQuery, List<String> pks, List<Object> pkValues, String dbType) {
        if (pks.size() == 1) {
            // Single PK: use simple IN clause
            String pkList = pkValues.stream()
                .map(v -> v == null ? "NULL" : "'" + v.toString().replace("'", "''") + "'")
                .collect(java.util.stream.Collectors.joining(", "));
            String pkCol = quoteIdentifier(pks.get(0), dbType);
            String inner = "SELECT * FROM (" + baseQuery + ") AS __src_keys WHERE " + pkCol + " IN (" + pkList + ")";
            return inner + " ORDER BY " + pkCol + ("postgresql".equals(dbType) ? " ASC NULLS FIRST" : " ASC");
        } else {
            // Composite PK: build tuple IN clause (PostgreSQL/MySQL support this)
            String pkCols = pks.stream().map(p -> quoteIdentifier(p, dbType)).collect(java.util.stream.Collectors.joining(", "));
            String tupleList = pkValues.stream()
                .map(v -> "(" + v.toString().replace("\u0000", ", ").replace("'", "''") + ")")
                .collect(java.util.stream.Collectors.joining(", "));
            return "SELECT * FROM (" + baseQuery + ") AS __src_keys WHERE (" + pkCols + ") IN (" + tupleList + ")";
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // countRows() — count rows in source & target (for batch progress)
    // ─────────────────────────────────────────────────────────────────────────
    public Map<String, Object> countRows(DiffRequest request) {
        sanitizeCustomQueries(request);
        DataSource sourceDs = connectionManagerService.getDataSource(request.getSourceConnection());
        DataSource targetDs = connectionManagerService.getDataSource(request.getTargetConnection());
        JdbcTemplate sourceJdbc = new JdbcTemplate(sourceDs);
        JdbcTemplate targetJdbc = new JdbcTemplate(targetDs);

        String sourceQuery, targetQuery;
        if (request.getCustomQuerySource() != null && !request.getCustomQuerySource().trim().isEmpty()) {
            sourceQuery = "SELECT COUNT(*) FROM (" + request.getCustomQuerySource().trim() + ") _cnt";
        } else if (request.getTableName() != null) {
            String dbType = request.getSourceConnection().getType() != null ? request.getSourceConnection().getType().toLowerCase() : "postgresql";
            sourceQuery = "SELECT COUNT(*) FROM " + formatTableName(request.getTableName()) + ("clickhouse".equals(dbType) ? " FINAL" : "");
        } else {
            String fallbackSrc = request.getCustomQuerySource();
            if (fallbackSrc == null || fallbackSrc.trim().isEmpty()) {
                throw new IllegalArgumentException("Either tableName or customQuerySource must be provided for count.");
            }
            sourceQuery = "SELECT COUNT(*) FROM (" + fallbackSrc.trim() + ") _cnt";
        }

        if (request.getCustomQueryTarget() != null && !request.getCustomQueryTarget().trim().isEmpty()) {
            targetQuery = "SELECT COUNT(*) FROM (" + request.getCustomQueryTarget().trim() + ") _cnt";
        } else if (request.getTableName() != null) {
            String dbType = request.getTargetConnection().getType() != null ? request.getTargetConnection().getType().toLowerCase() : "postgresql";
            targetQuery = "SELECT COUNT(*) FROM " + formatTableName(request.getTableName()) + ("clickhouse".equals(dbType) ? " FINAL" : "");
        } else {
            String fallbackTgt = request.getCustomQueryTarget();
            if (fallbackTgt == null || fallbackTgt.trim().isEmpty()) {
                throw new IllegalArgumentException("Either tableName or customQueryTarget must be provided for count.");
            }
            targetQuery = "SELECT COUNT(*) FROM (" + fallbackTgt.trim() + ") _cnt";
        }

        Long sourceCountLong = sourceJdbc.queryForObject(sourceQuery, Long.class);
        Long targetCountLong = targetJdbc.queryForObject(targetQuery, Long.class);
        long sourceCount = sourceCountLong != null ? sourceCountLong : 0L;
        long targetCount = targetCountLong != null ? targetCountLong : 0L;

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
        if (trimmed.endsWith(";")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
        }
        // Detect SQL Server by checking if this is a subquery of ROW_NUMBER pattern
        // For safety, wrap as subquery with standard syntax
        // PostgreSQL & MySQL: LIMIT/OFFSET
        // SQL Server: OFFSET/FETCH (requires ORDER BY)
        boolean hasSqlServerPattern = trimmed.toUpperCase().contains("ROW_NUMBER()") 
            || trimmed.toUpperCase().contains("TOP ");
        if (hasSqlServerPattern) {
            // SQL Server: wrap and use OFFSET FETCH
            if (!trimmed.toUpperCase().contains("ORDER BY")) {
                trimmed = trimmed + " ORDER BY (SELECT NULL)";
            }
            return trimmed + " OFFSET " + offset + " ROWS FETCH NEXT " + limit + " ROWS ONLY";
        }
        // Default PostgreSQL / MySQL
        return trimmed + " LIMIT " + limit + " OFFSET " + offset;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // syncData()
    // ─────────────────────────────────────────────────────────────────────────
    public Map<String, Object> syncData(DiffRequest request) {
        // Safety check: count rows first to prevent OOM on large tables
        Map<String, Object> counts = countRows(request);
        long srcCount = ((Number) counts.get("sourceCount")).longValue();
        long tgtCount = ((Number) counts.get("targetCount")).longValue();
        if (srcCount > MAX_SYNC_ROWS || tgtCount > MAX_SYNC_ROWS) {
            throw new IllegalArgumentException(
                String.format("Sync is limited to %d rows for memory safety. Source has %d rows, Target has %d rows. " +
                    "Please use a smaller dataset or filter with custom query.", MAX_SYNC_ROWS, srcCount, tgtCount));
        }

        DiffResult diff = compare(request);
        DataSource targetDs = connectionManagerService.getDataSource(request.getTargetConnection());
        try (java.sql.Connection targetConn = targetDs.getConnection()) {
            targetConn.setAutoCommit(false);
            try {
                JdbcTemplate targetJdbc = new JdbcTemplate(new org.springframework.jdbc.datasource.SingleConnectionDataSource(targetConn, true));

                List<String> pks = request.getPrimaryKeys();
                if (pks == null || pks.isEmpty()) throw new IllegalArgumentException("Primary keys required for sync.");

                String tableName = request.getTableName();
                List<String> columns = diff.getColumns();
                int inserted = 0, updated = 0, deleted = 0;

                String dbType = request.getTargetConnection().getType().toLowerCase();
                String safeTable = formatTableName(tableName);
                String colsSql = columns.stream()
                        .map(c -> quoteIdentifier(c, dbType))
                        .collect(Collectors.joining(", "));

                for (DiffRow row : diff.getRows()) {
                    if (row.getStatus() == DiffRow.Status.SOURCE_ONLY) {
                        String sql = "INSERT INTO " + safeTable + " (" + colsSql + ") VALUES (" +
                                columns.stream().map(c -> "?").collect(Collectors.joining(", ")) + ")";
                        targetJdbc.update(sql, columns.stream().map(c -> row.getCells().get(c).getSourceValue()).toArray());
                        inserted++;
                    } else if (row.getStatus() == DiffRow.Status.DIFFERENT) {
                        List<String> updCols = columns.stream().filter(c -> !pks.contains(c)).collect(Collectors.toList());
                        String sql = "UPDATE " + safeTable + " SET " +
                                updCols.stream().map(c -> quoteIdentifier(c, dbType) + " = ?").collect(Collectors.joining(", ")) +
                                " WHERE " + pks.stream().map(pk -> quoteIdentifier(pk, dbType) + " = ?").collect(Collectors.joining(" AND "));
                        List<Object> args = new ArrayList<>();
                        updCols.forEach(c -> args.add(row.getCells().get(c).getSourceValue()));
                        pks.forEach(pk -> args.add(row.getCells().get(pk).getSourceValue()));
                        targetJdbc.update(sql, args.toArray());
                        updated++;
                    } else if (row.getStatus() == DiffRow.Status.TARGET_ONLY) {
                        String sql = "DELETE FROM " + safeTable + " WHERE " +
                                pks.stream().map(pk -> quoteIdentifier(pk, dbType) + " = ?").collect(Collectors.joining(" AND "));
                        targetJdbc.update(sql, pks.stream().map(pk -> row.getCells().get(pk).getTargetValue()).toArray());
                        deleted++;
                    }
                }

                targetConn.commit();

                // Release diff data immediately
                diff = null;

                Map<String, Object> result = new HashMap<>();
                result.put("success", true);
                result.put("inserted", inserted);
                result.put("updated", updated);
                result.put("deleted", deleted);
                return result;
            } catch (Exception e) {
                targetConn.rollback();
                throw e;
            } finally {
                try { targetConn.setAutoCommit(true); } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            if (e instanceof RuntimeException) throw (RuntimeException) e;
            throw new RuntimeException("Sync failed: " + e.getMessage(), e);
        }
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
            idx[i] = (ci != null) ? ci : -1;
        }
        return idx;
    }

    private String buildKeyFromRs(ResultSet rs, int[] exactPkIdx) throws Exception {
        StringBuilder sb = new StringBuilder(exactPkIdx.length * 16);
        for (int i = 0; i < exactPkIdx.length; i++) {
            if (i > 0) sb.append('\u0000');
            if (exactPkIdx[i] > 0) {
                String val = rs.getString(exactPkIdx[i]);
                sb.append(val == null ? "\u0001NULL\u0001" : val);
            } else {
                sb.append("\u0001NULL\u0001");
            }
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

    private boolean fastRowEquals(ResultSet rs1, ResultSet rs2, int[] idx1, int[] idx2, int size) throws Exception {
        for (int i = 0; i < size; i++) {
            if (idx1[i] > 0 && idx2[i] > 0) {
                if (!normalizedEquals(getSafeObject(rs1, idx1[i]), getSafeObject(rs2, idx2[i]))) return false;
            } else if (idx1[i] > 0 || idx2[i] > 0) {
                return false; // one has the column, other doesn't
            }
        }
        return true;
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

    // Cached DateTimeFormatters to avoid creating new instances on every comparison (called millions of times)
    private static final java.time.format.DateTimeFormatter[] DT_FORMATTERS = {
        java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME,
        java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
        java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.S"),
        java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SS"),
        java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"),
    };

    /**
     * Membandingkan dua nilai dari database dengan normalisasi tipe.
     * NULL tidak sama dengan empty string — keduanya dianggap berbeda.
     * Number dari tipe berbeda (Integer vs Long) dibandingkan secara numerik.
     * Date string dengan format berbeda dibandingkan secara epoch.
     */
    private boolean normalizedEquals(Object a, Object b) {
        if (a == b) return true; // same reference or both null
        if (a == null || b == null) return false; // NULL != "" — perbedaan semantik yang valid
        if (a.equals(b)) return true;
        
        // Same class fast path — most common case in DB comparisons
        if (a.getClass() == b.getClass()) {
            if (a instanceof Number) {
                double da = ((Number) a).doubleValue();
                double db = ((Number) b).doubleValue();
                if (Double.isNaN(da) && Double.isNaN(db)) return true;
                if (Double.isInfinite(da) && Double.isInfinite(db) && Math.signum(da) == Math.signum(db)) return true;
                if (Math.abs(da - db) < 1e-9) return true;
                try {
                    return new java.math.BigDecimal(a.toString()).compareTo(new java.math.BigDecimal(b.toString())) == 0;
                } catch (Exception e) { return false; }
            }
            return a.toString().trim().equalsIgnoreCase(b.toString().trim());
        }
        
        // Both Numbers but different types (e.g. Integer vs Long)
        if (a instanceof Number && b instanceof Number) {
            double da = ((Number) a).doubleValue();
            double db = ((Number) b).doubleValue();
            if (Double.isNaN(da) && Double.isNaN(db)) return true;
            if (Double.isInfinite(da) && Double.isInfinite(db) && Math.signum(da) == Math.signum(db)) return true;
            if (Math.abs(da - db) < 1e-9) return true;
            // Fall through to BigDecimal for precision
            try {
                return new java.math.BigDecimal(a.toString()).compareTo(new java.math.BigDecimal(b.toString())) == 0;
            } catch (Exception e) { return false; }
        }
        
        // Boolean vs Number (e.g., MySQL TINYINT vs Postgres BOOLEAN)
        if (a instanceof Boolean && b instanceof Number) {
            return ((Boolean) a) ? ((Number) b).intValue() == 1 : ((Number) b).intValue() == 0;
        }
        if (b instanceof Boolean && a instanceof Number) {
            return ((Boolean) b) ? ((Number) a).intValue() == 1 : ((Number) a).intValue() == 0;
        }

        String strA = a.toString().trim();
        String strB = b.toString().trim();
        if (strA.equalsIgnoreCase(strB)) return true;
        
        // Date vs epoch comparison
        if (a instanceof java.util.Date || b instanceof java.util.Date) {
            long epochA = a instanceof java.util.Date ? ((java.util.Date) a).getTime() : tryParseEpoch(strA);
            long epochB = b instanceof java.util.Date ? ((java.util.Date) b).getTime() : tryParseEpoch(strB);
            if (epochA != Long.MIN_VALUE && epochB != Long.MIN_VALUE && epochA == epochB) return true;
            // Try date string formats only when one side is a Date
            long epoch = epochA != Long.MIN_VALUE ? epochA : epochB;
            String dateStr = epochA != Long.MIN_VALUE ? strB : strA;
            if (epoch != Long.MIN_VALUE) {
                long parsedEpoch = tryParseDateString(dateStr);
                if (parsedEpoch != Long.MIN_VALUE && epoch == parsedEpoch) return true;
            }
        }
        
        // Two Strings that look like Dates (e.g. "2026-06-18 10:00:00.0" vs "2026-06-18 10:00:00")
        if (a instanceof String && b instanceof String && strA.length() >= 10 && strB.length() >= 10) {
            if (Character.isDigit(strA.charAt(0)) && Character.isDigit(strB.charAt(0))) {
                long epochA = tryParseDateString(strA);
                if (epochA != Long.MIN_VALUE) {
                    long epochB = tryParseDateString(strB);
                    if (epochA == epochB) return true;
                }
            }
        }
        
        // Numeric string comparison — only if both look numeric (start with digit or minus)
        if (looksNumeric(strA) && looksNumeric(strB)) {
            try {
                return new java.math.BigDecimal(strA).compareTo(new java.math.BigDecimal(strB)) == 0;
            } catch (Exception ignored) {}
        }
        
        return false;
    }
    
    private static boolean looksNumeric(String s) {
        if (s.isEmpty()) return false;
        char c = s.charAt(0);
        return (c >= '0' && c <= '9') || c == '-' || c == '.';
    }

    private String quoteIdentifier(String col, String dbType) {
        if ("sqlserver".equals(dbType)) return "[" + col.replace("]", "]]") + "]";
        if ("mysql".equals(dbType) || "mariadb".equals(dbType) || "clickhouse".equals(dbType)) return "`" + col.replace("`", "``") + "`";
        return "\"" + col.replace("\"", "\"\"") + "\"";
    }

    private String buildQuery(String tableName, String customQuery, List<String> pks, List<String> sortColumns, boolean useSurrogateKey, boolean useMd5Fallback, String dbType) {
        String orderByClause = "";
        
        if (useMd5Fallback) {
            List<String> colsToHash = (sortColumns != null && !sortColumns.isEmpty()) ? sortColumns : pks;
            String concatCols = colsToHash.stream().map(c -> {
                String q = quoteIdentifier(c, dbType);
                if ("postgresql".equals(dbType)) return "COALESCE(" + q + "::text, '')";
                else return "IFNULL(" + q + ", '')";
            }).collect(java.util.stream.Collectors.joining(", '|', "));
            orderByClause = "MD5(CONCAT_WS('|', " + concatCols + "))";
        } else if (sortColumns != null && !sortColumns.isEmpty()) {
            // Build user sort columns
            String userSort = sortColumns.stream().map(c -> {
                String q = quoteIdentifier(c, dbType);
                return "postgresql".equals(dbType) ? q + " ASC NULLS FIRST" : q + " ASC";
            }).collect(java.util.stream.Collectors.joining(", "));
            
            // Append PK as final tiebreaker (only PKs not already in sortColumns)
            Set<String> sortColsLower = sortColumns.stream()
                .map(String::toLowerCase).collect(java.util.stream.Collectors.toSet());
            String pkTiebreaker = (pks != null) ? pks.stream()
                .filter(pk -> !sortColsLower.contains(pk.toLowerCase()))
                .map(pk -> {
                    String q2 = quoteIdentifier(pk, dbType);
                    return "postgresql".equals(dbType) ? q2 + " ASC NULLS FIRST" : q2 + " ASC";
                })
                .collect(java.util.stream.Collectors.joining(", ")) : "";
            
            orderByClause = pkTiebreaker.isEmpty() ? userSort : userSort + ", " + pkTiebreaker;
        } else if (pks != null && !pks.isEmpty()) {
            orderByClause = pks.stream().map(c -> {
                String q = quoteIdentifier(c, dbType);
                if ("postgresql".equals(dbType)) {
                    return q + " ASC NULLS FIRST";
                } else {
                    return q + " ASC";
                }
            }).collect(java.util.stream.Collectors.joining(", "));
        }

        boolean hasOrderBy = !orderByClause.isEmpty();

        if (customQuery != null && !customQuery.trim().isEmpty()) {
            String q = customQuery.trim();
            String upperQ = q.toUpperCase();
            if (upperQ.contains("INSERT ") || upperQ.contains("UPDATE ") || upperQ.contains("DELETE ") ||
                upperQ.contains("DROP ") || upperQ.contains("ALTER ") || upperQ.contains("TRUNCATE ") ||
                upperQ.contains("EXEC ") || upperQ.contains("EXECUTE ")) {
                throw new IllegalArgumentException("Custom query must be a SELECT statement. DML/DDL operations are not allowed.");
            }
            while (q.endsWith(";")) {
                q = q.substring(0, q.length() - 1).trim();
            }
            if (useSurrogateKey) {
                String window = hasOrderBy ? " ORDER BY " + orderByClause : "";
                q = "SELECT ROW_NUMBER() OVER (" + window + ") as __rn__, tmp.* FROM (" + q + ") as tmp ORDER BY __rn__";
            } else if (useMd5Fallback) {
                q = "SELECT tmp.* FROM (" + q + ") as tmp ORDER BY " + orderByClause;
            } else if (q.toUpperCase().contains("ORDER BY") && hasOrderBy) {
                // Custom query already has ORDER BY — wrap it and enforce PK as final tiebreaker
                // This guarantees deterministic ordering for LIMIT/OFFSET pagination
                q = "SELECT * FROM (" + q + ") AS __deterministic_order ORDER BY " + orderByClause;
            } else if (!q.toUpperCase().contains("ORDER BY") && hasOrderBy) {
                q = q + " ORDER BY " + orderByClause;
            }
            return q;
        }

        String safeTable = formatTableName(tableName);
        if ("clickhouse".equals(dbType)) {
            safeTable = safeTable + " FINAL";
        }

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
            if (i > 0) sb.append('\u0000');
            Object val = row.get(exactPks.get(i));
            sb.append(val == null ? "\u0001NULL\u0001" : val.toString());
        }
        return sb.toString();
    }

    private Object getSafeObject(ResultSet rs, int colIdx) throws Exception {
        Object val = rs.getObject(colIdx);
        if (val == null) return null;
        if (val instanceof java.sql.Blob) {
            java.sql.Blob b = (java.sql.Blob) val;
            return "[BLOB Data: " + b.length() + " bytes]";
        } else if (val instanceof java.sql.Clob) {
            java.sql.Clob c = (java.sql.Clob) val;
            return "[CLOB Data: " + c.length() + " chars]";
        } else if (val instanceof byte[]) {
            return "[BINARY Data: " + ((byte[]) val).length + " bytes]";
        } else if (val instanceof java.sql.Timestamp) {
            return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format((java.util.Date) val);
        } else if (val instanceof java.sql.Date) {
            return new java.text.SimpleDateFormat("yyyy-MM-dd").format((java.util.Date) val);
        } else if (val instanceof java.sql.Time) {
            return new java.text.SimpleDateFormat("HH:mm:ss").format((java.util.Date) val);
        } else if (val instanceof java.util.Date) {
            return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format((java.util.Date) val);
        } else if (val instanceof java.time.LocalDateTime) {
            return java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").format((java.time.LocalDateTime) val);
        } else if (val instanceof java.time.LocalDate) {
            return java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd").format((java.time.LocalDate) val);
        } else if (val instanceof java.time.LocalTime) {
            return java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss").format((java.time.LocalTime) val);
        }
        return val;
    }

    private Object[] getRow(ResultSet rs, int size, int[] colIdx) throws Exception {
        Object[] row = new Object[size];
        for (int i = 0; i < size; i++) {
            if (colIdx[i] > 0) {
                try { row[i] = getSafeObject(rs, colIdx[i]); } catch (Exception e) {}
            } else {
                row[i] = null;
            }
        }
        return row;
    }

    @SuppressWarnings("unchecked")
    private int compareKeys(ResultSet rsS, ResultSet rsT, int[] sPkIdx, int[] tPkIdx) throws Exception {
        for (int i = 0; i < sPkIdx.length; i++) {
            Object sObj = sPkIdx[i] > 0 ? getSafeObject(rsS, sPkIdx[i]) : null;
            Object tObj = tPkIdx[i] > 0 ? getSafeObject(rsT, tPkIdx[i]) : null;
            
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

    private long tryParseDateString(String s) {
        for (java.time.format.DateTimeFormatter fmt : DT_FORMATTERS) {
            try {
                return java.time.LocalDateTime.parse(s, fmt).atZone(java.time.ZoneId.of("UTC")).toInstant().toEpochMilli();
            } catch (Exception ignored) {}
        }
        return Long.MIN_VALUE;
    }

    private void reconcile(String key, DiffRow sourceOnly, DiffRow targetOnly, List<String> columns, DiffRequest request, DiffRowConsumer consumer, int[] differences) throws Exception {
        boolean allEqual = true;
        for (String col : columns) {
            Object sVal = sourceOnly.getCells().get(col).getSourceValue();
            Object tVal = targetOnly.getCells().get(col).getTargetValue();
            if (!normalizedEquals(sVal, tVal)) {
                allEqual = false;
                break;
            }
        }
        if (allEqual) {
            if (request.isReturnMatchedRows()) {
                Object[] values = new Object[columns.size()];
                for (int i = 0; i < columns.size(); i++) {
                    values[i] = sourceOnly.getCells().get(columns.get(i)).getSourceValue();
                }
                consumer.onMatchRow(key, values, columns);
            }
        } else {
            DiffRow diffRow = new DiffRow();
            diffRow.setRowKey(key);
            Map<String, DiffCell> cells = new LinkedHashMap<>(columns.size());
            for (String col : columns) {
                Object sVal = sourceOnly.getCells().get(col).getSourceValue();
                Object tVal = targetOnly.getCells().get(col).getTargetValue();
                cells.put(col, new DiffCell(sVal, tVal, !normalizedEquals(sVal, tVal)));
            }
            diffRow.setStatus(DiffRow.Status.DIFFERENT);
            diffRow.setCells(cells);
            differences[0]++;
            consumer.onRow(diffRow);
        }
    }

    private void sanitizeCustomQueries(DiffRequest request) {
        if (request == null) return;
        if (request.getCustomQuerySource() != null) {
            String q = request.getCustomQuerySource().trim();
            while (q.endsWith(";")) {
                q = q.substring(0, q.length() - 1).trim();
            }
            request.setCustomQuerySource(q);
        }
        if (request.getCustomQueryTarget() != null) {
            String q = request.getCustomQueryTarget().trim();
            while (q.endsWith(";")) {
                q = q.substring(0, q.length() - 1).trim();
            }
            request.setCustomQueryTarget(q);
        }
    }
}
