import React, { useState, useEffect } from 'react';
import axios from 'axios';
import { useAppStore } from '../store/useAppStore';
import { 
  Globe, Plus, Save, Play, Pencil, Trash2, Check, Copy, 
  Search, Clock, Database, X, Loader2, 
  RefreshCw, FileText, Code2, ShieldCheck,
  Zap, CheckCircle2, AlertCircle
} from 'lucide-react';
import clsx from 'clsx';

interface ApiSchedulerConfig {
  id?: string;
  name: string;
  method: string;
  url: string;
  queryParams?: string;
  headers?: string;
  authType?: string;
  authUsername?: string;
  authPassword?: string;
  authToken?: string;
  bodyType?: string;
  bodyContent?: string;
  targetConnectionId?: string;
  targetTable?: string;
  kodeData?: string;
  cronExpression?: string;
  active?: boolean;
  createdAt?: string;
  updatedAt?: string;
  lastRunAt?: string;
  lastRunStatus?: string;
  lastRunMessage?: string;
}

interface KeyValuePair {
  key: string;
  value: string;
  enabled: boolean;
}

const getMethodBadgeClass = (method: string) => {
  switch (method.toUpperCase()) {
    case 'GET': return 'bg-emerald-500/15 text-emerald-400 border border-emerald-500/30';
    case 'POST': return 'bg-blue-500/15 text-blue-400 border border-blue-500/30';
    case 'PUT': return 'bg-amber-500/15 text-amber-400 border border-amber-500/30';
    case 'PATCH': return 'bg-purple-500/15 text-purple-400 border border-purple-500/30';
    case 'DELETE': return 'bg-rose-500/15 text-rose-400 border border-rose-500/30';
    default: return 'bg-slate-500/15 text-slate-400 border border-slate-500/30';
  }
};

