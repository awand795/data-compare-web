// @ts-nocheck
import React, { useState, useEffect } from 'react';
import { useAppStore } from '../store/useAppStore';
import {
  Play, ArrowLeftRight, Loader2, Database, Copy, Check,
  Download, RefreshCw, ChevronDown, Maximize, Minimize
} from 'lucide-react';
import { Panel, Group, Separator } from 'react-resizable-panels';
import axios from 'axios';
import clsx from 'clsx';
import { buildEffectiveQuery } from '../utils/queryHelpers';
import { DiffDataGrid } from './DiffDataGrid';
import { SidePanel } from './QueryWorkspaceComponents';

export const QueryWorkspace: React.FC = () => {
  const {
    connections,
    sourceConnectionId, setSourceConnectionId,
    targetConnectionId, setTargetConnectionId,
    customQuerySource: sourceQuery, setCustomQuerySource: setSourceQuery,
    customQueryTarget: targetQuery, setCustomQueryTarget: setTargetQuery,
    focusedMappingId,
    tableMappings,
    defaultRowLimit,
    showAlert,
  } = useAppStore();

  const [sourceResults, setSourceResults] = useState<any[] | null>(null);
  const [targetResults, setTargetResults] = useState<any[] | null>(null);
  const [sourceError, setSourceError]     = useState('');
  const [targetError, setTargetError]     = useState('');
  const [loadingSource, setLoadingSource] = useState(false);
  const [loadingTarget, setLoadingTarget] = useState(false);
  const [copied, setCopied]               = useState<'source' | 'target' | null>(null);
  const [sourceLimit, setSourceLimit]     = useState(defaultRowLimit.toString());
  const [targetLimit, setTargetLimit]     = useState(defaultRowLimit.toString());
  const [sourceFormat, setSourceFormat]   = useState<'table' | 'json'>('table');
  const [targetFormat, setTargetFormat]   = useState<'table' | 'json'>('table');

  const [viewMode, setViewMode] = useState<'results' | 'diff'>('results');
  const [workspaceDiffResult, setWorkspaceDiffResult] = useState<any>(null);
  const [comparing, setComparing] = useState(false);
  const [filterStatus, setFilterStatus] = useState<'ALL' | 'DIFFERENT' | 'SOURCE_ONLY' | 'TARGET_ONLY' | 'IDENTICAL'>('ALL');
  const [isFullscreen, setIsFullscreen] = useState(false);
  const [returnMatchedRows, setReturnMatchedRows] = useState(true);

  const sourceConn = connections.find(c => c.id === sourceConnectionId);
  const targetConn = connections.find(c => c.id === targetConnectionId);

  // Warm up connections in the background as soon as they are selected
  useEffect(() => {
    if (sourceConn) {
      fetch('http://localhost:8081/api/warmup', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify([sourceConn])
      }).catch(err => console.error("Failed to trigger warmup", err));
    }
  }, [sourceConn?.id]);

  useEffect(() => {
    if (targetConn) {
      fetch('http://localhost:8081/api/warmup', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify([targetConn])
      }).catch(err => console.error("Failed to trigger warmup", err));
    }
  }, [targetConn?.id]);

  useEffect(() => {
    if (!focusedMappingId) return;
    const m = tableMappings.find(x => x.id === focusedMappingId);
    if (!m) return;
    const sq = m.customQuerySource ?? (m.sourceTable ? `SELECT * FROM ${m.sourceTable}` : '');
    const tq = m.customQueryTarget ?? (m.targetTable ? `SELECT * FROM ${m.targetTable}` : '');
    if (sq) setSourceQuery(sq);
    if (tq) setTargetQuery(tq);
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [focusedMappingId]);

  const applyLimit = (query: string, limit: string): string => {
    const n = parseInt(limit);
    if (!n || n <= 0) return query;
    const trimmed = query.trim().replace(/;$/, '');
    if (/limit\s+\d+\s*$/i.test(trimmed)) return trimmed;
    return `${trimmed} LIMIT ${n}`;
  };

  const applyWhere = (query: string, extraWhere: string | undefined): string => {
    if (!extraWhere) return query;
    const trimmed = query.trim().replace(/;$/, '');
    if (/\bWHERE\b/i.test(trimmed)) {
      // If there's an existing WHERE or GROUP BY / ORDER BY / LIMIT, we ideally parse AST.
      // But for simplicity in custom queries, we assume simple SELECT * FROM X WHERE ...
      // If there's a LIMIT at the end, we need to inject WHERE before LIMIT.
      let q = trimmed;
      let limitPart = '';
      const limitMatch = q.match(/\bLIMIT\s+\d+\s*$/i);
      if (limitMatch) {
        limitPart = limitMatch[0];
        q = q.substring(0, limitMatch.index);
      }
      return `${q} AND (${extraWhere}) ${limitPart}`.trim();
    }
    
    // Inject WHERE
    let q = trimmed;
    let limitPart = '';
    const limitMatch = q.match(/\bLIMIT\s+\d+\s*$/i);
    if (limitMatch) {
      limitPart = limitMatch[0];
      q = q.substring(0, limitMatch.index);
    }
    return `${q} WHERE ${extraWhere} ${limitPart}`.trim();
  };

  const executeQuery = async (side: 'source' | 'target') => {
    const isSource = side === 'source';
    const conn = isSource ? sourceConn : targetConn;
    const rawQuery = isSource ? sourceQuery : targetQuery;
    const limit = isSource ? sourceLimit : targetLimit;
    const setLoading = isSource ? setLoadingSource : setLoadingTarget;
    const setResults = isSource ? setSourceResults : setTargetResults;
    const setError = isSource ? setSourceError : setTargetError;

    if (!conn || !rawQuery.trim()) return;

    setLoading(true);
    setError('');
    
    let finalQuery = applyLimit(rawQuery, limit);

    // Apply data compare filters if a mapping is active
    const m = tableMappings.find(x => x.id === focusedMappingId);
    if (m) {
      if (m.dateColumn && (m.startDate || m.endDate)) {
        let dateWhere = [];
        if (m.startDate) dateWhere.push(`${m.dateColumn} >= '${m.startDate}'`);
        if (m.endDate) dateWhere.push(`${m.dateColumn} <= '${m.endDate}'`);
        finalQuery = applyWhere(finalQuery, dateWhere.join(' AND '));
      }
      
      const extraWhere = isSource ? m.extraWhereSource : m.extraWhereTarget;
      if (extraWhere) {
        finalQuery = applyWhere(finalQuery, extraWhere);
      }

      if (m.rowLimit && m.rowLimit > 0) {
        finalQuery = applyLimit(finalQuery, m.rowLimit.toString());
      }
    }

    try {
      setResults([]); // Start with empty results to show incoming rows
      const response = await fetch('http://localhost:8081/api/execute-query', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ connection: conn, query: finalQuery })
      });
      
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      const reader = response.body?.getReader();
      if (!reader) throw new Error("No reader");

      const decoder = new TextDecoder();
      let buffer = '';
      let allRows: any[] = [];

      let batchTimer: any = null;
      let pendingBatch: any[] = [];

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
            } else if (msg.type === 'row') {
               allRows.push(msg.data);
               pendingBatch.push(msg.data);
            }
          } catch (e) {
            console.error("Parse error", line, e);
          }
        }
        
        if (pendingBatch.length > 0 && !batchTimer) {
           batchTimer = setTimeout(() => {
             setResults(allRows);
             pendingBatch = [];
             batchTimer = null;
           }, 200);
        }
      }
      
      if (pendingBatch.length > 0) {
         if (batchTimer) clearTimeout(batchTimer);
         setResults([...allRows]);
      }
    } catch (err: any) {
      setError(err.message);
    } finally {
      setLoading(false);
    }
  };

  const handleCompare = async () => {
    if (!sourceConn || !targetConn || !sourceQuery.trim() || !targetQuery.trim()) return;
    setComparing(true);
    setViewMode('diff');
    setWorkspaceDiffResult({ columns: [], rows: [], summary: null, status: 'comparing' });
    
    let sqFinal = sourceQuery;
    let tqFinal = targetQuery;
    let pks = null;
    let excl = null;

    const m = tableMappings.find(x => x.id === focusedMappingId);
    if (m) {
      sqFinal = buildEffectiveQuery(m.sourceTable, m, 'source') || sqFinal;
      tqFinal = buildEffectiveQuery(m.targetTable, m, 'target') || tqFinal;
      pks = m.primaryKeys;
      excl = m.excludeColumns;
    }

    try {
      const response = await fetch('http://localhost:8081/api/compare', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          sourceConnection: sourceConn,
          targetConnection: targetConn,
          tableName: m?.sourceTable || null,
          customQuerySource: sqFinal,
          customQueryTarget: tqFinal,
          primaryKeys: pks || null,
          excludeColumns: excl || null,
          sortColumns: m?.sortColumns || null,
            returnMatchedRows,
        })
      });

      if (!response.ok) {
        let errStr = `HTTP ${response.status}`;
        try {
            const errBody = await response.json();
            if (errBody.message) errStr = errBody.message;
        } catch(e) {
            try {
                const text = await response.text();
                if (text) errStr = text;
            } catch (e2) {}
        }
        throw new Error(errStr);
      }
      const reader = response.body?.getReader();
      if (!reader) throw new Error("No reader");

      const decoder = new TextDecoder();
      let buffer = '';

      let batchTimer: any = null;
      let pendingColumns: string[] | null = null;
      let pendingRows: any[] = [];
      let pendingSummary: any = null;
      
      let allRows: any[] = [];
      let finalColumns: string[] = [];
      let counters = { match: 0, different: 0, sourceOnly: 0, targetOnly: 0 };
      let finalSummary: any = null;

      const flush = () => {
        setWorkspaceDiffResult({
          columns: finalColumns,
          rows: [...allRows],
          summary: finalSummary,
          counters: { ...counters },
          status: 'comparing'
        });
        pendingColumns = null;
        pendingRows = [];
        pendingSummary = null;
      };

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
            if (msg.type === 'columns') {
               pendingColumns = msg.data;
               finalColumns = msg.data;
            }
              else if (msg.type === 'row') {
                 pendingRows.push(msg.data);
                 allRows.push(msg.data);
                 if (msg.data.status === 'MATCH') counters.match++;
                 else if (msg.data.status === 'DIFFERENT') counters.different++;
                 else if (msg.data.status === 'SOURCE_ONLY') counters.sourceOnly++;
                 else if (msg.data.status === 'TARGET_ONLY') counters.targetOnly++;
              }
            else if (msg.type === 'summary') {
               pendingSummary = msg.data;
               finalSummary = msg.data;
            }
          } catch (e) {
            console.error("Parse error", line, e);
          }
        }
        
        if ((pendingColumns || pendingRows.length > 0 || pendingSummary) && !batchTimer) {
           batchTimer = setTimeout(() => {
             flush();
             batchTimer = null;
           }, 200);
        }
      }
      
      if (batchTimer) clearTimeout(batchTimer);
      flush();

      if (buffer.trim()) {
        try {
          const msg = JSON.parse(buffer.trim());
          if (msg.type === 'summary') {
            finalSummary = msg.data;
            setWorkspaceDiffResult({
              columns: finalColumns,
              rows: [...allRows], // Final immutable copy for rendering the grid
              summary: finalSummary,
              counters: { ...counters },
              status: 'done'
            });
            if (batchTimer) { clearTimeout(batchTimer); batchTimer = null; }
          }
        } catch (e) {}
      }
      
      setWorkspaceDiffResult({
        columns: finalColumns,
        rows: allRows,
        summary: finalSummary,
        status: 'done'
      });
      
    } catch (err: any) {
      showAlert({
        title: 'Comparison Failed',
        message: err.message || 'An unexpected error occurred.',
        type: 'error',
        details: err.stack || String(err)
      });
      setWorkspaceDiffResult((prev: any) => ({ ...prev, status: 'error' }));
    } finally {
      setComparing(false);
    }
  };

  const executeBoth = () => {
    if (sourceConn && sourceQuery.trim()) executeQuery('source');
    if (targetConn && targetQuery.trim()) executeQuery('target');
  };

  const copyResults = (side: 'source' | 'target') => {
    const data = side === 'source' ? sourceResults : targetResults;
    if (!data) return;
    navigator.clipboard.writeText(JSON.stringify(data, null, 2));
    setCopied(side);
    setTimeout(() => setCopied(null), 2000);
  };

  const downloadCSV = (side: 'source' | 'target') => {
    const data = side === 'source' ? sourceResults : targetResults;
    if (!data || data.length === 0) return;
    const cols = Object.keys(data[0]);
    const rows = [cols.join(','), ...data.map(r =>
      cols.map(c => {
        const v = r[c] === null ? '' : String(r[c]);
        return v.includes(',') || v.includes('"') ? `"${v.replace(/"/g, '""')}"` : v;
      }).join(',')
    )];
    const blob = new Blob([rows.join('\n')], { type: 'text/csv' });
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = `${side}_result.csv`;
    a.click();
  };

  return (
    <div className={clsx("flex flex-col bg-bg-main text-text-main", isFullscreen ? "fixed inset-0 z-[100]" : "h-full min-h-0")}>
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

        <div className="flex items-center gap-2">
          <div className="flex rounded overflow-hidden border border-border-input text-[10px] mr-2">
            <button
              onClick={() => setViewMode('results')}
              className={clsx("px-2 py-1 transition-colors", viewMode === 'results' ? "bg-blue-500/20 text-blue-500" : "text-text-muted hover:bg-bg-hover")}
            >
              Side-by-Side
            </button>
          <button
            onClick={() => setViewMode('diff')}
            className={clsx("px-2 py-1 transition-colors", viewMode === 'diff' ? "bg-amber-500/20 text-amber-500 dark:text-amber-400" : "text-text-muted hover:bg-bg-hover")}
          >
            Compare Diff
          </button>
        </div>
        
        <button
          onClick={() => setIsFullscreen(!isFullscreen)}
          className="px-2 py-1 border border-border-input bg-bg-panel hover:bg-bg-hover rounded-md text-text-muted hover:text-text-main flex items-center justify-center transition-colors mr-2"
          title={isFullscreen ? "Exit Full View" : "Full View"}
        >
          {isFullscreen ? <Minimize className="w-3.5 h-3.5" /> : <Maximize className="w-3.5 h-3.5" />}
        </button>

        <div className="flex items-center gap-2 mr-1">
          <input
            type="checkbox"
            id="returnMatchedRowsQw"
            checked={!returnMatchedRows}
            onChange={e => setReturnMatchedRows(!e.target.checked)}
            className="w-3 h-3 rounded border-border-input bg-bg-panel text-amber-500 focus:ring-amber-500 focus:ring-offset-bg-header"
          />
          <label htmlFor="returnMatchedRowsQw" className="text-[10px] text-text-muted cursor-pointer hover:text-text-main select-none font-medium">
            Only Diff
          </label>
        </div>

        <button
          onClick={handleCompare}
            disabled={!sourceConn || !targetConn || !sourceQuery.trim() || !targetQuery.trim() || comparing}
            className="px-3 py-1 bg-amber-500 hover:bg-amber-600 text-white rounded text-[10px] font-bold uppercase tracking-wide disabled:opacity-40 transition-colors flex items-center gap-1.5 shadow-sm"
          >
            {comparing ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Play className="w-3.5 h-3.5 fill-current" />}
            Compare
          </button>
          <button
            onClick={executeBoth}
            disabled={(!sourceConn || !sourceQuery.trim()) && (!targetConn || !targetQuery.trim())}
            className="px-4 py-1.5 bg-gradient-to-r from-amber-600 to-orange-500 hover:from-amber-500 hover:to-orange-400 text-white rounded-md flex items-center gap-2 text-xs font-bold disabled:opacity-40 shadow-lg shadow-amber-500/20 transition-all"
          >
            <Play className="w-3.5 h-3.5 fill-current" />
            Execute Both
          </button>
        </div>
      </div>

      <div className="flex-1 min-h-0">
        {viewMode === 'results' ? (
          <Group orientation="horizontal">
            <Panel defaultSize={50} minSize={25}>
              <SidePanel
                side="source"
                conn={sourceConn}
                query={sourceQuery}
                setQuery={setSourceQuery}
                loading={loadingSource}
                results={sourceResults}
                error={sourceError}
                limit={sourceLimit}
                setLimit={setSourceLimit}
                format={sourceFormat}
                setFormat={setSourceFormat}
                executeQuery={executeQuery}
                downloadCSV={downloadCSV}
                copyResults={copyResults}
                copied={copied}
              />
            </Panel>
            <Separator className="w-1 bg-border-main hover:bg-blue-500/50 transition-colors cursor-col-resize flex items-center justify-center">
              <div className="h-8 w-0.5 bg-border-item rounded-full" />
            </Separator>
            <Panel defaultSize={50} minSize={25}>
              <SidePanel
                side="target"
                conn={targetConn}
                query={targetQuery}
                setQuery={setTargetQuery}
                loading={loadingTarget}
                results={targetResults}
                error={targetError}
                limit={targetLimit}
                setLimit={setTargetLimit}
                format={targetFormat}
                setFormat={setTargetFormat}
                executeQuery={executeQuery}
                downloadCSV={downloadCSV}
                copyResults={copyResults}
                copied={copied}
              />
            </Panel>
          </Group>
        ) : (
          <div className="h-full bg-bg-panel flex flex-col p-2 min-h-0">
            {workspaceDiffResult && (
              <div className="flex bg-bg-input p-1 rounded-md mb-2 shrink-0 border border-border-input overflow-x-auto">
                {[
                      { id: 'ALL', label: 'All', count: workspaceDiffResult?.rows?.length || 0, color: 'text-text-main' },
                      { id: 'DIFFERENT', label: 'Different', count: workspaceDiffResult?.counters?.different || 0, color: 'text-amber-500 dark:text-amber-400' },
                      { id: 'SOURCE_ONLY', label: 'Src Only', count: workspaceDiffResult?.counters?.sourceOnly || 0, color: 'text-red-500 dark:text-red-400' },
                      { id: 'TARGET_ONLY', label: 'Tgt Only', count: workspaceDiffResult?.counters?.targetOnly || 0, color: 'text-emerald-600 dark:text-emerald-400' },
                      { id: 'IDENTICAL', label: 'Identical', count: workspaceDiffResult?.counters?.match || 0 },
                    ].map(tab => (
                      <button
                        key={tab.id}
                        onClick={() => setFilterStatus(tab.id as any)}
                        className={clsx(
                          "flex-1 flex items-center justify-center gap-1.5 px-3 py-1.5 rounded text-[10px] font-bold uppercase tracking-wider transition-all min-w-[100px]",
                          filterStatus === tab.id
                            ? "bg-bg-panel shadow-sm border border-border-item text-text-main"
                            : "text-text-muted hover:bg-bg-hover hover:text-text-main"
                        )}
                      >
                        <span className={tab.color}>{tab.label}</span>
                        <span className="bg-bg-main px-1.5 py-0.5 rounded-full text-[9px] border border-border-main">
                          {tab.count}
                        </span>
                      </button>
                    ))}
                  </div>
                )}
              <div className="flex-1 bg-bg-main border border-border-main rounded overflow-hidden min-h-0">
                <DiffDataGrid filterStatus={filterStatus} directResult={workspaceDiffResult} />
              </div>
          </div>
        )}
      </div>
    </div>
  );
};
