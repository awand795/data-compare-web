// @ts-nocheck
import React, { useState, useEffect } from 'react';
import { useAppStore, type Connection } from '../store/useAppStore';
import { Database, Plus, Trash2, CheckCircle, XCircle, Server, Plug, ChevronRight, ChevronDown, Table as TableIcon, Loader2, Download, Folder, Eye, EyeOff } from 'lucide-react';
import axios from 'axios';

export type TableInfo = {
  name: string;
  type: string;
};

export const ConnectionPanel: React.FC = () => {
  const { 
    connections, 
    addConnection, 
    removeConnection,
    setAppMode,
    setExplorerConnectionId,
    setExplorerTableName,
    explorerConnectionId,
    explorerTableName,
    showAlert,
    addToast
  } = useAppStore();
  
  const [isOpen, setIsOpen] = useState(false);
  const [formData, setFormData] = useState<Partial<Connection>>({
    type: 'postgresql',
    port: 5432
  });
  const [testStatus, setTestStatus] = useState<'idle' | 'testing' | 'success' | 'error'>('idle');
  const [schemas, setSchemas] = useState<string[]>([]);
  const [loadingSchemas, setLoadingSchemas] = useState(false);
  const [showPassword, setShowPassword] = useState(false);

  // Tree View State
  const [expandedConns, setExpandedConns] = useState<Record<string, boolean>>({});
  const [expandedFolders, setExpandedFolders] = useState<Record<string, boolean>>({});
  const [connTables, setConnTables] = useState<Record<string, TableInfo[]>>({});
  const [loadingTables, setLoadingTables] = useState<Record<string, boolean>>({});

  const handleChange = (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) => {
    setFormData({ ...formData, [e.target.name]: e.target.value });
  };

  const handleTest = async () => {
    setTestStatus('testing');
    try {
      const res = await axios.post('/api/test-connection', formData);
      setTestStatus(res.data.success ? 'success' : 'error');
    } finally {
      setTestStatus(prev => prev === 'testing' ? 'error' : prev);
    }
  };

  const handleLoadSchemas = async () => {
    if (!formData.host || !formData.database || !formData.username) return;
    setLoadingSchemas(true);
    try {
      const res = await axios.post('/api/schemas', formData);
      setSchemas(res.data);
      if (res.data.length > 0 && !formData.schema) {
        setFormData(prev => ({ ...prev, schema: res.data[0] }));
      }
    } catch (e) {
      console.error("Failed to load schemas", e);
    } finally {
      setLoadingSchemas(false);
    }
  };

  // Auto-load schemas when credentials are provided
  useEffect(() => {
    if ((formData.type === 'postgresql' || formData.type === 'sqlserver') && 
        formData.host && formData.database && formData.username && formData.password) {
      
      const timer = setTimeout(() => {
        handleLoadSchemas();
      }, 1000); // 1s debounce
      
      return () => clearTimeout(timer);
    }
  }, [formData.host, formData.database, formData.username, formData.password, formData.type]);

  const handleAdd = async () => {
    if (formData.name && formData.host && formData.database) {
      const newConn = { ...formData, id: Date.now().toString() } as Connection;
      try {
        await axios.post('/api/connections', newConn);
        addConnection(newConn);
        setIsOpen(false);
        setTestStatus('idle');
        setFormData({ type: 'postgresql', port: 5432 });
      } catch (err) {
        console.error('Failed to save connection:', err);
        alert('Failed to save connection');
      }
    }
  };

  const handleDelete = (e: React.MouseEvent, id: string, connName: string) => {
    e.stopPropagation();
    showAlert({
      type: 'error',
      title: 'Delete Connection?',
      message: `Are you sure you want to delete "${connName}"? This action cannot be undone.`,
      confirmLabel: 'Delete',
      cancelLabel: 'Cancel',
      onConfirm: async () => {
        try {
          await axios.delete(`/api/connections/${id}`);
          removeConnection(id);
          addToast({ type: 'success', title: 'Deleted', message: 'Connection removed successfully.' });
        } catch (err) {
          console.error('Failed to delete connection:', err);
          addToast({ type: 'error', title: 'Delete Failed', message: 'Failed to delete connection.' });
        }
      },
    });
  };

  const toggleConn = async (c: Connection) => {
    const isExpanded = !expandedConns[c.id];
    setExpandedConns(prev => ({ ...prev, [c.id]: isExpanded }));
    
    if (isExpanded && !connTables[c.id]) {
      setLoadingTables(prev => ({ ...prev, [c.id]: true }));
      try {
        const res = await axios.post('/api/tables', c);
        setConnTables(prev => ({ ...prev, [c.id]: res.data }));
      } catch (e) {
        console.error(e);
      } finally {
        setLoadingTables(prev => ({ ...prev, [c.id]: false }));
      }
    }
  };

  const toggleFolder = (connId: string, folderName: string) => {
    const key = `${connId}_${folderName}`;
    setExpandedFolders(prev => ({ ...prev, [key]: !prev[key] }));
  };

  const handleTableClick = (c: Connection, table: string) => {
    setExplorerConnectionId(c.id);
    setExplorerTableName(table);
    setAppMode('explorer');
  };

  const dbIcons: Record<string, string> = {
    postgresql: '🐘',
    mysql: '🐬',
    mariadb: '🔷',
    sqlserver: '🟦',
    clickhouse: '⚡',
  };

  return (
    <div className="flex flex-col h-full bg-bg-panel">
      {/* Header */}
      <div className="px-3 py-2.5 border-b border-border-main flex items-center justify-between">
        <span className="text-[10px] font-semibold text-text-muted uppercase tracking-wider">Database Explorer</span>
        <button 
          onClick={() => setIsOpen(!isOpen)}
          className="p-1 rounded hover:bg-bg-hover text-text-muted hover:text-blue-500 transition-colors"
          title="New Connection"
        >
          <Plus className="w-3.5 h-3.5" />
        </button>
      </div>

      {/* New connection form */}
      {isOpen && (
        <div className="m-2 p-3 border border-blue-500/30 rounded-lg bg-blue-500/5 flex flex-col gap-2 backdrop-blur-sm">
          <div className="text-[10px] font-bold text-blue-500 dark:text-blue-400 uppercase tracking-wider flex items-center gap-1 mb-1">
            <Plug className="w-3 h-3" /> New Connection
          </div>
          <input name="name" onChange={handleChange} className="w-full px-2.5 py-1.5 bg-bg-input border border-border-input rounded text-xs text-text-input placeholder-slate-500 focus:border-blue-500 focus:ring-1 focus:ring-blue-500/30 outline-none" placeholder="Connection Name" />
          <select name="type" value={formData.type} onChange={handleChange} className="w-full px-2.5 py-1.5 bg-bg-input border border-border-input rounded text-xs text-text-input outline-none focus:border-blue-500">
            <option value="postgresql">🐘 PostgreSQL</option>
            <option value="mysql">🐬 MySQL</option>
            <option value="mariadb">🔷 MariaDB</option>
            <option value="sqlserver">🟦 SQL Server</option>
            <option value="clickhouse">⚡ ClickHouse</option>
          </select>
          <div className="grid grid-cols-3 gap-1.5">
            <input name="host" onChange={handleChange} className="col-span-2 px-2.5 py-1.5 bg-bg-input border border-border-input rounded text-xs text-text-input placeholder-slate-500 focus:border-blue-500 outline-none" placeholder="Host" />
            <input name="port" type="number" onChange={handleChange} value={formData.port} className="px-2.5 py-1.5 bg-bg-input border border-border-input rounded text-xs text-text-input placeholder-slate-500 focus:border-blue-500 outline-none" placeholder="Port" />
          </div>
          {formData.type === 'postgresql' || formData.type === 'sqlserver' ? (
            <div className="grid grid-cols-2 gap-1.5">
              <input name="database" onChange={handleChange} className="px-2.5 py-1.5 bg-bg-input border border-border-input rounded text-xs text-text-input placeholder-slate-500 focus:border-blue-500 outline-none" placeholder="Database" />
              <div className="flex gap-1">
                {schemas.length > 0 ? (
                  <select name="schema" value={formData.schema || ''} onChange={handleChange} className="flex-1 px-2.5 py-1.5 bg-bg-input border border-border-input rounded text-xs text-text-input outline-none focus:border-blue-500 min-w-0">
                    <option value="">Select Schema...</option>
                    {schemas.map(s => <option key={s} value={s}>{s}</option>)}
                  </select>
                ) : (
                  <input name="schema" onChange={handleChange} value={formData.schema || ''} className="flex-1 px-2.5 py-1.5 bg-bg-input border border-border-input rounded text-xs text-text-input placeholder-slate-500 focus:border-blue-500 outline-none min-w-0" placeholder="Schema (Opt)" title="Optional schema (e.g., public, dbo)" />
                )}
                <button type="button" onClick={handleLoadSchemas} disabled={loadingSchemas} className="px-2 py-1.5 bg-blue-500/10 hover:bg-blue-500/20 text-blue-500 rounded border border-blue-500/20 text-xs font-medium flex-shrink-0" title="Load Schemas">
                  {loadingSchemas ? <Loader2 className="w-3 h-3 animate-spin" /> : <Download className="w-3 h-3" />}
                </button>
              </div>
            </div>
          ) : (
            <input name="database" onChange={handleChange} className="w-full px-2.5 py-1.5 bg-bg-input border border-border-input rounded text-xs text-text-input placeholder-slate-500 focus:border-blue-500 outline-none" placeholder="Database" />
          )}
          <div className="grid grid-cols-2 gap-1.5">
            <input name="username" onChange={handleChange} className="px-2.5 py-1.5 bg-bg-input border border-border-input rounded text-xs text-text-input placeholder-slate-500 focus:border-blue-500 outline-none" placeholder="Username" />
            <div className="relative">
              <input name="password" type={showPassword ? "text" : "password"} onChange={handleChange} className="w-full px-2.5 py-1.5 pr-8 bg-bg-input border border-border-input rounded text-xs text-text-input placeholder-slate-500 focus:border-blue-500 outline-none" placeholder="Password" />
              <button type="button" onClick={() => setShowPassword(!showPassword)} className="absolute inset-y-0 right-0 pr-2 flex items-center text-text-muted hover:text-text-main" tabIndex={-1}>
                {showPassword ? <EyeOff className="w-3.5 h-3.5" /> : <Eye className="w-3.5 h-3.5" />}
              </button>
            </div>
          </div>
          
          <div className="flex justify-between mt-1 gap-2">
            <button onClick={handleTest} disabled={testStatus === 'testing'} className="flex-1 px-2 py-1.5 text-xs font-semibold bg-teal-600 hover:bg-teal-500 text-white rounded shadow shadow-teal-500/20 flex items-center justify-center gap-1.5 transition-colors disabled:opacity-50">
              {testStatus === 'testing' ? (
                <span className="w-3 h-3 border-2 border-white border-t-transparent rounded-full animate-spin" />
              ) : testStatus === 'success' ? (
                <CheckCircle className="w-3 h-3 text-emerald-300" />
              ) : testStatus === 'error' ? (
                <XCircle className="w-3 h-3 text-red-300" />
              ) : (
                <Server className="w-3 h-3" />
              )}
              {testStatus === 'testing' ? 'Loading...' : testStatus === 'success' ? '✓ Connected' : 'Test Connection'}
            </button>
            <button onClick={handleAdd} className="flex-1 px-3 py-1.5 bg-blue-600 hover:bg-blue-500 text-white rounded text-xs font-semibold shadow-lg shadow-blue-500/20">
              Add
            </button>
          </div>
        </div>
      )}

      {/* Connection Tree list */}
      <div className="flex-1 overflow-y-auto py-1">
        {connections.length === 0 ? (
          <div className="text-xs text-text-muted text-center py-8 px-3 flex flex-col items-center gap-2">
            <Database className="w-8 h-8 text-text-muted opacity-45" />
            <span>No connections yet.<br/>Click + to add one.</span>
          </div>
        ) : (
          <div className="space-y-0.5">
            {connections.map(c => {
              const isExpanded = expandedConns[c.id];
              const isLoading = loadingTables[c.id];
              const tables = connTables[c.id] || [];

              return (
                <div key={c.id} className="flex flex-col">
                  {/* Connection Node */}
                  <div 
                    onClick={() => toggleConn(c)}
                    className="group flex justify-between items-center px-2 py-1.5 hover:bg-bg-conn-hover cursor-pointer transition-all border-l-2 border-transparent hover:border-blue-500"
                  >
                    <div className="flex items-center gap-1.5 overflow-hidden">
                      <div className="w-4 h-4 flex items-center justify-center text-text-muted">
                        {isExpanded ? <ChevronDown className="w-3.5 h-3.5" /> : <ChevronRight className="w-3.5 h-3.5" />}
                      </div>
                      <span className="text-sm shrink-0">{dbIcons[c.type] || '🗄️'}</span>
                      <div className="truncate flex flex-col">
                        <span className="font-medium text-xs text-text-main truncate">{c.name}</span>
                        <span className="text-[9px] text-text-muted truncate font-mono">{c.database}</span>
                      </div>
                    </div>
                    <button 
                      onClick={(e) => handleDelete(e, c.id, c.name)} 
                      className="opacity-0 group-hover:opacity-100 text-text-muted hover:text-red-500 p-1 transition-all"
                      title="Delete Connection"
                    >
                      <Trash2 className="w-3 h-3" />
                    </button>
                  </div>

                  {/* Database Objects Node */}
                  {isExpanded && (
                    <div className="flex flex-col">
                      {isLoading ? (
                        <div className="flex items-center gap-2 pl-9 py-2 text-[10px] text-text-muted">
                          <Loader2 className="w-3 h-3 animate-spin" /> Loading items...
                        </div>
                      ) : (
                        (() => {
                          const grouped: Record<string, TableInfo[]> = {};
                          tables.forEach(t => {
                            let groupName = 'Tables';
                            if (t.type === 'VIEW') groupName = 'Views';
                            if (t.type === 'MATERIALIZED VIEW') groupName = 'Materialized Views';
                            if (!grouped[groupName]) grouped[groupName] = [];
                            grouped[groupName].push(t);
                          });

                          return Object.entries(grouped).map(([folderName, items]) => {
                            const folderKey = `${c.id}_${folderName}`;
                            const isFolderExpanded = expandedFolders[folderKey];
                            
                            return (
                              <div key={folderName} className="flex flex-col">
                                <div 
                                  onClick={() => toggleFolder(c.id, folderName)}
                                  className="flex items-center gap-2 pl-6 pr-2 py-1.5 cursor-pointer hover:bg-bg-hover transition-colors text-xs text-text-main"
                                >
                                  <div className="w-4 h-4 flex items-center justify-center text-text-muted">
                                    {isFolderExpanded ? <ChevronDown className="w-3 h-3" /> : <ChevronRight className="w-3 h-3" />}
                                  </div>
                                  <Folder className="w-3.5 h-3.5 text-blue-500/70" />
                                  <span className="font-medium">{folderName} <span className="text-text-muted font-normal text-[10px]">({items.length})</span></span>
                                </div>
                                
                                {isFolderExpanded && (
                                  <div className="flex flex-col">
                                    {items.map(table => {
                                      const isSelected = explorerConnectionId === c.id && explorerTableName === table.name;
                                      return (
                                        <div 
                                          key={table.name}
                                          onClick={() => handleTableClick(c, table.name)}
                                          className={`flex items-center gap-2 pl-10 pr-2 py-1.5 cursor-pointer transition-colors text-xs font-mono
                                            ${isSelected 
                                              ? 'bg-blue-500/10 text-blue-500 dark:text-blue-400 border-r-2 border-blue-500' 
                                              : 'text-text-main hover:bg-bg-hover hover:text-blue-400'
                                            }
                                          `}
                                        >
                                          <TableIcon className={`w-3 h-3 shrink-0 ${isSelected ? 'text-blue-500' : 'text-text-muted'}`} />
                                          <span className="truncate">{table.name}</span>
                                        </div>
                                      );
                                    })}
                                  </div>
                                )}
                              </div>
                            );
                          });
                        })()
                      )}
                      {tables.length === 0 && !isLoading && (
                        <div className="pl-9 py-2 text-[10px] text-text-muted italic">
                          No items found.
                        </div>
                      )}
                    </div>
                  )}
                </div>
              );
            })}
          </div>
        )}
      </div>
    </div>
  );
};
