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
            headerStyle.setFont(headerFont);

            CellStyle matchStyle = wb.createCellStyle();
            
            CellStyle diffStyle = wb.createCellStyle();
            diffStyle.setFillForegroundColor(IndexedColors.LIGHT_YELLOW.getIndex());
            diffStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            CellStyle sourceOnlyStyle = wb.createCellStyle();
            sourceOnlyStyle.setFillForegroundColor(IndexedColors.ROSE.getIndex());
            sourceOnlyStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            CellStyle targetOnlyStyle = wb.createCellStyle();
            targetOnlyStyle.setFillForegroundColor(IndexedColors.LIGHT_GREEN.getIndex());
            targetOnlyStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            org.apache.poi.ss.usermodel.Font redFont = wb.createFont();
            redFont.setColor(IndexedColors.RED.getIndex());
            redFont.setBold(true);

            org.apache.poi.ss.usermodel.Font greenFont = wb.createFont();
            greenFont.setColor(IndexedColors.GREEN.getIndex());
            greenFont.setBold(true);
            
            org.apache.poi.ss.usermodel.Font normalFont = wb.createFont();

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
                    org.apache.poi.ss.usermodel.Row r0 = summarySheet.createRow(0);
                    r0.createCell(0).setCellValue("Data Comparison Summary");
                    r0.getCell(0).setCellStyle(headerStyle);

                    summarySheet.createRow(2).createCell(0).setCellValue("Total Source Rows:");
                    summarySheet.getRow(2).createCell(1).setCellValue(totalSource);

                    summarySheet.createRow(3).createCell(0).setCellValue("Total Target Rows:");
                    summarySheet.getRow(3).createCell(1).setCellValue(totalTarget);

                    summarySheet.createRow(4).createCell(0).setCellValue("Total Differences:");
                    summarySheet.getRow(4).createCell(1).setCellValue(totalDiffs);
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
                            XSSFRichTextString richString = new XSSFRichTextString();
                            int start = 0;
                            
                            String srcStr = "[SRC] " + (dc.getSourceValue() != null ? dc.getSourceValue() : "NULL") + "\n";
                            richString.append(srcStr);
                            richString.applyFont(start, start + srcStr.length(), (XSSFFont) rf);
                            start += srcStr.length();

                            String tgtStr = "[TGT] " + (dc.getTargetValue() != null ? dc.getTargetValue() : "NULL");
                            richString.append(tgtStr);
                            richString.applyFont(start, start + tgtStr.length(), (XSSFFont) gf);
                            
                            c.setCellValue(richString);
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
        PdfWriter.getInstance(document, out);
        document.open();

        Font titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 16);
        document.add(new Paragraph("Data Comparison Report - Filter: " + filterStatus, titleFont));
        document.add(new Paragraph(" ")); // blank line

        Font headerFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8, Color.WHITE);
        Font cellFont = FontFactory.getFont(FontFactory.HELVETICA, 8);
        Font redFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8, Color.RED);
        Font greenFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8, new Color(0, 150, 0));

        comparisonService.processStream(request, new DiffRowConsumer() {
            private PdfPTable table;
            private List<String> columns;
            private int printedRows = 0;

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
                cell.setPadding(4);
                table.addCell(cell);
            }

            @Override
            public void onRow(DiffRow row) throws Exception {
                boolean shouldPrint = "ALL".equalsIgnoreCase(filterStatus) || row.getStatus().name().equalsIgnoreCase(filterStatus);
                if (!shouldPrint) return;

                printedRows++;
                
                Color bgColor = Color.WHITE;
                if (row.getStatus() == DiffRow.Status.DIFFERENT) bgColor = new Color(254, 252, 232); // yellow
                else if (row.getStatus() == DiffRow.Status.SOURCE_ONLY) bgColor = new Color(254, 242, 242); // red
                else if (row.getStatus() == DiffRow.Status.TARGET_ONLY) bgColor = new Color(236, 253, 245); // green

                PdfPCell statusCell = new PdfPCell(new Phrase(row.getStatus().name(), cellFont));
                statusCell.setBackgroundColor(bgColor);
                table.addCell(statusCell);

                PdfPCell keyCell = new PdfPCell(new Phrase(row.getRowKey(), cellFont));
                keyCell.setBackgroundColor(bgColor);
                table.addCell(keyCell);

                for (String col : columns) {
                    DiffCell dc = row.getCells().get(col);
                    PdfPCell c = new PdfPCell();
                    c.setBackgroundColor(bgColor);
                    if (dc == null) {
                        c.addElement(new Phrase("", cellFont));
                    } else if (dc.isDifferent()) {
                        Paragraph p = new Paragraph();
                        p.add(new Chunk("[SRC] " + (dc.getSourceValue() != null ? dc.getSourceValue() : "NULL") + "\n", redFont));
                        p.add(new Chunk("[TGT] " + (dc.getTargetValue() != null ? dc.getTargetValue() : "NULL"), greenFont));
                        c.addElement(p);
                    } else {
                        c.addElement(new Phrase(String.valueOf(dc.getSourceValue()), cellFont));
                    }
                    table.addCell(c);
                }
            }

            @Override
            public void onTotals(int totalSource, int totalTarget, int totalDiffs) throws Exception {
                Font summaryFont = FontFactory.getFont(FontFactory.HELVETICA, 10);
                document.add(new Paragraph(String.format("Total Source: %d | Total Target: %d | Differences: %d", totalSource, totalTarget, totalDiffs), summaryFont));
                document.add(new Paragraph("Rows included in this report: " + printedRows, summaryFont));
                document.add(new Paragraph(" "));
                document.add(table);
            }
        });

        document.close();
    }
}
