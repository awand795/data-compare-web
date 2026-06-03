import React, { useMemo, useRef, useCallback } from 'react';
import { useAppStore, type DiffRow, type DiffResult } from '../store/useAppStore';
import {
  createColumnHelper,
  flexRender,
  getCoreRowModel,
  useReactTable,
  getFilteredRowModel,
  type ColumnDef,
} from '@tanstack/react-table';
import { Database, ArrowRight } from 'lucide-react';
import clsx from 'clsx';
import { useVirtualizer } from '@tanstack/react-virtual';
import ExcelJS from 'exceljs';
import { saveAs } from 'file-saver';
import jsPDF from 'jspdf';
import autoTable from 'jspdf-autotable';

interface DiffDataGridProps {
  mappingId?: string | null;
  filterStatus: 'ALL' | 'DIFFERENT' | 'SOURCE_ONLY' | 'TARGET_ONLY' | 'IDENTICAL';
  directResult?: any;
}

/* ── Memoised cell renderers ─────────────────────────────────────── */

const StatusCell = React.memo(({ value }: { value: string }) => {
  const styles: Record<string, string> = {
    MATCH: 'bg-bg-hover text-text-muted border-border-main',
    DIFFERENT: 'bg-amber-500/10 text-amber-500 dark:text-amber-400 border-amber-500/20',
    SOURCE_ONLY: 'bg-red-500/10 text-red-500 dark:text-red-400 border-red-500/20',
    TARGET_ONLY: 'bg-emerald-500/10 text-emerald-600 dark:text-emerald-400 border-emerald-500/20',
  };
  return (
    <span className={clsx("px-2 py-0.5 rounded border text-[9px] font-bold uppercase tracking-wider whitespace-nowrap", styles[value] || '')}>
      {value}
    </span>
  );
});
StatusCell.displayName = 'StatusCell';

const KeyCell = React.memo(({ value }: { value: string }) => (
  <div className="font-mono text-[11px] text-blue-500 dark:text-blue-400 font-medium whitespace-nowrap">{value}</div>
));
KeyCell.displayName = 'KeyCell';

const DiffCell = React.memo(({ cell }: { cell: { sourceValue: any; targetValue: any; isDifferent: boolean } | undefined }) => {
  if (!cell) return <span className="text-text-muted text-[10px]">—</span>;

  if (cell.isDifferent) {
    return (
      <div className="flex flex-col text-[10px] font-mono border border-border-main rounded overflow-hidden min-w-[100px]">
        <div className="bg-red-500/5 text-red-500 dark:text-red-300 px-2 py-1 border-b border-border-item flex items-baseline gap-1.5">
          <span className="text-[8px] text-red-500/60 font-bold uppercase shrink-0">SRC</span>
          <span className="break-all leading-snug">{String(cell.sourceValue ?? 'NULL')}</span>
        </div>
        <div className="bg-emerald-500/5 text-emerald-600 dark:text-emerald-300 px-2 py-1 flex items-baseline gap-1.5">
          <span className="text-[8px] text-emerald-500/60 font-bold uppercase shrink-0">TGT</span>
          <span className="break-all leading-snug">{String(cell.targetValue ?? 'NULL')}</span>
        </div>
      </div>
    );
  }

  return <div className="text-[11px] font-mono text-text-main px-0.5 whitespace-nowrap">{String(cell.sourceValue ?? 'NULL')}</div>;
});
DiffCell.displayName = 'DiffCell';

/* ── Helper: build flattened export data ─────────────────────────── */

function buildExportData(diffResult: DiffResult, filteredData: DiffRow[]) {
  const headers = ['Status', 'RowKey', ...diffResult.columns];

  const rows = filteredData.map(row => {
    const flat: Record<string, any> = {
      Status: row.status,
      RowKey: row.rowKey,
    };
    diffResult.columns.forEach(col => {
      const cell = row.cells[col];
      if (!cell) {
        flat[col] = '';
      } else if (cell.isDifferent) {
        flat[col] = {
          isDiff: true,
          src: cell.sourceValue ?? 'NULL',
          tgt: cell.targetValue ?? 'NULL'
        };
      } else {
        flat[col] = String(cell.sourceValue ?? 'NULL');
      }
    });
    return flat;
  });

  return { headers, rows };
}

/* ── Main component ──────────────────────────────────────────────── */

