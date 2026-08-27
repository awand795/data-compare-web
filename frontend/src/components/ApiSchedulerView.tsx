import React, { useState, useEffect, useMemo } from 'react';
import axios from 'axios';
import { useAppStore } from '../store/useAppStore';
import { 
  Globe, Plus, Save, Play, Pencil, Trash2, Check, Copy, 
  Search, Clock, Database, Loader2, ArrowLeft,
  RefreshCw, FileText, Code2, ShieldCheck,
  Zap, CheckCircle2, AlertCircle, Bell, MessageCircle, Send, Settings, X,
  Folder, FolderOpen, FolderPlus, FolderTree, Layers, AlertTriangle, ChevronDown, ChevronRight
} from 'lucide-react';
import clsx from 'clsx';
import { NotificationChannelsModal } from './NotificationChannelsModal';

interface ApiSchedulerConfig {
  id?: string;
  name: string;
  method: string;
  url: string;
  groupName?: string;
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
  notificationChannelId?: string;
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

interface NotificationChannel {
  id: string;
  name: string;
  type: 'TELEGRAM' | 'DISCORD';
  botToken?: string;
  chatId?: string;
  webhookUrl?: string;
}

const getMethodBadgeClass = (method: string) => {
  switch (method.toUpperCase()) {
    case 'GET': return 'bg-emerald-500/15 text-emerald-600 dark:text-emerald-400 border border-emerald-500/30';
    case 'POST': return 'bg-blue-500/15 text-blue-600 dark:text-blue-400 border border-blue-500/30';
    case 'PUT': return 'bg-amber-500/15 text-amber-700 dark:text-amber-400 border border-amber-500/30';
    case 'PATCH': return 'bg-purple-500/15 text-purple-600 dark:text-purple-400 border border-purple-500/30';
    case 'DELETE': return 'bg-rose-500/15 text-rose-600 dark:text-rose-400 border border-rose-500/30';
    default: return 'bg-slate-500/15 text-slate-600 dark:text-slate-400 border border-slate-500/30';
  }
};

export const ApiSchedulerView: React.FC = () => {
  const { connections, addToast, showAlert } = useAppStore();
  const [schedulers, setSchedulers] = useState<ApiSchedulerConfig[]>([]);
  const [channels, setChannels] = useState<NotificationChannel[]>([]);
  const [loading, setLoading] = useState(true);
  const [searchQuery, setSearchQuery] = useState('');
  const [methodFilter, setMethodFilter] = useState('ALL');
  const [statusFilter, setStatusFilter] = useState('ALL');
  const [selectedGroup, setSelectedGroup] = useState('ALL');
  const [isChannelModalOpen, setIsChannelModalOpen] = useState(false);

  // Group Management & Quick Group Modals
  const [isManageGroupsModalOpen, setIsManageGroupsModalOpen] = useState(false);
  const [newGroupInputName, setNewGroupInputName] = useState('');
  const [editingGroupName, setEditingGroupName] = useState<string | null>(null);
  const [editingGroupNewName, setEditingGroupNewName] = useState('');
  const [deletingGroupName, setDeletingGroupName] = useState<string | null>(null);
  const [isProcessingGroupAction, setIsProcessingGroupAction] = useState(false);
  const [isQuickGroupModalOpen, setIsQuickGroupModalOpen] = useState(false);
  const [quickGroupTarget, setQuickGroupTarget] = useState<ApiSchedulerConfig | null>(null);
  const [quickGroupValue, setQuickGroupValue] = useState('');
  const [isSavingQuickGroup, setIsSavingQuickGroup] = useState(false);
  const [collapsedGroups, setCollapsedGroups] = useState<Record<string, boolean>>({});

  // Full Screen View Mode: 'list' | 'editor'
  const [viewMode, setViewMode] = useState<'list' | 'editor'>('list');

  // Current Active Configuration for Insomnia Editor
  const [currentConfig, setCurrentConfig] = useState<Partial<ApiSchedulerConfig>>({
    method: 'GET',
    url: '',
    name: '',
    groupName: 'General',
    authType: 'none',
    bodyType: 'json',
    targetTable: 'sch_sync.tb_api_data',
    kodeData: 'API_KODE_DATA_V1',
    cronExpression: '0 */5 * * * *',
    notificationChannelId: '',
    active: true,
  });

  // Dual Notification Toggles & Profile IDs State
  const [isTgEnabled, setIsTgEnabled] = useState<boolean>(false);
  const [isDcEnabled, setIsDcEnabled] = useState<boolean>(false);
  const [selectedTgChannelId, setSelectedTgChannelId] = useState<string>('');
  const [selectedDcChannelId, setSelectedDcChannelId] = useState<string>('');

  // Multiple Spring Cron Triggers State
  const [cronTriggers, setCronTriggers] = useState<string[]>(['0 */5 * * * *']);

  // Insomnia Tabs State
  const [activeReqTab, setActiveReqTab] = useState<'params' | 'headers' | 'auth' | 'body' | 'target' | 'schedule'>('params');
  const [queryParamsList, setQueryParamsList] = useState<KeyValuePair[]>([{ key: '', value: '', enabled: true }]);
  const [headersList, setHeadersList] = useState<KeyValuePair[]>([
    { key: 'Content-Type', value: 'application/json', enabled: true },
    { key: 'Accept', value: 'application/json', enabled: true }
  ]);

  // Test Response State (Insomnia Response Console)
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
  const [isSaving, setIsSaving] = useState(false);

  // Main Tab & Auto-MV Extractor State
  const [activeMainTab, setActiveMainTab] = useState<'schedules' | 'mv_pipelines'>('schedules');
  const [mvPipelines, setMvPipelines] = useState<Array<{ mvName: string; targetTable: string; query: string; syncedRecords: number }>>([]);
  const [loadingMvPipelines, setLoadingMvPipelines] = useState(false);
  const [isAutoMvModalOpen, setIsAutoMvModalOpen] = useState(false);
  const [inspectingSchema, setInspectingSchema] = useState(false);
  const [deployingMv, setDeployingMv] = useState(false);
  const [viewQueryModal, setViewQueryModal] = useState<{ name: string; query: string } | null>(null);
  const [isCopiedDdl, setIsCopiedDdl] = useState(false);

  const formatSql = (rawSql?: string) => {
    if (!rawSql) return '';
    let sql = rawSql.trim();
    return sql
      .replace(/\b(CREATE MATERIALIZED VIEW|CREATE TABLE|CREATE VIEW)\b/gi, '$1\n')
      .replace(/\bTO\b/gi, '\nTO')
      .replace(/\bAS\b/gi, '\nAS\n')
      .replace(/\bSELECT\b/gi, 'SELECT\n  ')
      .replace(/\bFROM\b/gi, '\nFROM\n  ')
      .replace(/\bWHERE\b/gi, '\nWHERE\n  ')
      .replace(/\bORDER BY\b/gi, '\nORDER BY\n  ')
      .replace(/,\s*/g, ',\n  ');
  };

  const handleCopyDdl = (text: string) => {
    const formatted = formatSql(text);
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(formatted);
    } else {
      const textArea = document.createElement('textarea');
      textArea.value = formatted;
      document.body.appendChild(textArea);
      textArea.select();
      document.execCommand('copy');
      document.body.removeChild(textArea);
    }
    setIsCopiedDdl(true);
    addToast({ type: 'success', title: 'Copied', message: 'Formatted DDL Query copied to clipboard' });
    setTimeout(() => setIsCopiedDdl(false), 2000);
  };

  const [autoMvForm, setAutoMvForm] = useState<{
    connectionId: string;
    sourceTable: string;
    kodeData: string;
    targetTable: string;
    createNewTable: boolean;
    backfillHistorical: boolean;
    orderByStr: string;
    existingTables: string[];
    fields: Array<{ name: string; type: string; jsonKey: string; enabled: boolean }>;
  }>({
    connectionId: '',
    sourceTable: '',
    kodeData: '',
    targetTable: '',
    createNewTable: true,
    backfillHistorical: true,
    orderByStr: '',
    existingTables: [],
    fields: []
  });

  const fetchExistingTables = async (connId?: string) => {
    const cid = connId !== undefined ? connId : autoMvForm.connectionId;
    try {
      const res = await axios.get(`/api/api-schedulers/mv-pipelines/tables${cid ? `?connectionId=${encodeURIComponent(cid)}` : ''}`);
      if (Array.isArray(res.data)) {
        setAutoMvForm(prev => ({ ...prev, existingTables: res.data }));
      }
    } catch (err: any) {
      console.error('Failed to fetch existing ClickHouse tables', err);
    }
  };

  const openNewAutoMvModal = () => {
    const chConns = connections.filter(c => (c.type && c.type.toUpperCase().includes('CLICKHOUSE')) || (c.name && c.name.toLowerCase().includes('clickhouse')));
    const defaultConnId = chConns.length > 0 ? chConns[0].id : '';

    setAutoMvForm({
      connectionId: defaultConnId,
      sourceTable: '',
      kodeData: '',
      targetTable: '',
      createNewTable: true,
      backfillHistorical: true,
      orderByStr: '',
      existingTables: [],
      fields: []
    });
    setIsAutoMvModalOpen(true);
    fetchExistingTables(defaultConnId);
  };

  const fetchMvPipelines = async (connId?: string) => {
    const cid = connId !== undefined ? connId : autoMvForm.connectionId;
    setLoadingMvPipelines(true);
    try {
      const res = await axios.get(`/api/api-schedulers/mv-pipelines${cid ? `?connectionId=${encodeURIComponent(cid)}` : ''}`);
      if (Array.isArray(res.data)) {
        setMvPipelines(res.data);
      }
    } catch (err: any) {
      console.error('Failed to fetch Auto-MV Pipelines', err);
    } finally {
      setLoadingMvPipelines(false);
    }
  };

  const handleInspectSchema = async (srcTable?: string, kData?: string, connId?: string) => {
    const sTable = srcTable !== undefined ? srcTable : autoMvForm.sourceTable;
    const kd = kData !== undefined ? kData : autoMvForm.kodeData;
    const cid = connId !== undefined ? connId : autoMvForm.connectionId;

    if (!sTable || !sTable.trim()) {
      addToast({ type: 'warning', title: 'Validation Error', message: 'Please enter a Source Raw Table name first' });
      return;
    }
    setInspectingSchema(true);
    try {
      const queryParams = new URLSearchParams({
        sourceTable: sTable.trim(),
        kodeData: (kd || '').trim(),
        connectionId: cid || ''
      });
      const res = await axios.get(`/api/api-schedulers/mv-pipelines/inspect?${queryParams.toString()}`);
      const data = res.data;
      if (data) {
        const detected = (data.fields || []).map((f: any) => ({ ...f, enabled: true }));
        setAutoMvForm(prev => ({
          ...prev,
          existingTables: data.existingTables || [],
          targetTable: prev.targetTable || data.suggestedTargetTable || '',
          fields: detected.length > 0 ? detected : prev.fields
        }));
        if (detected.length > 0) {
          addToast({ type: 'success', title: 'Schema Inspected', message: `Detected ${detected.length} JSON fields for [${kd || 'All Data'}]` });
        } else {
          addToast({ type: 'warning', title: 'No Fields Detected', message: `No sample JSON data found in [${sTable}]` });
        }
      }
    } catch (err: any) {
      addToast({ type: 'error', title: 'Inspection Failed', message: err.message });
    } finally {
      setInspectingSchema(false);
    }
  };

  const handleDeployMvPipeline = async () => {
    if (!autoMvForm.sourceTable || !autoMvForm.sourceTable.trim()) {
      addToast({ type: 'warning', title: 'Validation Error', message: 'Source Raw Table is required' });
      return;
    }
    if (!autoMvForm.kodeData || !autoMvForm.kodeData.trim()) {
      addToast({ type: 'warning', title: 'Validation Error', message: 'kode_data is required' });
      return;
    }
    if (!autoMvForm.targetTable || !autoMvForm.targetTable.trim()) {
      addToast({ type: 'warning', title: 'Validation Error', message: 'Target Table Name is required' });
      return;
    }
    const selectedFields = autoMvForm.fields.filter(f => f.enabled);
    if (selectedFields.length === 0) {
      addToast({ type: 'warning', title: 'Validation Error', message: 'Select at least 1 field to extract' });
      return;
    }

    setDeployingMv(true);
    try {
      const payload = {
        connectionId: autoMvForm.connectionId,
        sourceTable: autoMvForm.sourceTable,
        kodeData: autoMvForm.kodeData,
        targetTable: autoMvForm.targetTable,
        createNewTable: autoMvForm.createNewTable,
        backfillHistorical: autoMvForm.backfillHistorical,
        orderBy: autoMvForm.orderByStr.split(/[;,\s]+/).filter(Boolean),
        fields: selectedFields.map(f => ({ name: f.name, type: f.type, jsonKey: f.jsonKey }))
      };

      const res = await axios.post('/api/api-schedulers/mv-pipelines/deploy', payload);
      if (res.data && res.data.success) {
        addToast({
          type: 'success',
          title: 'Pipeline Deployed',
          message: `${res.data.message} (${res.data.backfilledRecords || 0} records backfilled)`
        });
        setIsAutoMvModalOpen(false);
        fetchMvPipelines(autoMvForm.connectionId);
      }
    } catch (err: any) {
      addToast({ type: 'error', title: 'Deployment Failed', message: err.response?.data?.error || err.message });
    } finally {
      setDeployingMv(false);
    }
  };

  const handleDeleteMvPipeline = (mvName: string) => {
    showAlert({
      title: 'Delete Materialized View',
      message: `Are you sure you want to drop Materialized View "${mvName}"? This will stop automatic extraction to the target table.`,
      type: 'error',
      confirmLabel: 'Drop View',
      onConfirm: async () => {
        try {
          await axios.delete(`/api/api-schedulers/mv-pipelines/${mvName}?connectionId=${encodeURIComponent(autoMvForm.connectionId || '')}`);
          addToast({ type: 'success', title: 'View Dropped', message: `Materialized View [${mvName}] removed` });
          fetchMvPipelines(autoMvForm.connectionId);
        } catch (err: any) {
          addToast({ type: 'error', title: 'Delete Failed', message: err.message });
        }
      }
    });
  };

  const fetchSchedulers = async () => {
    setLoading(true);
    try {
      const [schedRes, chanRes] = await Promise.all([
        axios.get('/api/api-schedulers'),
        axios.get('/api/notification-channels')
      ]);
      if (Array.isArray(schedRes.data)) setSchedulers(schedRes.data);
      if (Array.isArray(chanRes.data)) setChannels(chanRes.data);
    } catch (err: any) {
      addToast({ type: 'error', title: 'Error', message: 'Failed to load API schedulers or notification profiles' });
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchSchedulers();
    fetchMvPipelines();
  }, []);

