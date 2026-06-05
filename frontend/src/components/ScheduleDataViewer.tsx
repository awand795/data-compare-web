import React, { useState, useEffect, useMemo, useRef, useCallback } from 'react';
import axios from 'axios';
import {
  X, Loader2, Database, AlertCircle, Download, CheckCircle,
  Filter, ChevronDown, Eye, Table2, ArrowRight
} from 'lucide-react';
import clsx from 'clsx';
import { useVirtualizer } from '@tanstack/react-virtual';

interface CellValue {
  sourceValue: any;
  targetValue: any;
  isDifferent: boolean;
}

interface RowData {
  id: number;
  resultId: string;
  rowKey: string;
  status: string;
  dataJson: string;
}

interface ParsedRow {
  id: number;
  rowKey: string;
  status: string;
  cells: Record<string, CellValue>;
}

interface ScheduleDataViewerProps {
  resultId: string;
  tableName: string;
  onClose: () => void;
}

type FilterMode = 'ALL' | 'DIFFERENT' | 'SOURCE_ONLY' | 'TARGET_ONLY';

/* ── Memoised cell renderers ─────────────────────────────────────── */

const StatusBadge = React.memo(({ status }: { status: string }) => {
  const styles: Record<string, string> = {
    DIFFERENT: 'bg-amber-500/10 text-amber-500 dark:text-amber-400 border-amber-500/20',
    SOURCE_ONLY: 'bg-red-500/10 text-red-500 dark:text-red-400 border-red-500/20',
    TARGET_ONLY: 'bg-emerald-500/10 text-emerald-600 dark:text-emerald-400 border-emerald-500/20',
  };
  return (
    <span className={clsx(
      "px-2 py-0.5 rounded border text-[9px] font-bold uppercase tracking-wider whitespace-nowrap",
      styles[status] || ''
    )}>
      {status.replace('_', ' ')}
    </span>
  );
});
StatusBadge.displayName = 'StatusBadge';

