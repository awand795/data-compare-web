import React, { useEffect, useRef, useState } from 'react';
import axios from 'axios';
import { Play, Pause, RotateCcw, Trash2, Activity, AlertTriangle, CheckCircle2, ChevronDown, ChevronRight, Search, Settings, Eye, BarChart2, X, Save, Edit, Edit3, Code, FileEdit, Database, Clock, Plus } from 'lucide-react';
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
  const { connections, addToast, showAlert } = useAppStore();
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
  const [activeSlotsTab, setActiveSlotsTab] = useState<'slots' | 'schedules'>('slots');
  const [replicationSlots, setReplicationSlots] = useState<any[]>([]);
  const [isLoadingSlots, setIsLoadingSlots] = useState(false);
  const [isCleaningSlots, setIsCleaningSlots] = useState(false);

  // WAL Alert Schedules State
  const [walSchedules, setWalSchedules] = useState<any[]>([]);
  const [isLoadingWalSchedules, setIsLoadingWalSchedules] = useState(false);
  const [isWalFormOpen, setIsWalFormOpen] = useState(false);
  const [editingWalSchedule, setEditingWalSchedule] = useState<any | null>(null);
  const [walFormName, setWalFormName] = useState('');
  const [walFormConnectionId, setWalFormConnectionId] = useState('');
  const [walFormThresholdMb, setWalFormThresholdMb] = useState(500);
  const [walFormCronTriggers, setWalFormCronTriggers] = useState<string[]>(['0 */15 * * * *']);
  const [walFormChannels, setWalFormChannels] = useState<string[]>([]);
  const [isSubmittingWal, setIsSubmittingWal] = useState(false);
  const [testingWalId, setTestingWalId] = useState<string | null>(null);

  const fetchWalSchedules = async () => {
    setIsLoadingWalSchedules(true);
    try {
      const res = await axios.get('/api/wal-alert-schedules');
      const data = res.data;
      if (Array.isArray(data)) {
        setWalSchedules(data);
      } else {
        setWalSchedules(data || []);
      }
    } catch (err) {
      console.error('Failed to fetch WAL alert schedules:', err);
    } finally {
      setIsLoadingWalSchedules(false);
    }
  };

  const handleOpenCreateWal = () => {
    if (replicationSlots.length === 0) {
      fetchReplicationSlots();
    }
    setEditingWalSchedule(null);
    setWalFormName('PostgreSQL WAL Bloat Alert (500MB)');
    setWalFormConnectionId('');
    setWalFormThresholdMb(500);
    setWalFormCronTriggers(['0 */15 * * * *']);
    setWalFormChannels(useAppStore.getState().notificationChannels.map(c => c.id));
    setIsWalFormOpen(true);
  };

  const handleOpenEditWal = (s: any) => {
    if (replicationSlots.length === 0) {
      fetchReplicationSlots();
    }
    setEditingWalSchedule(s);
    setWalFormName(s.name);
    setWalFormConnectionId(s.connectionId || '');
    setWalFormThresholdMb(s.thresholdMb || 500);
    const parsedTriggers = s.cronExpression
      ? s.cronExpression.split(/[,;\n]+/).map((x: string) => x.trim()).filter(Boolean)
      : ['0 */15 * * * *'];
    setWalFormCronTriggers(parsedTriggers.length > 0 ? parsedTriggers : ['0 */15 * * * *']);
    setWalFormChannels(s.channelIds ? s.channelIds.split(',').map((x: string) => x.trim()).filter(Boolean) : []);
    setIsWalFormOpen(true);
  };

  const handleSaveWalSchedule = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!walFormName.trim()) {
      addToast({ type: 'warning', title: 'Validation Error', message: 'Rule name is required.' });
      return;
    }
    if (walFormChannels.length === 0) {
      addToast({ type: 'warning', title: 'Validation Error', message: 'Please select at least one notification channel.' });
      return;
    }

    const validTriggers = walFormCronTriggers.map(c => c.trim()).filter(Boolean);
    if (validTriggers.length === 0) {
      addToast({ type: 'warning', title: 'Validation Error', message: 'Setidaknya masukkan 1 ekspresi Spring Cron.' });
      return;
    }
    const finalCron = validTriggers.join('; ');

    const payload = {
      name: walFormName.trim(),
      connectionId: walFormConnectionId || null,
      thresholdMb: walFormThresholdMb,
      cronExpression: finalCron,
      channelIds: walFormChannels.join(','),
      active: editingWalSchedule ? editingWalSchedule.active : true
    };

    setIsSubmittingWal(true);
    try {
      if (editingWalSchedule) {
        await fetch(`/api/wal-alert-schedules/${editingWalSchedule.id}`, {
          method: 'PUT',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(payload)
        });
        addToast({ type: 'success', title: 'Updated', message: `WAL alert rule "${walFormName}" updated.` });
      } else {
        await fetch('/api/wal-alert-schedules', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(payload)
        });
        addToast({ type: 'success', title: 'Created', message: `WAL alert rule "${walFormName}" created & activated.` });
      }
      setIsWalFormOpen(false);
      fetchWalSchedules();
    } catch (err) {
      addToast({ type: 'error', title: 'Error', message: 'Failed to save WAL alert schedule.' });
    } finally {
      setIsSubmittingWal(false);
    }
  };

  const handleToggleWalActive = async (id: string, currentActive: boolean) => {
    try {
      await fetch(`/api/wal-alert-schedules/${id}/active`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ active: !currentActive })
      });
      setWalSchedules(prev => prev.map(s => s.id === id ? { ...s, active: !currentActive } : s));
      addToast({ 
        type: 'info', 
        title: !currentActive ? 'Activated' : 'Paused', 
        message: `WAL alert schedule is now ${!currentActive ? 'active' : 'paused'}.` 
      });
    } catch (err) {
      addToast({ type: 'error', title: 'Error', message: 'Failed to toggle status.' });
    }
  };

  const handleDeleteWalSchedule = async (id: string, name: string) => {
    if (!confirm(`Delete WAL alert rule "${name}"?`)) return;
    try {
      await fetch(`/api/wal-alert-schedules/${id}`, { method: 'DELETE' });
      setWalSchedules(prev => prev.filter(s => s.id !== id));
      addToast({ type: 'success', title: 'Deleted', message: `Rule "${name}" removed.` });
    } catch (err) {
      addToast({ type: 'error', title: 'Error', message: 'Failed to delete rule.' });
    }
  };

  const handleTestWalAlert = async (id: string, name: string) => {
    setTestingWalId(id);
    try {
      const res = await fetch(`/api/wal-alert-schedules/${id}/test`, { method: 'POST' });
      const data = await res.json();
      if (data?.success) {
        addToast({ type: 'success', title: 'Test Alert Sent', message: `Test message sent to selected channels for "${name}".` });
      } else {
        addToast({ type: 'warning', title: 'Test Alert', message: data?.error || 'Failed to send test alert.' });
      }
    } catch (err: any) {
      addToast({ type: 'error', title: 'Test Failed', message: 'Failed to trigger test alert.' });
    } finally {
      setTestingWalId(null);
    }
  };

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
                if (p.name.endsWith('-shared')) {
                  const dbName = p.name.replace(/^source-/, '').replace(/-shared$/, '');
                  const sharedKey = `Shared:${dbName}`;
                  if (!acc[sharedKey]) acc[sharedKey] = [];
                  acc[sharedKey].push(p);
                  return acc;
                }
                
                const lastDash = p.name.lastIndexOf('-');
                const tsStr = p.name.slice(lastDash + 1);
                const isTimestamp = lastDash > 0 && !isNaN(Number(tsStr)) && tsStr.length >= 10;
                
                let deployId = '';
                if (isTimestamp) {
                  const ts = Number(tsStr);
                  const existingKey = Object.keys(acc).find(k => !k.startsWith('Shared:') && !isNaN(Number(k)) && Math.abs(Number(k) - ts) <= 2000);
                  deployId = existingKey ? existingKey : tsStr;
                } else if (p.name.startsWith('sink-clickhouse-')) {
                  deployId = p.name.replace('sink-clickhouse-', '');
                } else if (p.name.startsWith('source-')) {
                  const parts = p.name.split('-');
                  deployId = parts.length >= 3 ? parts.slice(2).join('-') : p.name;
                } else {
                  deployId = p.name;
                }

                if (!acc[deployId]) acc[deployId] = [];
                acc[deployId].push(p);
                return acc;
              }, {} as Record<string, Pipeline[]>)
            ).sort((a, b) => b[0].localeCompare(a[0])).map(([deployId, groupPipelines]) => {
              // Try to find target table name from sink connector
              const sink = groupPipelines.find(p => p.name.startsWith('sink-clickhouse-'));
              let folderName = `Deployment ID: ${deployId}`;
              let icon = "🗂️";
              if (deployId.startsWith('Shared:')) {
                const dbName = deployId.replace('Shared:', '');
                folderName = `Shared DB Source Connector (${dbName})`;
                icon = "⚡";
              } else if (sink) {
                const lastDash = sink.name.lastIndexOf('-');
                const tsStr = sink.name.slice(lastDash + 1);
                const hasTimestamp = lastDash > 0 && !isNaN(Number(tsStr)) && tsStr.length >= 10;

                const targetTable = hasTimestamp ? sink.name.slice('sink-clickhouse-'.length, lastDash) : sink.name.slice('sink-clickhouse-'.length);
                folderName = `Pipeline: ${targetTable || deployId}`;
              } else {
                folderName = `Pipeline: ${deployId}`;
              }

              const isSpecialGroup = deployId.startsWith('Shared:');

              // Compute list of Source DB names for this pipeline
              const sourceDbNames = Array.from(new Set(
                pipelines
                  .filter(p => p.name.startsWith('source-'))
                  .map(p => {
                    let rawDb = p.name.replace(/^source-/, '').replace(/-shared$/, '');
                    const lastDash = rawDb.lastIndexOf('-');
                    if (lastDash > 0 && !isNaN(Number(rawDb.slice(lastDash + 1)))) {
                      rawDb = rawDb.slice(0, lastDash);
                    }
                    const conn = connections.find((c: any) => {
                      const cleanCName = (c.name || '').replaceAll(/[^a-zA-Z0-9_]/g, '_').toLowerCase();
                      return cleanCName === rawDb.toLowerCase() || String(c.id) === rawDb || cleanCName.startsWith(rawDb.toLowerCase());
                    });
                    return conn ? conn.name : rawDb.replace(/_/g, ' ').toUpperCase();
                  })
              ));

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
                      <span className="text-xl flex-shrink-0">{icon}</span>
                      <h4 className="font-bold text-[13px] text-text-main break-all mt-1">
                        {folderName}
                        {!isSpecialGroup && <span className="text-text-muted font-normal text-[11px] ml-2 inline-block">(ID: {deployId})</span>}
                      </h4>
                    </div>
                    <div className="flex-shrink-0 mt-1">
                      <span className="text-[11px] font-bold text-text-muted bg-bg-panel px-2 py-1 rounded">
                        {groupPipelines.length} {deployId.startsWith('Shared:') ? 'Shared Slot' : 'Connector(s)'}
                      </span>
                    </div>
                  </div>
                  
                  {!isSpecialGroup && (
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

                  {groupPipelines.map(p => {
                    const info = (() => {
                      if (p.name.startsWith('sink-clickhouse-')) {
                        const parts = p.name.split('-');
                        const targetTable = parts.slice(2, -1).join('-') || parts.slice(2).join('-');
                        const sourcesText = sourceDbNames.length > 0 ? sourceDbNames.join(', ') : '';
                        return {
                          title: `ClickHouse Sink → ${targetTable}`,
                          sourcesInfo: sourcesText ? `Consuming CDC streams from: ${sourcesText}` : '',
                          rawName: p.name,
                          badge: 'CLICKHOUSE TARGET',
                          badgeClass: 'bg-amber-500/10 text-amber-500 border border-amber-500/20',
                          isSink: true
                        };
                      }
                      
                      if (p.name.startsWith('source-')) {
                        let rawDb = p.name.replace(/^source-/, '').replace(/-shared$/, '');
                        const lastDash = rawDb.lastIndexOf('-');
                        if (lastDash > 0 && !isNaN(Number(rawDb.slice(lastDash + 1)))) {
                          rawDb = rawDb.slice(0, lastDash);
                        }
                        
                        const conn = connections.find((c: any) => {
                          const cleanCName = (c.name || '').replaceAll(/[^a-zA-Z0-9_]/g, '_').toLowerCase();
                          return cleanCName === rawDb.toLowerCase() || String(c.id) === rawDb || cleanCName.startsWith(rawDb.toLowerCase());
                        });

                        const displayName = conn ? conn.name : rawDb.replace(/_/g, ' ').toUpperCase();
                        const dbType = conn ? conn.type.toUpperCase() : 'SOURCE DB';

                        return {
                          title: `Source DB: ${displayName}`,
                          sourcesInfo: '',
                          rawName: p.name,
                          badge: dbType,
                          badgeClass: 'bg-blue-500/10 text-blue-500 border border-blue-500/20',
                          isSink: false
                        };
                      }

                      return {
                        title: p.name,
                        sourcesInfo: '',
                        rawName: p.name,
                        badge: 'CONNECTOR',
                        badgeClass: 'bg-gray-500/10 text-gray-500 border border-gray-500/20',
                        isSink: false
                      };
                    })();

                    return (
                      <div key={p.name} className="bg-bg-panel border border-border-main rounded-lg p-3 hover:border-indigo-500/30 transition-colors shadow-sm">
                        <div className="flex items-start justify-between mb-2">
                          <div className="flex flex-col flex-1 min-w-0 pr-3">
                            <div className="flex items-center gap-2">
                              <span className="font-bold text-[13px] text-text-main truncate" title={p.name}>
                                {info.title}
                              </span>
                              <span className={clsx("px-2 py-0.5 rounded text-[9px] font-black tracking-wider uppercase shrink-0", info.badgeClass)}>
                                {info.badge}
                              </span>
                            </div>
                            {info.isSink && info.sourcesInfo && (
                              <div className="text-[11px] font-bold text-amber-600 dark:text-amber-400 mt-1 flex items-center gap-1.5">
                                <Database className="w-3.5 h-3.5 text-amber-500 shrink-0" />
                                <span>{info.sourcesInfo}</span>
                              </div>
                            )}
                            <div className="text-[11px] text-text-muted mt-0.5 flex items-center gap-2 font-mono">
                              <span className="truncate" title={p.name}>Name: {p.name}</span>
                              {p.lag !== undefined && (
                                <span className={clsx("px-1.5 py-0.5 rounded font-bold shrink-0", p.lag > 0 ? "bg-amber-500/10 text-amber-500" : "bg-emerald-500/10 text-emerald-500")}>
                                  {p.lag > 0 ? `⚠️ Lagging: ${p.lag.toLocaleString()} records` : `⚡ Synced`}
                                </span>
                              )}
                            </div>
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
                    );
                  })}
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
        <div className="fixed inset-0 bg-black/60 backdrop-blur-sm z-50 flex items-center justify-center p-3 md:p-4">
          <div className="bg-bg-panel w-full max-w-4xl rounded-xl shadow-2xl flex flex-col border border-border-main max-h-[90vh] h-[85vh] overflow-hidden">
            <div className="px-5 py-4 border-b border-border-main flex justify-between items-center bg-amber-500/10 shrink-0">
              <div className="flex items-center gap-3">
                <div className="w-8 h-8 rounded-lg bg-amber-500/20 text-amber-500 flex items-center justify-center">
                  <Database className="w-5 h-5" />
                </div>
                <div>
                  <h3 className="font-bold text-amber-600 dark:text-amber-400 text-sm md:text-base">
                    PostgreSQL Replication Slots & WAL Bloat Monitor
                  </h3>
                  <p className="text-[11px] text-text-muted">
                    Pantau ukuran file WAL dan atur jadwal notifikasi otomatis ke Telegram / Discord.
                  </p>
                </div>
              </div>
              <button onClick={() => setSlotsModalOpen(false)} className="text-text-muted hover:text-text-main text-2xl leading-none">&times;</button>
            </div>

            {/* Tabs Header */}
            <div className="flex items-center gap-2 px-5 pt-3 border-b border-border-main bg-bg-panel shrink-0">
              <button
                onClick={() => setActiveSlotsTab('slots')}
                className={clsx(
                  "px-3.5 py-2 text-xs font-bold border-b-2 transition-all flex items-center gap-2",
                  activeSlotsTab === 'slots'
                    ? "border-amber-500 text-amber-500"
                    : "border-transparent text-text-muted hover:text-text-main"
                )}
              >
                <Database className="w-3.5 h-3.5" />
                Daftar Slot Saat Ini ({replicationSlots.length})
              </button>
              <button
                onClick={() => {
                  setActiveSlotsTab('schedules');
                  fetchWalSchedules();
                }}
                className={clsx(
                  "px-3.5 py-2 text-xs font-bold border-b-2 transition-all flex items-center gap-2",
                  activeSlotsTab === 'schedules'
                    ? "border-amber-500 text-amber-500"
                    : "border-transparent text-text-muted hover:text-text-main"
                )}
              >
                <Activity className="w-3.5 h-3.5" />
                WAL Alert Schedules ({walSchedules.length})
              </button>
            </div>

            <div className="p-4 md:p-5 flex flex-col flex-1 min-h-0 overflow-y-auto space-y-4">
              {activeSlotsTab === 'slots' ? (
                <>
                  <div className="p-3 bg-amber-500/10 border border-amber-500/30 rounded-lg text-xs leading-relaxed flex items-start gap-2 shrink-0">
                    <AlertTriangle className="w-4 h-4 flex-shrink-0 mt-0.5 text-amber-600 dark:text-amber-400" />
                    <div>
                      <strong className="font-bold text-amber-800 dark:text-amber-300">Replication Slot & WAL Management:</strong>
                      <p className="mt-1 text-text-main dark:text-slate-300">
                        Setiap kali Debezium dijalankan pada PostgreSQL, sebuah <em>Replication Slot</em> dibuat. Jika pipeline di-stop atau dihapus tanpa membersihkan slot, PostgreSQL akan <strong>menahan file log WAL (Write-Ahead Log)</strong> di server database, menyebabkan kapasitas disk membesar hingga <strong>puluhan GB (misal 19GB+)</strong>.
                      </p>
                    </div>
                  </div>

                  <div className="flex items-center justify-between shrink-0">
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

                  <div className="border border-border-main rounded-lg bg-bg-main flex-1 min-h-0 overflow-auto">
                    {isLoadingSlots ? (
                      <div className="p-8 text-center text-text-muted text-xs flex items-center justify-center gap-2 h-full">
                        <Activity className="w-4 h-4 animate-spin text-amber-500 dark:text-amber-400" /> Scanning PostgreSQL replication slots...
                      </div>
                    ) : replicationSlots.length === 0 ? (
                      <div className="p-8 text-center text-text-muted text-xs italic h-full flex items-center justify-center">
                        Tidak ada replication slot yang ditemukan pada koneksi PostgreSQL.
                      </div>
                    ) : (
                      <table className="w-full text-left text-xs border-collapse min-w-[700px]">
                        <thead className="sticky top-0 z-10 bg-bg-header shadow-sm">
                          <tr className="text-text-muted border-b border-border-main font-semibold uppercase text-[10px]">
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
                              <td className="py-2.5 px-3 font-bold text-amber-600 dark:text-amber-400 break-all">{s.slot_name}</td>
                              <td className="py-2.5 px-3 text-text-muted">{s.plugin}</td>
                              <td className="py-2.5 px-3 text-text-muted">{s.database}</td>
                              <td className="py-2.5 px-3">
                                {s.active ? (
                                  <span className="px-2 py-0.5 rounded text-[10px] font-bold bg-emerald-500/10 text-emerald-600 dark:text-emerald-400 uppercase font-sans">
                                    ACTIVE (PID: {s.active_pid || 'N/A'})
                                  </span>
                                ) : (
                                  <span className="px-2 py-0.5 rounded text-[10px] font-bold bg-amber-500/15 text-amber-700 dark:text-amber-400 uppercase font-sans">
                                    INACTIVE (WAL Retained)
                                  </span>
                                )}
                              </td>
                              <td className="py-2.5 px-3 text-text-main dark:text-slate-300">{s.wal_retained || '-'}</td>
                              <td className="py-2.5 px-3 text-right">
                                <button
                                  onClick={() => handleCleanupSlots(s.slot_name, true)}
                                  disabled={isCleaningSlots || s.active}
                                  title={s.active ? "Slot sedang aktif (digunakan CDC pipeline). Matikan pipeline lebih dahulu jika ingin menghapus slot ini." : "Drop slot inaktif ini"}
                                  className={clsx(
                                    "px-2.5 py-1 rounded transition-colors text-[11px] font-bold font-sans",
                                    s.active
                                      ? "bg-slate-500/10 text-slate-400 dark:text-slate-500 border border-slate-500/20 cursor-not-allowed opacity-50"
                                      : "bg-red-500/10 text-red-500 hover:bg-red-500 hover:text-white border border-red-500/20"
                                  )}
                                >
                                  {s.active ? 'Active' : 'Drop Slot'}
                                </button>
                              </td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    )}
                  </div>
                </>
              ) : (
                /* WAL Alert Schedules Tab */
                <div className="space-y-4 flex flex-col flex-1 min-h-0">
                  <div className="flex items-center justify-between shrink-0">
                    <div>
                      <h4 className="font-bold text-xs text-text-main">
                        Jadwal Peringatan Otomatis (WAL Bloat Alert)
                      </h4>
                      <p className="text-[11px] text-text-muted">
                        Kirim notifikasi ke Telegram / Discord jika WAL size &ge; 500 MB atau slot inaktif menahan log.
                      </p>
                    </div>
                    <button
                      onClick={handleOpenCreateWal}
                      className="px-3 py-1.5 bg-amber-600 hover:bg-amber-500 text-white rounded text-xs font-bold transition-colors flex items-center gap-1.5 shadow"
                    >
                      + Tambah WAL Rule Baru
                    </button>
                  </div>

                  <div className="border border-border-main rounded-lg bg-bg-main flex-1 min-h-0 overflow-auto">
                    {isLoadingWalSchedules ? (
                      <div className="p-8 text-center text-xs text-text-muted flex items-center justify-center gap-2 h-full">
                        <Activity className="w-4 h-4 animate-spin text-amber-500" /> Loading schedules...
                      </div>
                    ) : walSchedules.length === 0 ? (
                      <div className="p-8 text-center text-xs text-text-muted space-y-3 h-full flex flex-col items-center justify-center">
                        <p className="italic">Belum ada aturan jadwal pemantauan WAL yang dibuat.</p>
                        <button
                          onClick={handleOpenCreateWal}
                          className="px-3 py-1.5 bg-amber-600 hover:bg-amber-500 text-white text-xs font-bold rounded shadow transition-colors"
                        >
                          + Buat WAL Alert Schedule
                        </button>
                      </div>
                    ) : (
                      <table className="w-full text-left text-xs border-collapse min-w-[750px]">
                        <thead className="sticky top-0 z-10 bg-bg-header shadow-sm">
                          <tr className="text-text-muted border-b border-border-main font-semibold uppercase text-[10px]">
                            <th className="py-2.5 px-3">Rule Name</th>
                            <th className="py-2.5 px-3">Connection</th>
                            <th className="py-2.5 px-3">Threshold</th>
                            <th className="py-2.5 px-3">Cron / Interval</th>
                            <th className="py-2.5 px-3">Last Status</th>
                            <th className="py-2.5 px-3 text-center">Status</th>
                            <th className="py-2.5 px-3 text-right">Action</th>
                          </tr>
                        </thead>
                        <tbody className="divide-y divide-border-main text-[11px]">
                          {walSchedules.map(ws => (
                            <tr key={ws.id} className="hover:bg-bg-header/50 transition-colors">
                              <td className="py-2.5 px-3 font-semibold text-text-main">{ws.name}</td>
                              <td className="py-2.5 px-3 text-text-muted">
                                {ws.connectionId 
                                  ? connections.find(c => c.id === ws.connectionId)?.name || 'Specific DB' 
                                  : 'All PostgreSQL DBs'}
                              </td>
                              <td className="py-2.5 px-3 font-mono font-bold text-amber-500">
                                &ge; {ws.thresholdMb} MB
                              </td>
                              <td className="py-2.5 px-3 font-mono text-[11px] text-text-main">
                                <div className="flex flex-col gap-1">
                                  {ws.cronExpression ? ws.cronExpression.split(/[,;\n]+/).map((c: string, i: number) => (
                                    <span key={i} className="flex items-center gap-1 text-amber-400 bg-amber-500/10 px-1.5 py-0.5 rounded border border-amber-500/20 w-fit">
                                      <Clock className="w-3 h-3 text-amber-400" />
                                      {c.trim()}
                                    </span>
                                  )) : (
                                    <span className="text-text-muted italic">-</span>
                                  )}
                                </div>
                              </td>
                              <td className="py-2.5 px-3">
                                <span className={clsx(
                                  "px-1.5 py-0.5 rounded text-[10px] font-bold uppercase",
                                  ws.lastStatus === 'OK' && "bg-emerald-500/10 text-emerald-400 border border-emerald-500/20",
                                  ws.lastStatus === 'ALERT_SENT' && "bg-red-500/10 text-red-400 border border-red-500/20",
                                  ws.lastStatus?.startsWith('ERROR') && "bg-rose-500/10 text-rose-400 border border-rose-500/20"
                                )}>
                                  {ws.lastStatus || '-'}
                                </span>
                              </td>
                              <td className="py-2.5 px-3 text-center">
                                <button
                                  onClick={() => handleToggleWalActive(ws.id, ws.active)}
                                  className={clsx(
                                    "px-2 py-0.5 rounded-full text-[10px] font-bold uppercase",
                                    ws.active 
                                      ? "bg-emerald-500/15 text-emerald-400 border border-emerald-500/30" 
                                      : "bg-slate-500/15 text-slate-400 border border-slate-500/30"
                                  )}
                                >
                                  {ws.active ? 'Active' : 'Paused'}
                                </button>
                              </td>
                              <td className="py-2.5 px-3 text-right">
                                <div className="flex items-center justify-end gap-1">
                                  <button
                                    onClick={() => handleTestWalAlert(ws.id, ws.name)}
                                    disabled={testingWalId === ws.id}
                                    title="Test Send Alert Now"
                                    className="p-1 rounded hover:bg-bg-panel border border-border-main text-text-muted hover:text-blue-400"
                                  >
                                    <Play className={clsx("w-3 h-3", testingWalId === ws.id && "animate-spin text-blue-500")} />
                                  </button>
                                  <button
                                    onClick={() => handleOpenEditWal(ws)}
                                    title="Edit Rule"
                                    className="p-1 rounded hover:bg-bg-panel border border-border-main text-text-muted hover:text-amber-400"
                                  >
                                    <Edit className="w-3 h-3" />
                                  </button>
                                  <button
                                    onClick={() => handleDeleteWalSchedule(ws.id, ws.name)}
                                    title="Delete Rule"
                                    className="p-1 rounded hover:bg-bg-panel border border-border-main text-text-muted hover:text-red-400"
                                  >
                                    <Trash2 className="w-3 h-3" />
                                  </button>
                                </div>
                              </td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    )}
                  </div>
                </div>
              )}
            </div>

            <div className="px-5 py-3 border-t border-border-main flex justify-end bg-bg-header/50 shrink-0">
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

      {/* WAL Alert Schedule Create / Edit Modal */}
      {isWalFormOpen && (
        <div className="fixed inset-0 bg-black/70 backdrop-blur-sm z-[60] flex items-center justify-center p-3 md:p-4">
          <div className="bg-bg-panel w-full max-w-lg rounded-xl shadow-2xl border border-border-main overflow-hidden flex flex-col max-h-[90vh]">
            <div className="px-5 py-4 border-b border-border-main flex justify-between items-center bg-amber-500/10 shrink-0">
              <h4 className="font-bold text-amber-500 text-sm flex items-center gap-2">
                <Activity className="w-4 h-4" />
                {editingWalSchedule ? 'Edit WAL Alert Schedule' : 'Buat WAL Alert Schedule Baru'}
              </h4>
              <button onClick={() => setIsWalFormOpen(false)} className="text-text-muted hover:text-text-main text-xl leading-none">&times;</button>
            </div>

            <form onSubmit={handleSaveWalSchedule} className="p-5 space-y-4 text-xs overflow-y-auto flex-1 min-h-0">
              <div>
                <label className="block font-semibold text-text-main mb-1">Nama Rule</label>
                <input
                  type="text"
                  required
                  value={walFormName}
                  onChange={e => setWalFormName(e.target.value)}
                  placeholder="e.g. WAL Bloat Alert (500MB)"
                  className="w-full bg-bg-main border border-border-main rounded-lg px-3 py-2 text-text-main focus:outline-none focus:border-amber-500"
                />
              </div>

              <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                <div>
                  <div className="flex items-center justify-between mb-1">
                    <label className="block font-semibold text-text-main">Pilih Target WAL Slot (Hanya Slot Aktif)</label>
                    <span className="text-[10px] text-emerald-500 font-bold">
                      {replicationSlots.filter(s => s.active).length} Aktif
                    </span>
                  </div>
                  <select
                    value={walFormConnectionId}
                    onChange={e => setWalFormConnectionId(e.target.value)}
                    className="w-full bg-bg-main border border-border-main rounded-lg px-2.5 py-2 text-text-main focus:outline-none focus:border-amber-500"
                  >
                    <option value="">Semua Slot Aktif ({replicationSlots.filter(s => s.active).length} slot)</option>
                    {replicationSlots.filter(s => s.active).map((s, idx) => (
                      <option key={`${s.connection_id || s.connection_name}-${s.slot_name}-${idx}`} value={s.connection_id || s.connection_name}>
                        {s.connection_name} &bull; {s.slot_name} ({s.database || 'db'})
                      </option>
                    ))}
                  </select>
                  {replicationSlots.filter(s => s.active).length === 0 && (
                    <p className="text-[10px] text-amber-500 mt-1 italic">
                      Saat ini belum ada slot PostgreSQL aktif terdeteksi. Silakan jalankan CDC pipeline terlebih dahulu.
                    </p>
                  )}
                </div>

                <div>
                  <label className="block font-semibold text-text-main mb-1">
                    Batas Threshold WAL: <strong className="text-amber-500 font-mono">{walFormThresholdMb} MB</strong>
                  </label>
                  <input
                    type="number"
                    min="50"
                    max="100000"
                    step="50"
                    value={walFormThresholdMb}
                    onChange={e => setWalFormThresholdMb(Number(e.target.value))}
                    className="w-full bg-bg-main border border-border-main rounded-lg px-2.5 py-2 text-text-main font-mono focus:outline-none focus:border-amber-500"
                  />
                </div>
              </div>

              {/* Schedule Cron / Interval (Multiple Spring Cron Triggers Supported) */}
              <div className="space-y-3 p-3.5 rounded-lg border border-border-main bg-bg-main/40">
                <div className="flex items-center justify-between">
                  <div>
                    <label className="block font-bold text-text-main">
                      Jadwal Pemeriksaan WAL (Spring Cron Expressions)
                    </label>
                    <p className="text-[11px] text-text-muted">
                      User dapat mengisi bebas format Spring Cron 6-field. Bisa menambah lebih dari 1 jadwal pemicu.
                    </p>
                  </div>
                  <button
                    type="button"
                    onClick={() => setWalFormCronTriggers(prev => [...prev, '0 0 23 * * *'])}
                    className="flex items-center gap-1 px-2.5 py-1 rounded-lg bg-amber-500/15 text-amber-400 hover:bg-amber-500/25 border border-amber-500/30 text-[11px] font-bold transition-colors"
                  >
                    <Plus className="w-3.5 h-3.5" /> Tambah Cron
                  </button>
                </div>

                <div className="space-y-2">
                  {walFormCronTriggers.map((cron, idx) => (
                    <div key={idx} className="flex items-center gap-2">
                      <div className="relative flex-1">
                        <div className="absolute inset-y-0 left-0 pl-2.5 flex items-center pointer-events-none text-text-muted">
                          <Clock className="w-3.5 h-3.5 text-amber-400" />
                        </div>
                        <input
                          type="text"
                          required
                          value={cron}
                          onChange={e => {
                            const copy = [...walFormCronTriggers];
                            copy[idx] = e.target.value;
                            setWalFormCronTriggers(copy);
                          }}
                          placeholder="e.g. 0 */15 * * * * atau 0 0 23 * * *"
                          className="w-full bg-bg-main border border-border-main rounded-lg pl-8 pr-3 py-1.5 text-xs font-mono font-bold text-amber-400 focus:outline-none focus:border-amber-500 shadow-inner"
                        />
                      </div>
                      {walFormCronTriggers.length > 1 && (
                        <button
                          type="button"
                          onClick={() => setWalFormCronTriggers(prev => prev.filter((_, i) => i !== idx))}
                          className="p-1.5 text-text-muted hover:text-red-400 hover:bg-red-500/10 rounded-lg transition-colors border border-border-main"
                          title="Hapus Trigger Cron Ini"
                        >
                          <Trash2 className="w-3.5 h-3.5" />
                        </button>
                      )}
                    </div>
                  ))}
                </div>

                {/* Quick Presets Helpers */}
                <div className="pt-1">
                  <div className="text-[10px] text-text-muted mb-1.5 font-semibold">
                    Preset Cepat (Klik untuk menambahkan ekspresi cron):
                  </div>
                  <div className="flex flex-wrap gap-1.5">
                    {[
                      { label: 'Tiap 5 Menit (0 */5 * * * *)', value: '0 */5 * * * *' },
                      { label: 'Tiap 10 Menit (0 */10 * * * *)', value: '0 */10 * * * *' },
                      { label: 'Tiap 15 Menit (0 */15 * * * *)', value: '0 */15 * * * *' },
                      { label: 'Tiap 30 Menit (0 */30 * * * *)', value: '0 */30 * * * *' },
                      { label: 'Tiap 1 Jam (0 0 * * * *)', value: '0 0 * * * *' },
                      { label: 'Tiap Hari 23:00 (0 0 23 * * *)', value: '0 0 23 * * *' },
                    ].map(p => (
                      <button
                        key={p.value}
                        type="button"
                        onClick={() => {
                          if (walFormCronTriggers.length === 1 && !walFormCronTriggers[0]) {
                            setWalFormCronTriggers([p.value]);
                          } else if (!walFormCronTriggers.includes(p.value)) {
                            setWalFormCronTriggers(prev => [...prev, p.value]);
                          }
                        }}
                        className="py-1 px-2 rounded bg-bg-panel border border-border-main hover:border-amber-500/40 text-[10px] text-text-muted hover:text-amber-400 font-mono transition-colors"
                      >
                        + {p.label}
                      </button>
                    ))}
                  </div>
                  <p className="text-[10px] text-text-muted mt-1 leading-relaxed">
                    💡 <b>Format 6 field:</b> <code>Detik Menit Jam Hari Bulan HariMinggu</code>. User dapat mengetik bebas ekspresi cron apa pun (misal: <code>0 0 8,14,20 * * *</code>).
                  </p>
                </div>
              </div>

              <div>
                <label className="block font-semibold text-text-main mb-1">
                  Pilih Target Channel Notifikasi (Telegram / Discord)
                </label>
                {useAppStore.getState().notificationChannels.length === 0 ? (
                  <div className="p-2.5 bg-amber-500/10 border border-amber-500/30 rounded text-amber-300 text-[11px]">
                    Belum ada notification profile.
                  </div>
                ) : (
                  <div className="grid grid-cols-1 sm:grid-cols-2 gap-2 max-h-36 overflow-y-auto pr-1">
                    {useAppStore.getState().notificationChannels.map(c => {
                      const isSelected = walFormChannels.includes(c.id);
                      return (
                        <div
                          key={c.id}
                          onClick={() => {
                            setWalFormChannels(prev => 
                              prev.includes(c.id) ? prev.filter(x => x !== c.id) : [...prev, c.id]
                            );
                          }}
                          className={clsx(
                            "p-2 rounded-lg border cursor-pointer flex items-center justify-between transition-all select-none",
                            isSelected
                              ? "bg-amber-500/15 border-amber-500 text-amber-300 font-bold"
                              : "bg-bg-main border-border-main text-text-muted hover:border-border-item"
                          )}
                        >
                          <span className="truncate font-semibold">{c.type === 'TELEGRAM' ? '✈️' : '💬'} {c.name}</span>
                          <input type="checkbox" checked={isSelected} onChange={() => {}} className="pointer-events-none" />
                        </div>
                      );
                    })}
                  </div>
                )}
              </div>

              <div className="pt-3 border-t border-border-main flex justify-end gap-2 shrink-0">
                <button
                  type="button"
                  onClick={() => setIsWalFormOpen(false)}
                  className="px-4 py-2 bg-bg-main hover:bg-bg-hover border border-border-main rounded text-text-muted font-semibold"
                >
                  Batal
                </button>
                <button
                  type="submit"
                  disabled={isSubmittingWal}
                  className="px-4 py-2 bg-amber-600 hover:bg-amber-500 text-white font-bold rounded shadow transition-colors disabled:opacity-50"
                >
                  {isSubmittingWal ? 'Menyimpan...' : 'Simpan Schedule'}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
};
