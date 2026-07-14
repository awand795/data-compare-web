import React, { useEffect, useState } from 'react';
import { Play, Pause, RotateCcw, Trash2, Activity, AlertTriangle, CheckCircle2, ChevronDown, ChevronRight, Search, Settings, Eye, BarChart2, X, Save } from 'lucide-react';
import { useAppStore } from '../store/useAppStore';
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

  const [lagHistory, setLagHistory] = useState<Record<string, {time: string, lag: number}[]>>({});
  const [statsModalOpen, setStatsModalOpen] = useState<Pipeline | null>(null);

  const filteredPipelines = pipelines.filter(p => p.name.toLowerCase().includes(searchQuery.toLowerCase()));

  const toggleGroup = (deployId: string) => {
    setExpandedGroups(prev => ({
      ...prev,
      [deployId]: !prev[deployId]
    }));
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
          <button onClick={fetchPipelines} className="text-[11px] text-text-muted hover:text-indigo-400 font-bold uppercase tracking-wide px-2 py-1 bg-indigo-500/10 rounded">
            Refresh
          </button>
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
                  className="bg-bg-header/50 border-b border-border-main px-4 py-3 flex items-start justify-between cursor-pointer hover:bg-bg-header/80 transition-colors"
                  onClick={() => toggleGroup(deployId)}
                >
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
                  <span className="text-[11px] font-bold text-text-muted bg-bg-panel px-2 py-1 rounded flex-shrink-0 mt-1">
                    {groupPipelines.length} Connector(s)
                  </span>
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
                className="w-full h-[60vh] bg-[#0d1117] text-[#c9d1d9] font-mono text-sm p-4 rounded-lg border border-border-main focus:border-indigo-500 outline-none"
                value={configData}
                onChange={e => setConfigData(e.target.value)}
                spellCheck={false}
              />
            </div>
            <div className="px-5 py-4 border-t border-border-main flex justify-end gap-3">
              <button onClick={() => setConfigModalOpen(null)} className="px-4 py-2 rounded-lg text-sm font-bold text-text-main hover:bg-bg-header">Cancel</button>
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
            <div className="p-5 overflow-auto max-h-[70vh] bg-[#0d1117] text-[#c9d1d9] font-mono text-xs whitespace-pre-wrap">
              {isPeeking ? (
                <div className="flex items-center gap-2 text-indigo-400"><Activity className="w-4 h-4 animate-spin" /> Consuming latest messages from Kafka...</div>
              ) : peekData.length === 0 ? (
                <div className="text-text-muted">No messages found or topic is empty.</div>
              ) : (
                peekData.map((msg, i) => (
                  <div key={i} className="mb-4 pb-4 border-b border-border-main/30 last:border-0 last:mb-0 last:pb-0 break-words">
                    <div className="text-indigo-400 mb-1">Offset: {msg.offset} | Partition: {msg.partition} | Time: {new Date(msg.timestamp).toLocaleString()}</div>
                    <div className="text-emerald-400">Key: {msg.key}</div>
                    <div className="text-text-main mt-1">{msg.value && msg.value.startsWith('{') ? JSON.stringify(JSON.parse(msg.value), null, 2) : msg.value}</div>
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
            <div className="p-5 overflow-auto bg-[#0d1117] font-mono text-[11px] text-red-300 whitespace-pre-wrap">
              {selectedTrace}
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
