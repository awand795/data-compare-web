import ExcelJS from 'exceljs';
import { saveAs } from 'file-saver';
import jsPDF from 'jspdf';
import autoTable from 'jspdf-autotable';

interface SheetData {
  sheetName: string;
  title: string;
  subtitle?: string;
  columns: string[];
  data: any[];
}

interface ExportOptions {
  fileName: string;
  sheets?: SheetData[];
  title?: string;
  subtitle?: string;
  columns?: string[];
  data?: any[];
}

export const exportToExcel = async (options: ExportOptions) => {
  const workbook = new ExcelJS.Workbook();
  workbook.creator = 'DarkoSync Studio';

  const sheets = options.sheets || [{
    sheetName: 'Data Export',
    title: options.title || 'Export',
    subtitle: options.subtitle,
    columns: options.columns || [],
    data: options.data || []
  }];

  sheets.forEach(({ sheetName, title, subtitle, columns, data }) => {
    const worksheet = workbook.addWorksheet(sheetName, {
      views: [{ state: 'frozen', ySplit: subtitle ? 4 : 3 }]
    });

    // Clean, modern title row
    worksheet.mergeCells(`A1:${String.fromCharCode(65 + Math.min(columns.length - 1, 25))}1`);
    const titleCell = worksheet.getCell('A1');
    titleCell.value = title;
    titleCell.font = { name: 'Inter', size: 18, bold: true, color: { argb: 'FF0F172A' } }; // slate-900
    titleCell.alignment = { vertical: 'middle', horizontal: 'left' };
    worksheet.getRow(1).height = 35;

    // Subtitle
    worksheet.mergeCells(`A2:${String.fromCharCode(65 + Math.min(columns.length - 1, 25))}2`);
    const subtitleCell = worksheet.getCell('A2');
    subtitleCell.value = subtitle || `Generated on: ${new Date().toLocaleString()}`;
    subtitleCell.font = { name: 'Inter', size: 10, color: { argb: 'FF64748B' } }; // slate-500
    subtitleCell.alignment = { vertical: 'middle', horizontal: 'left' };
    worksheet.getRow(2).height = 20;

    worksheet.getRow(3).height = 10; // spacer

    // Headers
    const headerRow = worksheet.getRow(4);
    headerRow.height = 25;
    columns.forEach((col, idx) => {
      const cell = headerRow.getCell(idx + 1);
      cell.value = col;
      cell.font = { name: 'Inter', size: 10, bold: true, color: { argb: 'FFFFFFFF' } };
      cell.fill = { type: 'pattern', pattern: 'solid', fgColor: { argb: 'FF2563EB' } }; // Blue-600
      cell.alignment = { vertical: 'middle', horizontal: 'center' };
      cell.border = {
        top: { style: 'thin', color: { argb: 'FF94A3B8' } },
        bottom: { style: 'medium', color: { argb: 'FF94A3B8' } },
        left: { style: 'thin', color: { argb: 'FF94A3B8' } },
        right: { style: 'thin', color: { argb: 'FF94A3B8' } }
      };
    });

    // Auto-calculate column widths
    const colWidths = columns.map(col => col.length + 2);
    data.forEach(row => {
      columns.forEach((col, idx) => {
        const val = row[col] ?? row[idx];
        const strVal = typeof val === 'object' && val !== null ? JSON.stringify(val) : String(val ?? '');
        colWidths[idx] = Math.max(colWidths[idx], Math.min(strVal.length + 2, 60)); // cap at 60 chars
      });
    });

    worksheet.columns = columns.map((_, idx) => ({
      width: Math.max(12, colWidths[idx])
    }));

    // Data
    data.forEach((row, rowIdx) => {
      const excelRow = worksheet.getRow(5 + rowIdx);
      excelRow.height = 20; // Fixed height to prevent massive rows
      columns.forEach((col, colIdx) => {
        const cell = excelRow.getCell(colIdx + 1);
        const val = row[col] ?? row[colIdx];
        cell.value = typeof val === 'object' && val !== null ? JSON.stringify(val) : val;
        cell.font = { name: 'Inter', size: 9, color: { argb: 'FF334155' } };
        // wrapText: false to keep rows neat
        cell.alignment = { vertical: 'middle', horizontal: 'left', wrapText: false, indent: 1 };
        
        if (rowIdx % 2 !== 0) {
          cell.fill = { type: 'pattern', pattern: 'solid', fgColor: { argb: 'FFF8FAFC' } }; // slate-50
        }
        
        cell.border = {
          top: { style: 'hair', color: { argb: 'FFE2E8F0' } },
          bottom: { style: 'hair', color: { argb: 'FFE2E8F0' } },
          left: { style: 'thin', color: { argb: 'FFE2E8F0' } },
          right: { style: 'thin', color: { argb: 'FFE2E8F0' } }
        };
      });
    });
  });

  const buffer = await workbook.xlsx.writeBuffer();
  saveAs(new Blob([buffer]), options.fileName.endsWith('.xlsx') ? options.fileName : `${options.fileName}.xlsx`);
};

