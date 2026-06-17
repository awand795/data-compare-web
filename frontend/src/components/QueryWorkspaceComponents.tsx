// @ts-nocheck
import React, { useRef, useMemo } from 'react';
import { Download, Check, Copy, Loader2, Database, ChevronDown, RefreshCw, Play, Maximize, Minimize } from 'lucide-react';
import clsx from 'clsx';
import { Panel, Group, Separator } from 'react-resizable-panels';
import { useAppStore } from '../store/useAppStore';
import { useVirtualizer } from '@tanstack/react-virtual';
import { SQLEditor } from './SQLEditor';
import { exportToExcel, exportToPDF } from '../utils/exportUtils';

export const ResultFooter: React.FC<{ 
  data: any[]; 
  cols: string[]; 
  side: 'source' | 'target';
  downloadCSV: (side: 'source' | 'target') => void;
  copyResults: (side: 'source' | 'target') => void;
  copied: string | null;
}> = ({ data, cols, side, downloadCSV, copyResults, copied }) => {
  const handleExportExcel = () => {
    if (data.length === 0) return;
    const exportData = data.map(row => {
      const filtered: Record<string, any> = {};
      cols.forEach(col => { filtered[col] = row[col]; });
      return filtered;
    });
    exportToExcel({
      title: `Query Results - ${side.toUpperCase()}`,
      subtitle: `Columns: ${cols.length} | Rows: ${data.length} | Generated: ${new Date().toLocaleString()}`,
      columns: cols,
      data: exportData,
      fileName: `${side}_query_export.xlsx`
    });
  };

  const handleExportPDF = () => {
    if (data.length === 0) return;
    const exportData = data.map(row => {
      const filtered: Record<string, any> = {};
      cols.forEach(col => { filtered[col] = row[col]; });
      return filtered;
    });
    exportToPDF({
      title: `Query Results - ${side.toUpperCase()}`,
      subtitle: `Columns: ${cols.length} | Rows: ${data.length} | Generated: ${new Date().toLocaleString()}`,
      columns: cols,
      data: exportData,
      fileName: `${side}_query_export.pdf`
    });
  };

  return (
    <div className="shrink-0 bg-bg-header border-t border-border-main px-3 py-1.5 flex items-center justify-between text-xs text-text-muted">
      <span>{data.length} rows × {cols.length} columns</span>
      <div className="flex items-center gap-3">
        <button onClick={() => downloadCSV(side)} className="flex items-center gap-1 hover:text-blue-500 transition-colors">
          <Download className="w-3 h-3" /> CSV
        </button>
        <button
          onClick={handleExportExcel}
          className="px-2.5 py-1 bg-green-600/10 text-green-600 dark:text-green-400 hover:bg-green-600/20 border border-green-600/20 rounded text-xs font-semibold flex items-center gap-1 transition-colors"
          title="Export to Excel"
        >
          Excel
        </button>
        <button
          onClick={handleExportPDF}
          className="px-2.5 py-1 bg-red-600/10 text-red-600 dark:text-red-400 hover:bg-red-600/20 border border-red-600/20 rounded text-xs font-semibold flex items-center gap-1 transition-colors"
          title="Export to PDF"
        >
          PDF
        </button>
        <button onClick={() => copyResults(side)} className="flex items-center gap-1 hover:text-blue-500 transition-colors">
          {copied === side ? <Check className="w-3 h-3 text-emerald-500" /> : <Copy className="w-3 h-3" />}
          {copied === side ? 'Copied!' : 'Copy JSON'}
        </button>
      </div>
    </div>
  );
};

