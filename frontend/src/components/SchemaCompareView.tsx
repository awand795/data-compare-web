// @ts-nocheck
import React, { useState } from 'react';
import { useAppStore } from '../store/useAppStore';
import { Table2, ArrowLeftRight, Loader2, KeyRound, AlertTriangle, CheckCircle, MinusCircle, PlusCircle, Database, Search, FileDown } from 'lucide-react';
import axios from 'axios';
import clsx from 'clsx';
import { exportToExcel, exportToPDF } from '../utils/exportUtils';

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

    const dateStr = new Date().toLocaleString();
    const connInfo = sourceConn && targetConn ? `Source: ${sourceConn.name} (${sourceConn.database}) | Target: ${targetConn.name} (${targetConn.database})` : '';

    exportToExcel({
      fileName: `schema-compare-${new Date().toISOString().slice(0, 10)}.xlsx`,
      sheets: [
        {
          sheetName: 'Summary',
          title: 'Schema Comparison Summary',
          subtitle: `${connInfo} | Generated: ${dateStr}`,
          columns: ['TableName', 'Status', 'ColumnCount'],
          data: summaryData
        },
        {
          sheetName: 'Details',
          title: 'Column Detail Differences',
          subtitle: `${connInfo} | Generated: ${dateStr}`,
          columns: ['TableName', 'ColumnName', 'Status', 'SourceType', 'TargetType', 'SourceNullable', 'TargetNullable', 'IsPK'],
          data: detailsData
        }
      ]
    });
  };

  const handleExportPDF = () => {
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

    const dateStr = new Date().toLocaleString();
    const connInfo = sourceConn && targetConn ? `Source: ${sourceConn.name} (${sourceConn.database}) | Target: ${targetConn.name} (${targetConn.database})` : '';

    exportToPDF({
      fileName: `schema-compare-${new Date().toISOString().slice(0, 10)}.pdf`,
      sheets: [
        {
          sheetName: 'Summary',
          title: 'Schema Comparison Summary',
          subtitle: `${connInfo} | Generated: ${dateStr}`,
          columns: ['TableName', 'Status', 'ColumnCount'],
          data: summaryData
        },
        {
          sheetName: 'Details',
          title: 'Column Detail Differences',
          subtitle: `${connInfo} | Generated: ${dateStr}`,
          columns: ['TableName', 'ColumnName', 'Status', 'SourceType', 'TargetType', 'SourceNullable', 'TargetNullable', 'IsPK'],
          data: detailsData
        }
      ]
    });
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
    return <span className={clsx("px-2.5 py-0.5 rounded border text-[11px] font-bold uppercase tracking-wider", styles[status] || '')}>{status.replace('_', ' ')}</span>;
  };

  return (
    <div className="flex flex-col h-full bg-bg-main text-text-main">
      {/* Connection Bar */}
      <div className="bg-bg-header border-b border-border-main px-2 sm:px-4 py-2.5 flex flex-col sm:flex-row items-start sm:items-center justify-between gap-3 shrink-0">
        <div className="flex flex-col sm:flex-row items-start sm:items-center gap-2 sm:gap-3 w-full sm:w-auto">
          <div className="flex items-center gap-2 sm:gap-3 w-full sm:w-auto">
            <div className="flex flex-col flex-1 sm:flex-none">
              <span className="text-[11px] font-bold text-blue-500 dark:text-blue-400 uppercase tracking-wider mb-0.5">Source</span>
              <select 
                className="px-3 py-2 bg-bg-input border border-border-input rounded-md text-[12px] sm:text-[13px] font-medium text-text-input w-full sm:w-40 md:w-52 focus:border-blue-500 outline-none truncate"
                value={sourceConnectionId || ''}
                onChange={e => setSourceConnectionId(e.target.value)}
              >
                <option value="">Select source...</option>
                {connections.map(c => <option key={c.id} value={c.id} className="truncate">{c.name} ({c.database})</option>)}
              </select>
            </div>
            
            <button
              onClick={() => {
                const temp = sourceConnectionId;
                setSourceConnectionId(targetConnectionId);
                setTargetConnectionId(temp);
                setSchemaResults([]);
              }}
              className="w-8 h-8 rounded-full bg-bg-panel hover:bg-bg-hover flex items-center justify-center border border-border-main mt-4 sm:mt-6 transition-colors cursor-pointer shrink-0"
              title="Swap Source and Target"
            >
              <ArrowLeftRight className="w-3.5 h-3.5 text-text-muted" />
            </button>
            
            <div className="flex flex-col flex-1 sm:flex-none">
              <span className="text-[11px] font-bold text-emerald-500 dark:text-emerald-400 uppercase tracking-wider mb-0.5">Target</span>
              <select 
                className="px-3 py-2 bg-bg-input border border-border-input rounded-md text-[12px] sm:text-[13px] font-medium text-text-input w-full sm:w-40 md:w-52 focus:border-blue-500 outline-none truncate"
                value={targetConnectionId || ''}
                onChange={e => setTargetConnectionId(e.target.value)}
              >
                <option value="">Select target...</option>
                {connections.map(c => <option key={c.id} value={c.id} className="truncate">{c.name} ({c.database})</option>)}
              </select>
            </div>
          </div>
        </div>

        <button 
          onClick={handleCompareSchema}
          disabled={!sourceConn || !targetConn || loading}
          className="px-5 py-2 bg-gradient-to-r from-purple-600 to-violet-500 hover:from-purple-500 hover:to-violet-400 text-white rounded-md flex items-center justify-center gap-2 text-sm font-bold disabled:opacity-40 shadow-lg shadow-purple-500/20 transition-all w-full sm:w-auto"
        >
          {loading ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Table2 className="w-3.5 h-3.5" />}
          {loading ? 'Comparing...' : 'Compare Schema'}
        </button>
      </div>

      {/* Summary stats bar */}
      {schemaResults.length > 0 && (
        <div className="bg-bg-row-alt border-b border-border-main px-2 sm:px-4 py-2 flex flex-col xl:flex-row items-start xl:items-center gap-3 shrink-0">
          <div className="flex flex-wrap items-center gap-1.5 sm:gap-3 w-full xl:w-auto">
            {[
              { id: 'ALL', label: 'All', count: schemaResults.length },
              { id: 'IDENTICAL', label: 'Match semua', count: statusCounts.IDENTICAL, color: 'text-emerald-600 dark:text-emerald-400' },
              { id: 'DIFFERENT', label: 'Different', count: statusCounts.DIFFERENT, color: 'text-amber-500 dark:text-amber-400' },
              { id: 'SOURCE_ONLY', label: 'Source Only', count: statusCounts.SOURCE_ONLY, color: 'text-red-500 dark:text-red-400' },
              { id: 'TARGET_ONLY', label: 'Target Only', count: statusCounts.TARGET_ONLY, color: 'text-cyan-500 dark:text-cyan-400' },
            ].map(f => (
              <button 
                key={f.id}
                onClick={() => setFilterStatus(f.id)}
                className={clsx(
                  "flex items-center gap-1.5 px-2 sm:px-3 py-1.5 rounded text-[11px] sm:text-xs font-medium transition-all flex-1 sm:flex-none justify-center sm:justify-start whitespace-nowrap",
                  filterStatus === f.id ? "bg-bg-active text-text-main shadow-inner" : "text-text-muted hover:text-text-main hover:bg-bg-hover"
                )}
              >
                <span className={f.color || ''}>{f.label}</span>
                <span className={clsx(
                  "px-1.5 py-0.5 rounded-full text-[9px] sm:text-[10px] font-bold",
                  filterStatus === f.id ? "bg-bg-panel text-text-main" : "bg-bg-active text-text-muted"
                )}>{f.count}</span>
              </button>
            ))}
          </div>
          <div className="flex-1 hidden xl:block" />
          <div className="flex items-center gap-2 w-full xl:w-auto justify-between sm:justify-start">
            <div className="relative flex-1 sm:flex-none">
              <Search className="w-3 h-3 absolute left-2 top-1/2 -translate-y-1/2 text-text-muted" />
              <input 
                value={searchTerm}
                onChange={e => setSearchTerm(e.target.value)}
                placeholder="Search tables..."
                className="pl-6 pr-2 py-1.5 sm:py-1 text-[11px] bg-bg-input border border-border-input rounded text-text-input placeholder-slate-500 w-full sm:w-40 outline-none focus:border-blue-500/50"
              />
            </div>
            <div className="flex items-center gap-2 shrink-0">
              <button
                onClick={handleExportExcel}
                className="px-3 py-1.5 bg-green-600/10 text-green-600 dark:text-green-400 hover:bg-green-600/20 border border-green-600/20 rounded-md text-xs font-semibold flex items-center gap-1.5 transition-colors"
              >
                <FileDown className="w-3.5 h-3.5" />
                <span className="hidden sm:inline">Excel</span>
              </button>
              <button
                onClick={handleExportPDF}
                className="px-3 py-1.5 bg-red-600/10 text-red-600 dark:text-red-400 hover:bg-red-600/20 border border-red-600/20 rounded-md text-xs font-semibold flex items-center gap-1.5 transition-colors"
              >
                <FileDown className="w-3.5 h-3.5" />
                <span className="hidden sm:inline">PDF</span>
              </button>
            </div>
          </div>
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
            <div className="w-px bg-border-main shrink-0" />
            {/* Table List */}
            <div className="w-80 shrink-0 border-r border-border-main overflow-y-auto bg-bg-panel">
              {filteredResults.map(r => (
                <div 
                  key={r.tableName}
                  onClick={() => setFocusedTable(r.tableName)}
                  className={clsx(
                    "flex items-center gap-2 px-4 py-2.5 cursor-pointer border-b border-border-item transition-colors border-l-[3px]",
                    focusedTable === r.tableName ? "bg-blue-500/10 dark:bg-blue-500/20 border-l-blue-500" : "hover:bg-bg-hover border-l-transparent"
                  )}
                >
                  {statusIcon(r.status)}
                  <span className="font-mono text-xs text-text-main truncate flex-1 font-medium">{r.tableName}</span>
                  <span className="text-[11px] text-text-muted">{r.columnDiffs?.length || 0} cols</span>
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
                        <tr className="text-xs text-text-muted uppercase tracking-wider border-b border-border-main">
                          <th className="px-4 py-2.5">Column</th>
                          <th className="px-4 py-2.5 text-center">Status</th>
                          <th className="px-4 py-2.5">Source Type</th>
                          <th className="px-4 py-2.5">Target Type</th>
                          <th className="px-4 py-2.5 text-center">Source Null?</th>
                          <th className="px-4 py-2.5 text-center">Target Null?</th>
                          <th className="px-4 py-2.5 text-center">PK</th>
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
                            <td className="px-4 py-2.5 font-mono text-xs text-text-main font-medium">{col.columnName}</td>
                            <td className="px-4 py-2.5 text-center">{statusBadge(col.status)}</td>
                            <td className="px-4 py-2.5 font-mono text-xs text-blue-500 dark:text-blue-400">{col.sourceType ? `${col.sourceType}(${col.sourceSize || ''})` : '—'}</td>
                            <td className="px-4 py-2.5 font-mono text-xs text-emerald-600 dark:text-emerald-400">{col.targetType ? `${col.targetType}(${col.targetSize || ''})` : '—'}</td>
                            <td className="px-4 py-2.5 text-center text-[11px]">{col.sourceNullable === 'YES' ? <span className="text-amber-500">NULL</span> : col.sourceNullable ? <span className="text-text-muted">NOT NULL</span> : '—'}</td>
                            <td className="px-4 py-2.5 text-center text-[11px]">{col.targetNullable === 'YES' ? <span className="text-amber-500">NULL</span> : col.targetNullable ? <span className="text-text-muted">NOT NULL</span> : '—'}</td>
                            <td className="px-4 py-2.5 text-center">{(col.isPrimaryKeySource || col.isPrimaryKeyTarget) && <KeyRound className="w-3 h-3 text-amber-500 dark:text-amber-400 inline" />}</td>
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