export const ApiSchedulerView: React.FC = () => {
  const { connections, addToast } = useAppStore();
  const [schedulers, setSchedulers] = useState<ApiSchedulerConfig[]>([]);
  const [loading, setLoading] = useState(true);
  const [searchQuery, setSearchQuery] = useState('');
  const [methodFilter, setMethodFilter] = useState('ALL');
  const [statusFilter, setStatusFilter] = useState('ALL');

  // Modal / Editor State (Insomnia UI)
  const [isModalOpen, setIsModalOpen] = useState(false);
  const [currentConfig, setCurrentConfig] = useState<Partial<ApiSchedulerConfig>>({
    method: 'GET',
    url: '',
    name: '',
    authType: 'none',
    bodyType: 'json',
    targetTable: 'sch_sync.tb_api_data',
    kodeData: 'API_KODE_DATA_V1',
    cronExpression: 'EVERY_5M',
    active: true,
  });

  // Insomnia Tabs State
  const [activeReqTab, setActiveReqTab] = useState<'params' | 'headers' | 'auth' | 'body' | 'target' | 'schedule'>('params');
  const [queryParamsList, setQueryParamsList] = useState<KeyValuePair[]>([{ key: '', value: '', enabled: true }]);
  const [headersList, setHeadersList] = useState<KeyValuePair[]>([
    { key: 'Content-Type', value: 'application/json', enabled: true },
    { key: 'Accept', value: 'application/json', enabled: true }
  ]);

  // Test Response State
  const [isTesting, setIsTesting] = useState(false);
  const [testResponse, setTestResponse] = useState<{
    statusCode?: number;
    durationMs?: number;
    body?: string;
    headers?: Record<string, string>;
  } | null>(null);
  const [activeRespTab, setActiveRespTab] = useState<'body' | 'headers'>('body');
  const [isCopied, setIsCopied] = useState(false);
  const [runningId, setRunningId] = useState<string | null>(null);

  const fetchSchedulers = async () => {
    setLoading(true);
    try {
      const res = await axios.get('/api/api-schedulers');
      if (Array.isArray(res.data)) {
        setSchedulers(res.data);
      }
    } catch (err: any) {
      addToast({ type: 'error', title: 'Error', message: 'Failed to load API schedulers' });
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchSchedulers();
  }, []);

  const openNewModal = () => {
    setCurrentConfig({
      method: 'GET',
      url: '',
      name: '',
      authType: 'none',
      bodyType: 'json',
      targetConnectionId: connections.length > 0 ? connections[0].id : '',
      targetTable: 'sch_sync.tb_api_data',
      kodeData: 'API_KODE_V1',
      cronExpression: 'EVERY_5M',
      active: true,
    });
    setQueryParamsList([{ key: '', value: '', enabled: true }]);
    setHeadersList([
      { key: 'Content-Type', value: 'application/json', enabled: true },
      { key: 'Accept', value: 'application/json', enabled: true }
    ]);
    setTestResponse(null);
    setActiveReqTab('params');
    setIsModalOpen(true);
  };

  const openEditModal = (cfg: ApiSchedulerConfig) => {
    setCurrentConfig(cfg);
    
    // Parse query params
    if (cfg.queryParams) {
      try {
        const obj = JSON.parse(cfg.queryParams);
        const list = Object.entries(obj).map(([k, v]) => ({ key: k, value: String(v), enabled: true }));
        setQueryParamsList(list.length > 0 ? list : [{ key: '', value: '', enabled: true }]);
      } catch (e) {
        setQueryParamsList([{ key: '', value: '', enabled: true }]);
      }
    } else {
      setQueryParamsList([{ key: '', value: '', enabled: true }]);
    }

    // Parse headers
    if (cfg.headers) {
      try {
        const obj = JSON.parse(cfg.headers);
        const list = Object.entries(obj).map(([k, v]) => ({ key: k, value: String(v), enabled: true }));
        setHeadersList(list.length > 0 ? list : [{ key: 'Content-Type', value: 'application/json', enabled: true }]);
      } catch (e) {
        setHeadersList([{ key: 'Content-Type', value: 'application/json', enabled: true }]);
      }
    } else {
      setHeadersList([{ key: 'Content-Type', value: 'application/json', enabled: true }]);
    }

    setTestResponse(null);
    setActiveReqTab('params');
    setIsModalOpen(true);
  };

  // Helper to compile KeyValuePair to JSON string
  const compileListToJson = (list: KeyValuePair[]) => {
    const obj: Record<string, string> = {};
    list.forEach(item => {
      if (item.enabled && item.key.trim()) {
        obj[item.key.trim()] = item.value;
      }
    });
    return JSON.stringify(obj);
  };

  const handleTestEndpoint = async () => {
    if (!currentConfig.url || !currentConfig.url.trim()) {
      addToast({ type: 'warning', title: 'URL Required', message: 'Please enter a valid HTTP endpoint URL' });
      return;
    }

    setIsTesting(true);
    setTestResponse(null);

    const payloadToTest = {
      ...currentConfig,
      queryParams: compileListToJson(queryParamsList),
      headers: compileListToJson(headersList),
    };

    try {
      const res = await axios.post('/api/api-schedulers/test', payloadToTest);
      setTestResponse(res.data);
      if (res.data.statusCode >= 200 && res.data.statusCode < 300) {
        addToast({ type: 'success', title: 'Test Successful', message: `HTTP ${res.data.statusCode} (${res.data.durationMs}ms)` });
      } else {
        addToast({ type: 'warning', title: 'API Response Error', message: `HTTP ${res.data.statusCode || 'Error'}` });
      }
    } catch (err: any) {
      setTestResponse({
        statusCode: 500,
        durationMs: 0,
        body: err.response?.data?.error || err.message || 'Request failed'
      });
      addToast({ type: 'error', title: 'Test Failed', message: err.message });
    } finally {
      setIsTesting(false);
    }
  };

  const handleSaveSchedule = async () => {
    if (!currentConfig.url || !currentConfig.url.trim()) {
      addToast({ type: 'warning', title: 'Validation Error', message: 'URL is required' });
      return;
    }
    if (!currentConfig.name || !currentConfig.name.trim()) {
      addToast({ type: 'warning', title: 'Validation Error', message: 'Schedule Name is required' });
      return;
    }

    const finalConfig = {
      ...currentConfig,
      name: currentConfig.name.trim(),
      url: currentConfig.url.trim(),
      queryParams: compileListToJson(queryParamsList),
      headers: compileListToJson(headersList),
    };

    try {
      if (finalConfig.id) {
        await axios.put(`/api/api-schedulers/${finalConfig.id}`, finalConfig);
        addToast({ type: 'success', title: 'Schedule Updated', message: `Successfully updated [${finalConfig.name}]` });
      } else {
        await axios.post('/api/api-schedulers', finalConfig);
        addToast({ type: 'success', title: 'Schedule Created', message: `Successfully created [${finalConfig.name}]` });
      }
      setIsModalOpen(false);
      fetchSchedulers();
    } catch (err: any) {
      addToast({ type: 'error', title: 'Save Failed', message: err.response?.data?.error || err.message });
    }
  };

  const handleRunNow = async (cfg: ApiSchedulerConfig) => {
    if (!cfg.id) return;
    setRunningId(cfg.id);
    try {
      const res = await axios.post(`/api/api-schedulers/${cfg.id}/run-now`);
      addToast({ 
        type: res.data?.lastRunStatus === 'SUCCESS' ? 'success' : 'error', 
        title: res.data?.lastRunStatus === 'SUCCESS' ? 'Execution Success' : 'Execution Failed', 
        message: res.data?.lastRunMessage || 'Completed'
      });
      fetchSchedulers();
    } catch (err: any) {
      addToast({ type: 'error', title: 'Execution Error', message: err.message });
    } finally {
      setRunningId(null);
    }
  };

  const handleDelete = async (id: string, name: string) => {
    if (!confirm(`Are you sure you want to delete API schedule "${name}"?`)) return;
    try {
      await axios.delete(`/api/api-schedulers/${id}`);
      addToast({ type: 'success', title: 'Deleted', message: `Schedule [${name}] removed` });
      fetchSchedulers();
    } catch (err: any) {
      addToast({ type: 'error', title: 'Delete Failed', message: err.message });
    }
  };

  const handleToggleActive = async (cfg: ApiSchedulerConfig) => {
    if (!cfg.id) return;
    try {
      const updated = { ...cfg, active: !cfg.active };
      await axios.put(`/api/api-schedulers/${cfg.id}`, updated);
      addToast({ type: 'info', title: 'Status Changed', message: `Schedule [${cfg.name}] is now ${updated.active ? 'Active' : 'Paused'}` });
      fetchSchedulers();
    } catch (err: any) {
      addToast({ type: 'error', title: 'Update Failed', message: err.message });
    }
  };

  // Filtered schedulers for Table View
  const filteredSchedulers = schedulers.filter(s => {
    const matchesSearch = searchQuery === '' || 
      s.name.toLowerCase().includes(searchQuery.toLowerCase()) || 
      s.url.toLowerCase().includes(searchQuery.toLowerCase()) || 
      (s.kodeData && s.kodeData.toLowerCase().includes(searchQuery.toLowerCase())) ||
      (s.targetTable && s.targetTable.toLowerCase().includes(searchQuery.toLowerCase()));

    const matchesMethod = methodFilter === 'ALL' || s.method.toUpperCase() === methodFilter;
    const matchesStatus = statusFilter === 'ALL' || 
      (statusFilter === 'Active' && s.active) || 
      (statusFilter === 'Paused' && !s.active);

    return matchesSearch && matchesMethod && matchesStatus;
  });

  const getPrettyJson = (raw: string | undefined) => {
    if (!raw) return '';
    try {
      return JSON.stringify(JSON.parse(raw), null, 2);
    } catch (e) {
      return raw;
    }
  };

  return (
    <div className="h-full flex flex-col bg-bg-main text-text-main p-4 md:p-6 overflow-hidden">
      {/* Top Banner Header */}
      <div className="flex flex-col md:flex-row md:items-center justify-between gap-4 mb-6 shrink-0">
        <div>
          <div className="flex items-center gap-3 mb-1">
            <div className="w-10 h-10 rounded-xl bg-gradient-to-br from-indigo-500 to-cyan-500 flex items-center justify-center shadow-lg shadow-indigo-500/25">
              <Globe className="w-5.5 h-5.5 text-white" />
            </div>
            <div>
              <h1 className="text-xl md:text-2xl font-bold bg-gradient-to-r from-indigo-400 via-cyan-300 to-blue-400 bg-clip-text text-transparent">
                API Ingestion & Scheduler
              </h1>
              <p className="text-xs md:text-sm text-text-muted">
                Insomnia-style HTTP Client with automated periodic ingestion into ClickHouse & PostgreSQL
              </p>
            </div>
          </div>
        </div>

        <div className="flex items-center gap-3">
          <button
            onClick={fetchSchedulers}
            className="p-2.5 rounded-xl bg-bg-panel hover:bg-bg-hover text-text-muted hover:text-text-main border border-border-main transition-all"
            title="Refresh List"
          >
            <RefreshCw className={clsx("w-4 h-4", loading && "animate-spin")} />
          </button>

          <button
            onClick={openNewModal}
            className="flex items-center gap-2 px-4 py-2.5 rounded-xl bg-gradient-to-r from-blue-600 via-cyan-500 to-blue-500 hover:from-blue-500 hover:to-cyan-400 text-white font-medium shadow-lg shadow-blue-500/25 hover:shadow-blue-500/40 transition-all text-sm"
          >
            <Plus className="w-4.5 h-4.5" />
            <span>New API Schedule</span>
          </button>
        </div>
      </div>

      {/* Filter Bar */}
      <div className="flex flex-col sm:flex-row items-center justify-between gap-3 bg-bg-panel border border-border-main p-3 rounded-2xl mb-4 shadow-sm shrink-0">
        <div className="flex items-center gap-3 w-full sm:w-auto flex-1 max-w-md">
          <div className="relative w-full">
            <Search className="w-4 h-4 text-text-muted absolute left-3 top-1/2 -translate-y-1/2" />
            <input
              type="text"
              placeholder="Search by name, URL, kode_data, or target table..."
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              className="w-full bg-bg-main border border-border-main rounded-xl pl-9 pr-4 py-2 text-sm text-text-main placeholder:text-text-muted focus:outline-none focus:border-blue-500 transition-colors"
            />
          </div>
        </div>

        <div className="flex items-center gap-2 w-full sm:w-auto overflow-x-auto pb-1 sm:pb-0">
          <div className="flex items-center gap-1.5 bg-bg-main border border-border-main p-1 rounded-xl">
            {['ALL', 'GET', 'POST', 'PUT', 'DELETE'].map(method => (
              <button
                key={method}
                onClick={() => setMethodFilter(method)}
                className={clsx(
                  "px-3 py-1 rounded-lg text-xs font-semibold transition-all",
                  methodFilter === method ? "bg-blue-500/20 text-blue-400 border border-blue-500/30" : "text-text-muted hover:text-text-main"
                )}
              >
                {method}
              </button>
            ))}
          </div>

          <div className="flex items-center gap-1.5 bg-bg-main border border-border-main p-1 rounded-xl">
            {['ALL', 'Active', 'Paused'].map(status => (
              <button
                key={status}
                onClick={() => setStatusFilter(status)}
                className={clsx(
                  "px-3 py-1 rounded-lg text-xs font-semibold transition-all",
                  statusFilter === status ? "bg-cyan-500/20 text-cyan-400 border border-cyan-500/30" : "text-text-muted hover:text-text-main"
                )}
              >
                {status}
              </button>
            ))}
          </div>
        </div>
      </div>

      {/* Main Table View (Default View as requested) */}
      <div className="flex-1 bg-bg-panel border border-border-main rounded-2xl overflow-hidden shadow-sm flex flex-col min-h-0">
        {loading ? (
          <div className="flex-1 flex flex-col items-center justify-center p-8 text-text-muted">
            <Loader2 className="w-8 h-8 animate-spin text-blue-400 mb-3" />
            <p className="text-sm">Loading API Schedulers...</p>
          </div>
        ) : filteredSchedulers.length === 0 ? (
          <div className="flex-1 flex flex-col items-center justify-center p-8 text-center text-text-muted">
            <div className="w-14 h-14 rounded-2xl bg-bg-hover flex items-center justify-center mb-3 text-text-muted">
              <Globe className="w-7 h-7" />
            </div>
            <h3 className="text-base font-semibold text-text-main mb-1">No API Schedulers Found</h3>
            <p className="text-xs max-w-sm mb-4">
              {searchQuery || methodFilter !== 'ALL' || statusFilter !== 'ALL' 
                ? "No schedules match your active search filters." 
                : "Create your first API Scheduler to start ingesting automated HTTP endpoints into ClickHouse or PostgreSQL."}
            </p>
            <button
              onClick={openNewModal}
              className="flex items-center gap-2 px-4 py-2 rounded-xl bg-blue-600 hover:bg-blue-500 text-white font-medium text-xs transition-colors"
            >
              <Plus className="w-4 h-4" />
              <span>Create New API Schedule</span>
            </button>
          </div>
        ) : (
          <div className="flex-1 overflow-auto">
            <table className="w-full text-left border-collapse min-w-[900px]">
              <thead className="sticky top-0 bg-bg-main/90 backdrop-blur-md border-b border-border-main text-xs font-semibold text-text-muted uppercase tracking-wider z-10">
                <tr>
                  <th className="py-3.5 px-4 w-16 text-center">Status</th>
                  <th className="py-3.5 px-4">Schedule & Endpoint</th>
                  <th className="py-3.5 px-4">Target Storage</th>
                  <th className="py-3.5 px-4">Interval</th>
                  <th className="py-3.5 px-4">Last Run Status</th>
                  <th className="py-3.5 px-4 text-right pr-6">Actions</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border-main text-sm">
                {filteredSchedulers.map((cfg) => {
                  const conn = connections.find(c => String(c.id) === String(cfg.targetConnectionId));
                  return (
                    <tr key={cfg.id} className="hover:bg-bg-hover/60 transition-colors group">
                      {/* Active Status Switch */}
                      <td className="py-3.5 px-4 text-center">
                        <button
                          onClick={() => handleToggleActive(cfg)}
                          className={clsx(
                            "w-9 h-5 rounded-full p-0.5 transition-colors relative inline-block",
                            cfg.active ? "bg-emerald-500" : "bg-slate-700"
                          )}
                          title={cfg.active ? "Click to Pause" : "Click to Activate"}
                        >
                          <div className={clsx(
                            "w-4 h-4 rounded-full bg-white transition-transform shadow-md",
                            cfg.active ? "translate-x-4" : "translate-x-0"
                          )} />
                        </button>
                      </td>

                      {/* Name & Endpoint */}
                      <td className="py-3.5 px-4">
                        <div className="flex flex-col gap-1">
                          <div className="flex items-center gap-2">
                            <span className={clsx("px-2 py-0.5 rounded text-[10px] font-bold tracking-wide", getMethodBadgeClass(cfg.method))}>
                              {cfg.method}
                            </span>
                            <span className="font-bold text-text-main group-hover:text-blue-400 transition-colors">
                              {cfg.name}
                            </span>
                          </div>
                          <span className="text-xs font-mono text-text-muted truncate max-w-md" title={cfg.url}>
                            {cfg.url}
                          </span>
                        </div>
                      </td>

                      {/* Target Storage */}
                      <td className="py-3.5 px-4">
                        <div className="flex flex-col gap-1">
                          <div className="flex items-center gap-1.5 text-xs text-text-main font-medium">
                            <Database className="w-3.5 h-3.5 text-cyan-400" />
                            <span>{conn ? conn.name : 'Target DB'}</span>
                            <span className="text-text-muted font-mono">({cfg.targetTable || 'sch_sync.tb_api_data'})</span>
                          </div>
                          <div className="flex items-center gap-1">
                            <span className="px-2 py-0.5 rounded-full text-[10px] font-mono bg-cyan-500/10 text-cyan-400 border border-cyan-500/20">
                              kode_data: {cfg.kodeData || 'API_KODE'}
                            </span>
                          </div>
                        </div>
                      </td>

                      {/* Interval */}
                      <td className="py-3.5 px-4">
                        <div className="flex items-center gap-1.5 text-xs font-medium text-text-muted">
                          <Clock className="w-3.5 h-3.5 text-amber-400" />
                          <span>{cfg.cronExpression || 'EVERY_5M'}</span>
                        </div>
                      </td>

                      {/* Last Run Status */}
                      <td className="py-3.5 px-4">
                        <div className="flex flex-col gap-1">
                          {cfg.lastRunStatus === 'SUCCESS' ? (
                            <span className="inline-flex items-center gap-1 px-2.5 py-0.5 rounded-full text-xs font-semibold bg-emerald-500/15 text-emerald-400 border border-emerald-500/30 w-fit">
                              <CheckCircle2 className="w-3 h-3" />
                              SUCCESS
                            </span>
                          ) : cfg.lastRunStatus === 'FAILED' ? (
                            <span className="inline-flex items-center gap-1 px-2.5 py-0.5 rounded-full text-xs font-semibold bg-rose-500/15 text-rose-400 border border-rose-500/30 w-fit" title={cfg.lastRunMessage}>
                              <AlertCircle className="w-3 h-3" />
                              FAILED
                            </span>
                          ) : (
                            <span className="inline-flex items-center gap-1 px-2.5 py-0.5 rounded-full text-xs font-semibold bg-slate-500/15 text-slate-400 border border-slate-500/30 w-fit">
                              PENDING
                            </span>
                          )}
                          <span className="text-[11px] text-text-muted">
                            {cfg.lastRunAt ? new Date(cfg.lastRunAt).toLocaleString() : 'Never executed'}
                          </span>
                        </div>
                      </td>

                      {/* Actions */}
                      <td className="py-3.5 px-4 text-right pr-6">
                        <div className="flex items-center justify-end gap-1.5">
                          <button
                            onClick={() => handleRunNow(cfg)}
                            disabled={runningId === cfg.id}
                            className="p-1.5 rounded-lg bg-emerald-500/10 hover:bg-emerald-500/20 text-emerald-400 hover:text-emerald-300 border border-emerald-500/20 transition-all"
                            title="Run Immediately & Ingest Data"
                          >
                            {runningId === cfg.id ? <Loader2 className="w-4 h-4 animate-spin" /> : <Play className="w-4 h-4 fill-emerald-400/20" />}
                          </button>

                          <button
                            onClick={() => openEditModal(cfg)}
                            className="p-1.5 rounded-lg bg-blue-500/10 hover:bg-blue-500/20 text-blue-400 hover:text-blue-300 border border-blue-500/20 transition-all"
                            title="Edit Insomnia HTTP Client & Schedule"
                          >
                            <Pencil className="w-4 h-4" />
                          </button>

                          <button
                            onClick={() => handleDelete(cfg.id!, cfg.name)}
                            className="p-1.5 rounded-lg bg-rose-500/10 hover:bg-rose-500/20 text-rose-400 hover:text-rose-300 border border-rose-500/20 transition-all"
                            title="Delete Schedule"
                          >
                            <Trash2 className="w-4 h-4" />
                          </button>
                        </div>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {/* Insomnia-Style Modal / Screen */}
      {isModalOpen && (
        <div className="fixed inset-0 z-50 bg-black/70 backdrop-blur-md flex items-center justify-center p-2 md:p-6 animate-fadeIn">
          <div className="bg-bg-panel border border-border-main rounded-2xl w-full max-w-6xl h-[92vh] flex flex-col overflow-hidden shadow-2xl">
            
            {/* Modal Top Header (Insomnia URL Bar) */}
            <div className="bg-bg-main border-b border-border-main p-3.5 flex flex-col md:flex-row items-center gap-3 shrink-0">
              <div className="flex items-center gap-2 w-full md:w-auto">
                <select
                  value={currentConfig.method || 'GET'}
                  onChange={(e) => setCurrentConfig({ ...currentConfig, method: e.target.value })}
                  className={clsx(
                    "px-3 py-2 rounded-xl text-xs font-bold tracking-wider focus:outline-none border border-border-main bg-bg-panel cursor-pointer",
                    getMethodBadgeClass(currentConfig.method || 'GET')
                  )}
                >
                  <option value="GET">GET</option>
                  <option value="POST">POST</option>
                  <option value="PUT">PUT</option>
                  <option value="DELETE">DELETE</option>
                  <option value="PATCH">PATCH</option>
                </select>

                <input
                  type="text"
                  placeholder="https://api.example.com/v1/resource..."
                  value={currentConfig.url || ''}
                  onChange={(e) => setCurrentConfig({ ...currentConfig, url: e.target.value })}
                  className="flex-1 bg-bg-panel border border-border-main rounded-xl px-3.5 py-2 text-xs font-mono text-text-main placeholder:text-text-muted focus:outline-none focus:border-blue-500 transition-colors"
                />
              </div>

              <div className="flex items-center justify-between md:justify-end gap-2 w-full md:w-auto">
                <input
                  type="text"
                  placeholder="Schedule Name e.g. Weather API Daily"
                  value={currentConfig.name || ''}
                  onChange={(e) => setCurrentConfig({ ...currentConfig, name: e.target.value })}
                  className="bg-bg-panel border border-border-main rounded-xl px-3 py-2 text-xs text-text-main placeholder:text-text-muted focus:outline-none focus:border-blue-500 transition-colors w-48 md:w-56"
                />

                <button
                  onClick={handleTestEndpoint}
                  disabled={isTesting}
                  className="flex items-center gap-1.5 px-4 py-2 rounded-xl bg-amber-500 hover:bg-amber-400 text-slate-950 font-bold text-xs shadow-md shadow-amber-500/20 transition-all disabled:opacity-50"
                  title="Test HTTP Request (Insomnia Console)"
                >
                  {isTesting ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Zap className="w-3.5 h-3.5 fill-slate-950" />}
                  <span>Test Endpoint</span>
                </button>

                <button
                  onClick={handleSaveSchedule}
                  className="flex items-center gap-1.5 px-4 py-2 rounded-xl bg-gradient-to-r from-blue-600 to-cyan-500 hover:from-blue-500 hover:to-cyan-400 text-white font-bold text-xs shadow-md shadow-blue-500/20 transition-all"
                >
                  <Save className="w-3.5 h-3.5" />
                  <span>Save Schedule</span>
                </button>

                <button
                  onClick={() => setIsModalOpen(false)}
                  className="p-2 rounded-xl hover:bg-bg-hover text-text-muted hover:text-text-main transition-colors ml-1"
                >
                  <X className="w-5 h-5" />
                </button>
              </div>
            </div>

            {/* Main Split Panel (Request Tabs Left / Response Viewer Right) */}
            <div className="flex-1 grid grid-cols-1 lg:grid-cols-12 min-h-0 divide-y lg:divide-y-0 lg:divide-x divide-border-main overflow-hidden">
              
              {/* Left Column: Request Configuration (Insomnia Tabs) */}
              <div className="lg:col-span-7 flex flex-col min-h-0 bg-bg-panel">
                {/* Request Tabs Header */}
                <div className="flex items-center gap-1 border-b border-border-main bg-bg-main px-3 pt-2 overflow-x-auto shrink-0">
                  {[
                    { id: 'params', label: 'Params', icon: Search },
                    { id: 'headers', label: 'Headers', icon: FileText },
                    { id: 'auth', label: 'Auth', icon: ShieldCheck },
                    { id: 'body', label: 'Body', icon: Code2 },
                    { id: 'target', label: 'Target Storage', icon: Database },
                    { id: 'schedule', label: 'Schedule', icon: Clock },
                  ].map((tab) => {
                    const Icon = tab.icon;
                    return (
                      <button
                        key={tab.id}
                        onClick={() => setActiveReqTab(tab.id as any)}
                        className={clsx(
                          "flex items-center gap-1.5 px-3.5 py-2 text-xs font-semibold rounded-t-xl transition-all border-t border-x",
                          activeReqTab === tab.id
                            ? "bg-bg-panel text-blue-400 border-border-main border-b-transparent -mb-px"
                            : "text-text-muted border-transparent hover:text-text-main hover:bg-bg-hover"
                        )}
                      >
                        <Icon className="w-3.5 h-3.5" />
                        <span>{tab.label}</span>
                      </button>
                    );
                  })}
                </div>

                {/* Tab Content Body */}
                <div className="flex-1 p-4 overflow-y-auto">
                  {/* TAB 1: PARAMS */}
                  {activeReqTab === 'params' && (
                    <div className="space-y-3">
                      <div className="flex items-center justify-between mb-2">
                        <span className="text-xs font-semibold text-text-muted">URL Query Parameters</span>
                        <button
                          onClick={() => setQueryParamsList([...queryParamsList, { key: '', value: '', enabled: true }])}
                          className="flex items-center gap-1 px-2.5 py-1 rounded-lg bg-blue-500/10 text-blue-400 text-xs font-medium hover:bg-blue-500/20 transition-colors"
                        >
                          <Plus className="w-3.5 h-3.5" />
                          <span>Add Param</span>
                        </button>
                      </div>

                      {queryParamsList.map((item, idx) => (
                        <div key={idx} className="flex items-center gap-2">
                          <input
                            type="checkbox"
                            checked={item.enabled}
                            onChange={(e) => {
                              const copy = [...queryParamsList];
                              copy[idx].enabled = e.target.checked;
                              setQueryParamsList(copy);
                            }}
                            className="rounded border-border-main text-blue-500 focus:ring-0"
                          />
                          <input
                            type="text"
                            placeholder="Key (e.g. limit)"
                            value={item.key}
                            onChange={(e) => {
                              const copy = [...queryParamsList];
                              copy[idx].key = e.target.value;
                              setQueryParamsList(copy);
                            }}
                            className="flex-1 bg-bg-main border border-border-main rounded-xl px-3 py-1.5 text-xs text-text-main font-mono placeholder:text-text-muted focus:outline-none focus:border-blue-500"
                          />
                          <input
                            type="text"
                            placeholder="Value (e.g. 50)"
                            value={item.value}
                            onChange={(e) => {
                              const copy = [...queryParamsList];
                              copy[idx].value = e.target.value;
                              setQueryParamsList(copy);
                            }}
                            className="flex-1 bg-bg-main border border-border-main rounded-xl px-3 py-1.5 text-xs text-text-main font-mono placeholder:text-text-muted focus:outline-none focus:border-blue-500"
                          />
                          <button
                            onClick={() => setQueryParamsList(queryParamsList.filter((_, i) => i !== idx))}
                            className="p-1.5 text-text-muted hover:text-rose-400 transition-colors"
                          >
                            <Trash2 className="w-4 h-4" />
                          </button>
                        </div>
                      ))}
                    </div>
                  )}

                  {/* TAB 2: HEADERS */}
                  {activeReqTab === 'headers' && (
                    <div className="space-y-3">
                      <div className="flex items-center justify-between mb-2">
                        <span className="text-xs font-semibold text-text-muted">HTTP Request Headers</span>
                        <button
                          onClick={() => setHeadersList([...headersList, { key: '', value: '', enabled: true }])}
                          className="flex items-center gap-1 px-2.5 py-1 rounded-lg bg-blue-500/10 text-blue-400 text-xs font-medium hover:bg-blue-500/20 transition-colors"
                        >
                          <Plus className="w-3.5 h-3.5" />
                          <span>Add Header</span>
                        </button>
                      </div>

                      {headersList.map((item, idx) => (
                        <div key={idx} className="flex items-center gap-2">
                          <input
                            type="checkbox"
                            checked={item.enabled}
                            onChange={(e) => {
                              const copy = [...headersList];
                              copy[idx].enabled = e.target.checked;
                              setHeadersList(copy);
                            }}
                            className="rounded border-border-main text-blue-500 focus:ring-0"
                          />
                          <input
                            type="text"
                            placeholder="Header (e.g. Content-Type)"
                            value={item.key}
                            onChange={(e) => {
                              const copy = [...headersList];
                              copy[idx].key = e.target.value;
                              setHeadersList(copy);
                            }}
                            className="flex-1 bg-bg-main border border-border-main rounded-xl px-3 py-1.5 text-xs text-text-main font-mono placeholder:text-text-muted focus:outline-none focus:border-blue-500"
                          />
                          <input
                            type="text"
                            placeholder="Value"
                            value={item.value}
                            onChange={(e) => {
                              const copy = [...headersList];
                              copy[idx].value = e.target.value;
                              setHeadersList(copy);
                            }}
                            className="flex-1 bg-bg-main border border-border-main rounded-xl px-3 py-1.5 text-xs text-text-main font-mono placeholder:text-text-muted focus:outline-none focus:border-blue-500"
                          />
                          <button
                            onClick={() => setHeadersList(headersList.filter((_, i) => i !== idx))}
                            className="p-1.5 text-text-muted hover:text-rose-400 transition-colors"
                          >
                            <Trash2 className="w-4 h-4" />
                          </button>
                        </div>
                      ))}
                    </div>
                  )}

                  {/* TAB 3: AUTH */}
                  {activeReqTab === 'auth' && (
                    <div className="space-y-4 max-w-md">
                      <div>
                        <label className="block text-xs font-semibold text-text-muted mb-1.5">Authentication Type</label>
                        <div className="grid grid-cols-3 gap-2">
                          {['none', 'basic', 'bearer'].map(auth => (
                            <button
                              key={auth}
                              type="button"
                              onClick={() => setCurrentConfig({ ...currentConfig, authType: auth })}
                              className={clsx(
                                "py-2 px-3 rounded-xl text-xs font-bold capitalize transition-all border",
                                currentConfig.authType === auth
                                  ? "bg-blue-500/20 text-blue-400 border-blue-500/40 shadow-sm"
                                  : "bg-bg-main text-text-muted border-border-main hover:text-text-main"
                              )}
                            >
                              {auth}
                            </button>
                          ))}
                        </div>
                      </div>

                      {currentConfig.authType === 'basic' && (
                        <div className="space-y-3 bg-bg-main p-3.5 border border-border-main rounded-xl">
                          <div>
                            <label className="block text-xs text-text-muted mb-1">Username</label>
                            <input
                              type="text"
                              value={currentConfig.authUsername || ''}
                              onChange={(e) => setCurrentConfig({ ...currentConfig, authUsername: e.target.value })}
                              className="w-full bg-bg-panel border border-border-main rounded-xl px-3 py-1.5 text-xs text-text-main focus:outline-none focus:border-blue-500"
                            />
                          </div>
                          <div>
                            <label className="block text-xs text-text-muted mb-1">Password</label>
                            <input
                              type="password"
                              value={currentConfig.authPassword || ''}
                              onChange={(e) => setCurrentConfig({ ...currentConfig, authPassword: e.target.value })}
                              className="w-full bg-bg-panel border border-border-main rounded-xl px-3 py-1.5 text-xs text-text-main focus:outline-none focus:border-blue-500"
                            />
                          </div>
                        </div>
                      )}

                      {currentConfig.authType === 'bearer' && (
                        <div className="bg-bg-main p-3.5 border border-border-main rounded-xl">
                          <label className="block text-xs text-text-muted mb-1">Bearer Token</label>
                          <input
                            type="text"
                            placeholder="eyJhbGciOiJIUzI1NiIsIn..."
                            value={currentConfig.authToken || ''}
                            onChange={(e) => setCurrentConfig({ ...currentConfig, authToken: e.target.value })}
                            className="w-full bg-bg-panel border border-border-main rounded-xl px-3 py-1.5 text-xs font-mono text-text-main focus:outline-none focus:border-blue-500"
                          />
                        </div>
                      )}
                    </div>
                  )}

                  {/* TAB 4: BODY */}
                  {activeReqTab === 'body' && (
                    <div className="space-y-3 h-full flex flex-col">
                      <div className="flex items-center gap-3">
                        <span className="text-xs font-semibold text-text-muted">Body Format:</span>
                        {['none', 'json', 'text'].map(type => (
                          <button
                            key={type}
                            type="button"
                            onClick={() => setCurrentConfig({ ...currentConfig, bodyType: type })}
                            className={clsx(
                              "px-2.5 py-1 rounded-lg text-xs font-semibold capitalize transition-all border",
                              currentConfig.bodyType === type
                                ? "bg-blue-500/20 text-blue-400 border-blue-500/40"
                                : "bg-bg-main text-text-muted border-border-main hover:text-text-main"
                            )}
                          >
                            {type}
                          </button>
                        ))}
                      </div>

                      {currentConfig.bodyType !== 'none' && (
                        <textarea
                          placeholder='{ "key": "value" }'
                          value={currentConfig.bodyContent || ''}
                          onChange={(e) => setCurrentConfig({ ...currentConfig, bodyContent: e.target.value })}
                          rows={10}
                          className="w-full flex-1 bg-bg-main border border-border-main rounded-xl p-3 text-xs font-mono text-text-main focus:outline-none focus:border-blue-500 resize-none"
                        />
                      )}
                    </div>
                  )}

                  {/* TAB 5: TARGET STORAGE (Crucial User Requirement!) */}
                  {activeReqTab === 'target' && (
                    <div className="space-y-4">
                      <div className="bg-cyan-500/10 border border-cyan-500/30 p-3.5 rounded-2xl flex items-start gap-3">
                        <Database className="w-5 h-5 text-cyan-400 shrink-0 mt-0.5" />
                        <div className="text-xs text-cyan-200">
                          <p className="font-semibold text-cyan-300 mb-1">Target Ingestion Schema Structure</p>
                          <p>
                            Hasil respon JSON dari API akan disimpan secara otomatis ke tabel target (ClickHouse atau PostgreSQL) dengan struktur 4 kolom standar:
                          </p>
                          <div className="flex flex-wrap gap-2 mt-2 font-mono text-[11px]">
                            <span className="bg-cyan-950/80 px-2 py-0.5 rounded border border-cyan-500/30">kode_data (Custom Code)</span>
                            <span className="bg-cyan-950/80 px-2 py-0.5 rounded border border-cyan-500/30">detail_data (Raw JSON Output)</span>
                            <span className="bg-cyan-950/80 px-2 py-0.5 rounded border border-cyan-500/30">input_by ('darkosync')</span>
                            <span className="bg-cyan-950/80 px-2 py-0.5 rounded border border-cyan-500/30">input_dt (Ingestion Timestamp)</span>
                          </div>
                        </div>
                      </div>

                      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                        <div>
                          <label className="block text-xs font-semibold text-text-muted mb-1">Target Connection Database</label>
                          <select
                            value={currentConfig.targetConnectionId || ''}
                            onChange={(e) => setCurrentConfig({ ...currentConfig, targetConnectionId: e.target.value })}
                            className="w-full bg-bg-main border border-border-main rounded-xl px-3 py-2 text-xs text-text-main focus:outline-none focus:border-blue-500"
                          >
                            <option value="">Select Connection (ClickHouse / PostgreSQL)...</option>
                            {connections.map(c => (
                              <option key={c.id} value={c.id}>{c.name} ({c.type} - {c.host})</option>
                            ))}
                          </select>
                        </div>

                        <div>
                          <label className="block text-xs font-semibold text-text-muted mb-1">Target Table Name</label>
                          <input
                            type="text"
                            placeholder="e.g. sch_sync.tb_api_data or public.tb_weather"
                            value={currentConfig.targetTable || ''}
                            onChange={(e) => setCurrentConfig({ ...currentConfig, targetTable: e.target.value })}
                            className="w-full bg-bg-main border border-border-main rounded-xl px-3 py-2 text-xs font-mono text-text-main focus:outline-none focus:border-blue-500"
                          />
                        </div>
                      </div>

                      <div>
                        <label className="block text-xs font-semibold text-text-muted mb-1">Kode Data Identifier (User Input)</label>
                        <input
                          type="text"
                          placeholder="e.g. KODE_WEATHER_V1 or SALES_DAILY_API"
                          value={currentConfig.kodeData || ''}
                          onChange={(e) => setCurrentConfig({ ...currentConfig, kodeData: e.target.value })}
                          className="w-full bg-bg-main border border-border-main rounded-xl px-3 py-2 text-xs font-mono text-text-main focus:outline-none focus:border-blue-500"
                        />
                        <p className="text-[11px] text-text-muted mt-1">
                          String unik ini akan disimpan di kolom `kode_data` untuk mempermudah query dan pembedaan dataset.
                        </p>
                      </div>
                    </div>
                  )}

                  {/* TAB 6: SCHEDULE */}
                  {activeReqTab === 'schedule' && (
                    <div className="space-y-4 max-w-md">
                      <div>
                        <label className="block text-xs font-semibold text-text-muted mb-1">Interval Preset</label>
                        <div className="grid grid-cols-2 gap-2">
                          {[
                            { id: 'EVERY_1M', label: 'Every 1 Minute' },
                            { id: 'EVERY_5M', label: 'Every 5 Minutes' },
                            { id: 'EVERY_15M', label: 'Every 15 Minutes' },
                            { id: 'EVERY_1H', label: 'Every 1 Hour' },
                            { id: 'EVERY_1D', label: 'Every 1 Day' },
                            { id: 'CUSTOM', label: 'Custom Cron' },
                          ].map(item => (
                            <button
                              key={item.id}
                              type="button"
                              onClick={() => setCurrentConfig({ ...currentConfig, cronExpression: item.id })}
                              className={clsx(
                                "py-2 px-3 rounded-xl text-xs font-semibold transition-all border text-left",
                                currentConfig.cronExpression === item.id || (item.id === 'CUSTOM' && currentConfig.cronExpression && !currentConfig.cronExpression.startsWith('EVERY_'))
                                  ? "bg-amber-500/20 text-amber-300 border-amber-500/40 shadow-sm"
                                  : "bg-bg-main text-text-muted border-border-main hover:text-text-main"
                              )}
                            >
                              {item.label}
                            </button>
                          ))}
                        </div>
                      </div>

                      {currentConfig.cronExpression && !currentConfig.cronExpression.startsWith('EVERY_') && (
                        <div>
                          <label className="block text-xs font-semibold text-text-muted mb-1">Custom Cron Expression</label>
                          <input
                            type="text"
                            placeholder="0 */5 * * * *"
                            value={currentConfig.cronExpression || ''}
                            onChange={(e) => setCurrentConfig({ ...currentConfig, cronExpression: e.target.value })}
                            className="w-full bg-bg-main border border-border-main rounded-xl px-3 py-2 text-xs font-mono text-text-main focus:outline-none focus:border-blue-500"
                          />
                        </div>
                      )}

                      <div className="flex items-center justify-between bg-bg-main p-3.5 border border-border-main rounded-xl">
                        <div>
                          <span className="block text-xs font-semibold text-text-main">Enable Schedule Immediately</span>
                          <span className="text-[11px] text-text-muted">Automated periodic background execution</span>
                        </div>
                        <input
                          type="checkbox"
                          checked={currentConfig.active !== false}
                          onChange={(e) => setCurrentConfig({ ...currentConfig, active: e.target.checked })}
                          className="w-5 h-5 rounded border-border-main text-blue-500 focus:ring-0 cursor-pointer"
                        />
                      </div>
                    </div>
                  )}
                </div>
              </div>

              {/* Right Column: Insomnia Response Console & Viewer */}
              <div className="lg:col-span-5 flex flex-col min-h-0 bg-bg-main">
                <div className="p-3 border-b border-border-main flex items-center justify-between bg-bg-panel shrink-0">
                  <div className="flex items-center gap-2">
                    <span className="text-xs font-bold uppercase tracking-wider text-text-muted">Response</span>
                    {testResponse && (
                      <span className={clsx(
                        "px-2.5 py-0.5 rounded-full text-xs font-bold",
                        testResponse.statusCode && testResponse.statusCode >= 200 && testResponse.statusCode < 300
                          ? "bg-emerald-500/20 text-emerald-400 border border-emerald-500/30"
                          : "bg-rose-500/20 text-rose-400 border border-rose-500/30"
                      )}>
                        {testResponse.statusCode} OK
                      </span>
                    )}
                  </div>

                  {testResponse && (
                    <div className="flex items-center gap-3 text-xs text-text-muted">
                      <span>{testResponse.durationMs} ms</span>
                      <button
                        onClick={() => {
                          if (testResponse.body) {
                            navigator.clipboard.writeText(getPrettyJson(testResponse.body));
                            setIsCopied(true);
                            setTimeout(() => setIsCopied(false), 2000);
                          }
                        }}
                        className="p-1 rounded hover:bg-bg-hover text-text-muted hover:text-text-main transition-colors"
                        title="Copy JSON Response"
                      >
                        {isCopied ? <Check className="w-4 h-4 text-emerald-400" /> : <Copy className="w-4 h-4" />}
                      </button>
                    </div>
                  )}
                </div>

                {/* Response Tabs Header */}
                <div className="flex items-center gap-1 border-b border-border-main bg-bg-main px-3 pt-1.5 shrink-0">
                  {['body', 'headers'].map(t => (
                    <button
                      key={t}
                      onClick={() => setActiveRespTab(t as any)}
                      className={clsx(
                        "px-3 py-1.5 text-xs font-semibold capitalize rounded-t-lg transition-all",
                        activeRespTab === t ? "bg-bg-panel text-blue-400 border-t border-x border-border-main" : "text-text-muted hover:text-text-main"
                      )}
                    >
                      {t}
                    </button>
                  ))}
                </div>

                {/* Response Content Body */}
                <div className="flex-1 p-3 overflow-auto bg-bg-main font-mono text-xs">
                  {isTesting ? (
                    <div className="h-full flex flex-col items-center justify-center text-text-muted p-6">
                      <Loader2 className="w-7 h-7 animate-spin text-amber-400 mb-2" />
                      <p className="text-xs">Sending HTTP Request to endpoint...</p>
                    </div>
                  ) : !testResponse ? (
                    <div className="h-full flex flex-col items-center justify-center text-text-muted p-6 text-center">
                      <Zap className="w-8 h-8 text-text-muted/40 mb-2" />
                      <p className="text-xs font-medium text-text-muted">Click "Test Endpoint" above to preview API response</p>
                    </div>
                  ) : activeRespTab === 'body' ? (
                    <pre className="text-emerald-400/90 whitespace-pre-wrap break-all selection:bg-blue-500/30">
                      {getPrettyJson(testResponse.body)}
                    </pre>
                  ) : (
                    <div className="space-y-1">
                      {Object.entries(testResponse.headers || {}).map(([k, v]) => (
                        <div key={k} className="flex gap-2">
                          <span className="text-blue-400 font-semibold">{k}:</span>
                          <span className="text-text-muted">{v}</span>
                        </div>
                      ))}
                    </div>
                  )}
                </div>
              </div>

            </div>
          </div>
        </div>
      )}
    </div>
  );
};
