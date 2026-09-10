import React, { useState, useEffect, useMemo } from 'react';
import axios from 'axios';
import { useAppStore } from '../store/useAppStore';
import {
  Webhook, Plus, RefreshCw, Trash2, Edit3, Check, Copy,
  ShieldCheck, Database, ArrowLeft, Search,
  List, Grid, AlertTriangle,
  Eye, EyeOff, Activity, Send, Bell, Key, X, Sparkles,
  Zap, CheckSquare, Square, Filter, Layers
} from 'lucide-react';
import clsx from 'clsx';
import { NotificationChannelsModal } from './NotificationChannelsModal';

export interface TriggerFilterRule {
  key: string;
  value: string;
}

export interface TriggerParamMapping {
  targetParam: string;
  sourceJsonPath: string;
  sourceType?: 'param' | 'body' | 'placeholder' | 'custom';
}

export interface WebhookConfig {
  id?: string;
  name: string;
  slug: string;
  description?: string;
  groupName?: string;
  secretHeaderName?: string;
  secretHeaderValue?: string;
  ipAllowlist?: string;
  targetConnectionId: string;
  targetTable: string;
  kodeData?: string;
  enableEnrichment?: boolean;
  triggerApiSchedulerId?: string;
  triggerFilterKey?: string;
  triggerFilterValue?: string;
  triggerParamKey?: string;
  triggerParamTarget?: string;
  triggerFilterRules?: string;
  triggerParamMapping?: string;
  enrichmentFilterStatus?: string;
  enrichmentTargetConnectionId?: string;
  enrichmentTargetTable?: string;
  enrichmentKodeData?: string;
  enrichmentGineeAccessKey?: string;
  enrichmentGineeSecretKey?: string;
  notificationChannelId?: string;
  active?: boolean;
  createdAt?: string;
  updatedAt?: string;
  lastTriggeredAt?: string;
  lastStatus?: string;
  lastMessage?: string;
  totalRequests?: number;
  successCount?: number;
  failureCount?: number;
}

export interface WebhookLog {
  id: string;
  webhookId: string;
  webhookSlug: string;
  receivedAt: string;
  sourceIp: string;
  httpMethod: string;
  headers?: string;
  payload?: string;
  status: string;
  statusCode: number;
  errorMessage?: string;
  durationMs: number;
  targetTable: string;
  rowsInserted: number;
}

interface NotificationChannel {
  id: string;
  name: string;
  type: 'TELEGRAM' | 'DISCORD';
  botToken?: string;
  chatId?: string;
  webhookUrl?: string;
}

