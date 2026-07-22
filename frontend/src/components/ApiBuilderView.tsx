import React, { useState, useEffect, useCallback, useRef } from 'react';
import axios from 'axios';
import { useAppStore } from '../store/useAppStore';
import { 
  Webhook, Plus, Save, ArrowLeft, Play, ShieldCheck, ShieldAlert, 
  FileJson, Pencil, Trash2, Copy, Check, Share2, Database, Server, 
  Settings2, ChevronDown, ChevronUp, X, AlertCircle, Loader2, 
  Search, Filter, Eraser, Code2, BookTemplate, 
  ListRestart, Bug, SquareTerminal, CopyPlus, FileCode
} from 'lucide-react';
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

type ValidationError = {
  field: string;
  message: string;
};

const QUERY_TEMPLATES = [
  { name: 'Get All', sql: 'SELECT * FROM my_table LIMIT 100', icon: Search },
  { name: 'Get By ID', sql: 'SELECT * FROM my_table WHERE id = :id', icon: Filter },
  { name: 'Search', sql: 'SELECT * FROM my_table WHERE name ILIKE :search_term\nORDER BY name\nLIMIT :limit\nOFFSET :offset', icon: Search },
  { name: 'Aggregate', sql: 'SELECT category, COUNT(*) as count, AVG(price) as avg_price\nFROM my_table\nGROUP BY category\nORDER BY count DESC', icon: Code2 },
  { name: 'Pagination', sql: 'SELECT * FROM my_table\nORDER BY id\nLIMIT :limit\nOFFSET :offset', icon: ListRestart },
  { name: 'Insert', sql: 'INSERT INTO my_table (column1, column2)\nVALUES (:value1, :value2)\nRETURNING *', icon: Plus },
  { name: 'Update', sql: 'UPDATE my_table\nSET column1 = :value1, column2 = :value2\nWHERE id = :id\nRETURNING *', icon: Pencil },
  { name: 'Delete', sql: 'DELETE FROM my_table\nWHERE id = :id\nRETURNING *', icon: Trash2 },
];

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
  const [isSaving, setIsSaving] = useState(false);
  const [isDirty, setIsDirty] = useState(false);
  const [validationErrors, setValidationErrors] = useState<ValidationError[]>([]);
  
  const [testResult, setTestResult] = useState<any>(null);
  const [testParams, setTestParams] = useState<Record<string, string>>({});
  const [isTesting, setIsTesting] = useState(false);
  const [copiedStates, setCopiedStates] = useState<Record<string, boolean>>({});
  const [isTestConsoleOpen, setIsTestConsoleOpen] = useState(false);
  const [paramCount, setParamCount] = useState(0);
  const [generatedShareUrl, setGeneratedShareUrl] = useState<string | null>(null);
  const [activeSpecTab, setActiveSpecTab] = useState<'curl' | 'postman' | 'bruno'>('curl');
  const [showTemplates, setShowTemplates] = useState(false);
  const [isPrettyPrint, setIsPrettyPrint] = useState(true);

  const editInitialRef = useRef<string>('');

  useEffect(() => {
    fetchEndpoints();
  }, []);

  // Track dirty state
  useEffect(() => {
    if (viewMode === 'edit' && currentApi) {
      const serialized = JSON.stringify({ api: currentApi, params: parameterMeta });
      if (editInitialRef.current && editInitialRef.current !== serialized) {
        setIsDirty(true);
      } else {
        setIsDirty(false);
      }
    }
  }, [currentApi, parameterMeta, viewMode]);

  // beforeunload warning for unsaved changes
  useEffect(() => {
    if (isDirty) {
      window.onbeforeunload = () => 'You have unsaved changes!';
    } else {
      window.onbeforeunload = null;
    }
    return () => { window.onbeforeunload = null; };
  }, [isDirty]);

  // Keyboard shortcuts
  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if (viewMode === 'edit') {
        if ((e.ctrlKey || e.metaKey) && e.key === 's') {
          e.preventDefault();
          handleSave();
        }
        if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') {
          e.preventDefault();
          if (isTestConsoleOpen) handleTest();
          else {
            setIsTestConsoleOpen(true);
            setTimeout(handleTest, 300);
          }
        }
      }
      if (e.key === 'Escape' && viewMode !== 'list') {
        e.preventDefault();
        if (isTestConsoleOpen) {
          setIsTestConsoleOpen(false);
        } else if (viewMode === 'edit' || viewMode === 'spec') {
          if (isDirty) {
            showAlert({
              title: 'Unsaved Changes',
              message: 'You have unsaved changes. Are you sure you want to go back?',
              type: 'warning',
              confirmLabel: 'Leave',
              cancelLabel: 'Stay',
              onConfirm: () => {
                setViewMode('list');
                setIsDirty(false);
              }
            });
          } else {
            setViewMode('list');
          }
        }
      }
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [viewMode, isTestConsoleOpen, isDirty]);

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

  const validate = (): ValidationError[] => {
    const errors: ValidationError[] = [];
    if (!currentApi) return errors;
    if (!currentApi.name.trim()) {
      errors.push({ field: 'name', message: 'API name is required' });
    }
    if (!currentApi.connectionId) {
      errors.push({ field: 'connectionId', message: 'Database connection is required' });
    }
    if (!currentApi.sqlQuery.trim()) {
      errors.push({ field: 'sqlQuery', message: 'SQL query is required' });
    }
    if (!currentApi.endpointPath.trim() || currentApi.endpointPath === '/') {
      errors.push({ field: 'endpointPath', message: 'Endpoint path is required' });
    }
    // Check for duplicate paths
    if (currentApi.endpointPath.trim() && currentApi.endpointPath !== '/') {
      const duplicate = endpoints.find(e => 
        e.endpointPath === currentApi.endpointPath && e.id !== currentApi.id
      );
      if (duplicate) {
        errors.push({ field: 'endpointPath', message: `Endpoint path "${currentApi.endpointPath}" already exists` });
      }
    }
    return errors;
  };

  const handleCreateNew = () => {
    const newApi: ApiEndpoint = {
      name: '',
      method: 'GET',
      endpointPath: '',
      connectionId: connections[0]?.id || '',
      sqlQuery: '',
      parameters: '[]',
      enablePagination: false,
      isPublic: true,
      authToken: ''
    };
    setCurrentApi(newApi);
    setTestParams({});
    setTestResult(null);
    setParameterMeta([]);
    setValidationErrors([]);
    setIsDirty(false);
    editInitialRef.current = JSON.stringify({ api: newApi, params: [] });
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
    setValidationErrors([]);
    setIsDirty(false);
    editInitialRef.current = JSON.stringify({ api, params: parsed });
    setViewMode('edit');
  };

  const handleViewSpec = (api: ApiEndpoint) => {
    setCurrentApi(api);
    setViewMode('spec');
  };

  const handleClone = (api: ApiEndpoint) => {
    const clone = { ...api, id: undefined, name: `${api.name} (Copy)` };
    setCurrentApi(clone);
    setTestParams({});
    setTestResult(null);
    let parsed: ApiParameter[] = [];
    try {
      if (api.parameters && api.parameters !== '[]') {
        parsed = JSON.parse(api.parameters);
      }
    } catch(e) {}
    setParameterMeta(parsed);
    setValidationErrors([]);
    setIsDirty(false);
    editInitialRef.current = JSON.stringify({ api: clone, params: parsed });
    setViewMode('edit');
    addToast({ type: 'info', title: 'Cloned', message: `"${api.name}" duplicated as "${clone.name}"` });
  };

  const handleSave = async () => {
    if (!currentApi) return;
    
    const errors = validate();
    setValidationErrors(errors);
    if (errors.length > 0) {
      addToast({ type: 'error', title: 'Validation Failed', message: `Please fix ${errors.length} error(s) before saving.` });
      return;
    }
    
    setIsSaving(true);
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
      setIsDirty(false);
      setViewMode('list');
    } catch (err: any) {
      addToast({ type: 'error', title: 'Error', message: 'Error saving API: ' + (err.response?.data?.error || err.message) });
    } finally {
      setIsSaving(false);
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

  const handleBackToList = () => {
    if (isDirty) {
      showAlert({
        title: 'Unsaved Changes',
        message: 'You have unsaved changes. Are you sure you want to go back?',
        type: 'warning',
        confirmLabel: 'Leave',
        cancelLabel: 'Stay',
        onConfirm: () => {
          setViewMode('list');
          setIsDirty(false);
        }
      });
    } else {
      setViewMode('list');
    }
  };

  const detectParams = (sql: string): string[] => {
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
          } as ApiParameter;
        });
        const isSame = prev.length === next.length && next.every((n, i) => n === prev[i]);
        return isSame ? prev : next;
      });
    }
  }, [currentApi?.sqlQuery, viewMode]);

  const handleTest = useCallback(async () => {
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
      const errorData = err.response?.data;
      setTestResult({ 
        status: err.response?.status || 500, 
        error: errorData?.error || err.message,
        detail: errorData?.detail || errorData?.stack || null
      });
    } finally {
      setIsTesting(false);
    }
  }, [currentApi, testParams]);

  const fallbackCopyTextToClipboard = (text: string, id: string) => {
    const textArea = document.createElement("textarea");
    textArea.value = text;
    textArea.style.top = "0";
    textArea.style.left = "0";
    textArea.style.position = "fixed";
    document.body.appendChild(textArea);
    textArea.focus();
    textArea.select();
    try {
      document.execCommand('copy');
      setCopiedStates(prev => ({ ...prev, [id]: true }));
      setTimeout(() => setCopiedStates(prev => ({ ...prev, [id]: false })), 2000);
      addToast({ type: 'success', title: 'Copied', message: 'Copied to clipboard!' });
    } catch {
      addToast({ type: 'error', title: 'Error', message: 'Failed to copy text' });
    }
    document.body.removeChild(textArea);
  };

  const handleCopy = (text: string, id: string) => {
    if (!navigator.clipboard) {
      fallbackCopyTextToClipboard(text, id);
      return;
    }
    navigator.clipboard.writeText(text).then(() => {
      setCopiedStates(prev => ({ ...prev, [id]: true }));
      setTimeout(() => {
        setCopiedStates(prev => ({ ...prev, [id]: false }));
      }, 2000);
      addToast({ type: 'success', title: 'Copied', message: 'Copied to clipboard!' });
    }).catch(() => {
      fallbackCopyTextToClipboard(text, id);
    });
  };

  const handleShare = async () => {
    if (!currentApi?.id) return;
    try {
      const res = await axios.post(`/api/api-builder/${currentApi.id}/share`);
      const { shareUrl } = res.data;
      const fullUrl = `${window.location.origin}${shareUrl}`;
      setGeneratedShareUrl(fullUrl);
      addToast({ type: 'success', title: 'Share Link Generated', message: 'One-time link generated successfully!' });
    } catch {
      addToast({ type: 'error', title: 'Error', message: 'Failed to generate share link.' });
    }
  };

  const applyTemplate = (template: typeof QUERY_TEMPLATES[0]) => {
    if (!currentApi) return;
    setCurrentApi({ ...currentApi, sqlQuery: template.sql });
    setShowTemplates(false);
  };

  const getConnectionName = (connectionId: string): string => {
    const conn = connections.find(c => c.id === connectionId);
    return conn ? `${conn.name} (${conn.type})` : connectionId;
  };

  // ─────────────────────────────────────────────
  // LIST VIEW
  // ─────────────────────────────────────────────
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
              <span className="ml-2 text-sm font-normal bg-bg-panel border border-border-main px-3 py-1 rounded-full text-text-muted">
                {endpoints.length} endpoint{endpoints.length !== 1 ? 's' : ''}
              </span>
            </h1>
            <p className="text-text-muted mt-2 text-sm max-w-xl leading-relaxed">
              Design, test, and deploy database-backed JSON APIs instantly. Turn complex queries into production-ready endpoints.
            </p>
            <div className="flex items-center gap-2 mt-2 text-[11px] text-text-muted">
              <kbd className="px-1.5 py-0.5 bg-bg-panel border border-border-main rounded text-[10px] font-mono">⌘S</kbd>
              <span>Save &middot;</span>
              <kbd className="px-1.5 py-0.5 bg-bg-panel border border-border-main rounded text-[10px] font-mono">⌘⏎</kbd>
              <span>Run Test &middot;</span>
              <kbd className="px-1.5 py-0.5 bg-bg-panel border border-border-main rounded text-[10px] font-mono">Esc</kbd>
              <span>Back</span>
            </div>
          </div>
          <button 
            onClick={handleCreateNew}
            className="px-5 py-2.5 bg-blue-600 hover:bg-blue-500 text-white rounded-lg flex items-center gap-2 font-medium transition-all shadow-lg shadow-blue-500/20 hover:shadow-blue-500/30 active:scale-[0.97]"
            aria-label="Create new API endpoint"
          >
            <Plus className="w-5 h-5" /> Create API
          </button>
        </div>

        <div className="bg-bg-panel border border-border-main rounded-xl shadow-xl overflow-hidden">
          <table className="w-full text-left text-sm">
            <thead className="bg-bg-editor/50 text-text-muted font-semibold border-b border-border-main">
              <tr>
                <th className="p-4 uppercase tracking-wider text-xs">Name</th>
                <th className="p-4 uppercase tracking-wider text-xs">Method &amp; Endpoint</th>
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
                        <p className="text-sm">Get started — click <strong>"Create API"</strong> to design your first endpoint.</p>
                      </div>
                    </div>
                  </td>
                </tr>
              ) : (
                endpoints.map(api => (
                  <tr key={api.id} className="border-b border-border-main hover:bg-bg-hover/50 transition-colors group">
                    <td className="p-4">
                      <div className="flex items-center gap-2">
                        <span className="font-semibold text-text-main">{api.name}</span>
                        {api.enablePagination && (
                          <span className="text-[10px] text-blue-400 bg-blue-500/10 px-1.5 py-0.5 rounded font-mono" title="Pagination enabled">Pg</span>
                        )}
                      </div>
                    </td>
                    <td className="p-4">
                      <div className="flex items-center gap-3">
                        <span className={clsx("px-2.5 py-1 rounded-md text-[10px] font-black tracking-widest flex items-center gap-1", 
                          getMethodBadgeClass(api.method)
                        )}>
                          <span className="sr-only">HTTP method: </span>
                          {api.method}
                        </span>
                        <code className="font-mono text-xs text-text-muted group-hover:text-blue-400 transition-colors">
                          /api/data{api.endpointPath}
                        </code>
                      </div>
                    </td>
                    <td className="p-4">
                      {api.isPublic ? (
                        <span className="inline-flex items-center gap-1.5 text-green-500 text-xs font-medium bg-green-500/10 px-2 py-1 rounded-full">
                          <ShieldCheck className="w-3.5 h-3.5" aria-hidden="true"/> Public
                        </span>
                      ) : (
                        <span className="inline-flex items-center gap-1.5 text-orange-500 text-xs font-medium bg-orange-500/10 px-2 py-1 rounded-full">
                          <ShieldAlert className="w-3.5 h-3.5" aria-hidden="true"/> Protected
                        </span>
                      )}
                    </td>
                    <td className="p-4">
                      <div className="flex items-center justify-end gap-1">
                        <button 
                          onClick={() => handleViewSpec(api)} 
                          className="p-2 text-text-muted hover:text-purple-400 hover:bg-purple-500/10 rounded-lg transition-colors"
                          title="View Spec"
                          aria-label={`View specification for ${api.name}`}
                        >
                          <FileJson className="w-4 h-4"/>
                        </button>
                        <button 
                          onClick={() => handleClone(api)} 
                          className="p-2 text-text-muted hover:text-cyan-400 hover:bg-cyan-500/10 rounded-lg transition-colors"
                          title="Clone API"
                          aria-label={`Clone ${api.name}`}
                        >
                          <CopyPlus className="w-4 h-4"/>
                        </button>
                        <button 
                          onClick={() => handleEdit(api)} 
                          className="p-2 text-text-muted hover:text-blue-400 hover:bg-blue-500/10 rounded-lg transition-colors"
                          title="Edit API"
                          aria-label={`Edit ${api.name}`}
                        >
                          <Pencil className="w-4 h-4"/>
                        </button>
                        <button 
                          onClick={() => handleDeleteClick(api)} 
                          className="p-2 text-text-muted hover:text-red-400 hover:bg-red-500/10 rounded-lg transition-colors"
                          title="Delete API"
                          aria-label={`Delete ${api.name}`}
                        >
                          <Trash2 className="w-4 h-4"/>
                        </button>
                      </div>
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

  // ─────────────────────────────────────────────
  // SPEC VIEW
  // ─────────────────────────────────────────────
  if (viewMode === 'spec' && currentApi) {
    const fullUrl = `${window.location.origin}/api/data${currentApi.endpointPath}`;
    const detectedParams = detectParams(currentApi.sqlQuery);
    let parsedParams: ApiParameter[] = [];
    try {
      parsedParams = JSON.parse(currentApi.parameters || '[]');
    } catch(e) {}
    const connName = getConnectionName(currentApi.connectionId);
    
    const qs = (detectedParams.length > 0 || currentApi.enablePagination) && currentApi.method === 'GET'
      ? '?' + [...detectedParams.map(p => `${p}=value`), ...(currentApi.enablePagination ? ['limit=100', 'offset=0'] : [])].join('&')
      : '';
      
    const curlExample = `curl -X ${currentApi.method} "${fullUrl}${qs}" \\\n  -H "Accept: application/json" ${!currentApi.isPublic ? `\\\n  -H "Authorization: Bearer ${currentApi.authToken}"` : ''}${currentApi.method !== 'GET' && detectedParams.length > 0 ? ` \\\n  -H "Content-Type: application/json" \\\n  -d '{\n${detectedParams.map(p => `    "${p}": "value"`).join(',\n')}\n  }'` : ''}`;

    const postmanExample = `${currentApi.method} ${fullUrl}${qs} HTTP/1.1\nHost: ${window.location.host}\nAccept: application/json${!currentApi.isPublic ? `\nAuthorization: Bearer ${currentApi.authToken}` : ''}${currentApi.method !== 'GET' && detectedParams.length > 0 ? `\nContent-Type: application/json\n\n{\n${detectedParams.map(p => `  "${p}": "value"`).join(',\n')}\n}` : ''}`;

    const brunoExample = `meta {\n  name: ${currentApi.name}\n  type: http\n  seq: 1\n}\n\n${currentApi.method.toLowerCase()} {\n  url: ${fullUrl}${qs}\n  body: ${currentApi.method !== 'GET' && detectedParams.length > 0 ? 'json' : 'none'}\n  auth: ${!currentApi.isPublic ? 'bearer' : 'none'}\n}\n${!currentApi.isPublic ? `\nauth:bearer {\n  token: ${currentApi.authToken}\n}` : ''}${(detectedParams.length > 0 || currentApi.enablePagination) && currentApi.method === 'GET' ? `\nquery {\n${detectedParams.map(p => `  ${p}: value`).join('\n')}${currentApi.enablePagination ? '\n  limit: 100\n  offset: 0' : ''}\n}` : ''}${currentApi.method !== 'GET' && detectedParams.length > 0 ? `\nbody:json {\n  {\n${detectedParams.map(p => `    "${p}": "value"`).join(',\n')}\n  }\n}` : ''}`;
    
    return (
      <div className="h-full flex flex-col p-6 overflow-y-auto">
        <div className="flex items-center justify-between mb-8 max-w-5xl mx-auto w-full">
          <div className="flex items-center gap-4">              <button 
              onClick={() => setViewMode('list')} 
              className="p-2.5 hover:bg-bg-hover bg-bg-panel border border-border-main rounded-xl text-text-muted shadow-sm transition-all hover:scale-105"
              aria-label="Back to API list"
            >
              <ArrowLeft className="w-5 h-5" />
            </button>
            <div>
              <h1 className="text-3xl font-extrabold text-text-main flex items-center gap-3">
                <FileJson className="w-7 h-7 text-purple-500" />
                API Specification
              </h1>
              <p className="text-text-muted text-sm mt-1 font-medium">{currentApi.name}</p>
            </div>
          </div>
          <button 
            onClick={handleShare}
            className="px-4 py-2 bg-gradient-to-r from-purple-500 to-blue-500 hover:from-purple-600 hover:to-blue-600 text-white rounded-lg flex items-center gap-2 font-medium shadow-lg shadow-purple-500/20 transition-all hover:scale-105 active:scale-[0.97]"
            aria-label="Generate one-time share link"
          >
            <Share2 className="w-4 h-4" />
            Share One-Time Link
          </button>
        </div>
        
        {generatedShareUrl && (
          <div className="max-w-5xl mx-auto w-full mb-6">
            <div className="bg-gradient-to-r from-purple-500/10 to-blue-500/10 border border-purple-500/20 p-6 rounded-2xl shadow-lg relative overflow-hidden">
              <div className="absolute top-0 left-0 w-1 h-full bg-gradient-to-b from-purple-500 to-blue-500"></div>
              <div className="flex items-start justify-between gap-4">
                <div className="flex-1">
                  <h3 className="text-sm font-bold text-purple-400 uppercase tracking-wider mb-2 flex items-center gap-2">
                    <Check className="w-4 h-4" /> One-Time Link Generated!
                  </h3>
                  <p className="text-text-muted text-sm mb-4">
                    This link can only be viewed once. Copy it below to share the API specification.
                  </p>
                  <div className="flex items-center gap-2">
                    <code className="bg-bg-input px-4 py-3 rounded-lg flex-1 font-mono text-sm border border-border-main text-text-main break-all">
                      {generatedShareUrl}
                    </code>
                    <button
                      onClick={() => handleCopy(generatedShareUrl, 'share-link')}
                      className="px-4 py-3 bg-bg-panel hover:bg-bg-hover border border-border-main rounded-lg text-text-main transition-colors flex items-center gap-2 shrink-0"
                      aria-label="Copy share link"
                    >
                      {copiedStates['share-link'] ? <Check className="w-4 h-4 text-green-500" /> : <Copy className="w-4 h-4" />}
                      {copiedStates['share-link'] ? 'Copied!' : 'Copy Link'}
                    </button>
                  </div>
                </div>
                <button 
                  onClick={() => setGeneratedShareUrl(null)}
                  className="p-2 text-text-muted hover:text-text-main hover:bg-bg-hover rounded-lg transition-colors"
                  aria-label="Close share link banner"
                >
                  <X className="w-5 h-5" />
                </button>
              </div>
            </div>
          </div>
        )}
        
        <div className="max-w-5xl mx-auto w-full space-y-6">
          <div className="bg-bg-panel border border-border-main p-8 rounded-2xl shadow-xl space-y-8 relative overflow-hidden">
            <div className="absolute top-0 left-0 w-full h-1 bg-gradient-to-r from-blue-500 via-purple-500 to-green-500 opacity-50"></div>
            
            {/* URL Section */}
            <div>
              <h3 className="text-sm font-bold text-text-muted uppercase tracking-wider mb-3 flex items-center gap-2 justify-between">
                <span className="flex items-center gap-2"><Webhook className="w-4 h-4" /> Endpoint URL</span>
                {currentApi.connectionId && (
                  <span className="text-xs bg-bg-hover px-2 py-1 rounded border border-border-main text-text-muted flex items-center gap-1.5 font-normal">
                    <Database className="w-3 h-3" /> Connection: {connName}
                  </span>
                )}
              </h3>
              <div className="flex items-center gap-0">
                <span className={clsx("px-4 py-3 rounded-l-lg text-sm font-black tracking-wider shadow-inner flex items-center gap-1.5", 
                    getMethodBadgeClass(currentApi.method)
                  )}>
                    <span aria-hidden="true" className="w-2 h-2 rounded-full bg-current"></span>
                    {currentApi.method}
                </span>
                <code className="bg-bg-editor px-4 py-3 border-y border-border-main text-text-main font-mono flex-1 text-sm overflow-x-auto whitespace-nowrap shadow-inner">
                  {fullUrl}
                </code>
                <button 
                  onClick={() => handleCopy(fullUrl, 'url')}
                  className="bg-bg-hover hover:bg-blue-500/10 hover:text-blue-400 border-y border-r border-border-main px-4 py-3 rounded-r-lg text-text-muted transition-colors flex items-center justify-center shadow-inner"
                  title="Copy URL"
                  aria-label="Copy endpoint URL"
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
                  <ShieldCheck className="w-6 h-6 text-green-500" aria-hidden="true" />
                  <div>
                    <p className="text-sm font-bold text-green-500">Public Access</p>
                    <p className="text-xs text-green-500/80 mt-0.5">This API can be accessed without any authorization headers.</p>
                  </div>
                </div>
              ) : (
                <div className="bg-orange-500/10 border border-orange-500/20 p-4 rounded-xl">
                  <div className="flex items-center gap-3 mb-3">
                    <ShieldAlert className="w-6 h-6 text-orange-500" aria-hidden="true" />
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
                      aria-label="Copy authorization token"
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
                <>
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
                          <tr className="border-b border-border-main bg-blue-500/5">
                            <td className="p-3 font-mono text-blue-400">limit</td>
                            <td className="p-3 text-text-muted capitalize text-xs">integer</td>
                            <td className="p-3"><span className="px-2 py-0.5 rounded text-[10px] uppercase tracking-wider font-bold bg-green-500/20 text-green-500">Optional</span></td>
                            <td className="p-3 text-text-muted font-mono text-xs">100</td>
                            <td className="p-3 text-text-muted text-xs">Max records to return. Example: <code className="font-mono">?limit=20</code></td>
                          </tr>
                          <tr className="border-b border-border-main bg-blue-500/5">
                            <td className="p-3 font-mono text-blue-400">size</td>
                            <td className="p-3 text-text-muted capitalize text-xs">integer</td>
                            <td className="p-3"><span className="px-2 py-0.5 rounded text-[10px] uppercase tracking-wider font-bold bg-green-500/20 text-green-500">Optional</span></td>
                            <td className="p-3 text-text-muted font-mono text-xs">100</td>
                            <td className="p-3 text-text-muted text-xs">Alias for <code className="font-mono">limit</code>. Example: <code className="font-mono">?size=20</code></td>
                          </tr>
                          <tr className="border-b border-border-main bg-blue-500/5">
                            <td className="p-3 font-mono text-blue-400">offset</td>
                            <td className="p-3 text-text-muted capitalize text-xs">integer</td>
                            <td className="p-3"><span className="px-2 py-0.5 rounded text-[10px] uppercase tracking-wider font-bold bg-green-500/20 text-green-500">Optional</span></td>
                            <td className="p-3 text-text-muted font-mono text-xs">0</td>
                            <td className="p-3 text-text-muted text-xs">Records to skip. Example: <code className="font-mono">?offset=40</code></td>
                          </tr>
                          <tr className="bg-blue-500/5">
                            <td className="p-3 font-mono text-blue-400">page</td>
                            <td className="p-3 text-text-muted capitalize text-xs">integer</td>
                            <td className="p-3"><span className="px-2 py-0.5 rounded text-[10px] uppercase tracking-wider font-bold bg-green-500/20 text-green-500">Optional</span></td>
                            <td className="p-3 text-text-muted font-mono text-xs">1</td>
                            <td className="p-3 text-text-muted text-xs">1-indexed page. Converts to <code className="font-mono">offset = (page-1) × limit</code>. Example: <code className="font-mono">?page=3</code></td>
                          </tr>
                        </>
                      )}
                    </tbody>
                  </table>
                  </div>
                  {currentApi.enablePagination && (
                    <div className="mt-4 bg-blue-500/10 border border-blue-500/20 rounded-xl p-4">
                      <h4 className="text-sm font-bold text-blue-400 mb-2 flex items-center gap-2">
                        <Settings2 className="w-4 h-4" /> How to use Pagination
                      </h4>
                      <div className="grid grid-cols-1 md:grid-cols-2 gap-4 text-xs text-text-muted mt-3">
                        <div className="bg-bg-panel/50 p-3 rounded-lg border border-border-main">
                          <p className="font-bold text-text-main mb-1">Style 1: limit &amp; offset (Database Style)</p>
                          <p className="mb-2 text-[11px]">Best for fetching a chunk of data and skipping records directly.</p>
                          <ul className="space-y-1 font-mono text-[11px] text-blue-300">
                            <li>?limit=20&amp;offset=0  <span className="text-text-muted font-sans ml-1">(first 20)</span></li>
                            <li>?limit=20&amp;offset=20 <span className="text-text-muted font-sans ml-1">(next 20)</span></li>
                          </ul>
                        </div>
                        <div className="bg-bg-panel/50 p-3 rounded-lg border border-border-main">
                          <p className="font-bold text-text-main mb-1">Style 2: size &amp; page (UI Table Style)</p>
                          <p className="mb-2 text-[11px]">Best for UI tables. <code className="font-mono">page</code> is 1-indexed.</p>
                          <ul className="space-y-1 font-mono text-[11px] text-orange-300">
                            <li>?size=20&amp;page=1 <span className="text-text-muted font-sans ml-1">(page 1, skips 0)</span></li>
                            <li>?size=20&amp;page=2 <span className="text-text-muted font-sans ml-1">(page 2, skips 20)</span></li>
                          </ul>
                        </div>
                      </div>
                      <p className="text-[11px] text-text-muted/80 mt-3 italic">* Priority: If mixed, <code className="font-mono">limit</code> overrides <code className="font-mono">size</code>, and <code className="font-mono">offset</code> overrides <code className="font-mono">page</code>.</p>
                    </div>
                  )}
                </>
              )}
            </div>
            
            {/* API Examples */}
            <div>
              <div className="flex items-center justify-between mb-3">
                <div className="flex items-center gap-1 bg-bg-editor/50 p-1 rounded-lg border border-border-main" role="tablist" aria-label="Code example format">
                  <button 
                    onClick={() => setActiveSpecTab('curl')}
                    className={clsx("px-3 py-1.5 rounded-md text-xs font-bold transition-colors", activeSpecTab === 'curl' ? "bg-bg-panel text-blue-400 shadow-sm border border-border-main" : "text-text-muted hover:text-text-main")}
                    role="tab"
                    aria-selected={activeSpecTab === 'curl'}
                    aria-label="cURL example"
                  >
                    cURL
                  </button>
                  <button 
                    onClick={() => setActiveSpecTab('postman')}
                    className={clsx("px-3 py-1.5 rounded-md text-xs font-bold transition-colors", activeSpecTab === 'postman' ? "bg-bg-panel text-orange-400 shadow-sm border border-border-main" : "text-text-muted hover:text-text-main")}
                    role="tab"
                    aria-selected={activeSpecTab === 'postman'}
                    aria-label="Postman HTTP example"
                  >
                    Postman (HTTP)
                  </button>
                  <button 
                    onClick={() => setActiveSpecTab('bruno')}
                    className={clsx("px-3 py-1.5 rounded-md text-xs font-bold transition-colors", activeSpecTab === 'bruno' ? "bg-bg-panel text-yellow-400 shadow-sm border border-border-main" : "text-text-muted hover:text-text-main")}
                    role="tab"
                    aria-selected={activeSpecTab === 'bruno'}
                    aria-label="Bruno API client example"
                  >
                    Bruno (.bru)
                  </button>
                </div>
                <button 
                  onClick={() => {
                    const text = activeSpecTab === 'curl' ? curlExample : activeSpecTab === 'postman' ? postmanExample : brunoExample;
                    handleCopy(text, `${activeSpecTab}-example`);
                  }}
                  className="text-xs flex items-center gap-1.5 text-blue-400 hover:text-blue-300 transition-colors"
                  aria-label="Copy code example"
                >
                  {copiedStates[`${activeSpecTab}-example`] ? <Check className="w-3.5 h-3.5" /> : <Copy className="w-3.5 h-3.5" />}
                  Copy Code
                </button>
              </div>
              <div className="relative group">
                <pre className="bg-[#0d1117] border border-border-main p-4 rounded-xl overflow-x-auto shadow-inner text-sm font-mono leading-relaxed">
                  <code className="text-gray-300 whitespace-pre">
                    {activeSpecTab === 'curl' ? curlExample : activeSpecTab === 'postman' ? postmanExample : brunoExample}
                  </code>
                </pre>
              </div>
            </div>

            {/* Response Format */}
            <div>
              <h3 className="text-sm font-bold text-text-muted uppercase tracking-wider mb-3 flex items-center gap-2">
                <FileJson className="w-4 h-4" /> Response Format
              </h3>
              <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                <div className="border border-green-500/20 bg-green-500/5 rounded-xl overflow-hidden">
                  <div className="px-4 py-2 bg-green-500/10 border-b border-green-500/20 flex items-center justify-between">
                    <span className="text-xs font-bold text-green-500 uppercase tracking-wider flex items-center gap-1.5">
                      <Check className="w-3 h-3" /> Success
                    </span>
                    <span className="font-mono text-xs text-green-500">200 OK</span>
                  </div>
                  <div className="p-4">
                    <pre className="text-xs font-mono text-text-muted">
{`[
  {
    "column1": "value1",
    "column2": "value2"
  }
]`}
                    </pre>
                  </div>
                </div>
                <div className="border border-red-500/20 bg-red-500/5 rounded-xl overflow-hidden">
                  <div className="px-4 py-2 bg-red-500/10 border-b border-red-500/20 flex items-center justify-between">
                    <span className="text-xs font-bold text-red-500 uppercase tracking-wider flex items-center gap-1.5">
                      <AlertCircle className="w-3 h-3" /> Error
                    </span>
                    <span className="font-mono text-xs text-red-500">4xx / 5xx</span>
                  </div>
                  <div className="p-4">
                    <pre className="text-xs font-mono text-text-muted">
{`{
  "error": "Error message description"
}`}
                    </pre>
                  </div>
                </div>
              </div>
            </div>
            
          </div>
        </div>
      </div>
    );
  }

  // ─────────────────────────────────────────────
  // EDIT VIEW
  // ─────────────────────────────────────────────
  if (viewMode === 'edit' && currentApi) {
    const paramsList = detectParams(currentApi.sqlQuery);
    const getError = (field: string) => validationErrors.find(e => e.field === field);
    
    return (
      <div className="h-full flex flex-col overflow-hidden bg-bg-editor">
        {/* TOP BAR */}
        <div className="bg-bg-panel border-b border-border-main p-3 flex items-center justify-between shrink-0 shadow-sm z-10">
          <div className="flex items-center gap-4 flex-1">
            <button 
              onClick={handleBackToList} 
              className="p-2 hover:bg-bg-hover rounded-lg text-text-muted transition-colors relative"
              aria-label="Back to API list"
            >
              <ArrowLeft className="w-5 h-5" />
              {isDirty && (
                <span className="absolute top-0.5 right-0.5 w-2 h-2 bg-yellow-500 rounded-full animate-pulse" title="Unsaved changes"></span>
              )}
            </button>
            <div className="h-6 w-px bg-border-main hidden md:block"></div>
            
            {/* Live URL Preview */}
            <div className="hidden md:flex items-center gap-0 text-sm font-mono flex-1 max-w-3xl overflow-hidden rounded-md border border-border-main shadow-inner opacity-80 hover:opacity-100 transition-opacity">
              <span className={clsx("px-3 py-1.5 font-bold tracking-wide shrink-0 flex items-center gap-1", getMethodBadgeClass(currentApi.method))}>
                <span className="w-1.5 h-1.5 rounded-full bg-current" aria-hidden="true"></span>
                {currentApi.method}
              </span>
              <span className="px-3 py-1.5 bg-bg-editor text-text-muted truncate flex-1 flex items-center gap-1">
                <span className="opacity-50">{window.location.origin}/api/data</span>
                <span className="text-blue-400 font-bold">{currentApi.endpointPath || <span className="opacity-30 italic">/your-path</span>}</span>
                {paramsList.length > 0 && currentApi.method === 'GET' && (
                  <span className="text-purple-400">?{paramsList.map(p => `${p}={${p}}`).join('&')}</span>
                )}
              </span>
            </div>

            {/* Dirty state indicator for wider screens */}
            {isDirty && (
              <div className="hidden md:flex items-center gap-1.5 text-[10px] font-bold text-yellow-500 bg-yellow-500/10 px-2 py-1 rounded-full border border-yellow-500/20 animate-in fade-in">
                <span className="w-1.5 h-1.5 bg-yellow-500 rounded-full"></span>
                Unsaved
              </div>
            )}
          </div>
          <div className="flex items-center gap-3">
            <button 
              onClick={handleSave}
              disabled={isSaving}
              className="px-5 py-2 bg-blue-600 hover:bg-blue-500 disabled:opacity-50 disabled:cursor-not-allowed text-white rounded-lg flex items-center gap-2 text-sm font-semibold transition-colors shadow-lg shadow-blue-500/20 active:scale-[0.97]"
              aria-label="Save API endpoint"
            >
              {isSaving ? (
                <Loader2 className="w-4 h-4 animate-spin" />
              ) : (
                <Save className="w-4 h-4" />
              )}
              {isSaving ? 'Saving...' : 'Save API'}
              <kbd className="hidden md:inline-flex ml-1 px-1.5 py-0.5 bg-blue-800/50 rounded text-[9px] font-mono border border-blue-400/30">⌘S</kbd>
            </button>
          </div>
        </div>

        {/* MAIN CONTENT AREA - Using grid rows for proper layout */}
        <div className="flex-1 overflow-hidden flex flex-col relative">
          <div className="flex-1 min-h-0 flex overflow-hidden">
            
            {/* LEFT: SQL Editor */}
            <div className="flex-1 lg:w-3/5 flex flex-col border-r border-border-main min-w-0">
              <div className="bg-bg-panel border-b border-border-main p-3 px-4 flex justify-between items-center shrink-0">
                <div className="flex items-center gap-2 text-sm font-bold text-text-main">
                  <Database className="w-4 h-4 text-blue-500" /> SQL Query
                </div>
                <div className="flex items-center gap-2">
                  <div className="relative">
                    <button
                      onClick={() => setShowTemplates(!showTemplates)}
                      className="text-[11px] font-medium text-purple-400 bg-purple-500/10 hover:bg-purple-500/20 px-2.5 py-1 rounded-full border border-purple-500/20 flex items-center gap-1.5 transition-colors"
                      aria-label="SQL templates"
                    >
                      <BookTemplate className="w-3 h-3" /> Templates
                    </button>
                    {showTemplates && (
                      <div className="absolute top-full right-0 mt-1 w-64 bg-bg-panel border border-border-main rounded-xl shadow-2xl z-30 overflow-hidden">
                        <div className="p-2 border-b border-border-main/50">
                          <p className="text-[10px] uppercase font-bold text-text-muted tracking-wider px-2">Query Templates</p>
                        </div>
                        <div className="p-1 max-h-64 overflow-y-auto">
                          {QUERY_TEMPLATES.map((template, i) => (
                            <button
                              key={i}
                              onClick={() => applyTemplate(template)}
                              className="w-full text-left px-3 py-2.5 hover:bg-bg-hover rounded-lg transition-colors flex items-center gap-3 group"
                            >
                              <template.icon className="w-4 h-4 text-text-muted group-hover:text-blue-400 shrink-0" />
                              <div>
                                <span className="text-sm font-medium text-text-main">{template.name}</span>
                                <p className="text-[10px] font-mono text-text-muted truncate max-w-[180px]">{template.sql.split('\n')[0]}</p>
                              </div>
                            </button>
                          ))}
                        </div>
                        <div className="p-2 border-t border-border-main/50 bg-bg-editor/30">
                          <p className="text-[9px] text-text-muted text-center">Click to replace current query</p>
                        </div>
                      </div>
                    )}
                  </div>
                  <div className="text-[11px] font-medium text-blue-400 bg-blue-500/10 px-2.5 py-1 rounded-full border border-blue-500/20">
                    Use <code className="font-mono font-bold text-blue-300">:param</code> for variables
                  </div>
                </div>
              </div>
              <div className="flex-1 min-h-0 relative bg-bg-editor">
                <SQLEditor 
                  key={currentApi.connectionId}
                  value={currentApi.sqlQuery}
                  onChange={val => setCurrentApi({...currentApi, sqlQuery: val})}
                  connectionId={currentApi.connectionId}
                />
              </div>
            </div>

            {/* RIGHT: Config Panel */}
            <div className="flex-1 lg:w-2/5 flex flex-col bg-bg-panel overflow-y-auto min-w-0">
              <div className="p-5 space-y-6">
                
                {/* Validation Errors Banner */}
                {validationErrors.length > 0 && (
                  <div className="bg-red-500/10 border border-red-500/20 rounded-xl p-3 animate-in fade-in">
                    <div className="flex items-center gap-2 mb-2">
                      <AlertCircle className="w-4 h-4 text-red-500" />
                      <span className="text-xs font-bold text-red-500 uppercase tracking-wider">Please fix {validationErrors.length} error(s)</span>
                    </div>
                    <ul className="space-y-1">
                      {validationErrors.map((err, i) => (
                        <li key={i} className="text-xs text-red-400 flex items-center gap-2 pl-6">
                          <span className="w-1 h-1 bg-red-500 rounded-full" /> {err.message}
                        </li>
                      ))}
                    </ul>
                  </div>
                )}
                
                {/* Basic Info Section */}
                <section>
                  <h3 className="text-xs font-bold text-text-muted uppercase tracking-wider mb-4 flex items-center gap-2">
                    <Settings2 className="w-4 h-4" /> Endpoint Configuration
                  </h3>
                  <div className="space-y-4">
                    <div>
                      <label className="block text-xs font-medium text-text-muted mb-1.5 flex items-center gap-1.5">
                        API Name
                        {getError('name') && <span className="text-red-500 text-[10px]">— {getError('name')?.message}</span>}
                      </label>
                      <input 
                        className={clsx("w-full bg-bg-editor border rounded-lg p-2.5 text-sm focus:ring-1 outline-none transition-all shadow-inner",
                          getError('name') 
                            ? "border-red-500 focus:border-red-500 focus:ring-red-500" 
                            : "border-border-main focus:border-blue-500 focus:ring-blue-500"
                        )}
                        value={currentApi.name}
                        onChange={e => setCurrentApi({...currentApi, name: e.target.value})}
                        placeholder="e.g. Get User by ID"
                        aria-label="API name"
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
                            aria-label="HTTP method"
                          >
                            {['GET','POST','PUT','PATCH','DELETE'].map(m => (
                              <option key={m} value={m}>{m}</option>
                            ))}
                          </select>
                          <ChevronDown className="w-4 h-4 absolute right-3 top-3 text-text-muted pointer-events-none" />
                        </div>
                      </div>
                      <div className="flex-1">
                        <label className="block text-xs font-medium text-text-muted mb-1.5 flex items-center gap-1.5">
                          Endpoint Path
                          {getError('endpointPath') && <span className="text-red-500 text-[10px]">— {getError('endpointPath')?.message}</span>}
                        </label>
                        <div className={clsx("flex items-center shadow-inner rounded-lg border transition-all overflow-hidden",
                          getError('endpointPath') 
                            ? "border-red-500 focus-within:border-red-500 focus-within:ring-1 focus-within:ring-red-500" 
                            : "border-border-main focus-within:border-blue-500 focus-within:ring-1 focus-within:ring-blue-500"
                        )}>
                          <span className="bg-bg-panel px-3 py-2.5 text-sm text-text-muted/70 font-mono border-r border-border-main">
                            /api/data
                          </span>
                          <input 
                            className="w-full bg-bg-editor p-2.5 text-sm outline-none font-mono text-blue-400"
                            value={currentApi.endpointPath}
                            onChange={e => {
                              let val = e.target.value;
                              if (val && !val.startsWith('/')) val = '/' + val;
                              setCurrentApi({...currentApi, endpointPath: val});
                            }}
                            placeholder="/users"
                            aria-label="Endpoint path"
                          />
                        </div>
                      </div>
                    </div>

                    <div>
                      <label className="block text-xs font-medium text-text-muted mb-1.5 flex items-center gap-1.5">
                        Database Connection
                        {getError('connectionId') && <span className="text-red-500 text-[10px]">— {getError('connectionId')?.message}</span>}
                      </label>
                      <div className={clsx("relative",
                        getError('connectionId') && "text-red-500"
                      )}>
                        <select 
                          className={clsx("w-full bg-bg-editor border rounded-lg p-2.5 text-sm focus:ring-1 outline-none transition-all shadow-inner appearance-none",
                            getError('connectionId') 
                              ? "border-red-500 focus:border-red-500 focus:ring-red-500" 
                              : "border-border-main focus:border-blue-500 focus:ring-blue-500"
                          )}
                          value={currentApi.connectionId}
                          onChange={e => setCurrentApi({...currentApi, connectionId: e.target.value})}
                          aria-label="Database connection"
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

                {/* Parameter Configuration - COMPACT TABLE LAYOUT */}
                <section>
                  <div className="flex items-center justify-between mb-3">
                    <h3 className="text-xs font-bold text-text-muted uppercase tracking-wider flex items-center gap-2">
                      <Database className="w-4 h-4" /> Parameters
                    </h3>
                    <div className={clsx("text-[10px] font-bold px-2 py-0.5 rounded-full transition-all", 
                      paramCount > 0 ? "bg-blue-500/20 text-blue-400 border border-blue-500/30" : "bg-bg-editor text-text-muted"
                    )}>
                      {paramCount} Detected
                    </div>
                  </div>
                  
                  {paramsList.length === 0 ? (
                    <div className="bg-bg-editor/50 border border-border-main border-dashed rounded-xl p-6 text-center">
                      <p className="text-xs text-text-muted">
                        Type <code className="font-mono text-blue-400 bg-blue-500/10 px-1 rounded">:param_name</code> in the SQL editor to automatically detect parameters.
                      </p>
                    </div>
                  ) : (
                    <div className="border border-border-main rounded-xl overflow-hidden shadow-sm">
                      <table className="w-full text-left">
                        <thead className="bg-bg-editor/80">
                          <tr>
                            <th className="p-2.5 border-b border-border-main font-semibold text-text-main text-[10px] uppercase tracking-wider">Parameter</th>
                            <th className="p-2.5 border-b border-border-main font-semibold text-text-main text-[10px] uppercase tracking-wider">Type</th>
                            <th className="p-2.5 border-b border-border-main font-semibold text-text-main text-[10px] uppercase tracking-wider text-center">Req</th>
                            <th className="p-2.5 border-b border-border-main font-semibold text-text-main text-[10px] uppercase tracking-wider">Default</th>
                            <th className="p-2.5 border-b border-border-main font-semibold text-text-main text-[10px] uppercase tracking-wider">Description</th>
                          </tr>
                        </thead>
                        <tbody>
                          {paramsList.map(p => {
                            const meta = parameterMeta.find(m => m.name === p) || {
                              name: p, type: 'string', required: true, defaultValue: '', description: ''
                            };
                            return (
                              <tr key={p} className="border-b border-border-main last:border-0 hover:bg-bg-hover/30 transition-colors">
                                <td className="p-2.5">
                                  <span className="font-mono text-blue-400 bg-blue-500/10 px-1.5 py-0.5 rounded text-[11px]">:{p}</span>
                                </td>
                                <td className="p-2.5">
                                  <div className="relative w-24">
                                    <select 
                                      className="w-full bg-bg-panel border border-border-main rounded text-[11px] p-1 outline-none focus:border-blue-500 appearance-none shadow-inner"
                                      value={meta.type}
                                      onChange={e => setParameterMeta(prev => prev.map(m => m.name === p ? { ...m, type: e.target.value as any } : m))}
                                      aria-label={`Type for ${p}`}
                                    >
                                      <option value="string">String</option>
                                      <option value="integer">Integer</option>
                                      <option value="number">Number</option>
                                      <option value="boolean">Boolean</option>
                                      <option value="date">Date</option>
                                    </select>
                                    <ChevronDown className="w-2.5 h-2.5 absolute right-1.5 top-1.5 text-text-muted pointer-events-none" />
                                  </div>
                                </td>
                                <td className="p-2.5 text-center">
                                  <label className="inline-flex items-center cursor-pointer" aria-label={`${p} required`}>
                                    <input 
                                      type="checkbox" 
                                      className="sr-only peer"
                                      checked={meta.required}
                                      onChange={e => setParameterMeta(prev => prev.map(m => m.name === p ? { ...m, required: e.target.checked } : m))}
                                    />
                                    <div className={clsx(
                                      "w-7 h-3.5 rounded-full relative transition-colors duration-200 peer-focus-visible:ring-2 peer-focus-visible:ring-blue-500",
                                      meta.required ? "bg-blue-500" : "bg-bg-panel border border-border-main"
                                    )}>
                                      <div className={clsx(
                                        "w-2.5 h-2.5 rounded-full absolute top-0.5 transition-all duration-200",
                                        meta.required ? "bg-white left-4" : "bg-text-muted left-0.5"
                                      )} />
                                    </div>
                                  </label>
                                </td>
                                <td className="p-2.5">
                                  <input 
                                    type="text"
                                    className="w-20 bg-bg-panel border border-border-main rounded text-[11px] p-1 outline-none focus:border-blue-500 shadow-inner"
                                    placeholder={meta.required ? "—" : "empty"}
                                    disabled={meta.required}
                                    value={meta.defaultValue}
                                    onChange={e => setParameterMeta(prev => prev.map(m => m.name === p ? { ...m, defaultValue: e.target.value } : m))}
                                    aria-label={`Default value for ${p}`}
                                  />
                                </td>
                                <td className="p-2.5">
                                  <input 
                                    type="text"
                                    className="w-full min-w-[80px] bg-bg-panel border border-border-main rounded text-[11px] p-1 outline-none focus:border-blue-500 shadow-inner"
                                    placeholder="Description..."
                                    value={meta.description}
                                    onChange={e => setParameterMeta(prev => prev.map(m => m.name === p ? { ...m, description: e.target.value } : m))}
                                    aria-label={`Description for ${p}`}
                                  />
                                </td>
                              </tr>
                            );
                          })}
                        </tbody>
                      </table>
                    </div>
                  )}
                </section>

                {/* Security & Features */}
                <section>
                  <h3 className="text-xs font-bold text-text-muted uppercase tracking-wider mb-4 flex items-center gap-2">
                    <ShieldCheck className="w-4 h-4" /> Security &amp; Features
                  </h3>
                  <div className="space-y-3">
                    <div className={clsx("border rounded-xl p-4 transition-colors", currentApi.isPublic ? "bg-green-500/5 border-green-500/20" : "bg-bg-editor border-border-main")}>
                      <label className="flex items-start gap-3 cursor-pointer">
                        <input 
                          type="checkbox" 
                          className="mt-1 w-4 h-4 rounded border-border-main text-green-500 focus:ring-green-500 focus:ring-offset-bg-panel bg-bg-panel"
                          checked={currentApi.isPublic} 
                          onChange={e => setCurrentApi({...currentApi, isPublic: e.target.checked})} 
                          aria-label="Public API toggle"
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
                          aria-label="Auth token"
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
                          aria-label="Enable auto-pagination"
                        />
                        <div>
                          <span className={clsx("text-sm font-bold", currentApi.enablePagination ? "text-blue-500" : "text-text-main")}>Enable Auto-Pagination</span>
                          <p className="text-xs text-text-muted mt-0.5">Automatically appends LIMIT and OFFSET based on <code className="font-mono">limit</code> and <code className="font-mono">page</code> request parameters.</p>
                        </div>
                      </label>
                    </div>
                  </div>
                </section>
              </div>
            </div>
          </div>
          
          {/* BOTTOM PANEL: Test Console — using flex-1 overflow instead of absolute */}
          <div className={clsx(
            "border-t border-border-main bg-[#0f141f] shadow-2xl transition-all duration-300 ease-in-out flex flex-col shrink-0",
            isTestConsoleOpen ? "flex-1 min-h-[250px] max-h-[50%]" : "h-11"
          )}>
            {/* Header */}
            <div 
              className="h-11 border-b border-border-main/50 flex items-center justify-between px-4 cursor-pointer hover:bg-white/[0.03] transition-colors shrink-0"
              onClick={() => setIsTestConsoleOpen(!isTestConsoleOpen)}
              role="button"
              tabIndex={0}
              aria-label={isTestConsoleOpen ? "Close test console" : "Open test console"}
              onKeyDown={e => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); setIsTestConsoleOpen(!isTestConsoleOpen); }}}
            >
              <div className="flex items-center gap-2.5">
                <SquareTerminal className="w-4 h-4 text-green-400" />
                <span className="text-sm font-bold text-white">Test Console</span>
                {isTestConsoleOpen && paramCount > 0 && (
                  <span className="text-[10px] text-blue-400 bg-blue-500/10 px-1.5 py-0.5 rounded-full border border-blue-500/20">{paramCount} param{paramCount > 1 ? 's' : ''}</span>
                )}
              </div>
              <div className="flex items-center gap-3">
                {isTestConsoleOpen && (
                  <button 
                    onClick={(e) => { e.stopPropagation(); handleTest(); }}
                    disabled={isTesting}
                    className="px-4 py-1.5 bg-green-600 hover:bg-green-500 disabled:opacity-50 disabled:cursor-not-allowed text-white rounded-md text-xs font-bold flex items-center gap-2 transition-colors shadow-lg shadow-green-600/20 active:scale-[0.97]"
                    aria-label="Run test query"
                  >
                    {isTesting ? (
                      <Loader2 className="w-3 h-3 animate-spin" />
                    ) : (
                      <Play className="w-3 h-3" />
                    )}
                    {isTesting ? 'Running...' : 'Run Test'}
                    <kbd className="hidden md:inline-flex ml-1 px-1 py-0.5 bg-green-800/40 rounded text-[8px] font-mono border border-green-400/30">⌘⏎</kbd>
                  </button>
                )}
                <div className="text-slate-400 hover:text-white transition-colors p-1">
                  {isTestConsoleOpen ? <ChevronDown className="w-4 h-4" /> : <ChevronUp className="w-4 h-4" />}
                </div>
              </div>
            </div>
            
            {/* Body */}
            {isTestConsoleOpen && (
              <div className="flex-1 flex overflow-hidden min-h-0">
                {/* Test Inputs */}
                <div className="w-1/4 min-w-[160px] border-r border-border-main/50 p-3 overflow-y-auto bg-black/20">
                  <h4 className="text-[10px] uppercase font-bold text-slate-400 tracking-wider mb-3">Variables</h4>
                  {paramsList.length === 0 ? (
                    <p className="text-xs text-slate-400 italic">No variables required.</p>
                  ) : (
                    <div className="space-y-2.5">
                      {paramsList.map(p => (
                        <div key={p}>
                          <label className="block text-[10px] font-mono text-blue-400 mb-0.5">:{p}</label>
                          <input 
                            type="text"
                            className="w-full bg-[#1e293b] border border-[#334155] rounded px-2 py-1.5 text-[11px] outline-none focus:border-blue-500 text-white shadow-inner font-mono transition-colors"
                            placeholder="value..."
                            value={testParams[p] || ''}
                            onChange={e => setTestParams({...testParams, [p]: e.target.value})}
                            aria-label={`Test value for ${p}`}
                          />
                        </div>
                      ))}
                    </div>
                  )}
                  {currentApi.enablePagination && (
                    <div className="mt-3 pt-3 border-t border-border-main/30 space-y-2.5">
                      <p className="text-[9px] uppercase font-bold text-purple-400 tracking-wider">Pagination</p>
                      <div>
                        <label className="block text-[10px] font-mono text-purple-400 mb-0.5">limit</label>
                        <input 
                          type="text"
                          className="w-full bg-[#1e293b] border border-[#334155] rounded px-2 py-1.5 text-[11px] outline-none focus:border-purple-500 text-white shadow-inner font-mono transition-colors"
                          placeholder="e.g. 20"
                          value={testParams['limit'] || ''}
                          onChange={e => setTestParams({...testParams, 'limit': e.target.value})}
                          aria-label="Pagination limit"
                        />
                      </div>
                     <div>
                        <label className="block text-[10px] font-mono text-purple-400 mb-0.5">offset</label>
                        <input 
                          type="text"
                          className="w-full bg-[#1e293b] border border-[#334155] rounded px-2 py-1.5 text-[11px] outline-none focus:border-purple-500 text-white shadow-inner font-mono transition-colors"
                          placeholder="e.g. 0"
                          value={testParams['offset'] || ''}
                          onChange={e => setTestParams({...testParams, 'offset': e.target.value})}
                          aria-label="Pagination offset"
                        />
                      </div>
                    </div>
                  )}
                  {/* Clear params button */}
                  {Object.keys(testParams).length > 0 && (
                    <button
                      onClick={() => setTestParams({})}
                      className="mt-3 text-[10px] text-text-muted hover:text-red-400 flex items-center gap-1 transition-colors"
                      aria-label="Clear test parameters"
                    >
                      <Eraser className="w-3 h-3" /> Clear
                    </button>
                  )}
                </div>
                
                {/* Response Viewer */}
                <div className="flex-1 flex flex-col bg-[#0b0f19] relative min-w-0">
                  <div className="h-8 border-b border-border-main/30 flex items-center justify-between px-3 bg-black/40 shrink-0">
                    <div className="flex items-center gap-2">
                      <span className="text-[10px] uppercase font-bold text-slate-400 tracking-wider">Response</span>
                      {/* Pretty-print toggle */}
                      {testResult && !testResult.error && (
                        <button
                          onClick={() => setIsPrettyPrint(!isPrettyPrint)}
                          className="text-[9px] text-blue-400 hover:text-blue-300 flex items-center gap-1 bg-blue-500/10 px-1.5 py-0.5 rounded transition-colors"
                          aria-label="Toggle JSON formatting"
                        >
                          <FileCode className="w-2.5 h-2.5" />
                          {isPrettyPrint ? 'Pretty' : 'Minified'}
                        </button>
                      )}
                    </div>
                    <div className="flex items-center gap-2">
                      {testResult && (
                        <>
                          <button
                            onClick={() => {
                              const content = testResult.data ? JSON.stringify(testResult.data, null, isPrettyPrint ? 2 : 0) : testResult.error;
                              handleCopy(content, 'response');
                            }}
                            className="text-[9px] text-slate-400 hover:text-white flex items-center gap-1 transition-colors"
                            aria-label="Copy response"
                          >
                            <Copy className="w-2.5 h-2.5" /> Copy
                          </button>
                          <span className={clsx("text-[10px] font-bold px-1.5 py-0.5 rounded-full", 
                            testResult.status >= 400 ? "bg-red-500/20 text-red-400 border border-red-500/20" : "bg-green-500/20 text-green-400 border border-green-500/20"
                          )}>
                            {testResult.status}
                            {testResult.status === 200 ? ' OK' : ''}
                          </span>
                        </>
                      )}
                    </div>
                  </div>
                  <div className="flex-1 overflow-auto p-3 relative">
                    {isTesting && (
                      <div className="absolute inset-0 bg-[#0b0f19]/80 backdrop-blur-sm flex items-center justify-center z-10">
                        <div className="flex flex-col items-center gap-2">
                          <div className="w-7 h-7 border-[3px] border-blue-500 border-t-transparent rounded-full animate-spin"></div>
                          <span className="text-[10px] text-blue-400 font-mono animate-pulse">Executing query...</span>
                        </div>
                      </div>
                    )}
                    {testResult ? (
                      <>
                        <pre className={clsx("font-mono text-[11px] leading-relaxed whitespace-pre-wrap break-all", 
                          testResult.status >= 400 ? "text-red-300" : "text-green-300"
                        )}>
                          {testResult.data 
                            ? JSON.stringify(testResult.data, null, isPrettyPrint ? 2 : 0)
                            : typeof testResult.error === 'string' 
                              ? testResult.error 
                              : JSON.stringify(testResult.error, null, 2)
                          }
                        </pre>
                        {testResult.detail && (
                          <details className="mt-3 border border-border-main/30 rounded-lg overflow-hidden">
                            <summary className="text-[10px] text-text-muted cursor-pointer px-2 py-1 hover:bg-white/5 transition-colors">Error details</summary>
                            <pre className="p-2 text-[10px] font-mono text-red-400/70 bg-black/40 whitespace-pre-wrap">{testResult.detail}</pre>
                          </details>
                        )}
                      </>
                    ) : (
                      <div className="h-full flex items-center justify-center">
                        <div className="text-center">
                          <Bug className="w-6 h-6 text-text-muted/20 mx-auto mb-2" />
                          <p className="text-text-muted/30 text-xs italic font-mono">// Response will appear here</p>
                          <p className="text-text-muted/20 text-[10px] mt-1">Enter test values and click Run Test</p>
                        </div>
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
