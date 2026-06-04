import React, { useMemo, useRef, useCallback, useEffect } from 'react';
import { useAppStore, type DiffRow } from '../store/useAppStore';
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
import { buildEffectiveQuery } from '../utils/queryHelpers';

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

    diffResult.columns.forEach((colName: string) => {
      cols.push(
        columnHelper.accessor(row => row.cells[colName], {
          id: `col_${colName}`,
          header: colName,
          cell: info => <DiffCell cell={info.getValue()} />,
        })
      );
    });

    return cols;
  }, [diffResult?.columns]);

  const filteredData = useMemo(() => {
    if (!diffResult) return [];
    // Destructure array to force a new reference so TanStack Table recomputes row model
    // This is required because we mutate existing.rows.push() in the Zustand store for performance
    if (filterStatus === 'ALL') return [...diffResult.rows];
    if (filterStatus === 'IDENTICAL') return diffResult.rows.filter((r: any) => r.status === 'MATCH');
    return diffResult.rows.filter((r: any) => r.status === filterStatus);
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
    initialRect: { width: 800, height: 600 },
  });

  useEffect(() => {
    // Force virtualizer to recalculate when new rows arrive during streaming
    // or when the status changes. This fixes the blank grid issue during streaming.
    if (tableRows.length > 0) {
      rowVirtualizer.measure();
    }
  }, [tableRows.length, diffResult?.status, rowVirtualizer]);

  /* ── Export handlers ─────────────────────────────────────────── */

  const buildExportPayload = useCallback(() => {
    if (!mappingId) return null;
    const store = useAppStore.getState();
    const mapping = store.tableMappings.find(m => m.id === mappingId);
    const sourceConn = store.connections.find(c => c.id === store.sourceConnectionId);
    const targetConn = store.connections.find(c => c.id === store.targetConnectionId);
    
    if (!mapping || !sourceConn || !targetConn) return null;
    
    const sqFinal = buildEffectiveQuery(mapping.sourceTable, mapping, 'source');
    const tqFinal = buildEffectiveQuery(mapping.targetTable, mapping, 'target');

    return {
      sourceConnection: sourceConn,
      targetConnection: targetConn,
      tableName: null,
      customQuerySource: sqFinal,
      customQueryTarget: tqFinal,
      primaryKeys: mapping.primaryKeys || null,
      excludeColumns: mapping.excludeColumns || null,
    };
  }, [mappingId]);

  const triggerDownload = async (url: string, filename: string) => {
    const payload = buildExportPayload();
    if (!payload) {
      alert("Cannot export: mapping or connection info missing.");
      return;
    }

    try {
      // Show loading indicator on button (could be enhanced, keeping simple for now)
      document.body.style.cursor = 'wait';
      const response = await fetch(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload),
      });

      if (!response.ok) {
        throw new Error(`Export failed with status: ${response.status}`);
      }

      const blob = await response.blob();
      const downloadUrl = window.URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = downloadUrl;
      link.download = filename;
      document.body.appendChild(link);
      link.click();
      link.remove();
      window.URL.revokeObjectURL(downloadUrl);
    } catch (err: any) {
      console.error(err);
      alert("Failed to export: " + err.message);
    } finally {
      document.body.style.cursor = 'default';
    }
  };

  const handleExportExcel = useCallback(() => {
    triggerDownload(`http://localhost:8081/api/export-excel?filterStatus=${filterStatus}`, `data-compare-${mappingId || 'export'}.xlsx`);
  }, [buildExportPayload, filterStatus, mappingId]);

  const handleExportPDF = useCallback(() => {
    triggerDownload(`http://localhost:8081/api/export-pdf?filterStatus=${filterStatus}`, `data-compare-${mappingId || 'export'}.pdf`);
  }, [buildExportPayload, filterStatus, mappingId]);


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
          <div className="p-12 text-center text-xs text-text-muted flex flex-col items-center justify-center gap-2">
            {diffResult.status === 'comparing' ? (
              <>
                <div className="w-5 h-5 border-2 border-blue-500 border-t-transparent rounded-full animate-spin" />
                <span className="text-blue-500 animate-pulse">Comparing in progress...</span>
              </>
            ) : (
              <span>No records match this filter.</span>
            )}
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
