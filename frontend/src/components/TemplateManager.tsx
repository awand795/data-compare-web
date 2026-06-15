import React, { useState } from 'react';
import { useAppStore, type Template } from '../store/useAppStore';
import { Save, Copy, FolderOpen } from 'lucide-react';

interface Props {
  appMode: 'data' | 'query';
}

export const TemplateManager: React.FC<Props> = ({ appMode }) => {
  const { 
    templates, addTemplate, updateTemplate, activeTemplateId, setActiveTemplateId,
    sourceConnectionId, targetConnectionId, tableMappings,
    customQuerySource, customQueryTarget, setSourceConnectionId, setTargetConnectionId,
    clearTableMappings, setCustomQuerySource, setCustomQueryTarget
  } = useAppStore();

  const [isSavingAs, setIsSavingAs] = useState(false);
  const [newTemplateName, setNewTemplateName] = useState('');

  const filteredTemplates = templates.filter(t => t.appMode === appMode);

  const handleLoad = (e: React.ChangeEvent<HTMLSelectElement>) => {
    const id = e.target.value;
    if (!id) {
      setActiveTemplateId(null);
      return;
    }
    const t = templates.find(x => x.id === id);
    if (!t) return;
    
    setActiveTemplateId(id);
    setSourceConnectionId(t.sourceConnectionId);
    setTargetConnectionId(t.targetConnectionId);
    
    if (appMode === 'data') {
      clearTableMappings();
      if (t.tableMappings) {
        useAppStore.setState({ 
          tableMappings: t.tableMappings, 
          focusedMappingId: t.tableMappings.length > 0 ? t.tableMappings[0].id : null, 
          selectedMappingIds: t.tableMappings.map(m => m.id)
        });
      }
    } else if (appMode === 'query') {
      setCustomQuerySource(t.customQuerySource || '');
      setCustomQueryTarget(t.customQueryTarget || '');
    }
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
    }
    updateTemplate(activeTemplateId, updates);
    useAppStore.getState().addToast({ type: 'success', title: 'Template Saved', message: 'Current settings have been saved to the template.' });
  };

  const handleSaveAs = () => {
    if (!newTemplateName.trim()) return;
    const newTemplate: Template = {
      id: 'tpl_' + Date.now(),
      name: newTemplateName.trim(),
      appMode,
      sourceConnectionId,
      targetConnectionId,
      tableMappings: appMode === 'data' ? tableMappings : undefined,
      customQuerySource: appMode === 'query' ? customQuerySource : undefined,
      customQueryTarget: appMode === 'query' ? customQueryTarget : undefined,
    };
    addTemplate(newTemplate);
    setActiveTemplateId(newTemplate.id);
    setIsSavingAs(false);
    setNewTemplateName('');
    useAppStore.getState().addToast({ type: 'success', title: 'Template Created', message: `Saved as new template: ${newTemplate.name}` });
  };

  return (
    <div className="flex items-center gap-1.5 shrink-0">
      <div className="flex items-center gap-1.5 bg-bg-panel border border-border-main rounded-md px-2 py-1 shadow-sm">
        <FolderOpen className="w-3.5 h-3.5 text-blue-500" />
        <select 
          value={activeTemplateId || ''} 
          onChange={handleLoad}
          className="bg-transparent text-xs text-text-main outline-none w-32 truncate cursor-pointer font-medium"
        >
          <option value="">-- Load Template --</option>
          {filteredTemplates.map(t => (
            <option key={t.id} value={t.id}>{t.name}</option>
          ))}
        </select>
      </div>
      
      {activeTemplateId && (
        <button 
          onClick={handleSave}
          className="flex items-center justify-center w-7 h-7 bg-bg-panel hover:bg-blue-500/10 text-text-main hover:text-blue-500 rounded-md border border-border-main shadow-sm transition-colors"
          title="Save changes to current template"
        >
          <Save className="w-3.5 h-3.5" />
        </button>
      )}

      {isSavingAs ? (
        <div className="flex items-center gap-1 bg-bg-panel border border-border-main p-1 rounded-md shadow-sm">
          <input 
            autoFocus
            value={newTemplateName} 
            onChange={e => setNewTemplateName(e.target.value)} 
            onKeyDown={e => e.key === 'Enter' && handleSaveAs()}
            placeholder="Name..." 
            className="px-1.5 py-0.5 text-xs font-medium bg-bg-input border border-border-input rounded w-24 outline-none focus:border-blue-500"
          />
          <button onClick={handleSaveAs} className="px-1.5 py-0.5 text-[10px] bg-blue-600 text-white rounded hover:bg-blue-500 font-medium">Save</button>
          <button onClick={() => setIsSavingAs(false)} className="px-1.5 py-0.5 text-[10px] bg-bg-hover text-text-muted rounded hover:text-text-main font-medium">Cancel</button>
        </div>
      ) : (
        <button 
          onClick={() => setIsSavingAs(true)}
          className="flex items-center justify-center w-7 h-7 bg-bg-panel hover:bg-emerald-500/10 text-text-main hover:text-emerald-500 rounded-md border border-border-main shadow-sm transition-colors"
          title="Save as new template"
        >
          <Copy className="w-3.5 h-3.5" />
        </button>
      )}
    </div>
  );
};
