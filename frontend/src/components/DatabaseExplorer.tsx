// @ts-nocheck
import React, { useState, useEffect } from 'react';
import { useAppStore, type Connection } from '../store/useAppStore';
import { useExplorerStore, type ExplorerNode, type ExplorerNodeType } from '../store/useExplorerStore';
import { ConnectionDialog } from './ConnectionDialog';
import { Database, Plus, Trash2, Search, ChevronRight, ChevronDown, Table as TableIcon, LayoutList, FileCode2, Link as LinkIcon, Key, Hash, RefreshCw, Server, Settings2 } from 'lucide-react';
import axios from 'axios';
import clsx from 'clsx';

export const DatabaseExplorer: React.FC = () => {
  const { connections, removeConnection, setExplorerConnectionId, setExplorerSchemaName, setExplorerTableName, setAppMode, explorerConnectionId, explorerTableName, explorerSchemaName } = useAppStore();
  const { nodes, upsertNode, removeNode, toggleExpand, setLoading, setLoaded, selectedNodeId, setSelectedNodeId } = useExplorerStore();
  
  const [isDialogOpen, setIsDialogOpen] = useState(false);
  const [searchQuery, setSearchQuery] = useState('');
  const [isSearchOpen, setIsSearchOpen] = useState(false);

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
        const tType = (t.type === 'VIEW' || t.type === 'view') ? 'view' : 'table';
        const id = `${schemaNodeId}_table_${tName}`;
        upsertNode({ id, parentId: schemaNodeId, type: tType, name: tName, isLoaded: false, isLoading: false, isExpanded: false, metadata: { connId, schema: schemaName } });
        return id;
      });
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
    toggleExpand(node.id);
    if (!node.isExpanded && !node.isLoaded && !node.isLoading) {
      if (node.type === 'server') {
        await loadSchemas(node.id);
      } else if (node.type === 'schema') {
        await loadTables(node.metadata?.connId || node.parentId, node.id, node.name);
      } else if (node.type === 'table' || node.type === 'view') {
        const connId = node.metadata?.connId;
        const schema = node.metadata?.schema;
        if (connId && schema) await loadColumns(connId, schema, node.id, node.name);
      }
    }
  };

  const handleNodeClick = (node: ExplorerNode) => {
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
    }
  };

  const getNodeIcon = (type: ExplorerNodeType, metadata?: any) => {
    switch (type) {
      case 'server': return <Server className="w-3.5 h-3.5 shrink-0 text-slate-400" />;
      case 'database': return <Database className="w-3.5 h-3.5 shrink-0 text-blue-400" />;
      case 'schema': return <LayoutList className="w-3.5 h-3.5 shrink-0 text-purple-400" />;
      case 'table': return <TableIcon className="w-3.5 h-3.5 shrink-0 text-emerald-400" />;
      case 'view': return <TableIcon className="w-3.5 h-3.5 shrink-0 text-indigo-400" />;
      case 'function': return <FileCode2 className="w-3.5 h-3.5 shrink-0 text-amber-400" />;
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

    // Filter logic
    if (searchQuery && !node.name.toLowerCase().includes(searchQuery.toLowerCase())) {
      // Allow parent if child matches
      const hasMatchingChild = node.childrenIds?.some(cid => nodes[cid]?.name.toLowerCase().includes(searchQuery.toLowerCase()));
      if (!hasMatchingChild && node.type !== 'server') return null; // Always show servers
    }

    const isSelected = selectedNodeId === node.id || ((node.type === 'table' || node.type === 'view') && explorerTableName === node.name && explorerConnectionId === node.metadata?.connId);

    return (
      <div key={node.id} className="flex flex-col">
        <div 
          onClick={() => handleNodeClick(node)}
          className={clsx(
            "flex items-center gap-1.5 py-1 px-2 cursor-pointer transition-colors text-xs font-mono select-none border-l-2",
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
          {getNodeIcon(node.type, node.metadata)}
          <span className="truncate">{node.label || node.name}</span>
          {node.type === 'server' && (
            <div className="ml-auto flex items-center opacity-0 hover:opacity-100 transition-opacity">
               <button onClick={(e) => { e.stopPropagation(); removeConnection(node.id); }} className="text-text-muted hover:text-red-500 p-0.5"><Trash2 className="w-3 h-3" /></button>
            </div>
          )}
        </div>
        {node.error && node.isExpanded && (
          <div className="text-[10px] text-red-400 pl-8 py-1 truncate">{node.error}</div>
        )}
        {node.isExpanded && node.childrenIds?.map(cid => renderNode(cid, depth + 1))}
      </div>
    );
  };

  const rootNodes = Object.values(nodes).filter(n => n.parentId === null);

  return (
    <div className="flex flex-col h-full bg-bg-panel border-r border-border-main">
      <div className="px-3 py-2.5 border-b border-border-main flex items-center justify-between bg-bg-header shrink-0">
        <span className="text-[10px] font-semibold text-text-muted uppercase tracking-wider flex items-center gap-1.5">
          <Database className="w-3.5 h-3.5" /> Explorer
        </span>
        <div className="flex gap-1">
          <button onClick={() => setIsSearchOpen(!isSearchOpen)} className="p-1 rounded hover:bg-bg-hover text-text-muted hover:text-text-main transition-colors" title="Search">
            <Search className="w-3.5 h-3.5" />
          </button>
          <button onClick={() => setIsDialogOpen(true)} className="p-1 rounded hover:bg-bg-hover text-text-muted hover:text-blue-500 transition-colors" title="New Connection">
            <Plus className="w-3.5 h-3.5" />
          </button>
        </div>
      </div>

      {isSearchOpen && (
        <div className="px-2 py-2 border-b border-border-main bg-bg-main shrink-0 animate-in slide-in-from-top-2">
          <input 
            type="text" 
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            placeholder="Filter nodes..." 
            className="w-full px-2.5 py-1.5 bg-bg-input border border-border-input rounded text-xs text-text-input outline-none focus:border-blue-500"
            autoFocus
          />
        </div>
      )}

      <div className="flex-1 overflow-y-auto py-1">
        {rootNodes.length === 0 ? (
          <div className="text-xs text-text-muted text-center py-8 px-3 flex flex-col items-center gap-2">
            <Server className="w-8 h-8 text-text-muted opacity-45" />
            <span>No connections yet.<br/>Click + to add one.</span>
          </div>
        ) : (
          rootNodes.map(node => renderNode(node.id, 0))
        )}
      </div>

      <ConnectionDialog isOpen={isDialogOpen} onClose={() => setIsDialogOpen(false)} />
    </div>
  );
};
