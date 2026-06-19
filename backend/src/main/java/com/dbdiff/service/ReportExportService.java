package com.dbdiff.service;

import com.dbdiff.model.DiffCell;
import com.dbdiff.model.DiffRequest;
import com.dbdiff.model.DiffRow;
import com.lowagie.text.*;
import com.lowagie.text.Font;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFRichTextString;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;

@Service
public class ReportExportService {

    @Autowired
    private DataComparisonService comparisonService;

    public void exportExcel(DiffRequest request, String filterStatus, OutputStream out) throws Exception {
        try (SXSSFWorkbook wb = new SXSSFWorkbook(100)) { // keep 100 rows in memory, exceeding rows will be flushed to disk
            
            CellStyle headerStyle = wb.createCellStyle();
            headerStyle.setFillForegroundColor(IndexedColors.BLUE.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            org.apache.poi.ss.usermodel.Font headerFont = wb.createFont();
            headerFont.setColor(IndexedColors.WHITE.getIndex());
            headerFont.setBold(true);
            headerFont.setFontHeightInPoints((short) 10);
            headerStyle.setFont(headerFont);
            headerStyle.setBorderTop(BorderStyle.THIN);
            headerStyle.setBorderBottom(BorderStyle.MEDIUM);
            headerStyle.setBorderLeft(BorderStyle.THIN);
            headerStyle.setBorderRight(BorderStyle.THIN);
            headerStyle.setVerticalAlignment(VerticalAlignment.CENTER);

            // Common cell style with borders
            CellStyle baseStyle = wb.createCellStyle();
            baseStyle.setBorderTop(BorderStyle.THIN);
            baseStyle.setBorderBottom(BorderStyle.THIN);
            baseStyle.setBorderLeft(BorderStyle.THIN);
            baseStyle.setBorderRight(BorderStyle.THIN);
            baseStyle.setVerticalAlignment(VerticalAlignment.CENTER);

            CellStyle matchStyle = wb.createCellStyle();
            matchStyle.cloneStyleFrom(baseStyle);
            
            CellStyle diffStyle = wb.createCellStyle();
            diffStyle.cloneStyleFrom(baseStyle);
            diffStyle.setFillForegroundColor(IndexedColors.LIGHT_YELLOW.getIndex());
            diffStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            CellStyle sourceOnlyStyle = wb.createCellStyle();
            sourceOnlyStyle.cloneStyleFrom(baseStyle);
            sourceOnlyStyle.setFillForegroundColor(IndexedColors.ROSE.getIndex());
            sourceOnlyStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            CellStyle targetOnlyStyle = wb.createCellStyle();
            targetOnlyStyle.cloneStyleFrom(baseStyle);
            targetOnlyStyle.setFillForegroundColor(IndexedColors.LIGHT_GREEN.getIndex());
            targetOnlyStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            org.apache.poi.ss.usermodel.Font redFont = wb.createFont();
            redFont.setColor(IndexedColors.RED.getIndex());
            redFont.setBold(true);
            redFont.setFontHeightInPoints((short) 9);

            org.apache.poi.ss.usermodel.Font greenFont = wb.createFont();
            greenFont.setColor(IndexedColors.GREEN.getIndex());
            greenFont.setBold(true);
            greenFont.setFontHeightInPoints((short) 9);
            
            org.apache.poi.ss.usermodel.Font normalFont = wb.createFont();
            normalFont.setFontHeightInPoints((short) 9);

            // Summary styling
            CellStyle titleStyle = wb.createCellStyle();
            titleStyle.cloneStyleFrom(headerStyle);
            
            CellStyle labelStyle = wb.createCellStyle();
            labelStyle.cloneStyleFrom(baseStyle);
            labelStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            labelStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            org.apache.poi.ss.usermodel.Font labelFont = wb.createFont();
            labelFont.setBold(true);
            labelFont.setFontHeightInPoints((short) 10);
            labelStyle.setFont(labelFont);

            CellStyle valueStyle = wb.createCellStyle();
            valueStyle.cloneStyleFrom(baseStyle);
            org.apache.poi.ss.usermodel.Font valueFont = wb.createFont();
            valueFont.setFontHeightInPoints((short) 10);
            valueStyle.setFont(valueFont);

            Sheet summarySheet = wb.createSheet("Summary");
            Sheet allSheet = wb.createSheet("All Rows");
            Sheet diffSheet = wb.createSheet("Different");
            Sheet sourceSheet = wb.createSheet("Source Only");
            Sheet targetSheet = wb.createSheet("Target Only");

            Sheet[] sheets = {allSheet, diffSheet, sourceSheet, targetSheet};

            comparisonService.processStream(request, new DiffRowConsumer() {
                private int[] rowCounts = new int[4]; // all, diff, source, target
                private List<String> columns;

                @Override
                public void onColumns(List<String> cols) throws Exception {
                    this.columns = cols;
                    // Create headers for all data sheets
                    for (Sheet sheet : sheets) {
                        org.apache.poi.ss.usermodel.Row headerRow = sheet.createRow(0);
                        org.apache.poi.ss.usermodel.Cell statusCell = headerRow.createCell(0);
                        statusCell.setCellValue("Status");
                        statusCell.setCellStyle(headerStyle);
                        
                        org.apache.poi.ss.usermodel.Cell keyCell = headerRow.createCell(1);
                        keyCell.setCellValue("RowKey");
                        keyCell.setCellStyle(headerStyle);

                        for (int i = 0; i < columns.size(); i++) {
                            org.apache.poi.ss.usermodel.Cell c = headerRow.createCell(i + 2);
                            c.setCellValue(columns.get(i));
                            c.setCellStyle(headerStyle);
                        }
                        
                        // Freeze header row
                        sheet.createFreezePane(0, 1);
                        // Set column widths
                        sheet.setColumnWidth(0, 12 * 256); // Status
                        sheet.setColumnWidth(1, 20 * 256); // RowKey
                        for (int i = 0; i < columns.size(); i++) {
                            int colWidth = Math.min(40, Math.max(12, columns.get(i).length() + 2));
                            sheet.setColumnWidth(i + 2, colWidth * 256);
                        }
                    }
                }

                @Override
                public void onRow(DiffRow row) throws Exception {
                    // Write to All Rows
                    writeRowToSheet(allSheet, rowCounts[0]++, row, matchStyle, diffStyle, sourceOnlyStyle, targetOnlyStyle, redFont, greenFont, normalFont);
                    
                    if (row.getStatus() == DiffRow.Status.DIFFERENT) {
                        writeRowToSheet(diffSheet, rowCounts[1]++, row, matchStyle, diffStyle, sourceOnlyStyle, targetOnlyStyle, redFont, greenFont, normalFont);
                    } else if (row.getStatus() == DiffRow.Status.SOURCE_ONLY) {
                        writeRowToSheet(sourceSheet, rowCounts[2]++, row, matchStyle, diffStyle, sourceOnlyStyle, targetOnlyStyle, redFont, greenFont, normalFont);
                    } else if (row.getStatus() == DiffRow.Status.TARGET_ONLY) {
                        writeRowToSheet(targetSheet, rowCounts[3]++, row, matchStyle, diffStyle, sourceOnlyStyle, targetOnlyStyle, redFont, greenFont, normalFont);
                    }
                }

                @Override
                public void onTotals(int totalSource, int totalTarget, int totalDiffs) throws Exception {
                    // Build summary sheet
                    org.apache.poi.ss.usermodel.Row r0 = summarySheet.createRow(0);
                    org.apache.poi.ss.usermodel.Cell titleCell = r0.createCell(0);
                    titleCell.setCellValue("Data Comparison Report");
                    titleCell.setCellStyle(titleStyle);
                    // Merge title across columns
                    for (int i = 1; i <= 4; i++) r0.createCell(i).setCellStyle(titleStyle);

                    org.apache.poi.ss.usermodel.Font boldDateFont = wb.createFont();
                    boldDateFont.setBold(true);
                    boldDateFont.setFontHeightInPoints((short) 10);
                    CellStyle dateStyle = wb.createCellStyle();
                    dateStyle.cloneStyleFrom(baseStyle);
                    dateStyle.setFont(boldDateFont);

                    int row = 2;
                    setSummaryRow(summarySheet, row++, "Generated", java.time.LocalDateTime.now().toString().replace("T", " "), labelStyle, dateStyle);
                    setSummaryRow(summarySheet, row++, "Filter Status", filterStatus != null ? filterStatus : "ALL", labelStyle, valueStyle);
                    setSummaryRow(summarySheet, row++, "Table Name", request.getTableName() != null ? request.getTableName() : "Custom Query", labelStyle, valueStyle);
                    setSummaryRow(summarySheet, row++, "", "", labelStyle, valueStyle);
                    setSummaryRow(summarySheet, row++, "Total Source Rows", String.valueOf(totalSource), labelStyle, valueStyle);
                    setSummaryRow(summarySheet, row++, "Total Target Rows", String.valueOf(totalTarget), labelStyle, valueStyle);
                    setSummaryRow(summarySheet, row++, "Total Differences", String.valueOf(totalDiffs), labelStyle, valueStyle);
                    
                    row++;
                    setSummaryRow(summarySheet, row++, "Sheet Breakdown", "", labelStyle, valueStyle);
                    setSummaryRow(summarySheet, row++, "  All Rows", String.valueOf(rowCounts[0]), labelStyle, valueStyle);
                    setSummaryRow(summarySheet, row++, "  Different", String.valueOf(rowCounts[1]), labelStyle, valueStyle);
                    setSummaryRow(summarySheet, row++, "  Source Only", String.valueOf(rowCounts[2]), labelStyle, valueStyle);
                    setSummaryRow(summarySheet, row++, "  Target Only", String.valueOf(rowCounts[3]), labelStyle, valueStyle);

                    // Auto-size summary columns
                    summarySheet.setColumnWidth(0, 22 * 256);
                    summarySheet.setColumnWidth(1, 30 * 256);
                }

                private void setSummaryRow(Sheet sheet, int rowIdx, String label, String value, CellStyle lblStyle, CellStyle valStyle) {
                    org.apache.poi.ss.usermodel.Row row = sheet.createRow(rowIdx);
                    org.apache.poi.ss.usermodel.Cell c0 = row.createCell(0);
                    c0.setCellValue(label);
                    c0.setCellStyle(lblStyle);
                    org.apache.poi.ss.usermodel.Cell c1 = row.createCell(1);
                    c1.setCellValue(value);
                    c1.setCellStyle(valStyle);
                }

                private void writeRowToSheet(Sheet sheet, int rowIndex, DiffRow row, CellStyle match, CellStyle diff, CellStyle source, CellStyle target, org.apache.poi.ss.usermodel.Font rf, org.apache.poi.ss.usermodel.Font gf, org.apache.poi.ss.usermodel.Font nf) {
                    org.apache.poi.ss.usermodel.Row r = sheet.createRow(rowIndex + 1); // +1 for header
                    CellStyle rowStyle = match;
                    if (row.getStatus() == DiffRow.Status.DIFFERENT) rowStyle = diff;
                    else if (row.getStatus() == DiffRow.Status.SOURCE_ONLY) rowStyle = source;
                    else if (row.getStatus() == DiffRow.Status.TARGET_ONLY) rowStyle = target;

                    org.apache.poi.ss.usermodel.Cell cStatus = r.createCell(0);
                    cStatus.setCellValue(row.getStatus().name());
                    cStatus.setCellStyle(rowStyle);

                    org.apache.poi.ss.usermodel.Cell cKey = r.createCell(1);
                    cKey.setCellValue(row.getRowKey());
                    cKey.setCellStyle(rowStyle);

                    int colIdx = 2;
                    for (String col : columns) {
                        DiffCell dc = row.getCells().get(col);
                        org.apache.poi.ss.usermodel.Cell c = r.createCell(colIdx++);
                        c.setCellStyle(rowStyle);
                        if (dc == null) {
                            c.setCellValue("");
                            continue;
                        }
                        if (dc.isDifferent()) {
                            String srcStr = "[SRC] " + (dc.getSourceValue() != null ? dc.getSourceValue() : "NULL");
                            String tgtStr = "[TGT] " + (dc.getTargetValue() != null ? dc.getTargetValue() : "NULL");
                            c.setCellValue(srcStr + "\n" + tgtStr);
                        } else {
                            c.setCellValue(String.valueOf(dc.getSourceValue()));
                        }
                    }
                }
            });

            wb.write(out);
        }
    }

    public void exportPdf(DiffRequest request, String filterStatus, OutputStream out) throws Exception {
        Document document = new Document(PageSize.A4.rotate());
        PdfWriter writer = PdfWriter.getInstance(document, out);
        document.open();

        try {
            // ── Header Section ──
            Font titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 18, new Color(30, 64, 175)); // dark blue
            Paragraph titlePara = new Paragraph("Data Comparison Report", titleFont);
            titlePara.setAlignment(Element.ALIGN_LEFT);
            document.add(titlePara);

            Font metaFont = FontFactory.getFont(FontFactory.HELVETICA, 10, new Color(100, 116, 139));
            Paragraph metaPara = new Paragraph();
            metaPara.add(new Chunk("Generated: ", FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10)));
            metaPara.add(new Chunk(java.time.LocalDateTime.now().toString().replace("T", " "), metaFont));
            metaPara.add(new Chunk("   |   Filter: ", FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10)));
            metaPara.add(new Chunk(filterStatus != null ? filterStatus : "ALL", metaFont));
            metaPara.add(new Chunk("   |   ", FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10)));
            String tableInfo = request.getTableName() != null ? request.getTableName() : "Custom Query";
            metaPara.add(new Chunk("Table: ", FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10)));
            metaPara.add(new Chunk(tableInfo, metaFont));
            document.add(metaPara);

            // Separator line
            Paragraph sep = new Paragraph();
            sep.add(new Chunk("_".repeat(120), FontFactory.getFont(FontFactory.HELVETICA, 6, new Color(200, 200, 200))));
            document.add(sep);
            document.add(new Paragraph(" "));

            Font headerFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8, Color.WHITE);
            Font cellFont = FontFactory.getFont(FontFactory.HELVETICA, 8);
            Font cellAltFont = FontFactory.getFont(FontFactory.HELVETICA, 7);
            Font redFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 7, Color.RED);
            Font greenFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 7, new Color(0, 150, 0));

            comparisonService.processStream(request, new DiffRowConsumer() {
                private PdfPTable table;
                private List<String> columns;
                private int printedRows = 0;
                private int rowNum = 0;

                @Override
                public void onColumns(List<String> cols) throws Exception {
                    this.columns = cols;
                    table = new PdfPTable(cols.size() + 2); // Status + Key + cols
                    table.setWidthPercentage(100);
                    
                    addHeader("Status", headerFont);
                    addHeader("RowKey", headerFont);
                    for (String c : cols) addHeader(c, headerFont);
                }

                private void addHeader(String text, Font f) {
                    PdfPCell cell = new PdfPCell(new Phrase(text, f));
                    cell.setBackgroundColor(new Color(59, 130, 246)); // Blue-500
                    cell.setPadding(5);
                    cell.setBorderWidthBottom(2f);
                    table.addCell(cell);
                }

                @Override
                public void onRow(DiffRow row) throws Exception {
                    boolean shouldPrint = "ALL".equalsIgnoreCase(filterStatus) || row.getStatus().name().equalsIgnoreCase(filterStatus);
                    if (!shouldPrint) return;

                    printedRows++;
                    rowNum++;

                    Color bgColor;
                    if (row.getStatus() == DiffRow.Status.MATCH) {
                        bgColor = rowNum % 2 == 0 ? Color.WHITE : new Color(248, 250, 252); // subtle alt rows
                    } else if (row.getStatus() == DiffRow.Status.DIFFERENT) {
                        bgColor = new Color(254, 252, 232); // yellow
                    } else if (row.getStatus() == DiffRow.Status.SOURCE_ONLY) {
                        bgColor = new Color(254, 242, 242); // red
                    } else {
                        bgColor = new Color(236, 253, 245); // green
                    }

                    Font currentFont = rowNum % 2 == 0 ? cellFont : cellAltFont;

                    PdfPCell statusCell = new PdfPCell(new Phrase(row.getStatus().name(), currentFont));
                    statusCell.setBackgroundColor(bgColor);
                    statusCell.setPadding(3);
                    table.addCell(statusCell);

                    PdfPCell keyCell = new PdfPCell(new Phrase(row.getRowKey(), currentFont));
                    keyCell.setBackgroundColor(bgColor);
                    keyCell.setPadding(3);
                    table.addCell(keyCell);

                    for (String col : columns) {
                        DiffCell dc = row.getCells().get(col);
                        PdfPCell c = new PdfPCell();
                        c.setBackgroundColor(bgColor);
                        c.setPadding(3);
                        if (dc == null) {
                            c.addElement(new Phrase("", currentFont));
                        } else if (dc.isDifferent()) {
                            Paragraph p = new Paragraph();
                            p.setLeading(8f);
                            p.add(new Chunk("[SRC] " + (dc.getSourceValue() != null ? dc.getSourceValue() : "NULL") + "\n", redFont));
                            p.add(new Chunk("[TGT] " + (dc.getTargetValue() != null ? dc.getTargetValue() : "NULL"), greenFont));
                            c.addElement(p);
                        } else {
                            c.addElement(new Phrase(String.valueOf(dc.getSourceValue()), currentFont));
                        }
                        table.addCell(c);
                    }

                    // Batch flush to document to prevent OutOfMemoryError on large datasets
                    if (printedRows % 100 == 0) {
                        document.add(table);
                        table = null; // let normal GC handle it
                        table = new PdfPTable(columns.size() + 2);
                        table.setWidthPercentage(100);
                        // Add headers for the new table batch
                        addHeader("Status", headerFont);
                        addHeader("RowKey", headerFont);
                        for (String c : columns) addHeader(c, headerFont);
                    }
                }

                @Override
                public void onTotals(int totalSource, int totalTarget, int totalDiffs) throws Exception {
                    document.add(new Paragraph(" "));
                    
                    // Summary box
                    PdfPTable summaryTable = new PdfPTable(2);
                    summaryTable.setWidthPercentage(60);
                    summaryTable.setHorizontalAlignment(Element.ALIGN_LEFT);

                    Font summaryLabelFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, new Color(55, 65, 81));
                    Font summaryValueFont = FontFactory.getFont(FontFactory.HELVETICA, 10);

                    addSummaryRow(summaryTable, "Total Source Rows", String.valueOf(totalSource), summaryLabelFont, summaryValueFont);
                    addSummaryRow(summaryTable, "Total Target Rows", String.valueOf(totalTarget), summaryLabelFont, summaryValueFont);
                    addSummaryRow(summaryTable, "Total Differences", String.valueOf(totalDiffs), summaryLabelFont, FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, new Color(220, 38, 38)));
                    addSummaryRow(summaryTable, "Rows in Report", String.valueOf(printedRows), summaryLabelFont, summaryValueFont);

                    document.add(summaryTable);
                    document.add(new Paragraph(" "));
                    if (table != null) {
                        document.add(table);
                    }
                }

                private void addSummaryRow(PdfPTable t, String label, String value, Font lblF, Font valF) {
                    PdfPCell lc = new PdfPCell(new Phrase(label, lblF));
                    lc.setBorder(Rectangle.NO_BORDER);
                    lc.setPadding(4);
                    PdfPCell vc = new PdfPCell(new Phrase(value, valF));
                    vc.setBorder(Rectangle.NO_BORDER);
                    vc.setPadding(4);
                    vc.setHorizontalAlignment(Element.ALIGN_RIGHT);
                    t.addCell(lc);
                    t.addCell(vc);
                }
            });
        } finally {
            if (document != null && document.isOpen()) {
                document.close();
            }
        }
    }
}