const CompareCell = React.memo(({ cell }: { cell: CellValue | undefined }) => {
  if (!cell) return <span className="text-text-muted text-[10px]">—</span>;

  if (cell.isDifferent) {
    return (
      <div className="flex flex-col text-[10px] font-mono border border-border-main rounded overflow-hidden min-w-[120px]">
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

  return (
    <div className="text-[11px] font-mono text-text-main px-0.5 whitespace-nowrap">
      {String(cell.sourceValue ?? 'NULL')}
    </div>
  );
});
CompareCell.displayName = 'CompareCell';

/* ── Main component ──────────────────────────────────────────────── */

export const ScheduleDataViewer: React.FC<ScheduleDataViewerProps> = ({ resultId, tableName, onClose }) => {
  const [rawRows, setRawRows] = useState<RowData[]>([]);
  const [loading, setLoading] = useState(true);
  const [filterMode, setFilterMode] = useState<FilterMode>('ALL');

  const scrollContainerRef = useRef<HTMLDivElement>(null);

  // Fetch rows from API
  useEffect(() => {
    setLoading(true);
    axios.get(`http://localhost:8081/api/schedules/results/${resultId}/rows?tableName=${encodeURIComponent(tableName)}`)
      .then(res => setRawRows(res.data || []))
      .catch(err => console.error("Failed to fetch rows", err))
      .finally(() => setLoading(false));
  }, [resultId]);

  // Parse all dataJson -> structured rows
  const parsedRows: ParsedRow[] = useMemo(() => {
    return rawRows.map(r => {
      let cells: Record<string, CellValue> = {};
      try { cells = JSON.parse(r.dataJson); } catch (e) {}
      return { id: r.id, rowKey: r.rowKey, status: r.status, cells };
    });
  }, [rawRows]);

  // Extract all unique column names across all rows
  const allColumns = useMemo(() => {
    const colSet = new Set<string>();
    for (const row of parsedRows) {
      for (const col of Object.keys(row.cells)) {
        colSet.add(col);
      }
    }
    return Array.from(colSet);
  }, [parsedRows]);

  // Filter rows by status
  const filteredRows = useMemo(() => {
    if (filterMode === 'ALL') return parsedRows;
    return parsedRows.filter(r => r.status === filterMode);
  }, [parsedRows, filterMode]);

  // Counts for summary
  const counts = useMemo(() => {
    let diff = 0, srcOnly = 0, tgtOnly = 0;
    for (const r of parsedRows) {
      if (r.status === 'DIFFERENT') diff++;
      else if (r.status === 'SOURCE_ONLY') srcOnly++;
      else if (r.status === 'TARGET_ONLY') tgtOnly++;
    }
    return { total: parsedRows.length, diff, srcOnly, tgtOnly };
  }, [parsedRows]);

  // Virtual scrolling
  const rowVirtualizer = useVirtualizer({
    count: filteredRows.length,
    getScrollElement: () => scrollContainerRef.current,
    estimateSize: () => 40,
    overscan: 15,
    initialRect: { width: 800, height: 500 },
  });

  // Force re-measure when filtered rows change
  useEffect(() => {
    if (filteredRows.length > 0) {
      rowVirtualizer.measure();
    }
  }, [filteredRows.length, rowVirtualizer]);

  // Export to CSV
  const handleExportCSV = useCallback(() => {
    if (parsedRows.length === 0) return;

    const headers = ['Status', 'Row Key', ...allColumns.flatMap(c => [`${c} (Source)`, `${c} (Target)`])];
    const csvRows = [headers.join(',')];

    for (const row of parsedRows) {
      const csvRow = [
        row.status,
        `"${row.rowKey}"`,
        ...allColumns.flatMap(col => {
          const cell = row.cells[col];
          if (!cell) return ['', ''];
          return [`"${String(cell.sourceValue ?? 'NULL')}"`, `"${String(cell.targetValue ?? 'NULL')}"`];
        })
      ];
      csvRows.push(csvRow.join(','));
    }

    const blob = new Blob([csvRows.join('\n')], { type: 'text/csv;charset=utf-8;' });
    const url = window.URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = `schedule-diff-${tableName}-${resultId.slice(0, 8)}.csv`;
    document.body.appendChild(link);
    link.click();
    link.remove();
    window.URL.revokeObjectURL(url);
  }, [parsedRows, allColumns, tableName, resultId]);

  /* ── Filter tabs ────────────────────────────────────────────── */

  const tabs: { key: FilterMode; label: string; count: number }[] = [
    { key: 'ALL', label: 'All', count: counts.total },
    { key: 'DIFFERENT', label: 'Different', count: counts.diff },
    { key: 'SOURCE_ONLY', label: 'Source Only', count: counts.srcOnly },
    { key: 'TARGET_ONLY', label: 'Target Only', count: counts.tgtOnly },
  ];

  /* ── Virtual items ──────────────────────────────────────────── */

  const virtualItems = rowVirtualizer.getVirtualItems();

  /* ── Render ─────────────────────────────────────────────────── */

  return (
    <div className="fixed inset-0 bg-black/70 backdrop-blur-md flex items-center justify-center z-[70] p-4">
      <div className="bg-bg-panel border border-border-main rounded-xl shadow-2xl w-full max-w-[95vw] h-[90vh] flex flex-col">
        {/* ── Header ── */}
        <div className="p-4 border-b border-border-main flex justify-between items-center bg-bg-header rounded-t-xl shrink-0">
          <div className="flex items-center gap-3 min-w-0">
            <div className="w-9 h-9 rounded-lg bg-blue-500/10 flex items-center justify-center shrink-0">
              <Table2 className="w-5 h-5 text-blue-400" />
            </div>
            <div className="min-w-0">
              <h2 className="font-bold text-lg truncate">Diff Data Details</h2>
              <p className="text-xs text-text-muted truncate">
                Table: <span className="font-mono text-blue-400">{tableName}</span>
                {' '}&bull;{' '}
                Run: <span className="font-mono">{resultId.slice(0, 8)}...</span>
                {' '}&bull;{' '}
                <span className="text-amber-400 font-semibold">{counts.total}</span> diffs
              </p>
            </div>
          </div>
          <div className="flex items-center gap-2">
            <button
              onClick={handleExportCSV}
              disabled={parsedRows.length === 0}
              className="flex items-center gap-1.5 px-3 py-1.5 bg-blue-600/10 text-blue-400 hover:bg-blue-600/20 border border-blue-600/20 rounded-lg text-xs font-semibold transition-colors disabled:opacity-30"
              title="Export to CSV"
            >
              <Download className="w-3.5 h-3.5" />
              CSV
            </button>
            <button onClick={onClose} className="p-1.5 rounded-lg hover:bg-bg-hover text-text-muted transition-colors">
              <X className="w-5 h-5" />
            </button>
          </div>
        </div>

        {/* ── Filter tabs ── */}
        <div className="shrink-0 px-4 pt-3 pb-2 border-b border-border-main bg-bg-main/30 flex items-center gap-1.5 overflow-x-auto">
          <Filter className="w-3.5 h-3.5 text-text-muted shrink-0 mr-1" />
          {tabs.map(tab => (
            <button
              key={tab.key}
              onClick={() => setFilterMode(tab.key)}
              className={clsx(
                "px-3 py-1.5 rounded-lg text-xs font-semibold transition-all flex items-center gap-1.5 whitespace-nowrap",
                filterMode === tab.key
                  ? "bg-blue-500/15 text-blue-400 shadow-sm border border-blue-500/20"
                  : "text-text-muted hover:text-text-main hover:bg-bg-hover border border-transparent"
              )}
            >
              {tab.label}
              <span className={clsx(
                "text-[10px] font-bold px-1.5 py-0.5 rounded-full",
                filterMode === tab.key ? "bg-blue-500/20 text-blue-400" : "bg-bg-hover text-text-muted"
              )}>
                {tab.count}
              </span>
            </button>
          ))}
        </div>

        {/* ── Content ── */}
        <div className="flex-1 overflow-hidden bg-bg-main/50">
          {loading ? (
            <div className="h-full flex items-center justify-center text-text-muted flex-col gap-3">
              <Loader2 className="w-8 h-8 animate-spin text-blue-500" />
              <p className="text-sm">Loading diff data...</p>
            </div>
          ) : parsedRows.length === 0 ? (
            <div className="h-full flex items-center justify-center text-text-muted flex-col gap-3">
              <div className="w-16 h-16 rounded-full bg-emerald-500/10 flex items-center justify-center">
                <CheckCircle className="w-8 h-8 text-emerald-500" />
              </div>
              <p className="text-sm font-semibold text-emerald-500">No Differences Found</p>
              <p className="text-xs text-text-muted/70">All data in <span className="font-mono text-blue-400">{tableName}</span> is identical between source and target.</p>
            </div>
          ) : filteredRows.length === 0 ? (
            <div className="h-full flex items-center justify-center text-text-muted flex-col gap-2">
              <Eye className="w-8 h-8 opacity-30" />
              <p className="text-xs">No rows match this filter.</p>
              <p className="text-[10px] text-text-muted/60">{counts.total} total rows, none matching "{filterMode.replace(/_/g, ' ')}"</p>
            </div>
          ) : (
            <div className="h-full overflow-auto" ref={scrollContainerRef}>
              <table className="w-full text-left border-collapse">
                <thead className="sticky top-0 z-10 bg-bg-header shadow-md border-b border-border-main">
                  <tr>
                    <th className="px-2 py-2 text-[10px] font-bold text-text-muted uppercase tracking-wider border-b border-border-main border-r border-r-border-item w-10 text-center bg-bg-header">
                      #
                    </th>
                    <th className="px-2 py-2 text-[10px] font-bold text-text-muted uppercase tracking-wider border-b border-border-main border-r border-r-border-item w-24 bg-bg-header">
                      Status
                    </th>
                    <th className="px-2 py-2 text-[10px] font-bold text-text-muted uppercase tracking-wider border-b border-border-main border-r border-r-border-item w-40 bg-bg-header">
                      Row Key
                    </th>
                    {allColumns.map(col => (
                      <th key={col} className="px-2 py-2 text-[10px] font-bold text-text-muted uppercase tracking-wider border-b border-border-main border-r border-r-border-item last:border-r-0 whitespace-nowrap bg-bg-header min-w-[140px]">
                        <div className="flex items-center gap-1">
                          <ArrowRight className="w-2.5 h-2.5 text-text-muted/40" />
                          <span>{col}</span>
                        </div>
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {virtualItems.length > 0 && (
                    <tr>
                      <td style={{ height: `${virtualItems[0].start}px` }} colSpan={allColumns.length + 3} />
                    </tr>
                  )}
                  {virtualItems.map(virtualRow => {
                    const row = filteredRows[virtualRow.index];
                    const i = virtualRow.index;
                    const rowClass = clsx(
                      "border-b border-border-item transition-colors",
                      i % 2 === 0 ? "bg-bg-main" : "bg-bg-row-alt",
                      row.status === 'DIFFERENT' && "bg-amber-500/[0.03] dark:bg-amber-500/[0.05]",
                      row.status === 'SOURCE_ONLY' && "bg-red-500/[0.03] dark:bg-red-500/[0.05]",
                      row.status === 'TARGET_ONLY' && "bg-emerald-500/[0.03] dark:bg-emerald-500/[0.05]",
                      "hover:bg-bg-hover"
                    );

                    return (
                      <tr
                        key={row.id}
                        data-index={virtualRow.index}
                        ref={rowVirtualizer.measureElement}
                        className={rowClass}
                      >
                        <td className="px-2 py-1.5 text-center text-text-muted text-[10px] border-r border-border-item align-top">
                          {i + 1}
                        </td>
                        <td className="px-2 py-1.5 border-r border-border-item align-top">
                          <StatusBadge status={row.status} />
                        </td>
                        <td className="px-2 py-1.5 font-mono text-[11px] text-blue-500 dark:text-blue-400 font-medium border-r border-border-item whitespace-nowrap align-top">
                          {row.rowKey}
                        </td>
                        {allColumns.map(col => (
                          <td key={col} className="px-2 py-1.5 align-top border-r border-border-item last:border-r-0">
                            <CompareCell cell={row.cells[col]} />
                          </td>
                        ))}
                      </tr>
                    );
                  })}
                  {virtualItems.length > 0 && (
                    <tr>
                      <td
                        style={{ height: `${rowVirtualizer.getTotalSize() - virtualItems[virtualItems.length - 1].end}px` }}
                        colSpan={allColumns.length + 3}
                      />
                    </tr>
                  )}
                </tbody>
              </table>
            </div>
          )}
        </div>

        {/* ── Footer stats ── */}
        <div className="shrink-0 bg-bg-header border-t border-border-main px-4 py-2 rounded-b-xl flex items-center gap-4 text-[10px] text-text-muted flex-wrap">
          <span>Total data: <strong className="text-text-main">{counts.total}</strong> rows</span>
          <span className="text-amber-500 dark:text-amber-400 font-semibold">Δ Different: {counts.diff}</span>
          <span className="text-red-500 font-semibold">↑ Source Only: {counts.srcOnly}</span>
          <span className="text-emerald-500 font-semibold">↓ Target Only: {counts.tgtOnly}</span>
          <span>Columns: <strong className="text-text-main">{allColumns.length}</strong></span>
          <span>Showing: <strong className="text-text-main">{filteredRows.length}</strong></span>

          <div className="flex-1" />

          <div className="flex items-center gap-2">
            {parsedRows.length > 0 && (
              <>
                <button
                  onClick={() => {
                    const summary = [
                      `=== Diff Data Summary ===`,
                      `Table: ${tableName}`,
                      `Run ID: ${resultId}`,
                      `Total Diffs: ${counts.total}`,
                      `  Different: ${counts.diff}`,
                      `  Source Only: ${counts.srcOnly}`,
                      `  Target Only: ${counts.tgtOnly}`,
                      `Columns: ${allColumns.length} (${allColumns.join(', ')})`,
                      `========================`,
                    ].join('\n');
                    navigator.clipboard.writeText(summary);
                  }}
                  className="px-2 py-1 text-[10px] text-text-muted hover:text-text-main hover:bg-bg-hover rounded transition-colors"
                  title="Copy summary to clipboard"
                >
                  Copy Summary
                </button>
                <button
                  onClick={handleExportCSV}
                  className="flex items-center gap-1 px-3 py-1.5 bg-blue-600/10 text-blue-400 hover:bg-blue-600/20 border border-blue-600/20 rounded-lg text-xs font-semibold transition-colors"
                >
                  <Download className="w-3 h-3" />
                  Export CSV
                </button>
              </>
            )}
            <button
              onClick={onClose}
              className="px-4 py-1.5 bg-bg-panel border border-border-input rounded-lg text-xs font-semibold hover:bg-bg-hover transition-colors"
            >
              Close
            </button>
          </div>
        </div>
      </div>
    </div>
  );
};