export const exportToPDF = (options: ExportOptions) => {
  const sheets = options.sheets || [{
    sheetName: 'Data Export',
    title: options.title || 'Export',
    subtitle: options.subtitle,
    columns: options.columns || [],
    data: options.data || []
  }];

  const maxCols = Math.max(...sheets.map(s => s.columns.length));
  
  // Dynamically size PDF width to prevent squished columns for large tables
  // A4 landscape is 842pt wide. If we have > 10 columns, we increase the width.
  const customWidth = Math.max(842, maxCols * 70); 
  const doc = new jsPDF('l', 'pt', [595, customWidth] as any);
  
  const pageWidth = doc.internal.pageSize.getWidth();

  sheets.forEach((sheet, sheetIdx) => {
    if (sheetIdx > 0) doc.addPage();
    const { title, subtitle, columns, data } = sheet;

    // Title
    doc.setFontSize(22);
    doc.setTextColor(15, 23, 42); // slate-900
    doc.text(title, 40, 45);
    
    // Subtitle
    doc.setFontSize(10);
    doc.setTextColor(100, 116, 139); // slate-500
    doc.text(subtitle || `Generated on: ${new Date().toLocaleString()}`, 40, 65);
    
    // Line separator
    doc.setDrawColor(203, 213, 225); // slate-300
    doc.setLineWidth(1);
    doc.line(40, 75, pageWidth - 40, 75);
    
    autoTable(doc, {
      head: [columns],
      body: data.map(row => columns.map((col, idx) => {
        const val = row[col] ?? row[idx];
        return typeof val === 'object' && val !== null ? JSON.stringify(val) : String(val ?? '');
      })),
      startY: 85,
      styles: { 
        fontSize: 7, 
        font: 'helvetica', 
        cellPadding: 3, 
        overflow: 'ellipsize', // Prevents giant rows from long text
        textColor: [51, 65, 85], // slate-700
        lineColor: [226, 232, 240], // slate-200
        lineWidth: 0.5,
      },
      headStyles: { 
        fillColor: [37, 99, 235], // blue-600
        textColor: 255, 
        fontSize: 8, 
        fontStyle: 'bold', 
        halign: 'center',
        valign: 'middle'
      },
      alternateRowStyles: { 
        fillColor: [248, 250, 252] // slate-50
      },
      theme: 'grid',
      pageBreak: 'auto',
      margin: { top: 40, right: 40, bottom: 40, left: 40 },
      didDrawPage: (data) => {
        doc.setFontSize(8);
        doc.setTextColor(148, 163, 184); // slate-400
        doc.text(`Page ${data.pageNumber}`, pageWidth - 40, doc.internal.pageSize.getHeight() - 20, { align: 'right' });
        doc.text(`DarkoSync Studio`, 40, doc.internal.pageSize.getHeight() - 20);
      }
    });
  });
  
  doc.save(options.fileName.endsWith('.pdf') ? options.fileName : `${options.fileName}.pdf`);
};


