// @ts-nocheck
import React, { useState } from 'react';
import { useAppStore } from '../store/useAppStore';
import { Table2, ArrowLeftRight, Loader2, KeyRound, AlertTriangle, CheckCircle, MinusCircle, PlusCircle, Database, Search, FileDown } from 'lucide-react';
import axios from 'axios';
import clsx from 'clsx';
import * as XLSX from 'xlsx';
import jsPDF from 'jspdf';
import autoTable from 'jspdf-autotable';

export const SchemaCompareView: React.FC = () => {
  const { connections, sourceConnectionId, setSourceConnectionId, targetConnectionId, setTargetConnectionId, schemaResults, setSchemaResults, showAlert, addToast } = useAppStore();
  const [loading, setLoading] = useState(false);
  const [focusedTable, setFocusedTable] = useState<string>('');
  const [searchTerm, setSearchTerm] = useState('');
  const [filterStatus, setFilterStatus] = useState<string>('ALL');

  const sourceConn = connections.find(c => c.id === sourceConnectionId);
  const targetConn = connections.find(c => c.id === targetConnectionId);

  // Warm up connections in the background as soon as they are selected
  React.useEffect(() => {
    if (sourceConn) {
      fetch('/api/warmup', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify([sourceConn])
      }).catch(err => console.error("Failed to trigger warmup", err));
    }
  }, [sourceConn?.id]);

  React.useEffect(() => {
    if (targetConn) {
      fetch('/api/warmup', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify([targetConn])
      }).catch(err => console.error("Failed to trigger warmup", err));
    }
  }, [targetConn?.id]);

  const handleCompareSchema = async () => {
    if (!sourceConn || !targetConn) return;
    setLoading(true);
    try {
      const res = await axios.post('/api/schema-compare-all', {
        sourceConnection: sourceConn,
        targetConnection: targetConn,
      });
      setSchemaResults(res.data);
    } catch (err: any) {
      console.error(err);
      addToast({ type: 'error', title: 'Schema Comparison Failed', message: err.response?.data?.message || err.message || 'An unexpected error occurred.' });
    } finally {
      setLoading(false);
    }
  };

  const focusedResult = schemaResults.find(r => r.tableName === focusedTable);

  const filteredResults = schemaResults.filter(r => {
    const matchSearch = !searchTerm || r.tableName.toLowerCase().includes(searchTerm.toLowerCase());
    const matchStatus = filterStatus === 'ALL' || r.status === filterStatus;
    return matchSearch && matchStatus;
  });

  const statusCounts = {
    IDENTICAL: schemaResults.filter(r => r.status === 'IDENTICAL').length,
    DIFFERENT: schemaResults.filter(r => r.status === 'DIFFERENT').length,
    SOURCE_ONLY: schemaResults.filter(r => r.status === 'SOURCE_ONLY').length,
    TARGET_ONLY: schemaResults.filter(r => r.status === 'TARGET_ONLY').length,
  };

  const handleExportExcel = () => {
    const summaryData = schemaResults.map(r => ({
      TableName: r.tableName,
      Status: r.status,
      ColumnCount: r.columnDiffs?.length || 0,
    }));

    const detailsData = schemaResults.flatMap(r =>
      (r.columnDiffs || []).map(col => ({
        TableName: r.tableName,
        ColumnName: col.columnName,
        Status: col.status,
        SourceType: col.sourceType ? `${col.sourceType}(${col.sourceSize || ''})` : '',
        TargetType: col.targetType ? `${col.targetType}(${col.targetSize || ''})` : '',
        SourceNullable: col.sourceNullable || '',
        TargetNullable: col.targetNullable || '',
        IsPK: (col.isPrimaryKeySource || col.isPrimaryKeyTarget) ? 'YES' : 'NO',
      }))
    );

    const wb = XLSX.utils.book_new();
    const wsSummary = XLSX.utils.json_to_sheet(summaryData);
    XLSX.utils.book_append_sheet(wb, wsSummary, 'Summary');
    const wsDetails = XLSX.utils.json_to_sheet(detailsData);
    XLSX.utils.book_append_sheet(wb, wsDetails, 'Details');
    XLSX.writeFile(wb, `schema-compare-${new Date().toISOString().slice(0, 10)}.xlsx`);
  };

  const handleExportPDF = () => {
    const doc = new jsPDF({ orientation: 'landscape', format: 'a4' });
    const dateStr = new Date().toLocaleString();
    const fileName = `schema-compare-${new Date().toISOString().slice(0, 10)}`;

    // ── Page 1: Summary ──
    doc.setFontSize(16);
    doc.setTextColor(30, 64, 175);
    doc.text('Schema Comparison Report', 14, 18);
    doc.setFontSize(8);
    doc.setTextColor(100, 116, 139);
    doc.text(`Generated: ${dateStr}`, 14, 25);
    if (sourceConn && targetConn) {
      doc.text(`Source: ${sourceConn.name} (${sourceConn.database})  →  Target: ${targetConn.name} (${targetConn.database})`, 14, 31);
    }
    doc.setDrawColor(200, 200, 200);
    doc.line(14, 34, 810, 34);

    const summaryRows = schemaResults.map(r => [
      r.tableName,
      r.status,
      String(r.columnDiffs?.length || 0),
    ]);

    autoTable(doc, {
      startY: 38,
      head: [['Table Name', 'Status', 'Column Changes']],
      body: summaryRows,
      styles: { fontSize: 8, cellPadding: 3 },
      headStyles: { fillColor: [59, 130, 246], fontSize: 9, halign: 'center' },
      alternateRowStyles: { fillColor: [248, 250, 252] },
      theme: 'grid',
    });

    // ── Page 2: Details ──
    doc.addPage();
    doc.setFontSize(14);
    doc.setTextColor(30, 64, 175);
    doc.text('Column Detail Differences', 14, 18);
    doc.setFontSize(8);
    doc.setTextColor(100, 116, 139);
    doc.text(`Generated: ${dateStr}`, 14, 25);
    doc.setDrawColor(200, 200, 200);
    doc.line(14, 28, 810, 28);

    const detailRows = schemaResults.flatMap(r =>
      (r.columnDiffs || []).map(col => [
        r.tableName,
        col.columnName,
        col.status,
        col.sourceType ? `${col.sourceType}${col.sourceSize != null ? '(' + col.sourceSize + ')' : ''}` : '-',
        col.targetType ? `${col.targetType}${col.targetSize != null ? '(' + col.targetSize + ')' : ''}` : '-',
        col.sourceNullable === 'YES' ? 'NULL' : (col.sourceNullable != null ? 'NOT NULL' : '-'),
        col.targetNullable === 'YES' ? 'NULL' : (col.targetNullable != null ? 'NOT NULL' : '-'),
        (col.isPrimaryKeySource || col.isPrimaryKeyTarget) ? 'YES' : 'NO',
      ])
    );

    autoTable(doc, {
      startY: 32,
      head: [['Table', 'Column', 'Status', 'Source Type', 'Target Type', 'Src Null?', 'Tgt Null?', 'PK']],
      body: detailRows,
      styles: { fontSize: 7, cellPadding: 2 },
      headStyles: { fillColor: [59, 130, 246], fontSize: 7 },
      alternateRowStyles: { fillColor: [248, 250, 252] },
      theme: 'grid',
    });

    // ── Page numbers ──
    const pageCount = doc.getNumberOfPages();
    for (let i = 1; i <= pageCount; i++) {
      doc.setPage(i);
      doc.setFontSize(7);
      doc.setTextColor(150, 150, 150);
      doc.text(`Page ${i} of ${pageCount}`, 810, 555, { align: 'right' });
      doc.text(`Schema Compare - ${fileName}`, 14, 555);
    }

    doc.save(`${fileName}.pdf`);
  };

  const statusIcon = (status: string) => {
    switch (status) {
      case 'IDENTICAL': return <CheckCircle className="w-3.5 h-3.5 text-emerald-500 dark:text-emerald-400" />;
      case 'DIFFERENT': return <AlertTriangle className="w-3.5 h-3.5 text-amber-500 dark:text-amber-400" />;
      case 'SOURCE_ONLY': return <MinusCircle className="w-3.5 h-3.5 text-red-500 dark:text-red-400" />;
      case 'TARGET_ONLY': return <PlusCircle className="w-3.5 h-3.5 text-cyan-500 dark:text-cyan-400" />;
      default: return null;
    }
  };

  const statusBadge = (status: string) => {
    const styles: Record<string, string> = {
      IDENTICAL: 'bg-emerald-500/10 text-emerald-600 dark:text-emerald-400 border-emerald-500/20',
      DIFFERENT: 'bg-amber-500/10 text-amber-600 dark:text-amber-400 border-amber-500/20',
      SOURCE_ONLY: 'bg-red-500/10 text-red-500 dark:text-red-400 border-red-500/20',
      TARGET_ONLY: 'bg-cyan-500/10 text-cyan-600 dark:text-cyan-400 border-cyan-500/20',
    };
    return <span className={clsx("px-2 py-0.5 rounded border text-[9px] font-bold uppercase tracking-wider", styles[status] || '')}>{status.replace('_', ' ')}</span>;
  };

  return (
    <div className="flex flex-col h-full bg-bg-main text-text-main">
      {/* Connection Bar */}
      <div className="bg-bg-header border-b border-border-main px-4 py-2.5 flex items-center justify-between shrink-0">
        <div className="flex items-center gap-3">
          <div className="flex flex-col">
            <span className="text-[9px] font-bold text-blue-500 dark:text-blue-400 uppercase tracking-widest mb-0.5">Source</span>
            <select 
              className="px-2.5 py-1.5 bg-bg-input border border-border-input rounded-md text-xs font-medium text-text-input w-52 focus:border-blue-500 outline-none"
              value={sourceConnectionId || ''}
              onChange={e => setSourceConnectionId(e.target.value)}
            >
              <option value="">Select source...</option>
              {connections.map(c => <option key={c.id} value={c.id}>{c.name} ({c.database})</option>)}
            </select>
          </div>
          
          <div className="w-8 h-8 rounded-full bg-bg-panel flex items-center justify-center border border-border-main mt-3">
            <ArrowLeftRight className="w-3.5 h-3.5 text-text-muted" />
          </div>
          
          <div className="flex flex-col">
            <span className="text-[9px] font-bold text-emerald-500 dark:text-emerald-400 uppercase tracking-widest mb-0.5">Target</span>
            <select 
              className="px-2.5 py-1.5 bg-bg-input border border-border-input rounded-md text-xs font-medium text-text-input w-52 focus:border-blue-500 outline-none"
              value={targetConnectionId || ''}
              onChange={e => setTargetConnectionId(e.target.value)}
            >
              <option value="">Select target...</option>
              {connections.map(c => <option key={c.id} value={c.id}>{c.name} ({c.database})</option>)}
            </select>
          </div>
        </div>

        <button 
          onClick={handleCompareSchema}
          disabled={!sourceConn || !targetConn || loading}
          className="px-4 py-1.5 bg-gradient-to-r from-purple-600 to-violet-500 hover:from-purple-500 hover:to-violet-400 text-white rounded-md flex items-center gap-2 text-xs font-bold disabled:opacity-40 shadow-lg shadow-purple-500/20 transition-all"
        >
          {loading ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Table2 className="w-3.5 h-3.5" />}
          {loading ? 'Comparing...' : 'Compare Schema'}
        </button>
      </div>

      {/* Summary stats bar */}
      {schemaResults.length > 0 && (
        <div className="bg-bg-row-alt border-b border-border-main px-4 py-2 flex items-center gap-3 shrink-0">
          {[
            { id: 'ALL', label: 'All', count: schemaResults.length },
            { id: 'IDENTICAL', label: 'Identical', count: statusCounts.IDENTICAL, color: 'text-emerald-600 dark:text-emerald-400' },
            { id: 'DIFFERENT', label: 'Different', count: statusCounts.DIFFERENT, color: 'text-amber-500 dark:text-amber-400' },
            { id: 'SOURCE_ONLY', label: 'Source Only', count: statusCounts.SOURCE_ONLY, color: 'text-red-500 dark:text-red-400' },
            { id: 'TARGET_ONLY', label: 'Target Only', count: statusCounts.TARGET_ONLY, color: 'text-cyan-500 dark:text-cyan-400' },
          ].map(f => (
            <button 
              key={f.id}
              onClick={() => setFilterStatus(f.id)}
              className={clsx(
                "flex items-center gap-1.5 px-2.5 py-1 rounded text-[10px] font-medium transition-all",
                filterStatus === f.id ? "bg-bg-active text-text-main shadow-inner" : "text-text-muted hover:text-text-main hover:bg-bg-hover"
              )}
            >
              <span className={f.color || ''}>{f.label}</span>
              <span className={clsx(
                "px-1.5 py-0.5 rounded-full text-[9px] font-bold",
                filterStatus === f.id ? "bg-bg-panel text-text-main" : "bg-bg-active text-text-muted"
              )}>{f.count}</span>
            </button>
          ))}
          <div className="flex-1" />
          <div className="relative">
            <Search className="w-3 h-3 absolute left-2 top-1/2 -translate-y-1/2 text-text-muted" />
            <input 
              value={searchTerm}
              onChange={e => setSearchTerm(e.target.value)}
              placeholder="Search tables..."
              className="pl-6 pr-2 py-1 text-[10px] bg-bg-input border border-border-input rounded text-text-input placeholder-slate-500 w-40 outline-none focus:border-blue-500/50"
            />
          </div>
          <button
            onClick={handleExportExcel}
            className="px-3 py-1.5 bg-green-600/10 text-green-600 dark:text-green-400 hover:bg-green-600/20 border border-green-600/20 rounded-md text-xs font-semibold flex items-center gap-1.5 transition-colors"
          >
            <FileDown className="w-3.5 h-3.5" />
            Excel
          </button>
          <button
            onClick={handleExportPDF}
            className="px-3 py-1.5 bg-red-600/10 text-red-600 dark:text-red-400 hover:bg-red-600/20 border border-red-600/20 rounded-md text-xs font-semibold flex items-center gap-1.5 transition-colors"
          >
            <FileDown className="w-3.5 h-3.5" />
            PDF
          </button>
        </div>
      )}

      {/* Content */}
      <div className="flex-1 flex min-h-0 overflow-hidden bg-bg-main">
        {schemaResults.length === 0 ? (
          <div className="flex-1 flex flex-col items-center justify-center text-text-muted gap-3">
            <Table2 className="w-10 h-10 text-text-muted opacity-50" />
            <span className="text-xs text-center">Select source & target connections,<br/>then click <strong className="text-text-main">Compare Schema</strong></span>
          </div>
        ) : (
          <>
            {/* Table List */}
            <div className="w-72 shrink-0 border-r border-border-main overflow-y-auto bg-bg-panel">
              {filteredResults.map(r => (
                <div 
                  key={r.tableName}
                  onClick={() => setFocusedTable(r.tableName)}
                  className={clsx(
                    "flex items-center gap-2 px-3 py-2 cursor-pointer border-b border-border-item transition-colors border-l-2",
                    focusedTable === r.tableName ? "bg-blue-500/10 dark:bg-blue-500/20 border-l-blue-500" : "hover:bg-bg-hover border-l-transparent"
                  )}
                >
                  {statusIcon(r.status)}
                  <span className="font-mono text-[11px] text-text-main truncate flex-1 font-medium">{r.tableName}</span>
                  <span className="text-[9px] text-text-muted">{r.columnDiffs?.length || 0} cols</span>
                </div>
              ))}
            </div>

            {/* Column Detail */}
            <div className="flex-1 overflow-auto bg-bg-main">
              {focusedResult ? (
                <div className="flex flex-col h-full bg-bg-panel">
                  <div className="bg-bg-header border-b border-border-main px-4 py-2 flex items-center gap-3 shrink-0">
                    <Database className="w-4 h-4 text-text-muted" />
                    <span className="font-mono text-sm text-text-main font-semibold">{focusedResult.tableName}</span>
                    {statusBadge(focusedResult.status)}
                  </div>
                  <div className="flex-1 overflow-auto">
                    <table className="w-full text-left">
                      <thead className="sticky top-0 z-10 bg-bg-header border-b border-border-main">
                        <tr className="text-[10px] text-text-muted uppercase tracking-wider border-b border-border-main">
                          <th className="px-3 py-2">Column</th>
                          <th className="px-3 py-2 text-center">Status</th>
                          <th className="px-3 py-2">Source Type</th>
                          <th className="px-3 py-2">Target Type</th>
                          <th className="px-3 py-2 text-center">Source Null?</th>
                          <th className="px-3 py-2 text-center">Target Null?</th>
                          <th className="px-3 py-2 text-center">PK</th>
                        </tr>
                      </thead>
                      <tbody>
                        {focusedResult.columnDiffs?.map((col, i) => (
                          <tr 
                            key={col.columnName} 
                            className={clsx(
                              "border-b border-border-item text-xs transition-colors",
                              i % 2 === 0 ? "bg-bg-main" : "bg-bg-row-alt",
                              col.status === 'DIFFERENT' && "bg-amber-500/[0.03] dark:bg-amber-500/[0.05]",
                              col.status === 'SOURCE_ONLY' && "bg-red-500/[0.03] dark:bg-red-500/[0.05]",
                              col.status === 'TARGET_ONLY' && "bg-cyan-500/[0.03] dark:bg-cyan-500/[0.05]"
                            )}
                          >
                            <td className="px-3 py-2 font-mono text-[11px] text-text-main font-medium">{col.columnName}</td>
                            <td className="px-3 py-2 text-center">{statusBadge(col.status)}</td>
                            <td className="px-3 py-2 font-mono text-[11px] text-blue-500 dark:text-blue-400">{col.sourceType ? `${col.sourceType}(${col.sourceSize || ''})` : '—'}</td>
                            <td className="px-3 py-2 font-mono text-[11px] text-emerald-600 dark:text-emerald-400">{col.targetType ? `${col.targetType}(${col.targetSize || ''})` : '—'}</td>
                            <td className="px-3 py-2 text-center text-[10px]">{col.sourceNullable === 'YES' ? <span className="text-amber-500">NULL</span> : col.sourceNullable ? <span className="text-text-muted">NOT NULL</span> : '—'}</td>
                            <td className="px-3 py-2 text-center text-[10px]">{col.targetNullable === 'YES' ? <span className="text-amber-500">NULL</span> : col.targetNullable ? <span className="text-text-muted">NOT NULL</span> : '—'}</td>
                            <td className="px-3 py-2 text-center">{(col.isPrimaryKeySource || col.isPrimaryKeyTarget) && <KeyRound className="w-3 h-3 text-amber-500 dark:text-amber-400 inline" />}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                </div>
              ) : (
                <div className="h-full flex items-center justify-center text-xs text-text-muted bg-bg-panel">
                  Click a table on the left to view column details
                </div>
              )}
            </div>
          </>
        )}
      </div>
    </div>
  );
};