export const WebhooksView: React.FC = () => {
  const { connections, addToast, showAlert } = useAppStore();

  const [webhooks, setWebhooks] = useState<WebhookConfig[]>([]);
  const [channels, setChannels] = useState<NotificationChannel[]>([]);
  const [loading, setLoading] = useState(true);
  const [searchQuery, setSearchQuery] = useState('');
  const [statusFilter, setStatusFilter] = useState<'ALL' | 'ACTIVE' | 'INACTIVE'>('ALL');
  const [selectedGroup, setSelectedGroup] = useState<string>('ALL');
  const [viewMode, setViewMode] = useState<'list' | 'editor'>('list');
  const [layoutMode, setLayoutMode] = useState<'cards' | 'table'>('cards');

  // Channel Modal
  const [isChannelModalOpen, setIsChannelModalOpen] = useState(false);

  // Active Editor Form State
  const [editingConfig, setEditingConfig] = useState<Partial<WebhookConfig>>({
    name: '',
    slug: '',
    description: '',
    groupName: 'General',
    secretHeaderName: '',
    secretHeaderValue: '',
    ipAllowlist: '',
    targetConnectionId: '',
    targetTable: '',
    kodeData: 'GINEE_WEBHOOK',
    enableEnrichment: false,
    triggerApiSchedulerId: '',
    triggerFilterKey: 'orderStatus',
    triggerFilterValue: 'READY_TO_SHIP',
    triggerParamKey: 'orderId',
    triggerParamTarget: '{{orderId}}',
    enrichmentFilterStatus: 'READY_TO_SHIP',
    enrichmentTargetConnectionId: '',
    enrichmentTargetTable: '',
    enrichmentKodeData: 'GINEE_READY_TO_SHIP',
    enrichmentGineeAccessKey: '',
    enrichmentGineeSecretKey: '',
    notificationChannelId: '',
    active: true,
  });
  const [isSaving, setIsSaving] = useState(false);
  const [showSecretValue, setShowSecretValue] = useState(false);

  // Filter rules and param mappings state for active editor
  const [filterRules, setFilterRules] = useState<TriggerFilterRule[]>([
    { key: 'orderStatus', value: 'READY_TO_SHIP' }
  ]);
  const [paramMappings, setParamMappings] = useState<TriggerParamMapping[]>([
    { targetParam: 'orderId', sourceJsonPath: 'orderId', sourceType: 'placeholder' }
  ]);

  // API Schedulers available for Trigger Webhook
  const [apiSchedulers, setApiSchedulers] = useState<any[]>([]);
  const [triggerSchedulerSearch, setTriggerSchedulerSearch] = useState('');

  // Logs Modal State
  const [isLogsModalOpen, setIsLogsModalOpen] = useState(false);
  const [activeLogWebhook, setActiveLogWebhook] = useState<WebhookConfig | null>(null);
  const [logs, setLogs] = useState<WebhookLog[]>([]);
  const [loadingLogs, setLoadingLogs] = useState(false);
  const [inspectingLog, setInspectingLog] = useState<WebhookLog | null>(null);

  // Copied State Tracker
  const [copiedSlug, setCopiedSlug] = useState<string | null>(null);

  // 1. Fetch Webhooks, Channels, and API Schedulers
  const fetchWebhooks = async () => {
    setLoading(true);
    try {
      const res = await axios.get('/api/webhooks');
      if (Array.isArray(res.data)) {
        setWebhooks(res.data);
      }
    } catch (err: any) {
      console.error('Failed to fetch webhooks', err);
      addToast({
        type: 'error',
        title: 'Fetch Error',
        message: 'Could not load webhooks from server.',
      });
    } finally {
      setLoading(false);
    }
  };

  const fetchApiSchedulers = async () => {
    try {
      const res = await axios.get('/api/api-schedulers');
      if (Array.isArray(res.data)) {
        setApiSchedulers(res.data);
      }
    } catch (err: any) {
      console.error('Failed to fetch API Schedulers for Webhooks', err);
    }
  };

  const fetchChannels = async () => {
    try {
      const res = await axios.get('/api/notification-channels');
      if (Array.isArray(res.data)) {
        setChannels(res.data);
      }
    } catch (err) {
      console.error('Failed to fetch notification channels', err);
    }
  };

  useEffect(() => {
    fetchWebhooks();
    fetchChannels();
    fetchApiSchedulers();
  }, []);

  // Compute unique groups
  const groups = useMemo(() => {
    const set = new Set<string>();
    set.add('General');
    webhooks.forEach(w => {
      if (w.groupName && w.groupName.trim()) {
        set.add(w.groupName.trim());
      }
    });
    return Array.from(set).sort();
  }, [webhooks]);

  // Filtered Webhooks
  const filteredWebhooks = useMemo(() => {
    return webhooks.filter(w => {
      const matchesSearch =
        searchQuery === '' ||
        w.name.toLowerCase().includes(searchQuery.toLowerCase()) ||
        w.slug.toLowerCase().includes(searchQuery.toLowerCase()) ||
        (w.targetTable && w.targetTable.toLowerCase().includes(searchQuery.toLowerCase())) ||
        (w.kodeData && w.kodeData.toLowerCase().includes(searchQuery.toLowerCase()));

      const matchesStatus =
        statusFilter === 'ALL' ||
        (statusFilter === 'ACTIVE' && w.active) ||
        (statusFilter === 'INACTIVE' && !w.active);

      const matchesGroup =
        selectedGroup === 'ALL' || (w.groupName || 'General') === selectedGroup;

      return matchesSearch && matchesStatus && matchesGroup;
    });
  }, [webhooks, searchQuery, statusFilter, selectedGroup]);

  // Helper to build listener URL
  const getListenerUrl = (slug: string) => {
    const origin = window.location.origin;
    return `${origin}/api/v1/webhooks/catch/${slug}`;
  };

  const copyToClipboard = (text: string, slugKey?: string) => {
    navigator.clipboard.writeText(text);
    if (slugKey) {
      setCopiedSlug(slugKey);
      setTimeout(() => setCopiedSlug(null), 2000);
    }
    addToast({
      type: 'success',
      title: 'Copied to Clipboard',
      message: text,
      duration: 2500,
    });
  };

  // Generate random secret key
  const handleGenerateSecret = () => {
    const chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789';
    let rand = 'whsec_';
    const cryptoObj = window.crypto || (window as any).msCrypto;
    if (cryptoObj && cryptoObj.getRandomValues) {
      const values = new Uint8Array(24);
      cryptoObj.getRandomValues(values);
      for (let i = 0; i < values.length; i++) {
        rand += chars[values[i] % chars.length];
      }
    } else {
      for (let i = 0; i < 24; i++) {
        rand += chars.charAt(Math.floor(Math.random() * chars.length));
      }
    }
    setEditingConfig(prev => ({ ...prev, secretHeaderValue: rand }));
    setShowSecretValue(true);
    addToast({
      type: 'info',
      title: 'Secret Key Generated',
      message: 'Random secret token generated. You can use it or edit manually.',
      duration: 2500,
    });
  };

  // Toggle Active
  const handleToggleActive = async (webhook: WebhookConfig) => {
    if (!webhook.id) return;
    const newActive = !webhook.active;
    try {
      await axios.patch(`/api/webhooks/${webhook.id}/toggle-active`, { active: newActive });
      setWebhooks(prev =>
        prev.map(item => (item.id === webhook.id ? { ...item, active: newActive } : item))
      );
      addToast({
        type: 'info',
        title: 'Status Updated',
        message: `Webhook "${webhook.name}" is now ${newActive ? 'active' : 'inactive'}.`,
      });
    } catch (err: any) {
      showAlert({
        title: 'Failed to Toggle Status',
        message: err.response?.data?.error || err.message,
        type: 'error',
      });
    }
  };

  // Delete Webhook
  const handleDeleteWebhook = (webhook: WebhookConfig) => {
    if (!webhook.id) return;
    showAlert({
      title: 'Delete Webhook',
      message: `Are you sure you want to permanently delete "${webhook.name}" (${webhook.slug})? All associated logs will also be removed.`,
      type: 'warning',
      confirmLabel: 'Delete',
      cancelLabel: 'Cancel',
      onConfirm: async () => {
        try {
          await axios.delete(`/api/webhooks/${webhook.id}`);
          setWebhooks(prev => prev.filter(item => item.id !== webhook.id));
          addToast({
            type: 'success',
            title: 'Deleted',
            message: `Webhook "${webhook.name}" deleted successfully.`,
          });
        } catch (err: any) {
          showAlert({
            title: 'Delete Failed',
            message: err.response?.data?.error || err.message,
            type: 'error',
          });
        }
      },
    });
  };

  // Test Alert
  const handleTestAlert = async (webhook: WebhookConfig) => {
    if (!webhook.id) return;
    try {
      const res = await axios.post(`/api/webhooks/${webhook.id}/test-alert`);
      showAlert({
        title: 'Test Alert Sent',
        message: res.data?.message || 'Test alert message was successfully delivered to Telegram/Discord.',
        type: 'success',
      });
    } catch (err: any) {
      showAlert({
        title: 'Test Alert Failed',
        message: err.response?.data?.error || err.message,
        type: 'error',
      });
    }
  };

  // Auto-detect parameters and body keys from selected API Schedulers
  const detectedSchedulerParams = useMemo(() => {
    const raw = editingConfig.triggerApiSchedulerId || '';
    const selectedIds = raw.split(/[,;\s]+/).map(s => s.trim()).filter(Boolean);

    const detected: { targetParam: string; sourceType: 'param' | 'body' | 'placeholder'; schedulerName: string }[] = [];
    const seen = new Set<string>();

    selectedIds.forEach(id => {
      const sched = apiSchedulers.find(s => s.id === id);
      if (!sched) return;
      const schedName = sched.name || 'Scheduler';

      // 1. Query Params
      if (sched.queryParams) {
        try {
          const parsed = JSON.parse(sched.queryParams);
          if (typeof parsed === 'object' && parsed !== null) {
            Object.keys(parsed).forEach(k => {
              const cleanK = k.trim();
              if (cleanK && !seen.has(cleanK)) {
                seen.add(cleanK);
                detected.push({ targetParam: cleanK, sourceType: 'param', schedulerName: schedName });
              }
            });
          }
        } catch (_) {}
      }

      // 2. Placeholders {{xxx}} or {xxx}
      const scanPlaceholders = (text?: string) => {
        if (!text) return;
        const regex = /\{\{([a-zA-Z0-9_-]+)\}\}|\{([a-zA-Z0-9_-]+)\}/g;
        let match;
        while ((match = regex.exec(text)) !== null) {
          const ph = match[1] || match[2];
          if (ph && !seen.has(ph)) {
            seen.add(ph);
            detected.push({ targetParam: ph, sourceType: 'placeholder', schedulerName: schedName });
          }
        }
      };

      scanPlaceholders(sched.url);
      scanPlaceholders(sched.queryParams);
      scanPlaceholders(sched.headers);
      scanPlaceholders(sched.bodyContent);

      // 3. Body Content JSON Keys
      if (sched.bodyContent) {
        try {
          const parsed = JSON.parse(sched.bodyContent);
          if (typeof parsed === 'object' && parsed !== null) {
            const scanKeys = (obj: any) => {
              if (Array.isArray(obj)) {
                if (obj.length > 0 && typeof obj[0] === 'object') scanKeys(obj[0]);
              } else if (typeof obj === 'object' && obj !== null) {
                Object.keys(obj).forEach(k => {
                  const cleanK = k.trim();
                  if (cleanK && !seen.has(cleanK)) {
                    seen.add(cleanK);
                    detected.push({ targetParam: cleanK, sourceType: 'body', schedulerName: schedName });
                  }
                  if (typeof obj[k] === 'object' && obj[k] !== null && !Array.isArray(obj[k])) {
                    scanKeys(obj[k]);
                  }
                });
              }
            };
            scanKeys(parsed);
          }
        } catch (_) {}
      }
    });

    return detected;
  }, [editingConfig.triggerApiSchedulerId, apiSchedulers]);

  const handleSyncDetectedParams = () => {
    setParamMappings(prev => {
      const currentMap = new Map(prev.map(p => [p.targetParam, p]));
      const next = [...prev];
      let added = 0;

      detectedSchedulerParams.forEach(dp => {
        if (!currentMap.has(dp.targetParam)) {
          let defaultSource = dp.targetParam;
          if (defaultSource.endsWith('s') && defaultSource.length > 2) {
            defaultSource = defaultSource.slice(0, -1);
          }
          next.push({
            targetParam: dp.targetParam,
            sourceJsonPath: defaultSource,
            sourceType: dp.sourceType,
          });
          added++;
        }
      });

      if (added > 0) {
        addToast({
          type: 'success',
          title: 'Parameter Disinkronkan',
          message: `${added} parameter baru dari API Scheduler ditambahkan ke mapping.`,
        });
      } else {
        addToast({
          type: 'info',
          title: 'Sudah Sesuai',
          message: 'Semua parameter dari API Scheduler sudah ada dalam mapping.',
        });
      }
      return next;
    });
  };

  // Open Editor for New or Existing
  const handleOpenEditor = (webhook?: WebhookConfig) => {
    if (webhook) {
      setEditingConfig({
        ...webhook,
        enableEnrichment: webhook.enableEnrichment ?? false,
        enrichmentFilterStatus: webhook.enrichmentFilterStatus || 'READY_TO_SHIP',
        enrichmentTargetConnectionId: webhook.enrichmentTargetConnectionId || connections[0]?.id || '',
        enrichmentTargetTable: webhook.enrichmentTargetTable || '',
        enrichmentKodeData: webhook.enrichmentKodeData || 'GINEE_READY_TO_SHIP',
        enrichmentGineeAccessKey: webhook.enrichmentGineeAccessKey || '',
        enrichmentGineeSecretKey: webhook.enrichmentGineeSecretKey || '',
      });

      // Parse filter rules
      if (webhook.triggerFilterRules && webhook.triggerFilterRules.trim()) {
        try {
          const parsed = JSON.parse(webhook.triggerFilterRules);
          if (Array.isArray(parsed) && parsed.length > 0) {
            setFilterRules(parsed);
          } else {
            setFilterRules([{ key: webhook.triggerFilterKey || 'orderStatus', value: webhook.triggerFilterValue || 'READY_TO_SHIP' }]);
          }
        } catch (_) {
          setFilterRules([{ key: webhook.triggerFilterKey || 'orderStatus', value: webhook.triggerFilterValue || 'READY_TO_SHIP' }]);
        }
      } else {
        setFilterRules([{ key: webhook.triggerFilterKey || 'orderStatus', value: webhook.triggerFilterValue || 'READY_TO_SHIP' }]);
      }

      // Parse param mappings
      if (webhook.triggerParamMapping && webhook.triggerParamMapping.trim()) {
        try {
          const parsed = JSON.parse(webhook.triggerParamMapping);
          if (Array.isArray(parsed) && parsed.length > 0) {
            setParamMappings(parsed);
          } else {
            const tgt = webhook.triggerParamTarget ? webhook.triggerParamTarget.replace(/[{}]/g, '').trim() : 'orderId';
            setParamMappings([{ targetParam: tgt, sourceJsonPath: webhook.triggerParamKey || 'orderId', sourceType: 'placeholder' }]);
          }
        } catch (_) {
          const tgt = webhook.triggerParamTarget ? webhook.triggerParamTarget.replace(/[{}]/g, '').trim() : 'orderId';
          setParamMappings([{ targetParam: tgt, sourceJsonPath: webhook.triggerParamKey || 'orderId', sourceType: 'placeholder' }]);
        }
      } else {
        const tgt = webhook.triggerParamTarget ? webhook.triggerParamTarget.replace(/[{}]/g, '').trim() : 'orderId';
        setParamMappings([{ targetParam: tgt, sourceJsonPath: webhook.triggerParamKey || 'orderId', sourceType: 'placeholder' }]);
      }
    } else {
      const firstConn = connections[0]?.id || '';
      setEditingConfig({
        name: '',
        slug: '',
        description: '',
        groupName: selectedGroup !== 'ALL' ? selectedGroup : 'General',
        secretHeaderName: 'X-Ginee-Signature',
        secretHeaderValue: '',
        ipAllowlist: '',
        targetConnectionId: firstConn,
        targetTable: '',
        kodeData: 'GINEE_WEBHOOK',
        enableEnrichment: false,
        triggerApiSchedulerId: '',
        triggerFilterKey: 'orderStatus',
        triggerFilterValue: 'READY_TO_SHIP',
        triggerParamKey: 'orderId',
        triggerParamTarget: '{{orderId}}',
        enrichmentFilterStatus: 'READY_TO_SHIP',
        enrichmentTargetConnectionId: firstConn,
        enrichmentTargetTable: '',
        enrichmentKodeData: 'GINEE_READY_TO_SHIP',
        enrichmentGineeAccessKey: '',
        enrichmentGineeSecretKey: '',
        notificationChannelId: channels.length > 0 ? channels[0].id : '',
        active: true,
      });
      setFilterRules([{ key: 'orderStatus', value: 'READY_TO_SHIP' }]);
      setParamMappings([{ targetParam: 'orderId', sourceJsonPath: 'orderId', sourceType: 'placeholder' }]);
    }
    setViewMode('editor');
  };

  // Auto slugify name if slug not yet touched
  const handleNameChange = (val: string) => {
    const isNew = !editingConfig.id;
    const generatedSlug = val
      .toLowerCase()
      .trim()
      .replace(/[^a-z0-9-_]+/g, '-')
      .replace(/^-+|-+$/g, '');

    setEditingConfig(prev => ({
      ...prev,
      name: val,
      slug: isNew ? generatedSlug : prev.slug,
    }));
  };

  // Save Webhook
  const handleSaveWebhook = async () => {
    if (!editingConfig.name || !editingConfig.name.trim()) {
      showAlert({ title: 'Validation Error', message: 'Webhook name is required.', type: 'warning' });
      return;
    }
    if (!editingConfig.targetConnectionId) {
      showAlert({ title: 'Validation Error', message: 'Please select a Target Database Connection for Raw Ingestion.', type: 'warning' });
      return;
    }
    if (!editingConfig.targetTable || !editingConfig.targetTable.trim()) {
      showAlert({ title: 'Validation Error', message: 'Target table name for Raw Ingestion is required.', type: 'warning' });
      return;
    }

    const cleanFilterRules = filterRules.filter(r => r.key && r.key.trim());
    const cleanParamMappings = paramMappings.filter(p => p.targetParam && p.targetParam.trim());

    if (editingConfig.enableEnrichment) {
      const selectedSchedIds = (editingConfig.triggerApiSchedulerId || '')
        .split(/[,;\s]+/)
        .map(s => s.trim())
        .filter(Boolean);

      if (selectedSchedIds.length === 0 && !editingConfig.enrichmentTargetConnectionId) {
        showAlert({
          title: 'Validation Error',
          message: 'Pilih minimal satu API Scheduler yang akan di-trigger pada Bagian 4.',
          type: 'warning',
        });
        return;
      }
      if (cleanParamMappings.length === 0) {
        showAlert({
          title: 'Validation Error',
          message: 'Tentukan minimal satu mapping parameter untuk menyuplai parameter ke API Scheduler.',
          type: 'warning',
        });
        return;
      }
    }

    // Auto-fill target storage from first selected scheduler if missing
    let effTargetConn = editingConfig.enrichmentTargetConnectionId;
    let effTargetTable = editingConfig.enrichmentTargetTable;
    let effKodeData = editingConfig.enrichmentKodeData;
    if (editingConfig.triggerApiSchedulerId) {
      const firstId = editingConfig.triggerApiSchedulerId.split(/[,;\s]+/)[0]?.trim();
      const sched = apiSchedulers.find(s => s.id === firstId);
      if (sched) {
        if (!effTargetConn) effTargetConn = sched.targetConnectionId;
        if (!effTargetTable) effTargetTable = sched.targetTable;
        if (!effKodeData) effKodeData = sched.kodeData;
      }
    }

    const payloadToSave: Partial<WebhookConfig> = {
      ...editingConfig,
      enrichmentTargetConnectionId: effTargetConn,
      enrichmentTargetTable: effTargetTable,
      enrichmentKodeData: effKodeData,
      triggerFilterRules: JSON.stringify(cleanFilterRules),
      triggerParamMapping: JSON.stringify(cleanParamMappings),
      triggerFilterKey: cleanFilterRules[0]?.key || 'orderStatus',
      triggerFilterValue: cleanFilterRules[0]?.value || 'READY_TO_SHIP',
      triggerParamKey: cleanParamMappings[0]?.sourceJsonPath || 'orderId',
      triggerParamTarget: cleanParamMappings[0]?.targetParam ? `{{${cleanParamMappings[0].targetParam}}}` : '{{orderId}}',
      enrichmentFilterStatus: cleanFilterRules[0]?.value || 'READY_TO_SHIP',
    };

    setIsSaving(true);
    try {
      if (editingConfig.id) {
        const res = await axios.put(`/api/webhooks/${editingConfig.id}`, payloadToSave);
        setWebhooks(prev => prev.map(w => (w.id === editingConfig.id ? res.data : w)));
        addToast({
          type: 'success',
          title: 'Webhook Updated',
          message: `Webhook "${editingConfig.name}" updated successfully.`,
        });
      } else {
        const res = await axios.post('/api/webhooks', payloadToSave);
        setWebhooks(prev => [res.data, ...prev]);
        addToast({
          type: 'success',
          title: 'Webhook Created',
          message: `Webhook "${res.data.name}" created and ready to receive requests!`,
        });
      }
      setViewMode('list');
    } catch (err: any) {
      showAlert({
        title: 'Failed to Save Webhook',
        message: err.response?.data?.error || err.message,
        type: 'error',
      });
    } finally {
      setIsSaving(false);
    }
  };

  // Open Logs Drawer
  const handleOpenLogs = async (webhook: WebhookConfig) => {
    if (!webhook.id) return;
    setActiveLogWebhook(webhook);
    setIsLogsModalOpen(true);
    setLoadingLogs(true);
    try {
      const res = await axios.get(`/api/webhooks/${webhook.id}/logs?limit=100`);
      setLogs(Array.isArray(res.data) ? res.data : []);
    } catch (err: any) {
      console.error('Failed to load webhook logs', err);
    } finally {
      setLoadingLogs(false);
    }
  };

  const handleClearLogs = async () => {
    if (!activeLogWebhook?.id) return;
    showAlert({
      title: 'Clear Webhook Activity Logs',
      message: `Are you sure you want to clear all activity logs for "${activeLogWebhook.name}"?`,
      type: 'warning',
      confirmLabel: 'Clear All',
      cancelLabel: 'Cancel',
      onConfirm: async () => {
        try {
          await axios.delete(`/api/webhooks/${activeLogWebhook.id}/logs`);
          setLogs([]);
          addToast({ type: 'success', title: 'Logs Cleared', message: 'All logs cleared successfully.' });
        } catch (err: any) {
          showAlert({ title: 'Error', message: err.message, type: 'error' });
        }
      },
    });
  };

  // Notification Channel Names Helper
  const getChannelNames = (channelIdsStr?: string) => {
    if (!channelIdsStr || !channelIdsStr.trim()) return [];
    const ids = channelIdsStr.split(',').map(s => s.trim());
    return ids
      .map(id => channels.find(c => c.id === id))
      .filter(Boolean) as NotificationChannel[];
  };

  return (
    <div className="flex-1 flex flex-col h-full bg-bg-main text-text-main overflow-hidden">
      {/* Top Banner / Breadcrumb */}
      {viewMode === 'list' ? (
        <div className="bg-bg-panel border-b border-border-main p-4 shrink-0 flex flex-wrap items-center justify-between gap-4">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 rounded-xl bg-gradient-to-br from-indigo-500 to-purple-600 flex items-center justify-center shadow-lg shadow-indigo-500/20">
              <Webhook className="w-5 h-5 text-white" />
            </div>
            <div>
              <div className="flex items-center gap-2">
                <h1 className="text-lg font-bold text-text-main">Inbound Webhooks</h1>
                <span className="px-2 py-0.5 text-xs font-semibold rounded-full bg-indigo-500/15 text-indigo-400 border border-indigo-500/25">
                  Strict 5-Column DDL
                </span>
              </div>
              <p className="text-xs text-text-muted mt-0.5">
                Ingest real-time webhooks (Ginee, e-commerce, third parties) directly into ClickHouse / PostgreSQL with Telegram & Discord alerts.
              </p>
            </div>
          </div>

          <div className="flex items-center gap-2">
            <button
              onClick={() => setIsChannelModalOpen(true)}
              className="px-3 py-2 text-xs font-medium rounded-lg bg-bg-hover hover:bg-bg-active border border-border-main text-text-muted hover:text-text-main flex items-center gap-1.5 transition-colors"
              title="Configure Telegram & Discord notification channels"
            >
              <Bell className="w-3.5 h-3.5 text-amber-400" />
              <span>Notification Channels</span>
              {channels.length > 0 && (
                <span className="ml-1 px-1.5 py-0.2 bg-amber-500/20 text-amber-400 rounded-full text-[10px] font-bold">
                  {channels.length}
                </span>
              )}
            </button>

            <button
              onClick={fetchWebhooks}
              disabled={loading}
              className="p-2 text-text-muted hover:text-text-main bg-bg-hover hover:bg-bg-active border border-border-main rounded-lg transition-colors"
              title="Refresh Webhooks"
            >
              <RefreshCw className={clsx('w-4 h-4', loading && 'animate-spin text-blue-400')} />
            </button>

            <button
              onClick={() => handleOpenEditor()}
              className="px-3.5 py-2 text-xs font-semibold rounded-lg bg-gradient-to-r from-indigo-600 to-purple-600 hover:from-indigo-500 hover:to-purple-500 text-white shadow-md shadow-indigo-500/20 flex items-center gap-2 transition-all"
            >
              <Plus className="w-4 h-4" />
              <span>Create Webhook</span>
            </button>
          </div>
        </div>
      ) : (
        <div className="bg-bg-panel border-b border-border-main px-4 py-3 shrink-0 flex items-center justify-between">
          <div className="flex items-center gap-3">
            <button
              onClick={() => setViewMode('list')}
              className="p-1.5 rounded-lg bg-bg-hover hover:bg-bg-active text-text-muted hover:text-text-main border border-border-main transition-colors flex items-center gap-1.5 text-xs font-medium"
            >
              <ArrowLeft className="w-4 h-4" />
              <span>Back to Webhook List</span>
            </button>
            <div className="h-5 w-px bg-border-main" />
            <h2 className="text-sm font-bold text-text-main">
              {editingConfig.id ? `Edit Webhook: ${editingConfig.name}` : 'Create New Inbound Webhook'}
            </h2>
          </div>

          <div className="flex items-center gap-2">
            <button
              onClick={() => setViewMode('list')}
              className="px-3 py-1.5 text-xs font-medium rounded-lg text-text-muted hover:text-text-main hover:bg-bg-hover border border-border-main transition-colors"
            >
              Cancel
            </button>
            <button
              onClick={handleSaveWebhook}
              disabled={isSaving}
              className="px-4 py-1.5 text-xs font-semibold rounded-lg bg-indigo-600 hover:bg-indigo-500 text-white flex items-center gap-1.5 shadow-sm transition-colors"
            >
              {isSaving ? <RefreshCw className="w-3.5 h-3.5 animate-spin" /> : <Check className="w-3.5 h-3.5" />}
              <span>{editingConfig.id ? 'Save Changes' : 'Create Webhook'}</span>
            </button>
          </div>
        </div>
      )}

      {/* Main Workspace */}
      <div className="flex-1 overflow-y-auto p-4 md:p-6 space-y-4">
        {viewMode === 'list' ? (
          <>
            {/* Filter and Group Bar */}
            <div className="bg-bg-panel border border-border-main rounded-xl p-3 flex flex-wrap items-center justify-between gap-3">
              {/* Search */}
              <div className="relative min-w-[220px] flex-1 max-w-md">
                <Search className="w-4 h-4 text-text-muted absolute left-3 top-1/2 -translate-y-1/2" />
                <input
                  type="text"
                  placeholder="Search webhooks, slug, target table..."
                  value={searchQuery}
                  onChange={e => setSearchQuery(e.target.value)}
                  className="w-full pl-9 pr-3 py-1.5 text-xs rounded-lg bg-bg-main border border-border-main text-text-main focus:outline-none focus:border-indigo-500"
                />
              </div>

              {/* Group Tabs */}
              <div className="flex items-center gap-1 overflow-x-auto max-w-full">
                <button
                  onClick={() => setSelectedGroup('ALL')}
                  className={clsx(
                    'px-2.5 py-1 text-xs font-medium rounded-lg transition-colors shrink-0',
                    selectedGroup === 'ALL'
                      ? 'bg-indigo-600 text-white'
                      : 'text-text-muted hover:text-text-main hover:bg-bg-hover'
                  )}
                >
                  All ({webhooks.length})
                </button>
                {groups.map(grp => {
                  const count = webhooks.filter(w => (w.groupName || 'General') === grp).length;
                  return (
                    <button
                      key={grp}
                      onClick={() => setSelectedGroup(grp)}
                      className={clsx(
                        'px-2.5 py-1 text-xs font-medium rounded-lg transition-colors shrink-0',
                        selectedGroup === grp
                          ? 'bg-indigo-600 text-white'
                          : 'text-text-muted hover:text-text-main hover:bg-bg-hover'
                      )}
                    >
                      {grp} ({count})
                    </button>
                  );
                })}
              </div>

              {/* Status & Layout Switcher */}
              <div className="flex items-center gap-2">
                <div className="flex items-center rounded-lg border border-border-main bg-bg-main p-0.5 text-xs">
                  <button
                    onClick={() => setStatusFilter('ALL')}
                    className={clsx(
                      'px-2 py-0.5 rounded-md font-medium transition-colors',
                      statusFilter === 'ALL' ? 'bg-bg-hover text-text-main' : 'text-text-muted'
                    )}
                  >
                    All
                  </button>
                  <button
                    onClick={() => setStatusFilter('ACTIVE')}
                    className={clsx(
                      'px-2 py-0.5 rounded-md font-medium transition-colors',
                      statusFilter === 'ACTIVE' ? 'bg-emerald-500/20 text-emerald-400' : 'text-text-muted'
                    )}
                  >
                    Active
                  </button>
                  <button
                    onClick={() => setStatusFilter('INACTIVE')}
                    className={clsx(
                      'px-2 py-0.5 rounded-md font-medium transition-colors',
                      statusFilter === 'INACTIVE' ? 'bg-rose-500/20 text-rose-400' : 'text-text-muted'
                    )}
                  >
                    Inactive
                  </button>
                </div>

                <div className="flex items-center rounded-lg border border-border-main bg-bg-main p-0.5 text-xs">
                  <button
                    onClick={() => setLayoutMode('cards')}
                    className={clsx(
                      'p-1.5 rounded-md transition-colors',
                      layoutMode === 'cards' ? 'bg-bg-hover text-indigo-400' : 'text-text-muted'
                    )}
                    title="Grid Card View"
                  >
                    <Grid className="w-3.5 h-3.5" />
                  </button>
                  <button
                    onClick={() => setLayoutMode('table')}
                    className={clsx(
                      'p-1.5 rounded-md transition-colors',
                      layoutMode === 'table' ? 'bg-bg-hover text-indigo-400' : 'text-text-muted'
                    )}
                    title="Table View"
                  >
                    <List className="w-3.5 h-3.5" />
                  </button>
                </div>
              </div>
            </div>

            {/* Content Display: Empty State or Cards / Table */}
            {loading ? (
              <div className="py-20 flex flex-col items-center justify-center text-text-muted">
                <RefreshCw className="w-8 h-8 animate-spin text-indigo-500 mb-3" />
                <p className="text-sm">Loading webhooks...</p>
              </div>
            ) : filteredWebhooks.length === 0 ? (
              <div className="py-20 bg-bg-panel border border-dashed border-border-main rounded-2xl flex flex-col items-center justify-center text-center p-6">
                <div className="w-14 h-14 rounded-2xl bg-indigo-500/10 text-indigo-400 flex items-center justify-center mb-3">
                  <Webhook className="w-7 h-7" />
                </div>
                <h3 className="text-base font-bold text-text-main">No Webhooks Found</h3>
                <p className="text-xs text-text-muted max-w-md mt-1 mb-4">
                  {searchQuery || statusFilter !== 'ALL' || selectedGroup !== 'ALL'
                    ? 'No webhooks matched your current filter criteria.'
                    : 'Create your first webhook listener to start receiving and ingesting real-time event payloads from Ginee or external services.'}
                </p>
                <button
                  onClick={() => handleOpenEditor()}
                  className="px-4 py-2 text-xs font-semibold rounded-lg bg-indigo-600 hover:bg-indigo-500 text-white flex items-center gap-2 shadow-md shadow-indigo-500/20"
                >
                  <Plus className="w-4 h-4" />
                  <span>Create Webhook</span>
                </button>
              </div>
            ) : layoutMode === 'cards' ? (
              /* Grid Cards View */
              <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
                {filteredWebhooks.map(w => {
                  const listenerUrl = getListenerUrl(w.slug);
                  const conn = connections.find(c => c.id === w.targetConnectionId);
                  const alertChans = getChannelNames(w.notificationChannelId);
                  const isCopied = copiedSlug === w.slug;

                  return (
                    <div
                      key={w.id}
                      className={clsx(
                        'bg-bg-panel border rounded-xl p-4 flex flex-col justify-between transition-all hover:border-indigo-500/50 hover:shadow-md',
                        w.active ? 'border-border-main' : 'border-border-main/50 opacity-75'
                      )}
                    >
                      {/* Card Header */}
                      <div>
                        <div className="flex items-start justify-between gap-2 mb-2">
                          <div className="min-w-0">
                            <div className="flex items-center gap-2">
                              <h3 className="text-sm font-bold text-text-main truncate" title={w.name}>
                                {w.name}
                              </h3>
                              <span className="px-1.5 py-0.2 rounded text-[10px] font-semibold bg-bg-hover text-text-muted border border-border-main">
                                {w.groupName || 'General'}
                              </span>
                            </div>
                            {w.description && (
                              <p className="text-xs text-text-muted truncate mt-0.5" title={w.description}>
                                {w.description}
                              </p>
                            )}
                          </div>

                          <button
                            onClick={() => handleToggleActive(w)}
                            className={clsx(
                              'px-2 py-0.5 rounded-full text-[11px] font-semibold border transition-colors shrink-0',
                              w.active
                                ? 'bg-emerald-500/15 text-emerald-400 border-emerald-500/30 hover:bg-emerald-500/25'
                                : 'bg-rose-500/15 text-rose-400 border-rose-500/30 hover:bg-rose-500/25'
                            )}
                          >
                            {w.active ? 'Active' : 'Disabled'}
                          </button>
                        </div>

                        {/* Inbound Listener URL Box */}
                        <div className="mt-3 bg-bg-main border border-border-main rounded-lg p-2 flex items-center justify-between gap-2">
                          <div className="min-w-0">
                            <span className="text-[10px] font-bold text-indigo-400 block">POST LISTENER URL</span>
                            <span className="text-[11px] font-mono text-text-main truncate block select-all">
                              {listenerUrl}
                            </span>
                          </div>
                          <button
                            onClick={() => copyToClipboard(listenerUrl, w.slug)}
                            className="p-1.5 rounded-md hover:bg-bg-hover text-text-muted hover:text-text-main transition-colors shrink-0"
                            title="Copy Webhook Catch URL"
                          >
                            {isCopied ? (
                              <Check className="w-3.5 h-3.5 text-emerald-400" />
                            ) : (
                              <Copy className="w-3.5 h-3.5" />
                            )}
                          </button>
                        </div>

                        {/* Metadata Rows */}
                        <div className="mt-3 space-y-1.5 text-xs text-text-muted">
                          {/* Target DB & Table */}
                          <div className="flex items-center justify-between gap-2">
                            <span className="flex items-center gap-1.5">
                              <Database className="w-3.5 h-3.5 text-blue-400" />
                              <span>Target Mentah:</span>
                            </span>
                            <div className="text-right truncate">
                              <span className="font-semibold text-text-main">{conn?.name || 'N/A'}</span>
                              <span className="mx-1 text-border-main">/</span>
                              <code className="text-indigo-400 bg-indigo-500/10 px-1 py-0.5 rounded text-[11px]">
                                {w.targetTable}
                              </code>
                            </div>
                          </div>

                          {/* Enriched Detail Target */}
                          {w.enableEnrichment && (
                            <div className="flex items-center justify-between gap-2 bg-emerald-500/10 border border-emerald-500/20 px-2 py-1 rounded">
                              <span className="flex items-center gap-1 text-[11px] font-semibold text-emerald-400">
                                <Sparkles className="w-3 h-3 text-emerald-400 shrink-0" />
                                <span>Detail ({w.enrichmentFilterStatus || 'READY_TO_SHIP'}):</span>
                              </span>
                              <div className="text-right truncate text-[11px] font-mono text-emerald-300">
                                {w.enrichmentTargetTable || 'N/A'}
                              </div>
                            </div>
                          )}

                          {/* Kode Data */}
                          <div className="flex items-center justify-between gap-2">
                            <span>Kode Data:</span>
                            <code className="font-mono text-text-main text-[11px]">
                              {w.kodeData || 'WEBHOOK'}
                            </code>
                          </div>

                          {/* Security: Header & IP */}
                          <div className="flex items-center justify-between gap-2">
                            <span className="flex items-center gap-1.5">
                              {w.secretHeaderName ? (
                                <Key className="w-3.5 h-3.5 text-amber-400" />
                              ) : (
                                <ShieldCheck className="w-3.5 h-3.5 text-emerald-400" />
                              )}
                              <span>Auth:</span>
                            </span>
                            <span className="truncate">
                              {w.secretHeaderName ? (
                                <span className="text-amber-400 font-mono text-[11px] bg-amber-500/10 px-1 py-0.5 rounded">
                                  {w.secretHeaderName}
                                </span>
                              ) : (
                                <span className="text-text-muted text-[11px]">None (Public)</span>
                              )}
                            </span>
                          </div>

                          {/* Alert Channels */}
                          <div className="flex items-center justify-between gap-2">
                            <span className="flex items-center gap-1.5">
                              <Bell className="w-3.5 h-3.5 text-purple-400" />
                              <span>Alerts:</span>
                            </span>
                            <div className="flex items-center gap-1 truncate">
                              {alertChans.length > 0 ? (
                                alertChans.map(c => (
                                  <span
                                    key={c.id}
                                    className="px-1.5 py-0.2 rounded text-[10px] bg-purple-500/15 text-purple-400 border border-purple-500/25"
                                  >
                                    {c.name}
                                  </span>
                                ))
                              ) : (
                                <span className="text-text-muted text-[11px]">No alert channel</span>
                              )}
                            </div>
                          </div>
                        </div>

                        {/* Request Stats */}
                        <div className="mt-3 pt-3 border-t border-border-main flex items-center justify-between text-xs">
                          <div className="flex items-center gap-3">
                            <span title="Total Ingested">
                              Total: <b className="text-text-main">{w.totalRequests || 0}</b>
                            </span>
                            <span className="text-emerald-400" title="Successful Requests">
                              ✓ {w.successCount || 0}
                            </span>
                            <span className="text-rose-400" title="Failed Requests">
                              ✗ {w.failureCount || 0}
                            </span>
                          </div>

                          {w.lastStatus && (
                            <span
                              className={clsx(
                                'px-1.5 py-0.2 text-[10px] font-bold rounded',
                                w.lastStatus === 'SUCCESS'
                                  ? 'bg-emerald-500/15 text-emerald-400'
                                  : 'bg-rose-500/15 text-rose-400'
                              )}
                            >
                              {w.lastStatus}
                            </span>
                          )}
                        </div>
                      </div>

                      {/* Card Action Buttons */}
                      <div className="mt-4 pt-3 border-t border-border-main flex items-center justify-between gap-2">
                        <div className="flex items-center gap-1">
                          <button
                            onClick={() => handleOpenLogs(w)}
                            className="px-2.5 py-1 text-xs font-medium rounded-lg bg-bg-hover hover:bg-bg-active text-text-muted hover:text-text-main border border-border-main flex items-center gap-1 transition-colors"
                            title="View Ingestion Logs"
                          >
                            <Activity className="w-3.5 h-3.5 text-cyan-400" />
                            <span>Logs</span>
                          </button>
                          <button
                            onClick={() => handleTestAlert(w)}
                            className="px-2.5 py-1 text-xs font-medium rounded-lg bg-bg-hover hover:bg-bg-active text-text-muted hover:text-text-main border border-border-main flex items-center gap-1 transition-colors"
                            title="Test Telegram/Discord Alert"
                          >
                            <Send className="w-3.5 h-3.5 text-purple-400" />
                            <span>Test Alert</span>
                          </button>
                        </div>

                        <div className="flex items-center gap-1">
                          <button
                            onClick={() => handleOpenEditor(w)}
                            className="p-1.5 rounded-lg hover:bg-bg-hover text-text-muted hover:text-text-main border border-border-main transition-colors"
                            title="Edit Webhook"
                          >
                            <Edit3 className="w-3.5 h-3.5" />
                          </button>
                          <button
                            onClick={() => handleDeleteWebhook(w)}
                            className="p-1.5 rounded-lg hover:bg-rose-500/15 text-text-muted hover:text-rose-400 border border-border-main transition-colors"
                            title="Delete Webhook"
                          >
                            <Trash2 className="w-3.5 h-3.5" />
                          </button>
                        </div>
                      </div>
                    </div>
                  );
                })}
              </div>
            ) : (
              /* Table Rows View */
              <div className="bg-bg-panel border border-border-main rounded-xl overflow-hidden shadow-sm">
                <div className="overflow-x-auto">
                  <table className="w-full text-left border-collapse text-xs">
                    <thead>
                      <tr className="border-b border-border-main bg-bg-main/50 text-text-muted font-semibold">
                        <th className="p-3">Webhook Name</th>
                        <th className="p-3">Slug & Catch URL</th>
                        <th className="p-3">Target Database / Table</th>
                        <th className="p-3">Security & Auth</th>
                        <th className="p-3">Alerts</th>
                        <th className="p-3 text-center">Requests</th>
                        <th className="p-3 text-center">Status</th>
                        <th className="p-3 text-right">Actions</th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-border-main">
                      {filteredWebhooks.map(w => {
                        const listenerUrl = getListenerUrl(w.slug);
                        const conn = connections.find(c => c.id === w.targetConnectionId);
                        const alertChans = getChannelNames(w.notificationChannelId);
                        const isCopied = copiedSlug === w.slug;

                        return (
                          <tr key={w.id} className="hover:bg-bg-hover/50 transition-colors">
                            <td className="p-3">
                              <div className="font-bold text-text-main">{w.name}</div>
                              <span className="inline-block mt-0.5 px-1.5 py-0.2 rounded text-[10px] font-medium bg-bg-hover text-text-muted border border-border-main">
                                {w.groupName || 'General'}
                              </span>
                            </td>
                            <td className="p-3">
                              <div className="flex items-center gap-1.5 max-w-xs">
                                <code className="font-mono text-indigo-400 bg-indigo-500/10 px-1.5 py-0.5 rounded text-[11px] truncate">
                                  /catch/{w.slug}
                                </code>
                                <button
                                  onClick={() => copyToClipboard(listenerUrl, w.slug)}
                                  className="p-1 rounded hover:bg-bg-hover text-text-muted hover:text-text-main"
                                  title="Copy Full URL"
                                >
                                  {isCopied ? (
                                    <Check className="w-3.5 h-3.5 text-emerald-400" />
                                  ) : (
                                    <Copy className="w-3.5 h-3.5" />
                                  )}
                                </button>
                              </div>
                            </td>
                            <td className="p-3">
                              <div className="text-text-main font-medium">{conn?.name || 'N/A'}</div>
                              <div className="text-indigo-400 font-mono text-[11px]">{w.targetTable}</div>
                              {w.enableEnrichment && (
                                <div className="text-emerald-400 font-mono text-[10px] mt-0.5 flex items-center gap-1">
                                  <Sparkles className="w-2.5 h-2.5 text-emerald-400" />
                                  <span>Detail: {w.enrichmentTargetTable || 'N/A'}</span>
                                </div>
                              )}
                            </td>
                            <td className="p-3">
                              {w.secretHeaderName ? (
                                <span className="font-mono text-amber-400 bg-amber-500/10 px-1.5 py-0.5 rounded text-[11px]">
                                  {w.secretHeaderName}
                                </span>
                              ) : (
                                <span className="text-text-muted">Public</span>
                              )}
                            </td>
                            <td className="p-3">
                              <div className="flex flex-wrap gap-1">
                                {alertChans.length > 0 ? (
                                  alertChans.map(c => (
                                    <span
                                      key={c.id}
                                      className="px-1.5 py-0.2 rounded text-[10px] bg-purple-500/15 text-purple-400 border border-purple-500/25"
                                    >
                                      {c.name}
                                    </span>
                                  ))
                                ) : (
                                  <span className="text-text-muted">None</span>
                                )}
                              </div>
                            </td>
                            <td className="p-3 text-center">
                              <div className="font-bold text-text-main">{w.totalRequests || 0}</div>
                              <div className="text-[10px] text-text-muted">
                                <span className="text-emerald-400">✓{w.successCount || 0}</span> |{' '}
                                <span className="text-rose-400">✗{w.failureCount || 0}</span>
                              </div>
                            </td>
                            <td className="p-3 text-center">
                              <button
                                onClick={() => handleToggleActive(w)}
                                className={clsx(
                                  'px-2 py-0.5 rounded-full text-[11px] font-semibold border transition-colors',
                                  w.active
                                    ? 'bg-emerald-500/15 text-emerald-400 border-emerald-500/30'
                                    : 'bg-rose-500/15 text-rose-400 border-rose-500/30'
                                )}
                              >
                                {w.active ? 'Active' : 'Disabled'}
                              </button>
                            </td>
                            <td className="p-3 text-right">
                              <div className="flex items-center justify-end gap-1">
                                <button
                                  onClick={() => handleOpenLogs(w)}
                                  className="p-1.5 rounded hover:bg-bg-hover text-text-muted hover:text-cyan-400"
                                  title="Logs"
                                >
                                  <Activity className="w-3.5 h-3.5" />
                                </button>
                                <button
                                  onClick={() => handleTestAlert(w)}
                                  className="p-1.5 rounded hover:bg-bg-hover text-text-muted hover:text-purple-400"
                                  title="Test Alert"
                                >
                                  <Send className="w-3.5 h-3.5" />
                                </button>
                                <button
                                  onClick={() => handleOpenEditor(w)}
                                  className="p-1.5 rounded hover:bg-bg-hover text-text-muted hover:text-text-main"
                                  title="Edit"
                                >
                                  <Edit3 className="w-3.5 h-3.5" />
                                </button>
                                <button
                                  onClick={() => handleDeleteWebhook(w)}
                                  className="p-1.5 rounded hover:bg-rose-500/15 text-text-muted hover:text-rose-400"
                                  title="Delete"
                                >
                                  <Trash2 className="w-3.5 h-3.5" />
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
          </>
        ) : (
          /* Editor Mode */
          <div className="max-w-4xl mx-auto space-y-6">
            {/* Card 1: General Info */}
            <div className="bg-bg-panel border border-border-main rounded-xl p-5 shadow-sm space-y-4">
              <div className="flex items-center gap-2 pb-3 border-b border-border-main">
                <div className="w-8 h-8 rounded-lg bg-indigo-500/15 text-indigo-400 flex items-center justify-center font-bold text-xs">
                  1
                </div>
                <div>
                  <h3 className="text-sm font-bold text-text-main">General Webhook Information</h3>
                  <p className="text-xs text-text-muted">
                    Set the identifier, friendly name, and URL slug for your inbound webhook listener.
                  </p>
                </div>
              </div>

              <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                <div>
                  <label className="block text-xs font-semibold text-text-main mb-1.5">
                    Webhook Name <span className="text-rose-400">*</span>
                  </label>
                  <input
                    type="text"
                    placeholder="e.g. Ginee Omnichannel Order Catch"
                    value={editingConfig.name || ''}
                    onChange={e => handleNameChange(e.target.value)}
                    className="w-full px-3 py-2 text-xs rounded-lg bg-bg-main border border-border-main text-text-main focus:outline-none focus:border-indigo-500"
                  />
                </div>

                <div>
                  <label className="block text-xs font-semibold text-text-main mb-1.5">
                    URL Slug <span className="text-rose-400">*</span>
                  </label>
                  <div className="flex items-center">
                    <span className="px-2.5 py-2 bg-bg-hover text-text-muted border border-r-0 border-border-main rounded-l-lg text-xs font-mono">
                      /catch/
                    </span>
                    <input
                      type="text"
                      placeholder="ginee-orders"
                      value={editingConfig.slug || ''}
                      onChange={e =>
                        setEditingConfig(prev => ({
                          ...prev,
                          slug: e.target.value.toLowerCase().replace(/[^a-z0-9-_]/g, '-'),
                        }))
                      }
                      className="w-full px-3 py-2 text-xs rounded-r-lg bg-bg-main border border-border-main text-text-main font-mono focus:outline-none focus:border-indigo-500"
                    />
                  </div>
                </div>
              </div>

              {/* Endpoint URL Preview Box */}
              {editingConfig.slug && (
                <div className="bg-indigo-500/10 border border-indigo-500/25 rounded-lg p-3 flex items-center justify-between gap-3">
                  <div className="min-w-0">
                    <span className="text-[10px] font-bold text-indigo-400 uppercase tracking-wider block">
                      Webhook Listener Public Endpoint
                    </span>
                    <span className="text-xs font-mono text-text-main truncate block select-all">
                      {getListenerUrl(editingConfig.slug)}
                    </span>
                  </div>
                  <button
                    type="button"
                    onClick={() => copyToClipboard(getListenerUrl(editingConfig.slug!))}
                    className="px-2.5 py-1 rounded bg-indigo-600 hover:bg-indigo-500 text-white text-xs font-medium flex items-center gap-1.5 shrink-0 transition-colors"
                  >
                    <Copy className="w-3.5 h-3.5" />
                    <span>Copy URL</span>
                  </button>
                </div>
              )}

              <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                <div>
                  <label className="block text-xs font-semibold text-text-main mb-1.5">
                    Group / Category
                  </label>
                  <input
                    type="text"
                    list="webhook-groups"
                    placeholder="General"
                    value={editingConfig.groupName || 'General'}
                    onChange={e => setEditingConfig(prev => ({ ...prev, groupName: e.target.value }))}
                    className="w-full px-3 py-2 text-xs rounded-lg bg-bg-main border border-border-main text-text-main focus:outline-none focus:border-indigo-500"
                  />
                  <datalist id="webhook-groups">
                    {groups.map(g => (
                      <option key={g} value={g} />
                    ))}
                  </datalist>
                </div>

                <div className="flex items-center gap-3 pt-6">
                  <label className="relative inline-flex items-center cursor-pointer">
                    <input
                      type="checkbox"
                      checked={editingConfig.active ?? true}
                      onChange={e => setEditingConfig(prev => ({ ...prev, active: e.target.checked }))}
                      className="sr-only peer"
                    />
                    <div className="w-9 h-5 bg-bg-hover peer-focus:outline-none rounded-full peer peer-checked:after:translate-x-full peer-checked:after:border-white after:content-[''] after:absolute after:top-[2px] after:left-[2px] after:bg-white after:border-gray-300 after:border after:rounded-full after:h-4 after:w-4 after:transition-all peer-checked:bg-indigo-600"></div>
                  </label>
                  <div>
                    <span className="text-xs font-semibold text-text-main block">Enable Webhook Listener</span>
                    <span className="text-[11px] text-text-muted">
                      When disabled, incoming requests will be rejected with HTTP 403.
                    </span>
                  </div>
                </div>
              </div>

              <div>
                <label className="block text-xs font-semibold text-text-main mb-1.5">Description</label>
                <textarea
                  rows={2}
                  placeholder="Optional notes or documentation for this webhook integration..."
                  value={editingConfig.description || ''}
                  onChange={e => setEditingConfig(prev => ({ ...prev, description: e.target.value }))}
                  className="w-full px-3 py-2 text-xs rounded-lg bg-bg-main border border-border-main text-text-main focus:outline-none focus:border-indigo-500"
                />
              </div>
            </div>

            {/* Card 2: Security & Header Verification */}
            <div className="bg-bg-panel border border-border-main rounded-xl p-5 shadow-sm space-y-4">
              <div className="flex items-center gap-2 pb-3 border-b border-border-main">
                <div className="w-8 h-8 rounded-lg bg-amber-500/15 text-amber-400 flex items-center justify-center font-bold text-xs">
                  2
                </div>
                <div>
                  <h3 className="text-sm font-bold text-text-main">Security & Verification (Optional)</h3>
                  <p className="text-xs text-text-muted">
                    Authenticate incoming webhook pushes using signature headers (e.g. Ginee token) and IP allowlists.
                  </p>
                </div>
              </div>

              <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                <div>
                  <label className="block text-xs font-semibold text-text-main mb-1.5">
                    Secret Header Name
                  </label>
                  <input
                    type="text"
                    placeholder="e.g. X-Ginee-Signature or Authorization"
                    value={editingConfig.secretHeaderName || ''}
                    onChange={e => setEditingConfig(prev => ({ ...prev, secretHeaderName: e.target.value }))}
                    className="w-full px-3 py-2 text-xs rounded-lg bg-bg-main border border-border-main text-text-main font-mono focus:outline-none focus:border-indigo-500"
                  />
                  <p className="text-[10px] text-text-muted mt-1">
                    Incoming request must include this header name.
                  </p>
                </div>

                <div>
                  <div className="flex items-center justify-between mb-1.5">
                    <label className="text-xs font-semibold text-text-main">
                      Secret Token / Value
                    </label>
                    <button
                      type="button"
                      onClick={handleGenerateSecret}
                      className="px-2 py-0.5 text-[11px] font-medium rounded bg-indigo-500/15 text-indigo-400 hover:bg-indigo-500/25 border border-indigo-500/30 flex items-center gap-1 transition-colors"
                      title="Generate random secure secret key"
                    >
                      <Key className="w-3 h-3" />
                      <span>Generate Secret</span>
                    </button>
                  </div>
                  <div className="relative">
                    <input
                      type={showSecretValue ? 'text' : 'password'}
                      placeholder="Expected secret value or signature token (auto-generate or fill manually)"
                      value={editingConfig.secretHeaderValue || ''}
                      onChange={e => setEditingConfig(prev => ({ ...prev, secretHeaderValue: e.target.value }))}
                      className="w-full px-3 py-2 pr-16 text-xs rounded-lg bg-bg-main border border-border-main text-text-main font-mono focus:outline-none focus:border-indigo-500"
                    />
                    <div className="absolute right-2 top-1/2 -translate-y-1/2 flex items-center gap-1">
                      {editingConfig.secretHeaderValue && (
                        <button
                          type="button"
                          onClick={() => copyToClipboard(editingConfig.secretHeaderValue!)}
                          className="p-1 text-text-muted hover:text-text-main"
                          title="Copy Secret Token"
                        >
                          <Copy className="w-3.5 h-3.5" />
                        </button>
                      )}
                      <button
                        type="button"
                        onClick={() => setShowSecretValue(!showSecretValue)}
                        className="p-1 text-text-muted hover:text-text-main"
                        title={showSecretValue ? 'Hide' : 'Show'}
                      >
                        {showSecretValue ? <EyeOff className="w-3.5 h-3.5" /> : <Eye className="w-3.5 h-3.5" />}
                      </button>
                    </div>
                  </div>
                  <p className="text-[10px] text-text-muted mt-1">
                    Bisa di-generate otomatis atau diisi manual sesuai token/signature dari Ginee.
                  </p>
                </div>
              </div>

              <div>
                <label className="block text-xs font-semibold text-text-main mb-1.5">
                  IP Allowlist (Comma-separated)
                </label>
                <input
                  type="text"
                  placeholder="e.g. 103.111.222.33, 192.168.1.50 (Leave blank to permit any IP)"
                  value={editingConfig.ipAllowlist || ''}
                  onChange={e => setEditingConfig(prev => ({ ...prev, ipAllowlist: e.target.value }))}
                  className="w-full px-3 py-2 text-xs rounded-lg bg-bg-main border border-border-main text-text-main font-mono focus:outline-none focus:border-indigo-500"
                />
                <p className="text-[10px] text-text-muted mt-1">
                  Requests from IPs not in this list will be rejected with HTTP 403 Forbidden.
                </p>
              </div>
            </div>

            {/* Card 3: Strict Target Schema Storage Requirement */}
            <div className="bg-bg-panel border border-border-main rounded-xl p-5 shadow-sm space-y-4">
              <div className="flex items-center gap-2 pb-3 border-b border-border-main">
                <div className="w-8 h-8 rounded-lg bg-blue-500/15 text-blue-400 flex items-center justify-center font-bold text-xs">
                  3
                </div>
                <div>
                  <h3 className="text-sm font-bold text-text-main">Target Database Storage</h3>
                  <p className="text-xs text-text-muted">
                    Save the received webhook payload directly into ClickHouse or PostgreSQL.
                  </p>
                </div>
              </div>

              {/* STRICT SCHEMA REQUIREMENT NOTICE */}
              <div className="bg-gradient-to-r from-amber-500/10 to-orange-500/10 border border-amber-500/30 rounded-xl p-4 space-y-2">
                <div className="flex items-center gap-2 text-amber-500 font-bold text-xs">
                  <AlertTriangle className="w-4 h-4 text-amber-500 shrink-0" />
                  <span>Strict Target Schema Requirement (Manual DDL)</span>
                </div>
                <p className="text-xs text-text-main leading-relaxed">
                  Tabel target wajib dibuat secara manual terlebih dahulu di <b>ClickHouse</b> atau <b>PostgreSQL</b> dengan <b>5 kolom standar</b> berikut. Jika tabel tidak ada atau kolom tidak sesuai standar, ingest data akan gagal dan alert kegagalan otomatis dikirimkan ke Telegram & Discord:
                </p>
                <div className="grid grid-cols-1 sm:grid-cols-5 gap-2 pt-1 font-mono text-[11px]">
                  <div className="bg-bg-main/80 p-2 rounded border border-border-main">
                    <b className="text-indigo-400 block">1. seq</b>
                    <span className="text-text-muted text-[10px]">Primary Key / Seq</span>
                  </div>
                  <div className="bg-bg-main/80 p-2 rounded border border-border-main">
                    <b className="text-indigo-400 block">2. kode_data</b>
                    <span className="text-text-muted text-[10px]">Custom Identifier</span>
                  </div>
                  <div className="bg-bg-main/80 p-2 rounded border border-border-main">
                    <b className="text-indigo-400 block">3. detail_data</b>
                    <span className="text-text-muted text-[10px]">Raw Payload JSON</span>
                  </div>
                  <div className="bg-bg-main/80 p-2 rounded border border-border-main">
                    <b className="text-indigo-400 block">4. input_by</b>
                    <span className="text-text-muted text-[10px]">'darkosync'</span>
                  </div>
                  <div className="bg-bg-main/80 p-2 rounded border border-border-main">
                    <b className="text-indigo-400 block">5. input_dt</b>
                    <span className="text-text-muted text-[10px]">Timestamp (now)</span>
                  </div>
                </div>

                {/* DDL Quick Copy Buttons */}
                <div className="pt-2 flex flex-wrap items-center gap-2">
                  <button
                    type="button"
                    onClick={() => {
                      const tbl = editingConfig.targetTable?.trim() || 'webhook_ginee_orders';
                      const ddl = `-- ClickHouse DDL\nCREATE TABLE IF NOT EXISTS ${tbl} (\n    seq UInt64,\n    kode_data String,\n    detail_data String,\n    input_by String DEFAULT 'darkosync',\n    input_dt DateTime DEFAULT now()\n) ENGINE = ReplacingMergeTree(input_dt)\nORDER BY seq;`;
                      copyToClipboard(ddl);
                    }}
                    className="px-2.5 py-1 text-xs rounded bg-bg-main hover:bg-bg-hover text-text-main border border-border-main flex items-center gap-1.5 transition-colors"
                  >
                    <Copy className="w-3 h-3 text-amber-400" />
                    <span>Copy ClickHouse DDL</span>
                  </button>

                  <button
                    type="button"
                    onClick={() => {
                      const tbl = editingConfig.targetTable?.trim() || 'webhook_ginee_orders';
                      const ddl = `-- PostgreSQL DDL\nCREATE TABLE IF NOT EXISTS ${tbl} (\n    seq BIGSERIAL PRIMARY KEY,\n    kode_data VARCHAR(255) NOT NULL,\n    detail_data JSONB NOT NULL,\n    input_by VARCHAR(100) DEFAULT 'darkosync',\n    input_dt TIMESTAMP DEFAULT CURRENT_TIMESTAMP\n);`;
                      copyToClipboard(ddl);
                    }}
                    className="px-2.5 py-1 text-xs rounded bg-bg-main hover:bg-bg-hover text-text-main border border-border-main flex items-center gap-1.5 transition-colors"
                  >
                    <Copy className="w-3 h-3 text-blue-400" />
                    <span>Copy PostgreSQL DDL</span>
                  </button>
                </div>
              </div>

              <div className="grid grid-cols-1 md:grid-cols-3 gap-4 pt-2">
                <div>
                  <label className="block text-xs font-semibold text-text-main mb-1.5">
                    Target Connection <span className="text-rose-400">*</span>
                  </label>
                  <select
                    value={editingConfig.targetConnectionId || ''}
                    onChange={e => setEditingConfig(prev => ({ ...prev, targetConnectionId: e.target.value }))}
                    className="w-full px-3 py-2 text-xs rounded-lg bg-bg-main border border-border-main text-text-main focus:outline-none focus:border-indigo-500"
                  >
                    <option value="">-- Select Connection --</option>
                    {connections.map(c => (
                      <option key={c.id} value={c.id}>
                        {c.name} ({c.type.toUpperCase()})
                      </option>
                    ))}
                  </select>
                </div>

                <div>
                  <label className="block text-xs font-semibold text-text-main mb-1.5">
                    Target Table Name <span className="text-rose-400">*</span>
                  </label>
                  <input
                    type="text"
                    placeholder="e.g. raw_ginee_events"
                    value={editingConfig.targetTable || ''}
                    onChange={e => setEditingConfig(prev => ({ ...prev, targetTable: e.target.value }))}
                    className="w-full px-3 py-2 text-xs rounded-lg bg-bg-main border border-border-main text-text-main font-mono focus:outline-none focus:border-indigo-500"
                  />
                </div>

                <div>
                  <label className="block text-xs font-semibold text-text-main mb-1.5">
                    Identifier Code (kode_data)
                  </label>
                  <input
                    type="text"
                    placeholder="e.g. GINEE_ORDER"
                    value={editingConfig.kodeData || ''}
                    onChange={e => setEditingConfig(prev => ({ ...prev, kodeData: e.target.value }))}
                    className="w-full px-3 py-2 text-xs rounded-lg bg-bg-main border border-border-main text-text-main font-mono focus:outline-none focus:border-indigo-500"
                  />
                </div>
              </div>
            </div>

            {/* Card 4: Trigger Webhooks (Execute API Schedulers on Inbound Webhook) */}
            <div className="bg-bg-panel border border-border-main rounded-xl p-5 shadow-sm space-y-5">
              <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3 pb-3 border-b border-border-main">
                <div className="flex items-center gap-2.5">
                  <div className="w-8 h-8 rounded-lg bg-emerald-500/15 text-emerald-400 flex items-center justify-center font-bold text-xs">
                    4
                  </div>
                  <div>
                    <div className="flex items-center gap-2">
                      <h3 className="text-sm font-bold text-text-main">Trigger Webhooks</h3>
                      <span className="px-2 py-0.5 rounded text-[10px] font-semibold bg-emerald-500/10 text-emerald-400 border border-emerald-500/20 flex items-center gap-1">
                        <Zap className="w-3 h-3 text-emerald-400" />
                        API Scheduler Engine
                      </span>
                    </div>
                    <p className="text-xs text-text-muted">
                      Trigger satu atau beberapa REST API Scheduler secara dinamis menggunakan parameter dari payload webhook yang masuk.
                    </p>
                  </div>
                </div>

                {/* Toggle Switch */}
                <label className="relative inline-flex items-center cursor-pointer select-none">
                  <input
                    type="checkbox"
                    checked={editingConfig.enableEnrichment || false}
                    onChange={e => setEditingConfig(prev => ({ ...prev, enableEnrichment: e.target.checked }))}
                    className="sr-only peer"
                  />
                  <div className="w-11 h-6 bg-bg-hover peer-focus:outline-none rounded-full peer peer-checked:after:translate-x-full peer-checked:after:border-white after:content-[''] after:absolute after:top-[2px] after:left-[2px] after:bg-white after:border-border-main after:border after:rounded-full after:h-5 after:w-5 after:transition-all peer-checked:bg-emerald-600"></div>
                  <span className="ml-2 text-xs font-semibold text-text-main">
                    {editingConfig.enableEnrichment ? 'Trigger Active' : 'Disabled'}
                  </span>
                </label>
              </div>

              {editingConfig.enableEnrichment ? (
                <div className="space-y-5 pt-1">
                  {/* Informative Banner */}
                  <div className="bg-emerald-500/10 border border-emerald-500/20 rounded-xl p-4 space-y-2">
                    <div className="flex items-center gap-2 text-emerald-400 font-bold text-xs">
                      <Sparkles className="w-4 h-4 text-emerald-400 shrink-0" />
                      <span>Flexible Dynamic Trigger Architecture</span>
                    </div>
                    <p className="text-xs text-text-main leading-relaxed">
                      Ketika payload webhook masuk dan cocok dengan kondisi filter (misal: <b>orderStatus == READY_TO_SHIP</b>), DarkoSync akan mengekstrak Primary Key (misal: <b>orderId</b>), menyuntikkannya ke placeholder (<code>{'{{orderId}}'}</code> / <code>{'{{pk}}'}</code>), lalu mengeksekusi <b>API Scheduler</b> yang dicentang secara background async dan menyimpan responsnya ke target database masing-masing.
                    </p>
                  </div>

                  {/* Section 1: Trigger Filter Rules (Multiple Key = Value) */}
                  <div className="bg-bg-main p-4 border border-border-main rounded-xl space-y-3">
                    <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-2 pb-2 border-b border-border-main">
                      <div className="flex items-center gap-2 text-xs font-bold text-text-main">
                        <Filter className="w-4 h-4 text-indigo-400" />
                        <span>1. Filter Kondisi Webhook (Key = Value)</span>
                      </div>
                      <button
                        type="button"
                        onClick={() => setFilterRules(prev => [...prev, { key: '', value: '' }])}
                        className="px-2.5 py-1 text-xs rounded bg-indigo-500/10 hover:bg-indigo-500/20 text-indigo-400 border border-indigo-500/30 flex items-center gap-1.5 transition-colors self-start sm:self-auto font-medium"
                      >
                        <Plus className="w-3.5 h-3.5" />
                        <span>Tambah Filter Rule</span>
                      </button>
                    </div>

                    <p className="text-[11px] text-text-muted">
                      DarkoSync akan mengevaluasi setiap item/payload webhook. Hanya item yang memenuhi <b>SEMUA</b> kondisi filter (Key = Value) di bawah ini yang akan men-trigger eksekusi API Scheduler.
                    </p>

                    <div className="space-y-2.5">
                      {filterRules.map((rule, idx) => (
                        <div key={idx} className="flex flex-col sm:flex-row sm:items-center gap-2.5 p-3 rounded-lg bg-bg-panel border border-border-main">
                          <div className="flex-1">
                            <label className="block text-[10px] font-semibold text-text-muted mb-1">
                              JSON Key di Webhook <span className="text-rose-400">*</span>
                            </label>
                            <input
                              type="text"
                              placeholder="e.g. orderStatus, channel, action"
                              value={rule.key}
                              onChange={e => {
                                const next = [...filterRules];
                                next[idx].key = e.target.value;
                                setFilterRules(next);
                              }}
                              className="w-full px-3 py-1.5 text-xs rounded-lg bg-bg-main border border-border-main text-text-main font-mono focus:outline-none focus:border-indigo-500"
                            />
                          </div>

                          <div className="hidden sm:flex items-center justify-center pt-4 text-text-muted font-bold text-xs">
                            =
                          </div>

                          <div className="flex-1">
                            <label className="block text-[10px] font-semibold text-text-muted mb-1">
                              Expected Value <span className="text-rose-400">*</span>
                            </label>
                            <input
                              type="text"
                              placeholder="e.g. READY_TO_SHIP, SHOPEE, * (semua)"
                              value={rule.value}
                              onChange={e => {
                                const next = [...filterRules];
                                next[idx].value = e.target.value;
                                setFilterRules(next);
                              }}
                              className="w-full px-3 py-1.5 text-xs rounded-lg bg-bg-main border border-border-main text-text-main font-mono focus:outline-none focus:border-indigo-500"
                            />
                          </div>

                          <div className="flex sm:flex-col justify-end pt-1 sm:pt-4">
                            <button
                              type="button"
                              onClick={() => {
                                if (filterRules.length <= 1) {
                                  setFilterRules([{ key: '', value: '' }]);
                                } else {
                                  setFilterRules(filterRules.filter((_, i) => i !== idx));
                                }
                              }}
                              className="p-1.5 rounded text-text-muted hover:text-rose-400 hover:bg-rose-500/10 transition-colors"
                              title="Hapus Filter Rule"
                            >
                              <Trash2 className="w-3.5 h-3.5" />
                            </button>
                          </div>
                        </div>
                      ))}
                    </div>
                  </div>

                  {/* Section B: Multiple API Scheduler Selection */}
                  <div className="bg-bg-main p-4 border border-border-main rounded-xl space-y-4">
                    <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-2 pb-2 border-b border-border-main">
                      <div className="flex items-center gap-2 text-xs font-bold text-text-main">
                        <Database className="w-4 h-4 text-emerald-400" />
                        <span>2. Pilih Target API Scheduler (Bisa Multiple / Beberapa Sekaligus)</span>
                      </div>

                      {/* Search Bar for Schedulers */}
                      <div className="relative w-full sm:w-64">
                        <Search className="w-3.5 h-3.5 absolute left-2.5 top-1/2 -translate-y-1/2 text-text-muted" />
                        <input
                          type="text"
                          placeholder="Cari API Scheduler..."
                          value={triggerSchedulerSearch}
                          onChange={e => setTriggerSchedulerSearch(e.target.value)}
                          className="w-full pl-8 pr-3 py-1.5 text-xs bg-bg-panel border border-border-main rounded-lg text-text-main focus:outline-none focus:border-emerald-500"
                        />
                      </div>
                    </div>

                    {/* Checkbox Multi-Select List of API Schedulers */}
                    {(() => {
                      const selectedIds = (editingConfig.triggerApiSchedulerId || '')
                        .split(/[,;\s]+/)
                        .map(s => s.trim())
                        .filter(Boolean);

                      const filteredSchedulers = apiSchedulers.filter(s => {
                        if (!triggerSchedulerSearch.trim()) return true;
                        const q = triggerSchedulerSearch.toLowerCase();
                        return (
                          (s.name && s.name.toLowerCase().includes(q)) ||
                          (s.url && s.url.toLowerCase().includes(q)) ||
                          (s.targetTable && s.targetTable.toLowerCase().includes(q)) ||
                          (s.method && s.method.toLowerCase().includes(q))
                        );
                      });

                      const toggleSchedulerId = (id: string) => {
                        const raw = editingConfig.triggerApiSchedulerId || '';
                        const currentList = raw.split(/[,;\s]+/).map(s => s.trim()).filter(Boolean);
                        let nextList: string[];
                        if (currentList.includes(id)) {
                          nextList = currentList.filter(x => x !== id);
                        } else {
                          nextList = [...currentList, id];
                        }
                        const newTriggerId = nextList.join(',');
                        
                        // Auto-populate target storage preview from first selected scheduler
                        const firstSelected = apiSchedulers.find(s => s.id === (nextList[0] || ''));
                        setEditingConfig(prev => ({
                          ...prev,
                          triggerApiSchedulerId: newTriggerId,
                          enrichmentTargetConnectionId: firstSelected ? firstSelected.targetConnectionId : prev.enrichmentTargetConnectionId,
                          enrichmentTargetTable: firstSelected ? firstSelected.targetTable : prev.enrichmentTargetTable,
                          enrichmentKodeData: firstSelected ? firstSelected.kodeData : prev.enrichmentKodeData,
                        }));
                      };

                      return (
                        <div className="space-y-3">
                          <div className="max-h-56 overflow-y-auto pr-1 space-y-2 border border-border-main/60 rounded-xl p-2 bg-bg-panel/50">
                            {filteredSchedulers.length === 0 ? (
                              <div className="p-4 text-center text-xs text-text-muted">
                                Tidak ada API Scheduler yang ditemukan. Silakan buat endpoint API baru di menu API Builder / Scheduler.
                              </div>
                            ) : (
                              filteredSchedulers.map(sched => {
                                const isChecked = selectedIds.includes(sched.id);
                                return (
                                  <div
                                    key={sched.id}
                                    onClick={() => toggleSchedulerId(sched.id)}
                                    className={clsx(
                                      "flex items-center justify-between p-2.5 rounded-lg border cursor-pointer transition-all",
                                      isChecked
                                        ? "bg-emerald-500/10 border-emerald-500/30 text-text-main shadow-sm"
                                        : "bg-bg-panel border-border-main text-text-muted hover:text-text-main hover:bg-bg-hover"
                                    )}
                                  >
                                    <div className="flex items-center gap-3 min-w-0">
                                      <div className="shrink-0 text-emerald-400">
                                        {isChecked ? (
                                          <CheckSquare className="w-4 h-4 text-emerald-500" />
                                        ) : (
                                          <Square className="w-4 h-4 text-text-muted" />
                                        )}
                                      </div>
                                      <div className="min-w-0">
                                        <div className="flex items-center gap-2">
                                          <span className={clsx(
                                            "px-1.5 py-0.5 text-[10px] font-bold rounded",
                                            sched.method === 'POST' ? 'bg-blue-500/20 text-blue-400' :
                                            sched.method === 'GET' ? 'bg-emerald-500/20 text-emerald-400' :
                                            'bg-purple-500/20 text-purple-400'
                                          )}>
                                            {sched.method}
                                          </span>
                                          <span className="text-xs font-semibold text-text-main truncate">
                                            {sched.name}
                                          </span>
                                          {sched.cronExpression ? (
                                            <span className="text-[10px] px-1.5 py-0.5 rounded bg-amber-500/10 text-amber-400 border border-amber-500/20">
                                              Cron + Webhook
                                            </span>
                                          ) : (
                                            <span className="text-[10px] px-1.5 py-0.5 rounded bg-emerald-500/10 text-emerald-400 border border-emerald-500/20">
                                              Trigger Standby Only
                                            </span>
                                          )}
                                        </div>
                                        <div className="text-[11px] text-text-muted truncate mt-0.5 font-mono">
                                          {sched.url} &rarr; <span className="text-text-main font-bold">{sched.targetTable || 'No table'}</span>
                                        </div>
                                      </div>
                                    </div>

                                    <div className="text-[10px] text-text-muted shrink-0 text-right">
                                      <span className="font-mono bg-bg-main px-2 py-0.5 rounded border border-border-main">
                                        {sched.targetTable || '-'}
                                      </span>
                                    </div>
                                  </div>
                                );
                              })
                            )}
                          </div>

                          {/* Cards for currently selected schedulers */}
                          {selectedIds.length > 0 && (
                            <div className="space-y-3 pt-2">
                              <div className="flex items-center justify-between text-xs font-bold text-text-main">
                                <span>Detail API Scheduler Terpilih ({selectedIds.length} Aktif)</span>
                                <span className="text-[11px] text-emerald-400 font-normal">
                                  Semua endpoint ini akan otomatis dijalankan saat webhook trigger cocok.
                                </span>
                              </div>

                              <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
                                {selectedIds.map(id => {
                                  const sched = apiSchedulers.find(s => s.id === id);
                                  if (!sched) return null;
                                  return (
                                    <div key={id} className="p-3.5 rounded-xl bg-bg-panel border border-border-main space-y-2.5">
                                      <div className="flex items-center justify-between">
                                        <div className="flex items-center gap-2">
                                          <span className="px-1.5 py-0.5 text-[10px] font-bold rounded bg-blue-500/20 text-blue-400">
                                            {sched.method}
                                          </span>
                                          <span className="text-xs font-bold text-text-main truncate">
                                            {sched.name}
                                          </span>
                                        </div>
                                        <button
                                          type="button"
                                          onClick={() => toggleSchedulerId(id)}
                                          className="text-text-muted hover:text-rose-400 text-xs transition-colors"
                                          title="Remove from trigger"
                                        >
                                          &times;
                                        </button>
                                      </div>

                                      <div className="text-[11px] font-mono bg-bg-main p-2 rounded-lg border border-border-main text-text-muted truncate">
                                        {sched.url}
                                      </div>

                                      <div className="grid grid-cols-2 gap-2 text-[11px]">
                                        <div className="bg-bg-main p-2 rounded-lg border border-border-main">
                                          <span className="text-text-muted block text-[10px]">Target Storage:</span>
                                          <span className="font-bold text-text-main font-mono truncate block">
                                            {sched.targetTable || '-'}
                                          </span>
                                        </div>
                                        <div className="bg-bg-main p-2 rounded-lg border border-border-main">
                                          <span className="text-text-muted block text-[10px]">Identifier:</span>
                                          <span className="font-bold text-emerald-400 font-mono truncate block">
                                            {sched.kodeData || 'GINEE_READY_TO_SHIP'}
                                          </span>
                                        </div>
                                      </div>

                                      {/* DDL Quick Copy for this table */}
                                      <div className="flex items-center gap-2 pt-1">
                                        <button
                                          type="button"
                                          onClick={() => {
                                            const tbl = sched.targetTable?.trim() || 'dw_erp.ginee_test3';
                                            const ddl = `-- ClickHouse DDL (Detail Table)\nCREATE TABLE IF NOT EXISTS ${tbl} (\n    seq UInt64,\n    kode_data String,\n    detail_data String,\n    input_by String DEFAULT 'darkosync',\n    input_dt DateTime DEFAULT now()\n) ENGINE = ReplacingMergeTree(input_dt)\nORDER BY seq;`;
                                            copyToClipboard(ddl);
                                          }}
                                          className="px-2 py-1 text-[10px] rounded bg-bg-main hover:bg-bg-hover text-text-main border border-border-main flex items-center gap-1 transition-colors"
                                        >
                                          <Copy className="w-3 h-3 text-amber-400" />
                                          <span>Copy ClickHouse DDL</span>
                                        </button>

                                        <button
                                          type="button"
                                          onClick={() => {
                                            const tbl = sched.targetTable?.trim() || 'ginee_orders_detail';
                                            const ddl = `-- PostgreSQL DDL (Detail Table)\nCREATE TABLE IF NOT EXISTS ${tbl} (\n    seq BIGSERIAL PRIMARY KEY,\n    kode_data VARCHAR(255) NOT NULL,\n    detail_data JSONB NOT NULL,\n    input_by VARCHAR(100) DEFAULT 'darkosync',\n    input_dt TIMESTAMP DEFAULT CURRENT_TIMESTAMP\n);`;
                                            copyToClipboard(ddl);
                                          }}
                                          className="px-2 py-1 text-[10px] rounded bg-bg-main hover:bg-bg-hover text-text-main border border-border-main flex items-center gap-1 transition-colors"
                                        >
                                          <Copy className="w-3 h-3 text-blue-400" />
                                          <span>Copy PostgreSQL DDL</span>
                                        </button>
                                      </div>
                                    </div>
                                  );
                                })}
                              </div>
                            </div>
                          )}
                        </div>
                      );
                    })()}
                  </div>

                  {/* Section 3: Mapping Parameter & Request Body API Scheduler */}
                  <div className="bg-bg-main p-4 border border-border-main rounded-xl space-y-4">
                    <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-2 pb-2 border-b border-border-main">
                      <div className="flex items-center gap-2 text-xs font-bold text-text-main">
                        <Layers className="w-4 h-4 text-emerald-400" />
                        <span>3. Mapping Parameter & Request Body API Scheduler</span>
                      </div>

                      <div className="flex flex-wrap items-center gap-2">
                        {detectedSchedulerParams.length > 0 && (
                          <button
                            type="button"
                            onClick={handleSyncDetectedParams}
                            className="px-2.5 py-1 text-xs rounded bg-emerald-500/10 hover:bg-emerald-500/20 text-emerald-400 border border-emerald-500/30 flex items-center gap-1.5 transition-colors font-medium"
                            title="Deteksi ulang parameter dari API Scheduler terpilih"
                          >
                            <RefreshCw className="w-3.5 h-3.5" />
                            <span>Sinkronkan Parameter ({detectedSchedulerParams.length})</span>
                          </button>
                        )}

                        <button
                          type="button"
                          onClick={() => setParamMappings(prev => [...prev, { targetParam: '', sourceJsonPath: '', sourceType: 'custom' }])}
                          className="px-2.5 py-1 text-xs rounded bg-bg-panel hover:bg-bg-hover text-text-main border border-border-main flex items-center gap-1.5 transition-colors font-medium"
                        >
                          <Plus className="w-3.5 h-3.5 text-emerald-400" />
                          <span>Tambah Mapping Parameter</span>
                        </button>
                      </div>
                    </div>

                    <p className="text-[11px] text-text-muted">
                      Tentukan sumber data dari JSON payload webhook untuk setiap parameter atau body key yang dibutuhkan oleh API Scheduler.
                    </p>

                    {paramMappings.length === 0 ? (
                      <div className="p-4 text-center text-xs text-text-muted border border-dashed border-border-main rounded-lg">
                        Belum ada mapping parameter. Klik <b>+ Tambah Mapping Parameter</b> atau <b>Sinkronkan Parameter</b>.
                      </div>
                    ) : (
                      <div className="space-y-2.5">
                        {paramMappings.map((pm, idx) => (
                          <div key={idx} className="flex flex-col sm:flex-row sm:items-center gap-3 p-3 rounded-lg bg-bg-panel border border-border-main">
                            {/* Target Param in API Scheduler */}
                            <div className="flex-1">
                              <div className="flex items-center justify-between mb-1">
                                <label className="text-[10px] font-semibold text-text-muted">
                                  Parameter / Body Key di API Scheduler <span className="text-rose-400">*</span>
                                </label>
                                {pm.sourceType && (
                                  <span className={clsx(
                                    "px-1.5 py-0.2 text-[9px] font-bold rounded",
                                    pm.sourceType === 'placeholder' ? 'bg-amber-500/20 text-amber-400' :
                                    pm.sourceType === 'body' ? 'bg-purple-500/20 text-purple-400' :
                                    pm.sourceType === 'param' ? 'bg-blue-500/20 text-blue-400' :
                                    'bg-emerald-500/20 text-emerald-400'
                                  )}>
                                    {pm.sourceType === 'placeholder' ? 'Tag {{...}}' :
                                     pm.sourceType === 'body' ? 'JSON Body' :
                                     pm.sourceType === 'param' ? 'Query Param' : 'Custom'}
                                  </span>
                                )}
                              </div>
                              <input
                                type="text"
                                placeholder="e.g. orderId, orderIds, shopId"
                                value={pm.targetParam}
                                onChange={e => {
                                  const next = [...paramMappings];
                                  next[idx].targetParam = e.target.value;
                                  setParamMappings(next);
                                }}
                                className="w-full px-3 py-1.5 text-xs rounded-lg bg-bg-main border border-border-main text-text-main font-mono focus:outline-none focus:border-emerald-500"
                              />
                            </div>

                            <div className="hidden sm:flex items-center justify-center pt-4 text-emerald-400">
                              <ArrowLeft className="w-4 h-4 text-text-muted" />
                            </div>

                            {/* Source JSON Key/Path in incoming Webhook Payload */}
                            <div className="flex-1">
                              <div className="flex items-center justify-between mb-1">
                                <label className="text-[10px] font-semibold text-text-muted">
                                  Ambil dari Webhook JSON Key / Path <span className="text-rose-400">*</span>
                                </label>
                                <span className="text-[9px] text-text-muted font-mono">Dot notation OK</span>
                              </div>
                              <input
                                type="text"
                                placeholder="e.g. orderId atau order_id atau data.order_sn"
                                value={pm.sourceJsonPath}
                                onChange={e => {
                                  const next = [...paramMappings];
                                  next[idx].sourceJsonPath = e.target.value;
                                  setParamMappings(next);
                                }}
                                className="w-full px-3 py-1.5 text-xs rounded-lg bg-bg-main border border-border-main text-text-main font-mono focus:outline-none focus:border-emerald-500"
                              />
                            </div>

                            {/* Action Delete */}
                            <div className="flex sm:flex-col justify-end pt-1 sm:pt-4">
                              <button
                                type="button"
                                onClick={() => {
                                  if (paramMappings.length <= 1) {
                                    setParamMappings([{ targetParam: '', sourceJsonPath: '', sourceType: 'custom' }]);
                                  } else {
                                    setParamMappings(paramMappings.filter((_, i) => i !== idx));
                                  }
                                }}
                                className="p-1.5 rounded text-text-muted hover:text-rose-400 hover:bg-rose-500/10 transition-colors"
                                title="Hapus Mapping Parameter"
                              >
                                <Trash2 className="w-3.5 h-3.5" />
                              </button>
                            </div>
                          </div>
                        ))}
                      </div>
                    )}

                    {/* Informative tips */}
                    <div className="p-3 bg-bg-panel/70 rounded-lg border border-border-main/60 flex items-start gap-2.5">
                      <Sparkles className="w-4 h-4 text-emerald-400 shrink-0 mt-0.5" />
                      <div className="text-[11px] text-text-muted leading-relaxed">
                        <span className="font-semibold text-text-main">Mendukung Format JSON Berbeda: </span>
                        Format JSON dari tiap marketplace / platform webhook bisa berbeda (misal: <code>orderId</code> di Ginee, <code>data.order_id</code> di TikTok, <code>ordersn</code> di Shopee). Tentukan path JSON di kolom kanan. DarkoSync mendukung pencarian langsung (e.g. <code>orderId</code>), dot notation bertingkat (e.g. <code>data.order.id</code>), dan array indexing (e.g. <code>orders[0].id</code>).
                      </div>
                    </div>
                  </div>

                  {/* Optional Fallback Ginee Direct Inputs for Backward Compatibility */}
                  <div className="pt-2 border-t border-border-main">
                    <details className="group text-xs text-text-muted">
                      <summary className="cursor-pointer font-semibold text-text-main hover:text-emerald-400 transition-colors flex items-center gap-2">
                        <Key className="w-3.5 h-3.5 text-amber-400" />
                        <span>Opsi Lanjutan: Override Kredensial Ginee API Langsung (Optional)</span>
                      </summary>
                      <div className="pt-3 space-y-3">
                        <p className="text-[11px] text-text-muted">
                          Jika tidak menggunakan API Scheduler, sistem dapat menggunakan kredensial Ginee langsung di bawah ini atau otomatis membaca dari <code>.env</code> server.
                        </p>
                        <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                          <div>
                            <label className="block text-[11px] font-medium text-text-muted mb-1">
                              Ginee Access Key
                            </label>
                            <input
                              type="text"
                              placeholder="Leave blank to use server .env"
                              value={editingConfig.enrichmentGineeAccessKey || ''}
                              onChange={e => setEditingConfig(prev => ({ ...prev, enrichmentGineeAccessKey: e.target.value }))}
                              className="w-full px-3 py-2 text-xs rounded-lg bg-bg-panel border border-border-main text-text-main font-mono focus:outline-none focus:border-indigo-500"
                            />
                          </div>

                          <div>
                            <label className="block text-[11px] font-medium text-text-muted mb-1">
                              Ginee Secret Key
                            </label>
                            <input
                              type="password"
                              placeholder="Leave blank to use server .env"
                              value={editingConfig.enrichmentGineeSecretKey || ''}
                              onChange={e => setEditingConfig(prev => ({ ...prev, enrichmentGineeSecretKey: e.target.value }))}
                              className="w-full px-3 py-2 text-xs rounded-lg bg-bg-panel border border-border-main text-text-main font-mono focus:outline-none focus:border-indigo-500"
                            />
                          </div>
                        </div>
                      </div>
                    </details>
                  </div>
                </div>
              ) : (
                <div className="p-4 bg-bg-main/50 rounded-xl border border-dashed border-border-main text-center">
                  <p className="text-xs text-text-muted">
                    Trigger Webhooks nonaktif. Aktifkan toggle di atas untuk mengeksekusi satu atau beberapa API Scheduler secara otomatis ketika event webhook tiba.
                  </p>
                </div>
              )}
            </div>

            {/* Card 5: Failure Alerts & Telegram/Discord Integration */}
            <div className="bg-bg-panel border border-border-main rounded-xl p-5 shadow-sm space-y-4">
              <div className="flex items-center justify-between pb-3 border-b border-border-main">
                <div className="flex items-center gap-2">
                  <div className="w-8 h-8 rounded-lg bg-purple-500/15 text-purple-400 flex items-center justify-center font-bold text-xs">
                    5
                  </div>
                  <div>
                    <h3 className="text-sm font-bold text-text-main">Telegram & Discord Failure Alerts</h3>
                    <p className="text-xs text-text-muted">
                      Receive immediate critical alerts when schema verification or ingestion fails.
                    </p>
                  </div>
                </div>

                <button
                  type="button"
                  onClick={() => setIsChannelModalOpen(true)}
                  className="px-2.5 py-1 text-xs font-medium rounded-lg bg-bg-hover hover:bg-bg-active border border-border-main text-purple-400 flex items-center gap-1.5 transition-colors"
                >
                  <Bell className="w-3 h-3" />
                  <span>Manage Channels</span>
                </button>
              </div>

              <div>
                <label className="block text-xs font-semibold text-text-main mb-2">
                  Select Alert Channels:
                </label>
                {channels.length === 0 ? (
                  <div className="p-4 bg-bg-main rounded-lg border border-dashed border-border-main text-center">
                    <p className="text-xs text-text-muted mb-2">No notification channels registered yet.</p>
                    <button
                      type="button"
                      onClick={() => setIsChannelModalOpen(true)}
                      className="px-3 py-1 text-xs font-medium rounded bg-purple-600 hover:bg-purple-500 text-white"
                    >
                      + Add Telegram / Discord Channel
                    </button>
                  </div>
                ) : (
                  <div className="grid grid-cols-1 sm:grid-cols-2 md:grid-cols-3 gap-2">
                    {channels.map(chan => {
                      const selectedIds = (editingConfig.notificationChannelId || '')
                        .split(',')
                        .map(s => s.trim());
                      const isSelected = selectedIds.includes(chan.id);

                      return (
                        <div
                          key={chan.id}
                          onClick={() => {
                            let updated: string[];
                            if (isSelected) {
                              updated = selectedIds.filter(id => id !== chan.id);
                            } else {
                              updated = [...selectedIds, chan.id].filter(Boolean);
                            }
                            setEditingConfig(prev => ({
                              ...prev,
                              notificationChannelId: updated.join(','),
                            }));
                          }}
                          className={clsx(
                            'p-2.5 rounded-lg border cursor-pointer flex items-center justify-between transition-all select-none',
                            isSelected
                              ? 'bg-purple-500/10 border-purple-500/50 text-text-main'
                              : 'bg-bg-main border-border-main text-text-muted hover:border-purple-500/30'
                          )}
                        >
                          <div className="flex items-center gap-2 truncate">
                            <span
                              className={clsx(
                                'w-2 h-2 rounded-full',
                                chan.type === 'TELEGRAM' ? 'bg-cyan-400' : 'bg-indigo-400'
                              )}
                            />
                            <div className="truncate">
                              <span className="text-xs font-semibold block truncate">{chan.name}</span>
                              <span className="text-[10px] text-text-muted block">{chan.type}</span>
                            </div>
                          </div>
                          <div
                            className={clsx(
                              'w-4 h-4 rounded border flex items-center justify-center shrink-0',
                              isSelected
                                ? 'bg-purple-600 border-purple-600 text-white'
                                : 'border-border-main'
                            )}
                          >
                            {isSelected && <Check className="w-3 h-3" />}
                          </div>
                        </div>
                      );
                    })}
                  </div>
                )}
              </div>
            </div>

            {/* Bottom Actions */}
            <div className="flex items-center justify-end gap-3 pt-4">
              <button
                type="button"
                onClick={() => setViewMode('list')}
                className="px-4 py-2 text-xs font-semibold rounded-lg bg-bg-panel hover:bg-bg-hover text-text-muted hover:text-text-main border border-border-main transition-colors"
              >
                Cancel
              </button>
              <button
                type="button"
                onClick={handleSaveWebhook}
                disabled={isSaving}
                className="px-6 py-2 text-xs font-semibold rounded-lg bg-gradient-to-r from-indigo-600 to-purple-600 hover:from-indigo-500 hover:to-purple-500 text-white shadow-md shadow-indigo-500/20 flex items-center gap-2 transition-all"
              >
                {isSaving ? <RefreshCw className="w-4 h-4 animate-spin" /> : <Check className="w-4 h-4" />}
                <span>{editingConfig.id ? 'Save Webhook Configuration' : 'Deploy Webhook Listener'}</span>
              </button>
            </div>
          </div>
        )}
      </div>

      {/* Activity Logs Modal / Drawer */}
      {isLogsModalOpen && activeLogWebhook && (
        <div className="fixed inset-0 z-50 bg-black/60 backdrop-blur-sm flex items-center justify-center p-4">
          <div className="bg-bg-panel border border-border-main rounded-2xl w-full max-w-5xl max-h-[85vh] flex flex-col shadow-2xl overflow-hidden animate-in fade-in zoom-in-95 duration-150">
            {/* Modal Header */}
            <div className="p-4 border-b border-border-main flex items-center justify-between shrink-0 bg-bg-main/50">
              <div className="flex items-center gap-3">
                <div className="w-8 h-8 rounded-lg bg-indigo-500/15 text-indigo-400 flex items-center justify-center">
                  <Activity className="w-4 h-4" />
                </div>
                <div>
                  <h3 className="text-sm font-bold text-text-main">
                    Activity Logs: {activeLogWebhook.name}
                  </h3>
                  <p className="text-[11px] font-mono text-indigo-400">
                    /catch/{activeLogWebhook.slug}
                  </p>
                </div>
              </div>

              <div className="flex items-center gap-2">
                <button
                  onClick={() => handleOpenLogs(activeLogWebhook)}
                  disabled={loadingLogs}
                  className="p-1.5 rounded-lg hover:bg-bg-hover text-text-muted hover:text-text-main transition-colors"
                  title="Refresh Logs"
                >
                  <RefreshCw className={clsx('w-4 h-4', loadingLogs && 'animate-spin text-indigo-400')} />
                </button>
                <button
                  onClick={handleClearLogs}
                  className="px-2.5 py-1 text-xs font-medium rounded-lg text-rose-400 hover:bg-rose-500/15 border border-border-main transition-colors"
                >
                  Clear Logs
                </button>
                <button
                  onClick={() => setIsLogsModalOpen(false)}
                  className="p-1.5 rounded-lg hover:bg-bg-hover text-text-muted hover:text-text-main transition-colors"
                >
                  <X className="w-4 h-4" />
                </button>
              </div>
            </div>

            {/* Modal Body */}
            <div className="flex-1 overflow-y-auto p-4">
              {loadingLogs ? (
                <div className="py-20 flex flex-col items-center justify-center text-text-muted">
                  <RefreshCw className="w-6 h-6 animate-spin text-indigo-500 mb-2" />
                  <p className="text-xs">Loading activity logs...</p>
                </div>
              ) : logs.length === 0 ? (
                <div className="py-16 text-center text-text-muted">
                  <p className="text-xs">No activity logs recorded yet for this webhook.</p>
                  <p className="text-[11px] text-text-muted/60 mt-1">
                    Send a test POST request to the listener URL to see it live here.
                  </p>
                </div>
              ) : (
                <div className="overflow-x-auto border border-border-main rounded-xl">
                  <table className="w-full text-left text-xs border-collapse">
                    <thead>
                      <tr className="bg-bg-main/60 border-b border-border-main text-text-muted font-semibold">
                        <th className="p-2.5">Timestamp</th>
                        <th className="p-2.5">Source IP</th>
                        <th className="p-2.5">Method</th>
                        <th className="p-2.5">Status</th>
                        <th className="p-2.5 text-center">Duration</th>
                        <th className="p-2.5 text-center">Rows</th>
                        <th className="p-2.5">Message / Error</th>
                        <th className="p-2.5 text-right">Details</th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-border-main">
                      {logs.map(log => {
                        const isSuccess = log.status === 'SUCCESS';
                        return (
                          <tr key={log.id} className="hover:bg-bg-hover/50 transition-colors">
                            <td className="p-2.5 font-mono text-[11px] text-text-muted whitespace-nowrap">
                              {log.receivedAt ? log.receivedAt.replace('T', ' ').substring(0, 19) : '-'}
                            </td>
                            <td className="p-2.5 font-mono text-[11px] text-text-main">
                              {log.sourceIp}
                            </td>
                            <td className="p-2.5 font-bold text-[11px] text-text-muted">
                              {log.httpMethod}
                            </td>
                            <td className="p-2.5">
                              <span
                                className={clsx(
                                  'px-2 py-0.5 rounded text-[10px] font-bold',
                                  isSuccess
                                    ? 'bg-emerald-500/15 text-emerald-400'
                                    : 'bg-rose-500/15 text-rose-400'
                                )}
                              >
                                {log.statusCode} {log.status}
                              </span>
                            </td>
                            <td className="p-2.5 text-center font-mono text-[11px] text-text-muted">
                              {log.durationMs}ms
                            </td>
                            <td className="p-2.5 text-center font-bold text-text-main">
                              {log.rowsInserted}
                            </td>
                            <td className="p-2.5 text-text-muted truncate max-w-xs" title={log.errorMessage}>
                              {log.errorMessage || '-'}
                            </td>
                            <td className="p-2.5 text-right">
                              <button
                                onClick={() => setInspectingLog(log)}
                                className="px-2 py-1 text-[11px] font-medium rounded bg-bg-hover hover:bg-bg-active text-text-main border border-border-main transition-colors"
                              >
                                Inspect
                              </button>
                            </td>
                          </tr>
                        );
                      })}
                    </tbody>
                  </table>
                </div>
              )}
            </div>
          </div>
        </div>
      )}

      {/* Inspect Log Modal */}
      {inspectingLog && (
        <div className="fixed inset-0 z-60 bg-black/70 backdrop-blur-sm flex items-center justify-center p-4">
          <div className="bg-bg-panel border border-border-main rounded-2xl w-full max-w-2xl max-h-[80vh] flex flex-col shadow-2xl overflow-hidden animate-in fade-in zoom-in-95 duration-150">
            <div className="p-4 border-b border-border-main flex items-center justify-between shrink-0 bg-bg-main/50">
              <h3 className="text-sm font-bold text-text-main">Inspect Webhook Request</h3>
              <button
                onClick={() => setInspectingLog(null)}
                className="p-1 rounded hover:bg-bg-hover text-text-muted hover:text-text-main"
              >
                <X className="w-4 h-4" />
              </button>
            </div>

            <div className="flex-1 overflow-y-auto p-4 space-y-4 text-xs">
              {inspectingLog.errorMessage && (
                <div className="p-3 bg-rose-500/10 border border-rose-500/30 rounded-lg text-rose-400">
                  <b className="block mb-1">Error Message:</b>
                  <p className="font-mono text-[11px]">{inspectingLog.errorMessage}</p>
                </div>
              )}

              <div>
                <b className="block text-text-main mb-1">HTTP Headers:</b>
                <pre className="p-3 bg-bg-main rounded-lg border border-border-main font-mono text-[11px] text-text-muted overflow-x-auto max-h-40">
                  {(() => {
                    try {
                      return JSON.stringify(JSON.parse(inspectingLog.headers || '{}'), null, 2);
                    } catch {
                      return inspectingLog.headers || '{}';
                    }
                  })()}
                </pre>
              </div>

              <div>
                <b className="block text-text-main mb-1">Payload Body (JSON):</b>
                <pre className="p-3 bg-bg-main rounded-lg border border-border-main font-mono text-[11px] text-text-muted overflow-x-auto max-h-64 select-all">
                  {(() => {
                    try {
                      return JSON.stringify(JSON.parse(inspectingLog.payload || '{}'), null, 2);
                    } catch {
                      return inspectingLog.payload || '{}';
                    }
                  })()}
                </pre>
              </div>
            </div>

            <div className="p-3 border-t border-border-main flex justify-end shrink-0 bg-bg-main/30">
              <button
                onClick={() => setInspectingLog(null)}
                className="px-4 py-1.5 text-xs font-medium rounded-lg bg-bg-hover text-text-main hover:bg-bg-active border border-border-main"
              >
                Close
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Shared Notification Channels Modal */}
      {isChannelModalOpen && (
        <NotificationChannelsModal
          onClose={() => {
            setIsChannelModalOpen(false);
            fetchChannels();
          }}
        />
      )}
    </div>
  );
};
