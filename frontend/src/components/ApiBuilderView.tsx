import React, { useState, useEffect } from 'react';
import axios from 'axios';
import { useAppStore } from '../store/useAppStore';
import { Webhook, Plus, Save, ArrowLeft, Play, ShieldCheck, ShieldAlert, FileJson, Pencil, Trash2, Copy, Check, Share2, Activity, Database, Server, Settings2 } from 'lucide-react';
import { SQLEditor } from './SQLEditor';
import clsx from 'clsx';

interface ApiParameter {
  name: string;
  type: 'string' | 'integer' | 'number' | 'boolean' | 'date';
  required: boolean;
  defaultValue: string;
  description: string;
}

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

const getMethodBadgeClass = (method: string) => {
  switch (method) {
    case 'GET': return 'bg-green-500/20 text-green-500 border border-green-500/20';
    case 'POST': return 'bg-blue-500/20 text-blue-500 border border-blue-500/20';
    case 'PUT': return 'bg-amber-500/20 text-amber-500 border border-amber-500/20';
    case 'PATCH': return 'bg-purple-500/20 text-purple-500 border border-purple-500/20';
    case 'DELETE': return 'bg-red-500/20 text-red-500 border border-red-500/20';
    default: return 'bg-gray-500/20 text-gray-500 border border-gray-500/20';
  }
};

