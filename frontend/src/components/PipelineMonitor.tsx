import React, { useEffect, useRef, useState } from 'react';
import { Play, Pause, RotateCcw, Trash2, Activity, AlertTriangle, CheckCircle2, ChevronDown, ChevronRight, Search, Settings, Eye, BarChart2, X, Save, Edit3, Code, FileEdit, Database } from 'lucide-react';
import { useAppStore } from '../store/useAppStore';
import { SQLEditor } from './SQLEditor';
import clsx from 'clsx';

interface Pipeline {
  name: string;
  type: string;
  state: string;
  worker_id: string;
  task_state?: string;
  trace?: string;
  lag?: number;
}

export const PipelineMonitor: React.FC = () => {
  const { addToast, showAlert } = useAppStore();
  const [pipelines, setPipelines] = useState<Pipeline[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [selectedTrace, setSelectedTrace] = useState<string | null>(null);
  const [expandedGroups, setExpandedGroups] = useState<Record<string, boolean>>({});
  const [searchQuery, setSearchQuery] = useState('');

  const [configModalOpen, setConfigModalOpen] = useState<string | null>(null);
  const [configData, setConfigData] = useState<string>('');
  
  const [snapshotProgress, setSnapshotProgress] = useState<Record<string, any>>({});
  const [snapshotTimes, setSnapshotTimes] = useState<Record<string, number>>(() => {
    try { return JSON.parse(localStorage.getItem('snapshotTimes') || '{}'); } catch { return {}; }
  });
  
  const [peekModalOpen, setPeekModalOpen] = useState<string | null>(null);
  const [peekData, setPeekData] = useState<any[]>([]);
  const [isPeeking, setIsPeeking] = useState(false);

  const [originalQueries, setOriginalQueries] = useState<Record<string, string>>({});
  const [queryModalOpen, setQueryModalOpen] = useState<{deployId: string, folderName: string, query: string} | null>(null);
  const [isFetchingQuery, setIsFetchingQuery] = useState(false);

  const [editQueryModal, setEditQueryModal] = useState<{deployId: string, folderName: string, query: string, sourceConnectionId: string | null} | null>(null);
  const [editQueryValue, setEditQueryValue] = useState('');
  const [editQueryLogs, setEditQueryLogs] = useState<string[]>([]);
  const [isUpdatingQuery, setIsUpdatingQuery] = useState(false);
  const editLogEndRef = useRef<HTMLDivElement>(null);

  const [renameModalOpen, setRenameModalOpen] = useState<{deployId: string, currentName: string} | null>(null);
  const [newPipelineName, setNewPipelineName] = useState('');
  const [isRenaming, setIsRenaming] = useState(false);
  const [lagHistory, setLagHistory] = useState<Record<string, {time: string, lag: number}[]>>({});
  const [statsModalOpen, setStatsModalOpen] = useState<Pipeline | null>(null);

  // Replication Slots Management State
  const [slotsModalOpen, setSlotsModalOpen] = useState(false);
  const [replicationSlots, setReplicationSlots] = useState<any[]>([]);
  const [isLoadingSlots, setIsLoadingSlots] = useState(false);
  const [isCleaningSlots, setIsCleaningSlots] = useState(false);

  const fetchReplicationSlots = async () => {
    setIsLoadingSlots(true);
    try {
      const res = await fetch('/api/dwh/replication-slots');
      if (res.ok) {
        const data = await res.json();
        setReplicationSlots(data);
      } else {
        addToast({ type: 'error', title: 'Error', message: 'Failed to fetch replication slots.' });
      }
    } catch (e) {
      addToast({ type: 'error', title: 'Error', message: 'Could not connect to server.' });
    } finally {
      setIsLoadingSlots(false);
    }
  };

  const handleOpenSlotsModal = () => {
    setSlotsModalOpen(true);
    fetchReplicationSlots();
  };

  const handleCleanupSlots = async (slotName?: string, inactiveOnly: boolean = true) => {
    setIsCleaningSlots(true);
    try {
      let url = `/api/dwh/replication-slots/cleanup?inactiveOnly=${inactiveOnly}`;
      if (slotName) {
        url += `&slotName=${encodeURIComponent(slotName)}`;
      }
      const res = await fetch(url, { method: 'POST' });
      if (res.ok) {
        const result = await res.json();
        const dropped: string[] = result.droppedSlots || [];
        if (dropped.length > 0) {
          addToast({
            type: 'success',
            title: 'Replication Slots Cleaned',
            message: `Successfully dropped ${dropped.length} slot(s): ${dropped.join(', ')}`
          });
        } else {
          addToast({
            type: 'info',
            title: 'No Slots Dropped',
            message: 'No matching inactive replication slots were found to drop.'
          });
        }
        fetchReplicationSlots();
      } else {
        addToast({ type: 'error', title: 'Cleanup Failed', message: 'Failed to drop replication slot(s).' });
      }
    } catch (e) {
      addToast({ type: 'error', title: 'Cleanup Error', message: 'Error performing replication slots cleanup.' });
    } finally {
      setIsCleaningSlots(false);
    }
  };

  const filteredPipelines = pipelines.filter(p => p.name.toLowerCase().includes(searchQuery.toLowerCase()));

  const toggleGroup = async (deployId: string) => {
    const isExpanding = !expandedGroups[deployId];
    setExpandedGroups(prev => ({
      ...prev,
      [deployId]: isExpanding
    }));
  };

  const openQueryModal = async (deployId: string, folderName: string) => {
    if (originalQueries[deployId]) {
      setQueryModalOpen({ deployId, folderName, query: originalQueries[deployId] });
      return;
    }
    
    setIsFetchingQuery(true);
    setQueryModalOpen({ deployId, folderName, query: 'Loading...' });
    try {
      const res = await fetch(`/api/dwh/pipelines/query/${deployId}`);
      if (res.ok) {
        const data = await res.json();
        if (data.query) {
          setOriginalQueries(prev => ({ ...prev, [deployId]: data.query }));
          setQueryModalOpen({ deployId, folderName, query: data.query });
        } else {
          setQueryModalOpen({ deployId, folderName, query: 'No query found.' });
        }
      } else {
        setQueryModalOpen({ deployId, folderName, query: 'Failed to fetch query.' });
      }
    } catch (e) {
      setQueryModalOpen({ deployId, folderName, query: 'Error fetching query.' });
    } finally {
      setIsFetchingQuery(false);
    }
  };

  const openEditQueryModal = async (deployId: string, folderName: string) => {
    const query = originalQueries[deployId] || '';
    let sourceConnectionId: string | null = null;
    let finalQuery = query;

    if (!query) {
      try {
        const res = await fetch(`/api/dwh/pipelines/metadata/${deployId}`);
        if (res.ok) {
          const meta = await res.json();
          finalQuery = meta.query || '';
          sourceConnectionId = meta.source_connection_id || null;
          if (finalQuery) setOriginalQueries(prev => ({ ...prev, [deployId]: finalQuery }));
        }
      } catch (e) { /* ignore */ }
    } else {
      try {
        const res = await fetch(`/api/dwh/pipelines/metadata/${deployId}`);
        if (res.ok) { const meta = await res.json(); sourceConnectionId = meta.source_connection_id || null; }
      } catch (e) { /* ignore */ }
    }

    setEditQueryValue(finalQuery);
    setEditQueryLogs([]);
    setEditQueryModal({ deployId, folderName, query: finalQuery, sourceConnectionId });
  };

  const handleUpdateQuery = async () => {
    if (!editQueryModal || !editQueryValue.trim()) return;
    
    if (editQueryModal.query.trim() === editQueryValue.trim()) {
      addToast({ type: 'warning', title: 'No Changes', message: 'Query has not been modified. Update cancelled.' });
      return;
    }

    setIsUpdatingQuery(true);
    setEditQueryLogs([`[${new Date().toLocaleTimeString()}] Starting query update...`]);

    try {
      const res = await fetch(`/api/dwh/pipelines/update-query/${editQueryModal.deployId}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ query: editQueryValue })
      });

      if (!res.ok) throw new Error(await res.text());

      const reader = res.body?.getReader();
      if (!reader) throw new Error('No stream');
      const decoder = new TextDecoder();

      while (true) {
        const { value, done } = await reader.read();
        if (done) break;
        const chunk = decoder.decode(value, { stream: true });
        for (const line of chunk.split('\n')) {
          if (line.startsWith('data:')) {
            const msg = line.substring(5).trim();
            if (msg) {
              setEditQueryLogs(prev => [...prev, `[${new Date().toLocaleTimeString()}] ${msg}`]);
              setTimeout(() => editLogEndRef.current?.scrollIntoView({ behavior: 'smooth' }), 50);
            }
          }
        }
      }
      setOriginalQueries(prev => ({ ...prev, [editQueryModal.deployId]: editQueryValue }));
      addToast({ type: 'success', title: 'Query Updated', message: 'Pipeline schema evolution complete.' });
    } catch (e: any) {
      setEditQueryLogs(prev => [...prev, `[${new Date().toLocaleTimeString()}] ERROR: ${e.message}`]);
      addToast({ type: 'error', title: 'Update Failed', message: e.message });
    } finally {
      setIsUpdatingQuery(false);
    }
  };

  const fetchPipelines = async () => {
    try {
      const response = await fetch('/api/dwh/pipelines');
      if (response.ok) {
        const data = await response.json();
        setPipelines(data);

        // Update lag history
        const now = new Date().toLocaleTimeString();
        setLagHistory(prev => {
          const next = { ...prev };
          data.forEach((p: Pipeline) => {
            if (p.lag !== undefined) {
              if (!next[p.name]) next[p.name] = [];
              next[p.name] = [...next[p.name].slice(-20), { time: now, lag: p.lag }];
            }
          });
          return next;
        });

        const dIds = new Set<string>();
        data.forEach((p: Pipeline) => {
          const lastDash = p.name.lastIndexOf('-');
          const tsStr = p.name.slice(lastDash + 1);
          if (lastDash > 0 && !isNaN(Number(tsStr)) && tsStr.length >= 10) {
             const existingKey = Array.from(dIds).find(k => Math.abs(Number(k) - Number(tsStr)) <= 2000);
             if (existingKey) {
                 dIds.add(existingKey);
             } else {
                 dIds.add(tsStr);
             }
          }
        });

        dIds.forEach(deployId => {
          fetch(`/api/dwh/pipelines/progress/${deployId}`)
            .then(r => r.json())
            .then(res => {
               if (!res.error) {
                  setSnapshotProgress(prev => {
                     if (prev[deployId] && prev[deployId].targetCount === res.targetCount && prev[deployId].percentage === res.percentage && prev[deployId].snapshotCompleted === res.snapshotCompleted) {
                         return prev;
                     }
                     return { ...prev, [deployId]: res };
                  });
                  
                  if (res.snapshotCompleted) {
                     setSnapshotTimes(prev => {
                        if (!prev[deployId]) {
                           const updated = { ...prev, [deployId]: Date.now() };
                           localStorage.setItem('snapshotTimes', JSON.stringify(updated));
                           return updated;
                        }
                        return prev;
                     });
                  }
               }
            })
            .catch(() => {});
        });
      }
    } catch (error) {
      console.error('Failed to fetch pipelines', error);
    } finally {
      setIsLoading(false);
    }
  };

  useEffect(() => {
    fetchPipelines();
    const interval = setInterval(fetchPipelines, 5000);
    return () => clearInterval(interval);
  }, []);

  const openConfig = async (connectorName: string) => {
    try {
      const res = await fetch(`/api/dwh/pipelines/${connectorName}/config`);
      if (res.ok) {
        const data = await res.json();
        setConfigData(JSON.stringify(data, null, 2));
        setConfigModalOpen(connectorName);
      }
    } catch (e) {
      addToast({ type: 'error', title: 'Error', message: 'Failed to fetch config' });
    }
  };

  const saveConfig = async () => {
    if (!configModalOpen) return;
    try {
      const parsed = JSON.parse(configData);
      const res = await fetch(`/api/dwh/pipelines/${configModalOpen}/config`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(parsed)
      });
      if (res.ok) {
        addToast({ type: 'success', title: 'Success', message: 'Config updated' });
        setConfigModalOpen(null);
      }
    } catch (e) {
      addToast({ type: 'error', title: 'Error', message: 'Invalid JSON or failed to update' });
    }
  };

  const openPeek = async (connectorName: string) => {
    setPeekModalOpen(connectorName);
    setIsPeeking(true);
    setPeekData([]);
    try {
      const res = await fetch(`/api/dwh/pipelines/${connectorName}/peek`);
      if (res.ok) {
        const data = await res.json();
        setPeekData(data);
      }
    } catch (e) {
      addToast({ type: 'error', title: 'Error', message: 'Failed to peek topic' });
    } finally {
      setIsPeeking(false);
    }
  };

  const handleAction = async (connectorName: string, action: string) => {
    try {
      const response = await fetch(`/api/dwh/pipelines/${connectorName}/action?action=${action}`, { method: 'POST' });
      if (response.ok) {
        addToast({ type: 'success', title: 'Action Successful', message: `Connector ${connectorName} ${action}ed.` });
        fetchPipelines();
      } else {
        throw new Error('Failed action');
      }
    } catch (error) {
      addToast({ type: 'error', title: 'Action Failed', message: `Could not ${action} connector.` });
    }
  };

  const handleDelete = (connectorName: string) => {
    showAlert({
      title: 'Delete Connector',
      message: `Are you sure you want to delete the connector "${connectorName}"? This action cannot be undone.`,
      type: 'error',
      confirmLabel: 'Delete',
      onConfirm: async () => {
        try {
          const response = await fetch(`/api/dwh/pipelines/${connectorName}`, { method: 'DELETE' });
          if (response.ok) {
            addToast({ type: 'success', title: 'Deleted', message: `Connector ${connectorName} deleted.` });
            fetchPipelines();
          } else {
            throw new Error('Delete failed');
          }
        } catch (error) {
          addToast({ type: 'error', title: 'Delete Failed', message: 'Could not delete connector.' });
        }
      }
    });
  };

  const handleDeletePipeline = (deployId: string, folderName: string) => {
    showAlert({
      title: 'Delete Pipeline',
      message: `Are you sure you want to delete "${folderName}"? This will delete all connectors, materialized views, landing tables, and the target table. This action cannot be undone.`,
      type: 'error',
      confirmLabel: 'Delete Pipeline',
      onConfirm: async () => {
        try {
          const response = await fetch(`/api/dwh/pipelines/group/${deployId}`, { method: 'DELETE' });
          if (response.ok) {
            addToast({ type: 'success', title: 'Deleted', message: `Pipeline deleted successfully.` });
            fetchPipelines();
          } else {
            throw new Error('Delete failed');
          }
        } catch (error) {
          addToast({ type: 'error', title: 'Delete Failed', message: 'Could not delete pipeline.' });
        }
      }
    });
  };

  const handleRename = async () => {
    if (!renameModalOpen || !newPipelineName.trim()) return;
    if (newPipelineName.trim() === renameModalOpen.currentName) {
      addToast({ type: 'warning', title: 'No Changes', message: 'Pipeline name is still the same.' });
      return;
    }
    setIsRenaming(true);
    try {
      const res = await fetch(`/api/dwh/pipelines/rename/${renameModalOpen.deployId}?newName=${encodeURIComponent(newPipelineName.trim())}`, { method: 'POST' });
      if (res.ok) {
        addToast({ type: 'success', title: 'Success', message: 'Pipeline renamed successfully.' });
        setRenameModalOpen(null);
        fetchPipelines();
      } else {
        const text = await res.text();
        addToast({ type: 'error', title: 'Rename Failed', message: text });
      }
    } catch (e) {
      addToast({ type: 'error', title: 'Rename Failed', message: 'Could not rename pipeline.' });
    } finally {
      setIsRenaming(false);
    }
  };

  const StatusBadge = ({ state }: { state: string }) => {
    const isRunning = state === 'RUNNING';
    const isFailed = state === 'FAILED';
    const isPaused = state === 'PAUSED';

    return (
      <span className={clsx(
        "px-2 py-1 rounded-md text-[10px] font-bold tracking-wider uppercase flex items-center gap-1 w-max",
        isRunning ? "bg-emerald-500/10 text-emerald-500" :
        isFailed ? "bg-red-500/10 text-red-500" :
        isPaused ? "bg-amber-500/10 text-amber-500" :
        "bg-gray-500/10 text-gray-500"
      )}>
        {isRunning && <CheckCircle2 className="w-3 h-3" />}
        {isFailed && <AlertTriangle className="w-3 h-3" />}
        {isPaused && <Pause className="w-3 h-3" />}
        {state}
      </span>
    );
  };

  return (
    <div className="flex flex-col h-full bg-bg-panel rounded-xl border border-border-main overflow-hidden">
      <div className="bg-bg-header border-b border-border-main px-4 py-3 flex flex-col gap-2.5">
        <div className="flex items-center justify-between">
          <h3 className="text-sm font-bold text-text-main flex items-center gap-2">
            <Activity className="w-4 h-4 text-indigo-500" /> Active Pipelines
          </h3>
          <div className="flex items-center gap-2">
            <button 
              onClick={handleOpenSlotsModal} 
              className="text-[11px] text-amber-400 hover:text-amber-300 font-bold uppercase tracking-wide px-2.5 py-1 bg-amber-500/10 hover:bg-amber-500/20 border border-amber-500/20 rounded flex items-center gap-1.5 transition-colors"
              title="Manage & Clean PostgreSQL WAL Replication Slots"
            >
              <Database className="w-3.5 h-3.5" /> WAL Slots
            </button>
            <button onClick={fetchPipelines} className="text-[11px] text-text-muted hover:text-indigo-400 font-bold uppercase tracking-wide px-2 py-1 bg-indigo-500/10 rounded">
              Refresh
            </button>
          </div>
        </div>
        <div className="relative">
          <Search className="w-4 h-4 absolute left-2.5 top-1/2 -translate-y-1/2 text-text-muted" />
          <input
            type="text"
            placeholder="Search pipelines..."
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            className="w-full bg-bg-panel border border-border-main text-text-main text-xs rounded-md pl-8 pr-3 py-1.5 focus:outline-none focus:border-indigo-500 transition-colors"
          />
        </div>
      </div>

      <div className="flex-1 overflow-auto p-4">
        {isLoading ? (
          <div className="flex justify-center items-center h-full text-text-muted">Loading pipelines...</div>
        ) : pipelines.length === 0 ? (
          <div className="flex justify-center items-center h-full text-text-muted text-sm italic">
            No active pipelines found. Deploy one to get started.
          </div>
        ) : filteredPipelines.length === 0 ? (
          <div className="flex justify-center items-center h-full text-text-muted text-sm italic">
            No pipelines match your search.
          </div>
        ) : (
          <div className="space-y-4">
            {Object.entries(
              filteredPipelines.reduce((acc, p) => {
                const lastDash = p.name.lastIndexOf('-');
                const tsStr = p.name.slice(lastDash + 1);
                const isTimestamp = lastDash > 0 && !isNaN(Number(tsStr)) && tsStr.length >= 10;
                
                if (isTimestamp) {
                  const ts = Number(tsStr);
                  // Find if there is an existing group within 2 seconds (to handle legacy connectors)
                  const existingKey = Object.keys(acc).find(k => k !== 'Legacy' && Math.abs(Number(k) - ts) <= 2000);
                  const deployId = existingKey ? existingKey : tsStr;
                  
                  if (!acc[deployId]) acc[deployId] = [];
                  acc[deployId].push(p);
                } else {
                  if (!acc['Legacy']) acc['Legacy'] = [];
                  acc['Legacy'].push(p);
                }
                return acc;
              }, {} as Record<string, Pipeline[]>)
            ).sort((a, b) => b[0].localeCompare(a[0])).map(([deployId, groupPipelines]) => {
              // Try to find target table name from sink connector
              const sink = groupPipelines.find(p => p.name.startsWith('sink-clickhouse-'));
              let folderName = `Deployment ID: ${deployId}`;
              if (sink) {
                const parts = sink.name.split('-');
                if (parts.length >= 3) {
                  const targetTable = parts.slice(2, -1).join('-');
                  folderName = `Pipeline: ${targetTable}`;
                }
              }

              return (
              <div key={deployId} className="bg-bg-main border border-border-main rounded-xl overflow-hidden">
                <div 
                  className="bg-bg-header/50 border-b border-border-main px-4 py-3 flex flex-col cursor-pointer hover:bg-bg-header/80 transition-colors"
                  onClick={() => toggleGroup(deployId)}
                >
                  <div className="flex items-start justify-between w-full">
                    <div className="flex items-start gap-2 flex-1 min-w-0 pr-3">
                      <div className="mt-1 flex-shrink-0">
                        {!expandedGroups[deployId] ? (
                          <ChevronRight className="w-4 h-4 text-text-muted" />
                        ) : (
                          <ChevronDown className="w-4 h-4 text-text-muted" />
                        )}
                      </div>
                      <span className="text-xl flex-shrink-0">🗂️</span>
                      <h4 className="font-bold text-[13px] text-text-main break-all mt-1">
                        {folderName}
                        {deployId !== 'Legacy' && <span className="text-text-muted font-normal text-[11px] ml-2 inline-block">(ID: {deployId})</span>}
                      </h4>
                    </div>
                    <div className="flex-shrink-0 mt-1">
                      <span className="text-[11px] font-bold text-text-muted bg-bg-panel px-2 py-1 rounded">
                        {groupPipelines.length} Connector(s)
                      </span>
                    </div>
                  </div>
                  
                  {deployId !== 'Legacy' && (
                    <div className="flex items-center gap-2 mt-3 ml-7">
                      <button 
                        onClick={(e) => { e.stopPropagation(); openQueryModal(deployId, folderName); }} 
                        className="p-1 rounded bg-indigo-500/10 text-indigo-400 hover:bg-indigo-500 hover:text-white transition-colors tooltip"
                        title="View Original Query"
                      >
                        <Code className="w-3.5 h-3.5" />
                      </button>
                      <button 
                        onClick={(e) => { e.stopPropagation(); openEditQueryModal(deployId, folderName); }} 
                        className="p-1 rounded bg-purple-500/10 text-purple-400 hover:bg-purple-500 hover:text-white transition-colors tooltip"
                        title="Edit Query & Sync Schema"
                      >
                        <FileEdit className="w-3.5 h-3.5" />
                      </button>
                      <button 
                        onClick={(e) => { e.stopPropagation(); const curName = folderName.replace('Pipeline: ', ''); setRenameModalOpen({ deployId, currentName: curName }); setNewPipelineName(curName); }} 
                        className="p-1 rounded bg-amber-500/10 text-amber-500 hover:bg-amber-500 hover:text-white transition-colors tooltip"
                        title="Rename Pipeline"
                      >
                        <Edit3 className="w-3.5 h-3.5" />
                      </button>
                      <button 
                        onClick={(e) => { e.stopPropagation(); handleDeletePipeline(deployId, folderName); }} 
                        className="p-1 rounded bg-red-500/10 text-red-500 hover:bg-red-500 hover:text-white transition-colors tooltip"
                        title="Delete Entire Pipeline"
                      >
                        <Trash2 className="w-3.5 h-3.5" />
                      </button>
                    </div>
                  )}
                </div>
                
                {expandedGroups[deployId] && (
                  <div className="p-3 space-y-3 bg-bg-main">
                  
                  {snapshotProgress[deployId] && !snapshotProgress[deployId].snapshotCompleted && snapshotProgress[deployId].sourceCount !== -1 && (
                    <div className="bg-bg-panel border border-border-main p-3 rounded-lg flex flex-col gap-2">
                       <div className="flex justify-between items-center">
                         <span className="text-xs font-bold text-text-main flex items-center gap-2">
                           <Settings className="w-3 h-3 text-indigo-500 animate-spin"/> 
                           Snapshotting Initial Data
                         </span>
                         <span className="text-xs font-mono text-text-muted">
                           {snapshotProgress[deployId].targetCount?.toLocaleString() || 0} / {snapshotProgress[deployId].sourceCount > 0 ? snapshotProgress[deployId].sourceCount.toLocaleString() : '?'} records
                         </span>
                       </div>
                       <div className="w-full h-1.5 bg-bg-main rounded-full overflow-hidden border border-border-main">
                         <div className="h-full bg-indigo-500 transition-all duration-500" style={{ width: `${snapshotProgress[deployId].percentage || 0}%` }}></div>
                       </div>
                    </div>
                  )}
                  {snapshotProgress[deployId] && snapshotProgress[deployId].snapshotCompleted && (
                    <div className="flex items-center gap-2 px-1 mb-2">
                      <CheckCircle2 className="w-3 h-3 text-emerald-500" />
                      <span className="text-[10px] text-text-muted italic">
                        Snapshot finished {snapshotTimes[deployId] ? `in ${Math.max(1, Math.round((snapshotTimes[deployId] - Number(deployId)) / 60000))} minutes` : `(${snapshotProgress[deployId].targetCount?.toLocaleString()} records)`}
                      </span>
                    </div>
                  )}

                  {groupPipelines.map(p => (
                    <div key={p.name} className="bg-bg-panel border border-border-main rounded-lg p-3 hover:border-indigo-500/30 transition-colors">
                      <div className="flex items-start justify-between mb-2">
                        <div className="flex flex-col flex-1 min-w-0 pr-3">
                          <span className="font-bold text-[13px] text-text-main break-all" title={p.name}>{p.name}</span>
                          <span className="text-[11px] text-text-muted mt-0.5 flex items-center gap-2">
                            <span>Type: {p.type}</span>
                            {p.lag !== undefined && (
                              <span className={clsx("px-1.5 py-0.5 rounded font-bold", p.lag > 0 ? "bg-amber-500/10 text-amber-500" : "bg-emerald-500/10 text-emerald-500")}>
                                {p.lag > 0 ? `⚠️ Lagging: ${p.lag.toLocaleString()} records` : `⚡ Synced`}
                              </span>
                            )}
                          </span>
                        </div>
                        <div className="flex items-center gap-2 flex-shrink-0 mt-0.5">
                          <StatusBadge state={p.state} />
                          {p.task_state && p.task_state !== p.state && <StatusBadge state={p.task_state} />}
                        </div>
                      </div>

                      <div className="flex items-center justify-between mt-3">
                        <div className="flex items-center gap-1.5">
                          <button onClick={() => handleAction(p.name, 'pause')} disabled={p.state === 'PAUSED'} className="p-1.5 rounded-md hover:bg-amber-500/10 text-text-muted hover:text-amber-500 disabled:opacity-30 tooltip" title="Pause">
                            <Pause className="w-4 h-4" />
                          </button>
                          <button onClick={() => handleAction(p.name, 'resume')} disabled={p.state === 'RUNNING'} className="p-1.5 rounded-md hover:bg-emerald-500/10 text-text-muted hover:text-emerald-500 disabled:opacity-30 tooltip" title="Resume">
                            <Play className="w-4 h-4" />
                          </button>
                          <button onClick={() => handleAction(p.name, 'restart')} className="p-1.5 rounded-md hover:bg-indigo-500/10 text-text-muted hover:text-indigo-500 tooltip" title="Restart">
                            <RotateCcw className="w-4 h-4" />
                          </button>
                          <div className="w-px h-4 bg-border-main mx-1" />
                          <button onClick={() => handleDelete(p.name)} className="p-1.5 rounded-md hover:bg-red-500/10 text-text-muted hover:text-red-500 tooltip" title="Delete">
                            <Trash2 className="w-4 h-4" />
                          </button>
                          <div className="w-px h-4 bg-border-main mx-1" />
                          <button onClick={() => openConfig(p.name)} className="p-1.5 rounded-md hover:bg-indigo-500/10 text-text-muted hover:text-indigo-500 tooltip" title="Edit Config">
                            <Settings className="w-4 h-4" />
                          </button>
                          <button onClick={() => openPeek(p.name)} className="p-1.5 rounded-md hover:bg-indigo-500/10 text-text-muted hover:text-indigo-500 tooltip" title="Peek Data (Kafka)">
                            <Eye className="w-4 h-4" />
                          </button>
                          {p.lag !== undefined && (
                            <button onClick={() => setStatsModalOpen(p)} className="p-1.5 rounded-md hover:bg-indigo-500/10 text-text-muted hover:text-indigo-500 tooltip" title="View Lag Stats">
                              <BarChart2 className="w-4 h-4" />
                            </button>
                          )}
                        </div>
                        
                        {p.trace && (
                          <button 
                            onClick={() => setSelectedTrace(p.trace || null)}
                            className="text-[11px] text-red-400 hover:text-red-300 underline font-semibold"
                          >
                            View Error Trace
                          </button>
                        )}
                      </div>
                    </div>
                  ))}
                  </div>
                )}
              </div>
              );
            })}
          </div>
        )}
      </div>

      {configModalOpen && (
        <div className="fixed inset-0 bg-black/60 backdrop-blur-sm z-50 flex items-center justify-center p-4">
          <div className="bg-bg-panel w-full max-w-3xl rounded-xl shadow-2xl flex flex-col border border-border-main">
            <div className="px-5 py-4 border-b border-border-main flex justify-between items-center">
              <h3 className="font-bold text-text-main flex items-center gap-2"><Settings className="w-5 h-5" /> Config JSON: {configModalOpen}</h3>
              <button onClick={() => setConfigModalOpen(null)} className="text-text-muted hover:text-text-main"><X className="w-5 h-5" /></button>
            </div>
            <div className="p-4">
              <textarea 
                className="w-full h-[60vh] bg-bg-editor text-text-main font-mono text-sm p-4 rounded-lg border border-border-main focus:border-indigo-500 outline-none"
                value={configData}
                onChange={e => setConfigData(e.target.value)}
                spellCheck={false}
              />
            </div>
            <div className="px-5 py-4 border-t border-border-main flex justify-end gap-3">
              <button onClick={() => setConfigModalOpen(null)} className="px-4 py-2 rounded-lg text-sm font-bold bg-rose-50 hover:bg-rose-100 dark:bg-rose-500/10 dark:hover:bg-rose-500/20 border border-rose-200 dark:border-rose-500/30 text-rose-600 dark:text-rose-400 transition-colors">Cancel</button>
              <button onClick={saveConfig} className="px-4 py-2 rounded-lg text-sm font-bold bg-indigo-600 text-white hover:bg-indigo-500 flex items-center gap-2"><Save className="w-4 h-4" /> Save & Restart</button>
            </div>
          </div>
        </div>
      )}

      {peekModalOpen && (
        <div className="fixed inset-0 bg-black/60 backdrop-blur-sm z-50 flex items-center justify-center p-4">
          <div className="bg-bg-panel w-full max-w-4xl rounded-xl shadow-2xl flex flex-col border border-border-main">
            <div className="px-5 py-4 border-b border-border-main flex justify-between items-center">
              <h3 className="font-bold text-text-main flex items-center gap-2"><Eye className="w-5 h-5" /> Topic Sampler: {peekModalOpen}</h3>
              <button onClick={() => setPeekModalOpen(null)} className="text-text-muted hover:text-text-main"><X className="w-5 h-5" /></button>
            </div>
            <div className="p-5 overflow-auto max-h-[70vh] bg-slate-100 dark:bg-[#0d1117] text-slate-800 dark:text-[#c9d1d9] font-mono text-xs whitespace-pre-wrap rounded-b-xl border-t border-border-main">
              {isPeeking ?
                <div className="flex items-center gap-2 text-indigo-600 dark:text-indigo-400"><Activity className="w-4 h-4 animate-spin" /> Consuming latest messages from Kafka...</div>
              : peekData.length === 0 ? (
                <div className="text-slate-500 dark:text-gray-400">No messages found or topic is empty.</div>
              ) : (
                peekData.map((msg, i) => (
                  <div key={i} className="mb-4 pb-4 border-b border-slate-300 dark:border-white/10 last:border-0 last:mb-0 last:pb-0 break-words">
                    {msg.error ? (
                      <div className="text-red-600 dark:text-red-400 bg-red-100 dark:bg-red-950/30 p-3 rounded">{msg.error}</div>
                    ) : (
                      <>
                        <div className="text-indigo-600 dark:text-indigo-400 mb-1">Offset: {msg.offset} | Partition: {msg.partition} | Time: {new Date(msg.timestamp).toLocaleString()}</div>
                        <div className="text-emerald-600 dark:text-emerald-400">Key: {msg.key}</div>
                        <div className="mt-1">{msg.value && msg.value.startsWith('{') ? JSON.stringify(JSON.parse(msg.value), null, 2) : msg.value}</div>
                      </>
                    )}
                  </div>
                ))
              )}
            </div>
          </div>
        </div>
      )}

      {statsModalOpen && (
        <div className="fixed inset-0 bg-black/60 backdrop-blur-sm z-50 flex items-center justify-center p-4">
          <div className="bg-bg-panel w-full max-w-2xl rounded-xl shadow-2xl flex flex-col border border-border-main">
            <div className="px-5 py-4 border-b border-border-main flex justify-between items-center">
              <h3 className="font-bold text-text-main flex items-center gap-2"><BarChart2 className="w-5 h-5" /> Historical Lag: {statsModalOpen.name}</h3>
              <button onClick={() => setStatsModalOpen(null)} className="text-text-muted hover:text-text-main"><X className="w-5 h-5" /></button>
            </div>
            <div className="p-5 h-[300px] flex items-end gap-2 overflow-x-auto relative bg-bg-header/50 rounded-b-xl">
              {(lagHistory[statsModalOpen.name] || []).length === 0 ? (
                <div className="text-text-muted w-full text-center mb-20">Waiting for data...</div>
              ) : (
                (lagHistory[statsModalOpen.name] || []).map((pt, i, arr) => {
                  const maxLag = Math.max(...arr.map(a => a.lag), 10);
                  const hPct = Math.max(5, (pt.lag / maxLag) * 100);
                  return (
                    <div key={i} className="flex-1 flex flex-col justify-end items-center group relative min-w-[30px] h-full">
                      <div className="absolute top-2 bg-bg-panel px-2 py-1 rounded text-[10px] opacity-0 group-hover:opacity-100 whitespace-nowrap z-10 border border-border-main">
                        {pt.time} - {pt.lag.toLocaleString()} recs
                      </div>
                      <div 
                        className="w-full bg-indigo-500 rounded-t-sm transition-all duration-300" 
                        style={{ height: `${hPct}%`, opacity: pt.lag === 0 ? 0.3 : 1 }}
                      />
                      <div className="text-[9px] text-text-muted mt-2 truncate w-full text-center">{pt.time.split(' ')[0]}</div>
                    </div>
                  );
                })
              )}
            </div>
          </div>
        </div>
      )}

      {selectedTrace && (
        <div className="fixed inset-0 bg-black/60 backdrop-blur-sm z-50 flex items-center justify-center p-4">
          <div className="bg-bg-panel w-full max-w-4xl max-h-[85vh] rounded-xl shadow-2xl flex flex-col border border-border-main">
            <div className="px-5 py-4 border-b border-border-main flex justify-between items-center bg-red-500/10">
              <h3 className="font-bold text-red-500 flex items-center gap-2"><AlertTriangle className="w-5 h-5" /> Error Trace</h3>
              <button onClick={() => setSelectedTrace(null)} className="text-text-muted hover:text-text-main text-2xl leading-none">&times;</button>
            </div>
            <div className="p-5 overflow-auto bg-bg-editor font-mono text-[11px] text-red-400 whitespace-pre-wrap border-t border-border-main">
              {selectedTrace}
            </div>
          </div>
        </div>
      )}

      {renameModalOpen && (
        <div className="fixed inset-0 bg-black/60 backdrop-blur-sm z-50 flex items-center justify-center p-4">
          <div className="bg-bg-panel w-full max-w-md rounded-xl shadow-2xl flex flex-col border border-border-main">
            <div className="px-5 py-4 border-b border-border-main flex justify-between items-center">
              <h3 className="font-bold text-text-main flex items-center gap-2"><Edit3 className="w-5 h-5 text-amber-500" /> Rename Pipeline</h3>
              <button onClick={() => setRenameModalOpen(null)} className="text-text-muted hover:text-text-main"><X className="w-5 h-5" /></button>
            </div>
            <div className="p-5 flex flex-col gap-4">
              <p className="text-xs text-text-muted leading-relaxed">
                Enter a new name for the pipeline. This will rename the target table, convenience views, and materialized views in ClickHouse.
              </p>
              <div className="flex flex-col gap-1.5">
                <label className="text-[11px] font-bold text-text-muted uppercase">New Pipeline Name</label>
                <input
                  type="text"
                  className="w-full bg-bg-main border border-border-input hover:border-indigo-500/50 rounded-lg text-sm px-3 py-2 text-text-main focus:outline-none focus:border-indigo-500 transition-colors"
                  placeholder="e.g. dwh_sales_fact_new"
                  value={newPipelineName}
                  onChange={(e) => setNewPipelineName(e.target.value)}
                  autoFocus
                />
              </div>
            </div>
            <div className="px-5 py-4 border-t border-border-main flex justify-end gap-3 bg-bg-header/50 rounded-b-xl">
              <button onClick={() => setRenameModalOpen(null)} className="px-4 py-2 rounded-lg text-sm font-bold bg-rose-50 hover:bg-rose-100 dark:bg-rose-500/10 dark:hover:bg-rose-500/20 border border-rose-200 dark:border-rose-500/30 text-rose-600 dark:text-rose-400 transition-colors disabled:opacity-50" disabled={isRenaming}>Cancel</button>
              <button onClick={handleRename} disabled={isRenaming || !newPipelineName.trim() || newPipelineName.trim() === renameModalOpen.currentName} className="px-4 py-2 rounded-lg text-sm font-bold bg-amber-500 hover:bg-amber-600 text-white disabled:opacity-50 transition-colors flex items-center gap-2">
                {isRenaming ? <Activity className="w-4 h-4 animate-spin" /> : <Save className="w-4 h-4" />}
                {isRenaming ? 'Renaming...' : 'Rename Pipeline'}
              </button>
            </div>
          </div>
        </div>
      )}

      {queryModalOpen && (
        <div className="fixed inset-0 bg-black/60 backdrop-blur-sm z-50 flex items-center justify-center p-4">
          <div className="bg-bg-panel w-full max-w-4xl rounded-xl shadow-2xl flex flex-col border border-border-main">
            <div className="px-5 py-4 border-b border-border-main flex justify-between items-center bg-indigo-500/10">
              <h3 className="font-bold text-indigo-400 flex items-center gap-2"><Code className="w-5 h-5" /> Original Deployment Query: {queryModalOpen.folderName}</h3>
              <button onClick={() => setQueryModalOpen(null)} className="text-text-muted hover:text-text-main text-2xl leading-none">&times;</button>
            </div>
            <div className="h-[70vh] flex flex-col bg-bg-editor rounded-b-xl overflow-hidden min-h-0 relative">
              {isFetchingQuery && queryModalOpen.query === 'Loading...' ? (
                <div className="flex items-center justify-center h-full gap-2 text-indigo-400 font-mono text-sm">
                  <Activity className="w-4 h-4 animate-spin" /> Fetching query...
                </div>
              ) : (
                <SQLEditor
                  value={queryModalOpen.query}
                  readOnly={true}
                  showMaximize={false}
                />
              )}
            </div>
          </div>
        </div>
      )}

      {editQueryModal && (
        <div className="fixed inset-0 bg-black/60 backdrop-blur-sm z-[60] flex items-center justify-center p-4">
          <div className="bg-bg-panel w-full max-w-5xl rounded-xl shadow-2xl flex flex-col border border-border-main" style={{maxHeight: '90vh'}}>
            <div className="px-5 py-4 border-b border-border-main flex justify-between items-center bg-purple-500/10">
              <h3 className="font-bold text-purple-300 flex items-center gap-2"><FileEdit className="w-5 h-5" /> Edit Query & Sync Schema: <span className="text-text-main font-normal">{editQueryModal.folderName}</span></h3>
              <button onClick={() => { if (!isUpdatingQuery) setEditQueryModal(null); }} className="text-text-muted hover:text-text-main"><X className="w-5 h-5" /></button>
            </div>

            <div className="flex flex-col flex-1 min-h-0 overflow-hidden">
              <div className="p-3 bg-amber-500/5 border-b border-amber-500/20">
                <p className="text-xs text-amber-400 leading-relaxed">
                  ⚡ <strong>Schema Evolution:</strong> Columns you <strong>add</strong> will be backfilled from source data. Columns you <strong>remove</strong> will be dropped from ClickHouse. Materialized Views will be automatically recreated.
                </p>
              </div>

              <div style={{height: '280px'}} className="border-b border-border-main flex-shrink-0">
                <SQLEditor
                  value={editQueryValue}
                  onChange={setEditQueryValue}
                  connectionId={editQueryModal.sourceConnectionId}
                  height="280px"
                  showMaximize={false}
                  placeholder="Edit your source query..."
                />
              </div>

              {editQueryLogs.length > 0 && (
                <div className="flex-1 min-h-0 overflow-y-auto bg-bg-editor border-t border-border-main p-4 font-mono text-[11px] custom-scrollbar">
                  {editQueryLogs.map((log, i) => (
                    <div key={i} className={clsx('leading-relaxed', log.includes('ERROR') ? 'text-red-400' : log.includes('✅') ? 'text-emerald-400' : log.includes('WARNING') ? 'text-amber-400' : 'text-slate-300')}>
                      {log}
                    </div>
                  ))}
                  <div ref={editLogEndRef} />
                </div>
              )}
            </div>

            <div className="px-5 py-4 border-t border-border-main flex justify-between items-center bg-bg-header/50 flex-shrink-0">
              <span className="text-xs text-text-muted">Changes will be applied immediately to ClickHouse and new CDC data will follow automatically.</span>
              <div className="flex gap-3">
                <button onClick={() => setEditQueryModal(null)} disabled={isUpdatingQuery} className="px-4 py-2 rounded-lg text-sm font-bold bg-rose-50 hover:bg-rose-100 dark:bg-rose-500/10 dark:hover:bg-rose-500/20 border border-rose-200 dark:border-rose-500/30 text-rose-600 dark:text-rose-400 transition-colors disabled:opacity-50">Cancel</button>
                <button onClick={handleUpdateQuery} disabled={isUpdatingQuery || !editQueryValue.trim()} className="px-5 py-2 rounded-lg text-sm font-bold bg-purple-600 hover:bg-purple-500 text-white disabled:opacity-50 transition-colors flex items-center gap-2">
                  {isUpdatingQuery ? <><Activity className="w-4 h-4 animate-spin" /> Syncing...</> : <><Save className="w-4 h-4" /> Save & Sync Schema</>}
                </button>
              </div>
            </div>
          </div>
        </div>
      )}

      {slotsModalOpen && (
        <div className="fixed inset-0 bg-black/60 backdrop-blur-sm z-50 flex items-center justify-center p-4">
          <div className="bg-bg-panel w-full max-w-4xl rounded-xl shadow-2xl flex flex-col border border-border-main max-h-[85vh]">
            <div className="px-5 py-4 border-b border-border-main flex justify-between items-center bg-amber-500/10">
              <h3 className="font-bold text-amber-400 flex items-center gap-2">
                <Database className="w-5 h-5" /> PostgreSQL Replication Slots (WAL Cleanup)
              </h3>
              <button onClick={() => setSlotsModalOpen(false)} className="text-text-muted hover:text-text-main text-2xl leading-none">&times;</button>
            </div>

            <div className="p-4 flex flex-col flex-1 min-h-0 overflow-y-auto space-y-4">
              <div className="p-3 bg-amber-500/10 border border-amber-500/30 rounded-lg text-xs text-amber-300 leading-relaxed flex items-start gap-2">
                <AlertTriangle className="w-4 h-4 flex-shrink-0 mt-0.5 text-amber-400" />
                <div>
                  <strong>Replication Slot & WAL Management:</strong>
                  <p className="mt-1 text-slate-300">
                    Setiap kali Debezium dijalankan pada PostgreSQL, sebuah <em>Replication Slot</em> dibuat. Jika pipeline di-stop atau dihapus tanpa membersihkan slot, PostgreSQL akan <strong>menahan file log WAL (Write-Ahead Log)</strong> di server database, menyebabkan kapasitas disk membesar hingga <strong>puluhan GB (misal 19GB+)</strong>.
                  </p>
                </div>
              </div>

              <div className="flex items-center justify-between">
                <span className="text-xs font-bold text-text-muted uppercase tracking-wider">
                  Total Slots Detected: {replicationSlots.length}
                </span>
                <div className="flex items-center gap-2">
                  <button
                    onClick={fetchReplicationSlots}
                    disabled={isLoadingSlots}
                    className="px-3 py-1.5 rounded text-xs font-bold bg-bg-header hover:bg-bg-main border border-border-main text-text-main flex items-center gap-1"
                  >
                    <RotateCcw className={clsx("w-3.5 h-3.5", isLoadingSlots && "animate-spin")} /> Refresh
                  </button>
                  <button
                    onClick={() => handleCleanupSlots(undefined, true)}
                    disabled={isCleaningSlots || replicationSlots.filter(s => !s.active).length === 0}
                    className="px-3.5 py-1.5 rounded text-xs font-bold bg-amber-600 hover:bg-amber-500 text-white disabled:opacity-50 flex items-center gap-1.5 transition-colors"
                  >
                    {isCleaningSlots ? <Activity className="w-3.5 h-3.5 animate-spin" /> : <Trash2 className="w-3.5 h-3.5" />}
                    Bersihkan Semua Slot Inaktif ({replicationSlots.filter(s => !s.active).length})
                  </button>
                </div>
              </div>

              <div className="border border-border-main rounded-lg overflow-hidden bg-bg-main flex-1 min-h-[200px]">
                {isLoadingSlots ? (
                  <div className="p-8 text-center text-text-muted text-xs flex items-center justify-center gap-2">
                    <Activity className="w-4 h-4 animate-spin text-amber-400" /> Scanning PostgreSQL replication slots...
                  </div>
                ) : replicationSlots.length === 0 ? (
                  <div className="p-8 text-center text-text-muted text-xs italic">
                    Tidak ada replication slot yang ditemukan pada koneksi PostgreSQL.
                  </div>
                ) : (
                  <div className="overflow-x-auto">
                    <table className="w-full text-left text-xs border-collapse">
                      <thead>
                        <tr className="bg-bg-header/80 text-text-muted border-b border-border-main font-semibold uppercase text-[10px]">
                          <th className="py-2.5 px-3">Connection</th>
                          <th className="py-2.5 px-3">Slot Name</th>
                          <th className="py-2.5 px-3">Plugin</th>
                          <th className="py-2.5 px-3">Database</th>
                          <th className="py-2.5 px-3">Status</th>
                          <th className="py-2.5 px-3">WAL Retained</th>
                          <th className="py-2.5 px-3 text-right">Action</th>
                        </tr>
                      </thead>
                      <tbody className="divide-y divide-border-main font-mono text-[11px]">
                        {replicationSlots.map((s, idx) => (
                          <tr key={idx} className="hover:bg-bg-header/50 transition-colors">
                            <td className="py-2.5 px-3 font-sans font-medium text-text-main">{s.connection_name}</td>
                            <td className="py-2.5 px-3 font-bold text-amber-400 break-all">{s.slot_name}</td>
                            <td className="py-2.5 px-3 text-text-muted">{s.plugin}</td>
                            <td className="py-2.5 px-3 text-text-muted">{s.database}</td>
                            <td className="py-2.5 px-3">
                              {s.active ? (
                                <span className="px-2 py-0.5 rounded text-[10px] font-bold bg-emerald-500/10 text-emerald-400 uppercase font-sans">
                                  ACTIVE (PID: {s.active_pid || 'N/A'})
                                </span>
                              ) : (
                                <span className="px-2 py-0.5 rounded text-[10px] font-bold bg-amber-500/15 text-amber-400 uppercase font-sans">
                                  INACTIVE (WAL Retained)
                                </span>
                              )}
                            </td>
                            <td className="py-2.5 px-3 text-slate-300">{s.wal_retained || '-'}</td>
                            <td className="py-2.5 px-3 text-right">
                              <button
                                onClick={() => handleCleanupSlots(s.slot_name, false)}
                                disabled={isCleaningSlots}
                                className="px-2.5 py-1 rounded bg-red-500/10 text-red-400 hover:bg-red-500 hover:text-white transition-colors text-[11px] font-bold font-sans disabled:opacity-50"
                              >
                                Drop Slot
                              </button>
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                )}
              </div>
            </div>

            <div className="px-5 py-3 border-t border-border-main flex justify-end bg-bg-header/50">
              <button
                onClick={() => setSlotsModalOpen(false)}
                className="px-4 py-1.5 rounded-lg text-xs font-bold bg-bg-header hover:bg-bg-main border border-border-main text-text-main"
              >
                Close
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
