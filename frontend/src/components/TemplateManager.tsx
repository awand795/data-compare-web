import React, { useState } from 'react';
import { useAppStore, type Template } from '../store/useAppStore';
import { Save, Copy, FolderOpen, X, Trash2, Search, Edit2, Check, Plus } from 'lucide-react';
import { detectPrimaryKeysFromQuery } from '../utils/queryHelpers';

interface Props {
  appMode: 'data' | 'query';
  children?: React.ReactNode;
}

export const TemplateManager: React.FC<Props> = ({ appMode, children }) => {
  const { 
    templates, addTemplate, updateTemplate, removeTemplate,
    activeTemplateId, setActiveTemplateId,
    sourceConnectionId, targetConnectionId, tableMappings,
    customQuerySource, customQueryTarget, queryPrimaryKeys,
    clearTableMappings, addTableMapping, setCustomQuerySource, setCustomQueryTarget, setQueryPrimaryKeys,
    clearDiffResults, triggerWorkspaceReset, showAlert
  } = useAppStore();

  const [showLoadModal, setShowLoadModal] = useState(false);
  const [showSaveModal, setShowSaveModal] = useState(false);
  
  const [newTemplateName, setNewTemplateName] = useState('');
  const [searchTerm, setSearchTerm] = useState('');

  const [editingTemplateId, setEditingTemplateId] = useState<string | null>(null);
  const [editingName, setEditingName] = useState('');

  const filteredTemplates = templates.filter(t => t.appMode === appMode);
  const searchedTemplates = filteredTemplates.filter(t => 
    t.name.toLowerCase().includes(searchTerm.toLowerCase())
  );

  const handleLoad = (t: Template) => {
    setActiveTemplateId(t.id);
    const srcConnId = t.sourceConnectionId ?? (t as any).source_connection_id ?? null;
    const tgtConnId = t.targetConnectionId ?? (t as any).target_connection_id ?? null;
    
    useAppStore.setState({
      sourceConnectionId: srcConnId,
      targetConnectionId: tgtConnId,
      focusedMappingId: null,
    });
    
    if (appMode === 'data') {
      clearTableMappings();
      clearDiffResults();
      const mappings = t.tableMappings || (t as any).table_mappings;
      if (mappings && Array.isArray(mappings)) {
        mappings.forEach(mapping => {
          if (!mapping.id) mapping.id = 'mapping_' + Date.now() + Math.random().toString(36).substring(2, 9);
          addTableMapping(mapping);
        });
      }
    } else if (appMode === 'query') {
      const srcQuery = t.customQuerySource ?? (t as any).custom_query_source ?? '';
      const tgtQuery = t.customQueryTarget ?? (t as any).custom_query_target ?? '';
      let pks = t.queryPrimaryKeys ?? (t as any).query_primary_keys ?? '';

      if (!pks && srcQuery) {
        const autoPks = detectPrimaryKeysFromQuery(srcQuery);
        if (autoPks && autoPks.length > 0) {
          pks = autoPks.join(', ');
        }
      }

      useAppStore.setState({
        customQuerySource: srcQuery,
        customQueryTarget: tgtQuery,
        queryPrimaryKeys: pks
      });
    }

    triggerWorkspaceReset();
    setShowLoadModal(false);
    useAppStore.getState().addToast({ type: 'success', title: 'Template Loaded', message: `Loaded template: ${t.name}` });
  };

  const handleSave = () => {
    if (!activeTemplateId) return;
    const updates: Partial<Template> = {
      sourceConnectionId,
      targetConnectionId,
    };
    if (appMode === 'data') updates.tableMappings = tableMappings;
    if (appMode === 'query') {
      updates.customQuerySource = customQuerySource;
      updates.customQueryTarget = customQueryTarget;
      updates.queryPrimaryKeys = queryPrimaryKeys;
    }
    updateTemplate(activeTemplateId, updates);
    useAppStore.getState().addToast({ type: 'success', title: 'Template Saved', message: 'Current settings have been saved to the template.' });
  };
  const handleNewTemplate = () => {
    setActiveTemplateId(null);
    if (appMode === 'query') {
      setCustomQuerySource('');
      setCustomQueryTarget('');
      setQueryPrimaryKeys('');
    } else if (appMode === 'data') {
      clearTableMappings();
      clearDiffResults();
    }
    triggerWorkspaceReset();
  };

  const handleSaveAs = () => {
    if (!newTemplateName.trim()) return;
    const newTemplate: Template = {
      id: '', // Backend will generate
      name: newTemplateName.trim(),
      appMode,
      sourceConnectionId,
      targetConnectionId,
      tableMappings: appMode === 'data' ? tableMappings : undefined,
      customQuerySource: appMode === 'query' ? customQuerySource : undefined,
      customQueryTarget: appMode === 'query' ? customQueryTarget : undefined,
      queryPrimaryKeys: appMode === 'query' ? queryPrimaryKeys : undefined,
    };
    const tempId = 'tpl_' + Date.now();
    newTemplate.id = tempId;
    
    addTemplate(newTemplate);
    setActiveTemplateId(tempId);
    setShowSaveModal(false);
    setNewTemplateName('');
    useAppStore.getState().addToast({ type: 'success', title: 'Template Created', message: `Saved as new template: ${newTemplate.name}` });
  };

  const activeTemplate = templates.find(t => t.id === activeTemplateId);

  return (
    <>
      <div className="flex flex-col items-end gap-1">
        <div className="flex items-center gap-2 shrink-0">
          {appMode === 'query' && (
            <button 
              onClick={handleNewTemplate}
              className="flex items-center gap-1.5 px-3 py-1.5 bg-bg-panel hover:bg-blue-500/10 text-text-main hover:text-blue-500 text-[11px] font-medium rounded-lg border border-border-main shadow-sm transition-all hover:shadow-md hover:-translate-y-0.5"
              title="Create new blank template"
            >
              <Plus className="w-3.5 h-3.5" /> New Template
            </button>
          )}

          <button 
            onClick={() => setShowLoadModal(true)}
            className="flex items-center gap-1.5 px-3 py-1.5 bg-bg-panel hover:bg-bg-hover text-text-main text-[11px] font-medium rounded-lg border border-border-main shadow-sm transition-all hover:shadow-md hover:-translate-y-0.5"
          >
            <FolderOpen className="w-3.5 h-3.5 text-blue-500" />
            Load Template
          </button>
          
          {activeTemplateId && activeTemplate ? (
            <>
              <button 
                onClick={handleSave}
                className="flex items-center gap-1.5 px-3 py-1.5 bg-bg-panel hover:bg-blue-500/10 text-text-main hover:text-blue-500 text-[11px] font-medium rounded-lg border border-border-main shadow-sm transition-all hover:shadow-md hover:-translate-y-0.5"
                title="Save changes to current template"
              >
                <Save className="w-3.5 h-3.5" /> Save
              </button>
              <button 
                onClick={() => setShowSaveModal(true)}
                className="flex items-center gap-1.5 px-3 py-1.5 bg-bg-panel hover:bg-emerald-500/10 text-text-main hover:text-emerald-500 text-[11px] font-medium rounded-lg border border-border-main shadow-sm transition-all hover:shadow-md hover:-translate-y-0.5"
                title="Save as new template"
              >
                <Copy className="w-3.5 h-3.5" /> Save As
              </button>
            </>
          ) : (
            <button 
              onClick={() => setShowSaveModal(true)}
              className="flex items-center gap-1.5 px-3 py-1.5 bg-bg-panel hover:bg-blue-500/10 text-text-main hover:text-blue-500 text-[11px] font-medium rounded-lg border border-border-main shadow-sm transition-all hover:shadow-md hover:-translate-y-0.5"
              title="Save as template"
            >
              <Save className="w-3.5 h-3.5" /> Save Template
            </button>
          )}
          {children}
        </div>
        {activeTemplateId && activeTemplate && (
          <span className="text-[10px] text-text-muted block w-full text-right px-1 mt-1">
            Active: <span className="font-bold text-blue-500">{activeTemplate.name}</span>
          </span>
        )}
      </div>

      {/* Load Modal */}
      {showLoadModal && (
        <div className="fixed inset-0 z-[200] flex items-center justify-center bg-black/60 backdrop-blur-sm p-4">
          <div className="bg-bg-main w-full max-w-lg rounded-xl shadow-2xl border border-border-main flex flex-col max-h-[85vh]">
            <div className="flex items-center justify-between p-4 border-b border-border-main">
              <h3 className="text-lg font-bold text-text-main flex items-center gap-2">
                <FolderOpen className="w-5 h-5 text-blue-500" />
                Load Template
              </h3>
              <button onClick={() => setShowLoadModal(false)} className="p-1 hover:bg-bg-hover rounded text-text-muted transition-colors">
                <X className="w-5 h-5" />
              </button>
            </div>
            
            <div className="p-4 border-b border-border-main bg-bg-panel">
              <div className="relative">
                <Search className="w-4 h-4 absolute left-3 top-1/2 -translate-y-1/2 text-text-muted" />
                <input
                  type="text"
                  placeholder="Search templates..."
                  value={searchTerm}
                  onChange={e => setSearchTerm(e.target.value)}
                  className="w-full pl-9 pr-4 py-2 bg-bg-input border border-border-input rounded-md text-sm text-text-main outline-none focus:border-blue-500 transition-colors"
                />
              </div>
            </div>

            <div className="flex-1 overflow-y-auto p-2">
              {searchedTemplates.length === 0 ? (
                <div className="text-center py-8 text-text-muted text-sm">
                  No templates found.
                </div>
              ) : (
                <div className="grid gap-2">
                  {searchedTemplates.map(t => (
                    <div key={t.id} className="flex items-center justify-between p-3 rounded-lg border border-border-item hover:border-blue-500/50 bg-bg-panel hover:bg-blue-500/5 group cursor-pointer transition-colors" onClick={() => { if (editingTemplateId !== t.id) handleLoad(t); }}>
                      <div className="flex flex-col w-full pr-4">
                        {editingTemplateId === t.id ? (
                          <div className="flex items-center gap-2" onClick={e => e.stopPropagation()}>
                            <input
                              autoFocus
                              type="text"
                              value={editingName}
                              onChange={e => setEditingName(e.target.value)}
                              onKeyDown={e => {
                                if (e.key === 'Enter') {
                                  if (editingName.trim() && editingName !== t.name) {
                                    updateTemplate(t.id, { name: editingName.trim() });
                                    useAppStore.getState().addToast({ type: 'success', title: 'Template Renamed', message: 'Name updated.' });
                                  }
                                  setEditingTemplateId(null);
                                } else if (e.key === 'Escape') {
                                  setEditingTemplateId(null);
                                }
                              }}
                              className="px-2 py-1 bg-bg-input border border-border-input rounded text-sm text-text-main outline-none focus:border-blue-500 w-full"
                            />
                            <button
                              onClick={(e) => {
                                e.stopPropagation();
                                if (editingName.trim() && editingName !== t.name) {
                                  updateTemplate(t.id, { name: editingName.trim() });
                                  useAppStore.getState().addToast({ type: 'success', title: 'Template Renamed', message: 'Name updated.' });
                                }
                                setEditingTemplateId(null);
                              }}
                              className="p-1.5 text-blue-500 hover:bg-blue-500/10 rounded transition-colors"
                            >
                              <Check className="w-4 h-4" />
                            </button>
                            <button
                              onClick={(e) => { e.stopPropagation(); setEditingTemplateId(null); }}
                              className="p-1.5 text-text-muted hover:bg-bg-hover rounded transition-colors"
                            >
                              <X className="w-4 h-4" />
                            </button>
                          </div>
                        ) : (
                          <>
                            <span className="font-bold text-sm text-text-main group-hover:text-blue-500 transition-colors">{t.name}</span>
                            <span className="text-xs text-text-muted mt-0.5">
                              {appMode === 'data' ? `${t.tableMappings?.length || 0} mappings` : 'Custom Query'}
                            </span>
                          </>
                        )}
                      </div>
                      <div className="flex items-center gap-1 opacity-0 group-hover:opacity-100 transition-opacity">
                        {editingTemplateId !== t.id && (
                          <button 
                            onClick={(e) => { 
                              e.stopPropagation(); 
                              setEditingTemplateId(t.id);
                              setEditingName(t.name);
                            }}
                            className="p-2 text-text-muted hover:text-blue-500 hover:bg-blue-500/10 rounded transition-colors"
                            title="Edit name"
                          >
                            <Edit2 className="w-4 h-4" />
                          </button>
                        )}
                        <button 
                          onClick={(e) => { 
                            e.stopPropagation(); 
                            showAlert({
                              title: 'Delete Template?',
                              message: `Are you sure you want to delete "${t.name}"? This action cannot be undone.`,
                              type: 'error',
                              confirmLabel: 'Delete',
                              onConfirm: () => removeTemplate(t.id)
                            });
                          }}
                          className="p-2 text-text-muted hover:text-red-500 hover:bg-red-500/10 rounded transition-colors"
                          title="Delete template"
                        >
                          <Trash2 className="w-4 h-4" />
                        </button>
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </div>
          </div>
        </div>
      )}

      {/* Save Modal */}
      {showSaveModal && (
        <div className="fixed inset-0 z-[200] flex items-center justify-center bg-black/60 backdrop-blur-sm p-4">
          <div className="bg-bg-main w-full max-w-sm rounded-xl shadow-2xl border border-border-main flex flex-col">
            <div className="flex items-center justify-between p-4 border-b border-border-main">
              <h3 className="text-lg font-bold text-text-main flex items-center gap-2">
                <Save className="w-5 h-5 text-blue-500" />
                {activeTemplateId ? 'Save As New Template' : 'Save Template'}
              </h3>
              <button onClick={() => setShowSaveModal(false)} className="p-1 hover:bg-bg-hover rounded text-text-muted transition-colors">
                <X className="w-5 h-5" />
              </button>
            </div>
            <div className="p-4">
              <label className="block text-sm font-medium text-text-muted mb-1.5">Template Name</label>
              <input
                autoFocus
                type="text"
                placeholder="e.g. Weekly Report Sync"
                value={newTemplateName}
                onChange={e => setNewTemplateName(e.target.value)}
                onKeyDown={e => e.key === 'Enter' && handleSaveAs()}
                className="w-full px-3 py-2 bg-bg-input border border-border-input rounded-md text-sm text-text-main outline-none focus:border-blue-500 transition-colors"
              />
            </div>
            <div className="p-4 border-t border-border-main bg-bg-panel flex justify-end gap-2 rounded-b-xl">
              <button 
                onClick={() => setShowSaveModal(false)}
                className="px-4 py-2 text-sm font-medium text-text-muted hover:text-text-main hover:bg-bg-hover rounded-md transition-colors"
              >
                Cancel
              </button>
              <button 
                onClick={handleSaveAs}
                disabled={!newTemplateName.trim()}
                className="px-4 py-2 text-sm font-bold text-white bg-blue-600 hover:bg-blue-500 disabled:opacity-50 disabled:cursor-not-allowed rounded-md transition-colors shadow-lg shadow-blue-500/20"
              >
                Save Template
              </button>
            </div>
          </div>
        </div>
      )}
    </>
  );
};