export const ApiBuilderView: React.FC = () => {
  const { connections, addToast, showAlert } = useAppStore();
  
  const [endpoints, setEndpoints] = useState<ApiEndpoint[]>([]);
  const [viewMode, setViewMode] = useState<'list' | 'edit' | 'spec'>('list');
  const [currentApi, setCurrentApi] = useState<ApiEndpoint | null>(null);
  const [parameterMeta, setParameterMeta] = useState<ApiParameter[]>([]);
  const [isLoadingList, setIsLoadingList] = useState(true);
  
  const [testResult, setTestResult] = useState<any>(null);
  const [testParams, setTestParams] = useState<Record<string, string>>({});
  const [isTesting, setIsTesting] = useState(false);
  const [copiedStates, setCopiedStates] = useState<Record<string, boolean>>({});
  const [isTestConsoleOpen, setIsTestConsoleOpen] = useState(true);
  const [paramCount, setParamCount] = useState(0);

  useEffect(() => {
    fetchEndpoints();
  }, []);

  const fetchEndpoints = async () => {
    setIsLoadingList(true);
    try {
      const res = await axios.get('/api/api-builder');
      setEndpoints(res.data);
    } catch (err: any) {
      addToast({ type: 'error', title: 'Error', message: 'Failed to fetch APIs' });
    } finally {
      setIsLoadingList(false);
    }
  };

  const handleCreateNew = () => {
    setCurrentApi({
      name: 'New API',
      method: 'GET',
      endpointPath: '/new-endpoint',
      connectionId: connections[0]?.id || '',
      sqlQuery: 'SELECT * FROM my_table \nWHERE id = :id OR status = :status \nLIMIT 10',
      parameters: '[]',
      enablePagination: false,
      isPublic: true,
      authToken: ''
    });
    setTestParams({});
    setTestResult(null);
    setParameterMeta([]);
    setViewMode('edit');
  };

  const handleEdit = (api: ApiEndpoint) => {
    setCurrentApi({ ...api });
    setTestParams({});
    setTestResult(null);
    let parsed: ApiParameter[] = [];
    try {
      if (api.parameters && api.parameters !== '[]') {
        parsed = JSON.parse(api.parameters);
      }
    } catch(e) {}
    setParameterMeta(parsed);
    setViewMode('edit');
  };

  const handleViewSpec = (api: ApiEndpoint) => {
    setCurrentApi(api);
    setViewMode('spec');
  };

  const handleSave = async () => {
    if (!currentApi) return;
    try {
      const apiToSave = { ...currentApi, parameters: JSON.stringify(parameterMeta) };
      if (apiToSave.id) {
        await axios.put(`/api/api-builder/${apiToSave.id}`, apiToSave);
        addToast({ type: 'success', title: 'Success', message: 'API Updated' });
      } else {
        await axios.post('/api/api-builder', apiToSave);
        addToast({ type: 'success', title: 'Success', message: 'API Created' });
      }
      fetchEndpoints();
      setViewMode('list');
    } catch (err: any) {
      addToast({ type: 'error', title: 'Error', message: 'Error saving API: ' + err.message });
    }
  };

  const handleDeleteClick = (api: ApiEndpoint) => {
    if (!api.id) return;
    showAlert({
      title: 'Delete API?',
      message: `Are you sure you want to delete "${api.name}"? This action cannot be undone.`,
      type: 'error',
      confirmLabel: 'Delete',
      onConfirm: () => handleDelete(api.id!)
    });
  };

  const handleDelete = async (id: string) => {
    try {
      await axios.delete(`/api/api-builder/${id}`);
      addToast({ type: 'success', title: 'Success', message: 'API Deleted' });
      fetchEndpoints();
    } catch (err: any) {
      addToast({ type: 'error', title: 'Error', message: 'Error deleting API' });
    }
  };

  const detectParams = (sql: string) => {
    const matches = sql.match(/(?<!:):(\w+)/g);
    if (!matches) return [];
    return Array.from(new Set(matches.map(m => m.substring(1))));
  };

  useEffect(() => {
    if (viewMode === 'edit' && currentApi) {
      const detected = detectParams(currentApi.sqlQuery);
      setParamCount(detected.length);
      setParameterMeta(prev => {
        const next = detected.map(name => {
          const existing = prev.find(p => p.name === name);
          if (existing) return existing;
          return {
            name,
            type: 'string',
            required: true,
            defaultValue: '',
            description: ''
          };
        });
        const isSame = prev.length === next.length && next.every((n, i) => n === prev[i]);
        return isSame ? prev : next;
      });
    }
  }, [currentApi?.sqlQuery, viewMode]);

  const handleTest = async () => {
    if (!currentApi) return;
    
    setIsTesting(true);
    setTestResult(null);
    
    try {
      const res = await axios.post('/api/api-builder/test-query', {
        api: currentApi,
        params: testParams
      });
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

  const handleCopy = (text: string, id: string) => {
    navigator.clipboard.writeText(text);
    setCopiedStates({ ...copiedStates, [id]: true });
    setTimeout(() => {
      setCopiedStates(prev => ({ ...prev, [id]: false }));
    }, 2000);
    addToast({ type: 'success', title: 'Copied', message: 'Copied to clipboard!' });
  };

  const handleShare = async () => {
    if (!currentApi?.id) return;
    try {
      const res = await axios.post(`/api/api-builder/${currentApi.id}/share`);
      const { token, shareUrl } = res.data;
      const fullUrl = `${window.location.origin}${shareUrl}`;
      handleCopy(fullUrl, 'share-link');
      addToast({ type: 'success', title: 'Share Link Generated', message: 'One-time link copied to clipboard!' });
    } catch (err: any) {
      addToast({ type: 'error', title: 'Error', message: 'Failed to generate share link.' });
    }
  };

  if (viewMode === 'list') {
    return (
      <div className="h-full flex flex-col p-6 overflow-y-auto">
        <div className="flex justify-between items-center mb-8">
          <div>
            <h1 className="text-3xl font-extrabold text-text-main flex items-center gap-3">
              <div className="bg-blue-500/10 p-2 rounded-xl">
                <Webhook className="w-8 h-8 text-blue-500" />
              </div>
              API Builder
            </h1>
            <p className="text-text-muted mt-2 text-sm max-w-xl leading-relaxed">
              Design, test, and deploy database-backed JSON APIs instantly. Turn complex queries into production-ready endpoints.
            </p>
          </div>
          <button 
            onClick={handleCreateNew}
            className="px-5 py-2.5 bg-blue-600 hover:bg-blue-500 text-white rounded-lg flex items-center gap-2 font-medium transition-all shadow-lg shadow-blue-500/20"
          >
            <Plus className="w-5 h-5" /> Create API
          </button>
        </div>

        <div className="bg-bg-panel border border-border-main rounded-xl shadow-xl overflow-hidden">
          <table className="w-full text-left text-sm">
            <thead className="bg-bg-editor/50 text-text-muted font-semibold border-b border-border-main">
              <tr>
                <th className="p-4 uppercase tracking-wider text-xs">Name</th>
                <th className="p-4 uppercase tracking-wider text-xs">Method & Endpoint</th>
                <th className="p-4 uppercase tracking-wider text-xs">Security</th>
                <th className="p-4 text-right uppercase tracking-wider text-xs">Actions</th>
              </tr>
            </thead>
            <tbody>
              {isLoadingList ? (
                <tr>
                  <td colSpan={4} className="p-12 text-center text-text-muted">
                    <div className="flex flex-col items-center justify-center gap-3">
                      <div className="w-6 h-6 border-2 border-blue-500 border-t-transparent rounded-full animate-spin"></div>
                      <span className="animate-pulse">Loading APIs...</span>
                    </div>
                  </td>
                </tr>
              ) : endpoints.length === 0 ? (
                <tr>
                  <td colSpan={4} className="p-16 text-center text-text-muted">
                    <div className="flex flex-col items-center justify-center gap-4">
                      <div className="w-16 h-16 bg-bg-editor rounded-full flex items-center justify-center border border-border-main">
                        <Server className="w-8 h-8 text-text-muted/50" />
                      </div>
                      <div>
                        <p className="text-lg font-medium text-text-main">No APIs created yet</p>
                        <p className="text-sm">Click "Create API" to design your first endpoint.</p>
                      </div>
                    </div>
                  </td>
                </tr>
              ) : (
                endpoints.map(api => (
                  <tr key={api.id} className="border-b border-border-main hover:bg-bg-hover/50 transition-colors group">
                    <td className="p-4 font-semibold text-text-main">{api.name}</td>
                    <td className="p-4">
                      <div className="flex items-center gap-3">
                        <span className={clsx("px-2.5 py-1 rounded-md text-[10px] font-black tracking-widest", 
                          getMethodBadgeClass(api.method)
                        )}>
                          {api.method}
                        </span>
                        <code className="font-mono text-xs text-text-muted group-hover:text-blue-400 transition-colors">
                          /api/data{api.endpointPath}
                        </code>
                      </div>
                    </td>
                    <td className="p-4">
                      {api.isPublic ? (
                        <span className="inline-flex items-center gap-1.5 text-green-500 text-xs font-medium bg-green-500/10 px-2 py-1 rounded-full"><ShieldCheck className="w-3.5 h-3.5"/> Public</span>
                      ) : (
                        <span className="inline-flex items-center gap-1.5 text-orange-500 text-xs font-medium bg-orange-500/10 px-2 py-1 rounded-full"><ShieldAlert className="w-3.5 h-3.5"/> Protected</span>
                      )}
                    </td>
                    <td className="p-4 flex items-center justify-end gap-2">
                      <button onClick={() => handleViewSpec(api)} className="p-2 text-text-muted hover:text-purple-400 hover:bg-purple-500/10 rounded-lg transition-colors tooltip" title="View Spec">
                        <FileJson className="w-4 h-4"/>
                      </button>
                      <button onClick={() => handleEdit(api)} className="p-2 text-text-muted hover:text-blue-400 hover:bg-blue-500/10 rounded-lg transition-colors tooltip" title="Edit API">
                        <Pencil className="w-4 h-4"/>
                      </button>
                      <button onClick={() => handleDeleteClick(api)} className="p-2 text-text-muted hover:text-red-400 hover:bg-red-500/10 rounded-lg transition-colors tooltip" title="Delete API">
                        <Trash2 className="w-4 h-4"/>
                      </button>
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
    let parsedParams: ApiParameter[] = [];
    try {
      parsedParams = JSON.parse(currentApi.parameters || '[]');
    } catch(e) {}
    
    const curlExample = `curl -X ${currentApi.method} "${fullUrl}${detectedParams.length > 0 && currentApi.method === 'GET' ? '?' + detectedParams.map(p => \`\${p}=value\`).join('&') : ''}" \\
  -H "Accept: application/json" ${!currentApi.isPublic ? \`\\
  -H "Authorization: Bearer \${currentApi.authToken}"\` : ''}${currentApi.method !== 'GET' && detectedParams.length > 0 ? \` \\
  -H "Content-Type: application/json" \\
  -d '{\n${detectedParams.map(p => \`    "\${p}": "value"\`).join(',\n')}\n  }'\` : ''}`;
    
    return (
      <div className="h-full flex flex-col p-6 overflow-y-auto">
        <div className="flex items-center justify-between mb-8 max-w-5xl mx-auto w-full">
          <div className="flex items-center gap-4">
            <button onClick={() => setViewMode('list')} className="p-2.5 hover:bg-bg-hover bg-bg-panel border border-border-main rounded-xl text-text-muted shadow-sm transition-all hover:scale-105">
              <ArrowLeft className="w-5 h-5" />
            </button>
            <div>
              <h1 className="text-3xl font-extrabold text-text-main flex items-center gap-3">
                API Specification
              </h1>
              <p className="text-text-muted text-sm mt-1 font-medium">{currentApi.name}</p>
            </div>
          </div>
          <button 
            onClick={handleShare}
            className="px-4 py-2 bg-gradient-to-r from-purple-500 to-blue-500 hover:from-purple-600 hover:to-blue-600 text-white rounded-lg flex items-center gap-2 font-medium shadow-lg shadow-purple-500/20 transition-all hover:scale-105"
          >
            {copiedStates['share-link'] ? <Check className="w-4 h-4" /> : <Share2 className="w-4 h-4" />}
            Share One-Time Link
          </button>
        </div>
        
        <div className="max-w-5xl mx-auto w-full space-y-6">
          <div className="bg-bg-panel border border-border-main p-8 rounded-2xl shadow-xl space-y-8 relative overflow-hidden">
            <div className="absolute top-0 left-0 w-full h-1 bg-gradient-to-r from-blue-500 via-purple-500 to-green-500 opacity-50"></div>
            
            {/* URL Section */}
            <div>
              <h3 className="text-sm font-bold text-text-muted uppercase tracking-wider mb-3 flex items-center gap-2">
                <Webhook className="w-4 h-4" /> Endpoint URL
              </h3>
              <div className="flex items-center gap-0">
                <span className={clsx("px-4 py-3 rounded-l-lg text-sm font-black tracking-wider shadow-inner", 
                    getMethodBadgeClass(currentApi.method)
                  )}>
                    {currentApi.method}
                </span>
                <code className="bg-bg-editor px-4 py-3 border-y border-border-main text-text-main font-mono flex-1 text-sm overflow-x-auto whitespace-nowrap shadow-inner">
                  {fullUrl}
                </code>
                <button 
                  onClick={() => handleCopy(fullUrl, 'url')}
                  className="bg-bg-hover hover:bg-blue-500/10 hover:text-blue-400 border-y border-r border-border-main px-4 py-3 rounded-r-lg text-text-muted transition-colors flex items-center justify-center shadow-inner"
                  title="Copy URL"
                >
                  {copiedStates['url'] ? <Check className="w-4 h-4 text-green-500" /> : <Copy className="w-4 h-4" />}
                </button>
              </div>
            </div>
            
            {/* Auth Section */}
            <div>
              <h3 className="text-sm font-bold text-text-muted uppercase tracking-wider mb-3 flex items-center gap-2">
                <ShieldCheck className="w-4 h-4" /> Authentication
              </h3>
              {currentApi.isPublic ? (
                <div className="bg-green-500/10 border border-green-500/20 p-4 rounded-xl flex items-center gap-3">
                  <ShieldCheck className="w-6 h-6 text-green-500" />
                  <div>
                    <p className="text-sm font-bold text-green-500">Public Access</p>
                    <p className="text-xs text-green-500/80 mt-0.5">This API can be accessed without any authorization headers.</p>
                  </div>
                </div>
              ) : (
                <div className="bg-orange-500/10 border border-orange-500/20 p-4 rounded-xl">
                  <div className="flex items-center gap-3 mb-3">
                    <ShieldAlert className="w-6 h-6 text-orange-500" />
                    <div>
                      <p className="text-sm font-bold text-orange-500">Protected Endpoint</p>
                      <p className="text-xs text-orange-500/80 mt-0.5">Include this header in your HTTP requests.</p>
                    </div>
                  </div>
                  <div className="flex items-center gap-0">
                    <code className="flex-1 bg-bg-editor/50 border border-orange-500/20 px-4 py-2.5 rounded-l-lg text-orange-300 font-mono text-sm overflow-x-auto shadow-inner">
                      Authorization: Bearer {currentApi.authToken}
                    </code>
                    <button 
                      onClick={() => handleCopy(currentApi.authToken, 'token')}
                      className="bg-orange-500/20 hover:bg-orange-500/30 border-y border-r border-orange-500/20 px-4 py-2.5 rounded-r-lg text-orange-400 transition-colors flex items-center justify-center shadow-inner"
                      title="Copy Token"
                    >
                      {copiedStates['token'] ? <Check className="w-4 h-4 text-green-500" /> : <Copy className="w-4 h-4" />}
                    </button>
                  </div>
                </div>
              )}
            </div>

            {/* Parameters Section */}
            <div>
              <h3 className="text-sm font-bold text-text-muted uppercase tracking-wider mb-3 flex items-center gap-2">
                <Settings2 className="w-4 h-4" /> Parameters
              </h3>
              {detectedParams.length === 0 && !currentApi.enablePagination ? (
                <div className="bg-bg-editor/50 border border-border-main p-8 rounded-xl text-center">
                  <p className="text-sm text-text-muted">No parameters required for this endpoint.</p>
                </div>
              ) : (
                <div className="border border-border-main rounded-xl overflow-hidden shadow-sm">
                  <table className="w-full text-left text-sm">
                    <thead className="bg-bg-editor/80">
                      <tr>
                        <th className="p-3 border-b border-border-main font-semibold text-text-main text-xs uppercase tracking-wider">Name</th>
                        <th className="p-3 border-b border-border-main font-semibold text-text-main text-xs uppercase tracking-wider">Type</th>
                        <th className="p-3 border-b border-border-main font-semibold text-text-main text-xs uppercase tracking-wider">Required</th>
                        <th className="p-3 border-b border-border-main font-semibold text-text-main text-xs uppercase tracking-wider">Default</th>
                        <th className="p-3 border-b border-border-main font-semibold text-text-main text-xs uppercase tracking-wider">Description</th>
                      </tr>
                    </thead>
                    <tbody>
                      {detectedParams.map(p => {
                        const meta = parsedParams.find(m => m.name === p) || {
                          name: p, type: 'string', required: true, defaultValue: '', description: `Extracted from SQL :${p}`
                        };
                        return (
                          <tr key={p} className="border-b border-border-main last:border-0 bg-bg-panel hover:bg-bg-hover/30 transition-colors">
                            <td className="p-3">
                              <span className="font-mono text-blue-400 bg-blue-500/10 px-2 py-1 rounded text-xs">{p}</span>
                            </td>
                            <td className="p-3 text-text-muted capitalize text-xs font-medium">{meta.type}</td>
                            <td className="p-3">
                              {meta.required ? (
                                <span className="px-2 py-0.5 rounded text-[10px] uppercase tracking-wider font-bold bg-orange-500/10 text-orange-500 border border-orange-500/20">Required</span>
                              ) : (
                                <span className="px-2 py-0.5 rounded text-[10px] uppercase tracking-wider font-bold bg-green-500/10 text-green-500 border border-green-500/20">Optional</span>
                              )}
                            </td>
                            <td className="p-3 text-text-muted font-mono text-xs">{meta.defaultValue || <span className="opacity-30">—</span>}</td>
                            <td className="p-3 text-text-muted text-xs max-w-xs truncate" title={meta.description}>{meta.description || <span className="opacity-30">—</span>}</td>
                          </tr>
                        );
                      })}
                      {currentApi.enablePagination && (
                        <>
                          <tr className="border-t border-border-main bg-bg-panel hover:bg-bg-hover/30 transition-colors">
                            <td className="p-3">
                              <span className="font-mono text-purple-400 bg-purple-500/10 px-2 py-1 rounded text-xs">limit</span>
                            </td>
                            <td className="p-3 text-text-muted capitalize text-xs font-medium">integer</td>
                            <td className="p-3">
                              <span className="px-2 py-0.5 rounded text-[10px] uppercase tracking-wider font-bold bg-green-500/10 text-green-500 border border-green-500/20">Optional</span>
                            </td>
                            <td className="p-3 text-text-muted font-mono text-xs">100</td>
                            <td className="p-3 text-text-muted text-xs">Number of records to return</td>
                          </tr>
                          <tr className="border-t border-border-main bg-bg-panel hover:bg-bg-hover/30 transition-colors">
                            <td className="p-3">
                              <span className="font-mono text-purple-400 bg-purple-500/10 px-2 py-1 rounded text-xs">offset</span>
                            </td>
                            <td className="p-3 text-text-muted capitalize text-xs font-medium">integer</td>
                            <td className="p-3">
                              <span className="px-2 py-0.5 rounded text-[10px] uppercase tracking-wider font-bold bg-green-500/10 text-green-500 border border-green-500/20">Optional</span>
                            </td>
                            <td className="p-3 text-text-muted font-mono text-xs">0</td>
                            <td className="p-3 text-text-muted text-xs">Offset records</td>
                          </tr>
                        </>
                      )}
                    </tbody>
                  </table>
                </div>
              )}
            </div>
            
            {/* cURL Example */}
            <div>
              <h3 className="text-sm font-bold text-text-muted uppercase tracking-wider mb-3 flex items-center justify-between">
                <span className="flex items-center gap-2"><Activity className="w-4 h-4" /> cURL Example</span>
                <button 
                  onClick={() => handleCopy(curlExample, 'curl')}
                  className="text-xs flex items-center gap-1.5 text-blue-400 hover:text-blue-300 transition-colors"
                >
                  {copiedStates['curl'] ? <Check className="w-3.5 h-3.5" /> : <Copy className="w-3.5 h-3.5" />}
                  Copy Code
                </button>
              </h3>
              <div className="relative group">
                <pre className="bg-[#0d1117] border border-border-main p-4 rounded-xl overflow-x-auto shadow-inner text-sm font-mono leading-relaxed">
                  <code className="text-gray-300">
                    <span className="text-purple-400">curl</span> -X <span className="text-blue-400">{currentApi.method}</span> <span className="text-green-400">"{fullUrl}{detectedParams.length > 0 && currentApi.method === 'GET' ? '?' + detectedParams.map(p => `${p}=value`).join('&') : ''}"</span> \
                    <br/>  -H <span className="text-green-400">"Accept: application/json"</span>{
                      !currentApi.isPublic ? ` \\\n  -H "Authorization: Bearer ${currentApi.authToken}"` : ''
                    }{
                      currentApi.method !== 'GET' && detectedParams.length > 0 ? ` \\\n  -H "Content-Type: application/json" \\\n  -d '{\n${detectedParams.map(p => `    "${p}": "value"`).join(',\n')}\n  }'` : ''
                    }
                  </code>
                </pre>
              </div>
            </div>
            
          </div>
        </div>
      </div>
    );
  }

  if (viewMode === 'edit' && currentApi) {
    const paramsList = detectParams(currentApi.sqlQuery);
    const fullUrlPreview = `${window.location.origin}/api/data${currentApi.endpointPath}${paramsList.length > 0 && currentApi.method === 'GET' ? '?' + paramsList.map(p => `${p}={${p}}`).join('&') : ''}`;
    
    return (
      <div className="h-full flex flex-col overflow-hidden bg-bg-editor">
        {/* TOP BAR */}
        <div className="bg-bg-panel border-b border-border-main p-3 flex items-center justify-between shrink-0 shadow-sm z-10">
          <div className="flex items-center gap-4 flex-1">
            <button onClick={() => setViewMode('list')} className="p-2 hover:bg-bg-hover rounded-lg text-text-muted transition-colors">
              <ArrowLeft className="w-5 h-5" />
            </button>
            <div className="h-6 w-px bg-border-main hidden md:block"></div>
            
            {/* Live URL Preview */}
            <div className="hidden md:flex items-center gap-0 text-sm font-mono flex-1 max-w-3xl overflow-hidden rounded-md border border-border-main shadow-inner opacity-80 hover:opacity-100 transition-opacity">
              <span className={clsx("px-3 py-1.5 font-bold tracking-wide shrink-0", getMethodBadgeClass(currentApi.method).replace('border', ''))}>
                {currentApi.method}
              </span>
              <span className="px-3 py-1.5 bg-bg-editor text-text-muted truncate flex-1 flex items-center gap-1">
                <span className="opacity-50">{window.location.origin}/api/data</span>
                <span className="text-blue-400 font-bold">{currentApi.endpointPath}</span>
                {paramsList.length > 0 && currentApi.method === 'GET' && (
                  <span className="text-purple-400">?{paramsList.map(p => `${p}={${p}}`).join('&')}</span>
                )}
              </span>
            </div>
          </div>
          <div className="flex items-center gap-3">
            <button 
              onClick={handleSave}
              className="px-5 py-2 bg-blue-600 hover:bg-blue-500 text-white rounded-lg flex items-center gap-2 text-sm font-semibold transition-colors shadow-lg shadow-blue-500/20"
            >
              <Save className="w-4 h-4" /> Save API
            </button>
          </div>
        </div>

        {/* MAIN CONTENT AREA */}
        <div className="flex-1 overflow-hidden grid grid-cols-1 lg:grid-cols-12 gap-0 relative">
          
          {/* LEFT: SQL Editor (Full Height) */}
          <div className="lg:col-span-7 flex flex-col border-r border-border-main h-full">
            <div className="bg-bg-panel border-b border-border-main p-3 px-4 flex justify-between items-center shrink-0">
              <div className="flex items-center gap-2 text-sm font-bold text-text-main">
                <Database className="w-4 h-4 text-blue-500" /> SQL Query
              </div>
              <div className="text-[11px] font-medium text-blue-400 bg-blue-500/10 px-2.5 py-1 rounded-full border border-blue-500/20">
                Use <code className="font-mono font-bold text-blue-300">:param_name</code> for dynamic parameters
              </div>
            </div>
            <div className="flex-1 min-h-0 relative bg-bg-editor">
              <SQLEditor 
                value={currentApi.sqlQuery}
                onChange={val => setCurrentApi({...currentApi, sqlQuery: val})}
                connectionId={currentApi.connectionId}
              />
            </div>
          </div>

          {/* RIGHT: Config Scrollable Area */}
          <div className="lg:col-span-5 flex flex-col h-full bg-bg-panel overflow-y-auto">
            <div className="p-6 space-y-8">
              
              {/* Basic Info Section */}
              <section>
                <h3 className="text-xs font-bold text-text-muted uppercase tracking-wider mb-4 flex items-center gap-2">
                  <Settings2 className="w-4 h-4" /> Endpoint Configuration
                </h3>
                <div className="space-y-4">
                  <div>
                    <label className="block text-xs font-medium text-text-muted mb-1.5">API Name</label>
                    <input 
                      className="w-full bg-bg-editor border border-border-main rounded-lg p-2.5 text-sm focus:border-blue-500 focus:ring-1 focus:ring-blue-500 outline-none transition-all shadow-inner"
                      value={currentApi.name}
                      onChange={e => setCurrentApi({...currentApi, name: e.target.value})}
                      placeholder="e.g. Get User by ID"
                    />
                  </div>

                  <div className="flex gap-3">
                    <div className="w-1/3">
                      <label className="block text-xs font-medium text-text-muted mb-1.5">Method</label>
                      <div className="relative">
                        <select 
                          className="w-full bg-bg-editor border border-border-main rounded-lg p-2.5 text-sm focus:border-blue-500 focus:ring-1 focus:ring-blue-500 outline-none transition-all shadow-inner appearance-none font-bold"
                          value={currentApi.method}
                          onChange={e => setCurrentApi({...currentApi, method: e.target.value})}
                        >
                          <option value="GET">GET</option>
                          <option value="POST">POST</option>
                          <option value="PUT">PUT</option>
                          <option value="PATCH">PATCH</option>
                          <option value="DELETE">DELETE</option>
                        </select>
                        <ChevronDown className="w-4 h-4 absolute right-3 top-3 text-text-muted pointer-events-none" />
                      </div>
                    </div>
                    <div className="flex-1">
                      <label className="block text-xs font-medium text-text-muted mb-1.5">Endpoint Path</label>
                      <div className="flex items-center shadow-inner rounded-lg border border-border-main focus-within:border-blue-500 focus-within:ring-1 focus-within:ring-blue-500 transition-all overflow-hidden">
                        <span className="bg-bg-panel px-3 py-2.5 text-sm text-text-muted/70 font-mono border-r border-border-main">
                          /api/data
                        </span>
                        <input 
                          className="w-full bg-bg-editor p-2.5 text-sm outline-none font-mono text-blue-400"
                          value={currentApi.endpointPath}
                          onChange={e => {
                            let val = e.target.value;
                            if (!val.startsWith('/')) val = '/' + val;
                            setCurrentApi({...currentApi, endpointPath: val});
                          }}
                          placeholder="/users"
                        />
                      </div>
                    </div>
                  </div>

                  <div>
                    <label className="block text-xs font-medium text-text-muted mb-1.5">Database Connection</label>
                    <div className="relative">
                      <select 
                        className="w-full bg-bg-editor border border-border-main rounded-lg p-2.5 text-sm focus:border-blue-500 focus:ring-1 focus:ring-blue-500 outline-none transition-all shadow-inner appearance-none"
                        value={currentApi.connectionId}
                        onChange={e => setCurrentApi({...currentApi, connectionId: e.target.value})}
                      >
                        <option value="" disabled>Select a connection...</option>
                        {connections.map(c => (
                          <option key={c.id} value={c.id}>{c.name} ({c.type})</option>
                        ))}
                      </select>
                      <ChevronDown className="w-4 h-4 absolute right-3 top-3 text-text-muted pointer-events-none" />
                    </div>
                  </div>
                </div>
              </section>

              {/* Parameter Configuration Section */}
              <section>
                <div className="flex items-center justify-between mb-4">
                  <h3 className="text-xs font-bold text-text-muted uppercase tracking-wider flex items-center gap-2">
                    <Database className="w-4 h-4" /> Parameters
                  </h3>
                  <div className={clsx("text-[10px] font-bold px-2 py-0.5 rounded-full transition-all duration-300", 
                    paramCount > 0 ? "bg-blue-500/20 text-blue-400 border border-blue-500/30" : "bg-bg-editor text-text-muted"
                  )}>
                    {paramCount} Detected
                  </div>
                </div>
                
                {paramsList.length === 0 ? (
                  <div className="bg-bg-editor/50 border border-border-main border-dashed rounded-xl p-6 text-center">
                    <p className="text-xs text-text-muted">Type <code className="font-mono text-blue-400 bg-blue-500/10 px-1 rounded">:param_name</code> in the SQL editor to detect parameters.</p>
                  </div>
                ) : (
                  <div className="space-y-3">
                    {paramsList.map(p => {
                      const meta = parameterMeta.find(m => m.name === p) || {
                        name: p, type: 'string', required: true, defaultValue: '', description: ''
                      };
                      return (
                        <div key={p} className="bg-bg-editor border border-border-main rounded-xl p-4 shadow-sm animate-in fade-in slide-in-from-right-4 duration-300">
                          <div className="flex items-center justify-between mb-3 border-b border-border-main/50 pb-2">
                            <span className="font-mono font-bold text-blue-400 text-sm">:{p}</span>
                            <label className="flex items-center gap-2 cursor-pointer group">
                              <span className="text-[10px] uppercase font-bold text-text-muted group-hover:text-text-main transition-colors">Required</span>
                              <div className={clsx("w-8 h-4 rounded-full relative transition-colors duration-200", meta.required ? "bg-blue-500" : "bg-bg-panel border border-border-main")}>
                                <div className={clsx("w-3 h-3 rounded-full absolute top-0.5 transition-all duration-200", meta.required ? "bg-white left-4.5" : "bg-text-muted left-0.5")} style={{ left: meta.required ? '18px' : '2px' }}/>
                              </div>
                              <input 
                                type="checkbox" className="sr-only"
                                checked={meta.required}
                                onChange={e => setParameterMeta(prev => prev.map(m => m.name === p ? { ...m, required: e.target.checked } : m))}
                              />
                            </label>
                          </div>
                          
                          <div className="grid grid-cols-2 gap-3 mb-3">
                            <div>
                              <label className="block text-[10px] uppercase font-bold text-text-muted mb-1">Type</label>
                              <div className="relative">
                                <select 
                                  className="w-full bg-bg-panel border border-border-main rounded text-xs p-1.5 outline-none focus:border-blue-500 appearance-none shadow-inner"
                                  value={meta.type}
                                  onChange={e => setParameterMeta(prev => prev.map(m => m.name === p ? { ...m, type: e.target.value as any } : m))}
                                >
                                  <option value="string">String</option>
                                  <option value="integer">Integer</option>
                                  <option value="number">Number</option>
                                  <option value="boolean">Boolean</option>
                                  <option value="date">Date</option>
                                </select>
                                <ChevronDown className="w-3 h-3 absolute right-2 top-2 text-text-muted pointer-events-none" />
                              </div>
                            </div>
                            <div>
                              <label className="block text-[10px] uppercase font-bold text-text-muted mb-1">Default</label>
                              <input 
                                type="text"
                                className="w-full bg-bg-panel border border-border-main rounded text-xs p-1.5 outline-none focus:border-blue-500 shadow-inner"
                                placeholder={meta.required ? "N/A (Required)" : "Empty..."}
                                disabled={meta.required}
                                value={meta.defaultValue}
                                onChange={e => setParameterMeta(prev => prev.map(m => m.name === p ? { ...m, defaultValue: e.target.value } : m))}
                              />
                            </div>
                          </div>
                          <div>
                            <label className="block text-[10px] uppercase font-bold text-text-muted mb-1">Description</label>
                            <input 
                              type="text"
                              className="w-full bg-bg-panel border border-border-main rounded text-xs p-1.5 outline-none focus:border-blue-500 shadow-inner"
                              placeholder="What is this parameter for?"
                              value={meta.description}
                              onChange={e => setParameterMeta(prev => prev.map(m => m.name === p ? { ...m, description: e.target.value } : m))}
                            />
                          </div>
                        </div>
                      );
                    })}
                  </div>
                )}
              </section>

              {/* Security & Features */}
              <section>
                <h3 className="text-xs font-bold text-text-muted uppercase tracking-wider mb-4 flex items-center gap-2">
                  <ShieldCheck className="w-4 h-4" /> Security & Features
                </h3>
                <div className="space-y-3">
                  <div className={clsx("border rounded-xl p-4 transition-colors", currentApi.isPublic ? "bg-green-500/5 border-green-500/20" : "bg-bg-editor border-border-main")}>
                    <label className="flex items-start gap-3 cursor-pointer">
                      <input 
                        type="checkbox" 
                        className="mt-1 w-4 h-4 rounded border-border-main text-green-500 focus:ring-green-500 focus:ring-offset-bg-panel bg-bg-panel"
                        checked={currentApi.isPublic} 
                        onChange={e => setCurrentApi({...currentApi, isPublic: e.target.checked})} 
                      />
                      <div>
                        <span className={clsx("text-sm font-bold", currentApi.isPublic ? "text-green-500" : "text-text-main")}>Public API</span>
                        <p className="text-xs text-text-muted mt-0.5">Allow anyone to access this endpoint without authentication.</p>
                      </div>
                    </label>
                  </div>

                  {!currentApi.isPublic && (
                    <div className="bg-orange-500/5 border border-orange-500/20 rounded-xl p-4 animate-in fade-in slide-in-from-top-2">
                      <label className="block text-xs font-bold text-orange-500 uppercase tracking-wider mb-2">Bearer Token Required</label>
                      <input 
                        type="text"
                        className="w-full bg-bg-panel border border-orange-500/30 rounded-lg p-2.5 text-sm focus:border-orange-500 focus:ring-1 focus:ring-orange-500 outline-none text-orange-400 font-mono shadow-inner"
                        value={currentApi.authToken}
                        onChange={e => setCurrentApi({...currentApi, authToken: e.target.value})}
                        placeholder="e.g. secret-token-123"
                      />
                    </div>
                  )}

                  <div className={clsx("border rounded-xl p-4 transition-colors", currentApi.enablePagination ? "bg-blue-500/5 border-blue-500/20" : "bg-bg-editor border-border-main")}>
                    <label className="flex items-start gap-3 cursor-pointer">
                      <input 
                        type="checkbox" 
                        className="mt-1 w-4 h-4 rounded border-border-main text-blue-500 focus:ring-blue-500 focus:ring-offset-bg-panel bg-bg-panel"
                        checked={currentApi.enablePagination} 
                        onChange={e => setCurrentApi({...currentApi, enablePagination: e.target.checked})} 
                      />
                      <div>
                        <span className={clsx("text-sm font-bold", currentApi.enablePagination ? "text-blue-500" : "text-text-main")}>Enable Auto-Pagination</span>
                        <p className="text-xs text-text-muted mt-0.5">Automatically appends LIMIT and OFFSET based on `limit` and `page` request parameters.</p>
                      </div>
                    </label>
                  </div>
                </div>
              </section>
              
              <div className="h-8"></div> {/* Spacer for bottom panel */}
            </div>
          </div>
          
          {/* BOTTOM PANEL: Test Console */}
          <div className={clsx(
            "absolute bottom-0 right-0 lg:w-[calc(100%-58.333333%)] w-full bg-[#0f141f] border-t border-l border-border-main shadow-2xl transition-all duration-300 ease-in-out flex flex-col z-20",
            isTestConsoleOpen ? "h-1/2 min-h-[300px]" : "h-12"
          )}>
            {/* Header */}
            <div 
              className="h-12 border-b border-border-main/50 flex items-center justify-between px-4 cursor-pointer hover:bg-white/5 transition-colors"
              onClick={() => setIsTestConsoleOpen(!isTestConsoleOpen)}
            >
              <div className="flex items-center gap-2 text-sm font-bold text-text-main">
                <Play className="w-4 h-4 text-green-400" /> Test Console
              </div>
              <div className="flex items-center gap-3">
                {isTestConsoleOpen && (
                  <button 
                    onClick={(e) => { e.stopPropagation(); handleTest(); }}
                    disabled={isTesting}
                    className="px-4 py-1.5 bg-green-500 hover:bg-green-400 disabled:opacity-50 text-white rounded text-xs font-bold flex items-center gap-2 transition-colors shadow-lg shadow-green-500/20"
                  >
                    {isTesting ? <div className="w-3 h-3 border-2 border-white border-t-transparent rounded-full animate-spin"/> : <Play className="w-3 h-3" />}
                    {isTesting ? 'Running...' : 'Run Test'}
                  </button>
                )}
                <div className="text-text-muted hover:text-white transition-colors p-1">
                  {isTestConsoleOpen ? <ChevronDown className="w-5 h-5" /> : <ChevronUp className="w-5 h-5" />}
                </div>
              </div>
            </div>
            
            {/* Body */}
            {isTestConsoleOpen && (
              <div className="flex-1 flex overflow-hidden">
                {/* Test Inputs */}
                <div className="w-1/3 border-r border-border-main/50 p-4 overflow-y-auto bg-black/20">
                  <h4 className="text-[10px] uppercase font-bold text-text-muted tracking-wider mb-3">Variables</h4>
                  {paramsList.length === 0 ? (
                    <p className="text-xs text-text-muted italic">No variables required.</p>
                  ) : (
                    <div className="space-y-3">
                      {paramsList.map(p => (
                        <div key={p}>
                          <label className="block text-xs font-mono text-blue-400 mb-1">:{p}</label>
                          <input 
                            type="text"
                            className="w-full bg-[#1e293b] border border-[#334155] rounded px-3 py-2 text-xs outline-none focus:border-blue-500 text-white shadow-inner font-mono transition-colors"
                            placeholder="value..."
                            value={testParams[p] || ''}
                            onChange={e => setTestParams({...testParams, [p]: e.target.value})}
                          />
                        </div>
                      ))}
                    </div>
                  )}
                  {currentApi.enablePagination && (
                    <div className="mt-4 pt-4 border-t border-border-main/30 space-y-3">
                       <div>
                          <label className="block text-xs font-mono text-purple-400 mb-1">limit</label>
                          <input 
                            type="text"
                            className="w-full bg-[#1e293b] border border-[#334155] rounded px-3 py-2 text-xs outline-none focus:border-purple-500 text-white shadow-inner font-mono transition-colors"
                            placeholder="100"
                            value={testParams['limit'] || ''}
                            onChange={e => setTestParams({...testParams, 'limit': e.target.value})}
                          />
                        </div>
                    </div>
                  )}
                </div>
                
                {/* Response Viewer */}
                <div className="flex-1 flex flex-col bg-[#0b0f19] relative">
                  <div className="h-8 border-b border-border-main/30 flex items-center justify-between px-4 bg-black/40 shrink-0">
                    <span className="text-[10px] uppercase font-bold text-text-muted tracking-wider">Response</span>
                    {testResult && (
                      <span className={clsx("text-[10px] font-bold px-2 py-0.5 rounded-full", 
                        testResult.status >= 400 ? "bg-red-500/20 text-red-400 border border-red-500/20" : "bg-green-500/20 text-green-400 border border-green-500/20"
                      )}>
                        {testResult.status} {testResult.status === 200 ? 'OK' : ''}
                      </span>
                    )}
                  </div>
                  <div className="flex-1 overflow-auto p-4 relative">
                    {isTesting && (
                      <div className="absolute inset-0 bg-[#0b0f19]/80 backdrop-blur-sm flex items-center justify-center z-10">
                        <div className="w-8 h-8 border-4 border-blue-500 border-t-transparent rounded-full animate-spin"></div>
                      </div>
                    )}
                    {testResult ? (
                      <pre className={clsx("font-mono text-xs leading-relaxed", testResult.status >= 400 ? "text-red-300" : "text-green-300")}>
                        {JSON.stringify(testResult.data || testResult.error, null, 2)}
                      </pre>
                    ) : (
                      <div className="h-full flex items-center justify-center text-text-muted/50 text-sm italic font-mono">
                        // Response will appear here
                      </div>
                    )}
                  </div>
                </div>
              </div>
            )}
          </div>
          
        </div>
      </div>
    );
  }

  return null;
};
