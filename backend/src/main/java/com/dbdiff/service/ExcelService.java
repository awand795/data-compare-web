package com.dbdiff.service;

import com.dbdiff.model.ConnectionDetails;
import org.apache.poi.ss.usermodel.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.sql.DataSource;
import java.io.InputStream;
import java.util.*;

@Service
public class ExcelService {

    private static final Logger logger = LoggerFactory.getLogger(ExcelService.class);

    private final ConnectionManagerService connectionManagerService;

    @Autowired
    public ExcelService(ConnectionManagerService connectionManagerService) {
        this.connectionManagerService = connectionManagerService;
    }

    /**
     * Parses an uploaded Excel file, creates a table in the provided database connection,
     * and inserts the Excel data into that table. All columns will be created as TEXT/VARCHAR.
     * 
     * @return The name of the generated table.
     */
    public String importExcelToDatabase(MultipartFile file, ConnectionDetails dbConnection) throws Exception {
        if (file.getSize() > 10 * 1024 * 1024) {
            throw new Exception("File terlalu besar. Maksimal 10MB untuk mencegah kehabisan memori.");
        }
        DataSource ds = connectionManagerService.getDataSource(dbConnection);
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        
        String tableName = "excel_import_" + UUID.randomUUID().toString().replace("-", "");
        
        try (InputStream is = file.getInputStream();
             Workbook workbook = WorkbookFactory.create(is)) {
             
            Sheet sheet = workbook.getSheetAt(0);
            Iterator<Row> rowIterator = sheet.iterator();
            
            if (!rowIterator.hasNext()) {
                throw new Exception("Excel file is empty");
            }
            
            // Read Header
            Row headerRow = rowIterator.next();
            List<String> columns = new ArrayList<>();
            for (Cell cell : headerRow) {
                String colName = cell.getStringCellValue();
                if (colName == null || colName.trim().isEmpty()) {
                    colName = "Column" + cell.getColumnIndex();
                }
                // Sanitize column name
                colName = colName.replaceAll("[^a-zA-Z0-9_]", "_");
                columns.add(colName);
            }
            
            // Create Table
            StringBuilder createSql = new StringBuilder("CREATE TABLE ");
            createSql.append(tableName).append(" (");
            for (int i = 0; i < columns.size(); i++) {
                createSql.append("\"").append(columns.get(i)).append("\" TEXT");
                if (i < columns.size() - 1) createSql.append(", ");
            }
            createSql.append(")");
            
            jdbc.execute(createSql.toString());
            boolean tableCreated = true;
            try {
                // Insert Data in batches
                String insertSql = buildInsertSql(tableName, columns);
                List<Object[]> batchArgs = new ArrayList<>();
                DataFormatter dataFormatter = new DataFormatter();
                
                while (rowIterator.hasNext()) {
                    Row row = rowIterator.next();
                    Object[] args = new Object[columns.size()];
                    for (int i = 0; i < columns.size(); i++) {
                        Cell cell = row.getCell(i, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
                        String val;
                        if (cell.getCellType() == CellType.NUMERIC) {
                            if (DateUtil.isCellDateFormatted(cell)) {
                                val = dataFormatter.formatCellValue(cell);
                            } else {
                                double d = cell.getNumericCellValue();
                                if (d == (long) d) {
                                    val = String.valueOf((long) d);
                                } else {
                                    val = java.math.BigDecimal.valueOf(d).toPlainString();
                                }
                            }
                        } else if (cell.getCellType() == CellType.BOOLEAN) {
                            val = String.valueOf(cell.getBooleanCellValue()).toLowerCase();
                        } else {
                            val = dataFormatter.formatCellValue(cell);
                        }
                        args[i] = val;
                    }
                    batchArgs.add(args);
                    if (batchArgs.size() >= 1000) {
                        jdbc.batchUpdate(insertSql, batchArgs);
                        batchArgs.clear();
                    }
                }
                if (!batchArgs.isEmpty()) {
                    jdbc.batchUpdate(insertSql, batchArgs);
                }
            } catch (Exception e) {
                try {
                    if (tableName != null && tableName.startsWith("excel_import_")) {
                        jdbc.execute("DROP TABLE IF EXISTS " + tableName);
                    }
                    logger.info("Cleaned up orphan excel table {} after import failure: {}", tableName, e.getMessage());
                } catch (Exception dropEx) {
                    logger.warn("Failed to cleanup orphan excel table {}: {}", tableName, dropEx.getMessage());
                }
                throw e;
            }
            
            return tableName;
        }
    }
    
    public void dropExcelTable(ConnectionDetails dbConnection, String tableName) {
        // Safety guard: hanya izinkan drop tabel dengan prefix excel_import_
        // Mencegah manipulasi nama tabel yang bisa menyebabkan drop tabel lain
        if (tableName == null || !tableName.startsWith("excel_import_")) {
            logger.warn("Refused to drop table '{}' — name does not start with excel_import_ prefix", tableName);
            return;
        }
        try {
            DataSource ds = connectionManagerService.getDataSource(dbConnection);
            JdbcTemplate jdbc = new JdbcTemplate(ds);
            jdbc.execute("DROP TABLE IF EXISTS " + tableName);
        } catch (Exception e) {
            logger.error("Failed to drop temporary excel table {}: {}", tableName, e.getMessage());
        }
    }

    private String buildInsertSql(String tableName, List<String> columns) {
        StringBuilder sql = new StringBuilder("INSERT INTO ");
        sql.append(tableName).append(" (");
        for (int i = 0; i < columns.size(); i++) {
            sql.append("\"").append(columns.get(i)).append("\"");
            if (i < columns.size() - 1) sql.append(", ");
        }
        sql.append(") VALUES (");
        for (int i = 0; i < columns.size(); i++) {
            sql.append("?");
            if (i < columns.size() - 1) sql.append(", ");
        }
        sql.append(")");
        return sql.toString();
    }
}
