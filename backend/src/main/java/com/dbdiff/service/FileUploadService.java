package com.dbdiff.service;

import org.apache.poi.ss.usermodel.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;

@Service
public class FileUploadService {

    private static final Logger logger = LoggerFactory.getLogger(FileUploadService.class);

    private final JdbcTemplate jdbcTemplate;

    @Autowired
    public FileUploadService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void initSchema() {
        try {
            jdbcTemplate.execute("CREATE SCHEMA IF NOT EXISTS sch_excel;");
            jdbcTemplate.execute(
                "CREATE TABLE IF NOT EXISTS sch_excel._file_uploads (" +
                "  table_name VARCHAR(255) PRIMARY KEY," +
                "  original_filename VARCHAR(255)," +
                "  file_type VARCHAR(50)," +
                "  description TEXT," +
                "  row_count INT DEFAULT 0," +
                "  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                ");"
            );
            logger.info("Schema 'sch_excel' and metadata table initialized successfully.");
        } catch (Exception e) {
            logger.error("Failed to initialize sch_excel schema or metadata table: {}", e.getMessage());
        }
    }

    public Map<String, Object> uploadFileToSchExcel(MultipartFile file, String customTableName, String description) throws Exception {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("File upload tidak boleh kosong");
        }

        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null) {
            originalFilename = "uploaded_file";
        }

        String lowerExt = originalFilename.toLowerCase();
        boolean isExcel = lowerExt.endsWith(".xlsx") || lowerExt.endsWith(".xls");
        boolean isCsv = lowerExt.endsWith(".csv");

        if (!isExcel && !isCsv) {
            throw new IllegalArgumentException("Format file tidak didukung. Harap upload file Excel (.xlsx, .xls) atau CSV (.csv)");
        }

        initSchema();

        // Generate sanitized table name
        String targetTableName = generateSanitizedTableName(originalFilename, customTableName);
        
        // Generate description if empty
        if (description == null || description.trim().isEmpty()) {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            description = "Uploaded from " + originalFilename + " on " + sdf.format(new Date());
        } else {
            description = description.trim();
        }

        List<String> columns;
        List<Object[]> rows;

        if (isExcel) {
            ParsedData parsed = parseExcel(file);
            columns = parsed.columns;
            rows = parsed.rows;
        } else {
            ParsedData parsed = parseCsv(file);
            columns = parsed.columns;
            rows = parsed.rows;
        }

        if (columns.isEmpty()) {
            throw new Exception("Tidak dapat membaca kolom dari file header");
        }

        // Create table in sch_excel schema
        jdbcTemplate.execute("DROP TABLE IF EXISTS sch_excel.\"" + targetTableName + "\";");

        StringBuilder createSql = new StringBuilder("CREATE TABLE sch_excel.\"");
        createSql.append(targetTableName).append("\" (");
        for (int i = 0; i < columns.size(); i++) {
            createSql.append("\"").append(columns.get(i)).append("\" TEXT");
            if (i < columns.size() - 1) createSql.append(", ");
        }
        createSql.append(");");
        jdbcTemplate.execute(createSql.toString());

        // Insert rows in batches
        if (!rows.isEmpty()) {
            StringBuilder insertSql = new StringBuilder("INSERT INTO sch_excel.\"");
            insertSql.append(targetTableName).append("\" (");
            for (int i = 0; i < columns.size(); i++) {
                insertSql.append("\"").append(columns.get(i)).append("\"");
                if (i < columns.size() - 1) insertSql.append(", ");
            }
            insertSql.append(") VALUES (");
            for (int i = 0; i < columns.size(); i++) {
                insertSql.append("?");
                if (i < columns.size() - 1) insertSql.append(", ");
            }
            insertSql.append(");");

            String sqlStr = insertSql.toString();
            List<Object[]> batch = new ArrayList<>();
            for (Object[] row : rows) {
                batch.add(row);
                if (batch.size() >= 1000) {
                    jdbcTemplate.batchUpdate(sqlStr, batch);
                    batch.clear();
                }
            }
            if (!batch.isEmpty()) {
                jdbcTemplate.batchUpdate(sqlStr, batch);
            }
        }

        // Upsert metadata record
        String upsertMetaSql = 
            "INSERT INTO sch_excel._file_uploads (table_name, original_filename, file_type, description, row_count, created_at) " +
            "VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP) " +
            "ON CONFLICT (table_name) DO UPDATE SET " +
            "  original_filename = EXCLUDED.original_filename, " +
            "  file_type = EXCLUDED.file_type, " +
            "  description = EXCLUDED.description, " +
            "  row_count = EXCLUDED.row_count, " +
            "  created_at = CURRENT_TIMESTAMP;";

        jdbcTemplate.update(upsertMetaSql, targetTableName, originalFilename, isExcel ? "excel" : "csv", description, rows.size());

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("tableName", targetTableName);
        result.put("originalFilename", originalFilename);
        result.put("fileType", isExcel ? "excel" : "csv");
        result.put("description", description);
        result.put("rowCount", rows.size());
        result.put("columnsCount", columns.size());

        return result;
    }

    public List<Map<String, Object>> getUploadedTables() {
        initSchema();
        String sql = 
            "SELECT table_name, original_filename, file_type, description, row_count, created_at " +
            "FROM sch_excel._file_uploads " +
            "ORDER BY created_at DESC;";
        try {
            return jdbcTemplate.queryForList(sql);
        } catch (Exception e) {
            logger.error("Error fetching sch_excel tables: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    public Map<String, Object> getTablePreview(String tableName, int page, int pageSize) {
        initSchema();
        String sanitized = sanitizeIdentifier(tableName);
        
        if (pageSize <= 0) pageSize = 50;
        if (pageSize > 500) pageSize = 500;
        if (page <= 0) page = 1;
        int offset = (page - 1) * pageSize;

        String countSql = "SELECT COUNT(*) FROM sch_excel.\"" + sanitized + "\";";
        int totalRows = 0;
        try {
            Integer c = jdbcTemplate.queryForObject(countSql, Integer.class);
            if (c != null) totalRows = c;
        } catch (Exception e) {
            logger.warn("Could not get row count for table {}: {}", sanitized, e.getMessage());
        }

        String dataSql = "SELECT * FROM sch_excel.\"" + sanitized + "\" LIMIT " + pageSize + " OFFSET " + offset + ";";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(dataSql);

        List<String> columns = new ArrayList<>();
        if (!rows.isEmpty()) {
            columns.addAll(rows.get(0).keySet());
        } else {
            // Fetch column names from information_schema if table is empty
            String colMetaSql = "SELECT column_name FROM information_schema.columns WHERE table_schema = 'sch_excel' AND table_name = ? ORDER BY ordinal_position;";
            columns = jdbcTemplate.queryForList(colMetaSql, String.class, sanitized);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("tableName", sanitized);
        result.put("columns", columns);
        result.put("rows", rows);
        result.put("totalRows", totalRows);
        result.put("page", page);
        result.put("pageSize", pageSize);
        result.put("totalPages", (int) Math.ceil((double) totalRows / pageSize));

        return result;
    }

    public void deleteUploadedTable(String tableName) {
        initSchema();
        String sanitized = sanitizeIdentifier(tableName);
        if ("_file_uploads".equalsIgnoreCase(sanitized)) {
            throw new IllegalArgumentException("Tidak dapat menghapus tabel metadata internal");
        }

        jdbcTemplate.execute("DROP TABLE IF EXISTS sch_excel.\"" + sanitized + "\";");
        jdbcTemplate.update("DELETE FROM sch_excel._file_uploads WHERE table_name = ?;", sanitized);
        logger.info("Deleted table sch_excel.\"{}\" and its metadata.", sanitized);
    }

    public Map<String, Object> updateUploadedTable(String oldTableName, String newTableName, String description) {
        initSchema();
        String oldSanitized = sanitizeIdentifier(oldTableName);
        if ("_file_uploads".equalsIgnoreCase(oldSanitized)) {
            throw new IllegalArgumentException("Tidak dapat mengubah tabel metadata internal");
        }

        String targetNewName = (newTableName != null && !newTableName.trim().isEmpty()) 
                ? sanitizeIdentifier(newTableName) 
                : oldSanitized;

        if ("_file_uploads".equalsIgnoreCase(targetNewName)) {
            throw new IllegalArgumentException("Nama tabel tidak boleh menggunakan nama metadata internal");
        }

        String desc = description != null ? description.trim() : "";

        if (!targetNewName.equalsIgnoreCase(oldSanitized)) {
            Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sch_excel._file_uploads WHERE table_name = ?;",
                Integer.class,
                targetNewName
            );
            if (count != null && count > 0) {
                throw new IllegalArgumentException("Tabel dengan nama '" + targetNewName + "' sudah ada di schema sch_excel");
            }

            jdbcTemplate.execute("ALTER TABLE sch_excel.\"" + oldSanitized + "\" RENAME TO \"" + targetNewName + "\";");

            jdbcTemplate.update(
                "UPDATE sch_excel._file_uploads SET table_name = ?, description = ? WHERE table_name = ?;",
                targetNewName, desc, oldSanitized
            );
            logger.info("Renamed table sch_excel.\"{}\" to sch_excel.\"{}\" and updated description.", oldSanitized, targetNewName);
        } else {
            jdbcTemplate.update(
                "UPDATE sch_excel._file_uploads SET description = ? WHERE table_name = ?;",
                desc, oldSanitized
            );
            logger.info("Updated description for table sch_excel.\"{}\"", oldSanitized);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("tableName", targetNewName);
        result.put("description", desc);
        return result;
    }

    private static class ParsedData {
        List<String> columns;
        List<Object[]> rows;

        ParsedData(List<String> columns, List<Object[]> rows) {
            this.columns = columns;
            this.rows = rows;
        }
    }

    private ParsedData parseExcel(MultipartFile file) throws Exception {
        DataFormatter formatter = new DataFormatter();
        List<String> rawHeaders = new ArrayList<>();
        List<Object[]> rows = new ArrayList<>();

        try (InputStream is = file.getInputStream();
             Workbook workbook = WorkbookFactory.create(is)) {

            Sheet sheet = workbook.getSheetAt(0);
            Iterator<Row> rowIterator = sheet.iterator();
            if (!rowIterator.hasNext()) {
                throw new Exception("File Excel kosong");
            }

            // Header row
            Row headerRow = rowIterator.next();
            int maxCellNum = headerRow.getLastCellNum();
            for (int i = 0; i < maxCellNum; i++) {
                Cell cell = headerRow.getCell(i, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
                rawHeaders.add(formatter.formatCellValue(cell));
            }

            List<String> columns = sanitizeColumns(rawHeaders);

            while (rowIterator.hasNext()) {
                Row row = rowIterator.next();
                Object[] args = new Object[columns.size()];
                boolean hasVal = false;
                for (int i = 0; i < columns.size(); i++) {
                    Cell cell = row.getCell(i, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
                    String val = getCellValueAsString(cell, formatter);
                    if (val != null && !val.trim().isEmpty()) {
                        hasVal = true;
                    }
                    args[i] = val != null ? val : "";
                }
                if (hasVal) {
                    rows.add(args);
                }
            }

            return new ParsedData(columns, rows);
        }
    }

    private ParsedData parseCsv(MultipartFile file) throws Exception {
        List<List<String>> allRows = new ArrayList<>();
        try (InputStream is = file.getInputStream();
             BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {

            String line;
            char delimiter = ',';
            boolean delimiterSet = false;

            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                if (!delimiterSet) {
                    int commas = countOccurrences(line, ',');
                    int semicolons = countOccurrences(line, ';');
                    int tabs = countOccurrences(line, '\t');
                    if (semicolons > commas && semicolons > tabs) delimiter = ';';
                    else if (tabs > commas && tabs > semicolons) delimiter = '\t';
                    delimiterSet = true;
                }
                allRows.add(parseCsvLine(line, delimiter));
            }
        }

        if (allRows.isEmpty()) {
            throw new Exception("File CSV kosong");
        }

        List<String> rawHeaders = allRows.get(0);
        List<String> columns = sanitizeColumns(rawHeaders);

        List<Object[]> rows = new ArrayList<>();
        for (int r = 1; r < allRows.size(); r++) {
            List<String> lineData = allRows.get(r);
            Object[] args = new Object[columns.size()];
            boolean hasVal = false;
            for (int c = 0; c < columns.size(); c++) {
                String val = c < lineData.size() ? lineData.get(c) : "";
                if (val != null && !val.trim().isEmpty()) {
                    hasVal = true;
                }
                args[c] = val != null ? val : "";
            }
            if (hasVal) {
                rows.add(args);
            }
        }

        return new ParsedData(columns, rows);
    }

    private String getCellValueAsString(Cell cell, DataFormatter formatter) {
        if (cell == null) return "";
        if (cell.getCellType() == CellType.NUMERIC) {
            if (DateUtil.isCellDateFormatted(cell)) {
                return formatter.formatCellValue(cell);
            } else {
                double d = cell.getNumericCellValue();
                if (d == (long) d) {
                    return String.valueOf((long) d);
                } else {
                    return java.math.BigDecimal.valueOf(d).toPlainString();
                }
            }
        } else if (cell.getCellType() == CellType.BOOLEAN) {
            return String.valueOf(cell.getBooleanCellValue()).toLowerCase();
        }
        return formatter.formatCellValue(cell);
    }

    private List<String> parseCsvLine(String line, char delimiter) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == delimiter && !inQuotes) {
                result.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        result.add(current.toString().trim());
        return result;
    }

    private int countOccurrences(String text, char c) {
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == c) count++;
        }
        return count;
    }

    private List<String> sanitizeColumns(List<String> rawHeaders) {
        List<String> cols = new ArrayList<>();
        Set<String> usedNames = new HashSet<>();

        for (int i = 0; i < rawHeaders.size(); i++) {
            String raw = rawHeaders.get(i);
            if (raw == null || raw.trim().isEmpty()) {
                raw = "column_" + (i + 1);
            }
            String colName = raw.replaceAll("[^a-zA-Z0-9_]", "_").toLowerCase();
            colName = colName.replaceAll("_+", "_");
            if (colName.matches("^[0-9].*")) {
                colName = "col_" + colName;
            }
            if (colName.isEmpty() || colName.equals("_")) {
                colName = "column_" + (i + 1);
            }
            String baseName = colName;
            int count = 1;
            while (usedNames.contains(colName)) {
                colName = baseName + "_" + count++;
            }
            usedNames.add(colName);
            cols.add(colName);
        }
        return cols;
    }

    private String generateSanitizedTableName(String originalFilename, String customTableName) {
        String baseName;
        if (customTableName != null && !customTableName.trim().isEmpty()) {
            baseName = customTableName.trim();
        } else {
            String nameWithoutExt = originalFilename;
            int dotIdx = originalFilename.lastIndexOf('.');
            if (dotIdx > 0) {
                nameWithoutExt = originalFilename.substring(0, dotIdx);
            }
            SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd");
            baseName = nameWithoutExt + "_" + sdf.format(new Date());
        }

        return sanitizeIdentifier(baseName);
    }

    private String sanitizeIdentifier(String name) {
        if (name == null || name.trim().isEmpty()) {
            name = "file_table_" + System.currentTimeMillis();
        }
        String sanitized = name.trim().replaceAll("[^a-zA-Z0-9_]", "_").toLowerCase();
        sanitized = sanitized.replaceAll("_+", "_");
        if (sanitized.matches("^[0-9].*")) {
            sanitized = "tbl_" + sanitized;
        }
        if (sanitized.isEmpty() || sanitized.equals("_")) {
            sanitized = "tbl_" + System.currentTimeMillis();
        }
        return sanitized;
    }
}
