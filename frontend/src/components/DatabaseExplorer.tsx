// @ts-nocheck
import React, { useState, useEffect } from 'react';
import { useAppStore, type Connection } from '../store/useAppStore';
import { useExplorerStore, type ExplorerNode, type ExplorerNodeType } from '../store/useExplorerStore';
import { ConnectionDialog } from './ConnectionDialog';
import { Database, Plus, Trash2, Search, ChevronRight, ChevronDown, Table as TableIcon, LayoutList, FileCode2, Link as LinkIcon, Key, Hash, RefreshCw, Server, Settings2, Play } from 'lucide-react';
import axios from 'axios';
import clsx from 'clsx';

export const DatabaseExplorer: React.FC = () => {
  const { 
    connections, removeConnection, 
    setExplorerConnectionId, setExplorerSchemaName, setExplorerTableName, 
    setAppMode, explorerConnectionId, explorerTableName, explorerSchemaName, 
    setCustomQuerySource, setSourceConnectionId,
    showAlert 
  } = useAppStore();
  const { nodes, upsertNode, patchNode, removeNode, toggleExpand, setLoading, setLoaded, selectedNodeId, setSelectedNodeId } = useExplorerStore();
  
  const [isDialogOpen, setIsDialogOpen] = useState(false);
  const [searchQuery, setSearchQuery] = useState('');
  
  // Context Menu State
  const [contextMenu, setContextMenu] = useState<{ x: number, y: number, nodeId: string } | null>(null);

  useEffect(() => {
    const handleClick = () => setContextMenu(null);
    window.addEventListener('click', handleClick);
    return () => window.removeEventListener('click', handleClick);
  }, []);

  // Initialize root nodes (servers) when connections change
  useEffect(() => {
    connections.forEach(conn => {
      if (!nodes[conn.id]) {
        upsertNode({
          id: conn.id,
          parentId: null,
          type: 'server',
          name: conn.name,
          label: `${conn.name} (${conn.type})`,
          isLoaded: false,
          isLoading: false,
          isExpanded: false,
          metadata: conn
        });
      }
    });
  }, [connections]);

  const loadSchemas = async (connId: string) => {
    setLoading(connId, true);
    try {
      const res = await axios.get(`/api/connections/${connId}/schemas`);
      const schemas: string[] = res.data;
      const childIds = schemas.map(s => {
        const id = `${connId}_schema_${s}`;
        upsertNode({ id, parentId: connId, type: 'schema', name: s, isLoaded: false, isLoading: false, isExpanded: false, metadata: { connId, schema: s } });
        return id;
      });
      setLoaded(connId, childIds);
    } catch (e: any) {
      setLoading(connId, false, e.message);
    }
  };

  const loadTables = async (connId: string, schemaNodeId: string, schemaName: string) => {
    setLoading(schemaNodeId, true);
    try {
      const res = await axios.get(`/api/connections/${connId}/schemas/${schemaName}/tables`);
      const tables: any[] = res.data;
      const childIds = tables.map(t => {
        const tName = typeof t === 'string' ? t : t.name;
        const isSystem = t.isSystem;
        let tType: ExplorerNodeType = 'table';
        if (t.type?.includes('VIEW')) tType = 'view';
        
        const id = `${schemaNodeId}_table_${tName}`;
        upsertNode({ 
          id, parentId: schemaNodeId, type: tType, name: tName, 
          label: isSystem ? `${tName} (sys)` : tName,
          isLoaded: false, isLoading: false, isExpanded: false, 
          metadata: { connId, schema: schemaName, isSystem } 
        });
        return id;
      });
      
      // Update schema label with count using patchNode to avoid overwriting expansion state
      patchNode(schemaNodeId, { label: `${schemaName} (${tables.length})` });

      setLoaded(schemaNodeId, childIds);
    } catch (e: any) {
      setLoading(schemaNodeId, false, e.message);
    }
  };

  const loadColumns = async (connId: string, schemaName: string, tableNodeId: string, tableName: string) => {
    setLoading(tableNodeId, true);
    try {
      const res = await axios.get(`/api/connections/${connId}/schemas/${schemaName}/tables/${tableName}/columns`);
      const cols: any[] = res.data;
      const childIds = cols.map(c => {
        const id = `${tableNodeId}_col_${c.name}`;
        upsertNode({ 
          id, parentId: tableNodeId, type: 'column', name: c.name, label: `${c.name} (${c.type})`, 
          isLoaded: true, isLoading: false, isExpanded: false, metadata: c 
        });
        return id;
      });
      setLoaded(tableNodeId, childIds);
    } catch (e: any) {
      setLoading(tableNodeId, false, e.message);
    }
  };

  const handleToggle = async (e: React.MouseEvent, node: ExplorerNode) => {
    e.stopPropagation();
    
    // If not loaded and not loading, fetch data and force expand
    if (!node.isLoaded && !node.isLoading) {
      toggleExpand(node.id, true);
      if (node.type === 'server') {
        await loadSchemas(node.id);
      } else if (node.type === 'schema') {
        await loadTables(node.metadata?.connId || node.parentId, node.id, node.name);
      } else if (node.type === 'table' || node.type === 'view') {
        const connId = node.metadata?.connId;
        const schema = node.metadata?.schema;
        if (connId && schema) await loadColumns(connId, schema, node.id, node.name);
      }
    } else {
      // Toggle if already loaded
      toggleExpand(node.id);
    }
  };

  const handleRefresh = async (e: React.MouseEvent, node: ExplorerNode) => {
    e.stopPropagation();
    // Reset loaded state to force re-fetch
    upsertNode({ ...node, isLoaded: false, isExpanded: true });
    
    if (node.type === 'server') await loadSchemas(node.id);
    else if (node.type === 'schema') await loadTables(node.metadata?.connId || node.parentId, node.id, node.name);
    else if (node.type === 'table' || node.type === 'view') {
      const connId = node.metadata?.connId;
      const schema = node.metadata?.schema;
      if (connId && schema) await loadColumns(connId, schema, node.id, node.name);
    }
  };

  const handleNodeClick = (node: ExplorerNode, e: React.MouseEvent) => {
    setSelectedNodeId(node.id);
    if (node.type === 'table' || node.type === 'view') {
      const connId = node.metadata?.connId;
      const schema = node.metadata?.schema;
      if (connId) {
        setExplorerConnectionId(connId);
        setExplorerSchemaName(schema || null);
        setExplorerTableName(node.name); 
        setAppMode('explorer');
      }
    } else if (node.type === 'server' || node.type === 'schema') {
      // For folders, single click also toggles expansion for better UX
      handleToggle(e, node);
    }
  };

  const handleContextMenu = (e: React.MouseEvent, nodeId: string) => {
    const node = nodes[nodeId];
    if (node && (node.type === 'table' || node.type === 'view')) {
      e.preventDefault();
      setContextMenu({ x: e.clientX, y: e.clientY, nodeId });
    }
  };

  const executeAction = (action: string) => {
    if (!contextMenu) return;
    const node = nodes[contextMenu.nodeId];
    if (!node) return;
    const connId = node.metadata?.connId;
    const schema = node.metadata?.schema;
    const tableName = node.name;
    const fullTable = schema && schema !== 'null' ? `${schema}.${tableName}` : tableName;

    switch (action) {
      case 'top100':
        setExplorerConnectionId(connId);
        setExplorerSchemaName(schema);
        setExplorerTableName(tableName);
        setCustomQuerySource(`SELECT * FROM ${fullTable} LIMIT 100`);
        setAppMode('query');
        break;
      case 'compare':
        setSourceConnectionId(connId);
        setAppMode('compare');
        break;
      case 'copyName':
        navigator.clipboard.writeText(fullTable);
        break;
    }
    setContextMenu(null);
  };

  const getNodeIcon = (type: ExplorerNodeType, metadata?: any) => {
    const isSystem = metadata?.isSystem;
    switch (type) {
      case 'server': return <Server className="w-3.5 h-3.5 shrink-0 text-slate-400" />;
      case 'schema': return <LayoutList className="w-3.5 h-3.5 shrink-0 text-purple-400" />;
      case 'table': return <TableIcon className={clsx("w-3.5 h-3.5 shrink-0", isSystem ? "text-slate-500" : "text-emerald-400")} />;
      case 'view': return <FileCode2 className={clsx("w-3.5 h-3.5 shrink-0", isSystem ? "text-slate-500" : "text-indigo-400")} />;
      case 'column': 
        if (metadata?.isPk) return <Key className="w-3.5 h-3.5 shrink-0 text-amber-500" />;
        if (metadata?.isFk) return <LinkIcon className="w-3.5 h-3.5 shrink-0 text-blue-400" />;
        return <Hash className="w-3.5 h-3.5 shrink-0 text-slate-500" />;
      default: return <ChevronRight className="w-3.5 h-3.5 shrink-0" />;
    }
  };

  const renderNode = (nodeId: string, depth: number = 0) => {
    const node = nodes[nodeId];
    if (!node) return null;

    if (searchQuery && !node.name.toLowerCase().includes(searchQuery.toLowerCase())) {
      const hasMatchingChild = node.childrenIds?.some(cid => nodes[cid]?.name.toLowerCase().includes(searchQuery.toLowerCase()));
      if (!hasMatchingChild && node.type !== 'server') return null;
    }

    const isSelected = selectedNodeId === node.id || ((node.type === 'table' || node.type === 'view') && explorerTableName === node.name && explorerConnectionId === node.metadata?.connId);

    return (
      <div key={node.id} className="flex flex-col group/node">          <div 
          onClick={(e) => handleNodeClick(node, e)}
          onContextMenu={(e) => handleContextMenu(e, node.id)}
          className={clsx(
            "flex items-center gap-1.5 py-1.5 px-2 cursor-pointer transition-colors text-xs font-mono select-none border-l-[3px] relative",
            isSelected ? "bg-blue-500/10 border-blue-500 text-blue-400" : "border-transparent text-text-main hover:bg-bg-hover"
          )}
          style={{ paddingLeft: `${depth * 12 + 8}px` }}
        >
          <div 
            onClick={(e) => handleToggle(e, node)} 
            className={clsx(
              "w-4 h-4 shrink-0 flex items-center justify-center rounded hover:bg-slate-500/20",
              (node.type === 'column' || node.type === 'index' || node.type === 'fk') ? "invisible" : ""
            )}
          >
            {node.isLoading ? <RefreshCw className="w-3 h-3 animate-spin text-text-muted" /> : (
              node.isExpanded ? <ChevronDown className="w-3.5 h-3.5 text-text-muted" /> : <ChevronRight className="w-3.5 h-3.5 text-text-muted" />
            )}
          </div>
          {node.type === 'server' && (
            <span className={clsx("w-1.5 h-1.5 rounded-full shrink-0", node.isLoaded ? "bg-green-500" : "bg-slate-500")} />
          )}
          {getNodeIcon(node.type, node.metadata)}
          <span className="truncate text-xs">{node.label || node.name}</span>
          
          <div className="ml-auto flex items-center gap-1 opacity-0 group-hover/node:opacity-100 transition-opacity">
             {(node.type === 'server' || node.type === 'schema' || node.type === 'table' || node.type === 'view') && (
               <button onClick={(e) => handleRefresh(e, node)} className="text-text-muted hover:text-blue-500 p-0.5" title="Refresh">
                 <RefreshCw className="w-2.5 h-2.5" />
               </button>
             )}
             {node.type === 'server' && (
               <button onClick={(e) => { e.stopPropagation(); showAlert({ title: 'Delete Connection', message: `Are you sure you want to delete "${node.name}"? This action cannot be undone.`, type: 'error', confirmLabel: 'Delete', onConfirm: () => { removeConnection(node.id); removeNode(node.id); } }); }} className="text-text-muted hover:text-red-500 p-0.5" title="Remove Connection"><Trash2 className="w-3 h-3" /></button>
             )}
          </div>
        </div>
        {node.error && node.isExpanded && (
          <div className="text-[10px] text-red-400 pl-8 py-1 truncate">{node.error}</div>
        )}
        {node.isExpanded && node.isLoading && (
          <div className="text-[10px] text-text-muted italic py-1" style={{ paddingLeft: `${(depth + 1) * 12 + 24}px` }}>
            Loading...
          </div>
        )}
        {node.isExpanded && node.childrenIds?.map(cid => renderNode(cid, depth + 1))}
      </div>
    );
  };

  const rootNodes = Object.values(nodes).filter(n => n.parentId === null);

  return (
    <div className="flex flex-col h-full bg-bg-main border-r border-border-main">
      <div className="h-10 px-3 flex items-center justify-between border-b border-border-main bg-bg-header shrink-0 border-t-2 border-t-blue-600/30">
        <span className="text-xs font-semibold text-text-muted uppercase tracking-wider flex items-center gap-1.5">
          <Database className="w-3.5 h-3.5 text-blue-500" /> Explorer
        </span>
        <button onClick={() => setIsDialogOpen(true)} className="p-1 rounded hover:bg-bg-hover text-text-muted hover:text-blue-500 transition-colors" title="New Connection">
          <Plus className="w-3.5 h-3.5" />
        </button>
      </div>

      {/* Search bar - always visible */}
      <div className="px-2 py-2 border-b border-border-main bg-bg-panel shrink-0">
        <div className="relative">
          <Search className="w-3 h-3 absolute left-2 top-1/2 -translate-y-1/2 text-text-muted" />
          <input 
            type="text" 
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            placeholder="Filter nodes..." 
            className="w-full pl-7 pr-2 h-7 bg-bg-input border border-border-input rounded text-xs text-text-input outline-none focus:border-blue-500 placeholder-slate-500"
          />
        </div>
      </div>

      <div className="flex-1 overflow-y-auto py-1">
        {rootNodes.length === 0 ? (
          <div className="text-xs text-text-muted text-center py-8 px-3 flex flex-col items-center gap-3">
            <Server className="w-10 h-10 text-text-muted opacity-30" />
            <span className="text-[11px]">No connections yet.</span>
            <button onClick={() => setIsDialogOpen(true)} className="px-3 py-1.5 bg-blue-600 hover:bg-blue-500 text-white rounded-md text-xs font-medium shadow-lg shadow-blue-500/20 transition-all flex items-center gap-1.5">
              <Plus className="w-3.5 h-3.5" />
              Add your first connection
            </button>
          </div>
        ) : (
          rootNodes.map(node => renderNode(node.id, 0))
        )}
      </div>

      {/* ── Context Menu ── */}
      {contextMenu && (
        <div 
          className="fixed z-[1000] bg-bg-panel border border-border-main rounded-lg shadow-xl py-1 min-w-[160px] animate-in fade-in zoom-in-95 duration-100"
          style={{ top: contextMenu.y, left: contextMenu.x }}
          onClick={e => e.stopPropagation()}
        >
          <button 
            onClick={() => executeAction('top100')}
            className="w-full text-left px-3 py-1.5 text-[11px] hover:bg-blue-500 hover:text-white transition-colors flex items-center gap-2"
          >
            <Play className="w-3 h-3" /> Select Top 100
          </button>
          <button 
            onClick={() => executeAction('compare')}
            className="w-full text-left px-3 py-1.5 text-[11px] hover:bg-blue-500 hover:text-white transition-colors flex items-center gap-2"
          >
            <RefreshCw className="w-3 h-3" /> Compare this Table
          </button>
          <div className="h-px bg-border-main my-1" />
          <button 
            onClick={() => executeAction('copyName')}
            className="w-full text-left px-3 py-1.5 text-[11px] hover:bg-blue-500 hover:text-white transition-colors flex items-center gap-2"
          >
            <Search className="w-3 h-3" /> Copy Full Name
          </button>
        </div>
      )}

      <ConnectionDialog isOpen={isDialogOpen} onClose={() => setIsDialogOpen(false)} />
    </div>
  );
};