  const openNewEditor = (presetGroup?: string) => {
    setCurrentConfig({
      method: 'GET',
      url: '',
      name: '',
      groupName: presetGroup || (selectedGroup !== 'ALL' ? selectedGroup : 'General'),
      authType: 'none',
      bodyType: 'json',
      targetConnectionId: connections.length > 0 ? connections[0].id : '',
      targetTable: 'sch_sync.tb_api_data',
      kodeData: 'API_KODE_V1',
      cronExpression: '0 */5 * * * *',
      notificationChannelId: '',
      active: true,
    });
    setIsTgEnabled(false);
    setIsDcEnabled(false);
    setSelectedTgChannelId('');
    setSelectedDcChannelId('');
    setCronTriggers(['0 */5 * * * *']);
    setQueryParamsList([{ key: '', value: '', enabled: true }]);
    setHeadersList([
      { key: 'Content-Type', value: 'application/json', enabled: true },
      { key: 'Accept', value: 'application/json', enabled: true }
    ]);
    setTestResponse(null);
    setActiveReqTab('params');
    setViewMode('editor');
  };

  const openEditEditor = (cfg: ApiSchedulerConfig) => {
    setCurrentConfig(cfg);

    // Parse Cron Triggers
    if (cfg.cronExpression) {
      const list = cfg.cronExpression.split(/[;,\n]+/).map(c => c.trim()).filter(Boolean);
      setCronTriggers(list.length > 0 ? list : ['0 */5 * * * *']);
    } else {
      setCronTriggers(['0 */5 * * * *']);
    }

    // Parse Notification Channel IDs (Detect both Telegram and Discord)
    if (cfg.notificationChannelId) {
      const ids = cfg.notificationChannelId.split(/[;,\n]+/).map(i => i.trim()).filter(Boolean);
      let tgId = '';
      let dcId = '';
      ids.forEach(id => {
        const chan = channels.find(c => String(c.id) === String(id));
        if (chan) {
          if (chan.type === 'TELEGRAM') tgId = chan.id;
          if (chan.type === 'DISCORD') dcId = chan.id;
        } else {
          tgId = id; // Fallback if raw ID
        }
      });

      setIsTgEnabled(!!tgId);
      setIsDcEnabled(!!dcId);
      setSelectedTgChannelId(tgId);
      setSelectedDcChannelId(dcId);
    } else {
      setIsTgEnabled(false);
      setIsDcEnabled(false);
      setSelectedTgChannelId('');
      setSelectedDcChannelId('');
    }
    
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
    setViewMode('editor');
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

  const getCompiledNotificationChannelId = () => {
    const ids = [];
    if (isTgEnabled && selectedTgChannelId) ids.push(selectedTgChannelId);
    if (isDcEnabled && selectedDcChannelId) ids.push(selectedDcChannelId);
    return ids.join('; ');
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
      cronExpression: cronTriggers.filter(c => c && c.trim()).join('; '),
      notificationChannelId: getCompiledNotificationChannelId(),
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

  const handleCopyResponse = async () => {
    if (!testResponse) return;
    const contentToCopy = activeRespTab === 'headers'
      ? (typeof testResponse.headers === 'object' ? JSON.stringify(testResponse.headers, null, 2) : String(testResponse.headers || ''))
      : getPrettyJson(testResponse.body);

    if (!contentToCopy) {
      addToast({ type: 'warning', title: 'Empty Content', message: 'No response content to copy' });
      return;
    }

    try {
      if (navigator.clipboard && navigator.clipboard.writeText) {
        await navigator.clipboard.writeText(contentToCopy);
      } else {
        const textArea = document.createElement('textarea');
        textArea.value = contentToCopy;
        textArea.style.position = 'fixed';
        textArea.style.opacity = '0';
        document.body.appendChild(textArea);
        textArea.focus();
        textArea.select();
        document.execCommand('copy');
        document.body.removeChild(textArea);
      }

      setIsCopied(true);
      setTimeout(() => setIsCopied(false), 2000);
      addToast({
        type: 'info',
        title: 'Copied to Clipboard',
        message: `API response ${activeRespTab} copied successfully`
      });
    } catch (err: any) {
      addToast({
        type: 'error',
        title: 'Copy Failed',
        message: 'Could not copy response to clipboard'
      });
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

    setIsSaving(true);
    const finalConfig = {
      ...currentConfig,
      name: currentConfig.name.trim(),
      url: currentConfig.url.trim(),
      groupName: (currentConfig.groupName || 'General').trim(),
      queryParams: compileListToJson(queryParamsList),
      headers: compileListToJson(headersList),
      cronExpression: cronTriggers.filter(c => c && c.trim()).join('; '),
      notificationChannelId: getCompiledNotificationChannelId(),
    };

    try {
      if (finalConfig.id) {
        await axios.put(`/api/api-schedulers/${finalConfig.id}`, finalConfig);
        addToast({ type: 'success', title: 'Schedule Updated', message: `Successfully updated [${finalConfig.name}]` });
      } else {
        await axios.post('/api/api-schedulers', finalConfig);
        addToast({ type: 'success', title: 'Schedule Created', message: `Successfully created [${finalConfig.name}]` });
      }
      setViewMode('list');
      fetchSchedulers();
    } catch (err: any) {
      addToast({ type: 'error', title: 'Save Failed', message: err.response?.data?.error || err.message });
    } finally {
      setIsSaving(false);
    }
  };

  const handleOpenQuickGroup = (cfg: ApiSchedulerConfig) => {
    setQuickGroupTarget(cfg);
    setQuickGroupValue(cfg.groupName || 'General');
    setIsQuickGroupModalOpen(true);
  };

  const handleSaveQuickGroup = async () => {
    if (!quickGroupTarget || !quickGroupTarget.id) return;
    setIsSavingQuickGroup(true);
    try {
      const grp = quickGroupValue.trim() || 'General';
      await axios.patch(`/api/api-schedulers/${quickGroupTarget.id}/group`, { groupName: grp });
      addToast({ type: 'success', title: 'Group Updated', message: `Moved schedule "${quickGroupTarget.name}" to group "${grp}".` });
      setIsQuickGroupModalOpen(false);
      fetchSchedulers();
    } catch (err: any) {
      addToast({ type: 'error', title: 'Update Failed', message: err.response?.data?.error || err.message });
    } finally {
      setIsSavingQuickGroup(false);
    }
  };

  const handleCreateGroup = (groupName: string) => {
    const clean = groupName.trim();
    if (!clean) return;
    setSelectedGroup(clean);
    setIsManageGroupsModalOpen(false);
    setNewGroupInputName('');
    addToast({ type: 'info', title: 'Group Selected', message: `Switched to schedule group "${clean}".` });
  };

  const handleRenameGroup = async (oldName: string, newName: string) => {
    if (!newName.trim() || newName.trim() === oldName) {
      setEditingGroupName(null);
      return;
    }
    setIsProcessingGroupAction(true);
    try {
      await axios.put('/api/api-schedulers/groups/rename', {
        oldName: oldName.trim(),
        newName: newName.trim()
      });
      addToast({
        type: 'success',
        title: 'Group Renamed',
        message: `Group "${oldName}" was renamed to "${newName.trim()}".`
      });
      setEditingGroupName(null);
      if (selectedGroup === oldName) setSelectedGroup(newName.trim());
      fetchSchedulers();
    } catch (err: any) {
      addToast({
        type: 'error',
        title: 'Rename Failed',
        message: err?.response?.data?.error || 'Failed to rename group'
      });
    } finally {
      setIsProcessingGroupAction(false);
    }
  };

  const handleDeleteGroup = async (groupName: string) => {
    setIsProcessingGroupAction(true);
    try {
      await axios.delete(`/api/api-schedulers/groups/${encodeURIComponent(groupName)}`);
      addToast({
        type: 'info',
        title: 'Group Deleted',
        message: `Group "${groupName}" was deleted. All schedules moved to "General".`
      });
      setDeletingGroupName(null);
      if (selectedGroup === groupName) setSelectedGroup('ALL');
      fetchSchedulers();
    } catch (err: any) {
      addToast({
        type: 'error',
        title: 'Delete Failed',
        message: err?.response?.data?.error || 'Failed to delete group'
      });
    } finally {
      setIsProcessingGroupAction(false);
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

  const handleDelete = (id: string, name: string) => {
    showAlert({
      title: 'Delete API Schedule',
      message: `Are you sure you want to delete the API schedule "${name}"? This action cannot be undone.`,
      type: 'error',
      confirmLabel: 'Delete Schedule',
      onConfirm: async () => {
        try {
          await axios.delete(`/api/api-schedulers/${id}`);
          addToast({ type: 'success', title: 'Deleted', message: `Schedule [${name}] removed` });
          fetchSchedulers();
        } catch (err: any) {
          addToast({ type: 'error', title: 'Delete Failed', message: err.message });
        }
      }
    });
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

  // All Groups extraction
  const allGroups = useMemo(() => {
    const set = new Set<string>();
    set.add('General');
    schedulers.forEach(s => {
      const g = (s.groupName || 'General').trim();
      if (g) set.add(g);
    });
    return Array.from(set).sort((a, b) => a === 'General' ? -1 : b === 'General' ? 1 : a.localeCompare(b));
  }, [schedulers]);

  // Filtered schedulers for Table View (Filters inside groups & across groups)
  const filteredSchedulers = useMemo(() => {
    return schedulers.filter(s => {
      const q = searchQuery.toLowerCase().trim();
      const matchesSearch = !q || 
        s.name.toLowerCase().includes(q) || 
        s.url.toLowerCase().includes(q) || 
        ((s.groupName || 'General').toLowerCase().includes(q)) ||
        (s.kodeData && s.kodeData.toLowerCase().includes(q)) ||
        (s.targetTable && s.targetTable.toLowerCase().includes(q));

      const matchesMethod = methodFilter === 'ALL' || s.method.toUpperCase() === methodFilter;
      const matchesStatus = statusFilter === 'ALL' || 
        (statusFilter === 'Active' && s.active) || 
        (statusFilter === 'Paused' && !s.active);
      const matchesGroup = selectedGroup === 'ALL' || (s.groupName || 'General') === selectedGroup;

      return matchesSearch && matchesMethod && matchesStatus && matchesGroup;
    });
  }, [schedulers, searchQuery, methodFilter, statusFilter, selectedGroup]);

  // Grouped Schedulers for Folder Accordion View
  const displayedGroups = useMemo(() => {
    const groupsToConsider = selectedGroup === 'ALL' ? allGroups : [selectedGroup];
    return groupsToConsider.map(grp => {
      const items = filteredSchedulers.filter(s => (s.groupName || 'General') === grp);
      return {
        groupName: grp,
        items,
        count: items.length,
        totalInGroup: schedulers.filter(s => (s.groupName || 'General') === grp).length
      };
    }).filter(g => {
      if (searchQuery.trim()) {
        return g.items.length > 0;
      }
      if (selectedGroup !== 'ALL') return true;
      return g.items.length > 0 || g.groupName === 'General';
    });
  }, [allGroups, filteredSchedulers, selectedGroup, searchQuery, schedulers]);

  const toggleGroupCollapse = (grp: string) => {
    setCollapsedGroups(prev => ({
      ...prev,
      [grp]: !prev[grp]
    }));
  };

  const expandAllGroups = () => {
    setCollapsedGroups({});
  };

  const collapseAllGroups = () => {
    const all: Record<string, boolean> = {};
    allGroups.forEach(g => { all[g] = true; });
    setCollapsedGroups(all);
  };

  const getPrettyJson = (raw: string | undefined) => {
    if (!raw) return '';
    try {
      return JSON.stringify(JSON.parse(raw), null, 2);
    } catch (e) {
      return raw;
    }
  };

  // Render Full Screen Insomnia Editor
  if (viewMode === 'editor') {
    const telegramChannels = channels.filter(c => c.type === 'TELEGRAM');
    const discordChannels = channels.filter(c => c.type === 'DISCORD');

    return (
      <div className="h-full flex flex-col bg-bg-main text-text-main overflow-hidden animate-fadeIn">
        
        {/* Top Navigation Bar (Clean & Sleek Header) */}
        <div className="bg-bg-panel border-b border-border-main px-4 py-3 flex items-center justify-between gap-3 shrink-0 shadow-sm z-20">
          {/* Left: Back Button & Page Title */}
          <div className="flex items-center gap-3">
            <button
              onClick={() => setViewMode('list')}
              className="flex items-center gap-2 px-3.5 py-2 rounded-xl bg-bg-main hover:bg-bg-hover text-text-muted hover:text-text-main border border-border-main transition-colors text-xs font-semibold"
            >
              <ArrowLeft className="w-4 h-4" />
              <span>Back to Schedules</span>
            </button>

            <div className="h-5 w-px bg-border-main hidden md:block" />

            <div>
              <h2 className="text-sm font-bold text-text-main flex items-center gap-2">
                <Globe className="w-4 h-4 text-cyan-500" />
                <span>{currentConfig.id ? `Edit Schedule: ${currentConfig.name || 'API Endpoint'}` : 'New API Ingestion Schedule'}</span>
              </h2>
              <p className="text-[11px] text-text-muted hidden md:block">
                Configure HTTP client, target database schema, Spring Cron, and failure alerts
              </p>
            </div>
          </div>

          {/* Right Action Buttons: Test Endpoint & Save Schedule */}
          <div className="flex items-center gap-2.5 shrink-0">
            <button
              onClick={handleTestEndpoint}
              disabled={isTesting}
              className="flex items-center gap-2 px-4 py-2.5 rounded-xl bg-amber-500 hover:bg-amber-400 text-slate-950 font-bold text-xs shadow-md shadow-amber-500/20 transition-all disabled:opacity-50"
              title="Send HTTP Request & View Live Response"
            >
              {isTesting ? <Loader2 className="w-4 h-4 animate-spin" /> : <Zap className="w-4 h-4 fill-slate-950" />}
              <span>Test Endpoint</span>
            </button>

            <button
              onClick={handleSaveSchedule}
              disabled={isSaving}
              className="flex items-center gap-2 px-4 py-2.5 rounded-xl bg-gradient-to-r from-blue-600 via-cyan-500 to-blue-500 hover:from-blue-500 hover:to-cyan-400 text-white font-bold text-xs shadow-md shadow-blue-500/25 hover:shadow-blue-500/40 transition-all disabled:opacity-50"
            >
              {isSaving ? <Loader2 className="w-4 h-4 animate-spin" /> : <Save className="w-4 h-4" />}
              <span>Save Schedule</span>
            </button>
          </div>
        </div>

        {/* Main Editor Body Container */}
        <div className="flex-1 flex flex-col min-h-0 overflow-hidden p-4 gap-4">
          
          {/* Theme-Aware Insomnia Request Command Header Card */}
          <div className="relative bg-bg-panel border border-border-main p-4 md:p-5 rounded-2xl shadow-sm shrink-0 space-y-3.5 overflow-hidden">
            
            {/* Top Ambient Glow Line */}
            <div className="absolute top-0 left-0 right-0 h-1 bg-gradient-to-r from-blue-500 via-cyan-400 to-indigo-500" />

            {/* Row 1: Schedule Name Input, Group Selector & Live Badges */}
            <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3">
              <div className="flex items-center gap-2 flex-1 max-w-2xl">
                <div className="p-1.5 rounded-lg bg-blue-500/15 text-blue-500 border border-blue-500/30 shrink-0">
                  <Pencil className="w-3.5 h-3.5" />
                </div>
                <input
                  type="text"
                  placeholder="Schedule Identifier Name (e.g. Daily Weather Ingestion API)"
                  value={currentConfig.name || ''}
                  onChange={(e) => setCurrentConfig({ ...currentConfig, name: e.target.value })}
                  className="flex-1 bg-bg-main border border-border-main rounded-xl px-3.5 py-2 text-xs font-bold text-text-main placeholder:text-text-muted focus:outline-none focus:border-cyan-500 focus:ring-2 focus:ring-cyan-500/20 transition-all shadow-inner"
                />

                {/* Group Selector */}
                <div className="relative w-44 shrink-0">
                  <input
                    list="scheduler-group-list"
                    type="text"
                    placeholder="Group (e.g. General)"
                    value={currentConfig.groupName || 'General'}
                    onChange={(e) => setCurrentConfig({ ...currentConfig, groupName: e.target.value })}
                    className="w-full bg-bg-main border border-border-main rounded-xl px-3 py-2 text-xs font-bold text-indigo-400 placeholder:text-text-muted focus:outline-none focus:border-indigo-500 focus:ring-2 focus:ring-indigo-500/20 transition-all shadow-inner"
                  />
                  <datalist id="scheduler-group-list">
                    {allGroups.map(g => (
                      <option key={g} value={g} />
                    ))}
                  </datalist>
                </div>
              </div>

              <div className="flex items-center gap-2 shrink-0">
                {(() => {
                  const activeCrons = cronTriggers.filter(c => c && c.trim());
                  const count = activeCrons.length;
                  const label = count === 0 
                    ? 'No Cron' 
                    : count === 1 
                      ? activeCrons[0] 
                      : `${activeCrons[0]} (+${count - 1} more)`;
                  return (
                    <span 
                      className="inline-flex items-center gap-1.5 px-3 py-1 rounded-xl text-[11px] font-mono font-bold bg-amber-500/10 text-amber-700 dark:text-amber-300 border border-amber-500/30 max-w-[200px] sm:max-w-[250px]"
                      title={activeCrons.join('\n')}
                    >
                      <Clock className="w-3 h-3 text-amber-600 dark:text-amber-400 shrink-0" />
                      <span className="truncate">{label}</span>
                    </span>
                  );
                })()}
                <span 
                  className="inline-flex items-center gap-1.5 px-3 py-1 rounded-xl text-[11px] font-mono font-bold bg-cyan-500/10 text-cyan-700 dark:text-cyan-300 border border-cyan-500/30 max-w-[150px]"
                  title={currentConfig.kodeData || 'API_KODE'}
                >
                  <Database className="w-3 h-3 text-cyan-600 dark:text-cyan-400 shrink-0" />
                  <span className="truncate">{currentConfig.kodeData || 'API_KODE'}</span>
                </span>
              </div>
            </div>

            {/* Row 2: Unified Insomnia HTTP Omnibar (Method + URL) */}
            <div className="flex items-center bg-bg-main border border-border-main rounded-xl p-1.5 shadow-inner focus-within:border-blue-500 focus-within:ring-2 focus-within:ring-blue-500/30 transition-all">
              
              {/* HTTP Method Dropdown Pill */}
              <div className="relative shrink-0">
                <select
                  value={currentConfig.method || 'GET'}
                  onChange={(e) => setCurrentConfig({ ...currentConfig, method: e.target.value })}
                  className={clsx(
                    "px-4 py-2 rounded-lg text-xs font-black tracking-wider focus:outline-none cursor-pointer appearance-none transition-all shadow-sm pr-7",
                    getMethodBadgeClass(currentConfig.method || 'GET')
                  )}
                >
                  <option value="GET" className="bg-bg-panel text-emerald-600 dark:text-emerald-400 font-bold">GET</option>
                  <option value="POST" className="bg-bg-panel text-blue-600 dark:text-blue-400 font-bold">POST</option>
                  <option value="PUT" className="bg-bg-panel text-amber-600 dark:text-amber-400 font-bold">PUT</option>
                  <option value="DELETE" className="bg-bg-panel text-rose-600 dark:text-rose-400 font-bold">DELETE</option>
                  <option value="PATCH" className="bg-bg-panel text-purple-600 dark:text-purple-400 font-bold">PATCH</option>
                </select>
                <div className="pointer-events-none absolute right-2.5 top-1/2 -translate-y-1/2 text-current opacity-70 text-[9px]">
                  ▼
                </div>
              </div>

              {/* Vertical Divider */}
              <div className="h-6 w-px bg-border-main mx-2 shrink-0" />

              {/* Endpoint URL Field */}
              <div className="flex items-center gap-2 flex-1 min-w-0 pr-2">
                <Globe className="w-4 h-4 text-cyan-500 shrink-0 ml-1 opacity-80" />
                <input
                  type="text"
                  placeholder="https://api.example.com/v1/data..."
                  value={currentConfig.url || ''}
                  onChange={(e) => setCurrentConfig({ ...currentConfig, url: e.target.value })}
                  className="w-full bg-transparent border-0 text-xs font-mono font-bold text-text-main dark:text-emerald-300 placeholder:text-text-muted focus:outline-none focus:ring-0 selection:bg-blue-500/40"
                />
              </div>

            </div>

          </div>

          {/* Main Split Insomnia Workspace (Left: Request Config Tabs | Right: Live Response Console) */}
          <div className="flex-1 grid grid-cols-1 lg:grid-cols-12 min-h-0 divide-y lg:divide-y-0 lg:divide-x divide-border-main overflow-hidden bg-bg-panel border border-border-main rounded-2xl shadow-sm">
            
            {/* Left Column: Insomnia Request Workbench (7 Cols) */}
            <div className="lg:col-span-7 flex flex-col min-h-0 bg-bg-panel">
              
              {/* Request Tabs Header */}
              <div className="flex items-center gap-1 border-b border-border-main bg-bg-main px-4 pt-2.5 overflow-x-auto shrink-0 z-10">
                {[
                  { id: 'params', label: 'Params', icon: Search },
                  { id: 'headers', label: 'Headers', icon: FileText },
                  { id: 'auth', label: 'Auth', icon: ShieldCheck },
                  { id: 'body', label: 'Body', icon: Code2 },
                  { id: 'target', label: 'Target Storage', icon: Database },
                  { id: 'schedule', label: 'Schedule & Alerts', icon: Clock },
                ].map((tab) => {
                  const Icon = tab.icon;
                  return (
                    <button
                      key={tab.id}
                      onClick={() => setActiveReqTab(tab.id as any)}
                      className={clsx(
                        "flex items-center gap-2 px-4 py-2.5 text-xs font-semibold rounded-t-xl transition-all border-t border-x",
                        activeReqTab === tab.id
                          ? "bg-bg-panel text-blue-500 dark:text-blue-400 border-border-main border-b-transparent -mb-px font-bold shadow-sm"
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
              <div className="flex-1 p-5 overflow-y-auto">
                
                {/* TAB 1: QUERY PARAMS */}
                {activeReqTab === 'params' && (
                  <div className="space-y-4">
                    <div className="flex items-center justify-between">
                      <div>
                        <h4 className="text-xs font-bold text-text-main">URL Query Parameters</h4>
                        <p className="text-[11px] text-text-muted">Appended as URL query string e.g. ?page=1&limit=50</p>
                      </div>
                      <button
                        onClick={() => setQueryParamsList([...queryParamsList, { key: '', value: '', enabled: true }])}
                        className="flex items-center gap-1.5 px-3 py-1.5 rounded-xl bg-blue-500/10 text-blue-600 dark:text-blue-400 text-xs font-semibold hover:bg-blue-500/20 transition-colors border border-blue-500/20"
                      >
                        <Plus className="w-3.5 h-3.5" />
                        <span>Add Param</span>
                      </button>
                    </div>

                    <div className="space-y-2">
                      {queryParamsList.map((item, idx) => (
                        <div key={idx} className="flex items-center gap-2.5 group">
                          <input
                            type="checkbox"
                            checked={item.enabled}
                            onChange={(e) => {
                              const copy = [...queryParamsList];
                              copy[idx].enabled = e.target.checked;
                              setQueryParamsList(copy);
                            }}
                            className="w-4 h-4 rounded border-border-main text-blue-500 focus:ring-0 cursor-pointer"
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
                            className="flex-1 bg-bg-main border border-border-main rounded-xl px-3.5 py-2 text-xs text-text-main font-mono placeholder:text-text-muted focus:outline-none focus:border-blue-500 transition-colors"
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
                            className="flex-1 bg-bg-main border border-border-main rounded-xl px-3.5 py-2 text-xs text-text-main font-mono placeholder:text-text-muted focus:outline-none focus:border-blue-500 transition-colors"
                          />
                          <button
                            onClick={() => setQueryParamsList(queryParamsList.filter((_, i) => i !== idx))}
                            className="p-2 text-text-muted hover:text-rose-500 hover:bg-rose-500/10 rounded-lg transition-colors"
                          >
                            <Trash2 className="w-4 h-4" />
                          </button>
                        </div>
                      ))}
                    </div>
                  </div>
                )}

                {/* TAB 2: HTTP HEADERS */}
                {activeReqTab === 'headers' && (
                  <div className="space-y-4">
                    <div className="flex items-center justify-between">
                      <div>
                        <h4 className="text-xs font-bold text-text-main">HTTP Request Headers</h4>
                        <p className="text-[11px] text-text-muted">Custom HTTP headers sent with the request</p>
                      </div>
                      <button
                        onClick={() => setHeadersList([...headersList, { key: '', value: '', enabled: true }])}
                        className="flex items-center gap-1.5 px-3 py-1.5 rounded-xl bg-blue-500/10 text-blue-600 dark:text-blue-400 text-xs font-semibold hover:bg-blue-500/20 transition-colors border border-blue-500/20"
                      >
                        <Plus className="w-3.5 h-3.5" />
                        <span>Add Header</span>
                      </button>
                    </div>

                    <div className="space-y-2">
                      {headersList.map((item, idx) => (
                        <div key={idx} className="flex items-center gap-2.5 group">
                          <input
                            type="checkbox"
                            checked={item.enabled}
                            onChange={(e) => {
                              const copy = [...headersList];
                              copy[idx].enabled = e.target.checked;
                              setHeadersList(copy);
                            }}
                            className="w-4 h-4 rounded border-border-main text-blue-500 focus:ring-0 cursor-pointer"
                          />
                          <input
                            type="text"
                            placeholder="Header (e.g. Authorization)"
                            value={item.key}
                            onChange={(e) => {
                              const copy = [...headersList];
                              copy[idx].key = e.target.value;
                              setHeadersList(copy);
                            }}
                            className="flex-1 bg-bg-main border border-border-main rounded-xl px-3.5 py-2 text-xs text-text-main font-mono placeholder:text-text-muted focus:outline-none focus:border-blue-500 transition-colors"
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
                            className="flex-1 bg-bg-main border border-border-main rounded-xl px-3.5 py-2 text-xs text-text-main font-mono placeholder:text-text-muted focus:outline-none focus:border-blue-500 transition-colors"
                          />
                          <button
                            onClick={() => setHeadersList(headersList.filter((_, i) => i !== idx))}
                            className="p-2 text-text-muted hover:text-rose-500 hover:bg-rose-500/10 rounded-lg transition-colors"
                          >
                            <Trash2 className="w-4 h-4" />
                          </button>
                        </div>
                      ))}
                    </div>
                  </div>
                )}

                {/* TAB 3: AUTHENTICATION */}
                {activeReqTab === 'auth' && (
                  <div className="space-y-5 max-w-lg">
                    <div>
                      <label className="block text-xs font-bold text-text-main mb-2">Authentication Mechanism</label>
                      <div className="grid grid-cols-3 gap-2.5">
                        {['none', 'basic', 'bearer'].map(auth => (
                          <button
                            key={auth}
                            type="button"
                            onClick={() => setCurrentConfig({ ...currentConfig, authType: auth })}
                            className={clsx(
                              "py-2.5 px-4 rounded-xl text-xs font-bold capitalize transition-all border text-center",
                              currentConfig.authType === auth
                                ? "bg-blue-500/20 text-blue-600 dark:text-blue-400 border-blue-500/40 shadow-sm"
                                : "bg-bg-main text-text-muted border-border-main hover:text-text-main hover:bg-bg-hover"
                            )}
                          >
                            {auth}
                          </button>
                        ))}
                      </div>
                    </div>

                    {currentConfig.authType === 'basic' && (
                      <div className="space-y-3.5 bg-bg-main p-4 border border-border-main rounded-2xl shadow-inner">
                        <div>
                          <label className="block text-xs font-semibold text-text-muted mb-1">Username</label>
                          <input
                            type="text"
                            value={currentConfig.authUsername || ''}
                            onChange={(e) => setCurrentConfig({ ...currentConfig, authUsername: e.target.value })}
                            className="w-full bg-bg-panel border border-border-main rounded-xl px-3.5 py-2 text-xs text-text-main focus:outline-none focus:border-blue-500"
                          />
                        </div>
                        <div>
                          <label className="block text-xs font-semibold text-text-muted mb-1">Password</label>
                          <input
                            type="password"
                            value={currentConfig.authPassword || ''}
                            onChange={(e) => setCurrentConfig({ ...currentConfig, authPassword: e.target.value })}
                            className="w-full bg-bg-panel border border-border-main rounded-xl px-3.5 py-2 text-xs text-text-main focus:outline-none focus:border-blue-500"
                          />
                        </div>
                      </div>
                    )}

                    {currentConfig.authType === 'bearer' && (
                      <div className="bg-bg-main p-4 border border-border-main rounded-2xl shadow-inner">
                        <label className="block text-xs font-semibold text-text-muted mb-1">Bearer Token</label>
                        <input
                          type="text"
                          placeholder="eyJhbGciOiJIUzI1NiIsInR5cCI6..."
                          value={currentConfig.authToken || ''}
                          onChange={(e) => setCurrentConfig({ ...currentConfig, authToken: e.target.value })}
                          className="w-full bg-bg-panel border border-border-main rounded-xl px-3.5 py-2 text-xs font-mono text-text-main focus:outline-none focus:border-blue-500"
                        />
                      </div>
                    )}
                  </div>
                )}

                {/* TAB 4: REQUEST BODY */}
                {activeReqTab === 'body' && (
                  <div className="space-y-4 h-full flex flex-col">
                    <div className="flex items-center gap-3">
                      <span className="text-xs font-bold text-text-main">Body Payload Format:</span>
                      {['none', 'json', 'text'].map(type => (
                        <button
                          key={type}
                          type="button"
                          onClick={() => setCurrentConfig({ ...currentConfig, bodyType: type })}
                          className={clsx(
                            "px-3 py-1.5 rounded-xl text-xs font-semibold capitalize transition-all border",
                            currentConfig.bodyType === type
                              ? "bg-blue-500/20 text-blue-600 dark:text-blue-400 border-blue-500/40 font-bold"
                              : "bg-bg-main text-text-muted border-border-main hover:text-text-main"
                          )}
                        >
                          {type}
                        </button>
                      ))}
                    </div>

                    {currentConfig.bodyType !== 'none' && (
                      <div className="flex-1 flex flex-col min-h-[280px]">
                        <textarea
                          placeholder='{ "key": "value", "filter": "active" }'
                          value={currentConfig.bodyContent || ''}
                          onChange={(e) => setCurrentConfig({ ...currentConfig, bodyContent: e.target.value })}
                          rows={12}
                          className="w-full flex-1 bg-bg-main border border-border-main rounded-2xl p-4 text-xs font-mono text-emerald-600 dark:text-emerald-400 placeholder:text-text-muted focus:outline-none focus:border-blue-500 resize-none shadow-inner"
                        />
                      </div>
                    )}
                  </div>
                )}

                {/* TAB 5: TARGET STORAGE (ClickHouse & PostgreSQL 5-Column Ingestion Validation) */}
                {activeReqTab === 'target' && (
                  <div className="space-y-5">
                    <div className="bg-cyan-500/10 border border-cyan-500/30 p-4 rounded-2xl flex items-start gap-3.5 shadow-sm">
                      <Database className="w-5 h-5 text-cyan-600 dark:text-cyan-400 shrink-0 mt-0.5" />
                      <div className="text-xs text-text-main space-y-2">
                        <p className="font-bold text-cyan-700 dark:text-cyan-300 text-sm">Strict Target Schema Requirement (Manual DDL)</p>
                        <p className="leading-relaxed text-text-muted">
                          Tabel target <b>wajib dibuat secara manual terlebih dahulu</b> di ClickHouse / PostgreSQL dengan 5 kolom standar berikut. Jika tabel tidak ada atau kolom tidak sesuai standar, ingest data akan gagal dan notifikasi alert kegagalan akan dikirim ke Telegram & Discord:
                        </p>
                        <div className="flex flex-wrap gap-2 font-mono text-[11px]">
                          <span className="bg-bg-panel px-2.5 py-1 rounded-lg border border-cyan-500/30 text-cyan-700 dark:text-cyan-300 font-bold">1. seq (Primary Key / Sequence)</span>
                          <span className="bg-bg-panel px-2.5 py-1 rounded-lg border border-cyan-500/30 text-cyan-700 dark:text-cyan-300 font-bold">2. kode_data (Custom Identifier)</span>
                          <span className="bg-bg-panel px-2.5 py-1 rounded-lg border border-cyan-500/30 text-cyan-700 dark:text-cyan-300 font-bold">3. detail_data (Raw Response JSON)</span>
                          <span className="bg-bg-panel px-2.5 py-1 rounded-lg border border-cyan-500/30 text-cyan-700 dark:text-cyan-300 font-bold">4. input_by ('darkosync')</span>
                          <span className="bg-bg-panel px-2.5 py-1 rounded-lg border border-cyan-500/30 text-cyan-700 dark:text-cyan-300 font-bold">5. input_dt (Timestamp)</span>
                        </div>
                      </div>
                    </div>

                    <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                      <div>
                        <label className="block text-xs font-bold text-text-main mb-1.5">Target Database Connection</label>
                        <select
                          value={currentConfig.targetConnectionId || ''}
                          onChange={(e) => setCurrentConfig({ ...currentConfig, targetConnectionId: e.target.value })}
                          className="w-full bg-bg-main border border-border-main rounded-xl px-3.5 py-2.5 text-xs text-text-main focus:outline-none focus:border-blue-500 cursor-pointer font-medium"
                        >
                          <option value="">Select Connection (ClickHouse / PostgreSQL)...</option>
                          {connections.map(c => (
                            <option key={c.id} value={c.id}>{c.name} ({c.type} - {c.host})</option>
                          ))}
                        </select>
                      </div>

                      <div>
                        <label className="block text-xs font-bold text-text-main mb-1.5">Target Table Name</label>
                        <input
                          type="text"
                          placeholder="e.g. sch_sync.tb_api_data or public.tb_weather"
                          value={currentConfig.targetTable || ''}
                          onChange={(e) => setCurrentConfig({ ...currentConfig, targetTable: e.target.value })}
                          className="w-full bg-bg-main border border-border-main rounded-xl px-3.5 py-2.5 text-xs font-mono text-text-main focus:outline-none focus:border-blue-500"
                        />
                      </div>
                    </div>

                    <div>
                      <label className="block text-xs font-bold text-text-main mb-1.5">Kode Data Identifier (User Input)</label>
                      <input
                        type="text"
                        placeholder="e.g. KODE_WEATHER_V1 or SALES_DAILY_API"
                        value={currentConfig.kodeData || ''}
                        onChange={(e) => setCurrentConfig({ ...currentConfig, kodeData: e.target.value })}
                        className="w-full bg-bg-main border border-border-main rounded-xl px-3.5 py-2.5 text-xs font-mono text-text-main focus:outline-none focus:border-blue-500"
                      />
                      <p className="text-[11px] text-text-muted mt-1.5">
                        String unik ini akan disimpan di kolom `kode_data` untuk mempermudah query dan pembedaan dataset.
                      </p>
                    </div>
                  </div>
                )}

                {/* TAB 6: SCHEDULE & ALERTS (Multiple Spring Cron Triggers + Dual Telegram & Discord Selection) */}
                {activeReqTab === 'schedule' && (
                  <div className="space-y-6 max-w-2xl">
                    
                    {/* Spring Cron Expression Section (Multiple Triggers Supported) */}
                    <div className="space-y-4">
                      <div className="flex items-center justify-between">
                        <div>
                          <h4 className="text-xs font-bold text-text-main">Spring Cron Schedule Triggers</h4>
                          <p className="text-[11px] text-text-muted">Standard 6-field Spring Cron (sec min hr day month weekday). Add multiple rules to schedule multiple periodic triggers.</p>
                        </div>
                        <button
                          type="button"
                          onClick={() => setCronTriggers([...cronTriggers, '0 0 12 * * *'])}
                          className="flex items-center gap-1.5 px-3 py-1.5 rounded-xl bg-amber-500/10 text-amber-700 dark:text-amber-300 hover:bg-amber-500/20 text-xs font-bold transition-colors border border-amber-500/30 shadow-sm"
                        >
                          <Plus className="w-3.5 h-3.5" />
                          <span>Add Cron Trigger</span>
                        </button>
                      </div>

                      <div className="space-y-2.5">
                        {cronTriggers.map((cron, idx) => (
                          <div key={idx} className="flex items-center gap-2.5 group">
                            <div className="flex-1 bg-bg-main p-3 border border-border-main rounded-xl shadow-inner flex items-center gap-2">
                              <Clock className="w-4 h-4 text-amber-600 dark:text-amber-400 shrink-0" />
                              <input
                                type="text"
                                placeholder="e.g. 0 */5 * * * * or 0 0 12 * * *"
                                value={cron}
                                onChange={(e) => {
                                  const copy = [...cronTriggers];
                                  copy[idx] = e.target.value;
                                  setCronTriggers(copy);
                                }}
                                className="w-full bg-transparent border-0 text-xs font-mono font-bold text-amber-700 dark:text-amber-300 focus:outline-none focus:ring-0 placeholder:text-text-muted"
                              />
                            </div>
                            {cronTriggers.length > 1 && (
                              <button
                                type="button"
                                onClick={() => setCronTriggers(cronTriggers.filter((_, i) => i !== idx))}
                                className="p-2.5 text-text-muted hover:text-rose-500 hover:bg-rose-500/10 rounded-xl transition-colors border border-transparent hover:border-rose-500/20"
                                title="Remove Cron Trigger"
                              >
                                <Trash2 className="w-4 h-4" />
                              </button>
                            )}
                          </div>
                        ))}
                      </div>

                      <div className="p-3 rounded-xl bg-amber-500/10 border border-amber-500/25 text-xs text-amber-800 dark:text-amber-200 flex items-start gap-2.5">
                        <Zap className="w-4 h-4 text-amber-600 dark:text-amber-400 shrink-0 mt-0.5" />
                        <span className="leading-relaxed font-medium">
                          Setiap aturan Spring Cron di atas akan mengeksekusi fetch API secara mandiri, memasukkan data ke tabel target, dan mengirim notifikasi jika terjadi kegagalan.
                        </span>
                      </div>
                    </div>

                    {/* Telegram & Discord Notification Profile Integration (Simultaneous Dual Activation) */}
                    <div className="space-y-4 pt-4 border-t border-border-main">
                      <div className="flex items-center justify-between">
                        <div className="flex items-center gap-2">
                          <Bell className="w-4 h-4 text-purple-600 dark:text-purple-400" />
                          <div>
                            <h4 className="text-xs font-bold text-text-main">Failure Alert Notification Profiles</h4>
                            <p className="text-[11px] text-text-muted">Sends failure alert to Telegram, Discord, or BOTH simultaneously</p>
                          </div>
                        </div>

                        <button
                          type="button"
                          onClick={() => setIsChannelModalOpen(true)}
                          className="flex items-center gap-1.5 px-3 py-1.5 rounded-xl bg-purple-500/15 text-purple-700 dark:text-purple-300 hover:bg-purple-500/25 border border-purple-500/30 text-xs font-bold transition-all"
                        >
                          <Settings className="w-3.5 h-3.5" />
                          <span>Manage Profiles</span>
                        </button>
                      </div>

                      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                        
                        {/* Telegram Profile Card */}
                        <div className={clsx(
                          "p-4 rounded-2xl border transition-all space-y-3",
                          isTgEnabled 
                            ? "bg-blue-500/10 border-blue-500/40 shadow-sm" 
                            : "bg-bg-main border-border-main hover:border-blue-500/30"
                        )}>
                          <div 
                            onClick={() => {
                              const next = !isTgEnabled;
                              setIsTgEnabled(next);
                              if (next && !selectedTgChannelId && telegramChannels.length > 0) {
                                setSelectedTgChannelId(telegramChannels[0].id);
                              }
                            }}
                            className="flex items-center justify-between cursor-pointer select-none"
                          >
                            <div className="flex items-center gap-2">
                              <MessageCircle className="w-4.5 h-4.5 text-blue-500" />
                              <div>
                                <span className="text-xs font-bold text-text-main block">✈️ Telegram Alerts</span>
                                <span className="text-[10px] text-text-muted">
                                  {isTgEnabled ? 'Enabled for this schedule' : 'Click to enable Telegram notifications'}
                                </span>
                              </div>
                            </div>
                            <input
                              type="checkbox"
                              checked={isTgEnabled}
                              onChange={(e) => {
                                e.stopPropagation();
                                const next = e.target.checked;
                                setIsTgEnabled(next);
                                if (next && !selectedTgChannelId && telegramChannels.length > 0) {
                                  setSelectedTgChannelId(telegramChannels[0].id);
                                }
                              }}
                              className="w-4.5 h-4.5 rounded border-border-main text-blue-500 focus:ring-0 cursor-pointer"
                            />
                          </div>

                          {isTgEnabled && (
                            <div className="space-y-2 pt-2 border-t border-blue-500/20 animate-fadeIn">
                              <label className="block text-[11px] font-semibold text-text-muted">Select Telegram Profile</label>
                              {telegramChannels.length === 0 ? (
                                <div className="p-3 bg-blue-500/10 border border-blue-500/25 rounded-xl text-xs text-blue-700 dark:text-blue-300 space-y-2">
                                  <p className="font-medium">Belum ada profile Telegram yang tersimpan.</p>
                                  <button 
                                    type="button" 
                                    onClick={(e) => {
                                      e.stopPropagation();
                                      setIsChannelModalOpen(true);
                                    }} 
                                    className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-blue-500 text-white font-bold text-xs hover:bg-blue-600 transition-all shadow-sm"
                                  >
                                    <Plus className="w-3.5 h-3.5" />
                                    <span>+ Tambah Profile Telegram</span>
                                  </button>
                                </div>
                              ) : (
                                <select
                                  value={selectedTgChannelId}
                                  onChange={(e) => setSelectedTgChannelId(e.target.value)}
                                  className="w-full bg-bg-panel border border-border-main rounded-xl px-3 py-2 text-xs font-semibold text-text-main focus:outline-none focus:border-blue-500 cursor-pointer"
                                >
                                  {telegramChannels.map(chan => (
                                    <option key={chan.id} value={chan.id}>
                                      {chan.name} (Chat ID: {chan.chatId || '-'})
                                    </option>
                                  ))}
                                </select>
                              )}
                            </div>
                          )}
                        </div>

                        {/* Discord Profile Card */}
                        <div className={clsx(
                          "p-4 rounded-2xl border transition-all space-y-3",
                          isDcEnabled 
                            ? "bg-indigo-500/10 border-indigo-500/40 shadow-sm" 
                            : "bg-bg-main border-border-main hover:border-indigo-500/30"
                        )}>
                          <div 
                            onClick={() => {
                              const next = !isDcEnabled;
                              setIsDcEnabled(next);
                              if (next && !selectedDcChannelId && discordChannels.length > 0) {
                                setSelectedDcChannelId(discordChannels[0].id);
                              }
                            }}
                            className="flex items-center justify-between cursor-pointer select-none"
                          >
                            <div className="flex items-center gap-2">
                              <Send className="w-4.5 h-4.5 text-indigo-500" />
                              <div>
                                <span className="text-xs font-bold text-text-main block">💬 Discord Webhook Alerts</span>
                                <span className="text-[10px] text-text-muted">
                                  {isDcEnabled ? 'Enabled for this schedule' : 'Click to enable Discord notifications'}
                                </span>
                              </div>
                            </div>
                            <input
                              type="checkbox"
                              checked={isDcEnabled}
                              onChange={(e) => {
                                e.stopPropagation();
                                const next = e.target.checked;
                                setIsDcEnabled(next);
                                if (next && !selectedDcChannelId && discordChannels.length > 0) {
                                  setSelectedDcChannelId(discordChannels[0].id);
                                }
                              }}
                              className="w-4.5 h-4.5 rounded border-border-main text-indigo-500 focus:ring-0 cursor-pointer"
                            />
                          </div>

                          {isDcEnabled && (
                            <div className="space-y-2 pt-2 border-t border-indigo-500/20 animate-fadeIn">
                              <label className="block text-[11px] font-semibold text-text-muted">Select Discord Profile</label>
                              {discordChannels.length === 0 ? (
                                <div className="p-3 bg-indigo-500/10 border border-indigo-500/25 rounded-xl text-xs text-indigo-700 dark:text-indigo-300 space-y-2">
                                  <p className="font-medium">Belum ada profile Discord Webhook yang tersimpan.</p>
                                  <button 
                                    type="button" 
                                    onClick={(e) => {
                                      e.stopPropagation();
                                      setIsChannelModalOpen(true);
                                    }} 
                                    className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-indigo-600 text-white font-bold text-xs hover:bg-indigo-700 transition-all shadow-sm"
                                  >
                                    <Plus className="w-3.5 h-3.5" />
                                    <span>+ Tambah Profile Discord</span>
                                  </button>
                                </div>
                              ) : (
                                <select
                                  value={selectedDcChannelId}
                                  onChange={(e) => setSelectedDcChannelId(e.target.value)}
                                  className="w-full bg-bg-panel border border-border-main rounded-xl px-3 py-2 text-xs font-semibold text-text-main focus:outline-none focus:border-indigo-500 cursor-pointer"
                                >
                                  {discordChannels.map(chan => (
                                    <option key={chan.id} value={chan.id}>
                                      {chan.name} (Webhook Configured)
                                    </option>
                                  ))}
                                </select>
                              )}
                            </div>
                          )}
                        </div>

                      </div>

                      {(isTgEnabled || isDcEnabled) && (
                        <div className="p-3.5 rounded-xl bg-purple-500/10 border border-purple-500/20 text-xs text-purple-700 dark:text-purple-200 flex items-center gap-2">
                          <Bell className="w-4 h-4 text-purple-500 shrink-0" />
                          <span>
                            Notifikasi alert kegagalan (FAILED) akan dikirimkan secara bersamaan ke: <b>{[isTgEnabled && 'Telegram', isDcEnabled && 'Discord'].filter(Boolean).join(' & ')}</b>.
                          </span>
                        </div>
                      )}
                    </div>

                    {/* Enable Active Switch */}
                    <div className="flex items-center justify-between bg-bg-main p-4 border border-border-main rounded-2xl shadow-sm">
                      <div>
                        <span className="block text-xs font-bold text-text-main">Enable Schedule Immediately</span>
                        <span className="text-[11px] text-text-muted">Automated background execution on interval trigger</span>
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

            {/* Right Column: Insomnia Live Response Console & Viewer (5 Cols) */}
            <div className="lg:col-span-5 flex flex-col min-h-0 bg-bg-main">
              
              {/* Console Header Bar */}
              <div className="p-3.5 border-b border-border-main flex items-center justify-between bg-bg-panel shrink-0 shadow-sm">
                <div className="flex items-center gap-2.5">
                  <span className="text-xs font-bold uppercase tracking-wider text-text-muted">Response Console</span>
                  {testResponse && (
                    <span className={clsx(
                      "px-2.5 py-0.5 rounded-full text-xs font-bold shadow-sm",
                      testResponse.statusCode && testResponse.statusCode >= 200 && testResponse.statusCode < 300
                        ? "bg-emerald-500/20 text-emerald-600 dark:text-emerald-400 border border-emerald-500/30"
                        : "bg-rose-500/20 text-rose-600 dark:text-rose-400 border border-rose-500/30"
                    )}>
                      {testResponse.statusCode} OK
                    </span>
                  )}
                </div>

                {testResponse && (
                  <div className="flex items-center gap-3 text-xs text-text-muted font-mono">
                    <span>{testResponse.durationMs} ms</span>
                    <button
                      onClick={handleCopyResponse}
                      className="p-1.5 rounded-lg hover:bg-bg-hover text-text-muted hover:text-text-main transition-colors border border-border-main"
                      title={`Copy ${activeRespTab.toUpperCase()} Response`}
                    >
                      {isCopied ? <Check className="w-4 h-4 text-emerald-500" /> : <Copy className="w-4 h-4" />}
                    </button>
                  </div>
                )}
              </div>

              {/* Response Sub-Tabs (Body vs Headers) */}
              <div className="flex items-center gap-1 border-b border-border-main bg-bg-main px-4 pt-2 shrink-0">
                {['body', 'headers'].map(t => (
                  <button
                    key={t}
                    onClick={() => setActiveRespTab(t as any)}
                    className={clsx(
                      "px-4 py-2 text-xs font-bold capitalize rounded-t-xl transition-all border-t border-x",
                      activeRespTab === t
                        ? "bg-bg-panel text-blue-500 dark:text-blue-400 border-border-main border-b-transparent -mb-px font-bold shadow-sm"
                        : "text-text-muted border-transparent hover:text-text-main hover:bg-bg-hover"
                    )}
                  >
                    {t}
                  </button>
                ))}
              </div>

              {/* Response Body Console */}
              <div className="flex-1 p-4 overflow-auto bg-bg-main font-mono text-xs shadow-inner">
                {isTesting ? (
                  <div className="h-full flex flex-col items-center justify-center text-text-muted p-8 text-center">
                    <Loader2 className="w-8 h-8 animate-spin text-amber-500 mb-3" />
                    <p className="text-xs font-semibold text-text-main">Sending HTTP Request to endpoint...</p>
                    <p className="text-[11px] text-text-muted mt-1">Measuring latency and response payload</p>
                  </div>
                ) : !testResponse ? (
                  <div className="h-full flex flex-col items-center justify-center text-text-muted p-8 text-center">
                    <div className="w-12 h-12 rounded-2xl bg-bg-panel border border-border-main flex items-center justify-center mb-3">
                      <Zap className="w-6 h-6 text-amber-500/60" />
                    </div>
                    <p className="text-xs font-bold text-text-main mb-1">Live Console Ready</p>
                    <p className="text-[11px] text-text-muted max-w-xs">
                      Click <span className="text-amber-600 dark:text-amber-400 font-bold">"Test Endpoint"</span> above to execute a live request and view response JSON & headers.
                    </p>
                  </div>
                ) : activeRespTab === 'body' ? (
                  <pre className="text-emerald-700 dark:text-emerald-400 whitespace-pre-wrap break-all selection:bg-blue-500/30 leading-relaxed font-mono font-medium">
                    {getPrettyJson(testResponse.body)}
                  </pre>
                ) : (
                  <div className="space-y-1.5">
                    {Object.entries(testResponse.headers || {}).map(([k, v]) => (
                      <div key={k} className="flex gap-2 text-xs">
                        <span className="text-blue-500 font-bold">{k}:</span>
                        <span className="text-text-muted break-all">{v}</span>
                      </div>
                    ))}
                  </div>
                )}
              </div>

            </div>

          </div>

        </div>

        {/* Modal for Managing Shared Notification Profiles (Telegram & Discord) */}
        {isChannelModalOpen && (
          <NotificationChannelsModal
            onClose={() => {
              setIsChannelModalOpen(false);
              fetchSchedulers();
            }}
          />
        )}

      </div>
    );
  }

  // Default List / Table View
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
              <h1 className="text-xl md:text-2xl font-bold bg-gradient-to-r from-indigo-500 via-cyan-400 to-blue-500 bg-clip-text text-transparent">
                API Ingestion & Scheduler
              </h1>
              <p className="text-xs md:text-sm text-text-muted">
                Advanced HTTP Client with automated periodic ingestion into ClickHouse & PostgreSQL
              </p>
            </div>
          </div>
        </div>

        <div className="flex items-center gap-3">
          <button
            onClick={() => setIsChannelModalOpen(true)}
            className="flex items-center gap-2 px-3.5 py-2.5 rounded-xl bg-bg-panel hover:bg-bg-hover text-text-muted hover:text-purple-500 border border-border-main transition-all text-xs font-semibold shadow-sm"
            title="Manage Telegram & Discord Profiles"
          >
            <Bell className="w-4 h-4 text-purple-500" />
            <span>Notification Profiles</span>
          </button>

          <button
            onClick={() => { fetchSchedulers(); fetchMvPipelines(); }}
            className="p-2.5 rounded-xl bg-bg-panel hover:bg-bg-hover text-text-muted hover:text-text-main border border-border-main transition-all shadow-sm"
            title="Refresh List"
          >
            <RefreshCw className={clsx("w-4 h-4", (loading || loadingMvPipelines) && "animate-spin")} />
          </button>

          <button
            onClick={() => setIsManageGroupsModalOpen(true)}
            className="flex items-center gap-2 px-3.5 py-2.5 rounded-xl bg-bg-panel hover:bg-bg-hover text-text-muted hover:text-indigo-400 border border-border-main transition-all text-xs font-semibold shadow-sm cursor-pointer"
            title="Manage Scheduler Groups"
          >
            <FolderTree className="w-4 h-4 text-indigo-400" />
            <span className="hidden sm:inline">Manage Groups</span>
            <span className="px-1.5 py-0.2 text-[10px] bg-indigo-500/20 text-indigo-300 rounded-full font-mono font-bold">
              {allGroups.length}
            </span>
          </button>

          <button
            onClick={() => openNewEditor()}
            className="flex items-center gap-2 px-4 py-2.5 rounded-xl bg-gradient-to-r from-blue-600 via-cyan-500 to-blue-500 hover:from-blue-500 hover:to-cyan-400 text-white font-bold shadow-lg shadow-blue-500/25 hover:shadow-blue-500/40 transition-all text-xs"
          >
            <Plus className="w-4.5 h-4.5" />
            <span>New API Schedule</span>
          </button>
        </div>
      </div>

      {/* Main Sub-Navigation Tabs (Option A) */}
      <div className="flex items-center gap-2 mb-4 shrink-0">
        <button
          onClick={() => setActiveMainTab('schedules')}
          className={clsx(
            "flex items-center gap-2 px-4 py-2 rounded-xl text-xs font-bold transition-all border shadow-sm",
            activeMainTab === 'schedules'
              ? "bg-blue-500/15 text-blue-600 dark:text-blue-400 border-blue-500/30"
              : "bg-bg-panel text-text-muted hover:text-text-main border-border-main hover:bg-bg-hover"
          )}
        >
          <Globe className="w-4 h-4" />
          <span>API Schedules List ({schedulers.length})</span>
        </button>

        <button
          onClick={() => { setActiveMainTab('mv_pipelines'); fetchMvPipelines(); }}
          className={clsx(
            "flex items-center gap-2 px-4 py-2 rounded-xl text-xs font-bold transition-all border shadow-sm",
            activeMainTab === 'mv_pipelines'
              ? "bg-amber-500/15 text-amber-600 dark:text-amber-400 border-amber-500/30"
              : "bg-bg-panel text-text-muted hover:text-text-main border-border-main hover:bg-bg-hover"
          )}
        >
          <Zap className="w-4 h-4 text-amber-500" />
          <span>Auto-MV Extractor Pipelines ({mvPipelines.length})</span>
        </button>
      </div>

      {activeMainTab === 'schedules' ? (
        <>
          {/* GROUP FILTER TABS */}
          <div className="flex items-center justify-between gap-3 mb-4 shrink-0 overflow-x-auto pb-1">
            <div className="flex items-center gap-2">
              <button
                onClick={() => setSelectedGroup('ALL')}
                className={clsx(
                  "flex items-center gap-2 px-3.5 py-1.5 rounded-xl text-xs font-bold transition-all border whitespace-nowrap cursor-pointer",
                  selectedGroup === 'ALL'
                    ? "bg-blue-600 text-white border-blue-500 shadow-md shadow-blue-500/25"
                    : "bg-bg-panel text-text-muted hover:text-text-main border-border-main hover:bg-bg-hover"
                )}
              >
                <Layers className="w-3.5 h-3.5" />
                <span>All Schedules</span>
                <span className={clsx("px-1.5 py-0.2 text-[10px] font-mono rounded-full font-bold", selectedGroup === 'ALL' ? "bg-white/20 text-white" : "bg-bg-main text-text-muted")}>
                  {schedulers.length}
                </span>
              </button>

              {allGroups.map(grp => {
                const count = schedulers.filter(s => (s.groupName || 'General') === grp).length;
                const isSelected = selectedGroup === grp;
                return (
                  <button
                    key={grp}
                    onClick={() => setSelectedGroup(grp)}
                    className={clsx(
                      "flex items-center gap-2 px-3.5 py-1.5 rounded-xl text-xs font-bold transition-all border whitespace-nowrap cursor-pointer",
                      isSelected
                        ? "bg-indigo-600 text-white border-indigo-500 shadow-md shadow-indigo-500/25"
                        : "bg-bg-panel text-text-muted hover:text-text-main border-border-main hover:bg-bg-hover"
                    )}
                  >
                    <Folder className="w-3.5 h-3.5 text-indigo-400" />
                    <span>{grp}</span>
                    <span className={clsx("px-1.5 py-0.2 text-[10px] font-mono rounded-full font-bold", isSelected ? "bg-white/20 text-white" : "bg-bg-main text-text-muted")}>
                      {count}
                    </span>
                  </button>
                );
              })}
            </div>

            <button
              onClick={() => setIsManageGroupsModalOpen(true)}
              className="flex items-center gap-1.5 px-3 py-1.5 rounded-xl bg-bg-panel hover:bg-bg-hover border border-dashed border-border-main hover:border-indigo-500/50 text-text-muted hover:text-indigo-400 text-xs font-bold transition-all shrink-0 cursor-pointer"
              title="Manage / Create Groups"
            >
              <FolderPlus className="w-3.5 h-3.5" />
              <span className="hidden sm:inline">+ Add Group</span>
            </button>
          </div>

          {/* Filter Bar */}
          <div className="flex flex-col sm:flex-row items-center justify-between gap-3 bg-bg-panel border border-border-main p-3 rounded-2xl mb-4 shadow-sm shrink-0">
            <div className="flex items-center gap-3 w-full sm:w-auto flex-1 max-w-md">
              <div className="relative w-full">
                <Search className="w-4 h-4 text-text-muted absolute left-3 top-1/2 -translate-y-1/2" />
                <input
                  type="text"
                  placeholder={selectedGroup === 'ALL' ? "Search schedules across all groups..." : `Search in group "${selectedGroup}"...`}
                  value={searchQuery}
                  onChange={(e) => setSearchQuery(e.target.value)}
                  className="w-full bg-bg-main border border-border-main rounded-xl pl-9 pr-8 py-2 text-xs text-text-main placeholder:text-text-muted focus:outline-none focus:border-blue-500 transition-colors shadow-inner"
                />
                {searchQuery && (
                  <button
                    onClick={() => setSearchQuery('')}
                    className="absolute right-2.5 top-1/2 -translate-y-1/2 text-text-muted hover:text-text-main"
                  >
                    <X className="w-3.5 h-3.5" />
                  </button>
                )}
              </div>
            </div>

            <div className="flex items-center gap-2 w-full sm:w-auto overflow-x-auto pb-1 sm:pb-0">
              {/* Expand / Collapse All Folders */}
              <div className="flex items-center gap-1 bg-bg-main border border-border-main p-1 rounded-xl">
                <button
                  type="button"
                  onClick={expandAllGroups}
                  className="px-2.5 py-1 rounded-lg text-[11px] font-bold text-text-muted hover:text-text-main hover:bg-bg-hover transition-colors"
                  title="Expand all group folders"
                >
                  Expand All
                </button>
                <button
                  type="button"
                  onClick={collapseAllGroups}
                  className="px-2.5 py-1 rounded-lg text-[11px] font-bold text-text-muted hover:text-text-main hover:bg-bg-hover transition-colors"
                  title="Collapse all group folders"
                >
                  Collapse All
                </button>
              </div>

              <div className="flex items-center gap-1 bg-bg-main border border-border-main p-1 rounded-xl">
                {['ALL', 'GET', 'POST', 'PUT', 'DELETE'].map(method => (
                  <button
                    key={method}
                    onClick={() => setMethodFilter(method)}
                    className={clsx(
                      "px-3 py-1 rounded-lg text-[11px] font-bold transition-all",
                      methodFilter === method ? "bg-blue-500/20 text-blue-600 dark:text-blue-400 border border-blue-500/30" : "text-text-muted hover:text-text-main"
                    )}
                  >
                    {method}
                  </button>
                ))}
              </div>

              <div className="flex items-center gap-1 bg-bg-main border border-border-main p-1 rounded-xl">
                {['ALL', 'Active', 'Paused'].map(status => (
                  <button
                    key={status}
                    onClick={() => setStatusFilter(status)}
                    className={clsx(
                      "px-3 py-1 rounded-lg text-[11px] font-bold transition-all",
                      statusFilter === status ? "bg-cyan-500/20 text-cyan-600 dark:text-cyan-400 border border-cyan-500/30" : "text-text-muted hover:text-text-main"
                    )}
                  >
                    {status}
                  </button>
                ))}
              </div>
            </div>
          </div>

      {/* Main Folder Accordion View */}
      {loading ? (
        <div className="flex-1 bg-bg-panel border border-border-main rounded-2xl p-8 flex flex-col items-center justify-center text-text-muted shadow-sm">
          <Loader2 className="w-8 h-8 animate-spin text-blue-500 mb-3" />
          <p className="text-xs">Loading API Schedulers...</p>
        </div>
      ) : filteredSchedulers.length === 0 ? (
        <div className="flex-1 bg-bg-panel border border-border-main rounded-2xl p-8 flex flex-col items-center justify-center text-center text-text-muted shadow-sm">
          <div className="w-14 h-14 rounded-2xl bg-bg-hover flex items-center justify-center mb-3 text-text-muted border border-border-main">
            <Globe className="w-7 h-7" />
          </div>
          <h3 className="text-base font-semibold text-text-main mb-1">No API Schedulers Found</h3>
          <p className="text-xs max-w-sm mb-4">
            {searchQuery || methodFilter !== 'ALL' || statusFilter !== 'ALL' 
              ? "No schedules match your active search filters." 
              : "Create your first API Scheduler to start ingesting automated HTTP endpoints into ClickHouse or PostgreSQL."}
          </p>
          <button
            onClick={() => openNewEditor()}
            className="flex items-center gap-2 px-4 py-2 rounded-xl bg-blue-600 hover:bg-blue-500 text-white font-bold text-xs transition-colors shadow-md shadow-blue-500/20"
          >
            <Plus className="w-4 h-4" />
            <span>Create New API Schedule</span>
          </button>
        </div>
      ) : (
        <div className="flex-1 overflow-y-auto min-h-0 pr-1 space-y-4 pb-6">
          {displayedGroups.map(group => {
            const isGroupCollapsed = searchQuery.trim() ? false : Boolean(collapsedGroups[group.groupName]);

            return (
              <div 
                key={group.groupName}
                className="border border-border-main rounded-2xl bg-bg-panel/60 overflow-hidden shadow-sm transition-all"
              >
                {/* Folder Group Header */}
                <div
                  onClick={() => toggleGroupCollapse(group.groupName)}
                  className="flex items-center justify-between p-3.5 bg-bg-panel hover:bg-bg-hover/70 border-b border-border-main/60 cursor-pointer select-none transition-colors"
                >
                  <div className="flex items-center gap-3">
                    <div className={clsx(
                      "w-7 h-7 rounded-lg flex items-center justify-center transition-all",
                      isGroupCollapsed ? "bg-bg-editor text-text-muted" : "bg-indigo-500/10 text-indigo-400"
                    )}>
                      {isGroupCollapsed ? <ChevronRight className="w-4 h-4" /> : <ChevronDown className="w-4 h-4" />}
                    </div>
                    <div className="flex items-center gap-2">
                      {isGroupCollapsed ? (
                        <Folder className="w-4.5 h-4.5 text-indigo-400" />
                      ) : (
                        <FolderOpen className="w-4.5 h-4.5 text-indigo-400" />
                      )}
                      <h4 className="text-sm font-extrabold text-text-main">
                        {group.groupName}
                      </h4>
                      <span className="px-2 py-0.5 rounded-full text-[11px] font-mono font-bold bg-bg-editor border border-border-main text-text-muted">
                        {group.count} {group.count === 1 ? 'Schedule' : 'Schedules'}
                      </span>
                    </div>
                  </div>

                  <div className="flex items-center gap-2" onClick={e => e.stopPropagation()}>
                    <button
                      onClick={() => openNewEditor(group.groupName)}
                      className="flex items-center gap-1.5 px-3 py-1.5 rounded-xl bg-indigo-500/10 hover:bg-indigo-500/20 text-indigo-400 border border-indigo-500/20 text-xs font-bold transition-all cursor-pointer"
                      title={`Create new schedule in group "${group.groupName}"`}
                    >
                      <Plus className="w-3.5 h-3.5" />
                      <span className="hidden sm:inline">+ Add to Group</span>
                    </button>
                  </div>
                </div>

                {/* Folder Group Content */}
                {!isGroupCollapsed && (
                  <div className="p-4 bg-bg-main/30 animate-in fade-in duration-150">
                    {group.items.length === 0 ? (
                      <div className="p-6 text-center text-text-muted border border-dashed border-border-main rounded-xl">
                        <p className="text-xs">No schedules found in group "{group.groupName}".</p>
                        <button
                          onClick={() => openNewEditor(group.groupName)}
                          className="mt-2 text-xs font-bold text-indigo-400 hover:text-indigo-300"
                        >
                          + Add schedule to this group
                        </button>
                      </div>
                    ) : (
                      <div className="border border-border-main rounded-xl overflow-hidden shadow-inner bg-bg-panel">
                        <div className="overflow-x-auto">
                          <table className="w-full text-left border-collapse min-w-[900px]">
                            <thead className="bg-bg-editor text-[11px] font-bold text-text-muted uppercase tracking-wider border-b border-border-main">
                              <tr>
                                <th className="py-3 px-4 w-16 text-center">Status</th>
                                <th className="py-3 px-4">Schedule &amp; Endpoint</th>
                                <th className="py-3 px-4">Target Storage</th>
                                <th className="py-3 px-4">Spring Cron</th>
                                <th className="py-3 px-4">Notification Profiles</th>
                                <th className="py-3 px-4">Last Run Status</th>
                                <th className="py-3 px-4 text-right pr-6">Actions</th>
                              </tr>
                            </thead>
                            <tbody className="divide-y divide-border-main/60 text-xs">
                              {group.items.map((cfg) => {
                                const conn = connections.find(c => String(c.id) === String(cfg.targetConnectionId));
                                return (
                                  <tr key={cfg.id} className="hover:bg-bg-hover/60 transition-colors group relative hover:z-30">
                                    {/* Active Status Switch */}
                                    <td className="py-3 px-4 text-center">
                                      <button
                                        onClick={() => handleToggleActive(cfg)}
                                        className={clsx(
                                          "w-9 h-5 rounded-full p-0.5 transition-colors relative inline-block cursor-pointer",
                                          cfg.active ? "bg-emerald-500" : "bg-slate-400 dark:bg-slate-700"
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
                                    <td className="py-3 px-4">
                                      <div className="flex flex-col gap-1">
                                        <div className="flex items-center gap-2 flex-wrap">
                                          <span className={clsx("px-2 py-0.5 rounded text-[10px] font-bold tracking-wide", getMethodBadgeClass(cfg.method))}>
                                            {cfg.method}
                                          </span>
                                          <span className="font-bold text-text-main group-hover:text-blue-500 transition-colors text-xs">
                                            {cfg.name}
                                          </span>
                                          <button
                                            onClick={() => handleOpenQuickGroup(cfg)}
                                            className="inline-flex items-center gap-1 text-[10px] font-bold px-2 py-0.5 rounded-lg bg-indigo-500/10 text-indigo-400 border border-indigo-500/20 hover:bg-indigo-500/20 transition-all cursor-pointer"
                                            title="Click to change group"
                                          >
                                            <Folder className="w-3 h-3" />
                                            <span>{cfg.groupName || 'General'}</span>
                                          </button>
                                        </div>
                                        <span className="text-[11px] font-mono text-text-muted truncate max-w-md" title={cfg.url}>
                                          {cfg.url}
                                        </span>
                                      </div>
                                    </td>

                                    {/* Target Storage */}
                                    <td className="py-3 px-4">
                                      <div className="flex flex-col gap-1">
                                        <div className="flex items-center gap-1.5 text-xs text-text-main font-medium">
                                          <Database className="w-3.5 h-3.5 text-cyan-600 dark:text-cyan-400" />
                                          <span>{conn ? conn.name : 'Target DB'}</span>
                                          <span className="text-text-muted font-mono">({cfg.targetTable || 'sch_sync.tb_api_data'})</span>
                                        </div>
                                        <div className="flex items-center gap-1">
                                          <span className="px-2 py-0.5 rounded-full text-[10px] font-mono bg-cyan-500/10 text-cyan-700 dark:text-cyan-300 border border-cyan-500/20">
                                            kode_data: {cfg.kodeData || 'API_KODE'}
                                          </span>
                                        </div>
                                      </div>
                                    </td>

                                    {/* Spring Cron */}
                                    <td className="py-3 px-4 relative">
                                      {(() => {
                                        const crons = (cfg.cronExpression || '0 */5 * * * *')
                                          .split(/[;,\n]+/)
                                          .map(c => c.trim())
                                          .filter(Boolean);
                                        const count = crons.length;
                                        if (count === 0) {
                                          return <span className="text-text-muted text-[11px] font-mono">No Cron</span>;
                                        }
                                        const firstCron = crons[0];
                                        const extraCount = count - 1;

                                        return (
                                          <div className="relative group/cron inline-block">
                                            <div 
                                              className="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-xl text-[11px] font-mono font-bold bg-amber-500/10 text-amber-700 dark:text-amber-300 border border-amber-500/30 max-w-[220px] sm:max-w-[260px] cursor-pointer shadow-sm hover:bg-amber-500/20 transition-colors"
                                            >
                                              <Clock className="w-3.5 h-3.5 text-amber-600 dark:text-amber-400 shrink-0" />
                                              <span className="truncate">{firstCron}</span>
                                              {extraCount > 0 && (
                                                <span className="px-1.5 py-0.5 rounded-md bg-amber-500/20 dark:bg-amber-500/30 text-amber-900 dark:text-amber-200 text-[10px] font-extrabold shrink-0">
                                                  +{extraCount} more
                                                </span>
                                              )}
                                            </div>

                                            {extraCount > 0 && (
                                              <div className="absolute left-0 top-full mt-1.5 hidden group-hover/cron:flex flex-col z-50 min-w-[230px] p-2.5 bg-slate-900/95 dark:bg-slate-950/95 text-white rounded-xl shadow-2xl border border-amber-500/30 backdrop-blur-md pointer-events-none">
                                                <div className="text-[10px] font-bold text-amber-400 mb-1.5 border-b border-amber-500/20 pb-1 flex items-center justify-between">
                                                  <span className="flex items-center gap-1">
                                                    <Clock className="w-3 h-3 text-amber-400" />
                                                    Active Cron Triggers
                                                  </span>
                                                  <span className="px-1.5 py-0.2 rounded bg-amber-500/20 text-amber-300 text-[9px] font-extrabold">{count} Total</span>
                                                </div>
                                                <div className="space-y-1 font-mono text-[11px]">
                                                  {crons.map((cron, idx) => (
                                                    <div key={idx} className="flex items-center gap-1.5 text-amber-100 bg-amber-500/10 px-2 py-1 rounded-md border border-amber-500/10">
                                                      <span className="text-[9px] text-amber-400 font-bold shrink-0">#{idx + 1}</span>
                                                      <span className="truncate">{cron}</span>
                                                    </div>
                                                  ))}
                                                </div>
                                              </div>
                                            )}
                                          </div>
                                        );
                                      })()}
                                    </td>

                                    {/* Notification Profiles */}
                                    <td className="py-3 px-4">
                                      {cfg.notificationChannelId ? (
                                        <div className="flex flex-col gap-1 text-xs font-medium">
                                          {cfg.notificationChannelId.split(/[;,\n]+/).map((id, i) => {
                                            const chan = channels.find(c => String(c.id) === String(id.trim()));
                                            if (!chan) return null;
                                            return (
                                              <div key={i} className="flex items-center gap-1.5 text-purple-700 dark:text-purple-300">
                                                {chan.type === 'DISCORD' ? <Send className="w-3.5 h-3.5 text-indigo-500" /> : <MessageCircle className="w-3.5 h-3.5 text-blue-500" />}
                                                <span>{chan.name}</span>
                                              </div>
                                            );
                                          })}
                                        </div>
                                      ) : (
                                        <span className="text-text-muted text-[11px] font-mono">Disabled</span>
                                      )}
                                    </td>

                                    {/* Last Run Status */}
                                    <td className="py-3 px-4">
                                      <div className="flex flex-col gap-1">
                                        {cfg.lastRunStatus === 'SUCCESS' ? (
                                          <span className="inline-flex items-center gap-1 px-2.5 py-0.5 rounded-full text-[11px] font-bold bg-emerald-500/15 text-emerald-600 dark:text-emerald-400 border border-emerald-500/30 w-fit">
                                            <CheckCircle2 className="w-3 h-3" />
                                            SUCCESS
                                          </span>
                                        ) : cfg.lastRunStatus === 'FAILED' ? (
                                          <span className="inline-flex items-center gap-1 px-2.5 py-0.5 rounded-full text-[11px] font-bold bg-rose-500/15 text-rose-600 dark:text-rose-400 border border-rose-500/30 w-fit" title={cfg.lastRunMessage}>
                                            <AlertCircle className="w-3 h-3" />
                                            FAILED
                                          </span>
                                        ) : (
                                          <span className="inline-flex items-center gap-1 px-2.5 py-0.5 rounded-full text-[11px] font-bold bg-slate-500/15 text-slate-600 dark:text-slate-400 border border-slate-500/30 w-fit">
                                            PENDING
                                          </span>
                                        )}
                                        <span className="text-[11px] text-text-muted">
                                          {cfg.lastRunAt ? new Date(cfg.lastRunAt).toLocaleString() : 'Never executed'}
                                        </span>
                                      </div>
                                    </td>

                                    {/* Actions */}
                                    <td className="py-3 px-4 text-right pr-6">
                                      <div className="flex items-center justify-end gap-1.5">
                                        <button
                                          onClick={() => handleRunNow(cfg)}
                                          disabled={runningId === cfg.id}
                                          className="p-1.5 rounded-lg bg-emerald-500/10 hover:bg-emerald-500/20 text-emerald-600 dark:text-emerald-400 border border-emerald-500/20 transition-all cursor-pointer"
                                          title="Run Immediately & Ingest Data"
                                        >
                                          {runningId === cfg.id ? <Loader2 className="w-4 h-4 animate-spin" /> : <Play className="w-4 h-4 fill-emerald-500/20" />}
                                        </button>

                                        <button
                                          onClick={() => openEditEditor(cfg)}
                                          className="p-1.5 rounded-lg bg-blue-500/10 hover:bg-blue-500/20 text-blue-600 dark:text-blue-400 border border-blue-500/20 transition-all cursor-pointer"
                                          title="Edit HTTP Client & Schedule"
                                        >
                                          <Pencil className="w-4 h-4" />
                                        </button>

                                        <button
                                          onClick={() => handleDelete(cfg.id!, cfg.name)}
                                          className="p-1.5 rounded-lg bg-rose-500/10 hover:bg-rose-500/20 text-rose-600 dark:text-rose-400 border border-rose-500/20 transition-all cursor-pointer"
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
                      </div>
                    )}
                  </div>
                )}
              </div>
            );
          })}
        </div>
      )}
    </>
  ) : (
        /* Tab 2: Auto-MV Extractor Pipelines Panel (Option A) */
        <div className="flex-1 bg-bg-panel border border-border-main rounded-2xl p-4 md:p-6 overflow-auto shadow-sm flex flex-col min-h-0">
          <div className="flex flex-col sm:flex-row items-start sm:items-center justify-between gap-3 mb-6 shrink-0 border-b border-border-main pb-4">
            <div>
              <h3 className="text-base font-bold text-text-main flex items-center gap-2">
                <Zap className="w-5 h-5 text-amber-500" />
                Automated Materialized View Extractor Pipelines
              </h3>
              <p className="text-xs text-text-muted mt-0.5">
                Active ClickHouse Materialized Views automatically unpacking raw JSON from source tables into target tables
              </p>
            </div>

            <button
              onClick={openNewAutoMvModal}
              className="flex items-center gap-2 px-4 py-2.5 rounded-xl bg-amber-500 hover:bg-amber-600 text-white font-bold text-xs transition-colors shadow-lg shadow-amber-500/20"
            >
              <Plus className="w-4 h-4" />
              <span>Create New Auto-MV Pipeline</span>
            </button>
          </div>

          {loadingMvPipelines ? (
            <div className="flex-1 flex flex-col items-center justify-center p-8 text-text-muted">
              <Loader2 className="w-8 h-8 animate-spin text-amber-500 mb-3" />
              <p className="text-xs">Loading Auto-MV Pipelines from ClickHouse...</p>
            </div>
          ) : mvPipelines.length === 0 ? (
            <div className="flex-1 flex flex-col items-center justify-center p-8 text-center text-text-muted">
              <div className="w-14 h-14 rounded-2xl bg-amber-500/10 flex items-center justify-center mb-3 text-amber-500 border border-amber-500/20">
                <Zap className="w-7 h-7" />
              </div>
              <h3 className="text-base font-semibold text-text-main mb-1">No Auto-MV Pipelines Found</h3>
              <p className="text-xs max-w-sm mb-4">
                Create an Automated Materialized View to unpack raw JSON responses (from source raw table) into clean, structured target tables.
              </p>
              <button
                onClick={openNewAutoMvModal}
                className="flex items-center gap-2 px-4 py-2 rounded-xl bg-amber-500 hover:bg-amber-600 text-white font-bold text-xs transition-colors shadow-md shadow-amber-500/20"
              >
                <Plus className="w-4 h-4" />
                <span>Setup First Auto-MV Extractor</span>
              </button>
            </div>
          ) : (
            <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
              {mvPipelines.map((mv) => (
                <div key={mv.mvName} className="bg-bg-main border border-border-main rounded-xl p-4 hover:border-amber-500/40 transition-all shadow-sm flex flex-col justify-between">
                  <div>
                    <div className="flex items-start justify-between mb-2">
                      <div className="flex items-center gap-2">
                        <span className="p-1.5 rounded-lg bg-amber-500/10 text-amber-500 border border-amber-500/20">
                          <Zap className="w-4 h-4" />
                        </span>
                        <div>
                          <h4 className="font-bold text-xs text-text-main truncate max-w-[180px]" title={mv.mvName}>
                            {mv.mvName}
                          </h4>
                          <span className="text-[10px] text-text-muted font-mono">
                            Target: {mv.targetTable}
                          </span>
                        </div>
                      </div>
                      <span className="px-2 py-0.5 rounded text-[9px] font-black uppercase tracking-wider bg-emerald-500/10 text-emerald-500 border border-emerald-500/20">
                        ACTIVE
                      </span>
                    </div>

                    <div className="bg-bg-panel border border-border-main p-2.5 rounded-lg my-3 space-y-1 text-[11px] font-mono">
                      <div className="flex justify-between items-center text-text-muted">
                        <span>Target Table:</span>
                        <span className="text-text-main font-bold">{mv.targetTable}</span>
                      </div>
                      <div className="flex justify-between items-center text-text-muted">
                        <span>Synced Records:</span>
                        <span className="text-amber-500 font-bold">{mv.syncedRecords.toLocaleString()} rows</span>
                      </div>
                    </div>
                  </div>

                  <div className="flex items-center justify-between pt-2 border-t border-border-main mt-2">
                    <button
                      onClick={() => setViewQueryModal({ name: mv.mvName, query: mv.query })}
                      className="flex items-center gap-1 text-[11px] text-blue-500 hover:text-blue-400 font-semibold"
                    >
                      <Code2 className="w-3.5 h-3.5" />
                      <span>View DDL Query</span>
                    </button>

                    <button
                      onClick={() => handleDeleteMvPipeline(mv.mvName)}
                      className="p-1.5 rounded-lg hover:bg-rose-500/10 text-text-muted hover:text-rose-500 transition-colors"
                      title="Drop Materialized View"
                    >
                      <Trash2 className="w-4 h-4" />
                    </button>
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>
      )}

      {/* Modal for Setup Automated Materialized View Extractor */}
      {isAutoMvModalOpen && (
        <div className="fixed inset-0 z-50 bg-black/60 backdrop-blur-sm flex items-center justify-center p-4">
          <div className="bg-bg-panel border border-border-main rounded-2xl w-full max-w-2xl overflow-hidden shadow-2xl flex flex-col max-h-[90vh]">
            {/* Header */}
            <div className="px-6 py-4 border-b border-border-main flex items-center justify-between bg-bg-header/50">
              <div className="flex items-center gap-2">
                <div className="p-2 rounded-xl bg-amber-500/10 text-amber-500 border border-amber-500/20">
                  <Zap className="w-5 h-5" />
                </div>
                <div>
                  <h3 className="font-bold text-sm text-text-main">Automated Materialized View Extractor</h3>
                  <p className="text-xs text-text-muted">Unpack raw JSON from source table into clean ClickHouse target table</p>
                </div>
              </div>
              <button onClick={() => setIsAutoMvModalOpen(false)} className="p-1.5 rounded-lg hover:bg-bg-hover text-text-muted hover:text-text-main">
                <X className="w-5 h-5" />
              </button>
            </div>

            {/* Body */}
            <div className="p-6 overflow-y-auto space-y-4 text-xs">
              {/* ClickHouse Connection & Database Selector */}
              <div className="bg-amber-500/5 border border-amber-500/20 p-3 rounded-xl">
                <label className="block text-text-main font-bold mb-1 flex items-center gap-1.5">
                  <Database className="w-4 h-4 text-amber-500" />
                  <span>ClickHouse Target Database Connection</span>
                </label>
                <select
                  value={autoMvForm.connectionId}
                  onChange={(e) => {
                    const newConnId = e.target.value;
                    setAutoMvForm(prev => ({ ...prev, connectionId: newConnId }));
                    if (autoMvForm.sourceTable) {
                      handleInspectSchema(autoMvForm.sourceTable, autoMvForm.kodeData, newConnId);
                    }
                  }}
                  className="w-full bg-bg-panel border border-border-main rounded-xl px-3 py-2 text-text-main font-mono text-xs focus:outline-none focus:border-amber-500 shadow-sm"
                >
                  <option value="">Default Server Connection (Local / Server)</option>
                  {connections
                    .filter(c => (c.type && c.type.toUpperCase().includes('CLICKHOUSE')) || (c.name && c.name.toLowerCase().includes('clickhouse')))
                    .map(c => (
                      <option key={c.id} value={c.id}>
                        ⚡ {c.name} ({c.database || 'default'}) - {c.host}:{c.port}
                      </option>
                    ))}
                </select>
              </div>

              <div className="grid grid-cols-2 gap-4">
                <div>
                  <label className="block text-text-muted font-bold mb-1">Source Raw Table</label>
                  <input
                    type="text"
                    list="source-tables-list"
                    value={autoMvForm.sourceTable}
                    onChange={(e) => setAutoMvForm({ ...autoMvForm, sourceTable: e.target.value })}
                    placeholder="e.g. api_test"
                    className="w-full bg-bg-main border border-border-main rounded-xl px-3 py-2 text-text-main font-mono focus:outline-none focus:border-amber-500"
                  />
                  <datalist id="source-tables-list">
                    {autoMvForm.existingTables.map(t => (
                      <option key={t} value={t} />
                    ))}
                  </datalist>
                </div>

                <div>
                  <label className="block text-text-muted font-bold mb-1">Filter kode_data</label>
                  <div className="flex gap-2">
                    <input
                      type="text"
                      value={autoMvForm.kodeData}
                      onChange={(e) => setAutoMvForm({ ...autoMvForm, kodeData: e.target.value })}
                      placeholder="e.g. API_KODE_V1"
                      className="w-full bg-bg-main border border-border-main rounded-xl px-3 py-2 text-text-main font-mono focus:outline-none focus:border-amber-500"
                    />
                    <button
                      type="button"
                      onClick={() => handleInspectSchema(autoMvForm.sourceTable, autoMvForm.kodeData, autoMvForm.connectionId)}
                      disabled={inspectingSchema}
                      className="px-3 py-2 rounded-xl bg-amber-500/10 text-amber-500 hover:bg-amber-500 hover:text-white font-bold shrink-0 transition-colors border border-amber-500/20"
                      title="Inspect JSON Schema"
                    >
                      {inspectingSchema ? <Loader2 className="w-4 h-4 animate-spin" /> : "Inspect"}
                    </button>
                  </div>
                </div>
              </div>

              {/* Target Table Settings */}
              <div className="bg-bg-main border border-border-main p-4 rounded-xl space-y-3">
                <div className="flex items-center justify-between">
                  <span className="font-bold text-text-main flex items-center gap-2">
                    <Database className="w-4 h-4 text-amber-500" />
                    Target Table Selection
                  </span>
                  <div className="flex items-center gap-2 text-xs">
                    <label className="flex items-center gap-1.5 cursor-pointer">
                      <input
                        type="radio"
                        name="tableMode"
                        checked={autoMvForm.createNewTable}
                        onChange={() => setAutoMvForm({ ...autoMvForm, createNewTable: true })}
                        className="accent-amber-500"
                      />
                      <span>Create New Table</span>
                    </label>
                    <label className="flex items-center gap-1.5 cursor-pointer ml-3">
                      <input
                        type="radio"
                        name="tableMode"
                        checked={!autoMvForm.createNewTable}
                        onChange={() => {
                          setAutoMvForm(prev => ({ ...prev, createNewTable: false }));
                          fetchExistingTables();
                        }}
                        className="accent-amber-500"
                      />
                      <span>Use Existing Table</span>
                    </label>
                  </div>
                </div>

                <div>
                  <label className="block text-text-muted mb-1 font-semibold">Target Table Name</label>
                  {autoMvForm.createNewTable ? (
                    <input
                      type="text"
                      value={autoMvForm.targetTable}
                      onChange={(e) => setAutoMvForm({ ...autoMvForm, targetTable: e.target.value })}
                      placeholder="e.g. target_cabang_api"
                      className="w-full bg-bg-panel border border-border-main rounded-xl px-3 py-2 text-text-main font-mono focus:outline-none focus:border-amber-500"
                    />
                  ) : (
                    <select
                      value={autoMvForm.targetTable}
                      onChange={(e) => setAutoMvForm({ ...autoMvForm, targetTable: e.target.value })}
                      className="w-full bg-bg-panel border border-border-main rounded-xl px-3 py-2 text-text-main font-mono focus:outline-none focus:border-amber-500 text-xs"
                    >
                      <option value="">-- Select Existing Target Table ({autoMvForm.existingTables.length} available) --</option>
                      {autoMvForm.existingTables.map(t => (
                        <option key={t} value={t}>{t}</option>
                      ))}
                    </select>
                  )}
                </div>

                {autoMvForm.createNewTable && (
                  <div>
                    <label className="block text-text-muted mb-1 font-semibold">PRIMARY KEY / ORDER BY Columns</label>
                    <input
                      type="text"
                      value={autoMvForm.orderByStr}
                      onChange={(e) => setAutoMvForm({ ...autoMvForm, orderByStr: e.target.value })}
                      placeholder="kode_perusahaan, kode_cabang"
                      className="w-full bg-bg-panel border border-border-main rounded-xl px-3 py-2 text-text-main font-mono focus:outline-none focus:border-amber-500"
                    />
                  </div>
                )}
              </div>

              {/* Fields List */}
              <div>
                <div className="flex items-center justify-between mb-2">
                  <span className="font-bold text-text-main">
                    Detected Fields to Extract ({autoMvForm.fields.filter(f => f.enabled).length}/{autoMvForm.fields.length})
                  </span>
                  <button
                    type="button"
                    onClick={() => {
                      const allSelected = autoMvForm.fields.every(f => f.enabled);
                      setAutoMvForm({
                        ...autoMvForm,
                        fields: autoMvForm.fields.map(f => ({ ...f, enabled: !allSelected }))
                      });
                    }}
                    className="text-[11px] text-amber-500 hover:underline"
                  >
                    Toggle All
                  </button>
                </div>

                <div className="bg-bg-main border border-border-main rounded-xl p-3 max-h-48 overflow-y-auto space-y-2">
                  {autoMvForm.fields.length === 0 ? (
                    <p className="text-text-muted italic text-center py-4">Click "Inspect" to auto-detect JSON fields from sample data...</p>
                  ) : (
                    autoMvForm.fields.map((f, idx) => (
                      <div key={idx} className="flex items-center justify-between bg-bg-panel p-2 rounded-lg border border-border-main">
                        <label className="flex items-center gap-2 cursor-pointer">
                          <input
                            type="checkbox"
                            checked={f.enabled}
                            onChange={(e) => {
                              const next = [...autoMvForm.fields];
                              next[idx].enabled = e.target.checked;
                              setAutoMvForm({ ...autoMvForm, fields: next });
                            }}
                            className="accent-amber-500 rounded"
                          />
                          <span className="font-mono text-text-main font-semibold">{f.name}</span>
                        </label>

                        <select
                          value={f.type}
                          onChange={(e) => {
                            const next = [...autoMvForm.fields];
                            next[idx].type = e.target.value;
                            setAutoMvForm({ ...autoMvForm, fields: next });
                          }}
                          className="bg-bg-main border border-border-main rounded px-2 py-1 font-mono text-[11px] text-amber-500 font-bold"
                        >
                          <option value="String">String</option>
                          <option value="UInt8">UInt8</option>
                          <option value="UInt64">UInt64</option>
                          <option value="Float64">Float64</option>
                        </select>
                      </div>
                    ))
                  )}
                </div>
              </div>

              {/* Backfill Option */}
              <label className="flex items-center gap-2 text-text-main font-semibold cursor-pointer pt-1">
                <input
                  type="checkbox"
                  checked={autoMvForm.backfillHistorical}
                  onChange={(e) => setAutoMvForm({ ...autoMvForm, backfillHistorical: e.target.checked })}
                  className="accent-amber-500 w-4 h-4"
                />
                <span>Backfill existing historical records from {autoMvForm.sourceTable} immediately</span>
              </label>
            </div>

            {/* Footer */}
            <div className="px-6 py-4 border-t border-border-main bg-bg-header/50 flex items-center justify-between shrink-0">
              <button
                onClick={() => setIsAutoMvModalOpen(false)}
                className="px-4 py-2 rounded-xl bg-bg-main hover:bg-bg-hover text-text-muted font-bold text-xs"
              >
                Cancel
              </button>

              <button
                onClick={handleDeployMvPipeline}
                disabled={deployingMv || autoMvForm.fields.filter(f => f.enabled).length === 0}
                className="flex items-center gap-2 px-5 py-2.5 rounded-xl bg-amber-500 hover:bg-amber-600 text-white font-bold text-xs shadow-lg shadow-amber-500/20 disabled:opacity-40 transition-all"
              >
                {deployingMv ? <Loader2 className="w-4 h-4 animate-spin" /> : <Zap className="w-4 h-4 fill-white" />}
                <span>Deploy Auto-MV Pipeline</span>
              </button>
            </div>
          </div>
        </div>
      )}

      {/* DDL Query Modal Viewer */}
      {viewQueryModal && (
        <div className="fixed inset-0 z-50 bg-black/60 backdrop-blur-sm flex items-center justify-center p-4">
          <div className="bg-bg-panel border border-border-main rounded-2xl w-full max-w-3xl overflow-hidden shadow-2xl flex flex-col max-h-[90vh]">
            <div className="px-6 py-4 border-b border-border-main flex items-center justify-between bg-bg-header/50">
              <div className="flex items-center gap-2">
                <Code2 className="w-5 h-5 text-blue-500" />
                <h3 className="font-bold text-sm text-text-main">DDL Query: {viewQueryModal.name}</h3>
              </div>
              <div className="flex items-center gap-2">
                <button
                  onClick={() => handleCopyDdl(viewQueryModal.query)}
                  className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-blue-500/10 hover:bg-blue-500/20 text-blue-600 dark:text-blue-400 border border-blue-500/20 text-xs font-bold transition-all"
                  title="Copy DDL Query"
                >
                  {isCopiedDdl ? <Check className="w-3.5 h-3.5 text-emerald-500" /> : <Copy className="w-3.5 h-3.5" />}
                  <span>{isCopiedDdl ? "Copied!" : "Copy DDL"}</span>
                </button>
                <button onClick={() => setViewQueryModal(null)} className="p-1.5 rounded-lg hover:bg-bg-hover text-text-muted hover:text-text-main">
                  <X className="w-5 h-5" />
                </button>
              </div>
            </div>
            <div className="p-4 bg-slate-950 text-emerald-400 font-mono text-xs overflow-auto max-h-[65vh] leading-relaxed shadow-inner">
              <pre className="whitespace-pre-wrap">{formatSql(viewQueryModal.query)}</pre>
            </div>
            <div className="px-6 py-3 border-t border-border-main flex justify-end">
              <button onClick={() => setViewQueryModal(null)} className="px-4 py-2 rounded-xl bg-bg-main hover:bg-bg-hover text-text-main text-xs font-bold">
                Close
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Modal for Managing Shared Notification Profiles (Telegram & Discord) */}
      {isChannelModalOpen && (
        <NotificationChannelsModal
          onClose={() => {
            setIsChannelModalOpen(false);
            fetchSchedulers();
          }}
        />
      )}

      {/* ── MANAGE SCHEDULER GROUPS MODAL ─────────────────────────────── */}
      {isManageGroupsModalOpen && (
        <div className="fixed inset-0 bg-black/70 backdrop-blur-sm z-50 flex items-center justify-center p-4 animate-in fade-in">
          <div className="bg-bg-panel border border-border-main rounded-2xl shadow-2xl max-w-md w-full overflow-hidden flex flex-col">
            <div className="p-5 border-b border-border-main flex items-center justify-between bg-bg-editor/50">
              <div className="flex items-center gap-2.5">
                <div className="w-8 h-8 rounded-xl bg-indigo-500/10 border border-indigo-500/20 flex items-center justify-center text-indigo-400">
                  <FolderTree className="w-4 h-4" />
                </div>
                <div>
                  <h3 className="text-sm font-bold text-text-main">Manage Scheduler Groups</h3>
                  <p className="text-[11px] text-text-muted">Organize ingestion schedules into categories</p>
                </div>
              </div>
              <button
                onClick={() => setIsManageGroupsModalOpen(false)}
                className="p-1.5 rounded-lg text-text-muted hover:text-text-main hover:bg-bg-hover transition-colors"
              >
                <X className="w-4 h-4" />
              </button>
            </div>

            <div className="p-5 space-y-4">
              {/* Create New Group Input */}
              <div className="space-y-1.5">
                <label className="text-[11px] font-extrabold text-text-muted uppercase tracking-wider">
                  Create New Group
                </label>
                <div className="flex items-center gap-2">
                  <input
                    type="text"
                    className="w-full bg-bg-main border border-border-main focus:border-indigo-500 focus:ring-1 focus:ring-indigo-500 rounded-xl px-3 py-2 text-xs outline-none text-text-main font-medium shadow-inner"
                    placeholder="e.g. Finance Ingest, Sales Sync, Master Data..."
                    value={newGroupInputName}
                    onChange={e => setNewGroupInputName(e.target.value)}
                    onKeyDown={e => {
                      if (e.key === 'Enter' && newGroupInputName.trim()) {
                        handleCreateGroup(newGroupInputName.trim());
                      }
                    }}
                  />
                  <button
                    type="button"
                    disabled={!newGroupInputName.trim()}
                    onClick={() => handleCreateGroup(newGroupInputName.trim())}
                    className="px-4 py-2 bg-indigo-600 hover:bg-indigo-500 disabled:opacity-50 text-white rounded-xl text-xs font-bold transition-all shrink-0"
                  >
                    + Add
                  </button>
                </div>
              </div>

                {/* Existing Groups List */}
                <div className="space-y-2 pt-2">
                  <label className="text-[11px] font-extrabold text-text-muted uppercase tracking-wider block">
                    Existing Groups ({allGroups.length})
                  </label>
                  <div className="max-h-72 overflow-y-auto space-y-2 pr-1">
                    {allGroups.map(grp => {
                      const count = schedulers.filter(s => (s.groupName || 'General') === grp).length;
                      const isEditing = editingGroupName === grp;
                      const isDeleting = deletingGroupName === grp;

                      if (isDeleting) {
                        return (
                          <div key={grp} className="p-3 rounded-xl border border-rose-500/40 bg-rose-500/10 space-y-2.5 animate-in fade-in">
                            <div className="flex items-center gap-2 text-rose-400 text-xs font-bold">
                              <AlertTriangle className="w-4 h-4 shrink-0" />
                              <span>Delete group "{grp}"?</span>
                            </div>
                            <p className="text-[11px] text-text-muted leading-relaxed">
                              All <b className="text-text-main font-semibold">{count}</b> schedules in this group will be moved to <b className="text-text-main font-semibold">"General"</b>.
                            </p>
                            <div className="flex items-center justify-end gap-2 pt-1">
                              <button
                                type="button"
                                disabled={isProcessingGroupAction}
                                onClick={() => setDeletingGroupName(null)}
                                className="px-3 py-1 rounded-lg text-xs font-bold text-text-muted hover:text-text-main hover:bg-bg-hover transition-colors"
                              >
                                Cancel
                              </button>
                              <button
                                type="button"
                                disabled={isProcessingGroupAction}
                                onClick={() => handleDeleteGroup(grp)}
                                className="px-3 py-1 rounded-lg bg-rose-600 hover:bg-rose-500 text-white text-xs font-bold transition-all flex items-center gap-1.5 shadow-sm shadow-rose-600/30"
                              >
                                {isProcessingGroupAction ? <Loader2 className="w-3 h-3 animate-spin" /> : <Trash2 className="w-3 h-3" />}
                                <span>Delete</span>
                              </button>
                            </div>
                          </div>
                        );
                      }

                      if (isEditing) {
                        return (
                          <div key={grp} className="p-2.5 rounded-xl border border-indigo-500/50 bg-indigo-500/10 flex items-center gap-2 animate-in fade-in">
                            <input
                              type="text"
                              autoFocus
                              className="flex-1 bg-bg-main border border-indigo-500/50 rounded-lg px-2.5 py-1.5 text-xs text-text-main font-bold outline-none shadow-inner"
                              value={editingGroupNewName}
                              onChange={e => setEditingGroupNewName(e.target.value)}
                              onKeyDown={e => {
                                if (e.key === 'Enter') handleRenameGroup(grp, editingGroupNewName);
                                if (e.key === 'Escape') setEditingGroupName(null);
                              }}
                            />
                            <button
                              type="button"
                              disabled={isProcessingGroupAction || !editingGroupNewName.trim()}
                              onClick={() => handleRenameGroup(grp, editingGroupNewName)}
                              className="p-1.5 rounded-lg bg-indigo-600 hover:bg-indigo-500 text-white disabled:opacity-50 transition-colors"
                              title="Save Rename"
                            >
                              {isProcessingGroupAction ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Check className="w-3.5 h-3.5" />}
                            </button>
                            <button
                              type="button"
                              disabled={isProcessingGroupAction}
                              onClick={() => setEditingGroupName(null)}
                              className="p-1.5 rounded-lg text-text-muted hover:text-text-main hover:bg-bg-hover transition-colors"
                              title="Cancel"
                            >
                              <X className="w-3.5 h-3.5" />
                            </button>
                          </div>
                        );
                      }

                      return (
                        <div
                          key={grp}
                          className={clsx(
                            "flex items-center justify-between p-2.5 rounded-xl border transition-all group",
                            selectedGroup === grp
                              ? "bg-indigo-500/10 border-indigo-500/40 text-indigo-300"
                              : "bg-bg-main/60 border-border-main hover:bg-bg-hover hover:border-indigo-500/30 text-text-main"
                          )}
                        >
                          <div
                            onClick={() => {
                              setSelectedGroup(grp);
                              setIsManageGroupsModalOpen(false);
                            }}
                            className="flex items-center gap-2.5 flex-1 min-w-0 cursor-pointer"
                          >
                            <Folder className="w-4 h-4 text-indigo-400 shrink-0" />
                            <span className="text-xs font-bold truncate">{grp}</span>
                            <span className="text-[10px] font-mono px-2 py-0.5 bg-bg-panel rounded-md border border-border-main text-text-muted font-bold shrink-0">
                              {count} {count === 1 ? 'Job' : 'Jobs'}
                            </span>
                          </div>

                          <div className="flex items-center gap-1 shrink-0 ml-2">
                            <button
                              type="button"
                              onClick={(e) => {
                                e.stopPropagation();
                                setEditingGroupName(grp);
                                setEditingGroupNewName(grp);
                              }}
                              className="p-1.5 rounded-lg text-text-muted hover:text-indigo-400 hover:bg-indigo-500/10 transition-colors"
                              title={`Rename group "${grp}"`}
                            >
                              <Pencil className="w-3.5 h-3.5" />
                            </button>
                            {grp !== 'General' && (
                              <button
                                type="button"
                                onClick={(e) => {
                                  e.stopPropagation();
                                  setDeletingGroupName(grp);
                                }}
                                className="p-1.5 rounded-lg text-text-muted hover:text-rose-400 hover:bg-rose-500/10 transition-colors"
                                title={`Delete group "${grp}"`}
                              >
                                <Trash2 className="w-3.5 h-3.5" />
                              </button>
                            )}
                          </div>
                        </div>
                      );
                    })}
                  </div>
                </div>
              </div>

              <div className="p-4 border-t border-border-main flex items-center justify-end bg-bg-main/40">
                <button
                  type="button"
                  onClick={() => {
                    setIsManageGroupsModalOpen(false);
                    setEditingGroupName(null);
                    setDeletingGroupName(null);
                  }}
                  className="px-4 py-2 rounded-xl text-xs font-bold text-text-muted hover:text-text-main hover:bg-bg-hover transition-colors"
                >
                  Close
                </button>
              </div>
            </div>
          </div>
        )}

      {/* ── QUICK CHANGE SCHEDULER GROUP MODAL ───────────────────────── */}
      {isQuickGroupModalOpen && quickGroupTarget && (
        <div className="fixed inset-0 bg-black/70 backdrop-blur-sm z-50 flex items-center justify-center p-4 animate-in fade-in">
          <div className="bg-bg-panel border border-border-main rounded-2xl shadow-2xl max-w-sm w-full overflow-hidden flex flex-col">
            <div className="p-5 border-b border-border-main flex items-center justify-between bg-bg-editor/50">
              <div className="flex items-center gap-2">
                <Folder className="w-4 h-4 text-indigo-400" />
                <h3 className="text-sm font-bold text-text-main">Change Schedule Group</h3>
              </div>
              <button
                onClick={() => setIsQuickGroupModalOpen(false)}
                className="p-1.5 rounded-lg text-text-muted hover:text-text-main hover:bg-bg-hover transition-colors"
              >
                <X className="w-4 h-4" />
              </button>
            </div>

            <div className="p-5 space-y-3">
              <p className="text-xs text-text-muted">
                Move schedule <b className="text-text-main font-bold">"{quickGroupTarget.name}"</b> to a group:
              </p>
              <div className="relative">
                <input
                  list="quick-modal-scheduler-group-list"
                  className="w-full bg-bg-main border border-border-main focus:border-indigo-500 rounded-xl p-2.5 text-xs outline-none text-text-main font-bold shadow-inner"
                  value={quickGroupValue}
                  onChange={e => setQuickGroupValue(e.target.value)}
                  placeholder="Select or enter group name..."
                />
                <datalist id="quick-modal-scheduler-group-list">
                  {allGroups.map(g => (
                    <option key={g} value={g} />
                  ))}
                </datalist>
              </div>
              <div className="flex items-center gap-1.5 flex-wrap pt-1">
                {allGroups.map(g => (
                  <button
                    key={g}
                    type="button"
                    onClick={() => setQuickGroupValue(g)}
                    className={clsx(
                      "px-2.5 py-1 rounded-lg text-[11px] font-bold border transition-all cursor-pointer",
                      quickGroupValue === g
                        ? "bg-indigo-600 text-white border-indigo-500"
                        : "bg-bg-main hover:bg-bg-hover text-text-muted border-border-main"
                    )}
                  >
                    {g}
                  </button>
                ))}
              </div>
            </div>

            <div className="p-4 border-t border-border-main flex items-center justify-end gap-2 bg-bg-main/40">
              <button
                type="button"
                onClick={() => setIsQuickGroupModalOpen(false)}
                className="px-3.5 py-2 rounded-xl text-xs font-bold text-text-muted hover:text-text-main hover:bg-bg-hover transition-colors"
              >
                Cancel
              </button>
              <button
                type="button"
                onClick={handleSaveQuickGroup}
                disabled={isSavingQuickGroup}
                className="px-4 py-2 rounded-xl bg-indigo-600 hover:bg-indigo-500 disabled:opacity-50 text-white text-xs font-bold transition-all shadow-md shadow-indigo-500/20 flex items-center gap-1.5"
              >
                {isSavingQuickGroup ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Save className="w-3.5 h-3.5" />}
                <span>Save Group</span>
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
