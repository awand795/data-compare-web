// @ts-nocheck
import React, { useState, useEffect, useRef } from 'react';
import { useAppStore, type TableMapping } from '../store/useAppStore';
import {
  Database, ArrowRight, Play, LayoutList, CheckSquare, Square,
  Settings, Plus, Trash2, ArrowLeftRight, Loader2, Search,
  CalendarDays, ChevronDown, ChevronUp, Filter, Tag, Maximize, Minimize, RefreshCw,
  AlertTriangle
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
    showAlert, addToast,
  } = useAppStore();

  const [sourceTables, setSourceTables] = useState<string[]>([]);
  const [targetTables, setTargetTables] = useState<string[]>([]);
  const [loadingTables, setLoadingTables] = useState(false);
  const [loading, setLoading] = useState(false);
  const [returnMatchedRows, setReturnMatchedRows] = useState(true);
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
  const [fadingOutMappings, setFadingOutMappings] = useState<Set<string>>(new Set());
  const prevComparingRef = useRef<Set<string>>(new Set());
  const creatingMappingsRef = useRef(false);

  // Detect mappings that just finished comparing → trigger fade-out animation
  useEffect(() => {
    const currentComparing = new Set(
      Object.entries(diffResults)
        .filter(([, dr]) => dr.status === 'comparing')
        .map(([id]) => id)
    );

    const prevComparing = prevComparingRef.current;
    const justCompleted = [...prevComparing].filter(id => !currentComparing.has(id));

    if (justCompleted.length > 0) {
      setFadingOutMappings(prev => {
        const next = new Set(prev);
        justCompleted.forEach(id => next.add(id));
        return next;
      });

      // Remove from fade-out set after animation completes (700ms)
      setTimeout(() => {
        setFadingOutMappings(prev => {
          const next = new Set(prev);
          justCompleted.forEach(id => next.delete(id));
          return next;
        });
      }, 700);
    }

    prevComparingRef.current = currentComparing;
  }, [diffResults]);

  const focusedMapping = focusedMappingId || '';

  const sourceConn = connections.find(c => c.id === sourceConnectionId);
  const targetConn = connections.find(c => c.id === targetConnectionId);

  // Fetch tables when connections change
  useEffect(() => {
    let cancelled = false;
    // Immediately clear old tables to prevent stale data race with auto-mapping
    setSourceTables([]);
    setTargetTables([]);
    setLoadingTables(true);
    
    const p1 = sourceConn 
      ? axios.post('/api/tables', sourceConn).then(res => { if (!cancelled) setSourceTables(res.data.filter((t: any) => !t.name.toLowerCase().startsWith('excel_import_')).map((t: any) => t.name)); }).catch(console.error)
      : Promise.resolve(setSourceTables([]));
      
    const p2 = targetConn 
      ? axios.post('/api/tables', targetConn).then(res => { if (!cancelled) setTargetTables(res.data.filter((t: any) => !t.name.toLowerCase().startsWith('excel_import_')).map((t: any) => t.name)); }).catch(console.error)
      : Promise.resolve(setTargetTables([]));
      
    Promise.all([p1, p2]).finally(() => { if (!cancelled) setLoadingTables(false); });
    
    return () => { cancelled = true; };
  }, [sourceConn?.id, targetConn?.id]);

  // Warm up connections in the background as soon as they are selected
  useEffect(() => {
    if (sourceConn) {
      fetch('/api/warmup', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify([sourceConn])
      }).catch(err => console.error("Failed to trigger warmup", err));
    }
  }, [sourceConn?.id]);

  useEffect(() => {
    if (targetConn) {
      fetch('/api/warmup', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify([targetConn])
      }).catch(err => console.error("Failed to trigger warmup", err));
    }
  }, [targetConn?.id]);

  // Auto-create 1:1 mappings when tables load and no mappings exist
  useEffect(() => {
    if (sourceTables.length > 0 && targetTables.length > 0 && tableMappings.length === 0 && sourceConn?.id && !loadingTables && !creatingMappingsRef.current) {
      creatingMappingsRef.current = true;
      
      const commonTables = sourceTables.filter(t => targetTables.includes(t));
      
      const createMappings = async () => {
        try {
          // Fetch all PKs in parallel first — single await point
          const pkEntries = await Promise.allSettled(
            commonTables.map(async (t) => {
              const pkRes = await axios.post('/api/primary-keys', {
                connection: sourceConn,
                tableName: t
              });
              return { t, pks: pkRes.data as string[] };
            })
          );
          
          const pkMap = new Map<string, string[]>();
          pkEntries.forEach(entry => {
            if (entry.status === 'fulfilled' && entry.value.pks.length > 0) {
              pkMap.set(entry.value.t, entry.value.pks);
            }
          });
          
          // Build all mappings synchronously
          const allMappings: TableMapping[] = commonTables.map(t => ({
            id: `auto-${t}`,
            sourceTable: t,
            targetTable: t,
            primaryKeys: pkMap.has(t) ? pkMap.get(t) : undefined
          }));
          
          sourceTables.filter(t => !targetTables.includes(t)).forEach(t => {
            allMappings.push({ id: `src-only-${t}`, sourceTable: t, targetTable: '' });
          });
          targetTables.filter(t => !sourceTables.includes(t)).forEach(t => {
            allMappings.push({ id: `tgt-only-${t}`, sourceTable: '', targetTable: t });
          });
          
          // Verify connection hasn't changed while we were loading
          const currentState = useAppStore.getState();
          if (currentState.sourceConnectionId !== sourceConn?.id ||
              currentState.targetConnectionId !== targetConn?.id) {
            return; // stale — connection changed while loading, discard
          }
          
          // Batch add all at once — atomic, no intermediate state
          if (allMappings.length > 0) {
            useAppStore.setState(state => ({
              tableMappings: [...state.tableMappings, ...allMappings]
            }));
          }
        } finally {
          creatingMappingsRef.current = false;
        }
      };

      createMappings();
    }
  }, [sourceTables, targetTables, sourceConn?.id, loadingTables]);

  const streamLoadMapping = async (mapping: TableMapping) => {
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
      sortColumns: mapping.sortColumns || null,
      returnMatchedRows,
    };

    const store = useAppStore.getState();
    store.initDiffResult(mapping.id);

    // Step 1: Get row counts first (for progress bar)
    let totalRows = 0;
    try {
      const countRes = await fetch('/api/compare-count', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload)
      });
      if (countRes.ok) {
        const countData = await countRes.json();
        // Merge-join output dibatasi tabel terkecil, jadi pakai min
        totalRows = Math.min(countData.sourceCount || 0, countData.targetCount || 0);
        store.setBatchProgress(mapping.id, 0, totalRows);
      }
    } catch (e) {
      console.warn('Count fetch failed, continuing without progress', e);
    }

    // Step 2: Start streaming comparison
    const response = await fetch('/api/compare', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload)
    });

    if (!response.ok) {
      let errMsg = 'Comparison failed for ' + (mapping.sourceTable || 'custom query');
      try { const errBody = await response.json(); errMsg = errBody.message || errMsg; } catch (_e) {}
      throw new Error(errMsg);
    }

    if (!response.body) {
      throw new Error('Streaming not supported by browser');
    }

    // Step 3: Read the NDJSON stream
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    let rowBatch: any[] = [];
    let rowCount = 0;
    let columnsSet = false;
    let summaryData: any = null;
    let lastFlushTime = Date.now();

    const flushRowBatch = () => {
      if (rowBatch.length > 0) {
        store.appendDiffRows(mapping.id, rowBatch);
        rowBatch = [];
      }
    };

    try {
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        
        buffer += decoder.decode(value, { stream: true });
        
        const lines = buffer.split('\n');
        buffer = lines.pop() || '';
        
        for (const line of lines) {
          if (!line.trim()) continue;
          try {
            const obj = JSON.parse(line);
            
            if (obj.type === 'columns') {
              if (!columnsSet) {
                store.setDiffColumns(mapping.id, obj.data);
                columnsSet = true;
              }
            } else if (obj.type === 'row') {
              rowBatch.push(obj.data);
              rowCount++;
              
              const now = Date.now();
              // Flush every 3000 rows OR every 300ms for smooth but efficient UI updates
              if (rowBatch.length >= 3000 || (now - lastFlushTime > 300 && rowBatch.length > 0)) {
                flushRowBatch();
                lastFlushTime = now;
              }
              // Update progress periodically
              if (totalRows > 0 && rowCount % 3000 === 0) {
                store.setBatchProgress(mapping.id, rowCount, totalRows);
              }
            } else if (obj.type === 'summary') {
              summaryData = obj.data;
            } else if (obj.type === 'error') {
              console.error('Stream error for', mapping.id, obj.message);
            }
          } catch (e) {
            // Partial JSON line — will be completed in next chunk
          }
        }
      }
    } finally {
      reader.releaseLock();
    }
    
    // Flush remaining rows
    flushRowBatch();
    
    // Final progress update
    if (totalRows > 0) {
      store.setBatchProgress(mapping.id, totalRows, totalRows);
    }
    
    // Finalize: set summary
    if (summaryData) {
      store.setDiffSummary(mapping.id, {
        totalSourceRows: summaryData.totalSourceRows || 0,
        totalTargetRows: summaryData.totalTargetRows || 0,
        totalDifferences: summaryData.totalDifferences || 0,
      });
    }
  };

  const handleCompare = async () => {
    if (!sourceConn || !targetConn || selectedMappings.size === 0) return;
    setLoading(true);
    const mappingsToCompare = tableMappings.filter(
      m => selectedMappings.has(m.id) && (m.sourceTable || m.customQuerySource) && (m.targetTable || m.customQueryTarget)
    );
    setProgress({ current: 0, total: mappingsToCompare.length });

    const failedMappings: { tableName: string; error: string }[] = [];

    try {
      // Run mappings with concurrency limit (max 3 at a time) to prevent connection pool exhaustion
      const concurrency = 3;
      const executing = new Set<Promise<void>>();
      const promises: Promise<void>[] = [];

      for (const mapping of mappingsToCompare) {
        const p = streamLoadMapping(mapping).then(() => {
          setProgress(prev => ({ current: prev.current + 1, total: prev.total }));
        }).catch(err => {
          console.error("Mapping failed", mapping.id, err);
          failedMappings.push({
            tableName: mapping.sourceTable || 'Custom SQL Mapping',
            error: err.message || String(err)
          });
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

      if (failedMappings.length > 0) {
        const title = failedMappings.length === 1 
          ? `Comparison Failed: ${failedMappings[0].tableName}`
          : `${failedMappings.length} Comparisons Failed`;
        
        const message = failedMappings.length === 1
          ? failedMappings[0].error
          : `The following table comparisons encountered errors:\n\n` + 
            failedMappings.map(f => `• ${f.tableName}: ${f.error}`).join('\n');
            
        addToast({ type: 'error', title, message });
      }

      if (!focusedMapping && mappingsToCompare.length > 0) {
        const firstSuccess = mappingsToCompare.find(m => !failedMappings.some(f => f.tableName === (m.sourceTable || 'Custom SQL Mapping')));
        if (firstSuccess) {
          setFocusedMappingId(firstSuccess.id);
        } else {
          setFocusedMappingId(mappingsToCompare[0].id);
        }
      }
    } catch (err: any) {
      console.error(err);
      addToast({ type: 'error', title: 'Comparison Blocked', message: err.response?.data?.message || err.message || 'An unexpected error occurred.' });
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

    showAlert({
      title: 'Synchronize Data',
      message: msg,
      type: 'warning',
      confirmLabel: 'Synchronize',
      cancelLabel: 'Cancel',
      onConfirm: async () => {
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
              sortColumns: mapping.sortColumns || null,
        returnMatchedRows,
            };

            const res = await axios.post('/api/data-sync', payload);
            if (res.data.success) {
              successCount++;
            }
          }
          addToast({ type: 'success', title: 'Synchronization Complete', message: `Synchronized ${successCount} tables successfully.` });
          // Re-run compare to reflect identical state
          handleCompare();
        } catch (err: any) {
          console.error(err);
          addToast({ type: 'error', title: 'Synchronization Failed', message: err.response?.data?.message || err.message || 'An unexpected error occurred.' });
        } finally {
          setSyncing(false);
          setProgress({ current: 0, total: 0 });
        }
      }
    });
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

  const filteredMappings = tableMappings.filter(m => {
    // Exclude any mappings that involve excel_import_ tables
    if (m.sourceTable?.toLowerCase().startsWith('excel_import_') || m.targetTable?.toLowerCase().startsWith('excel_import_')) {
      return false;
    }
    return !searchTerm ||
      m.sourceTable.toLowerCase().includes(searchTerm.toLowerCase()) ||
      m.targetTable.toLowerCase().includes(searchTerm.toLowerCase()) ||
      (m.label || '').toLowerCase().includes(searchTerm.toLowerCase());
  });

  const focusedDiff = focusedMapping ? diffResults[focusedMapping] : null;
  const focusedMappingObj = tableMappings.find(m => m.id === focusedMapping);

  return (
    <div className={clsx("flex flex-col bg-bg-main text-text-main", isFullscreen ? "fixed inset-0 z-[100]" : "h-full min-h-0")}>
      {/* ── Connection Bar ── */}
      <div className="bg-bg-header border-b border-border-main px-2 sm:px-4 py-2.5 flex flex-col 2xl:flex-row items-start 2xl:items-center justify-between gap-3 shrink-0">
        <div className="flex flex-col sm:flex-row items-start sm:items-center gap-2 sm:gap-3 w-full 2xl:w-auto">
          <div className="flex items-center gap-2 sm:gap-3 w-full sm:w-auto">
            <div className="flex flex-col flex-1 sm:flex-none">
              <span className="text-[11px] font-bold text-blue-500 dark:text-blue-400 uppercase tracking-wider mb-0.5">Source</span>
              <select
                className="px-3 py-2 bg-bg-input border border-border-input rounded-md text-[12px] sm:text-[13px] font-medium text-text-input w-full sm:w-40 md:w-52 focus:border-blue-500 outline-none truncate"
                value={sourceConnectionId || ''}
                onChange={e => { setSourceConnectionId(e.target.value); clearTableMappings(); }}
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
                clearTableMappings();
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
                onChange={e => { setTargetConnectionId(e.target.value); clearTableMappings(); }}
              >
                <option value="">Select target...</option>
                {connections.map(c => <option key={c.id} value={c.id} className="truncate">{c.name} ({c.database})</option>)}
              </select>
            </div>
          </div>
        </div>

        <div className="flex flex-wrap items-center gap-2 w-full 2xl:w-auto">
          {loading && (
            <div className="flex flex-col mr-2 min-w-[200px] sm:min-w-[280px] w-full sm:w-auto order-last sm:order-none">
              {/* Mapping-level progress */}
              <div className="flex items-center gap-2 text-xs text-blue-500 font-medium">
                <Loader2 className="w-3.5 h-3.5 animate-spin shrink-0" />
                <span className="whitespace-nowrap">Mapping {progress.current}/{progress.total}</span>
                <div className="w-20 h-1.5 bg-bg-hover rounded-full overflow-hidden">
                  <div
                    className="h-full bg-blue-500 rounded-full transition-all duration-300"
                    style={{ width: `${progress.total > 0 ? (progress.current / progress.total) * 100 : 0}%` }}
                  />
                </div>
              </div>
              {/* Per-mapping batch progress */}
              {Object.entries(diffResults).map(([mid, dr]) => {
                const isFading = fadingOutMappings.has(mid);
                if ((dr.status !== 'comparing' && !isFading) || !dr.batchTotal) return null;
                const mapping = tableMappings.find(m => m.id === mid);
                const name = mapping?.label || mapping?.sourceTable || mid.slice(0, 8);
                const pct = dr.batchTotal > 0 ? ((dr.batchCurrent || 0) / dr.batchTotal) * 100 : 0;
                return (
                  <div key={mid} className={`flex items-center gap-2 text-[10px] text-text-muted mt-0.5 ${isFading ? 'animate-fade-out' : ''}`}>
                    <span className="font-mono truncate max-w-[100px]">{name}</span>
                    <div className="w-16 h-1 bg-bg-hover rounded-full overflow-hidden">
                      <div
                        className="h-full bg-blue-400 rounded-full transition-all duration-200"
                        style={{ width: `${pct}%` }}
                      />
                    </div>
                    <span className="whitespace-nowrap">Batch {dr.batchCurrent}/{dr.batchTotal}</span>
                    <span className="whitespace-nowrap text-amber-500">Δ {dr.differentCount + dr.sourceOnlyCount + dr.targetOnlyCount}</span>
                    {dr.rows.length > 0 && <span className="whitespace-nowrap">{dr.rows.length.toLocaleString()} rows</span>}
                  </div>
                );
              })}
            </div>
          )}
          
          <div className="flex flex-1 sm:flex-none items-center gap-2 flex-wrap">
            <div className="flex items-center gap-1.5 bg-bg-input px-2 py-1 rounded-md border border-border-input flex-1 sm:flex-none justify-center">
              <input
                type="checkbox"
                id="returnMatchedRows"
                checked={!returnMatchedRows}
                onChange={e => setReturnMatchedRows(!e.target.checked)}
                className="w-3.5 h-3.5 rounded border-border-item bg-bg-panel text-amber-500 focus:ring-amber-500 focus:ring-offset-bg-main"
              />
              <label htmlFor="returnMatchedRows" className="text-[11px] font-medium text-text-muted cursor-pointer hover:text-text-main select-none whitespace-nowrap">
                Only Diff (Fast)
              </label>
            </div>
            <button
              onClick={openAddModal}
              className="px-3 sm:px-4 py-2 border border-border-input bg-bg-panel hover:bg-bg-hover rounded-md text-xs font-medium text-text-main flex items-center justify-center gap-1.5 transition-colors flex-1 sm:flex-none whitespace-nowrap"
            >
              <Plus className="w-3.5 h-3.5 text-blue-500" /> Add Mapping
            </button>
            
            <button
              onClick={() => setIsFullscreen(!isFullscreen)}
              className="px-2 py-1.5 border border-border-input bg-bg-panel hover:bg-bg-hover rounded-md text-text-muted hover:text-text-main flex items-center justify-center transition-colors hidden sm:flex shrink-0"
              title={isFullscreen ? "Exit Full View" : "Full View"}
            >
              {isFullscreen ? <Minimize className="w-3.5 h-3.5" /> : <Maximize className="w-3.5 h-3.5" />}
            </button>
          </div>

          <div className="flex items-center gap-2 w-full sm:w-auto mt-2 sm:mt-0">
            <button
              onClick={handleSynchronize}
              disabled={!sourceConn || !targetConn || selectedMappings.size === 0 || loading || syncing}
              className="px-3 sm:px-5 py-2 bg-gradient-to-r from-amber-600 to-amber-500 hover:from-amber-500 hover:to-amber-400 text-white rounded-md flex items-center justify-center gap-1 sm:gap-2 text-xs sm:text-sm font-bold disabled:opacity-40 disabled:cursor-not-allowed shadow-lg shadow-amber-500/20 transition-all flex-1"
              title="Synchronize Data from Source to Target"
            >
              {syncing ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <RefreshCw className="w-3.5 h-3.5" />}
              {syncing ? 'Syncing...' : 'Synchronize'}
            </button>

            <button
              onClick={handleCompare}
              disabled={!sourceConn || !targetConn || selectedMappings.size === 0 || loading || syncing}
              className="px-3 sm:px-5 py-2 bg-gradient-to-r from-blue-600 to-blue-500 hover:from-blue-500 hover:to-blue-400 text-white rounded-md flex items-center justify-center gap-1 sm:gap-2 text-xs sm:text-sm font-bold disabled:opacity-40 disabled:cursor-not-allowed shadow-lg shadow-blue-500/20 transition-all flex-1 whitespace-nowrap"
            >
              {loading ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Play className="w-3.5 h-3.5 fill-current" />}
              {loading ? 'Comparing...' : `Compare (${selectedMappings.size})`}
            </button>
          </div>
        </div>
      </div>

      {/* ── Split: Table list (top) + Data grid (bottom) ── */}
      <div className="flex-1 min-h-0">
        <Group orientation="vertical">

          {/* ── Upper Panel: Table Mappings ── */}
          <Panel defaultSize="35%" minSize="15%">
            <div className="h-full bg-bg-panel flex flex-col">
              <div className="bg-bg-header border-b border-border-main px-3 py-1.5 flex items-center justify-between shrink-0">
                <div className="flex items-center gap-2 text-xs font-semibold text-text-muted uppercase tracking-wider">
                  <LayoutList className="w-3.5 h-3.5 text-blue-500" /> Table Mappings
                  <span className="ml-1 px-1.5 py-0.5 bg-bg-hover rounded text-[10px] text-text-muted">{tableMappings.length}</span>
                </div>
                <div className="flex items-center gap-2">
                  <div className="relative">
                    <Search className="w-3 h-3 absolute left-2 top-1/2 -translate-y-1/2 text-text-muted" />
                    <input
                      value={searchTerm}
                      onChange={e => setSearchTerm(e.target.value)}
                      placeholder="Filter tables..."
                      className="pl-6 pr-2 py-1 text-[11px] bg-bg-input border border-border-input rounded text-text-input placeholder-slate-500 w-36 outline-none focus:border-blue-500/50"
                    />
                  </div>
                </div>
              </div>

              <div className="flex-1 overflow-auto">
                <table className="w-full text-left text-xs">
                  <thead className="sticky top-0 z-10 bg-bg-header text-xs text-text-muted uppercase tracking-wider border-b border-border-main">
                    <tr>
                      <th className="py-2 px-3 w-8 text-center">
                        <button onClick={toggleSelectAll} className="text-text-muted hover:text-blue-500 pt-0.5">
                          {selectedMappings.size === tableMappings.length && tableMappings.length > 0
                            ? <CheckSquare className="w-3.5 h-3.5 text-blue-500" />
                            : <Square className="w-3.5 h-3.5" />}
                        </button>
                      </th>
                      <th className="py-2 px-3">Label / Source</th>
                      <th className="py-2 px-3 text-center w-8">→</th>
                      <th className="py-2 px-3">Target</th>
                      <th className="py-2 px-3 text-center">Status</th>
                      <th className="py-2 px-3 text-right">Diff</th>
                      <th className="py-2 px-3 text-right">Src Only</th>
                      <th className="py-2 px-3 text-right">Tgt Only</th>
                      <th className="py-2 px-3 text-right">Match</th>
                      <th className="py-2 px-3 text-center w-14">Actions</th>
                    </tr>
                  </thead>
                  <tbody>
                    {loadingTables && (
                      <tr>
                        <td colSpan={10} className="py-8 text-center text-xs text-text-muted">
                          <Loader2 className="w-5 h-5 animate-spin mx-auto mb-2 text-blue-500" />
                          Fetching table lists from databases...
                        </td>
                      </tr>
                    )}
                    {!loadingTables && tableMappings.length === 0 && (
                      <tr>
                        <td colSpan={10} className="py-8 text-center text-xs text-text-muted">
                          {sourceConn && targetConn ? 'No tables found.' : 'Select Source and Target connections above to start.'}
                        </td>
                      </tr>
                    )}
                    {!loadingTables && filteredMappings.map(m => {
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
                              ? "bg-blue-500/10 dark:bg-blue-500/20 border-l-[3px] border-l-blue-500"
                              : "hover:bg-bg-hover border-l-[3px] border-l-transparent"
                          )}
                        >
                          <td className="py-2 px-3 text-center" onClick={e => e.stopPropagation()}>
                            <button onClick={() => toggleMapping(m.id)} className="pt-0.5">
                              {isChecked
                                ? <CheckSquare className="w-3.5 h-3.5 text-blue-500" />
                                : <Square className="w-3.5 h-3.5 text-text-muted" />}
                            </button>
                          </td>
                          <td className="py-2 px-3">
                            <span className={clsx("font-mono text-xs font-medium", m.sourceTable ? "text-text-main" : "text-text-muted italic")}>
                              {displayName}
                            </span>
                            {hasCustom && (
                              <span className="ml-1 text-[10px] bg-blue-500/20 text-blue-500 dark:text-blue-400 px-1.5 py-0.5 rounded font-bold">SQL</span>
                            )}
                            {hasDateFilter && (
                              <span className="ml-1 text-[10px] bg-amber-500/20 text-amber-600 dark:text-amber-400 px-1.5 py-0.5 rounded font-bold">DATE</span>
                            )}
                            {hasExtraWhere && (
                              <span className="ml-1 text-[10px] bg-purple-500/20 text-purple-600 dark:text-purple-400 px-1.5 py-0.5 rounded font-bold">FILTER</span>
                            )}
                            {m.rowLimit && (
                              <span className="ml-1 text-[10px] bg-cyan-500/20 text-cyan-600 dark:text-cyan-400 px-1.5 py-0.5 rounded font-bold">LIMIT</span>
                            )}
                          </td>
                          <td className="py-2 px-3 text-center text-text-muted">
                            <ArrowRight className="w-3 h-3 inline" />
                          </td>
                          <td className="py-2 px-3">
                            <span className={clsx("font-mono text-xs font-medium", m.targetTable ? "text-text-main" : "text-text-muted italic")}>
                              {m.targetTable || '(none)'}
                            </span>
                          </td>
                          <td className="py-2 px-3 text-center">
                            {diff ? (
                              diff.status === 'comparing'
                                ? <span className="text-[11px] font-bold text-blue-500 dark:text-blue-400 bg-blue-500/10 px-2.5 py-0.5 rounded-full animate-pulse whitespace-nowrap">
                                    Comparing… {diff.rows.length > 0 && `(${diff.rows.length})`}
                                  </span>
                                : diff.totalDifferences > 0
                                  ? <span className="text-[11px] font-bold text-amber-500 dark:text-amber-400 bg-amber-500/10 px-2.5 py-0.5 rounded-full">Different</span>
                                  : <span className="text-[11px] font-bold text-emerald-600 dark:text-emerald-400 bg-emerald-500/10 px-2.5 py-0.5 rounded-full">Identical</span>
                            ) : isSourceOnly ? (
                              <span className="text-[11px] font-bold text-red-500 dark:text-red-400 bg-red-500/10 px-2.5 py-0.5 rounded-full">Src Only</span>
                            ) : isTargetOnly ? (
                              <span className="text-[11px] font-bold text-emerald-600 dark:text-emerald-400 bg-emerald-500/10 px-2.5 py-0.5 rounded-full">Tgt Only</span>
                            ) : (
                              <span className="text-[11px] text-text-muted">—</span>
                            )}
                          </td>
                          <td className="py-2 px-3 text-right font-mono text-xs text-amber-500/80">{diff ? diff.differentCount : '—'}</td>
                          <td className="py-2 px-3 text-right font-mono text-xs text-red-500/80">{diff ? diff.sourceOnlyCount : '—'}</td>
                          <td className="py-2 px-3 text-right font-mono text-xs text-emerald-500/80">{diff ? diff.targetOnlyCount : '—'}</td>
                          <td className="py-2 px-3 text-right font-mono text-xs text-text-muted">{diff ? diff.matchCount : '—'}</td>
                          <td className="py-2 px-3 text-center" onClick={e => e.stopPropagation()}>
                            <div className="flex items-center justify-center gap-0.5">
                              <button
                                onClick={e => openEditModal(e, m)}
                                className="w-6 h-6 rounded text-text-muted hover:text-blue-500 hover:bg-bg-hover transition-colors flex items-center justify-center"
                                title="Edit mapping"
                              >
                                <Settings className="w-3 h-3" />
                              </button>
                              <button
                                onClick={e => { e.stopPropagation(); removeTableMapping(m.id); }}
                                className="w-6 h-6 rounded text-text-muted hover:text-red-500 hover:bg-bg-hover transition-colors flex items-center justify-center"
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
                    { id: 'DIFFERENT', label: 'Different', count: focusedDiff?.differentCount || 0, color: 'text-amber-500 dark:text-amber-400' },
                    { id: 'SOURCE_ONLY', label: 'Src Only', count: focusedDiff?.sourceOnlyCount || 0, color: 'text-red-500 dark:text-red-400' },
                    { id: 'TARGET_ONLY', label: 'Tgt Only', count: focusedDiff?.targetOnlyCount || 0, color: 'text-emerald-600 dark:text-emerald-400' },
                    { id: 'IDENTICAL', label: 'Identical', count: focusedDiff?.matchCount || 0, color: '' },
                  ] as { id: string, label: string, count: number, color: string }[]).map(tab => (
                    <button
                      key={tab.id}
                      onClick={() => setActiveTab(tab.id as any)}
                      className={clsx(
                        "px-3 py-1.5 text-xs font-medium rounded flex items-center gap-1.5 transition-all",
                        activeTab === tab.id
                          ? "bg-bg-active text-text-main shadow-inner"
                          : "text-text-muted hover:text-text-main hover:bg-bg-hover"
                      )}
                    >
                      <span className={tab.color || ''}>{tab.label}</span>
                      <span className={clsx(
                        "px-1.5 py-0.5 rounded-full text-[10px] font-bold",
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
                    <span className="text-[10px] uppercase font-bold text-text-muted tracking-wider">Label</span>
                    <input
                      type="text"
                      placeholder="Alias for this mapping"
                      value={focusedMappingObj.label || ''}
                      onChange={e => updateTableMapping(focusedMappingObj.id, { label: e.target.value })}
                      className="px-2 py-1 text-xs bg-bg-input border border-border-input rounded w-32 outline-none focus:border-blue-500"
                    />
                  </div>

                  <div className="h-4 w-px bg-border-main" />

                  {/* Date filter */}
                  <div className="flex items-center gap-2">
                    <CalendarDays className="w-3.5 h-3.5 text-blue-500" />
                    <span className="text-[10px] uppercase font-bold text-blue-500 tracking-wider">Date Filter</span>
                    <div className="flex items-center gap-1.5">
                      <span className="text-[11px] text-text-muted">Column:</span>
                      <input
                        type="text"
                        placeholder="e.g. created_at"
                        value={focusedMappingObj.dateColumn || ''}
                        onChange={e => updateMappingWithDateFilter(focusedMappingObj.id, { dateColumn: e.target.value })}
                        className={clsx(
                          "px-2 py-1 text-xs bg-bg-input border rounded w-28 outline-none focus:border-blue-500",
                          !focusedMappingObj.dateColumn && (focusedMappingObj.startDate || focusedMappingObj.endDate)
                            ? "border-red-500 bg-red-500/10"
                            : "border-border-input"
                        )}
                        title={!focusedMappingObj.dateColumn && (focusedMappingObj.startDate || focusedMappingObj.endDate) ? "Column name is required to apply date filter!" : ""}
                      />
                    </div>
                    <div className="flex items-center gap-1">
                      <span className="text-[11px] text-text-muted">From:</span>
                      <input
                        type="date"
                        value={focusedMappingObj.startDate || ''}
                        onChange={e => updateMappingWithDateFilter(focusedMappingObj.id, { startDate: e.target.value })}
                        className="px-2 py-1 text-xs bg-bg-input border border-border-input rounded outline-none focus:border-blue-500"
                      />
                    </div>
                    <div className="flex items-center gap-1">
                      <span className="text-[11px] text-text-muted">To:</span>
                      <input
                        type="date"
                        value={focusedMappingObj.endDate || ''}
                        onChange={e => updateMappingWithDateFilter(focusedMappingObj.id, { endDate: e.target.value })}
                        className="px-2 py-1 text-xs bg-bg-input border border-border-input rounded outline-none focus:border-blue-500"
                      />
                    </div>
                  </div>

                  <div className="h-4 w-px bg-border-main" />

                  {/* Row limit */}
                  <div className="flex items-center gap-1.5">
                    <span className="text-[10px] uppercase font-bold text-cyan-600 dark:text-cyan-400 tracking-wider">Limit</span>
                    <input
                      type="number"
                      min={0}
                      placeholder="No limit"
                      value={focusedMappingObj.rowLimit || ''}
                      onChange={e => updateMappingWithDateFilter(
                        focusedMappingObj.id,
                        { rowLimit: e.target.value ? parseInt(e.target.value) : undefined }
                      )}
                      className="px-2 py-1 text-xs bg-bg-input border border-border-input rounded w-24 outline-none focus:border-blue-500"
                    />
                    <span className="text-[11px] text-text-muted">rows</span>
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
                      <span className="text-[10px] font-bold text-text-muted uppercase tracking-wider">Query Preview</span>
                      <div className="grid grid-cols-2 gap-2">
                        <div className="bg-bg-editor rounded px-2 py-1 font-mono text-[11px] text-blue-400 truncate" title={buildEffectiveQuery(focusedMappingObj.sourceTable, focusedMappingObj, 'source')}>
                          <span className="text-[9px] text-blue-500/60 font-bold uppercase mr-1">SRC</span>
                          {buildEffectiveQuery(focusedMappingObj.sourceTable, focusedMappingObj, 'source') || '—'}
                        </div>
                        <div className="bg-bg-editor rounded px-2 py-1 font-mono text-[11px] text-emerald-400 truncate" title={buildEffectiveQuery(focusedMappingObj.targetTable, focusedMappingObj, 'target')}>
                          <span className="text-[9px] text-emerald-500/60 font-bold uppercase mr-1">TGT</span>
                          {buildEffectiveQuery(focusedMappingObj.targetTable, focusedMappingObj, 'target') || '—'}
                        </div>
                      </div>
                    </div>
                  )}

                  {/* Warning banner for missing primary key and sort columns */}
                  {!focusedMappingObj.primaryKeys?.length && !focusedMappingObj.sortColumns?.length && (
                    <div className="w-full mt-1.5 px-3 py-1.5 bg-amber-500/10 border border-amber-500/20 rounded-md text-[11px] text-amber-600 dark:text-amber-400 flex items-center gap-1.5 font-medium animate-in slide-in-from-top-1 duration-200">
                      <AlertTriangle className="w-3.5 h-3.5 text-amber-500 shrink-0" />
                      <span>
                        <strong>No Primary Key or Sort Columns defined:</strong> Comparison will fallback to default database physical ordering (ORDER BY 1) via a surrogate key. This is non-deterministic for views.
                      </span>
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