export const ResultTable: React.FC<{
  data: any[] | null;
  error: string;
  loading: boolean;
  side: 'source' | 'target';
  format: 'table' | 'json';
  downloadCSV: (side: 'source' | 'target') => void;
  copyResults: (side: 'source' | 'target') => void;
  copied: string | null;
  isMaximized?: boolean;
  onToggleMaximize?: () => void;
}> = ({ data, error, loading, side, format, downloadCSV, copyResults, copied, isMaximized, onToggleMaximize }) => {
  const [colSearch, setColSearch] = React.useState('');
  const parentRef = useRef<HTMLDivElement>(null);

  const columns = useMemo(() => (data && data.length > 0 ? Object.keys(data[0]) : []), [data]);

  const filteredCols = useMemo(() => {
    if (!colSearch.trim()) return columns;
    return columns.filter(c => c.toLowerCase().includes(colSearch.toLowerCase()));
  }, [columns, colSearch]);

  const rowVirtualizer = useVirtualizer({
    count: data?.length ?? 0,
    getScrollElement: () => parentRef.current,
    estimateSize: () => 28,
    overscan: 15,
  });

  if (loading) return (
    <div className="h-full flex items-center justify-center text-text-muted gap-2">
      <Loader2 className="w-4 h-4 animate-spin text-blue-500" />
      <span className="text-xs">Executing...</span>
    </div>
  );
  if (error) return (
    <div className="p-4">
      <div className="bg-red-500/10 border border-red-500/20 rounded-lg p-3 text-xs text-red-500 dark:text-red-400 font-mono whitespace-pre-wrap">
        {error}
      </div>
    </div>
  );
  if (!data) return (
    <div className="h-full flex flex-col items-center justify-center text-text-muted text-xs gap-2">
      <Database className="w-6 h-6 opacity-30" />
      <span>Enter a query and press <kbd className="bg-bg-hover px-1 rounded border border-border-main font-mono text-[10px]">Ctrl+Enter</kbd> or Run</span>
    </div>
  );
  if (data.length === 0) return (
    <div className="h-full flex items-center justify-center text-text-muted text-xs">
      Query returned 0 rows
    </div>
  );

  if (format === 'json') {
    return (
      <div className="h-full flex flex-col">
        <div className="flex-1 overflow-auto p-3">
          <pre className="text-[10px] font-mono text-text-main whitespace-pre-wrap">{JSON.stringify(data, null, 2)}</pre>
        </div>
        <ResultFooter data={data} cols={columns} side={side} downloadCSV={downloadCSV} copyResults={copyResults} copied={copied} />
      </div>
    );
  }

  const virtualItems = rowVirtualizer.getVirtualItems();

  return (
    <div className="h-full flex flex-col">
      <div className="shrink-0 px-2 py-1 border-b border-border-item bg-bg-header flex items-center gap-2">
        <input
          value={colSearch}
          onChange={e => setColSearch(e.target.value)}
          placeholder="Filter columns..."
          className="flex-1 px-2 py-0.5 text-[10px] bg-bg-input border border-border-input rounded outline-none focus:border-blue-500 text-text-input placeholder-slate-500"
        />
        {onToggleMaximize && (
          <button
            onClick={onToggleMaximize}
            className="p-1 text-text-muted hover:text-blue-500 rounded hover:bg-bg-hover transition-colors"
            title={isMaximized ? "Restore Editor" : "Full View Results"}
          >
            {isMaximized ? <Minimize className="w-3 h-3" /> : <Maximize className="w-3 h-3" />}
          </button>
        )}
      </div>
      <div className="flex-1 overflow-auto" ref={parentRef}>
        <table className="w-full text-left border-collapse">
          <thead className="sticky top-0 z-10 bg-bg-header border-b border-border-main">
            <tr>
              <th className="px-2 py-1.5 text-[10px] font-bold text-text-muted uppercase tracking-wider border-b border-border-main bg-bg-header w-10">#</th>
              {filteredCols.map(col => (
                <th key={col} className="px-2 py-2 text-xs font-bold text-text-muted uppercase tracking-wider border-b border-border-main border-r border-r-border-item whitespace-nowrap bg-bg-header">
                  {col}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {virtualItems.length > 0 && (
              <tr>
                <td style={{ height: `${virtualItems[0].start}px` }} colSpan={filteredCols.length + 1} />
              </tr>
            )}
            {virtualItems.map((virtualRow) => {
              const row = data[virtualRow.index];
              const i = virtualRow.index;
              return (
                <tr
                  key={i}
                  data-index={virtualRow.index}
                  ref={rowVirtualizer.measureElement}
                  className={clsx(
                    "border-b border-border-item hover:bg-bg-hover transition-colors",
                    i % 2 === 0 ? "bg-bg-main" : "bg-bg-row-alt"
                  )}
                >
                  <td className="px-2 py-1 text-[10px] text-text-muted font-mono">{i + 1}</td>
                  {filteredCols.map(col => (
                    <td key={col} className="px-2 py-1 text-[11px] font-mono text-text-main border-r border-border-item max-w-[240px] truncate">
                      {row[col] === null
                        ? <span className="text-text-muted italic">NULL</span>
                        : String(row[col])}
                    </td>
                  ))}
                </tr>
              );
            })}
            {virtualItems.length > 0 && (
              <tr>
                <td
                  style={{ height: `${rowVirtualizer.getTotalSize() - virtualItems[virtualItems.length - 1].end}px` }}
                  colSpan={filteredCols.length + 1}
                />
              </tr>
            )}
          </tbody>
        </table>
      </div>
      <ResultFooter data={data} cols={filteredCols} side={side} downloadCSV={downloadCSV} copyResults={copyResults} copied={copied} />
    </div>
  );
};

export const SidePanel = ({
  side, conn, query, setQuery, loading, results, error,
  limit, setLimit, format, setFormat,
  executeQuery, downloadCSV, copyResults, copied,
  isFullscreen, toggleFullscreen
}: any) => {
  const { tableMappings, focusedMappingId, theme } = useAppStore();
  const [resultsMaximized, setResultsMaximized] = React.useState(false);
  
  const isSource = side === 'source';
  const accent   = isSource ? 'blue' : 'emerald';
  const label    = isSource ? 'Source' : 'Target';
  
  const m = tableMappings.find((x: any) => x.id === focusedMappingId);
  const hasActiveFilters = m && (m.dateColumn || m.rowLimit || (isSource ? m.extraWhereSource : m.extraWhereTarget));

  return (
    <div className={clsx(
      "h-full flex flex-col bg-bg-panel",
      isFullscreen && "fixed inset-0 z-[120] bg-bg-main"
    )}>
      <div className="bg-bg-header border-b border-border-main px-3 py-1.5 flex flex-col shrink-0">
        <div className="flex items-center justify-between w-full">
          <div className={`flex items-center gap-2 text-xs font-bold text-${accent}-500 dark:text-${accent}-400 uppercase tracking-wider`}>
            <Database className="w-3 h-3" /> {label} Query
            {conn && (
              <span className="ml-1 text-[11px] font-normal text-text-muted normal-case">
                {conn.name} ({conn.database})
              </span>
            )}
          </div>
          <div className="flex items-center gap-2">
            <div className="flex rounded-md overflow-hidden border border-border-input text-xs">
              <button
                onClick={() => setFormat('table')}
                className={clsx("px-2.5 py-1", format === 'table' ? `bg-${accent}-500/20 text-${accent}-500` : 'text-text-muted hover:bg-bg-hover')}
              >Table</button>
              <button
                onClick={() => setFormat('json')}
                className={clsx("px-2.5 py-1", format === 'json' ? `bg-${accent}-500/20 text-${accent}-500` : 'text-text-muted hover:bg-bg-hover')}
              >JSON</button>
            </div>
            <div className="flex items-center gap-1 text-[10px] text-text-muted">
              <ChevronDown className="w-3 h-3" />
              <input
                type="number"
                value={limit}
                onChange={e => setLimit(e.target.value)}
                className="w-16 bg-bg-input border border-border-input rounded px-1.5 py-0.5 text-[10px] outline-none focus:border-blue-500 text-text-input"
              />
              <span>rows</span>
            </div>
            {results && (
              <button
                onClick={() => executeQuery(side)}
                className="p-1 rounded hover:bg-bg-hover text-text-muted hover:text-blue-500 transition-colors"
                title="Re-run"
              >
                <RefreshCw className="w-3 h-3" />
              </button>
            )}
            <button
              onClick={() => executeQuery(side)}
              disabled={!conn || !query.trim() || loading}
              className={`px-2.5 py-1 bg-${accent}-600/20 hover:bg-${accent}-600/30 text-${accent}-500 dark:text-${accent}-400 rounded text-[10px] font-medium disabled:opacity-40 flex items-center gap-1 transition-colors`}
            >
              {loading ? <Loader2 className="w-3 h-3 animate-spin" /> : <Play className="w-3 h-3 fill-current" />}
              Run
            </button>
            <button
              onClick={toggleFullscreen}
              className="p-1 text-text-muted hover:text-blue-500 rounded hover:bg-bg-hover transition-colors ml-1"
              title={isFullscreen ? "Exit Full View" : "Full View"}
            >
              {isFullscreen ? <Minimize className="w-3.5 h-3.5" /> : <Maximize className="w-3.5 h-3.5" />}
            </button>
          </div>
        </div>
        {hasActiveFilters && (
          <div className="mt-1.5 px-2 py-1 bg-amber-500/10 border border-amber-500/20 rounded text-[9px] text-amber-600 dark:text-amber-400 flex items-center gap-1.5 font-medium">
            <span className="font-bold">Filters Active:</span> 
            Date filters or row limits configured in the Data Compare view will be applied automatically when running this query.
          </div>
        )}
      </div>

      <Group orientation="vertical">
        {!resultsMaximized && (
          <>
            <Panel defaultSize={50} minSize={15}>
              <div className="h-full overflow-hidden bg-bg-editor">
                <SQLEditor
                  value={query}
                  onChange={setQuery}
                  connectionId={conn?.id}
                  onExecute={() => executeQuery(side)}
                  placeholder="SELECT * FROM table_name WHERE ..."
                  className="h-full"
                  showMaximize={false}
                />
              </div>
            </Panel>
            <Separator className="h-1 transition-all" />
          </>
        )}
        <Panel defaultSize={resultsMaximized ? 100 : 50} minSize={20}>
          <div className="h-full border-t border-border-main">
            <ResultTable 
              data={results} 
              error={error} 
              loading={loading} 
              side={side} 
              format={format} 
              downloadCSV={downloadCSV} 
              copyResults={copyResults} 
              copied={copied}
              isMaximized={resultsMaximized}
              onToggleMaximize={() => setResultsMaximized(!resultsMaximized)}
            />
          </div>
        </Panel>
      </Group>
    </div>
  );
};
