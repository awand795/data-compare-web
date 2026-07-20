import React, { useState, useEffect } from 'react';
import axios from 'axios';
import { useAppStore } from '../store/useAppStore';
import { Webhook, Plus, Save, ArrowLeft, Play, ShieldCheck, ShieldAlert, FileJson } from 'lucide-react';
import { SQLEditor } from './SQLEditor';
import clsx from 'clsx';

interface ApiEndpoint {
  id?: string;
  name: string;
  method: string;
  endpointPath: string;
  connectionId: string;
  sqlQuery: string;
  parameters: string;
  enablePagination: boolean;
  isPublic: boolean;
  authToken: string;
}

export const ApiBuilderView: React.FC = () => {
  const { connections, addToast } = useAppStore();
  
  const [endpoints, setEndpoints] = useState<ApiEndpoint[]>([]);
  const [viewMode, setViewMode] = useState<'list' | 'edit' | 'spec'>('list');
  const [currentApi, setCurrentApi] = useState<ApiEndpoint | null>(null);
  
  const [testResult, setTestResult] = useState<any>(null);
  const [testParams, setTestParams] = useState<Record<string, string>>({});
  const [isTesting, setIsTesting] = useState(false);

  useEffect(() => {
    fetchEndpoints();
  }, []);

  const fetchEndpoints = async () => {
    try {
      const res = await axios.get('/api/api-builder');
      setEndpoints(res.data);
    } catch (err: any) {
      addToast({ type: 'error', title: 'Error', message: 'Failed to fetch APIs' });
    }
  };

  const handleCreateNew = () => {
    setCurrentApi({
      name: 'New API',
      method: 'GET',
      endpointPath: '/new-endpoint',
      connectionId: connections[0]?.id || '',
      sqlQuery: 'SELECT * FROM my_table LIMIT 10',
      parameters: '[]',
      enablePagination: false,
      isPublic: true,
      authToken: ''
    });
    setTestParams({});
    setTestResult(null);
    setViewMode('edit');
  };

  const handleEdit = (api: ApiEndpoint) => {
    setCurrentApi({ ...api });
    setTestParams({});
    setTestResult(null);
    setViewMode('edit');
  };

  const handleViewSpec = (api: ApiEndpoint) => {
    setCurrentApi(api);
    setViewMode('spec');
  };

  const handleSave = async () => {
    if (!currentApi) return;
    try {
      if (currentApi.id) {
        await axios.put(`/api/api-builder/${currentApi.id}`, currentApi);
        addToast({ type: 'success', title: 'Success', message: 'API Updated' });
      } else {
        await axios.post('/api/api-builder', currentApi);
        addToast({ type: 'success', title: 'Success', message: 'API Created' });
      }
      fetchEndpoints();
      setViewMode('list');
    } catch (err: any) {
      addToast({ type: 'error', title: 'Error', message: 'Error saving API: ' + err.message });
    }
  };

  const handleDelete = async (id: string) => {
    if (!confirm('Are you sure you want to delete this API?')) return;
    try {
      await axios.delete(`/api/api-builder/${id}`);
      addToast({ type: 'success', title: 'Success', message: 'API Deleted' });
      fetchEndpoints();
    } catch (err: any) {
      addToast({ type: 'error', title: 'Error', message: 'Error deleting API' });
    }
  };

  const detectParams = (sql: string) => {
    const matches = sql.match(/:\w+/g);
    if (!matches) return [];
    return Array.from(new Set(matches.map(m => m.substring(1))));
  };

  const handleTest = async () => {
    if (!currentApi) return;
    setIsTesting(true);
    setTestResult(null);
    
    // We simulate hitting the real dynamic endpoint. We need to construct the URL.
    const url = `/api/data${currentApi.endpointPath}`;
    
    try {
      const headers: Record<string, string> = {};
      if (!currentApi.isPublic && currentApi.authToken) {
        headers['Authorization'] = `Bearer ${currentApi.authToken}`;
      }

      let res;
      if (currentApi.method === 'GET') {
        const queryParams = new URLSearchParams(testParams).toString();
        res = await axios.get(`${url}?${queryParams}`, { headers });
      } else {
        res = await axios.post(url, testParams, { headers });
      }
      setTestResult({ status: res.status, data: res.data });
    } catch (err: any) {
      setTestResult({ 
        status: err.response?.status || 500, 
        error: err.response?.data?.error || err.message 
      });
    } finally {
      setIsTesting(false);
    }
  };

  if (viewMode === 'list') {
    return (
      <div className="h-full flex flex-col p-6 overflow-y-auto">
        <div className="flex justify-between items-center mb-6">
          <div>
            <h1 className="text-2xl font-bold text-text-main flex items-center gap-2">
              <Webhook className="w-7 h-7 text-blue-500" /> API Builder
            </h1>
            <p className="text-text-muted mt-1">Turn SQL queries into production-ready JSON APIs instantly.</p>
          </div>
          <button 
            onClick={handleCreateNew}
            className="px-4 py-2 bg-blue-500 hover:bg-blue-600 text-white rounded flex items-center gap-2 transition-colors"
          >
            <Plus className="w-4 h-4" /> Create API
          </button>
        </div>

        <div className="bg-bg-panel border border-border-main rounded shadow-sm overflow-hidden">
          <table className="w-full text-left text-sm">
            <thead className="bg-bg-editor text-text-muted font-medium border-b border-border-main">
              <tr>
                <th className="p-3">Name</th>
                <th className="p-3">Method</th>
                <th className="p-3">Endpoint</th>
                <th className="p-3">Security</th>
                <th className="p-3">Actions</th>
              </tr>
            </thead>
            <tbody>
              {endpoints.length === 0 ? (
                <tr>
                  <td colSpan={5} className="p-8 text-center text-text-muted">
                    No APIs created yet. Click "Create API" to get started.
                  </td>
                </tr>
              ) : (
                endpoints.map(api => (
                  <tr key={api.id} className="border-b border-border-main hover:bg-bg-hover">
                    <td className="p-3 font-medium">{api.name}</td>
                    <td className="p-3">
                      <span className={clsx("px-2 py-1 rounded text-xs font-bold", 
                        api.method === 'GET' ? "bg-green-500/20 text-green-500" : "bg-blue-500/20 text-blue-500"
                      )}>
                        {api.method}
                      </span>
                    </td>
                    <td className="p-3 font-mono text-xs text-text-muted">
                      /api/data{api.endpointPath}
                    </td>
                    <td className="p-3">
                      {api.isPublic ? (
                        <span className="flex items-center gap-1 text-green-500 text-xs"><ShieldCheck className="w-4 h-4"/> Public</span>
                      ) : (
                        <span className="flex items-center gap-1 text-orange-500 text-xs"><ShieldAlert className="w-4 h-4"/> Protected</span>
                      )}
                    </td>
                    <td className="p-3 flex items-center gap-2">
                      <button onClick={() => handleEdit(api)} className="text-blue-500 hover:underline">Edit</button>
                      <button onClick={() => handleViewSpec(api)} className="text-purple-500 hover:underline flex items-center gap-1"><FileJson className="w-3 h-3"/> Spec</button>
                      <button onClick={() => api.id && handleDelete(api.id)} className="text-red-500 hover:underline ml-2">Delete</button>
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>
      </div>
    );
  }

  if (viewMode === 'spec' && currentApi) {
    const fullUrl = `${window.location.origin}/api/data${currentApi.endpointPath}`;
    const detectedParams = detectParams(currentApi.sqlQuery);
    
    return (
      <div className="h-full flex flex-col p-6 overflow-y-auto">
        <div className="flex items-center gap-4 mb-6">
          <button onClick={() => setViewMode('list')} className="p-2 hover:bg-bg-hover rounded text-text-muted">
            <ArrowLeft className="w-5 h-5" />
          </button>
          <h1 className="text-2xl font-bold text-text-main flex items-center gap-2">
            API Specification: {currentApi.name}
          </h1>
        </div>
        
        <div className="bg-bg-panel border border-border-main p-6 rounded shadow-sm max-w-4xl space-y-6">
          <div>
            <h3 className="text-sm font-semibold text-text-muted mb-2">Endpoint URL</h3>
            <div className="flex items-center gap-3">
              <span className={clsx("px-2 py-1 rounded text-sm font-bold", 
                  currentApi.method === 'GET' ? "bg-green-500/20 text-green-500" : "bg-blue-500/20 text-blue-500"
                )}>
                  {currentApi.method}
              </span>
              <code className="bg-bg-editor px-3 py-1.5 rounded text-blue-400 font-mono flex-1">
                {fullUrl}
              </code>
            </div>
          </div>
          
          <div>
            <h3 className="text-sm font-semibold text-text-muted mb-2">Authentication</h3>
            {currentApi.isPublic ? (
              <p className="text-sm text-green-500 flex items-center gap-2"><ShieldCheck className="w-4 h-4"/> This API is public. No authentication required.</p>
            ) : (
              <div className="bg-orange-500/10 border border-orange-500/20 p-3 rounded">
                <p className="text-sm text-orange-400 mb-2 flex items-center gap-2"><ShieldAlert className="w-4 h-4"/> Protected API. Include the following header in your requests:</p>
                <code className="text-xs bg-bg-editor p-2 rounded block text-orange-300">
                  Authorization: Bearer {currentApi.authToken}
                </code>
              </div>
            )}
          </div>

          <div>
            <h3 className="text-sm font-semibold text-text-muted mb-2">Parameters</h3>
            {detectedParams.length === 0 && !currentApi.enablePagination ? (
              <p className="text-sm text-text-muted">No parameters required.</p>
            ) : (
              <table className="w-full text-left text-sm border border-border-main">
                <thead className="bg-bg-editor">
                  <tr>
                    <th className="p-2 border-b border-border-main">Parameter</th>
                    <th className="p-2 border-b border-border-main">Type</th>
                    <th className="p-2 border-b border-border-main">Description</th>
                  </tr>
                </thead>
                <tbody>
                  {detectedParams.map(p => (
                    <tr key={p} className="border-b border-border-main">
                      <td className="p-2 font-mono text-blue-400">{p}</td>
                      <td className="p-2 text-text-muted">Any</td>
                      <td className="p-2 text-text-muted">Extracted from SQL <code>:{p}</code></td>
                    </tr>
                  ))}
                  {currentApi.enablePagination && (
                    <>
                      <tr className="border-b border-border-main">
                        <td className="p-2 font-mono text-blue-400">limit / size</td>
                        <td className="p-2 text-text-muted">Integer</td>
                        <td className="p-2 text-text-muted">Number of records to return (Pagination)</td>
                      </tr>
                      <tr className="border-b border-border-main">
                        <td className="p-2 font-mono text-blue-400">offset / page</td>
                        <td className="p-2 text-text-muted">Integer</td>
                        <td className="p-2 text-text-muted">Offset or Page number (Pagination)</td>
                      </tr>
                    </>
                  )}
                </tbody>
              </table>
            )}
            {currentApi.method === 'POST' && (
              <p className="text-xs text-text-muted mt-2">
                * Note: For POST requests, send parameters as a JSON object in the Request Body.
              </p>
            )}
          </div>
        </div>
      </div>
    );
  }

  if (viewMode === 'edit' && currentApi) {
    const paramsList = detectParams(currentApi.sqlQuery);
    
    return (
      <div className="h-full flex flex-col overflow-hidden">
        <div className="bg-bg-panel border-b border-border-main p-4 flex items-center justify-between shrink-0">
          <div className="flex items-center gap-3">
            <button onClick={() => setViewMode('list')} className="p-2 hover:bg-bg-hover rounded text-text-muted">
              <ArrowLeft className="w-5 h-5" />
            </button>
            <h2 className="text-xl font-bold text-text-main">{currentApi.id ? 'Edit API' : 'Create New API'}</h2>
          </div>
          <button 
            onClick={handleSave}
            className="px-4 py-2 bg-blue-500 hover:bg-blue-600 text-white rounded flex items-center gap-2"
          >
            <Save className="w-4 h-4" /> Save API
          </button>
        </div>

        <div className="flex-1 overflow-y-auto p-6 grid grid-cols-1 lg:grid-cols-2 gap-6">
          {/* LEFT: Config Form */}
          <div className="space-y-6">
            <div className="bg-bg-panel border border-border-main p-4 rounded shadow-sm space-y-4">
              <h3 className="font-semibold text-text-main border-b border-border-main pb-2">Basic Info</h3>
              
              <div>
                <label className="block text-sm text-text-muted mb-1">API Name</label>
                <input 
                  className="w-full bg-bg-editor border border-border-main rounded p-2 text-sm focus:border-blue-500 outline-none"
                  value={currentApi.name}
                  onChange={e => setCurrentApi({...currentApi, name: e.target.value})}
                />
              </div>

              <div className="flex gap-4">
                <div className="w-1/3">
                  <label className="block text-sm text-text-muted mb-1">Method</label>
                  <select 
                    className="w-full bg-bg-editor border border-border-main rounded p-2 text-sm focus:border-blue-500 outline-none"
                    value={currentApi.method}
                    onChange={e => setCurrentApi({...currentApi, method: e.target.value})}
                  >
                    <option value="GET">GET</option>
                    <option value="POST">POST</option>
                  </select>
                </div>
                <div className="flex-1">
                  <label className="block text-sm text-text-muted mb-1">Endpoint Path</label>
                  <div className="flex items-center">
                    <span className="bg-bg-editor border border-r-0 border-border-main rounded-l p-2 text-sm text-text-muted whitespace-nowrap">
                      /api/data
                    </span>
                    <input 
                      className="w-full bg-bg-editor border border-border-main rounded-r p-2 text-sm focus:border-blue-500 outline-none"
                      value={currentApi.endpointPath}
                      onChange={e => {
                        let val = e.target.value;
                        if (!val.startsWith('/')) val = '/' + val;
                        setCurrentApi({...currentApi, endpointPath: val});
                      }}
                      placeholder="/my-endpoint"
                    />
                  </div>
                </div>
              </div>

              <div>
                <label className="block text-sm text-text-muted mb-1">Data Source</label>
                <select 
                  className="w-full bg-bg-editor border border-border-main rounded p-2 text-sm focus:border-blue-500 outline-none"
                  value={currentApi.connectionId}
                  onChange={e => setCurrentApi({...currentApi, connectionId: e.target.value})}
                >
                  <option value="" disabled>Select a connection...</option>
                  {connections.map(c => (
                    <option key={c.id} value={c.id}>{c.name} ({c.type})</option>
                  ))}
                </select>
              </div>
            </div>

            <div className="bg-bg-panel border border-border-main p-4 rounded shadow-sm space-y-4">
              <h3 className="font-semibold text-text-main border-b border-border-main pb-2">Security & Pagination</h3>
              
              <label className="flex items-center gap-2 cursor-pointer">
                <input type="checkbox" checked={currentApi.isPublic} onChange={e => setCurrentApi({...currentApi, isPublic: e.target.checked})} />
                <span className="text-sm">Public API (No authentication required)</span>
              </label>

              {!currentApi.isPublic && (
                <div>
                  <label className="block text-sm text-text-muted mb-1">API Token (Bearer)</label>
                  <input 
                    type="text"
                    className="w-full bg-bg-editor border border-border-main rounded p-2 text-sm focus:border-blue-500 outline-none"
                    value={currentApi.authToken}
                    onChange={e => setCurrentApi({...currentApi, authToken: e.target.value})}
                    placeholder="e.g. secret-token-123"
                  />
                </div>
              )}

              <label className="flex items-center gap-2 cursor-pointer mt-4">
                <input type="checkbox" checked={currentApi.enablePagination} onChange={e => setCurrentApi({...currentApi, enablePagination: e.target.checked})} />
                <span className="text-sm">Enable Pagination (Auto append LIMIT/OFFSET based on request params)</span>
              </label>
            </div>
          </div>

          {/* RIGHT: SQL & Test */}
          <div className="space-y-6 flex flex-col">
            <div className="bg-bg-panel border border-border-main rounded shadow-sm flex flex-col" style={{ minHeight: '300px' }}>
              <div className="p-3 border-b border-border-main font-semibold text-sm">
                SQL Query
              </div>
              <div className="flex-1 min-h-0 relative">
                <SQLEditor 
                  value={currentApi.sqlQuery}
                  onChange={val => setCurrentApi({...currentApi, sqlQuery: val})}
                  connectionId={currentApi.connectionId}
                />
              </div>
            </div>

            <div className="bg-bg-panel border border-border-main p-4 rounded shadow-sm flex-1 flex flex-col">
              <div className="flex items-center justify-between mb-4 border-b border-border-main pb-2">
                <h3 className="font-semibold text-text-main">Test Endpoint</h3>
                <button 
                  onClick={handleTest}
                  disabled={isTesting}
                  className="px-3 py-1.5 bg-green-500 hover:bg-green-600 disabled:opacity-50 text-white rounded text-sm flex items-center gap-2"
                >
                  <Play className="w-3 h-3" /> {isTesting ? 'Testing...' : 'Test'}
                </button>
              </div>
              
              {paramsList.length > 0 && (
                <div className="mb-4">
                  <h4 className="text-xs font-semibold text-text-muted uppercase mb-2">Parameters</h4>
                  <div className="grid gap-2">
                    {paramsList.map(p => (
                      <div key={p} className="flex items-center gap-2">
                        <span className="w-24 text-xs font-mono text-blue-400">:{p}</span>
                        <input 
                          type="text"
                          className="flex-1 bg-bg-editor border border-border-main rounded px-2 py-1 text-xs outline-none focus:border-blue-500"
                          placeholder="Value..."
                          value={testParams[p] || ''}
                          onChange={e => setTestParams({...testParams, [p]: e.target.value})}
                        />
                      </div>
                    ))}
                  </div>
                </div>
              )}

              <h4 className="text-xs font-semibold text-text-muted uppercase mb-2">Response</h4>
              <div className="flex-1 bg-bg-editor border border-border-main rounded p-2 overflow-auto font-mono text-xs">
                {testResult ? (
                  <div>
                    <div className={clsx("mb-2 font-bold", testResult.status >= 400 ? "text-red-400" : "text-green-400")}>
                      Status: {testResult.status}
                    </div>
                    <pre className="text-text-main whitespace-pre-wrap">
                      {JSON.stringify(testResult.data || testResult.error, null, 2)}
                    </pre>
                  </div>
                ) : (
                  <div className="text-text-muted text-center mt-4">Click "Test" to see response</div>
                )}
              </div>
            </div>
          </div>
        </div>
      </div>
    );
  }

  return null;
};