export const DiffDataGrid: React.FC<DiffDataGridProps> = ({ mappingId, filterStatus, directResult }) => {
  const { diffResults } = useAppStore();
  const diffResult = directResult || (mappingId ? diffResults[mappingId] : null);

  const scrollContainerRef = useRef<HTMLDivElement>(null);

  const columnHelper = createColumnHelper<DiffRow>();

  const columns = useMemo(() => {
    if (!diffResult) return [];

    const cols: ColumnDef<DiffRow, any>[] = [
      columnHelper.accessor('status', {
        header: 'Status',
        size: 100,
        cell: info => <StatusCell value={info.getValue()} />,
      }),
      columnHelper.accessor('rowKey', {
        header: 'Key',
        size: 120,
        cell: info => <KeyCell value={info.getValue()} />,
      }),
    ];

    diffResult.columns.forEach(colName => {
      cols.push(
        columnHelper.accessor(row => row.cells[colName], {
          id: `col_${colName}`,
          header: colName,
          cell: info => <DiffCell cell={info.getValue()} />,
        })
      );
    });

    return cols;
  }, [diffResult]);

  const filteredData = useMemo(() => {
    if (!diffResult) return [];
    if (filterStatus === 'ALL') return diffResult.rows;
    if (filterStatus === 'IDENTICAL') return diffResult.rows.filter(r => r.status === 'MATCH');
    return diffResult.rows.filter(r => r.status === filterStatus);
  }, [diffResult, filterStatus]);

  const table = useReactTable({
    data: filteredData,
    columns,
    getCoreRowModel: getCoreRowModel(),
    getFilteredRowModel: getFilteredRowModel(),
  });

  const { rows: tableRows } = table.getRowModel();

  const rowVirtualizer = useVirtualizer({
    count: tableRows.length,
    getScrollElement: () => scrollContainerRef.current,
    estimateSize: () => 36,
    overscan: 20,
  });

  /* ── Export handlers ─────────────────────────────────────────── */

  const handleExportExcel = useCallback(async () => {
    if (!diffResult || filteredData.length === 0) return;
    const { headers, rows } = buildExportData(diffResult, filteredData);
    
    const workbook = new ExcelJS.Workbook();
    const sheet = workbook.addWorksheet('Data Compare', {
      views: [{ state: 'frozen', xSplit: 2, ySplit: 8 }]
    });

    // Write Header Summary
    sheet.getCell('A1').value = 'DATA COMPARISON REPORT';
    sheet.getCell('A1').font = { bold: true, size: 16, color: { argb: 'FFFFFFFF' } };
    sheet.getCell('A1').fill = { type: 'pattern', pattern: 'solid', fgColor: { argb: 'FF1E3A8A' } }; // blue-900
    sheet.mergeCells('A1:E1');

    const summaryMap = [
      ['Total Rows', diffResult.rows.length],
      ['Source Rows', diffResult.totalSourceRows],
      ['Target Rows', diffResult.totalTargetRows],
      ['Total Differences', diffResult.totalDifferences],
      ['Currently Showing', filteredData.length]
    ];

    summaryMap.forEach((s, idx) => {
      const row = idx + 3;
      sheet.getCell(`A${row}`).value = s[0];
      sheet.getCell(`A${row}`).font = { bold: true };
      sheet.getCell(`B${row}`).value = s[1];
    });

    // Write Columns
    const headerRow = sheet.getRow(8);
    headers.forEach((h, i) => {
      const cell = headerRow.getCell(i + 1);
      cell.value = h;
      cell.font = { bold: true, color: { argb: 'FFFFFFFF' } };
      cell.fill = { type: 'pattern', pattern: 'solid', fgColor: { argb: 'FF3B82F6' } }; // blue-500
      cell.alignment = { vertical: 'middle', horizontal: 'center' };
    });

    // Column widths
    const colWidths: Record<string, number> = {};
    headers.forEach(h => colWidths[h] = Math.max(h.length + 5, 12));

    // Write Data
    rows.forEach((r, idx) => {
      const rowNum = 9 + idx;
      const row = sheet.getRow(rowNum);
      const status = r['Status'];

      // Row background color based on status
      let bgColor = 'FFFFFFFF'; // match (white)
      if (status === 'DIFFERENT') bgColor = 'FFFFFBEB'; // amber-50
      else if (status === 'SOURCE_ONLY') bgColor = 'FFFEF2F2'; // red-50
      else if (status === 'TARGET_ONLY') bgColor = 'FFECFDF5'; // emerald-50

      headers.forEach((h, colIdx) => {
        const cell = row.getCell(colIdx + 1);
        cell.fill = { type: 'pattern', pattern: 'solid', fgColor: { argb: bgColor } };
        cell.alignment = { vertical: 'top', wrapText: true };
        cell.border = {
          top: { style: 'thin', color: { argb: 'FFE2E8F0' } },
          bottom: { style: 'thin', color: { argb: 'FFE2E8F0' } },
          left: { style: 'thin', color: { argb: 'FFE2E8F0' } },
          right: { style: 'thin', color: { argb: 'FFE2E8F0' } },
        };

        const val = r[h];
        if (val && typeof val === 'object' && val.isDiff) {
          // It's a difference cell! Apply Rich Text
          cell.value = {
            richText: [
              { text: '[SRC] ' + val.src + '\n', font: { color: { argb: 'FFDC2626' }, bold: true } },
              { text: '[TGT] ' + val.tgt, font: { color: { argb: 'FF059669' }, bold: true } }
            ]
          };
          colWidths[h] = Math.max(colWidths[h], String(val.src).length + 8, String(val.tgt).length + 8);
        } else {
          cell.value = val;
          if (status === 'DIFFERENT' && colIdx > 1) {
             cell.font = { color: { argb: 'FF64748B' } }; // muted for identical cells in different rows
          }
          if (val) {
             colWidths[h] = Math.max(colWidths[h], String(val).length + 4);
          }
        }
      });
    });

    // Set Max Widths to prevent insane Excel widths
    headers.forEach((h, i) => {
      sheet.getColumn(i + 1).width = Math.min(colWidths[h], 50);
    });

    const buffer = await workbook.xlsx.writeBuffer();
    saveAs(new Blob([buffer]), `data-compare-${mappingId || 'export'}.xlsx`);
  }, [diffResult, filteredData, mappingId]);

  const handleExportPDF = useCallback(() => {
    if (!diffResult || filteredData.length === 0) return;
    const { headers, rows } = buildExportData(diffResult, filteredData);
    const doc = new jsPDF('l', 'pt', 'a4');
    
    doc.setFontSize(14);
    doc.text('Data Comparison Report', 40, 40);
    doc.setFontSize(10);
    doc.text(`Total Rows: ${diffResult.rows.length}  |  Source Rows: ${diffResult.totalSourceRows}  |  Target Rows: ${diffResult.totalTargetRows}`, 40, 60);
    doc.text(`Total Differences: ${diffResult.totalDifferences}  |  Showing in Report: ${filteredData.length}`, 40, 75);

    // Format plain strings for PDF since it doesn't support our rich text object directly
    const pdfRows = rows.map(r => headers.map(h => {
      const val = r[h];
      if (val && typeof val === 'object' && val.isDiff) {
        return `[SRC] ${val.src}\n[TGT] ${val.tgt}`;
      }
      return val ?? '';
    }));

    autoTable(doc, {
      startY: 90,
      head: [headers],
      body: pdfRows,
      styles: { fontSize: 6, cellPadding: 2, overflow: 'linebreak' },
      theme: 'grid',
      headStyles: { fillColor: [59, 130, 246] }, // Blue-500
      horizontalPageBreak: true,
      horizontalPageBreakRepeat: 0,
      didParseCell: (hookData) => {
        if (hookData.section === 'body') {
           const status = hookData.row.raw[0]; // Status is first column
           if (status === 'DIFFERENT') hookData.cell.styles.fillColor = [254, 252, 232]; // yellow-50
           else if (status === 'SOURCE_ONLY') hookData.cell.styles.fillColor = [254, 242, 242]; // red-50
           else if (status === 'TARGET_ONLY') hookData.cell.styles.fillColor = [236, 253, 245]; // emerald-50
           
           if (hookData.column.index === 0) {
             if (status === 'MATCH') hookData.cell.styles.textColor = [100, 116, 139];
             if (status === 'DIFFERENT') hookData.cell.styles.textColor = [245, 158, 11];
             if (status === 'SOURCE_ONLY') hookData.cell.styles.textColor = [239, 68, 68];
             if (status === 'TARGET_ONLY') hookData.cell.styles.textColor = [16, 185, 129];
           }

           if (hookData.column.index > 1) {
             const val = String(hookData.cell.raw || '');
             if (val.includes('[SRC]') && val.includes('[TGT]')) {
                hookData.cell.styles.textColor = [220, 38, 38]; // Red for diff text
             } else if (status === 'DIFFERENT') {
                hookData.cell.styles.textColor = [148, 163, 184]; // Muted for identical text
             }
           }
        }
      }
    });
    doc.save(`data-compare-${mappingId || 'export'}.pdf`);
  }, [diffResult, filteredData, mappingId]);

  /* ── Empty state ─────────────────────────────────────────────── */

  if (!diffResult) {
    return (
      <div className="h-full flex flex-col items-center justify-center text-text-muted gap-3">
        <div className="flex items-center gap-2 text-text-muted opacity-50">
          <Database className="w-6 h-6" />
          <ArrowRight className="w-4 h-4" />
          <Database className="w-6 h-6" />
        </div>
        <span className="text-xs">Select table mappings and click <strong className="text-text-main">Compare</strong> to view differences</span>
      </div>
    );
  }

  /* ── Virtualised table ───────────────────────────────────────── */

  const virtualItems = rowVirtualizer.getVirtualItems();

  return (
    <div className="h-full flex flex-col">
      <div className="flex-1 overflow-auto" ref={scrollContainerRef}>
        <table className="w-full text-left border-collapse">
          <thead className="sticky top-0 z-10 bg-bg-header shadow-md border-b border-border-main">
            {table.getHeaderGroups().map(headerGroup => (
              <tr key={headerGroup.id}>
                {headerGroup.headers.map(header => (
                  <th key={header.id} className="px-2.5 py-2 text-[10px] font-bold text-text-muted uppercase tracking-wider border-b border-border-main border-r border-r-border-item last:border-r-0 whitespace-nowrap bg-bg-header">
                    {header.isPlaceholder
                      ? null
                      : flexRender(header.column.columnDef.header, header.getContext())}
                  </th>
                ))}
              </tr>
            ))}
          </thead>
          <tbody>
            {virtualItems.length > 0 && (
              <tr>
                <td style={{ height: `${virtualItems[0].start}px` }} colSpan={columns.length} />
              </tr>
            )}
            {virtualItems.map(virtualRow => {
              const row = tableRows[virtualRow.index];
              const i = virtualRow.index;
              const status = row.original.status;
              const rowClass = clsx(
                "border-b border-border-item transition-colors",
                i % 2 === 0 ? "bg-bg-main" : "bg-bg-row-alt",
                status === 'DIFFERENT' && "bg-amber-500/[0.03] dark:bg-amber-500/[0.05]",
                status === 'SOURCE_ONLY' && "bg-red-500/[0.03] dark:bg-red-500/[0.05]",
                status === 'TARGET_ONLY' && "bg-emerald-500/[0.03] dark:bg-emerald-500/[0.05]",
                "hover:bg-bg-hover"
              );

              return (
                <tr
                  key={row.id}
                  data-index={virtualRow.index}
                  ref={rowVirtualizer.measureElement}
                  className={rowClass}
                >
                  {row.getVisibleCells().map(cell => (
                    <td key={cell.id} className="px-2 py-1.5 align-top border-r border-border-item last:border-r-0">
                      {flexRender(cell.column.columnDef.cell, cell.getContext())}
                    </td>
                  ))}
                </tr>
              );
            })}
            {virtualItems.length > 0 && (
              <tr>
                <td
                  style={{ height: `${rowVirtualizer.getTotalSize() - virtualItems[virtualItems.length - 1].end}px` }}
                  colSpan={columns.length}
                />
              </tr>
            )}
          </tbody>
        </table>

        {filteredData.length === 0 && (
          <div className="p-12 text-center text-xs text-text-muted">
            No records match this filter.
          </div>
        )}
      </div>

      {/* Footer stats + export */}
      <div className="shrink-0 bg-bg-header border-t border-border-main px-3 py-1.5 flex items-center gap-4 text-[10px] text-text-muted">
        <span>Total: <strong className="text-text-main">{diffResult.rows.length}</strong> rows</span>
        <span>Source: <strong className="text-text-main">{diffResult.totalSourceRows}</strong></span>
        <span>Target: <strong className="text-text-main">{diffResult.totalTargetRows}</strong></span>
        <span className="text-amber-500 dark:text-amber-400 font-semibold">Δ {diffResult.totalDifferences}</span>
        <span>Showing: <strong className="text-text-main">{filteredData.length}</strong></span>

        {/* Spacer */}
        <div className="flex-1" />

        {/* Export buttons */}
        <div className="flex items-center gap-2">
          <button
            onClick={handleExportExcel}
            className="px-3 py-1.5 bg-green-600/10 text-green-600 dark:text-green-400 hover:bg-green-600/20 border border-green-600/20 rounded-md text-xs font-semibold flex items-center gap-1.5 transition-colors"
            title="Export current view to Excel"
          >
            Excel
          </button>
          <button
            onClick={handleExportPDF}
            className="px-3 py-1.5 bg-red-600/10 text-red-600 dark:text-red-400 hover:bg-red-600/20 border border-red-600/20 rounded-md text-xs font-semibold flex items-center gap-1.5 transition-colors"
            title="Export current view to PDF"
          >
            PDF
          </button>
        </div>
      </div>
    </div>
  );
};
