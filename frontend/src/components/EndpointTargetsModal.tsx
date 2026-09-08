// @ts-nocheck
import React, { useState, useEffect, useMemo } from 'react';
import axios from 'axios';
import {
  Radio, X, Plus, Search, Globe, Trash2, Edit3, Check, Copy,
  Play, RefreshCw, Server, ArrowRight, ShieldCheck, AlertCircle, ExternalLink, Activity
} from 'lucide-react';
import clsx from 'clsx';
import { useAppStore } from '../store/useAppStore';

export interface EndpointTarget {
  id?: string;
  name: string;
  url: string;
  method?: string;
  headers?: string;
  groupName?: string;
  description?: string;
  createdAt?: string;
  updatedAt?: string;
}

interface EndpointTargetsModalProps {
  onClose: () => void;
  onSelect?: (target: EndpointTarget) => void;
  selectedUrl?: string;
  selectedId?: string;
}

export const EndpointTargetsModal: React.FC<EndpointTargetsModalProps> = ({ onClose, onSelect, selectedUrl, selectedId }) => {
  const { showAlert } = useAppStore();
  const [endpoints, setEndpoints] = useState<EndpointTarget[]>([]);
  const [loading, setLoading] = useState(false);
  const [searchQuery, setSearchQuery] = useState('');
  const [testingEndpointId, setTestingEndpointId] = useState<string | null>(null);
  
  // Form State
  const [isFormOpen, setIsFormOpen] = useState(false);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [formData, setFormData] = useState<EndpointTarget>({
    name: '',
    url: '',
    method: 'POST',
    headers: '',
    description: ''
  });

  // Testing State
  const [isTesting, setIsTesting] = useState(false);
  const [testResult, setTestResult] = useState<{
    statusCode: number;
    durationMs: number;
    body?: string;
    error?: string;
    success: boolean;
  } | null>(null);

  // Copied State
  const [copiedId, setCopiedId] = useState<string | null>(null);

  useEffect(() => {
    fetchEndpoints();
  }, []);

  const fetchEndpoints = async () => {
    setLoading(true);
    try {
      const res = await axios.get('/api/endpoint-targets');
      if (Array.isArray(res.data)) {
        setEndpoints(res.data);
      }
    } catch (err: any) {
      console.error('Failed to fetch endpoint targets', err);
    } finally {
      setLoading(false);
    }
  };

  const handleOpenCreate = () => {
    setEditingId(null);
    setFormData({
      name: '',
      url: '',
      method: 'POST',
      headers: '{\n  "Content-Type": "application/json"\n}',
      description: ''
    });
    setTestResult(null);
    setIsFormOpen(true);
  };

  const handleOpenEdit = (t: EndpointTarget) => {
    setEditingId(t.id || null);
    setFormData({
      name: t.name,
      url: t.url,
      method: t.method || 'POST',
      headers: t.headers || '',
      description: t.description || ''
    });
    setTestResult(null);
    setIsFormOpen(true);
  };

  const handleSave = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!formData.name.trim() || !formData.url.trim()) {
      showAlert({
        title: 'Validation Error',
        message: 'Endpoint Name and Target Host / URL are required fields.',
        type: 'warning'
      });
      return;
    }

    try {
      if (editingId) {
        await axios.put(`/api/endpoint-targets/${editingId}`, formData);
        showAlert({
          title: 'Endpoint Updated',
          message: `Endpoint "${formData.name}" has been successfully updated.`,
          type: 'success'
        });
      } else {
        await axios.post('/api/endpoint-targets', formData);
        showAlert({
          title: 'Endpoint Created',
          message: `Endpoint "${formData.name}" has been successfully created.`,
          type: 'success'
        });
      }
      setIsFormOpen(false);
      fetchEndpoints();
    } catch (err: any) {
      showAlert({
        title: 'Save Failed',
        message: err.response?.data?.error || err.message || 'Failed to save endpoint target.',
        type: 'error'
      });
    }
  };

  const handleDelete = (id: string, name: string) => {
    showAlert({
      title: 'Delete Endpoint Target',
      message: `Are you sure you want to delete endpoint "${name}"? This action cannot be undone and any job or API referencing it will fail.`,
      type: 'error',
      confirmLabel: 'Delete Endpoint',
      cancelLabel: 'Cancel',
      onConfirm: async () => {
        try {
          await axios.delete(`/api/endpoint-targets/${id}`);
          fetchEndpoints();
          showAlert({
            title: 'Endpoint Deleted',
            message: `Endpoint "${name}" has been deleted.`,
            type: 'success'
          });
        } catch (err: any) {
          showAlert({
            title: 'Delete Failed',
            message: err.response?.data?.error || err.message || 'Failed to delete endpoint target.',
            type: 'error'
          });
        }
      }
    });
  };

  const handleTestConnection = async () => {
    if (!formData.url.trim()) {
      showAlert({
        title: 'Target URL Required',
        message: 'Please enter a Target Host / URL before testing the connection.',
        type: 'warning'
      });
      return;
    }
    setIsTesting(true);
    setTestResult(null);
    try {
      const res = await axios.post('/api/endpoint-targets/test', {
        url: formData.url,
        method: formData.method,
        headers: formData.headers
      });
      setTestResult(res.data);
      if (res.data.success) {
        showAlert({
          title: 'Ping Successful',
          message: `Target endpoint is reachable!\n\nHTTP Status: ${res.data.statusCode}\nResponse Latency: ${res.data.durationMs}ms`,
          type: 'success',
          details: res.data.body ? (typeof res.data.body === 'object' ? JSON.stringify(res.data.body, null, 2) : String(res.data.body)) : undefined
        });
      } else {
        showAlert({
          title: 'Ping Failed',
          message: `Endpoint responded with status ${res.data.statusCode}.\nLatency: ${res.data.durationMs}ms`,
          type: 'error',
          details: res.data.error || (typeof res.data.body === 'object' ? JSON.stringify(res.data.body, null, 2) : String(res.data.body)) || undefined
        });
      }
    } catch (err: any) {
      const errMsg = err.response?.data?.error || err.message || 'Connection failed';
      setTestResult({
        statusCode: 500,
        durationMs: 0,
        error: errMsg,
        success: false
      });
      showAlert({
        title: 'Connection Test Failed',
        message: `Could not connect to target endpoint:\n\n${errMsg}`,
        type: 'error',
        details: err.response?.data ? JSON.stringify(err.response?.data, null, 2) : (err.stack || undefined)
      });
    } finally {
      setIsTesting(false);
    }
  };

  const handleTestPingEndpoint = async (ep: EndpointTarget) => {
    if (!ep.url || !ep.url.trim()) {
      showAlert({
        title: 'Invalid Target URL',
        message: `The endpoint "${ep.name}" does not have a valid URL configured.`,
        type: 'warning'
      });
      return;
    }
    setTestingEndpointId(ep.id || null);
    try {
      const res = await axios.post('/api/endpoint-targets/test', {
        url: ep.url,
        method: ep.method || 'POST',
        headers: ep.headers
      });
      if (res.data.success) {
        showAlert({
          title: 'Ping Successful',
          message: `Target "${ep.name}" is reachable!\n\nHTTP Status: ${res.data.statusCode}\nResponse Latency: ${res.data.durationMs}ms`,
          type: 'success',
          details: res.data.body ? (typeof res.data.body === 'object' ? JSON.stringify(res.data.body, null, 2) : String(res.data.body)) : undefined
        });
      } else {
        showAlert({
          title: 'Ping Failed',
          message: `Target "${ep.name}" responded with error status ${res.data.statusCode}.\nLatency: ${res.data.durationMs}ms`,
          type: 'error',
          details: res.data.error || (typeof res.data.body === 'object' ? JSON.stringify(res.data.body, null, 2) : String(res.data.body)) || undefined
        });
      }
    } catch (err: any) {
      const errMsg = err.response?.data?.error || err.message || 'Connection failed';
      showAlert({
        title: 'Ping Failed',
        message: `Could not connect to "${ep.name}":\n\n${errMsg}`,
        type: 'error',
        details: err.response?.data ? JSON.stringify(err.response?.data, null, 2) : (err.stack || undefined)
      });
    } finally {
      setTestingEndpointId(null);
    }
  };

  const handleCopyUrl = (text: string, id: string) => {
    navigator.clipboard.writeText(text);
    setCopiedId(id);
    setTimeout(() => setCopiedId(null), 2000);
  };

  const filteredEndpoints = useMemo(() => {
    const q = searchQuery.toLowerCase().trim();
    if (!q) return endpoints;
    return endpoints.filter(ep =>
      (ep.name || '').toLowerCase().includes(q) ||
      (ep.url || '').toLowerCase().includes(q) ||
      (ep.description || '').toLowerCase().includes(q) ||
      (ep.method || '').toLowerCase().includes(q)
    );
  }, [endpoints, searchQuery]);

  const getMethodBadgeClass = (m: string | undefined) => {
    const method = (m || 'POST').toUpperCase();
    if (method === 'GET') return 'bg-blue-500/10 text-blue-400 border-blue-500/20';
    if (method === 'POST') return 'bg-emerald-500/10 text-emerald-400 border-emerald-500/20';
    if (method === 'PUT') return 'bg-amber-500/10 text-amber-400 border-amber-500/20';
    if (method === 'PATCH') return 'bg-purple-500/10 text-purple-400 border-purple-500/20';
    if (method === 'DELETE') return 'bg-rose-500/10 text-rose-400 border-rose-500/20';
    return 'bg-gray-500/10 text-gray-400 border-gray-500/20';
  };

  return (
    <div className="fixed inset-0 bg-black/60 backdrop-blur-sm flex items-center justify-center z-[70] p-4 animate-fadeIn">
      <div className="bg-bg-panel border border-border-main rounded-2xl shadow-2xl w-full max-w-4xl max-h-[85vh] overflow-hidden flex flex-col">
        
        {/* Modal Header */}
        <div className="p-4 md:p-5 border-b border-border-main flex justify-between items-center bg-bg-panel shrink-0">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 rounded-xl bg-cyan-500/15 border border-cyan-500/30 flex items-center justify-center text-cyan-400 shadow-sm">
              <Radio className="w-5 h-5" />
            </div>
            <div>
              <h2 className="font-bold text-base md:text-lg text-text-main flex items-center gap-2">
                <span>Endpoint List &amp; Host Targets</span>
                <span className="text-[11px] px-2 py-0.5 rounded-full bg-cyan-500/10 border border-cyan-500/25 text-cyan-400 font-mono font-bold">
                  {endpoints.length}
                </span>
              </h2>
              <p className="text-xs text-text-muted mt-0.5">
                Manage reusable host endpoints (Apache APISIX, internal services, webhooks) for API Builder &amp; Scheduler.
              </p>
            </div>
          </div>
          <button
            onClick={onClose}
            className="p-2 rounded-xl text-text-muted hover:text-text-main hover:bg-bg-hover transition-colors"
            title="Close"
          >
            <X className="w-5 h-5" />
          </button>
        </div>

        {/* Modal Body */}
        <div className="flex-1 overflow-hidden flex flex-col p-4 md:p-6 bg-bg-main">
          
          {isFormOpen ? (
            /* ─────────────────────────────────────────────────────────────
               CREATE / EDIT FORM
               ───────────────────────────────────────────────────────────── */
            <form onSubmit={handleSave} className="flex-1 flex flex-col overflow-y-auto space-y-4">
              <div className="flex items-center justify-between border-b border-border-main pb-3">
                <h3 className="font-bold text-sm text-text-main flex items-center gap-2">
                  <Server className="w-4 h-4 text-cyan-500" />
                  <span>{editingId ? 'Edit Endpoint Target' : 'Create New Endpoint Target'}</span>
                </h3>
                <button
                  type="button"
                  onClick={() => setIsFormOpen(false)}
                  className="text-xs text-text-muted hover:text-text-main underline"
                >
                  Cancel &amp; Back to List
                </button>
              </div>

              <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
                <div className="md:col-span-2">
                  <label className="block text-xs font-bold text-text-main mb-1">
                    Endpoint Name <span className="text-rose-500">*</span>
                  </label>
                  <input
                    type="text"
                    required
                    value={formData.name}
                    onChange={e => setFormData({ ...formData, name: e.target.value })}
                    placeholder="e.g., Apache APISIX Gateway, Internal Sync Service"
                    className="w-full bg-bg-panel border border-border-main rounded-xl px-3.5 py-2 text-xs font-semibold text-text-main focus:outline-none focus:border-cyan-500"
                  />
                </div>

                <div>
                  <label className="block text-xs font-bold text-text-main mb-1">
                    Default HTTP Method
                  </label>
                  <select
                    value={formData.method}
                    onChange={e => setFormData({ ...formData, method: e.target.value })}
                    className="w-full bg-bg-panel border border-border-main rounded-xl px-3 py-2 text-xs font-semibold text-text-main focus:outline-none focus:border-cyan-500 cursor-pointer"
                  >
                    <option value="POST">POST</option>
                    <option value="GET">GET</option>
                    <option value="PUT">PUT</option>
                    <option value="PATCH">PATCH</option>
                    <option value="DELETE">DELETE</option>
                  </select>
                </div>
              </div>

              <div>
                <label className="block text-xs font-bold text-text-main mb-1">
                  Target Host / URL <span className="text-rose-500">*</span>
                </label>
                <div className="flex gap-2">
                  <input
                    type="text"
                    required
                    value={formData.url}
                    onChange={e => setFormData({ ...formData, url: e.target.value })}
                    placeholder="http://apisix.company.com:9080/apisix/admin or https://webhook.site/..."
                    className="flex-1 bg-bg-panel border border-border-main rounded-xl px-3.5 py-2 text-xs font-mono text-text-main focus:outline-none focus:border-cyan-500"
                  />
                  <button
                    type="button"
                    onClick={handleTestConnection}
                    disabled={isTesting}
                    className="px-3.5 py-2 bg-bg-panel hover:bg-bg-hover border border-border-main text-text-main hover:text-cyan-400 rounded-xl text-xs font-bold flex items-center gap-1.5 transition-all shrink-0 cursor-pointer"
                  >
                    {isTesting ? (
                      <RefreshCw className="w-3.5 h-3.5 animate-spin text-cyan-400" />
                    ) : (
                      <Play className="w-3.5 h-3.5 text-cyan-400 fill-cyan-400" />
                    )}
                    <span>Test Ping</span>
                  </button>
                </div>
              </div>

              {/* Test Response Feedback */}
              {testResult && (
                <div className={clsx(
                  "p-3 rounded-xl border text-xs font-mono space-y-1 animate-fadeIn",
                  testResult.success
                    ? "bg-emerald-500/10 border-emerald-500/30 text-emerald-400"
                    : "bg-rose-500/10 border-rose-500/30 text-rose-400"
                )}>
                  <div className="flex items-center justify-between font-bold">
                    <span>HTTP Status: {testResult.statusCode}</span>
                    <span>Latency: {testResult.durationMs}ms</span>
                  </div>
                  {testResult.error && (
                    <div className="text-[11px] text-rose-300 break-all">{testResult.error}</div>
                  )}
                  {testResult.body && (
                    <div className="text-[11px] text-text-muted mt-1 break-all line-clamp-2">
                      Response: {testResult.body}
                    </div>
                  )}
                </div>
              )}

              <div>
                <label className="block text-xs font-bold text-text-main mb-1">
                  Default Headers (Optional JSON)
                </label>
                <textarea
                  rows={4}
                  value={formData.headers}
                  onChange={e => setFormData({ ...formData, headers: e.target.value })}
                  placeholder='{\n  "Authorization": "Bearer YOUR_TOKEN",\n  "X-API-KEY": "secret"\n}'
                  className="w-full bg-bg-panel border border-border-main rounded-xl p-3 text-xs font-mono text-text-main focus:outline-none focus:border-cyan-500"
                />
              </div>

              <div>
                <label className="block text-xs font-bold text-text-main mb-1">
                  Description / Notes
                </label>
                <input
                  type="text"
                  value={formData.description}
                  onChange={e => setFormData({ ...formData, description: e.target.value })}
                  placeholder="e.g., Gateway for syncing billing data to POS external servers"
                  className="w-full bg-bg-panel border border-border-main rounded-xl px-3.5 py-2 text-xs text-text-main focus:outline-none focus:border-cyan-500"
                />
              </div>

              <div className="flex items-center justify-end gap-3 pt-3 border-t border-border-main mt-auto">
                <button
                  type="button"
                  onClick={() => setIsFormOpen(false)}
                  className="px-4 py-2 rounded-xl bg-bg-panel hover:bg-bg-hover text-text-muted hover:text-text-main border border-border-main text-xs font-bold transition-all"
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  className="px-5 py-2 rounded-xl bg-gradient-to-r from-cyan-500 to-blue-600 hover:from-cyan-400 hover:to-blue-500 text-white text-xs font-bold shadow-md shadow-cyan-500/20 transition-all cursor-pointer"
                >
                  {editingId ? 'Save Changes' : 'Create Endpoint'}
                </button>
              </div>
            </form>
          ) : (
            /* ─────────────────────────────────────────────────────────────
               LIST OF SAVED ENDPOINTS
               ───────────────────────────────────────────────────────────── */
            <div className="flex-1 flex flex-col overflow-hidden">
              {/* Controls bar */}
              <div className="flex items-center justify-between gap-3 mb-4 shrink-0">
                <div className="relative flex-1 max-w-md">
                  <Search className="w-4 h-4 text-text-muted absolute left-3 top-2.5" />
                  <input
                    type="text"
                    value={searchQuery}
                    onChange={e => setSearchQuery(e.target.value)}
                    placeholder="Search endpoints by name, URL, method..."
                    className="w-full bg-bg-panel border border-border-main rounded-xl pl-9 pr-3 py-1.5 text-xs text-text-main placeholder:text-text-muted focus:outline-none focus:border-cyan-500"
                  />
                </div>

                <div className="flex items-center gap-2">
                  <button
                    onClick={fetchEndpoints}
                    className="p-2 bg-bg-panel hover:bg-bg-hover border border-border-main text-text-muted hover:text-text-main rounded-xl text-xs transition-all shadow-sm"
                    title="Refresh List"
                  >
                    <RefreshCw className={clsx("w-3.5 h-3.5", loading && "animate-spin")} />
                  </button>

                  <button
                    onClick={handleOpenCreate}
                    className="px-3.5 py-1.5 bg-gradient-to-r from-cyan-500 to-blue-600 hover:from-cyan-400 hover:to-blue-500 text-white rounded-xl text-xs font-bold flex items-center gap-1.5 shadow-md shadow-cyan-500/20 transition-all cursor-pointer"
                  >
                    <Plus className="w-3.5 h-3.5" />
                    <span>Add Endpoint</span>
                  </button>
                </div>
              </div>

              {/* Endpoints Table / Cards */}
              <div className="flex-1 overflow-y-auto space-y-2.5 pr-1">
                {filteredEndpoints.length === 0 ? (
                  <div className="h-48 flex flex-col items-center justify-center text-text-muted border border-dashed border-border-main rounded-2xl p-6 text-center">
                    <Radio className="w-8 h-8 text-text-muted/50 mb-2" />
                    <p className="text-xs font-semibold text-text-main">No endpoints found</p>
                    <p className="text-[11px] text-text-muted mt-0.5 max-w-sm">
                      Click "+ Add Endpoint" to register target URLs (like Apache APISIX or outbound webhooks) that you can select across API Builder and API Scheduler.
                    </p>
                  </div>
                ) : (
                  filteredEndpoints.map(ep => {
                    const isSelected = (selectedUrl && ep.url === selectedUrl) || (selectedId && ep.id === selectedId);
                    return (
                      <div
                        key={ep.id}
                        className={clsx(
                          "p-3.5 rounded-xl transition-all shadow-sm flex flex-col sm:flex-row sm:items-center justify-between gap-3 group border",
                          isSelected
                            ? "bg-cyan-500/10 border-cyan-500 shadow-cyan-500/10 ring-1 ring-cyan-500/30"
                            : "bg-bg-panel border-border-main hover:border-cyan-500/40"
                        )}
                      >
                        <div className="flex-1 min-w-0">
                          <div className="flex items-center gap-2.5 flex-wrap">
                            <span className={clsx("px-2 py-0.5 rounded-md text-[10px] font-mono font-bold border", getMethodBadgeClass(ep.method))}>
                              {ep.method || 'POST'}
                            </span>
                            <span className="text-xs font-bold text-text-main truncate">
                              {ep.name}
                            </span>
                            {isSelected && (
                              <span className="px-2 py-0.5 rounded-full text-[10px] font-bold bg-cyan-500 text-white flex items-center gap-1 shadow-sm">
                                <Check className="w-3 h-3" /> Current Selected
                              </span>
                            )}
                          </div>

                          <div className="flex items-center gap-2 mt-1.5">
                            <span className="text-[11px] font-mono text-cyan-400/90 truncate bg-bg-main px-2 py-0.5 rounded border border-border-main select-all max-w-lg">
                              {ep.url}
                            </span>
                            <button
                              onClick={() => handleCopyUrl(ep.url, ep.id!)}
                              className="p-1 rounded text-text-muted hover:text-text-main hover:bg-bg-hover transition-colors"
                              title="Copy URL"
                            >
                              {copiedId === ep.id ? (
                                <Check className="w-3 h-3 text-emerald-500" />
                              ) : (
                                <Copy className="w-3 h-3" />
                              )}
                            </button>
                          </div>

                          {ep.description && (
                            <p className="text-[11px] text-text-muted mt-1 truncate">
                              {ep.description}
                            </p>
                          )}
                        </div>

                        <div className="flex items-center gap-1.5 shrink-0 self-end sm:self-center">
                          {onSelect && (
                            <button
                              type="button"
                              onClick={() => {
                                onSelect(ep);
                                onClose();
                              }}
                              className={clsx(
                                "px-3 py-1.5 rounded-xl text-xs font-bold transition-all flex items-center gap-1.5 cursor-pointer shadow-sm",
                                isSelected
                                  ? "bg-cyan-500 text-white shadow-cyan-500/20 ring-2 ring-cyan-400"
                                  : "bg-cyan-500/15 hover:bg-cyan-500 text-cyan-400 hover:text-white border border-cyan-500/30"
                              )}
                              title={isSelected ? "Endpoint is currently selected" : "Click to select this endpoint"}
                            >
                              <Check className="w-3.5 h-3.5" />
                              <span>{isSelected ? 'Applied' : 'Select'}</span>
                            </button>
                          )}
                          <button
                            onClick={() => handleTestPingEndpoint(ep)}
                            disabled={testingEndpointId === ep.id}
                            className="px-2 py-1.5 rounded-lg bg-bg-main hover:bg-cyan-500/10 text-text-muted hover:text-cyan-400 border border-border-main transition-colors text-xs flex items-center gap-1.5"
                            title="Test Ping Endpoint"
                          >
                            {testingEndpointId === ep.id ? (
                              <RefreshCw className="w-3.5 h-3.5 animate-spin text-cyan-400" />
                            ) : (
                              <Play className="w-3.5 h-3.5 text-cyan-400 fill-cyan-400/20" />
                            )}
                            <span className="text-[11px] font-semibold text-text-muted group-hover:text-cyan-400">Ping</span>
                          </button>
                          <button
                            onClick={() => handleOpenEdit(ep)}
                            className="p-1.5 rounded-lg bg-bg-main hover:bg-bg-hover text-text-muted hover:text-text-main border border-border-main transition-colors text-xs"
                            title="Edit"
                          >
                            <Edit3 className="w-3.5 h-3.5" />
                          </button>
                          <button
                            onClick={() => handleDelete(ep.id!, ep.name)}
                            className="p-1.5 rounded-lg bg-bg-main hover:bg-rose-500/10 text-text-muted hover:text-rose-400 border border-border-main transition-colors text-xs"
                            title="Delete"
                          >
                            <Trash2 className="w-3.5 h-3.5" />
                          </button>
                        </div>
                      </div>
                    );
                  })
                )}
              </div>
            </div>
          )}

        </div>

      </div>
    </div>
  );
};
