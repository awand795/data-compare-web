// @ts-nocheck
import React, { useState, useEffect, useRef } from 'react';
import { useAppStore } from '../store/useAppStore';
import { Table as TableIcon, LayoutList, Database, Loader2, ChevronLeft, ChevronRight, AlertCircle, Search, Maximize, Minimize, Key, Link as LinkIcon, FileCode2 } from 'lucide-react';
import axios from 'axios';
import clsx from 'clsx';
import { useVirtualizer } from '@tanstack/react-virtual';
import type { ColumnDiff } from '../store/useAppStore';
import * as XLSX from 'xlsx';
import jsPDF from 'jspdf';
import autoTable from 'jspdf-autotable';

type TabType = 'data' | 'columns' | 'indexes' | 'foreign_keys' | 'ddl' | 'stats';

export const TableDetailPanel: React.FC = () => {
  const { connections, explorerConnectionId, explorerDatabaseName, explorerTableName, explorerSchemaName, defaultRowLimit } = useAppStore();
  const [activeTab, setActiveTab] = useState<TabType>('data');
  const [isFullscreen, setIsFullscreen] = useState(false);
  
  const [schemaData, setSchemaData] = useState<any[]>([]);
  const [indexesData, setIndexesData] = useState<any[]>([]);
  const [fkData, setFkData] = useState<any[]>([]);
  const [ddlData, setDdlData] = useState<string>('');
  const [statsData, setStatsData] = useState<any[]>([]);
  
  const [tableData, setTableData] = useState<Record<string, any>[]>([]);
  const [columns, setColumns] = useState<string[]>([]);
  
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Pagination State
  const [limit, setLimit] = useState<number | 'unlimited' | 'custom'>(defaultRowLimit || 100);
  const [customLimit, setCustomLimit] = useState<string>('');
  const [offset, setOffset] = useState<number>(0);

  const [hasMoreUnlimited, setHasMoreUnlimited] = useState(true);
  const [isFetchingMore, setIsFetchingMore] = useState(false);

  const parentRef = useRef<HTMLDivElement>(null);

  const rowVirtualizer = useVirtualizer({
    count: tableData.length,
    getScrollElement: () => parentRef.current,
    estimateSize: () => 32, // roughly 32px per row
    overscan: 10, // load 10 items outside of the viewport
  });

  const conn = connections.find(c => c.id === explorerConnectionId);

  useEffect(() => {
    if (conn && explorerTableName) {
      setOffset(0); // Reset offset on table change
      setColumns([]); // Reset columns on table change
      fetchData();
    }
  }, [explorerConnectionId, explorerTableName, activeTab, limit]);

  useEffect(() => {
    if (conn && explorerTableName && activeTab === 'data') {
      fetchData();
    }
  }, [offset]);

  const virtualItems = rowVirtualizer.getVirtualItems();

  useEffect(() => {
    if (limit === 'unlimited' && !loading && !isFetchingMore && hasMoreUnlimited) {
      const lastItem = virtualItems[virtualItems.length - 1];
      if (lastItem && lastItem.index >= tableData.length - 2) {
        setIsFetchingMore(true);
        fetchData(true).finally(() => setIsFetchingMore(false));
      }
    }
  }, [virtualItems, limit, loading, isFetchingMore, hasMoreUnlimited, tableData.length]);

  const fetchData = async (isLoadMore = false) => {
    if (!conn || !explorerTableName) return;
    if (!isLoadMore) setLoading(true);
    setError(null);
    try {
      const schema = explorerSchemaName || 'null';
      const database = explorerDatabaseName || conn?.database || 'null';
      
      if (activeTab === 'columns') {
        const res = await axios.get(`/api/connections/${conn.id}/databases/${database}/schemas/${schema}/tables/${explorerTableName}/columns`);
        setSchemaData(res.data);
      } else if (activeTab === 'indexes') {
        const res = await axios.get(`/api/connections/${conn.id}/databases/${database}/schemas/${schema}/tables/${explorerTableName}/indexes`);
        setIndexesData(res.data);
      } else if (activeTab === 'foreign_keys') {
        const res = await axios.get(`/api/connections/${conn.id}/databases/${database}/schemas/${schema}/tables/${explorerTableName}/foreign-keys`);
        setFkData(res.data);
      } else if (activeTab === 'ddl') {
        const res = await axios.get(`/api/connections/${conn.id}/databases/${database}/schemas/${schema}/tables/${explorerTableName}/ddl`);
        setDdlData(res.data.ddl || res.data); 
      } else if (activeTab === 'stats') {
        const res = await axios.get(`/api/connections/${conn.id}/databases/${database}/schemas/${schema}/tables/${explorerTableName}/stats`);
        setStatsData(res.data);
      } else if (activeTab === 'data') {
        const queryLimit = limit === 'unlimited' ? 50000 : (limit === 'custom' ? Number(customLimit) : limit);
        const quote = (name: string) => `"${name}"`;
        const tableNameWithSchema = explorerSchemaName ? `${quote(explorerSchemaName)}.${quote(explorerTableName)}` : quote(explorerTableName);
        
        // Use the stored connection but override its database name for browse mode
        const queryConn = { ...conn, database: explorerDatabaseName || conn.database };
        
        const response = await fetch('/api/execute-query', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            connection: queryConn,
            query: `SELECT * FROM ${tableNameWithSchema} LIMIT ${queryLimit} OFFSET ${offset}`
          })
        });

        if (!response.ok) {
          const errText = await response.text();
          throw new Error(errText || 'Query failed');
        }
        
        const reader = response.body?.getReader();
        if (!reader) throw new Error("No stream reader");
        
        const decoder = new TextDecoder();
        let buffer = '';
        let allRows: any[] = [];
        let cols: string[] = [];
        let hasError = false;

        while (true) {
          const { done, value } = await reader.read();
          if (done) break;
          
          buffer += decoder.decode(value, { stream: true });
          const lines = buffer.split('\n');
          buffer = lines.pop() || '';
          
          for (const line of lines) {
            if (!line.trim()) continue;
            try {
              const msg = JSON.parse(line);
              if (msg.type === 'error') {
                 setError(msg.message);
                 hasError = true;
              } else if (msg.type === 'columns') {
                 cols = msg.data;
                 if (offset === 0) setColumns(cols);
              } else if (msg.type === 'row') {
                 allRows.push(msg.data);
              }
            } catch (e) {
              console.error("Parse error", line, e);
            }
          }
        }
        
        if (!hasError) {
          if (offset === 0) {
            setTableData(allRows);
          } else {
            setTableData(prev => [...prev, ...allRows]);
          }
          if (allRows.length < (limit === 'unlimited' ? 5000 : Number(limit))) {
            setHasMoreUnlimited(false);
          } else {
            setHasMoreUnlimited(true);
          }
        }
      }
    } catch (err: any) {
      setError(err.message || 'An error occurred');
    } finally {
      setLoading(false);
      setIsFetchingMore(false);
    }
  };

  const handleExportExcel = () => {
    if (tableData.length === 0) return;
    const worksheet = XLSX.utils.json_to_sheet(tableData);
    const workbook = XLSX.utils.book_new();
    XLSX.utils.book_append_sheet(workbook, worksheet, "Data");
    XLSX.writeFile(workbook, `${explorerTableName}_export.xlsx`);
  };

  const handleExportPDF = () => {
    if (tableData.length === 0) return;
    const doc = new jsPDF('l', 'pt', 'a4');
    
    // Title
    doc.setFontSize(14);
    doc.setTextColor(30, 64, 175);
    doc.text(`Table: ${explorerTableName}`, 40, 30);
    doc.setFontSize(8);
    doc.setTextColor(100, 116, 139);
    doc.text(`Generated: ${new Date().toLocaleString()}`, 40, 38);
    if (conn) doc.text(`Connection: ${conn.name} (${conn.database})`, 40, 44);
    
    // Separator
    doc.setDrawColor(200, 200, 200);
    doc.line(40, 48, 770, 48);
    
    autoTable(doc, {
      head: [columns],
      body: tableData.map(row => columns.map(col => String(row[col] ?? ''))),
      startY: 52,
      styles: { fontSize: 7, cellPadding: 2, overflow: 'linebreak' },
      headStyles: { fillColor: [59, 130, 246], fontSize: 8, halign: 'center' },
      alternateRowStyles: { fillColor: [248, 250, 252] },
      theme: 'grid',
      pageBreak: 'auto',
      margin: { top: 40 },
    });
    
    // Footer
    const pageCount = doc.getNumberOfPages();
    for (let i = 1; i <= pageCount; i++) {
      doc.setPage(i);
      doc.setFontSize(7);
      doc.setTextColor(150, 150, 150);
      doc.text(`Page ${i} of ${pageCount}`, 770, 555, { align: 'right' });
      doc.text(`Data Sync Studio - ${explorerTableName}`, 40, 555);
    }
    
    doc.save(`${explorerTableName}_export.pdf`);
  };

  const handleNextPage = () => {
    if (limit !== 'unlimited') {
      setOffset(prev => prev + (limit as number));
    }
  };

  const handlePrevPage = () => {
    if (limit !== 'unlimited') {
      setOffset(prev => Math.max(0, prev - (limit as number)));
    }
  };

  const handleLimitChange = (e: React.ChangeEvent<HTMLSelectElement>) => {
    const val = e.target.value;
    if (val === 'unlimited') {
      setLimit('unlimited');
    } else if (val === 'custom') {
      setLimit('custom');
    } else {
      setLimit(Number(val));
    }
    setOffset(0);
  };

  if (!conn || !explorerTableName) {
    return (
      <div className="flex-1 flex flex-col items-center justify-center bg-bg-main text-text-muted">
        <Database className="w-12 h-12 mb-4 opacity-20" />
        <h2 className="text-xl font-bold text-text-main mb-2">Database Explorer</h2>
        <p className="text-sm">Select a table from the connection panel to view its data and schema.</p>
      </div>
    );
  }

  return (
    <div className={clsx("flex flex-col bg-bg-main overflow-hidden relative", isFullscreen ? "fixed inset-0 z-[100]" : "h-full min-h-0")}>
      {/* Header */}
      <div className="px-6 py-4 border-b border-border-main flex items-center justify-between bg-bg-panel shrink-0">
        <div className="flex items-center gap-3">
          <div className="w-10 h-10 rounded-lg bg-blue-500/10 flex items-center justify-center text-blue-500">
            <TableIcon className="w-5 h-5" />
          </div>
          <div>
            <h1 className="text-base font-bold text-text-main">{explorerTableName}</h1>
            <p className="text-[11px] text-text-muted font-mono">{conn.name} • {explorerDatabaseName || conn.database}</p>
          </div>
        </div>

        {/* Tabs */}
        <div className="flex items-center gap-3">
          <div className="flex items-center gap-1 bg-bg-input p-1 rounded-lg border border-border-input overflow-x-auto hide-scrollbar">
            {[
              { id: 'columns', label: 'Columns', icon: TableIcon },
              { id: 'indexes', label: 'Indexes', icon: Database },
              { id: 'foreign_keys', label: 'Foreign Keys', icon: LinkIcon },
              { id: 'ddl', label: 'DDL', icon: FileCode2 },
              { id: 'data', label: 'Data', icon: LayoutList },
              { id: 'stats', label: 'Statistics', icon: AlertCircle },
            ].map(tab => (
              <button
                key={tab.id}
                onClick={() => setActiveTab(tab.id as TabType)}
                className={`px-3 py-1.5 rounded-md text-xs font-semibold flex items-center gap-1.5 transition-colors whitespace-nowrap
                  ${activeTab === tab.id ? 'bg-bg-panel text-blue-500 shadow-sm border border-border-item' : 'text-text-muted hover:text-text-main'}
                `}
              >
                <tab.icon className="w-3.5 h-3.5" /> {tab.label}
              </button>
            ))}
          </div>
          
          <button
            onClick={() => setIsFullscreen(!isFullscreen)}
            className="p-2 border border-border-input bg-bg-panel hover:bg-bg-hover rounded-md text-text-muted hover:text-text-main flex items-center justify-center transition-colors ml-2"
            title={isFullscreen ? "Exit Full View" : "Full View"}
          >
            {isFullscreen ? <Minimize className="w-4 h-4" /> : <Maximize className="w-4 h-4" />}
          </button>
        </div>
      </div>

      {/* Toolbar (Only for Data Tab) */}
      {activeTab === 'data' && (
        <div className="px-4 py-2 border-b border-border-main bg-bg-panel flex items-center justify-between shrink-0">
          <div className="flex items-center gap-4">
            <div className="flex items-center gap-2 text-xs">
              <span className="text-text-muted">Limit:</span>
              <select 
                value={limit === 'unlimited' ? 'unlimited' : [50, 100, 500].includes(limit as number) ? limit : 'custom'} 
                onChange={handleLimitChange}
                className="bg-bg-input border border-border-input rounded px-2 py-1 outline-none focus:border-blue-500"
              >
                <option value={50}>50 rows</option>
                <option value={100}>100 rows</option>
                <option value={500}>500 rows</option>
                <option value="custom">Custom...</option>
                <option value="unlimited">Unlimited</option>
              </select>
              
              {(!['unlimited', 50, 100, 500].includes(limit as any) || customLimit !== '') && (
                <div className="flex items-center gap-1 ml-2">
                  <input 
                    type="number" 
                    placeholder="Enter limit" 
                    value={customLimit}
                    onChange={e => setCustomLimit(e.target.value)}
                    className="w-24 bg-bg-input border border-border-input rounded px-2 py-1 outline-none focus:border-blue-500"
                  />
                  <button 
                    onClick={() => {
                      const num = parseInt(customLimit);
                      if (num > 0) {
                        setLimit(num);
                        setOffset(0);
                      }
                    }}
                    className="px-2 py-1 bg-blue-500/10 text-blue-500 rounded hover:bg-blue-500/20"
                  >
                    Apply
                  </button>
                </div>
              )}
            </div>

            {/* Export Buttons */}
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

          {/* Pagination Controls */}
          {limit !== 'unlimited' && (
            <div className="flex items-center gap-3">
              <span className="text-xs text-text-muted">
                Showing {offset + 1} - {offset + (limit as number)}
              </span>
              <div className="flex items-center gap-1">
                <button 
                  onClick={handlePrevPage} 
                  disabled={offset === 0}
                  className="p-1 rounded bg-bg-input border border-border-input text-text-main disabled:opacity-50 disabled:cursor-not-allowed hover:bg-bg-hover"
                >
                  <ChevronLeft className="w-4 h-4" />
                </button>
                <button 
                  onClick={handleNextPage}
                  disabled={tableData.length < (limit as number)} // Disable next if current page isn't full
                  className="p-1 rounded bg-bg-input border border-border-input text-text-main disabled:opacity-50 disabled:cursor-not-allowed hover:bg-bg-hover"
                >
                  <ChevronRight className="w-4 h-4" />
                </button>
              </div>
            </div>
          )}
        </div>
      )}

      {/* Content Area */}
      <div className="flex-1 overflow-auto relative p-4">
        {loading && (
          <div className="absolute inset-0 bg-bg-main/50 backdrop-blur-sm z-10 flex flex-col items-center justify-center gap-3">
            <Loader2 className="w-8 h-8 animate-spin text-blue-500" />
            <span className="text-sm font-medium text-text-main">Executing query...</span>
          </div>
        )}
        
        {error && (
          <div className="m-4 p-4 border border-red-500/50 bg-red-500/10 rounded-lg flex items-start gap-3">
            <AlertCircle className="w-5 h-5 text-red-500 shrink-0 mt-0.5" />
            <div>
              <h3 className="text-sm font-bold text-red-500">Query Failed</h3>
              <p className="text-xs text-text-main mt-1 font-mono">{error}</p>
            </div>
          </div>
        )}

        {!loading && !error && activeTab === 'columns' && (
          <div className="border border-border-main rounded-lg overflow-hidden bg-bg-panel">
            <table className="w-full text-left border-collapse">
              <thead>
                <tr className="bg-bg-hover border-b border-border-main text-[11px] uppercase tracking-wider text-text-muted">
                  <th className="p-3 font-semibold">Column Name</th>
                  <th className="p-3 font-semibold">Data Type</th>
                  <th className="p-3 font-semibold">Size</th>
                  <th className="p-3 font-semibold">Nullable</th>
                  <th className="p-3 font-semibold">Key</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border-main">
                {schemaData.map((col: any, i) => (
                  <tr key={i} className="hover:bg-bg-hover/50 text-xs text-text-main">
                    <td className="p-3 font-mono font-medium flex items-center gap-2">
                      {col.isPk && <Key className="w-3 h-3 text-amber-500" />}
                      {col.isFk && <LinkIcon className="w-3 h-3 text-blue-400" />}
                      {col.name}
                    </td>
                    <td className="p-3 text-blue-500 dark:text-blue-400 font-mono">{col.type || '-'}</td>
                    <td className="p-3 font-mono">{col.size || '-'}</td>
                    <td className="p-3">{col.nullable === 'YES' || col.nullable === true ? 'Yes' : 'No'}</td>
                    <td className="p-3">
                      {col.isPk && <span className="inline-flex px-1.5 py-0.5 rounded bg-amber-500/10 text-amber-500 font-bold text-[10px] mr-1">PK</span>}
                      {col.isFk && <span className="inline-flex px-1.5 py-0.5 rounded bg-blue-500/10 text-blue-500 font-bold text-[10px]">FK</span>}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}

        {!loading && !error && activeTab === 'indexes' && (
          <div className="border border-border-main rounded-lg overflow-hidden bg-bg-panel">
            <table className="w-full text-left border-collapse">
              <thead>
                <tr className="bg-bg-hover border-b border-border-main text-[11px] uppercase tracking-wider text-text-muted">
                  <th className="p-3 font-semibold">Index Name</th>
                  <th className="p-3 font-semibold">Columns</th>
                  <th className="p-3 font-semibold">Unique</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border-main">
                {indexesData.map((idx: any, i) => (
                  <tr key={i} className="hover:bg-bg-hover/50 text-xs text-text-main">
                    <td className="p-3 font-mono font-medium">{idx.name}</td>
                    <td className="p-3 font-mono">{idx.columns}</td>
                    <td className="p-3">{idx.unique ? <span className="text-emerald-500">Yes</span> : 'No'}</td>
                  </tr>
                ))}
                {indexesData.length === 0 && (
                  <tr><td colSpan={3} className="p-6 text-center text-text-muted">No indexes found.</td></tr>
                )}
              </tbody>
            </table>
          </div>
        )}

        {!loading && !error && activeTab === 'foreign_keys' && (
          <div className="border border-border-main rounded-lg overflow-hidden bg-bg-panel">
            <table className="w-full text-left border-collapse">
              <thead>
                <tr className="bg-bg-hover border-b border-border-main text-[11px] uppercase tracking-wider text-text-muted">
                  <th className="p-3 font-semibold">FK Name</th>
                  <th className="p-3 font-semibold">Column</th>
                  <th className="p-3 font-semibold">Target Table</th>
                  <th className="p-3 font-semibold">Target Column</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border-main">
                {fkData.map((fk: any, i) => (
                  <tr key={i} className="hover:bg-bg-hover/50 text-xs text-text-main">
                    <td className="p-3 font-mono font-medium">{fk.name}</td>
                    <td className="p-3 font-mono">{fk.columnName}</td>
                    <td className="p-3 font-mono text-blue-500">{fk.targetTable}</td>
                    <td className="p-3 font-mono">{fk.targetColumn}</td>
                  </tr>
                ))}
                {fkData.length === 0 && (
                  <tr><td colSpan={4} className="p-6 text-center text-text-muted">No foreign keys found.</td></tr>
                )}
              </tbody>
            </table>
          </div>
        )}

        {!loading && !error && activeTab === 'ddl' && (
          <div className="border border-border-main rounded-lg overflow-hidden bg-bg-panel p-4">
            <pre className="text-xs font-mono text-blue-400 whitespace-pre-wrap">{ddlData || 'No DDL available.'}</pre>
          </div>
        )}

        {!loading && !error && activeTab === 'stats' && (
          <div className="border border-border-main rounded-lg overflow-hidden bg-bg-panel">
            <table className="w-full text-left border-collapse">
              <thead>
                <tr className="bg-bg-hover border-b border-border-main text-[11px] uppercase tracking-wider text-text-muted">
                  <th className="p-3 font-semibold">Property</th>
                  <th className="p-3 font-semibold">Value</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border-main">
                {statsData.map((stat: any, i) => (
                  <tr key={i} className="hover:bg-bg-hover/50 text-xs text-text-main">
                    <td className="p-3 font-medium capitalize">{stat.name.replace(/_/g, ' ')}</td>
                    <td className="p-3 font-mono">{stat.value}</td>
                  </tr>
                ))}
                {statsData.length === 0 && (
                  <tr><td colSpan={2} className="p-6 text-center text-text-muted">No statistics available.</td></tr>
                )}
              </tbody>
            </table>
          </div>
        )}

        {!loading && !error && activeTab === 'data' && (
          <div className="flex flex-col gap-2">
            {limit === 'unlimited' && tableData.length > 5000 && (
              <div className="bg-amber-500/10 border border-amber-500/20 text-amber-600 dark:text-amber-400 px-3 py-2 rounded text-xs flex items-center gap-2">
                <AlertCircle className="w-4 h-4 shrink-0" />
                <span>
                  <strong>Tip:</strong> You are viewing {tableData.length} rows. Scrolling is virtualized to prevent lag, but loading massive data might take a moment.
                </span>
              </div>
            )}
            {isFetchingMore && (
              <div className="text-xs text-blue-500 flex items-center gap-2 px-2">
                <Loader2 className="w-3 h-3 animate-spin" /> Fetching more data...
              </div>
            )}
            <div ref={parentRef} className="border border-border-main rounded-lg overflow-auto bg-bg-panel" style={{ maxHeight: 'calc(100vh - 200px)' }}>
              <table className="text-left border-collapse whitespace-nowrap" style={{ tableLayout: 'fixed', minWidth: '100%' }}>
                <thead className="sticky top-0 z-10 bg-bg-panel shadow-sm">
                  <tr className="border-b border-border-main text-[11px] uppercase tracking-wider text-text-muted bg-bg-hover">
                    {columns.map(col => (
                      <th key={col} className="p-2.5 font-semibold truncate border-r border-border-main last:border-r-0" style={{ width: 200, minWidth: 200, maxWidth: 200 }}>{col}</th>
                    ))}
                  </tr>
                </thead>
                <tbody className="divide-y divide-border-main">
                {tableData.length === 0 ? (
                  <tr>
                    <td colSpan={Math.max(1, columns.length)} className="p-8 text-center text-text-muted text-sm">
                      No data found.
                    </td>
                  </tr>
                ) : (
                  <>
                    {rowVirtualizer.getVirtualItems().length > 0 && (
                      <tr>
                        <td style={{ height: `${rowVirtualizer.getVirtualItems()[0].start}px` }} colSpan={columns.length} />
                      </tr>
                    )}
                    {rowVirtualizer.getVirtualItems().map((virtualRow) => {
                      const row = tableData[virtualRow.index];
                      return (
                        <tr 
                          key={virtualRow.index} 
                          data-index={virtualRow.index}
                          ref={rowVirtualizer.measureElement}
                          className="hover:bg-bg-hover/50 text-xs text-text-main"
                        >
                          {columns.map(col => {
                            const val = row[col];
                            let display = val;
                            if (val === null) display = <span className="text-text-muted italic">null</span>;
                            else if (typeof val === 'object') display = JSON.stringify(val);
                            else if (typeof val === 'boolean') display = val ? 'true' : 'false';
                            
                            return (
                              <td key={col} className="p-2.5 truncate font-mono border-r border-border-main last:border-r-0" style={{ width: 200, minWidth: 200, maxWidth: 200 }}>
                                {display}
                              </td>
                            );
                          })}
                        </tr>
                      );
                    })}
                    {rowVirtualizer.getVirtualItems().length > 0 && (
                      <tr>
                        <td 
                          style={{ height: `${rowVirtualizer.getTotalSize() - rowVirtualizer.getVirtualItems()[rowVirtualizer.getVirtualItems().length - 1].end}px` }} 
                          colSpan={columns.length} 
                        />
                      </tr>
                    )}
                  </>
                )}
              </tbody>
            </table>
          </div>
        </div>
        )}
      </div>
    </div>
  );
};
