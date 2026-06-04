import React, { useState, useEffect } from 'react';
import { useAppStore, type TableMapping } from '../store/useAppStore';
import {
  Database, ArrowRight, Play, LayoutList, CheckSquare, Square,
  Settings, Plus, Trash2, ArrowLeftRight, Loader2, Search,
  CalendarDays, ChevronDown, ChevronUp, Filter, Tag, Maximize, Minimize, RefreshCw
} from 'lucide-react';
import axios from 'axios';
import { DiffDataGrid } from './DiffDataGrid';
import { TableMappingModal } from './TableMappingModal';
import { Panel, Group, Separator } from 'react-resizable-panels';
import clsx from 'clsx';
import { buildEffectiveQuery } from '../utils/queryHelpers';

export const DataCompareView: React.FC = () => {
  const {
    connections, sourceConnectionId, setSourceConnectionId,
    targetConnectionId, setTargetConnectionId,
    setDiffResult, diffResults,
    tableMappings, addTableMapping, removeTableMapping, updateTableMapping, clearTableMappings,
    selectedMappingIds, setSelectedMappingIds,
    focusedMappingId, setFocusedMappingId,
  } = useAppStore();

  const [sourceTables, setSourceTables] = useState<string[]>([]);
  const [targetTables, setTargetTables] = useState<string[]>([]);
  const [loading, setLoading] = useState(false);
  const [syncing, setSyncing] = useState(false);
  const [progress, setProgress] = useState({ current: 0, total: 0 });
  const [activeTab, setActiveTab] = useState<'ALL' | 'DIFFERENT' | 'SOURCE_ONLY' | 'TARGET_ONLY' | 'IDENTICAL'>('ALL');
  const [mappingModalOpen, setMappingModalOpen] = useState(false);
  const [editingMapping, setEditingMapping] = useState<TableMapping | null>(null);
  const [searchTerm, setSearchTerm] = useState('');
  const [dateBarOpen, setDateBarOpen] = useState(true);

  const selectedMappings = React.useMemo(() => new Set(selectedMappingIds), [selectedMappingIds]);
  const [isFullscreen, setIsFullscreen] = useState(false);
  const [isFullscreenGrid, setIsFullscreenGrid] = useState(false);
  const focusedMapping = focusedMappingId || '';

  const sourceConn = connections.find(c => c.id === sourceConnectionId);
  const targetConn = connections.find(c => c.id === targetConnectionId);

  // Fetch tables when connections change
  useEffect(() => {
    if (sourceConn) {
      axios.post('http://localhost:8081/api/tables', sourceConn)
        .then(res => setSourceTables(res.data.map((t: any) => t.name)))
        .catch(console.error);
    } else {
      setSourceTables([]);
    }
  }, [sourceConn?.id]);

  useEffect(() => {
    if (targetConn) {
      axios.post('http://localhost:8081/api/tables', targetConn)
        .then(res => setTargetTables(res.data.map((t: any) => t.name)))
        .catch(console.error);
    } else {
      setTargetTables([]);
    }
  }, [targetConn?.id]);

  // Auto-create 1:1 mappings when tables load and no mappings exist
  useEffect(() => {
    if (sourceTables.length > 0 && targetTables.length > 0 && tableMappings.length === 0) {
      const commonTables = sourceTables.filter(t => targetTables.includes(t));
      commonTables.forEach(t => {
        addTableMapping({ id: `auto-${t}`, sourceTable: t, targetTable: t });
      });
      sourceTables.filter(t => !targetTables.includes(t)).forEach(t => {
        addTableMapping({ id: `src-only-${t}`, sourceTable: t, targetTable: '' });
      });
      targetTables.filter(t => !sourceTables.includes(t)).forEach(t => {
        addTableMapping({ id: `tgt-only-${t}`, sourceTable: '', targetTable: t });
      });
    }
  }, [sourceTables, targetTables]);

  const streamOneMapping = async (mapping: TableMapping) => {
    const sqFinal = buildEffectiveQuery(mapping.sourceTable, mapping, 'source');
    const tqFinal = buildEffectiveQuery(mapping.targetTable, mapping, 'target');

    const payload = {
      sourceConnection: sourceConn,
      targetConnection: targetConn,
      tableName: null,
      customQuerySource: sqFinal,
      customQueryTarget: tqFinal,
      primaryKeys: mapping.primaryKeys || null,
      excludeColumns: mapping.excludeColumns || null,
    };

    const store = useAppStore.getState();
    store.initDiffResult(mapping.id);

    const response = await fetch('http://localhost:8081/api/compare', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload)
    });

    if (!response.ok) {
      let errMsg = 'Comparison failed for ' + (mapping.sourceTable || 'custom query');
      try {
        const errBody = await response.json();
        errMsg = errBody.message || errMsg;
      } catch (_e) {}
      throw new Error(errMsg);
    }

    const reader = response.body?.getReader();
    if (!reader) throw new Error("Response body is not readable");
    const decoder = new TextDecoder('utf-8');
    let buffer = '';
    let rowBatch: any[] = [];
    let batchTimer: ReturnType<typeof setTimeout> | null = null;

    const flushRows = () => {
      if (rowBatch.length > 0) {
        store.appendDiffRows(mapping.id, rowBatch);
        rowBatch = [];
      }
    };

    while (true) {
      const { done, value } = await reader.read();
      if (done) {
        if (batchTimer) clearTimeout(batchTimer);
        flushRows();
        break;
      }

      buffer += decoder.decode(value, { stream: true });
      let boundary = buffer.indexOf('\n');
      while (boundary !== -1) {
        const line = buffer.substring(0, boundary);
        buffer = buffer.substring(boundary + 1);

        if (line.trim().length > 0) {
          try {
            const parsed = JSON.parse(line);
            if (parsed.type === 'columns') {
              store.setDiffColumns(mapping.id, parsed.data);
            } else if (parsed.type === 'row') {
              rowBatch.push(parsed.data);
              if (rowBatch.length >= 500) {
                flushRows();
              } else if (!batchTimer) {
                batchTimer = setTimeout(() => {
                  flushRows();
                  batchTimer = null;
                }, 500); // Throttle state updates to 500ms (prevent React rendering storm)
              }
            } else if (parsed.type === 'summary') {
              if (batchTimer) { clearTimeout(batchTimer); batchTimer = null; }
              flushRows();
              store.setDiffSummary(mapping.id, parsed.data);
            }
          } catch (e) {
            console.error("Failed to parse NDJSON line:", line, e);
          }
        }
        boundary = buffer.indexOf('\n');
      }
    }
  };

  const handleCompare = async () => {
    if (!sourceConn || !targetConn || selectedMappings.size === 0) return;
    setLoading(true);
    const mappingsToCompare = tableMappings.filter(
      m => selectedMappings.has(m.id) && (m.sourceTable || m.customQuerySource) && (m.targetTable || m.customQueryTarget)
    );
    setProgress({ current: 0, total: mappingsToCompare.length });

    try {
      // Run mappings with concurrency limit (max 3 at a time) to prevent connection pool exhaustion
      const concurrency = 3;
      const executing = new Set<Promise<void>>();
      const promises: Promise<void>[] = [];

      for (const mapping of mappingsToCompare) {
        const p = streamOneMapping(mapping).then(() => {
          setProgress(prev => ({ current: prev.current + 1, total: prev.total }));
        }).catch(err => {
          console.error("Mapping failed", mapping.id, err);
          setProgress(prev => ({ current: prev.current + 1, total: prev.total }));
        });

        promises.push(p);
        executing.add(p);
        p.finally(() => executing.delete(p));

        if (executing.size >= concurrency) {
          await Promise.race(executing);
        }
      }
      
      await Promise.allSettled(promises);

      if (!focusedMapping && mappingsToCompare.length > 0) {
        setFocusedMappingId(mappingsToCompare[0].id);
      }
    } catch (err: any) {
      console.error(err);
      alert('Comparison failed: ' + (err.response?.data?.message || err.message));
    } finally {
      setLoading(false);
      setProgress({ current: 0, total: 0 });
    }
  };

  const handleSynchronize = async () => {
    if (!sourceConn || !targetConn || selectedMappings.size === 0) return;
    
    const mappingsToSync = tableMappings.filter(
      m => selectedMappings.has(m.id) && m.sourceTable && m.targetTable
    );

    if (mappingsToSync.length === 0) return;

    // View check: basic heuristic. If table name contains 'view', warn the user.
    const hasPossibleView = mappingsToSync.some(m => m.targetTable.toLowerCase().includes('view'));
    let msg = `Are you sure you want to synchronize ${mappingsToSync.length} selected tables? This will modify the target database by inserting, updating, and deleting rows to match the source.`;
    if (hasPossibleView) {
      msg += `\n\nWARNING: One or more target objects might be a VIEW. You usually cannot synchronize data directly into a VIEW.`;
    }

    if (!confirm(msg)) return;

    setSyncing(true);
    let successCount = 0;
    try {
      for (let i = 0; i < mappingsToSync.length; i++) {
        const mapping = mappingsToSync[i];
        setProgress({ current: i + 1, total: mappingsToSync.length });

        const sqFinal = buildEffectiveQuery(mapping.sourceTable, mapping, 'source');
        const tqFinal = buildEffectiveQuery(mapping.targetTable, mapping, 'target');

        const payload = {
          sourceConnection: sourceConn,
          targetConnection: targetConn,
          tableName: mapping.targetTable,
          customQuerySource: sqFinal,
          customQueryTarget: tqFinal,
          primaryKeys: mapping.primaryKeys || null,
          excludeColumns: mapping.excludeColumns || null,
        };

        const res = await axios.post('http://localhost:8081/api/data-sync', payload);
        if (res.data.success) {
          successCount++;
        }
      }
      alert(`Synchronized ${successCount} tables successfully.`);
      // Re-run compare to reflect identical state
      handleCompare();
    } catch (err: any) {
      console.error(err);
      alert('Synchronization failed: ' + (err.response?.data?.message || err.message));
    } finally {
      setSyncing(false);
      setProgress({ current: 0, total: 0 });
    }
  };

  // ── Date/filter helper: just updates state, effective query is built dynamically ──
  const updateMappingWithDateFilter = (mappingId: string, updates: Partial<TableMapping>) => {
    updateTableMapping(mappingId, updates);
  };

  const toggleMapping = (id: string) => {
    const s = new Set(selectedMappings);
    const checked = !s.has(id);
    if (checked) s.add(id); else s.delete(id);
    setSelectedMappingIds(Array.from(s));
    if (checked) setFocusedMappingId(id);
  };

  const toggleSelectAll = () => {
    if (selectedMappings.size === tableMappings.length) {
      setSelectedMappingIds([]);
    } else {
      setSelectedMappingIds(tableMappings.map(m => m.id));
    }
  };

  const openEditModal = (e: React.MouseEvent, mapping: TableMapping) => {
    e.stopPropagation();
    setEditingMapping(mapping);
    setMappingModalOpen(true);
  };

  const openAddModal = () => {
    setEditingMapping(null);
    setMappingModalOpen(true);
  };

  const filteredMappings = tableMappings.filter(m =>
    !searchTerm ||
    m.sourceTable.toLowerCase().includes(searchTerm.toLowerCase()) ||
    m.targetTable.toLowerCase().includes(searchTerm.toLowerCase()) ||
    (m.label || '').toLowerCase().includes(searchTerm.toLowerCase())
  );

  const focusedDiff = focusedMapping ? diffResults[focusedMapping] : null;
  const focusedMappingObj = tableMappings.find(m => m.id === focusedMapping);

  return (
    <div className={clsx("flex flex-col bg-bg-main text-text-main", isFullscreen ? "fixed inset-0 z-[100]" : "h-full min-h-0")}>
      {/* ── Connection Bar ── */}
      <div className="bg-bg-header border-b border-border-main px-4 py-2.5 flex items-center justify-between shrink-0">
        <div className="flex items-center gap-3">
          <div className="flex items-center gap-2">
            <div className="flex flex-col">
              <span className="text-[9px] font-bold text-blue-500 dark:text-blue-400 uppercase tracking-widest mb-0.5">Source</span>
              <select
                className="px-2.5 py-1.5 bg-bg-input border border-border-input rounded-md text-xs font-medium text-text-input w-52 focus:border-blue-500 outline-none"
                value={sourceConnectionId || ''}
                onChange={e => { setSourceConnectionId(e.target.value); clearTableMappings(); }}
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
                onChange={e => { setTargetConnectionId(e.target.value); clearTableMappings(); }}
              >
                <option value="">Select target...</option>
                {connections.map(c => <option key={c.id} value={c.id}>{c.name} ({c.database})</option>)}
              </select>
            </div>
          </div>
        </div>

        <div className="flex items-center gap-2">
          {loading && (
            <div className="flex items-center gap-2 text-xs text-blue-500 mr-2 font-medium">
              <Loader2 className="w-3.5 h-3.5 animate-spin" />
              <span>{progress.current}/{progress.total}</span>
              <div className="w-24 h-1.5 bg-bg-hover rounded-full overflow-hidden">
                <div
                  className="h-full bg-blue-500 rounded-full transition-all duration-300"
                  style={{ width: `${progress.total > 0 ? (progress.current / progress.total) * 100 : 0}%` }}
                />
              </div>
            </div>
          )}
          <button
            onClick={openAddModal}
            className="px-3 py-1.5 border border-border-input bg-bg-panel hover:bg-bg-hover rounded-md text-xs font-medium text-text-main flex items-center gap-1.5 transition-colors"
          >
            <Plus className="w-3.5 h-3.5 text-blue-500" /> Add Mapping
          </button>
          
          <button
            onClick={() => setIsFullscreen(!isFullscreen)}
            className="px-2 py-1.5 border border-border-input bg-bg-panel hover:bg-bg-hover rounded-md text-text-muted hover:text-text-main flex items-center justify-center transition-colors"
            title={isFullscreen ? "Exit Full View" : "Full View"}
          >
            {isFullscreen ? <Minimize className="w-3.5 h-3.5" /> : <Maximize className="w-3.5 h-3.5" />}
          </button>

          <button
            onClick={handleSynchronize}
            disabled={!sourceConn || !targetConn || selectedMappings.size === 0 || loading || syncing}
            className="px-4 py-1.5 bg-gradient-to-r from-amber-600 to-amber-500 hover:from-amber-500 hover:to-amber-400 text-white rounded-md flex items-center gap-2 text-xs font-bold disabled:opacity-40 disabled:cursor-not-allowed shadow-lg shadow-amber-500/20 transition-all"
            title="Synchronize Data from Source to Target"
          >
            {syncing ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <RefreshCw className="w-3.5 h-3.5" />}
            {syncing ? 'Syncing...' : 'Synchronize'}
          </button>

          <button
            onClick={handleCompare}
            disabled={!sourceConn || !targetConn || selectedMappings.size === 0 || loading || syncing}
            className="px-4 py-1.5 bg-gradient-to-r from-blue-600 to-blue-500 hover:from-blue-500 hover:to-blue-400 text-white rounded-md flex items-center gap-2 text-xs font-bold disabled:opacity-40 disabled:cursor-not-allowed shadow-lg shadow-blue-500/20 transition-all"
          >
            {loading ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Play className="w-3.5 h-3.5 fill-current" />}
            {loading ? 'Comparing...' : `Compare (${selectedMappings.size})`}
          </button>
        </div>
      </div>

      {/* ── Split: Table list (top) + Data grid (bottom) ── */}
      <div className="flex-1 min-h-0">
        <Group orientation="vertical">

          {/* ── Upper Panel: Table Mappings ── */}
          <Panel defaultSize="35%" minSize="15%">
            <div className="h-full bg-bg-panel flex flex-col">
              <div className="bg-bg-header border-b border-border-main px-3 py-1.5 flex items-center justify-between shrink-0">
                <div className="flex items-center gap-2 text-[10px] font-semibold text-text-muted uppercase tracking-wider">
                  <LayoutList className="w-3.5 h-3.5 text-blue-500" /> Table Mappings
                  <span className="ml-1 px-1.5 py-0.5 bg-bg-hover rounded text-[9px] text-text-muted">{tableMappings.length}</span>
                </div>
                <div className="relative">
                  <Search className="w-3 h-3 absolute left-2 top-1/2 -translate-y-1/2 text-text-muted" />
                  <input
                    value={searchTerm}
                    onChange={e => setSearchTerm(e.target.value)}
                    placeholder="Filter tables..."
                    className="pl-6 pr-2 py-1 text-[10px] bg-bg-input border border-border-input rounded text-text-input placeholder-slate-500 w-36 outline-none focus:border-blue-500/50"
                  />
                </div>
              </div>

              <div className="flex-1 overflow-auto">
                <table className="w-full text-left text-xs">
                  <thead className="sticky top-0 z-10 bg-bg-header text-[10px] text-text-muted uppercase tracking-wider border-b border-border-main">
                    <tr>
                      <th className="py-1.5 px-2 w-8 text-center">
                        <button onClick={toggleSelectAll} className="text-text-muted hover:text-blue-500 pt-0.5">
                          {selectedMappings.size === tableMappings.length && tableMappings.length > 0
                            ? <CheckSquare className="w-3.5 h-3.5 text-blue-500" />
                            : <Square className="w-3.5 h-3.5" />}
                        </button>
                      </th>
                      <th className="py-1.5 px-2">Label / Source</th>
                      <th className="py-1.5 px-2 text-center w-8">→</th>
                      <th className="py-1.5 px-2">Target</th>
                      <th className="py-1.5 px-2 text-center">Status</th>
                      <th className="py-1.5 px-2 text-right">Diff</th>
                      <th className="py-1.5 px-2 text-right">Src Only</th>
                      <th className="py-1.5 px-2 text-right">Tgt Only</th>
                      <th className="py-1.5 px-2 text-right">Match</th>
                      <th className="py-1.5 px-2 text-center w-14">Actions</th>
                    </tr>
                  </thead>
                  <tbody>
                    {filteredMappings.map(m => {
                      const isChecked = selectedMappings.has(m.id);
                      const isFocused = m.id === focusedMapping;
                      const diff = diffResults[m.id];
                      const hasCustom = !!(m.customQuerySource || m.customQueryTarget);
                      const hasDateFilter = !!(m.dateColumn && (m.startDate || m.endDate));
                      const hasExtraWhere = !!(m.extraWhereSource || m.extraWhereTarget);
                      const isSourceOnly = m.sourceTable && !m.targetTable;
                      const isTargetOnly = !m.sourceTable && m.targetTable;
                      const displayName = m.label || m.sourceTable || '(none)';

                      return (
                        <tr
                          key={m.id}
                          onClick={() => setFocusedMappingId(m.id)}
                          className={clsx(
                            "cursor-pointer border-b border-border-item transition-colors",
                            isFocused
                              ? "bg-blue-500/10 dark:bg-blue-500/20 border-l-2 border-l-blue-500"
                              : "hover:bg-bg-hover border-l-2 border-l-transparent"
                          )}
                        >
                          <td className="py-1.5 px-2 text-center" onClick={e => e.stopPropagation()}>
                            <button onClick={() => toggleMapping(m.id)} className="pt-0.5">
                              {isChecked
                                ? <CheckSquare className="w-3.5 h-3.5 text-blue-500" />
                                : <Square className="w-3.5 h-3.5 text-text-muted" />}
                            </button>
                          </td>
                          <td className="py-1.5 px-2">
                            <span className={clsx("font-mono text-[11px] font-medium", m.sourceTable ? "text-text-main" : "text-text-muted italic")}>
                              {displayName}
                            </span>
                            {hasCustom && (
                              <span className="ml-1 text-[8px] bg-blue-500/20 text-blue-500 dark:text-blue-400 px-1 py-0.5 rounded font-bold">SQL</span>
                            )}
                            {hasDateFilter && (
                              <span className="ml-1 text-[8px] bg-amber-500/20 text-amber-600 dark:text-amber-400 px-1 py-0.5 rounded font-bold">DATE</span>
                            )}
                            {hasExtraWhere && (
                              <span className="ml-1 text-[8px] bg-purple-500/20 text-purple-600 dark:text-purple-400 px-1 py-0.5 rounded font-bold">FILTER</span>
                            )}
                            {m.rowLimit && (
                              <span className="ml-1 text-[8px] bg-cyan-500/20 text-cyan-600 dark:text-cyan-400 px-1 py-0.5 rounded font-bold">LIMIT</span>
                            )}
                          </td>
                          <td className="py-1.5 px-2 text-center text-text-muted">
                            <ArrowRight className="w-3 h-3 inline" />
                          </td>
                          <td className="py-1.5 px-2">
                            <span className={clsx("font-mono text-[11px] font-medium", m.targetTable ? "text-text-main" : "text-text-muted italic")}>
                              {m.targetTable || '(none)'}
                            </span>
                          </td>
                          <td className="py-1.5 px-2 text-center">
                            {diff ? (
                              diff.status === 'comparing'
                                ? <span className="text-[10px] font-bold text-blue-500 dark:text-blue-400 bg-blue-500/10 px-2 py-0.5 rounded-full animate-pulse whitespace-nowrap">
                                    Comparing… {diff.rows.length > 0 && `(${diff.rows.length})`}
                                  </span>
                                : diff.totalDifferences > 0
                                  ? <span className="text-[10px] font-bold text-amber-500 dark:text-amber-400 bg-amber-500/10 px-2 py-0.5 rounded-full">Different</span>
                                  : <span className="text-[10px] font-bold text-emerald-600 dark:text-emerald-400 bg-emerald-500/10 px-2 py-0.5 rounded-full">Identical</span>
                            ) : isSourceOnly ? (
                              <span className="text-[10px] font-bold text-red-500 dark:text-red-400 bg-red-500/10 px-2 py-0.5 rounded-full">Src Only</span>
                            ) : isTargetOnly ? (
                              <span className="text-[10px] font-bold text-emerald-600 dark:text-emerald-400 bg-emerald-500/10 px-2 py-0.5 rounded-full">Tgt Only</span>
                            ) : (
                              <span className="text-[10px] text-text-muted">—</span>
                            )}
                          </td>
                          <td className="py-1.5 px-2 text-right font-mono text-[11px] text-amber-500/80">{diff ? diff.rows.filter(r => r.status === 'DIFFERENT').length : '—'}</td>
                          <td className="py-1.5 px-2 text-right font-mono text-[11px] text-red-500/80">{diff ? diff.rows.filter(r => r.status === 'SOURCE_ONLY').length : '—'}</td>
                          <td className="py-1.5 px-2 text-right font-mono text-[11px] text-emerald-500/80">{diff ? diff.rows.filter(r => r.status === 'TARGET_ONLY').length : '—'}</td>
                          <td className="py-1.5 px-2 text-right font-mono text-[11px] text-text-muted">{diff ? diff.rows.filter(r => r.status === 'MATCH').length : '—'}</td>
                          <td className="py-1.5 px-2 text-center" onClick={e => e.stopPropagation()}>
                            <div className="flex items-center justify-center gap-1">
                              <button
                                onClick={e => openEditModal(e, m)}
                                className="p-1 rounded text-text-muted hover:text-blue-500 hover:bg-bg-hover transition-colors"
                                title="Edit mapping"
                              >
                                <Settings className="w-3 h-3" />
                              </button>
                              <button
                                onClick={e => { e.stopPropagation(); removeTableMapping(m.id); }}
                                className="p-1 rounded text-text-muted hover:text-red-500 hover:bg-bg-hover transition-colors"
                                title="Remove mapping"
                              >
                                <Trash2 className="w-3 h-3" />
                              </button>
                            </div>
                          </td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>
            </div>
          </Panel>

          <Separator className="h-1 transition-all" />

          {/* ── Lower Panel: Detail Data Grid ── */}
          <Panel defaultSize={65} minSize={25}>
            <div className={clsx("flex flex-col bg-bg-panel", isFullscreenGrid ? "fixed inset-0 z-[110]" : "h-full")}>
              {/* Tab bar */}
              <div className="flex items-center bg-bg-header border-b border-border-main px-2 shrink-0">
                {focusedMappingObj && (
                  <div className="text-[10px] text-text-muted font-mono mr-3 py-2 flex items-center gap-1.5">
                    <Database className="w-3 h-3 text-blue-500" />
                    <span className="text-blue-500 font-medium">{focusedMappingObj.label || focusedMappingObj.sourceTable}</span>
                    <ArrowRight className="w-2.5 h-2.5 text-text-muted" />
                    <span className="text-emerald-500 font-medium">{focusedMappingObj.targetTable}</span>
                  </div>
                )}
                <div className="flex items-center gap-0.5 py-1 flex-1">
                  {([
                    { id: 'ALL', label: 'All', count: focusedDiff?.rows.length || 0, color: '' },
                    { id: 'DIFFERENT', label: 'Different', count: focusedDiff?.rows.filter(r => r.status === 'DIFFERENT').length || 0, color: 'text-amber-500 dark:text-amber-400' },
                    { id: 'SOURCE_ONLY', label: 'Src Only', count: focusedDiff?.rows.filter(r => r.status === 'SOURCE_ONLY').length || 0, color: 'text-red-500 dark:text-red-400' },
                    { id: 'TARGET_ONLY', label: 'Tgt Only', count: focusedDiff?.rows.filter(r => r.status === 'TARGET_ONLY').length || 0, color: 'text-emerald-600 dark:text-emerald-400' },
                    { id: 'IDENTICAL', label: 'Identical', count: focusedDiff?.rows.filter(r => r.status === 'MATCH').length || 0, color: '' },
                  ] as { id: string, label: string, count: number, color: string }[]).map(tab => (
                    <button
                      key={tab.id}
                      onClick={() => setActiveTab(tab.id as any)}
                      className={clsx(
                        "px-2.5 py-1 text-[10px] font-medium rounded flex items-center gap-1.5 transition-all",
                        activeTab === tab.id
                          ? "bg-bg-active text-text-main shadow-inner"
                          : "text-text-muted hover:text-text-main hover:bg-bg-hover"
                      )}
                    >
                      <span className={tab.color || ''}>{tab.label}</span>
                      <span className={clsx(
                        "px-1.5 py-0.5 rounded-full text-[9px] font-bold",
                        activeTab === tab.id ? "bg-bg-panel text-text-main" : "bg-bg-active text-text-muted"
                      )}>{tab.count}</span>
                    </button>
                  ))}
                </div>
                {/* Toggle date bar & Fullscreen Grid */}
                <div className="ml-auto flex items-center gap-1">
                  {focusedMappingObj && (
                    <button
                      onClick={() => setDateBarOpen(v => !v)}
                      className="flex items-center gap-1 text-[10px] text-text-muted hover:text-blue-500 px-2 py-1 rounded hover:bg-bg-hover transition-colors"
                    >
                      <Filter className="w-3 h-3" />
                      Filters
                      {dateBarOpen ? <ChevronUp className="w-3 h-3" /> : <ChevronDown className="w-3 h-3" />}
                    </button>
                  )}
                  <button
                    onClick={() => setIsFullscreenGrid(!isFullscreenGrid)}
                    className="p-1 text-text-muted hover:text-blue-500 rounded hover:bg-bg-hover transition-colors"
                    title={isFullscreenGrid ? "Exit Full View" : "Full View Grid"}
                  >
                    {isFullscreenGrid ? <Minimize className="w-3.5 h-3.5" /> : <Maximize className="w-3.5 h-3.5" />}
                  </button>
                </div>
              </div>

              {/* ── Quick Filter Bar (collapsible) ── */}
              {focusedMappingObj && dateBarOpen && (
                <div className="bg-bg-panel border-b border-border-main px-4 py-2 flex flex-wrap items-center gap-x-4 gap-y-2 text-xs shrink-0">

                  {/* Label */}
                  <div className="flex items-center gap-1.5">
                    <Tag className="w-3 h-3 text-text-muted" />
                    <span className="text-[9px] uppercase font-bold text-text-muted tracking-wider">Label</span>
                    <input
                      type="text"
                      placeholder="Alias for this mapping"
                      value={focusedMappingObj.label || ''}
                      onChange={e => updateTableMapping(focusedMappingObj.id, { label: e.target.value })}
                      className="px-2 py-0.5 text-[11px] bg-bg-input border border-border-input rounded w-32 outline-none focus:border-blue-500"
                    />
                  </div>

                  <div className="h-4 w-px bg-border-main" />

                  {/* Date filter */}
                  <div className="flex items-center gap-2">
                    <CalendarDays className="w-3 h-3 text-blue-500" />
                    <span className="text-[9px] uppercase font-bold text-blue-500 tracking-wider">Date Filter</span>
                    <div className="flex items-center gap-1.5">
                      <span className="text-[10px] text-text-muted">Column:</span>
                      <input
                        type="text"
                        placeholder="e.g. created_at"
                        value={focusedMappingObj.dateColumn || ''}
                        onChange={e => updateMappingWithDateFilter(focusedMappingObj.id, { dateColumn: e.target.value })}
                        className={clsx(
                          "px-2 py-0.5 text-[11px] bg-bg-input border rounded w-28 outline-none focus:border-blue-500",
                          !focusedMappingObj.dateColumn && (focusedMappingObj.startDate || focusedMappingObj.endDate)
                            ? "border-red-500 bg-red-500/10"
                            : "border-border-input"
                        )}
                        title={!focusedMappingObj.dateColumn && (focusedMappingObj.startDate || focusedMappingObj.endDate) ? "Column name is required to apply date filter!" : ""}
                      />
                    </div>
                    <div className="flex items-center gap-1">
                      <span className="text-[10px] text-text-muted">From:</span>
                      <input
                        type="date"
                        value={focusedMappingObj.startDate || ''}
                        onChange={e => updateMappingWithDateFilter(focusedMappingObj.id, { startDate: e.target.value })}
                        className="px-2 py-0.5 text-[11px] bg-bg-input border border-border-input rounded outline-none focus:border-blue-500"
                      />
                    </div>
                    <div className="flex items-center gap-1">
                      <span className="text-[10px] text-text-muted">To:</span>
                      <input
                        type="date"
                        value={focusedMappingObj.endDate || ''}
                        onChange={e => updateMappingWithDateFilter(focusedMappingObj.id, { endDate: e.target.value })}
                        className="px-2 py-0.5 text-[11px] bg-bg-input border border-border-input rounded outline-none focus:border-blue-500"
                      />
                    </div>
                  </div>

                  <div className="h-4 w-px bg-border-main" />

                  {/* Row limit */}
                  <div className="flex items-center gap-1.5">
                    <span className="text-[9px] uppercase font-bold text-cyan-600 dark:text-cyan-400 tracking-wider">Limit</span>
                    <input
                      type="number"
                      min={0}
                      placeholder="No limit"
                      value={focusedMappingObj.rowLimit || ''}
                      onChange={e => updateMappingWithDateFilter(
                        focusedMappingObj.id,
                        { rowLimit: e.target.value ? parseInt(e.target.value) : undefined }
                      )}
                      className="px-2 py-0.5 text-[11px] bg-bg-input border border-border-input rounded w-24 outline-none focus:border-blue-500"
                    />
                    <span className="text-[10px] text-text-muted">rows</span>
                  </div>

                  {/* Clear all filters */}
                  {(focusedMappingObj.dateColumn || focusedMappingObj.startDate || focusedMappingObj.endDate || focusedMappingObj.rowLimit) && (
                    <button
                      onClick={() => updateMappingWithDateFilter(focusedMappingObj.id, {
                        dateColumn: '', startDate: '', endDate: '', rowLimit: undefined,
                      })}
                      className="text-[10px] text-red-500 hover:text-red-600 font-semibold cursor-pointer transition-colors ml-1"
                    >
                      Clear Filters
                    </button>
                  )}

                  {/* Query Preview */}
                  {(focusedMappingObj.dateColumn || focusedMappingObj.startDate || focusedMappingObj.endDate || focusedMappingObj.rowLimit || focusedMappingObj.extraWhereSource) && (
                    <div className="w-full mt-1 flex flex-col gap-1">
                      <span className="text-[9px] font-bold text-text-muted uppercase tracking-wider">Query Preview</span>
                      <div className="grid grid-cols-2 gap-2">
                        <div className="bg-bg-editor rounded px-2 py-1 font-mono text-[10px] text-blue-400 truncate" title={buildEffectiveQuery(focusedMappingObj.sourceTable, focusedMappingObj, 'source')}>
                          <span className="text-[8px] text-blue-500/60 font-bold uppercase mr-1">SRC</span>
                          {buildEffectiveQuery(focusedMappingObj.sourceTable, focusedMappingObj, 'source') || '—'}
                        </div>
                        <div className="bg-bg-editor rounded px-2 py-1 font-mono text-[10px] text-emerald-400 truncate" title={buildEffectiveQuery(focusedMappingObj.targetTable, focusedMappingObj, 'target')}>
                          <span className="text-[8px] text-emerald-500/60 font-bold uppercase mr-1">TGT</span>
                          {buildEffectiveQuery(focusedMappingObj.targetTable, focusedMappingObj, 'target') || '—'}
                        </div>
                      </div>
                    </div>
                  )}
                </div>
              )}

              {/* ── DiffDataGrid ── */}
              <div className="flex-1 overflow-auto bg-bg-main">
                <DiffDataGrid mappingId={focusedMapping} filterStatus={activeTab} />
              </div>
            </div>
          </Panel>
        </Group>
      </div>

      {/* ── Mapping Modal ── */}
      {mappingModalOpen && (
        <TableMappingModal
          sourceTables={sourceTables}
          targetTables={targetTables}
          editingMapping={editingMapping}
          onClose={() => { setMappingModalOpen(false); setEditingMapping(null); }}
        />
      )}
    </div>
  );
};
