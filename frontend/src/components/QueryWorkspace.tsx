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

  const sourceConn = connections.find(c => c.id === sourceConnectionId);
  const targetConn = connections.find(c => c.id === targetConnectionId);

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
      const res = await axios.post('http://localhost:8081/api/execute-query', {
        connection: conn,
        query: finalQuery
      });
      if (res.data.success === false) {
        setError(res.data.message || 'Error executing query');
        setResults(null);
      } else {
        setResults(res.data.rows || res.data);
      }
    } catch (err: any) {
      setError(err.response?.data?.message || err.message);
      setResults(null);
    } finally {
      setLoading(false);
    }
  };

  const handleCompare = async () => {
    if (!sourceConn || !targetConn || !sourceQuery.trim() || !targetQuery.trim()) return;
    setComparing(true);
    
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
      const res = await axios.post('http://localhost:8081/api/compare', {
        sourceConnection: sourceConn,
        targetConnection: targetConn,
        tableName: m?.sourceTable || null,
        customQuerySource: sqFinal,
        customQueryTarget: tqFinal,
        primaryKeys: pks || null,
        excludeColumns: excl || null,
      });
      setWorkspaceDiffResult(res.data);
      setViewMode('diff');
    } catch (err: any) {
      alert('Comparison failed: ' + (err.response?.data?.message || err.message));
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
                  { id: 'DIFFERENT', label: 'Different', count: workspaceDiffResult?.rows?.filter((r: any) => r.status === 'DIFFERENT').length || 0, color: 'text-amber-500 dark:text-amber-400' },
                  { id: 'SOURCE_ONLY', label: 'Src Only', count: workspaceDiffResult?.rows?.filter((r: any) => r.status === 'SOURCE_ONLY').length || 0, color: 'text-red-500 dark:text-red-400' },
                  { id: 'TARGET_ONLY', label: 'Tgt Only', count: workspaceDiffResult?.rows?.filter((r: any) => r.status === 'TARGET_ONLY').length || 0, color: 'text-emerald-600 dark:text-emerald-400' },
                  { id: 'IDENTICAL', label: 'Identical', count: workspaceDiffResult?.rows?.filter((r: any) => r.status === 'MATCH').length || 0 },
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
                    <span className="bg-bg-hover px-1.5 py-0.5 rounded text-[9px] font-mono text-text-muted">
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
