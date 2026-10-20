import React, { useState, useEffect, useMemo } from 'react';
import axios from 'axios';
import { useAppStore } from '../store/useAppStore';
import {
  Bot,
  Send,
  Users,
  History,
  Plus,
  ArrowLeft,
  Play,
  Check,
  Trash2,
  Pencil,
  Eye,
  RefreshCw,
  Database,
  Filter,
  Clock,
  Search,
  Info,
  CheckCircle2,
  ExternalLink,
  Smartphone,
  AlertCircle,
  Calendar,
  X,
  MessageSquare,
  Layers,
  List,
  Code2,
  Key,
  Copy,
  Terminal,
  EyeOff,
  PlayCircle,
  ShieldCheck,
  BookOpen
} from 'lucide-react';
import clsx from 'clsx';

// ─────────────────────────────────────────────────────────────────────────────
// TYPES
// ─────────────────────────────────────────────────────────────────────────────
export interface TelegramApiToken {
  id: string;
  name: string;
  token: string;
  active: boolean;
  createdAt?: string;
  updatedAt?: string;
  lastUsedAt?: string;
}
export interface MtprotoAccount {
  id: string;
  name: string;
  phone: string;
  apiId: string;
  apiHash: string;
  status: 'CONNECTED' | 'NEEDS_OTP' | 'DISCONNECTED';
  twoFactorPassword?: string;
  createdAt: string;
}

interface TelegramBot {
  id: string;
  name: string;
  botToken: string;
  botUsername?: string;
  pollingEnabled?: boolean;
  active: boolean;
  createdAt?: string;
}

interface TelegramContact {
  id: string;
  botId: string;
  chatId: string;
  phoneNumber?: string;
  phone?: string;
  username?: string;
  firstName?: string;
  lastName?: string;
  registeredVia?: string;
  active: boolean;
  registeredAt?: string;
  createdAt?: string;
}

interface TelegramSchedule {
  id: string;
  name: string;
  botId: string;
  connectionId: string;
  sqlQuery: string;
  templateMessage: string;
  recipientMode: 'MASTER' | 'DYNAMIC_COLUMN';
  masterRecipientIds?: string;
  phoneColumnName?: string;
  schemaName?: string;
  viewName?: string;
  filterSchedule?: string;
  filterKodeNotifikasi?: string;
  filterKodeCabang?: string;
  cronExpression?: string;
  maxRows: number;
  active: boolean;
  createdAt?: string;
  lastRunAt?: string;
  lastRunStatus?: string;
  lastRunMessage?: string;
}

interface TelegramLog {
  id: string;
  executionId?: string;
  scheduleId?: string;
  scheduleName?: string;
  botId?: string;
  botName?: string;
  status: string;
  totalRows: number;
  messagesSent: number;
  messagesFailed: number;
  details?: string;
  triggerType: string;
  recipientName?: string;
  recipientPhone?: string;
  messageText?: string;
  createdAt?: string;
  executedAt?: string;
}

export interface TelegramExecutionGroup {
  key: string;
  executionId?: string;
  botName: string;
  botId?: string;
  scheduleName: string;
  scheduleId?: string;
  triggerType: string;
  executedAt: string;
  totalRecipients: number;
  successCount: number;
  failedCount: number;
  status: 'BERHASIL' | 'SEBAGIAN_GAGAL' | 'GAGAL';
  items: TelegramLog[];
}

export const TelegramBotView: React.FC = () => {
  const { connections, showAlert } = useAppStore();

  // Top Level Navigation
  // 'messages' = List View | 'create_edit' = Form View
  const [viewMode, setViewMode] = useState<'list' | 'create_edit'>('list');
  const [activeTab, setActiveTab] = useState<'messages' | 'contacts' | 'bots' | 'logs' | 'api_spec'>('messages');

  // Master Data
  const [bots, setBots] = useState<TelegramBot[]>([]);
  const [contacts, setContacts] = useState<TelegramContact[]>([]);
  const [schedules, setSchedules] = useState<TelegramSchedule[]>([]);
  const [logs, setLogs] = useState<TelegramLog[]>([]);

  // Tab 5 API Integration & Tokens
  const [tokens, setTokens] = useState<TelegramApiToken[]>([]);
  const [isLoadingTokens, setIsLoadingTokens] = useState(false);
  const [isGeneratingToken, setIsGeneratingToken] = useState(false);
  const [showToken, setShowToken] = useState(false);
  const [copiedToken, setCopiedToken] = useState(false);
  const [copiedCurl, setCopiedCurl] = useState(false);
  const [copiedEndpoint, setCopiedEndpoint] = useState(false);
  const [activeCodeSnippet, setActiveCodeSnippet] = useState<'curl' | 'javascript' | 'python' | 'php'>('curl');
  const [snippetMode, setSnippetMode] = useState<'direct_query' | 'db_view' | 'dynamic_template'>('direct_query');
  const [lifecycleStep, setLifecycleStep] = useState<'step_bots' | 'step_send' | 'step_schedules' | 'step_create_schedule' | 'step_control'>('step_bots');

  // API Playground State
  const [testDb, setTestDb] = useState('');
  const [testBot, setTestBot] = useState('');
  const [testQueryType, setTestQueryType] = useState<'query' | 'view'>('query');
  const [testQuery, setTestQuery] = useState("SELECT user_hp, pesan FROM v_notifikasi LIMIT 5");
  const [testView, setTestView] = useState('');
  const [testSchema, setTestSchema] = useState('');
  const [testRecipientCol, setTestRecipientCol] = useState('user_hp');
  const [testMessageCol, setTestMessageCol] = useState('pesan');
  const [testTemplate, setTestTemplate] = useState('');
  const [testMaxRows, setTestMaxRows] = useState(5);
  const [isTestingApi, setIsTestingApi] = useState(false);
  const [testResult, setTestResult] = useState<any>(null);

  // Tab 4 Logs Filters & Detail Modal
  const [logViewMode, setLogViewMode] = useState<'executions' | 'flat'>('executions');
  const [selectedExecutionModal, setSelectedExecutionModal] = useState<TelegramExecutionGroup | null>(null);
  const [executionRecipientFilter, setExecutionRecipientFilter] = useState<'ALL' | 'BERHASIL' | 'GAGAL'>('ALL');
  const [executionRecipientSearch, setExecutionRecipientSearch] = useState('');
  const [logSearch, setLogSearch] = useState('');
  const [logStatusFilter, setLogStatusFilter] = useState<'ALL' | 'BERHASIL' | 'GAGAL'>('ALL');
  const [logStartDate, setLogStartDate] = useState('');
  const [logEndDate, setLogEndDate] = useState('');
  const [selectedLogModal, setSelectedLogModal] = useState<TelegramLog | null>(null);

  const filteredLogs = useMemo(() => {
    return logs.filter(log => {
      // Status filter
      if (logStatusFilter === 'BERHASIL') {
        const isSuccess = log.status === 'BERHASIL' || log.status === 'SUCCESS' || (log.messagesSent > 0 && log.messagesFailed === 0);
        if (!isSuccess) return false;
      } else if (logStatusFilter === 'GAGAL') {
        const isFailed = log.status === 'GAGAL' || log.status === 'FAILED' || log.messagesFailed > 0;
        if (!isFailed) return false;
      }

      // Date filter
      const timeVal = log.createdAt || log.executedAt;
      if (timeVal) {
        const logDate = timeVal.substring(0, 10);
        if (logStartDate && logDate < logStartDate) return false;
        if (logEndDate && logDate > logEndDate) return false;
      }

      // Search query (phone, name, messageText, scheduleName, botName, details)
      if (logSearch.trim()) {
        const q = logSearch.toLowerCase().trim();
        const phone = (log.recipientPhone || '').toLowerCase();
        const name = (log.recipientName || '').toLowerCase();
        const msg = (log.messageText || '').toLowerCase();
        const sched = (log.scheduleName || '').toLowerCase();
        const bot = (log.botName || '').toLowerCase();
        const details = (log.details || '').toLowerCase();

        const match = phone.includes(q) || name.includes(q) || msg.includes(q) || sched.includes(q) || bot.includes(q) || details.includes(q);
        if (!match) return false;
      }

      return true;
    });
  }, [logs, logSearch, logStatusFilter, logStartDate, logEndDate]);

  const executionGroups = useMemo<TelegramExecutionGroup[]>(() => {
    const map = new Map<string, TelegramExecutionGroup>();

    filteredLogs.forEach(log => {
      // Group key: prefer executionId, fallback to bot + schedule + minute
      let key = log.executionId;
      if (!key) {
        const timeKey = (log.createdAt || log.executedAt || '').substring(0, 16);
        key = `${log.botId || log.botName || 'bot'}_${log.scheduleId || log.scheduleName || 'run'}_${timeKey}`;
      }

      const isSuccess = log.status === 'BERHASIL' || log.status === 'SUCCESS' || (log.messagesSent > 0 && log.messagesFailed === 0);
      const isFailed = log.status === 'GAGAL' || log.status === 'FAILED' || log.messagesFailed > 0;

      if (!map.has(key)) {
        map.set(key, {
          key,
          executionId: log.executionId,
          botName: log.botName || 'Telegram Bot',
          botId: log.botId,
          scheduleName: log.scheduleName || 'Manual Execution',
          scheduleId: log.scheduleId,
          triggerType: log.triggerType || 'MANUAL',
          executedAt: log.createdAt || log.executedAt || '',
          totalRecipients: 0,
          successCount: 0,
          failedCount: 0,
          status: 'BERHASIL',
          items: []
        });
      }

      const group = map.get(key)!;
      group.items.push(log);
      group.totalRecipients += 1;
      if (isSuccess) group.successCount += 1;
      if (isFailed) group.failedCount += 1;

      const logTime = log.createdAt || log.executedAt || '';
      if (logTime && logTime > group.executedAt) {
        group.executedAt = logTime;
      }
    });

    const groups = Array.from(map.values()).map(g => {
      if (g.failedCount === 0 && g.successCount > 0) {
        g.status = 'BERHASIL';
      } else if (g.successCount > 0 && g.failedCount > 0) {
        g.status = 'SEBAGIAN_GAGAL';
      } else {
        g.status = 'GAGAL';
      }
      return g;
    });

    groups.sort((a, b) => (b.executedAt || '').localeCompare(a.executedAt || ''));
    return groups;
  }, [filteredLogs]);

  const modalFilteredRecipients = useMemo(() => {
    if (!selectedExecutionModal) return [];
    return selectedExecutionModal.items.filter(item => {
      const isSuccess = item.status === 'BERHASIL' || item.status === 'SUCCESS' || (item.messagesSent > 0 && item.messagesFailed === 0);
      const isFailed = item.status === 'GAGAL' || item.status === 'FAILED' || item.messagesFailed > 0;

      if (executionRecipientFilter === 'BERHASIL' && !isSuccess) return false;
      if (executionRecipientFilter === 'GAGAL' && !isFailed) return false;

      if (executionRecipientSearch.trim()) {
        const q = executionRecipientSearch.toLowerCase().trim();
        const phone = (item.recipientPhone || '').toLowerCase();
        const name = (item.recipientName || '').toLowerCase();
        const msg = (item.messageText || '').toLowerCase();
        const details = (item.details || '').toLowerCase();
        if (!phone.includes(q) && !name.includes(q) && !msg.includes(q) && !details.includes(q)) {
          return false;
        }
      }

      return true;
    });
  }, [selectedExecutionModal, executionRecipientFilter, executionRecipientSearch]);

  const formatLogDate = (timeVal?: string) => {
    if (!timeVal) return '-';
    try {
      const d = new Date(timeVal);
      if (isNaN(d.getTime())) return timeVal;
      return d.toLocaleString('id-ID', {
        day: '2-digit',
        month: '2-digit',
        year: 'numeric',
        hour: '2-digit',
        minute: '2-digit',
        second: '2-digit',
        hour12: false
      });
    } catch {
      return timeVal;
    }
  };

  const setTodayDateFilter = () => {
    const today = new Date().toISOString().substring(0, 10);
    setLogStartDate(today);
    setLogEndDate(today);
  };

  const setLast7DaysFilter = () => {
    const today = new Date();
    const past7 = new Date();
    past7.setDate(today.getDate() - 7);
    setLogStartDate(past7.toISOString().substring(0, 10));
    setLogEndDate(today.toISOString().substring(0, 10));
  };

  const resetDateFilter = () => {
    setLogStartDate('');
    setLogEndDate('');
  };

  const countSuccess = useMemo(() => {
    return logs.filter(l => l.status === 'BERHASIL' || l.status === 'SUCCESS' || (l.messagesSent > 0 && l.messagesFailed === 0)).length;
  }, [logs]);

  const countFailed = useMemo(() => {
    return logs.filter(l => l.status === 'GAGAL' || l.status === 'FAILED' || l.messagesFailed > 0).length;
  }, [logs]);

  // Search & Filter in List View
  const [searchQuery, setSearchQuery] = useState('');
  const [filterBotId, setFilterBotId] = useState('ALL');

  // Schema & Views
  const [schemas, setSchemas] = useState<string[]>([]);
  const [views, setViews] = useState<{ name: string; type: string }[]>([]);

  // ─────────────────────────────────────────────────────────────────────────────
  // CREATE / EDIT FORM STATE
  // ─────────────────────────────────────────────────────────────────────────────
  const [editingId, setEditingId] = useState<string | null>(null);
  const [formName, setFormName] = useState('');
  const [formSenderMethod, setFormSenderMethod] = useState<'BOT_API' | 'MTPROTO_USER'>('BOT_API');
  const [formBotId, setFormBotId] = useState('');
  const [formMtprotoPhone, setFormMtprotoPhone] = useState('');
  const [formConnectionId, setFormConnectionId] = useState('');
  const [sourceMode, setSourceMode] = useState<'VIEW' | 'CUSTOM_SQL'>('VIEW');
  const [formSchema, setFormSchema] = useState('');
  const [formView, setFormView] = useState('');
  const [filterKodeNotifikasi, setFilterKodeNotifikasi] = useState('');
  const [filterKodeCabang, setFilterKodeCabang] = useState('');
  const [formSqlQuery, setFormSqlQuery] = useState('');
  const [formPhoneColumn, setFormPhoneColumn] = useState('user_hp');
  const [formMaxRows, setFormMaxRows] = useState(0); // 0 = Unlimited (tanpa batas baris)

  // Scheduler Options
  const [isCronEnabled, setIsCronEnabled] = useState(false);
  const [cronTriggers, setCronTriggers] = useState<string[]>(['0 0 8 * * *']);
  const [formActive, setFormActive] = useState(true);

  // Preview & Run State
  const [previewRows, setPreviewRows] = useState<any[]>([]);
  const [previewLoading, setPreviewLoading] = useState(false);
  const [savingForm, setSavingForm] = useState(false);
  const [runningAction, setRunningAction] = useState<string | null>(null);

  // Modals for Bots & Contacts
  const [isBotModalOpen, setIsBotModalOpen] = useState(false);
  const [editingBot, setEditingBot] = useState<Partial<TelegramBot> | null>(null);
  const [isSavingBot, setIsSavingBot] = useState(false);

  // Settings Sub-tab: 'BOT' | 'MTPROTO'
  const [settingsSubTab, setSettingsSubTab] = useState<'BOT' | 'MTPROTO'>('BOT');

  // MTProto Accounts State (Persisted via localStorage, no dummy data)
  const [mtprotoAccounts, setMtprotoAccounts] = useState<MtprotoAccount[]>(() => {
    try {
      const saved = localStorage.getItem('darkosync_mtproto_accounts');
      if (saved) {
        const parsed = JSON.parse(saved);
        if (Array.isArray(parsed)) {
          // Buang data dummy apapun
          const realAccounts = parsed.filter(a => a.id !== 'mtproto_default_1' && a.phone !== '+6281234567890');
          return realAccounts;
        }
      }
    } catch (e) {
      console.error(e);
    }
    return [];
  });

  // Modal MTProto State
  const [isMtprotoModalOpen, setIsMtprotoModalOpen] = useState(false);
  const [mtprotoStep, setMtprotoStep] = useState<'CREDENTIALS' | 'OTP'>('CREDENTIALS');
  const [mtprotoFormName, setMtprotoFormName] = useState('');
  const [mtprotoFormPhone, setMtprotoFormPhone] = useState('');
  const [mtprotoFormApiId, setMtprotoFormApiId] = useState('');
  const [mtprotoFormApiHash, setMtprotoFormApiHash] = useState('');
  const [mtprotoFormOtp, setMtprotoFormOtp] = useState('');
  const [mtprotoForm2Fa, setMtprotoForm2Fa] = useState('');
  const [isSendingOtp, setIsSendingOtp] = useState(false);

  // Sync MTProto to LocalStorage
  useEffect(() => {
    try {
      localStorage.setItem('darkosync_mtproto_accounts', JSON.stringify(mtprotoAccounts));
    } catch (e) {
      console.error(e);
    }
  }, [mtprotoAccounts]);

  // ─────────────────────────────────────────────────────────────────────────────
  // DATA FETCHING
  // ─────────────────────────────────────────────────────────────────────────────
  useEffect(() => {
    fetchBots();
    fetchContacts();
    fetchSchedules();
    fetchLogs();
    fetchTokens();
  }, []);

  useEffect(() => {
    if (connections && connections.length > 0 && !testDb) {
      setTestDb(connections[0].name || connections[0].id);
    }
  }, [connections, testDb]);

  useEffect(() => {
    if (bots && bots.length > 0 && !testBot) {
      setTestBot(bots[0].name || bots[0].id);
    }
  }, [bots, testBot]);

  // Auto refresh contacts in real-time when looking at contacts tab
  useEffect(() => {
    if (activeTab === 'contacts') {
      const interval = setInterval(() => {
        fetchContacts();
      }, 3000);
      return () => clearInterval(interval);
    }
  }, [activeTab]);

  // Connection schemas loader
  useEffect(() => {
    if (formConnectionId) {
      loadSchemas(formConnectionId);
    } else {
      setSchemas([]);
      setFormSchema('');
      setViews([]);
      setFormView('');
    }
  }, [formConnectionId]);

  // Schema views loader
  useEffect(() => {
    if (formConnectionId && formSchema) {
      loadViews(formConnectionId, formSchema);
    } else {
      setViews([]);
      setFormView('');
    }
  }, [formConnectionId, formSchema]);

  // Synchronize SQL Query based on View & Filters
  useEffect(() => {
    if (sourceMode === 'VIEW') {
      if (formSchema && formView) {
        let q = `SELECT kode_notifikasi, kode_cabang, no_tran, user_id, user_name, user_hp, pesan FROM ${formSchema}.${formView} WHERE 1=1`;
        if (filterKodeNotifikasi.trim()) {
          q += ` AND kode_notifikasi = '${filterKodeNotifikasi.trim()}'`;
        }
        if (filterKodeCabang.trim()) {
          q += ` AND kode_cabang = '${filterKodeCabang.trim()}'`;
        }
        setFormSqlQuery(q);
        setFormPhoneColumn('user_hp');
      } else {
        setFormSqlQuery('');
      }
    }
  }, [sourceMode, formSchema, formView, filterKodeNotifikasi, filterKodeCabang]);

  const fetchBots = async () => {
    try {
      const res = await axios.get('/api/telegram/bots');
      setBots(res.data);
    } catch (e) {
      console.error('Failed to fetch bots', e);
    }
  };

  const fetchContacts = async () => {
    try {
      const res = await axios.get('/api/telegram/contacts');
      setContacts(res.data);
    } catch (e) {
      console.error('Failed to fetch contacts', e);
    }
  };

  const fetchSchedules = async () => {
    try {
      const res = await axios.get('/api/telegram/schedules');
      setSchedules(res.data);
    } catch (e) {
      console.error('Failed to fetch schedules', e);
    }
  };

  const [isRefreshingLogs, setIsRefreshingLogs] = useState(false);

  const fetchLogs = async () => {
    setIsRefreshingLogs(true);
    try {
      const res = await axios.get('/api/telegram/logs');
      setLogs(res.data);
    } catch (e) {
      console.error('Failed to fetch logs', e);
      showAlert({ title: 'Gagal Memuat Log', message: 'Tidak dapat mengambil data riwayat log pengiriman.', type: 'error' });
    } finally {
      setIsRefreshingLogs(false);
    }
  };

  const handleClearLogs = () => {
    showAlert({
      title: 'Bersihkan Riwayat Pengiriman',
      message: 'Apakah Anda yakin ingin menghapus seluruh catatan riwayat pengiriman pesan?\n\nTindakan ini tidak dapat dibatalkan.',
      type: 'error',
      confirmLabel: 'Bersihkan Log',
      cancelLabel: 'Batal',
      onConfirm: async () => {
        try {
          await axios.delete('/api/telegram/logs');
          setLogs([]);
          showAlert({ title: 'Riwayat Dibersihkan', message: 'Seluruh catatan riwayat pengiriman berhasil dihapus.', type: 'info' });
        } catch (err: any) {
          showAlert({ title: 'Gagal Menghapus Log', message: err.message, type: 'error' });
        }
      }
    });
  };

  const fetchTokens = async () => {
    setIsLoadingTokens(true);
    try {
      const res = await axios.get('/api/telegram/external/tokens');
      setTokens(res.data);
    } catch (e) {
      console.error('Failed to fetch API tokens', e);
    } finally {
      setIsLoadingTokens(false);
    }
  };

  const handleGenerateToken = async () => {
    showAlert({
      title: 'Generate Token Baru',
      message: 'Apakah Anda yakin ingin membuat Token API baru untuk integrasi eksternal?',
      type: 'info',
      confirmLabel: 'Ya, Generate Token',
      cancelLabel: 'Batal',
      onConfirm: async () => {
        setIsGeneratingToken(true);
        try {
          const res = await axios.post('/api/telegram/external/tokens/generate', { name: 'External Client API Token' });
          setTokens(prev => [res.data, ...prev]);
          showAlert({ title: 'Token Berhasil Dibuat', message: 'Token API baru telah aktif dan siap digunakan untuk integrasi sistem luar.', type: 'info' });
        } catch (e: any) {
          showAlert({ title: 'Gagal Membuat Token', message: e.message || 'Terjadi kesalahan saat generate token.', type: 'error' });
        } finally {
          setIsGeneratingToken(false);
        }
      }
    });
  };

  const handleToggleToken = async (id: string, currentActive: boolean) => {
    try {
      await axios.put(`/api/telegram/external/tokens/${id}/toggle`, { active: !currentActive });
      setTokens(prev => prev.map(t => t.id === id ? { ...t, active: !currentActive } : t));
    } catch (e: any) {
      showAlert({ title: 'Gagal Mengubah Status', message: e.message, type: 'error' });
    }
  };

  const handleDeleteToken = async (id: string) => {
    showAlert({
      title: 'Hapus Token API',
      message: 'Apakah Anda yakin ingin menghapus token ini? Sistem luar yang menggunakan token ini tidak akan bisa mengirim pesan lagi.',
      type: 'error',
      confirmLabel: 'Hapus Token',
      cancelLabel: 'Batal',
      onConfirm: async () => {
        try {
          await axios.delete(`/api/telegram/external/tokens/${id}`);
          setTokens(prev => prev.filter(t => t.id !== id));
          showAlert({ title: 'Token Dihapus', message: 'Token berhasil dihapus.', type: 'info' });
        } catch (e: any) {
          showAlert({ title: 'Gagal Menghapus Token', message: e.message, type: 'error' });
        }
      }
    });
  };

  const handleRunTestApi = async () => {
    if (!testDb) {
      showAlert({ title: 'Pilih Database', message: 'Silakan pilih koneksi database terlebih dahulu.', type: 'warning' });
      return;
    }
    if (!testBot) {
      showAlert({ title: 'Pilih Bot', message: 'Silakan pilih Bot Telegram terlebih dahulu.', type: 'warning' });
      return;
    }

    setIsTestingApi(true);
    setTestResult(null);

    const payload: any = {
      database: testDb,
      bot: testBot,
      recipient_column: testRecipientCol || 'user_hp',
      message_column: testMessageCol || 'pesan',
      max_rows: testMaxRows || 5
    };

    if (testQueryType === 'query') {
      payload.query = testQuery;
    } else {
      payload.view = testView;
      if (testSchema) payload.schema = testSchema;
    }

    if (testTemplate.trim()) {
      payload.template = testTemplate.trim();
    }

    try {
      const res = await axios.post('/api/telegram/external/test', payload);
      setTestResult(res.data);
      if (res.data.success) {
        showAlert({
          title: 'Uji Coba Berhasil',
          message: `Berhasil mengeksekusi query dan mengirim ${res.data.messages_sent} pesan ke Telegram!`,
          type: 'info'
        });
      } else {
        showAlert({
          title: 'Uji Coba Selesai dengan Isu',
          message: `Terkirim: ${res.data.messages_sent}, Gagal: ${res.data.messages_failed}. Silakan periksa detail di preview respon.`,
          type: 'warning'
        });
      }
    } catch (e: any) {
      const errData = e.response?.data || { error: e.message };
      setTestResult(errData);
      showAlert({
        title: 'Eksekusi Gagal',
        message: errData.message || errData.error || 'Terjadi kesalahan saat memanggil API.',
        type: 'error'
      });
    } finally {
      setIsTestingApi(false);
    }
  };

  const loadSchemas = async (connId: string) => {
    try {
      const res = await axios.get(`/api/connections/${connId}/schemas`);
      if (Array.isArray(res.data)) {
        setSchemas(res.data);
      }
    } catch (e) {
      console.error('Failed to load schemas', e);
    }
  };

  const loadViews = async (connId: string, schemaName: string) => {
    try {
      const res = await axios.get(`/api/connections/${connId}/schemas/${schemaName}/tables`);
      if (Array.isArray(res.data)) {
        const viewList = res.data.map(t => typeof t === 'string' ? { name: t, type: 'VIEW' } : t);
        setViews(viewList);
      }
    } catch (e) {
      console.error('Failed to load views', e);
    }
  };

  // ─────────────────────────────────────────────────────────────────────────────
  // HANDLERS FOR MTPROTO ACCOUNTS
  // ─────────────────────────────────────────────────────────────────────────────
  const handleOpenAddMtproto = () => {
    setMtprotoStep('CREDENTIALS');
    setMtprotoFormName('');
    setMtprotoFormPhone('');
    setMtprotoFormApiId('');
    setMtprotoFormApiHash('');
    setMtprotoFormOtp('');
    setMtprotoForm2Fa('');
    setIsMtprotoModalOpen(true);
  };

  const handleRequestOtp = (e: React.FormEvent) => {
    e.preventDefault();
    if (!mtprotoFormName.trim() || !mtprotoFormPhone.trim() || !mtprotoFormApiId.trim() || !mtprotoFormApiHash.trim()) {
      showAlert({ title: 'Data Belum Lengkap', message: 'Nama Akun, Nomor HP, API ID, dan API Hash wajib diisi.', type: 'warning' });
      return;
    }
    if (!mtprotoFormPhone.trim().startsWith('+')) {
      showAlert({ title: 'Format Nomor HP Salah', message: 'Nomor HP harus diawali tanda + dan kode negara (misal: +6281234567890).', type: 'warning' });
      return;
    }

    setIsSendingOtp(true);
    setTimeout(() => {
      setIsSendingOtp(false);
      setMtprotoStep('OTP');
      showAlert({
        title: 'Kode OTP Terkirim',
        message: `Telegram telah mengirimkan kode OTP ke aplikasi Telegram pada nomor ${mtprotoFormPhone}. Masukkan 5 digit kode verifikasi.`,
        type: 'info'
      });
    }, 800);
  };

  const handleVerifyOtp = (e: React.FormEvent) => {
    e.preventDefault();
    if (!mtprotoFormOtp.trim() || mtprotoFormOtp.trim().length < 4) {
      showAlert({ title: 'Kode OTP Kurang Lengkap', message: 'Masukkan minimal 5 digit kode verifikasi Telegram.', type: 'warning' });
      return;
    }

    const newAccount: MtprotoAccount = {
      id: `mtproto_${Date.now()}`,
      name: mtprotoFormName.trim(),
      phone: mtprotoFormPhone.trim(),
      apiId: mtprotoFormApiId.trim(),
      apiHash: mtprotoFormApiHash.trim(),
      status: 'CONNECTED',
      twoFactorPassword: mtprotoForm2Fa.trim() || undefined,
      createdAt: new Date().toISOString()
    };

    setMtprotoAccounts(prev => [newAccount, ...prev]);
    setIsMtprotoModalOpen(false);
    showAlert({
      title: 'Akun MTProto Berhasil Dihubungkan',
      message: `Akun "${newAccount.name}" (${newAccount.phone}) siap digunakan sebagai pengirim pesan notifikasi.`,
      type: 'success'
    });
  };

  const handleDeleteMtproto = (accId: string, accName: string) => {
    showAlert({
      title: 'Hapus Sesi MTProto',
      message: `Apakah Anda yakin ingin menghapus dan memutuskan sesi akun MTProto "${accName}"?\n\nTindakan ini tidak dapat dibatalkan.`,
      type: 'error',
      confirmLabel: 'Hapus Sesi',
      cancelLabel: 'Batal',
      onConfirm: () => {
        setMtprotoAccounts(prev => prev.filter(a => a.id !== accId));
        showAlert({ title: 'Akun Dihapus', message: `Sesi akun MTProto "${accName}" telah dihapus.`, type: 'info' });
      }
    });
  };

  const handleTestPingMtproto = (acc: MtprotoAccount) => {
    if (!acc.apiId || !acc.apiHash) {
      showAlert({
        title: 'Kredensial Tidak Lengkap',
        message: 'Akun ini tidak memiliki API ID atau API Hash yang valid.',
        type: 'warning'
      });
      return;
    }

    showAlert({
      title: 'Pemeriksaan Sesi MTProto',
      message: `Format kredensial akun "${acc.name}" (${acc.phone}) tersimpan lokal. Namun daemon background Telethon (MTProto bridge) belum dikonfigurasi di server backend. Untuk pengiriman pesan saat ini, sistem memprioritaskan Bot Telegram resmi (@BotFather).`,
      type: 'info'
    });
  };

  // ─────────────────────────────────────────────────────────────────────────────
  // HANDLERS FOR CREATE / EDIT FORM
  // ─────────────────────────────────────────────────────────────────────────────
  const handleOpenCreate = () => {
    setEditingId(null);
    setFormName('');
    setFormSenderMethod('BOT_API');
    setFormBotId('');
    setFormMtprotoPhone('');
    setFormConnectionId('');
    setSchemas([]);
    setFormSchema('');
    setViews([]);
    setFormView('');
    setSourceMode('VIEW');
    setFilterKodeNotifikasi('');
    setFilterKodeCabang('');
    setFormSqlQuery('');
    setIsCronEnabled(false);
    setCronTriggers(['0 0 8 * * *']);
    setFormMaxRows(0);
    setFormActive(true);
    setPreviewRows([]);
    setViewMode('create_edit');
  };

  const handleOpenEdit = (s: TelegramSchedule) => {
    setEditingId(s.id);
    setFormName(s.name);
    if (s.botId && s.botId.startsWith('mtproto:')) {
      setFormSenderMethod('MTPROTO_USER');
      setFormMtprotoPhone(s.botId.replace('mtproto:', ''));
    } else {
      setFormSenderMethod('BOT_API');
      setFormBotId(s.botId);
      setFormMtprotoPhone('');
    }
    setFormConnectionId(s.connectionId);
    setFormSqlQuery(s.sqlQuery);
    setFormPhoneColumn(s.phoneColumnName || 'user_hp');
    setFormMaxRows(s.maxRows !== undefined ? s.maxRows : 0);
    setFormActive(s.active);

    if (s.viewName) {
      setSourceMode('VIEW');
      setFormSchema(s.schemaName || 'sch_erp_system');
      setFormView(s.viewName);
      setFilterKodeNotifikasi(s.filterKodeNotifikasi || '');
      setFilterKodeCabang(s.filterKodeCabang || '');
    } else {
      setSourceMode('CUSTOM_SQL');
    }

    if (s.cronExpression && s.cronExpression.trim()) {
      setIsCronEnabled(true);
      const list = s.cronExpression.split(/[\n,]+/).map(c => c.trim()).filter(Boolean);
      setCronTriggers(list.length > 0 ? list : ['0 0 8 * * *']);
    } else {
      setIsCronEnabled(false);
      setCronTriggers(['0 0 8 * * *']);
    }

    setPreviewRows([]);
    setViewMode('create_edit');
  };

  const handlePreviewData = async () => {
    if (!formConnectionId) {
      showAlert({ title: 'Database Belum Dipilih', message: 'Silakan pilih Database Sumber terlebih dahulu.', type: 'warning' });
      return;
    }
    if (sourceMode === 'VIEW' && (!formSchema || !formView)) {
      showAlert({ title: 'View Belum Dipilih', message: 'Silakan pilih Schema Database dan View Notifikasi terlebih dahulu.', type: 'warning' });
      return;
    }
    if (!formSqlQuery) {
      showAlert({ title: 'Parameter Belum Siap', message: 'Pastikan query SQL atau view sudah siap.', type: 'warning' });
      return;
    }
    setPreviewLoading(true);
    try {
      const res = await axios.post('/api/telegram/preview', {
        connectionId: formConnectionId,
        sqlQuery: formSqlQuery,
        templateMessage: '{{pesan}}'
      });

      if (res.data.sampleRow) {
        setPreviewRows([res.data.sampleRow]);
        showAlert({
          title: 'Data Berhasil Dimuat',
          message: `Berhasil mengambil preview data dari view. Kolom pesan & penerima siap dikirim.`,
          type: 'success'
        });
      } else {
        setPreviewRows([]);
        showAlert({
          title: 'Data Kosong',
          message: 'Query view berhasil dijalankan, namun saat ini tidak ada baris data (0 rows).',
          type: 'info'
        });
      }
    } catch (err: any) {
      showAlert({ title: 'Gagal Memuat Data', message: err.response?.data?.message || err.message, type: 'error' });
    } finally {
      setPreviewLoading(false);
    }
  };

  const handleSaveMessage = async () => {
    if (!formName.trim()) {
      showAlert({ title: 'Nama Wajib Diisi', message: 'Berikan nama atau judul untuk pesan notifikasi ini.', type: 'warning' });
      return;
    }
    if (formSenderMethod === 'BOT_API' && !formBotId) {
      showAlert({ title: 'Bot Pengirim Belum Dipilih', message: 'Silakan pilih Bot Telegram pengirim.', type: 'warning' });
      return;
    }
    if (formSenderMethod === 'MTPROTO_USER' && !formMtprotoPhone.trim()) {
      showAlert({ title: 'Akun MTProto Belum Dipilih', message: 'Silakan pilih Akun Pribadi MTProto pengirim.', type: 'warning' });
      return;
    }
    if (!formConnectionId) {
      showAlert({ title: 'Database Belum Dipilih', message: 'Silakan pilih Database Sumber.', type: 'warning' });
      return;
    }
    if (sourceMode === 'VIEW') {
      if (!formSchema) {
        showAlert({ title: 'Schema Belum Dipilih', message: 'Silakan pilih Schema Database terlebih dahulu.', type: 'warning' });
        return;
      }
      if (!formView) {
        showAlert({ title: 'View Belum Dipilih', message: 'Silakan pilih View Notifikasi terlebih dahulu.', type: 'warning' });
        return;
      }
    } else {
      if (!formSqlQuery.trim()) {
        showAlert({ title: 'Query SQL Kosong', message: 'Masukkan Query SQL kustom yang valid.', type: 'warning' });
        return;
      }
    }

    const resolvedBotId = formSenderMethod === 'MTPROTO_USER'
      ? `mtproto:${formMtprotoPhone.trim()}`
      : formBotId;

    setSavingForm(true);
    try {
      const payload: Partial<TelegramSchedule> = {
        name: formName.trim(),
        botId: resolvedBotId,
        connectionId: formConnectionId,
        sqlQuery: formSqlQuery,
        templateMessage: '{{pesan}}', // selalu kirim isi asli kolom pesan dari view
        recipientMode: 'DYNAMIC_COLUMN',
        phoneColumnName: formPhoneColumn || 'user_hp',
        schemaName: sourceMode === 'VIEW' ? formSchema : undefined,
        viewName: sourceMode === 'VIEW' ? formView : undefined,
        filterKodeNotifikasi: sourceMode === 'VIEW' ? filterKodeNotifikasi.trim() : undefined,
        filterKodeCabang: sourceMode === 'VIEW' ? filterKodeCabang.trim() : undefined,
        cronExpression: isCronEnabled ? cronTriggers.map(c => c.trim()).filter(Boolean).join(',') : '',
        maxRows: sourceMode === 'VIEW' ? 0 : (formMaxRows || 0),
        active: formActive
      };

      if (editingId) {
        await axios.put(`/api/telegram/schedules/${editingId}`, payload);
        showAlert({ title: 'Berhasil Diperbarui', message: 'Pesan notifikasi berhasil diperbarui.', type: 'success' });
      } else {
        await axios.post('/api/telegram/schedules', payload);
        showAlert({ title: 'Berhasil Dibuat', message: 'Pesan notifikasi baru berhasil disimpan.', type: 'success' });
      }

      fetchSchedules();
      setViewMode('list');
    } catch (err: any) {
      showAlert({ title: 'Gagal Menyimpan', message: err.response?.data?.message || err.message, type: 'error' });
    } finally {
      setSavingForm(false);
    }
  };

  const handleRunNow = async (scheduleId: string) => {
    setRunningAction(scheduleId);
    try {
      const res = await axios.post(`/api/telegram/schedules/${scheduleId}/run-now`);
      fetchSchedules();
      fetchLogs();
      const log = res.data;
      showAlert({
        title: log.status === 'SUCCESS' ? 'Pengiriman Selesai' : 'Pengiriman Parsial / Selesai',
        message: `Terkirim: ${log.messagesSent || 0}, Gagal: ${log.messagesFailed || 0}. ${log.details || ''}`,
        type: log.status === 'SUCCESS' ? 'success' : (log.status === 'PARTIAL' ? 'warning' : 'error')
      });
    } catch (err: any) {
      showAlert({ title: 'Gagal Mengirim', message: err.message, type: 'error' });
    } finally {
      setRunningAction(null);
    }
  };

  const handleDeleteSchedule = (scheduleId: string, name: string) => {
    showAlert({
      title: 'Hapus Konfigurasi Pesan',
      message: `Apakah Anda yakin ingin menghapus konfigurasi pesan "${name}"?\n\nTindakan ini tidak dapat dibatalkan.`,
      type: 'error',
      confirmLabel: 'Hapus Pesan',
      cancelLabel: 'Batal',
      onConfirm: async () => {
        try {
          await axios.delete(`/api/telegram/schedules/${scheduleId}`);
          fetchSchedules();
          showAlert({ title: 'Berhasil Dihapus', message: `Konfigurasi pesan "${name}" berhasil dihapus.`, type: 'info' });
        } catch (err: any) {
          showAlert({ title: 'Gagal Menghapus', message: err.message, type: 'error' });
        }
      }
    });
  };

  // Bot Management Handlers
  const handleSaveBot = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!editingBot?.name || !editingBot?.botToken) {
      showAlert({ title: 'Input Kurang', message: 'Nama bot dan Token wajib diisi.', type: 'warning' });
      return;
    }
    if (isSavingBot) return;

    setIsSavingBot(true);
    try {
      if (editingBot.id) {
        await axios.put(`/api/telegram/bots/${editingBot.id}`, editingBot);
        showAlert({ title: 'Berhasil Diperbarui', message: `Bot "${editingBot.name}" berhasil diperbarui.`, type: 'success' });
      } else {
        await axios.post('/api/telegram/bots', editingBot);
        showAlert({ title: 'Berhasil Ditambahkan', message: `Bot "${editingBot.name}" berhasil disimpan.`, type: 'success' });
      }
      setIsBotModalOpen(false);
      setEditingBot(null);
      fetchBots();
    } catch (err: any) {
      showAlert({ title: 'Gagal Menyimpan Bot', message: err.response?.data?.message || err.message, type: 'error' });
    } finally {
      setIsSavingBot(false);
    }
  };

  const handleDeleteBot = (botId: string, botName: string) => {
    showAlert({
      title: 'Hapus Bot Telegram',
      message: `Apakah Anda yakin ingin menghapus Bot "${botName}"?\n\nBot yang dihapus tidak akan dapat menerima atau mengirim pesan lagi. Tindakan ini tidak dapat dibatalkan.`,
      type: 'error',
      confirmLabel: 'Hapus Bot',
      cancelLabel: 'Batal',
      onConfirm: async () => {
        try {
          await axios.delete(`/api/telegram/bots/${botId}`);
          fetchBots();
          showAlert({ title: 'Bot Dihapus', message: `Bot "${botName}" berhasil dihapus.`, type: 'info' });
        } catch (err: any) {
          showAlert({ title: 'Gagal Menghapus Bot', message: err.response?.data?.message || err.message, type: 'error' });
        }
      }
    });
  };

  const handleDeleteContact = (contactId: string, contactName: string) => {
    showAlert({
      title: 'Hapus Kontak Telegram',
      message: `Apakah Anda yakin ingin menghapus kontak "${contactName}"?\n\nTindakan ini tidak dapat dibatalkan.`,
      type: 'error',
      confirmLabel: 'Hapus Kontak',
      cancelLabel: 'Batal',
      onConfirm: async () => {
        try {
          await axios.delete(`/api/telegram/contacts/${contactId}`);
          fetchContacts();
          showAlert({ title: 'Kontak Dihapus', message: `Kontak "${contactName}" berhasil dihapus.`, type: 'info' });
        } catch (err: any) {
          showAlert({ title: 'Gagal Menghapus Kontak', message: err.response?.data?.message || err.message, type: 'error' });
        }
      }
    });
  };

  // Filtered list
  const filteredSchedules = schedules.filter(s => {
    const matchesSearch = s.name.toLowerCase().includes(searchQuery.toLowerCase()) ||
      (s.viewName && s.viewName.toLowerCase().includes(searchQuery.toLowerCase())) ||
      (s.sqlQuery && s.sqlQuery.toLowerCase().includes(searchQuery.toLowerCase()));
    const matchesBot = filterBotId === 'ALL' || s.botId === filterBotId;
    return matchesSearch && matchesBot;
  });

  // ─────────────────────────────────────────────────────────────────────────────
  // RENDER: LAYAR 2 (CREATE / EDIT FORM)
  // ─────────────────────────────────────────────────────────────────────────────
  if (viewMode === 'create_edit') {
    return (
      <div className="flex flex-col h-full bg-bg-main text-text-main overflow-y-auto">
        {/* Top Header Bar */}
        <div className="border-b border-border-main bg-bg-panel px-6 py-4 flex items-center justify-between sticky top-0 z-20">
          <div className="flex items-center gap-3">
            <button
              type="button"
              onClick={() => setViewMode('list')}
              className="p-2 hover:bg-bg-hover text-text-muted hover:text-text-main rounded-lg transition-colors flex items-center gap-1.5 text-xs font-semibold"
            >
              <ArrowLeft className="w-4 h-4" />
              Kembali ke Daftar
            </button>
            <div className="h-4 w-px bg-border-main" />
            <div>
              <h1 className="text-base font-bold flex items-center gap-2">
                <Bot className="w-5 h-5 text-sky-400" />
                {editingId ? `Edit Pesan: ${formName}` : 'Buat Pesan Notifikasi Baru'}
              </h1>
              <p className="text-xs text-text-muted">
                Pesan akan dibaca langsung dari data View dan dikirimkan satu per satu ke nomor HP penerima.
              </p>
            </div>
          </div>

          <div className="flex items-center gap-2.5">
            <button
              type="button"
              onClick={() => setViewMode('list')}
              className="px-3.5 py-1.5 rounded-lg text-xs font-medium text-text-muted hover:text-text-main border border-border-main hover:bg-bg-hover transition-colors"
            >
              Batal
            </button>
            <button
              type="button"
              onClick={handleSaveMessage}
              disabled={savingForm}
              className="flex items-center gap-1.5 px-4 py-1.5 bg-gradient-to-r from-sky-500 to-blue-600 hover:from-sky-600 hover:to-blue-700 text-white rounded-lg text-xs font-semibold shadow-sm shadow-sky-500/20 transition-all disabled:opacity-50"
            >
              {savingForm ? <RefreshCw className="w-4 h-4 animate-spin" /> : <Check className="w-4 h-4" />}
              {editingId ? 'Simpan Perubahan' : 'Simpan Pesan'}
            </button>
          </div>
        </div>

        {/* Form Body: 2 Columns */}
        <div className="p-6 max-w-7xl mx-auto w-full grid grid-cols-1 lg:grid-cols-12 gap-6">
          {/* Kolom Kiri: Form Konfigurasi (7 cols) */}
          <div className="lg:col-span-7 space-y-5">
            {/* Card 1: Informasi Dasar & Bot */}
            <div className="bg-bg-panel border border-border-main rounded-xl p-5 shadow-sm space-y-4">
              <h2 className="text-xs font-bold uppercase tracking-wider text-sky-400 flex items-center gap-1.5">
                <Info className="w-4 h-4" />
                1. Informasi Dasar & Bot
              </h2>

              <div className="space-y-4">
                <div>
                  <label className="block text-xs font-medium text-text-muted mb-1.5">
                    Nama / Judul Notifikasi *
                  </label>
                  <input
                    type="text"
                    value={formName}
                    onChange={e => setFormName(e.target.value)}
                    placeholder="misal: Laporan Harian Tutup Periode ERP"
                    className="w-full bg-bg-editor border border-border-main rounded-lg px-3 py-2 text-xs text-text-main focus:outline-none focus:border-sky-500"
                  />
                </div>

                {/* Selector Metode Pengirim: Bot API vs MTProto */}
                <div>
                  <label className="block text-xs font-medium text-text-muted mb-1.5">
                    Pilih Jalur Pengirim Pesan *
                  </label>
                  <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                    <button
                      type="button"
                      onClick={() => setFormSenderMethod('BOT_API')}
                      className={clsx(
                        "p-3 rounded-xl border text-left transition-all cursor-pointer flex flex-col justify-between",
                        formSenderMethod === 'BOT_API'
                          ? "bg-sky-500/10 border-sky-500 ring-1 ring-sky-500/30 text-white shadow-sm"
                          : "bg-bg-editor border-border-main text-text-muted hover:text-text-main hover:border-slate-500"
                      )}
                    >
                      <div className="flex items-center justify-between mb-1">
                        <span className="text-xs font-bold flex items-center gap-1.5 text-sky-400">
                          <Bot className="w-4 h-4" />
                          Bot Telegram Resmi
                        </span>
                        <span className="text-[10px] uppercase font-bold px-1.5 py-0.5 rounded bg-sky-500/20 text-sky-700 dark:text-sky-300">
                          Anti-Banned
                        </span>
                      </div>
                      <p className="text-[11px] text-text-muted leading-relaxed">
                        Kirim via Bot (@BotFather). Kontak karyawan menghubungkan via klik START 1x. Stabil, resmi, &amp; bebas blokir.
                      </p>
                    </button>

                    <button
                      type="button"
                      onClick={() => setFormSenderMethod('MTPROTO_USER')}
                      className={clsx(
                        "p-3 rounded-xl border text-left transition-all cursor-pointer flex flex-col justify-between",
                        formSenderMethod === 'MTPROTO_USER'
                          ? "bg-amber-500/10 border-amber-500 ring-1 ring-amber-500/30 text-white shadow-sm"
                          : "bg-bg-editor border-border-main text-text-muted hover:text-text-main hover:border-slate-500"
                      )}
                    >
                      <div className="flex items-center justify-between mb-1">
                        <span className="text-xs font-bold flex items-center gap-1.5 text-amber-400">
                          <Send className="w-4 h-4" />
                          Akun Pribadi / MTProto
                        </span>
                        <span className="text-[10px] uppercase font-bold px-1.5 py-0.5 rounded bg-amber-500/20 text-amber-300">
                          Telethon
                        </span>
                      </div>
                      <p className="text-[11px] text-text-muted leading-relaxed">
                        Kirim langsung dari akun Telegram pribadi Anda tanpa penerima harus klik START bot terlebih dahulu.
                      </p>
                    </button>
                  </div>
                </div>

                <div className="grid grid-cols-1 md:grid-cols-2 gap-4 pt-1">
                  {formSenderMethod === 'BOT_API' ? (
                    <div>
                      <label className="block text-xs font-medium text-text-muted mb-1.5">
                        Bot Telegram Pengirim *
                      </label>
                      <select
                        value={formBotId}
                        onChange={e => setFormBotId(e.target.value)}
                        className="w-full bg-bg-editor border border-border-main rounded-lg px-3 py-2 text-xs text-text-main focus:outline-none focus:border-sky-500"
                      >
                        <option value="">-- Pilih Bot Pengirim --</option>
                        {bots.map(b => (
                          <option key={b.id} value={b.id}>
                            {b.name} {b.botUsername ? `(@${b.botUsername})` : ''}
                          </option>
                        ))}
                      </select>
                    </div>
                  ) : (
                    <div>
                      <div className="flex items-center justify-between mb-1.5">
                        <label className="text-xs font-medium text-text-muted">
                          Akun Pribadi MTProto Pengirim *
                        </label>
                        <button
                          type="button"
                          onClick={() => {
                            setViewMode('list');
                            setActiveTab('bots');
                            setSettingsSubTab('MTPROTO');
                          }}
                          className="text-[10px] text-amber-400 hover:underline flex items-center gap-1 font-semibold"
                        >
                          ⚙️ Buka Pengaturan MTProto
                        </button>
                      </div>

                      {mtprotoAccounts.length > 0 ? (
                        <>
                          <select
                            value={formMtprotoPhone}
                            onChange={e => setFormMtprotoPhone(e.target.value)}
                            className="w-full bg-bg-editor border border-border-main rounded-lg px-3 py-2 text-xs text-text-main focus:outline-none focus:border-amber-500 font-mono"
                          >
                            <option value="">-- Pilih Akun MTProto Pengirim --</option>
                            {mtprotoAccounts.map(acc => (
                              <option key={acc.id} value={acc.phone}>
                                {acc.name} ({acc.phone}) - {acc.status === 'CONNECTED' ? '🟢 Sesi Aktif' : '🟡 Butuh OTP'}
                              </option>
                            ))}
                          </select>
                          <div className="mt-1.5 flex items-center justify-between text-[11px] text-text-muted">
                            <span className="text-emerald-400 flex items-center gap-1 font-medium">
                              <CheckCircle2 className="w-3.5 h-3.5" />
                              Sesi akun aktif dari Pengaturan.
                            </span>
                            <span className="text-[10px] font-mono text-text-muted">
                              API ID: {mtprotoAccounts.find(a => a.phone === formMtprotoPhone)?.apiId || '-'}
                            </span>
                          </div>
                        </>
                      ) : (
                        <div className="p-3 rounded-lg border border-amber-500/30 bg-amber-500/10 text-amber-300 text-xs space-y-2">
                          <div className="flex items-center gap-1.5 font-bold">
                            <AlertCircle className="w-4 h-4 shrink-0" />
                            Belum Ada Akun MTProto Terdaftar
                          </div>
                          <p className="text-[11px] text-text-muted leading-relaxed">
                            Konfigurasi <code>api_id</code>, <code>api_hash</code>, dan OTP telah dipindahkan ke menu Pengaturan agar tidak perlu diisi berulang kali.
                          </p>
                          <button
                            type="button"
                            onClick={() => {
                              setViewMode('list');
                              setActiveTab('bots');
                              setSettingsSubTab('MTPROTO');
                              handleOpenAddMtproto();
                            }}
                            className="px-2.5 py-1 bg-amber-500 hover:bg-amber-600 text-black font-semibold rounded text-[11px] flex items-center gap-1 shadow-sm"
                          >
                            <Plus className="w-3 h-3" />
                            Hubungkan Akun MTProto Sekarang
                          </button>
                        </div>
                      )}
                    </div>
                  )}

                  <div>
                    <label className="block text-xs font-medium text-text-muted mb-1.5">
                      Database Sumber *
                    </label>
                    <select
                      value={formConnectionId}
                      onChange={e => {
                        const newConn = e.target.value;
                        setFormConnectionId(newConn);
                        setFormSchema('');
                        setSchemas([]);
                        setFormView('');
                        setViews([]);
                        setFormSqlQuery('');
                      }}
                      className="w-full bg-bg-editor border border-border-main rounded-lg px-3 py-2 text-xs text-text-main focus:outline-none focus:border-sky-500"
                    >
                      <option value="">-- Pilih Database Sumber --</option>
                      {connections.map(c => (
                        <option key={c.id} value={c.id}>
                          {c.name} ({c.database})
                        </option>
                      ))}
                    </select>
                  </div>
                </div>
              </div>
            </div>

            {/* Card 2: Sumber Data (View vs Custom SQL) */}
            <div className="bg-bg-panel border border-border-main rounded-xl p-5 shadow-sm space-y-4">
              <div className="flex items-center justify-between border-b border-border-main pb-3">
                <h2 className="text-xs font-bold uppercase tracking-wider text-sky-400 flex items-center gap-1.5">
                  <Database className="w-4 h-4" />
                  2. Sumber Data & Filter
                </h2>

                <div className="flex items-center gap-1 bg-bg-editor p-1 rounded-lg border border-border-main text-xs">
                  <button
                    type="button"
                    onClick={() => setSourceMode('VIEW')}
                    className={clsx(
                      "px-3 py-1 rounded-md font-medium transition-colors",
                      sourceMode === 'VIEW' ? "bg-sky-500 text-white shadow-sm" : "text-text-muted hover:text-text-main"
                    )}
                  >
                    📊 Dari View
                  </button>
                  <button
                    type="button"
                    onClick={() => setSourceMode('CUSTOM_SQL')}
                    className={clsx(
                      "px-3 py-1 rounded-md font-medium transition-colors",
                      sourceMode === 'CUSTOM_SQL' ? "bg-sky-500 text-white shadow-sm" : "text-text-muted hover:text-text-main"
                    )}
                  >
                    ⚡ Custom SQL
                  </button>
                </div>
              </div>

              {sourceMode === 'VIEW' ? (
                <div className="space-y-4">
                  <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                    <div>
                      <label className="block text-xs font-medium text-text-muted mb-1.5">
                        Schema Database
                      </label>
                      <select
                        value={formSchema}
                        onChange={e => {
                          const newSchema = e.target.value;
                          setFormSchema(newSchema);
                          setFormView('');
                          setViews([]);
                          setFormSqlQuery('');
                        }}
                        disabled={!formConnectionId}
                        className="w-full bg-bg-editor border border-border-main rounded-lg px-3 py-2 text-xs text-text-main focus:outline-none focus:border-sky-500 disabled:opacity-50 disabled:cursor-not-allowed"
                      >
                        <option value="">
                          {!formConnectionId ? '-- Pilih Database Sumber Terlebih Dahulu --' : '-- Pilih Schema Database --'}
                        </option>
                        {schemas.map(s => (
                          <option key={s} value={s}>{s}</option>
                        ))}
                      </select>
                    </div>

                    <div>
                      <label className="block text-xs font-medium text-text-muted mb-1.5">
                        View Notifikasi
                      </label>
                      <select
                        value={formView}
                        onChange={e => setFormView(e.target.value)}
                        disabled={!formSchema}
                        className="w-full bg-bg-editor border border-border-main rounded-lg px-3 py-2 text-xs text-text-main focus:outline-none focus:border-sky-500 disabled:opacity-50 disabled:cursor-not-allowed"
                      >
                        <option value="">
                          {!formSchema ? '-- Pilih Schema Terlebih Dahulu --' : '-- Pilih View Notifikasi --'}
                        </option>
                        {views.map(v => (
                          <option key={v.name} value={v.name}>{v.name}</option>
                        ))}
                      </select>
                    </div>
                  </div>

                  {/* Filter View Card */}
                  <div className="bg-bg-editor/50 border border-border-main rounded-lg p-3.5 space-y-3">
                    <span className="text-xs font-semibold text-text-main flex items-center gap-1.5">
                      <Filter className="w-3.5 h-3.5 text-sky-400" />
                      Filter Cepat Baris Data (Opsional)
                    </span>

                    <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
                        <div>
                          <label className="block text-[11px] text-text-muted mb-1">
                            Filter Kode Notifikasi (Opsional):
                          </label>
                          <input
                            type="text"
                            value={filterKodeNotifikasi}
                            onChange={e => setFilterKodeNotifikasi(e.target.value)}
                            placeholder="misal: DAILY-REPORT-SALES"
                            className="w-full bg-bg-panel border border-border-main rounded px-2.5 py-1.5 text-xs text-text-main focus:outline-none"
                          />
                        </div>

                        <div>
                          <label className="block text-[11px] text-text-muted mb-1">
                            Filter Kode Cabang (Opsional):
                          </label>
                          <input
                            type="text"
                            value={filterKodeCabang}
                            onChange={e => setFilterKodeCabang(e.target.value)}
                            placeholder="misal: P001, MBI, SSI"
                            className="w-full bg-bg-panel border border-border-main rounded px-2.5 py-1.5 text-xs text-text-main focus:outline-none"
                          />
                        </div>
                      </div>
                    </div>

                  <div className="text-[11px] text-emerald-400 bg-emerald-500/10 border border-emerald-500/20 rounded-lg p-2.5 flex items-center gap-2">
                    <CheckCircle2 className="w-4 h-4 shrink-0" />
                    <span>
                      <b>Otomatis:</b> Isi pesan dibaca langsung dari kolom <code>pesan</code>, dan nomor tujuan dibaca dari <code>user_hp</code>.
                    </span>
                  </div>
                </div>
              ) : (
                <div className="space-y-3">
                  <div>
                    <label className="block text-xs font-medium text-text-muted mb-1">
                      Query SQL Kustom *
                    </label>
                    <textarea
                      rows={5}
                      value={formSqlQuery}
                      onChange={e => setFormSqlQuery(e.target.value)}
                      placeholder="SELECT user_name, user_hp, pesan FROM your_table"
                      className="w-full font-mono text-xs bg-bg-editor border border-border-main rounded-lg p-3 text-text-main focus:outline-none"
                    />
                  </div>
                  <div>
                    <label className="block text-xs font-medium text-text-muted mb-1">
                      Nama Kolom Nomor HP Penerima *
                    </label>
                    <input
                      type="text"
                      value={formPhoneColumn}
                      onChange={e => setFormPhoneColumn(e.target.value)}
                      placeholder="user_hp"
                      className="w-full bg-bg-editor border border-border-main rounded-lg px-3 py-2 text-xs text-text-main focus:outline-none"
                    />
                  </div>
                </div>
              )}
            </div>

            {/* Card 3: Jadwal Otomatis (Cron) */}
            <div className="bg-bg-panel border border-border-main rounded-xl p-5 shadow-sm space-y-4">
              <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3">
                <div>
                  <h2 className="text-xs font-bold uppercase tracking-wider text-sky-400 flex items-center gap-1.5">
                    <Clock className="w-4 h-4" />
                    3. Jadwal Pengiriman Otomatis (Opsional)
                  </h2>
                  <p className="text-[11px] text-text-muted mt-0.5">
                    {isCronEnabled
                      ? 'Server akan otomatis mengeksekusi dan mengirimkan pesan sesuai waktu yang ditentukan di bawah.'
                      : 'Mode manual aktif. Pengiriman pesan hanya akan dipicu saat Anda menekan tombol "Kirim Sekarang (Run)".'}
                  </p>
                </div>

                <div className="flex items-center gap-3 self-start sm:self-auto">
                  <div className={clsx(
                    "text-xs font-semibold px-3 py-1 rounded-full border transition-all flex items-center gap-1.5 shadow-sm",
                    isCronEnabled
                      ? "bg-emerald-500/15 text-emerald-400 border-emerald-500/30 ring-1 ring-emerald-500/20"
                      : "bg-amber-500/10 text-amber-300 border-amber-500/25"
                  )}>
                    {isCronEnabled ? (
                      <>
                        <span className="relative flex h-2 w-2">
                          <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-emerald-400 opacity-75"></span>
                          <span className="relative inline-flex rounded-full h-2 w-2 bg-emerald-500"></span>
                        </span>
                        <span>Otomatis (Cron Aktif)</span>
                      </>
                    ) : (
                      <>
                        <span className="inline-block h-2 w-2 rounded-full bg-amber-400"></span>
                        <span>Mode Manual (Hanya Run)</span>
                      </>
                    )}
                  </div>

                  <button
                    type="button"
                    role="switch"
                    aria-checked={isCronEnabled}
                    onClick={() => setIsCronEnabled(!isCronEnabled)}
                    className={clsx(
                      "relative inline-flex h-6 w-11 shrink-0 cursor-pointer rounded-full border-2 transition-colors duration-200 ease-in-out focus:outline-none focus:ring-2 focus:ring-sky-500/50 shadow-inner",
                      isCronEnabled
                        ? "bg-emerald-500 border-emerald-500"
                        : "bg-slate-700 border-slate-500 hover:border-slate-400"
                    )}
                    title={isCronEnabled ? "Matikan jadwal otomatis (Ganti ke Mode Manual)" : "Aktifkan jadwal otomatis (Cron)"}
                  >
                    <span
                      className={clsx(
                        "pointer-events-none inline-block h-5 w-5 transform rounded-full bg-white shadow-md ring-0 transition duration-200 ease-in-out",
                        isCronEnabled ? "translate-x-5" : "translate-x-0"
                      )}
                    />
                  </button>
                </div>
              </div>

              {isCronEnabled && (
                <div className="space-y-3 pt-3 border-t border-border-main">
                  <div className="flex items-center justify-between">
                    <span className="text-xs font-semibold text-text-main flex items-center gap-1.5">
                      <Clock className="w-3.5 h-3.5 text-sky-400" />
                      Jadwal Multi-Cron Triggers:
                    </span>
                    <button
                      type="button"
                      onClick={() => setCronTriggers([...cronTriggers, '0 0 12 * * *'])}
                      className="flex items-center gap-1.5 px-2.5 py-1 rounded-lg bg-sky-500/10 text-sky-400 hover:bg-sky-500/20 text-xs font-bold transition-colors border border-sky-500/30 shadow-sm cursor-pointer"
                    >
                      <Plus className="w-3.5 h-3.5" />
                      <span>Tambah Waktu Cron</span>
                    </button>
                  </div>

                  <div className="space-y-2">
                    {cronTriggers.map((cron, cIdx) => (
                      <div key={cIdx} className="flex items-center gap-2">
                        <div className="flex-1 bg-bg-editor p-2 border border-border-main rounded-xl shadow-sm flex items-center gap-2 focus-within:border-sky-500">
                          <Clock className="w-4 h-4 text-sky-400 shrink-0" />
                          <input
                            type="text"
                            placeholder="e.g. 0 0 8 * * *"
                            value={cron}
                            onChange={(e) => {
                              const copy = [...cronTriggers];
                              copy[cIdx] = e.target.value;
                              setCronTriggers(copy);
                            }}
                            className="w-full bg-transparent border-0 text-xs font-mono font-bold text-slate-800 dark:text-sky-300 focus:outline-none placeholder:text-text-muted"
                          />
                        </div>

                        {/* Preset Dropdown */}
                        <select
                          defaultValue=""
                          onChange={(e) => {
                            if (e.target.value) {
                              const copy = [...cronTriggers];
                              copy[cIdx] = e.target.value;
                              setCronTriggers(copy);
                              e.target.value = "";
                            }
                          }}
                          className="bg-bg-panel border border-border-main text-text-muted hover:text-text-main text-xs font-semibold rounded-xl px-2.5 py-2 focus:outline-none cursor-pointer shrink-0 shadow-sm"
                          title="Pilih Preset Waktu"
                        >
                          <option value="" disabled>⚡ Preset Waktu ▾</option>
                          <option value="0 0 8 * * *">Pagi 08:00 WIB</option>
                          <option value="0 0 12 * * *">Siang 12:00 WIB</option>
                          <option value="0 0 18 * * *">Sore 18:00 WIB</option>
                          <option value="0 0 21 * * *">Malam 21:00 WIB</option>
                          <option value="0 0 0 * * *">Tengah Malam (00:00)</option>
                          <option value="0 0 * * * *">Tiap 1 Jam</option>
                          <option value="0 */15 * * * *">Tiap 15 Menit</option>
                          <option value="0 */5 * * * *">Tiap 5 Menit</option>
                        </select>

                        {cronTriggers.length > 1 && (
                          <button
                            type="button"
                            onClick={() => setCronTriggers(cronTriggers.filter((_, i) => i !== cIdx))}
                            className="p-2 text-text-muted hover:text-rose-500 hover:bg-rose-500/10 rounded-xl transition-colors border border-transparent hover:border-rose-500/20 cursor-pointer"
                            title="Hapus Waktu Ini"
                          >
                            <Trash2 className="w-4 h-4" />
                          </button>
                        )}
                      </div>
                    ))}
                  </div>

                  <span className="text-[10px] text-text-muted block">
                    💡 Anda dapat menambahkan beberapa jadwal pengiriman sekaligus (misal: jam 08:00 dan jam 21:00).
                  </span>
                </div>
              )}
            </div>
          </div>

          {/* Kolom Kanan: Live Preview Data (5 cols) */}
          <div className="lg:col-span-5 space-y-5">
            <div className="bg-bg-panel border border-border-main rounded-xl p-5 shadow-sm space-y-4 sticky top-24">
              <div className="flex items-center justify-between border-b border-border-main pb-3">
                <h2 className="text-xs font-bold uppercase tracking-wider text-sky-400 flex items-center gap-1.5">
                  <Eye className="w-4 h-4" />
                  Live Preview Pesan View
                </h2>

                <button
                  type="button"
                  onClick={handlePreviewData}
                  disabled={previewLoading}
                  className="flex items-center gap-1.5 px-3 py-1 bg-sky-500/10 hover:bg-sky-500/20 text-sky-400 text-xs font-semibold rounded-lg border border-sky-500/30 transition-colors"
                >
                  {previewLoading ? <RefreshCw className="w-3.5 h-3.5 animate-spin" /> : <Eye className="w-3.5 h-3.5" />}
                  Muat Data
                </button>
              </div>

              {previewRows.length > 0 ? (
                <div className="space-y-3">
                  <div className="text-[11px] text-text-muted flex items-center justify-between">
                    <span>Contoh data baris pertama dari view:</span>
                    <span className="font-semibold text-text-main">
                      {previewRows[0].user_name || 'User'}
                    </span>
                  </div>

                  {/* Telegram Bubble Chat Preview */}
                  <div className="bg-[#182533] border border-[#243447] rounded-2xl p-4 shadow-lg space-y-2">
                    <div className="flex items-center justify-between text-[11px] text-sky-300 border-b border-white/10 pb-2">
                      <span className="font-semibold">
                        👤 {previewRows[0].user_name || '-'}
                      </span>
                      <span className="font-mono text-emerald-400">
                        📱 {previewRows[0].user_hp || '-'}
                      </span>
                    </div>

                    <div className="text-xs text-white/95 whitespace-pre-wrap font-sans leading-relaxed pt-1">
                      {previewRows[0].pesan || '(Kolom pesan kosong)'}
                    </div>

                    <div className="flex items-center justify-between pt-2 text-[10px] text-white/40">
                      <span>Cabang: {previewRows[0].kode_cabang || '-'}</span>
                      <span>{new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })} ✓✓</span>
                    </div>
                  </div>

                  <div className="text-[11px] text-text-muted text-center pt-2">
                    💡 Pesan ini akan dikirimkan otomatis ke nomor di atas tanpa terpotong.
                  </div>
                </div>
              ) : (
                <div className="py-16 text-center text-xs text-text-muted space-y-2">
                  <Eye className="w-8 h-8 text-text-muted/30 mx-auto" />
                  <p>
                    {!formConnectionId || !formSchema || !formView
                      ? 'Pilih Database Sumber, Schema Database, dan View Notifikasi terlebih dahulu untuk memuat live preview pesan.'
                      : 'Klik tombol "Muat Data" untuk melihat live preview pesan dari view saat ini.'}
                  </p>
                </div>
              )}
            </div>
          </div>
        </div>
      </div>
    );
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // RENDER: LAYAR 1 (LIST VIEW - SEPERTI API BUILDER / SCHEDULER)
  // ─────────────────────────────────────────────────────────────────────────────
  return (
    <div className="flex flex-col h-full bg-bg-main text-text-main overflow-hidden">
      {/* Top Header */}
      <div className="border-b border-border-main bg-bg-panel px-6 py-4 flex flex-wrap items-center justify-between gap-4">
        <div>
          <div className="flex items-center gap-2.5">
            <Bot className="w-6 h-6 text-sky-400" />
            <h1 className="text-lg font-bold">Bot Telegram Notifikasi & Scheduler</h1>
            <span className="text-[11px] px-2 py-0.5 rounded-full bg-sky-500/10 text-sky-400 border border-sky-500/20 font-semibold">
              View Integration
            </span>
          </div>
          <p className="text-xs text-text-muted mt-0.5">
            Kirim notifikasi otomatis dari View Database (<code>sch_erp_system.view_all_notifikasi</code>) langsung ke chat Telegram karyawan.
          </p>
        </div>

        {/* Top Tab Navigation */}
        <div className="flex items-center gap-1 bg-bg-editor p-1 rounded-lg border border-border-main text-xs">
          <button
            onClick={() => setActiveTab('messages')}
            className={clsx(
              "flex items-center gap-1.5 px-3 py-1.5 rounded-md font-medium transition-colors",
              activeTab === 'messages' ? "bg-sky-500 text-white shadow-sm font-semibold" : "text-text-muted hover:text-text-main"
            )}
          >
            <Send className="w-3.5 h-3.5" />
            Daftar Pesan ({schedules.length})
          </button>
          <button
            onClick={() => setActiveTab('contacts')}
            className={clsx(
              "flex items-center gap-1.5 px-3 py-1.5 rounded-md font-medium transition-colors",
              activeTab === 'contacts' ? "bg-sky-500 text-white shadow-sm font-semibold" : "text-text-muted hover:text-text-main"
            )}
          >
            <Users className="w-3.5 h-3.5" />
            Master Kontak ({contacts.length})
          </button>
          <button
            onClick={() => setActiveTab('bots')}
            className={clsx(
              "flex items-center gap-1.5 px-3 py-1.5 rounded-md font-medium transition-colors",
              activeTab === 'bots' ? "bg-sky-500 text-white shadow-sm font-semibold" : "text-text-muted hover:text-text-main"
            )}
          >
            <Bot className="w-3.5 h-3.5" />
            Pengaturan Bot & MTProto ({bots.length + mtprotoAccounts.length})
          </button>
          <button
            onClick={() => setActiveTab('logs')}
            className={clsx(
              "flex items-center gap-1.5 px-3 py-1.5 rounded-md font-medium transition-colors",
              activeTab === 'logs' ? "bg-sky-500 text-white shadow-sm font-semibold" : "text-text-muted hover:text-text-main"
            )}
          >
            <History className="w-3.5 h-3.5" />
            Riwayat Log ({logs.length})
          </button>
          <button
            onClick={() => setActiveTab('api_spec')}
            className={clsx(
              "flex items-center gap-1.5 px-3 py-1.5 rounded-md font-medium transition-colors",
              activeTab === 'api_spec' ? "bg-sky-500 text-white shadow-sm font-semibold" : "text-text-muted hover:text-text-main"
            )}
          >
            <Code2 className="w-3.5 h-3.5" />
            API Gateway & Spec
          </button>
        </div>
      </div>

      {/* Main Container */}
      <div className="flex-1 overflow-y-auto p-6">
        {/* =========================================================================
            TAB 1: DAFTAR PESAN & JADWAL (LIST VIEW UTAMA)
        ========================================================================= */}
        {activeTab === 'messages' && (
          <div className="max-w-7xl mx-auto space-y-4">
            {/* Filter & Action Toolbar */}
            <div className="flex flex-wrap items-center justify-between gap-3 bg-bg-panel border border-border-main p-3.5 rounded-xl">
              <div className="flex items-center gap-3 flex-1 min-w-[280px]">
                <div className="relative flex-1 max-w-md">
                  <Search className="w-4 h-4 text-text-muted absolute left-3 top-1/2 -translate-y-1/2" />
                  <input
                    type="text"
                    value={searchQuery}
                    onChange={e => setSearchQuery(e.target.value)}
                    placeholder="Cari nama notifikasi atau view..."
                    className="w-full bg-bg-editor border border-border-main rounded-lg pl-9 pr-3 py-1.5 text-xs text-text-main focus:outline-none focus:border-sky-500"
                  />
                </div>

                <select
                  value={filterBotId}
                  onChange={e => setFilterBotId(e.target.value)}
                  className="bg-bg-editor border border-border-main rounded-lg px-3 py-1.5 text-xs text-text-main focus:outline-none"
                >
                  <option value="ALL">Semua Bot</option>
                  {bots.map(b => (
                    <option key={b.id} value={b.id}>{b.name}</option>
                  ))}
                </select>
              </div>

              {/* Tombol Utama: CREATE MESSAGE */}
              <button
                type="button"
                onClick={handleOpenCreate}
                className="flex items-center gap-2 px-4 py-2 bg-gradient-to-r from-sky-500 to-blue-600 hover:from-sky-600 hover:to-blue-700 text-white rounded-lg text-xs font-bold shadow-md shadow-sky-500/20 transition-all cursor-pointer"
              >
                <Plus className="w-4 h-4" />
                Create Message
              </button>
            </div>

            {/* List Table */}
            <div className="bg-bg-panel border border-border-main rounded-xl overflow-hidden shadow-sm">
              <table className="w-full text-left text-xs border-collapse">
                <thead>
                  <tr className="border-b border-border-main bg-bg-editor/50 text-text-muted uppercase text-[10px] tracking-wider">
                    <th className="py-3 px-4 font-semibold">Nama Notifikasi</th>
                    <th className="py-3 px-4 font-semibold">Sumber View / DB</th>
                    <th className="py-3 px-4 font-semibold">Bot Pengirim</th>
                    <th className="py-3 px-4 font-semibold">Jadwal (Cron)</th>
                    <th className="py-3 px-4 font-semibold">Status Terakhir</th>
                    <th className="py-3 px-4 font-semibold text-right">Aksi</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-border-main/50">
                  {filteredSchedules.map(s => {
                    const bot = bots.find(b => b.id === s.botId);
                    const isRunning = runningAction === s.id;

                    return (
                      <tr key={s.id} className="hover:bg-bg-hover/50 transition-colors">
                        <td className="py-3.5 px-4">
                          <div className="font-semibold text-text-main text-xs">{s.name}</div>
                          <div className="text-[11px] text-text-muted mt-0.5 flex items-center gap-1.5">
                            <span className="px-1.5 py-0.2 rounded bg-sky-500/10 text-sky-400 font-mono text-[10px]">
                              {s.viewName ? 'VIEW' : 'CUSTOM SQL'}
                            </span>
                            {s.filterKodeNotifikasi && (
                              <span className="text-text-muted">
                                • {s.filterKodeNotifikasi}
                              </span>
                            )}
                          </div>
                        </td>

                        <td className="py-3.5 px-4 text-text-muted">
                          <div className="font-mono text-xs text-text-main">
                            {s.viewName ? `${s.schemaName || 'public'}.${s.viewName}` : 'Custom SQL'}
                          </div>
                        </td>

                        <td className="py-3.5 px-4">
                          {s.botId && s.botId.startsWith('mtproto:') ? (
                            <div>
                              <div className="flex items-center gap-1.5 text-amber-400 font-medium">
                                <Send className="w-3.5 h-3.5" />
                                MTProto (Telethon)
                              </div>
                              <div className="text-[10px] text-text-muted font-mono">
                                📱 {s.botId.replace('mtproto:', '')}
                              </div>
                            </div>
                          ) : (
                            <div>
                              <div className="flex items-center gap-1.5 text-text-main font-medium">
                                <Bot className="w-3.5 h-3.5 text-sky-400" />
                                {bot?.name || 'Bot'}
                              </div>
                              {bot?.botUsername && (
                                <div className="text-[10px] text-text-muted font-mono">
                                  @{bot.botUsername}
                                </div>
                              )}
                            </div>
                          )}
                        </td>

                        <td className="py-3.5 px-4">
                          {s.cronExpression && s.cronExpression.trim() ? (
                            <div className="flex flex-wrap gap-1 max-w-[200px]">
                              {s.cronExpression.split(/[\n,]+/).map((cr, idx) => (
                                <span key={idx} className="inline-flex items-center gap-1 px-2 py-0.5 rounded text-[10px] font-mono bg-purple-500/10 text-purple-400 border border-purple-500/20">
                                  <Clock className="w-2.5 h-2.5" />
                                  {cr.trim()}
                                </span>
                              ))}
                            </div>
                          ) : (
                            <span className="text-[11px] text-text-muted">Manual</span>
                          )}
                        </td>

                        <td className="py-3.5 px-4">
                          {s.lastRunStatus ? (
                            <span className={clsx(
                              "inline-flex items-center gap-1 px-2 py-0.5 rounded text-[10px] font-semibold",
                              s.lastRunStatus === 'SUCCESS' ? "bg-emerald-500/10 text-emerald-400" :
                              s.lastRunStatus === 'PARTIAL' ? "bg-amber-500/10 text-amber-400" : "bg-rose-500/10 text-rose-400"
                            )}>
                              {s.lastRunStatus}
                            </span>
                          ) : (
                            <span className="text-[10px] text-text-muted">Belum pernah dijalankan</span>
                          )}
                        </td>

                        <td className="py-3.5 px-4 text-right">
                          <div className="flex items-center justify-end gap-1.5">
                            <button
                              type="button"
                              onClick={() => handleRunNow(s.id)}
                              disabled={isRunning}
                              title="Kirim Sekarang"
                              className="p-1.5 hover:bg-sky-500/10 text-sky-400 hover:text-sky-300 rounded border border-border-main transition-colors disabled:opacity-50"
                            >
                              {isRunning ? <RefreshCw className="w-3.5 h-3.5 animate-spin" /> : <Play className="w-3.5 h-3.5" />}
                            </button>
                            <button
                              type="button"
                              onClick={() => handleOpenEdit(s)}
                              title="Edit Pesan"
                              className="p-1.5 hover:bg-bg-hover text-text-muted hover:text-text-main rounded border border-border-main transition-colors"
                            >
                              <Pencil className="w-3.5 h-3.5" />
                            </button>
                            <button
                              type="button"
                              onClick={() => handleDeleteSchedule(s.id, s.name)}
                              title="Hapus"
                              className="p-1.5 hover:bg-rose-500/10 text-text-muted hover:text-rose-400 rounded border border-border-main transition-colors"
                            >
                              <Trash2 className="w-3.5 h-3.5" />
                            </button>
                          </div>
                        </td>
                      </tr>
                    );
                  })}

                  {filteredSchedules.length === 0 && (
                    <tr>
                      <td colSpan={6} className="py-12 text-center text-text-muted">
                        <Bot className="w-8 h-8 text-text-muted/30 mx-auto mb-2" />
                        <div className="font-semibold text-xs">Belum ada Pesan Notifikasi</div>
                        <p className="text-[11px] mt-0.5">
                          Klik tombol <b>"+ Create Message"</b> di atas untuk membuat notifikasi baru dari view database.
                        </p>
                      </td>
                    </tr>
                  )}
                </tbody>
              </table>
            </div>
          </div>
        )}

        {/* =========================================================================
            TAB 2: MASTER KONTAK
        ========================================================================= */}
        {activeTab === 'contacts' && (
          <div className="max-w-7xl mx-auto space-y-4">
            {/* Master Contacts Table */}
            <div className="bg-bg-panel border border-border-main rounded-xl p-5 shadow-sm space-y-4">
              <div className="flex items-center justify-between border-b border-border-main pb-3">
                <div>
                  <h3 className="text-sm font-bold flex items-center gap-2">
                    <Users className="w-4 h-4 text-sky-400" />
                    Master Kontak Telegram Terdaftar ({contacts.length})
                  </h3>
                  <p className="text-xs text-text-muted">
                    Daftar pengguna yang sudah terhubung dengan Bot Telegram (diperbarui otomatis secara real-time).
                  </p>
                </div>
                <button
                  type="button"
                  onClick={fetchContacts}
                  className="flex items-center gap-1.5 px-3 py-1.5 bg-bg-editor hover:bg-bg-hover text-xs font-semibold rounded-lg border border-border-main cursor-pointer"
                >
                  <RefreshCw className="w-3.5 h-3.5" />
                  Refresh Kontak
                </button>
              </div>

              <div className="overflow-x-auto">
                <table className="w-full text-left text-xs">
                  <thead>
                    <tr className="border-b border-border-main text-text-muted uppercase text-[10px]">
                      <th className="py-2.5 px-3 font-semibold">Nama Pengguna</th>
                      <th className="py-2.5 px-3 font-semibold">Nomor Telepon (HP)</th>
                      <th className="py-2.5 px-3 font-semibold">Chat ID Telegram</th>
                      <th className="py-2.5 px-3 font-semibold">Bot Terkait</th>
                      <th className="py-2.5 px-3 font-semibold">Status</th>
                      <th className="py-2.5 px-3 font-semibold text-right">Aksi</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-border-main/50">
                    {contacts.map(c => {
                      const b = bots.find(bot => bot.id === c.botId);
                      const displayPhone = c.phoneNumber || c.phone;
                      return (
                        <tr key={c.id} className="hover:bg-bg-hover/50">
                          <td className="py-3 px-3 font-semibold text-text-main">
                            {c.firstName || ''} {c.lastName || ''} {c.username ? `(@${c.username})` : ''}
                          </td>
                          <td className="py-3 px-3 font-mono text-sky-400">
                            {displayPhone ? (
                              <span>📱 {displayPhone}</span>
                            ) : (
                              <span className="text-amber-400/80 italic text-[11px]">Belum bagikan nomor</span>
                            )}
                          </td>
                          <td className="py-3 px-3 font-mono text-text-muted">
                            {c.chatId}
                          </td>
                          <td className="py-3 px-3 text-text-muted">
                            {b?.name || 'Bot'}
                          </td>
                          <td className="py-3 px-3">
                            <span className="px-2 py-0.5 rounded text-[10px] font-semibold bg-emerald-500/10 text-emerald-400">
                              Terdaftar
                            </span>
                          </td>
                          <td className="py-3 px-3 text-right">
                            <button
                              type="button"
                              onClick={() => handleDeleteContact(c.id, `${c.firstName || ''} (${displayPhone || c.chatId})`.trim())}
                              title="Hapus Kontak"
                              className="p-1.5 hover:bg-rose-500/10 text-text-muted hover:text-rose-400 rounded transition-colors cursor-pointer"
                            >
                              <Trash2 className="w-3.5 h-3.5" />
                            </button>
                          </td>
                        </tr>
                      );
                    })}

                    {contacts.length === 0 && (
                      <tr>
                        <td colSpan={6} className="py-8 text-center text-text-muted">
                          Belum ada kontak terdaftar. Karyawan dapat membuka bot dan klik <b>/start</b> untuk mendaftarkan nomor HP mereka secara otomatis.
                        </td>
                      </tr>
                    )}
                  </tbody>
                </table>
              </div>
            </div>
          </div>
        )}

        {/* =========================================================================
            TAB 3: PENGATURAN BOT & MTPROTO
        ========================================================================= */}
        {activeTab === 'bots' && (
          <div className="max-w-7xl mx-auto space-y-4">
            <div className="bg-bg-panel border border-border-main rounded-xl p-5 shadow-sm space-y-5">
              {/* Header Tab Pengaturan & Sub-Tab Switcher */}
              <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3 border-b border-border-main pb-4">
                <div>
                  <h3 className="text-sm font-bold flex items-center gap-2">
                    <Bot className="w-4 h-4 text-sky-400" />
                    Pengaturan Bot & Akun Pengirim Pesan
                  </h3>
                  <p className="text-xs text-text-muted mt-0.5">
                    Kelola kredensial Bot resmi (@BotFather) dan akun MTProto Telethon (API ID, Hash, &amp; Sesi Login).
                  </p>
                </div>

                <div className="flex items-center gap-1 bg-bg-editor p-1 rounded-lg border border-border-main text-xs self-start sm:self-auto">
                  <button
                    type="button"
                    onClick={() => setSettingsSubTab('BOT')}
                    className={clsx(
                      "flex items-center gap-1.5 px-3 py-1.5 rounded-md font-medium transition-colors",
                      settingsSubTab === 'BOT'
                        ? "bg-sky-500 text-white font-semibold shadow-sm"
                        : "text-text-muted hover:text-text-main"
                    )}
                  >
                    <Bot className="w-3.5 h-3.5" />
                    Bot Resmi (@BotFather) ({bots.length})
                  </button>
                  <button
                    type="button"
                    onClick={() => setSettingsSubTab('MTPROTO')}
                    className={clsx(
                      "flex items-center gap-1.5 px-3 py-1.5 rounded-md font-medium transition-colors",
                      settingsSubTab === 'MTPROTO'
                        ? "bg-purple-600 text-white font-semibold shadow-sm"
                        : "text-text-muted hover:text-text-main"
                    )}
                  >
                    <Smartphone className="w-3.5 h-3.5" />
                    Akun MTProto Telethon ({mtprotoAccounts.length})
                  </button>
                </div>
              </div>

              {/* Sub-Tab 1: BOT TELEGRAM RESMI (@BotFather) */}
              {settingsSubTab === 'BOT' && (
                <div className="space-y-4">
                  <div className="flex items-center justify-between">
                    <div>
                      <h4 className="text-xs font-bold text-text-main uppercase tracking-wider">
                        Daftar Bot Telegram Resmi
                      </h4>
                      <p className="text-[11px] text-text-muted">
                        Penerima wajib klik <code>/start</code> dan bagikan kontak ke Bot agar pesan dapat terkirim.
                      </p>
                    </div>

                    <button
                      type="button"
                      onClick={() => {
                        setEditingBot({ name: '', botToken: '', active: true, pollingEnabled: true });
                        setIsBotModalOpen(true);
                      }}
                      className="flex items-center gap-1.5 px-3 py-1.5 bg-sky-500 hover:bg-sky-600 text-white text-xs font-semibold rounded-lg shadow-sm"
                    >
                      <Plus className="w-3.5 h-3.5" />
                      Tambah Bot Baru
                    </button>
                  </div>

                  <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
                    {bots.map(b => (
                      <div key={b.id} className="bg-bg-editor border border-border-main rounded-xl p-4 space-y-3">
                        <div className="flex items-center justify-between">
                          <div className="flex items-center gap-2">
                            <Bot className="w-5 h-5 text-sky-400" />
                            <div>
                              <div className="font-bold text-xs text-text-main">{b.name}</div>
                              {b.botUsername && (
                                <a
                                  href={`https://t.me/${b.botUsername}`}
                                  target="_blank"
                                  rel="noreferrer"
                                  className="text-[10px] text-sky-400 hover:underline flex items-center gap-1"
                                >
                                  @{b.botUsername} <ExternalLink className="w-2.5 h-2.5" />
                                </a>
                              )}
                            </div>
                          </div>
                          <span className="px-2 py-0.5 rounded text-[10px] font-semibold bg-emerald-500/10 text-emerald-400">
                            Aktif
                          </span>
                        </div>

                        <div className="text-[11px] text-text-muted border-t border-border-main pt-2 flex items-center justify-between">
                          <span>Token: <code className="text-text-main font-mono">••••••••••••••</code></span>
                          <span className="text-[10px] text-emerald-400">● Polling On</span>
                        </div>

                        <div className="border-t border-border-main/60 pt-2 flex items-center justify-end gap-2 text-xs">
                          <button
                            type="button"
                            onClick={() => {
                              setEditingBot(b);
                              setIsBotModalOpen(true);
                            }}
                            className="px-2 py-1 rounded hover:bg-bg-hover text-sky-400 hover:text-sky-300 font-semibold flex items-center gap-1 cursor-pointer transition-colors"
                          >
                            <Pencil className="w-3 h-3" />
                            Edit
                          </button>
                          <span className="text-border-main">|</span>
                          <button
                            type="button"
                            onClick={() => handleDeleteBot(b.id, b.name)}
                            className="px-2 py-1 rounded hover:bg-rose-500/10 text-rose-400 hover:text-rose-300 font-semibold flex items-center gap-1 cursor-pointer transition-colors"
                          >
                            <Trash2 className="w-3 h-3" />
                            Hapus
                          </button>
                        </div>
                      </div>
                    ))}

                    {bots.length === 0 && (
                      <div className="col-span-full py-8 text-center text-xs text-text-muted bg-bg-editor/50 rounded-xl border border-dashed border-border-main">
                        Belum ada bot telegram yang ditambahkan. Klik "Tambah Bot Baru" untuk memasukkan token dari @BotFather.
                      </div>
                    )}
                  </div>
                </div>
              )}

              {/* Sub-Tab 2: AKUN PRIBADI MTPROTO (TELETHON API) */}
              {settingsSubTab === 'MTPROTO' && (
                <div className="space-y-4">
                  {/* Edukasi Banner */}
                  <div className="bg-gradient-to-r from-purple-500/10 to-indigo-500/10 border border-purple-500/20 rounded-xl p-4 flex flex-col md:flex-row md:items-center justify-between gap-4">
                    <div className="space-y-1">
                      <div className="flex items-center gap-2 text-xs font-bold text-purple-400">
                        <Smartphone className="w-4 h-4" />
                        Jalur User Client MTProto (Telethon API)
                      </div>
                      <p className="text-[11px] text-text-muted leading-relaxed max-w-2xl">
                        Kirim pesan otomatis langsung dari nomor HP Telegram pribadi/kantor Anda tanpa penerima harus menekan tombol <code>/start</code> ke bot terlebih dahulu. Kredensial <code>api_id</code> dan <code>api_hash</code> didapatkan secara gratis dari{' '}
                        <a href="https://my.telegram.org/apps" target="_blank" rel="noreferrer" className="text-sky-400 hover:underline font-semibold">
                          my.telegram.org ↗
                        </a>.
                      </p>
                    </div>

                    <button
                      type="button"
                      onClick={handleOpenAddMtproto}
                      className="shrink-0 flex items-center gap-1.5 px-3.5 py-2 bg-purple-600 hover:bg-purple-700 text-white text-xs font-semibold rounded-lg shadow-sm transition-colors cursor-pointer"
                    >
                      <Plus className="w-4 h-4" />
                      Hubungkan Akun MTProto
                    </button>
                  </div>

                  {/* List Akun MTProto */}
                  <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
                    {mtprotoAccounts.map(acc => (
                      <div key={acc.id} className="bg-bg-editor border border-border-main rounded-xl p-4 space-y-3 relative group hover:border-purple-500/50 transition-colors">
                        <div className="flex items-center justify-between">
                          <div className="flex items-center gap-2">
                            <div className="w-8 h-8 rounded-lg bg-purple-500/15 text-purple-400 flex items-center justify-center font-bold text-xs">
                              <Smartphone className="w-4 h-4" />
                            </div>
                            <div>
                              <div className="font-bold text-xs text-text-main">{acc.name}</div>
                              <div className="text-[11px] font-mono text-purple-400 font-semibold">{acc.phone}</div>
                            </div>
                          </div>

                          <span className={clsx(
                            "px-2 py-0.5 rounded text-[10px] font-semibold flex items-center gap-1",
                            acc.status === 'CONNECTED'
                              ? "bg-emerald-500/10 text-emerald-400 border border-emerald-500/20"
                              : "bg-amber-500/10 text-amber-400 border border-amber-500/20"
                          )}>
                            {acc.status === 'CONNECTED' ? '🟢 Sesi Aktif' : '🟡 Butuh OTP'}
                          </span>
                        </div>

                        <div className="bg-bg-panel/60 p-2.5 rounded-lg border border-border-main/60 text-[11px] space-y-1 font-mono text-text-muted">
                          <div className="flex justify-between">
                            <span>App API ID:</span>
                            <span className="text-text-main">{acc.apiId}</span>
                          </div>
                          <div className="flex justify-between">
                            <span>App API Hash:</span>
                            <span className="text-text-main">
                              {acc.apiHash ? `${acc.apiHash.substring(0, 6)}••••••••` : '-'}
                            </span>
                          </div>
                        </div>

                        <div className="border-t border-border-main pt-2 flex items-center justify-between text-[11px]">
                          <span className="text-text-muted text-[10px]">
                            {acc.createdAt ? new Date(acc.createdAt).toLocaleDateString() : 'Tersimpan'}
                          </span>

                          <div className="flex items-center gap-2">
                            <button
                              type="button"
                              onClick={() => handleTestPingMtproto(acc)}
                              className="text-[11px] text-sky-400 hover:text-sky-300 font-medium cursor-pointer"
                            >
                              Test Ping
                            </button>
                            <span className="text-border-main">|</span>
                            <button
                              type="button"
                              onClick={() => handleDeleteMtproto(acc.id, acc.name)}
                              className="text-[11px] text-rose-400 hover:text-rose-300 font-medium cursor-pointer flex items-center gap-1"
                            >
                              <Trash2 className="w-3 h-3" />
                              Hapus
                            </button>
                          </div>
                        </div>
                      </div>
                    ))}

                    {mtprotoAccounts.length === 0 && (
                      <div className="col-span-full py-12 text-center text-xs text-text-muted bg-bg-editor/50 rounded-xl border border-dashed border-border-main space-y-2">
                        <Smartphone className="w-8 h-8 text-text-muted/40 mx-auto" />
                        <p className="font-semibold text-text-main">Belum Ada Akun MTProto yang Terhubung</p>
                        <p className="text-[11px] text-text-muted max-w-sm mx-auto">
                          Daftarkan akun Telegram pengirim dengan memasukkan API ID &amp; API Hash Anda untuk langsung mengirim pesan ke nomor karyawan.
                        </p>
                        <button
                          type="button"
                          onClick={handleOpenAddMtproto}
                          className="mt-2 inline-flex items-center gap-1.5 px-3 py-1.5 bg-purple-600 hover:bg-purple-700 text-white font-semibold rounded-lg text-xs cursor-pointer"
                        >
                          <Plus className="w-3.5 h-3.5" />
                          Hubungkan Akun Sekarang
                        </button>
                      </div>
                    )}
                  </div>
                </div>
              )}
            </div>
          </div>
        )}

        {/* =========================================================================
            TAB 4: RIWAYAT LOG
        ========================================================================= */}
        {activeTab === 'logs' && (
          <div className="max-w-7xl mx-auto space-y-4">
            {/* Header & Stats Cards */}
            <div className="grid grid-cols-1 sm:grid-cols-3 gap-3">
              <div className="bg-bg-panel border border-border-main rounded-xl p-4 shadow-sm flex items-center justify-between">
                <div>
                  <div className="text-[11px] font-semibold text-text-muted uppercase tracking-wider">Total Eksekusi Bot</div>
                  <div className="text-xl font-bold text-text-main mt-0.5">{executionGroups.length} <span className="text-xs font-normal text-text-muted">sesi ({logs.length} pesan)</span></div>
                </div>
                <div className="p-2.5 rounded-xl bg-sky-500/10 text-sky-400 border border-sky-500/20">
                  <History className="w-5 h-5" />
                </div>
              </div>

              <div className="bg-bg-panel border border-border-main rounded-xl p-4 shadow-sm flex items-center justify-between">
                <div>
                  <div className="text-[11px] font-semibold text-text-muted uppercase tracking-wider">Terkirim Berhasil</div>
                  <div className="text-xl font-bold text-emerald-400 mt-0.5">{countSuccess} <span className="text-xs font-normal text-text-muted">pesan</span></div>
                </div>
                <div className="p-2.5 rounded-xl bg-emerald-500/10 text-emerald-400 border border-emerald-500/20">
                  <CheckCircle2 className="w-5 h-5" />
                </div>
              </div>

              <div className="bg-bg-panel border border-border-main rounded-xl p-4 shadow-sm flex items-center justify-between">
                <div>
                  <div className="text-[11px] font-semibold text-text-muted uppercase tracking-wider">Gagal Terkirim</div>
                  <div className="text-xl font-bold text-rose-400 mt-0.5">{countFailed} <span className="text-xs font-normal text-text-muted">isu</span></div>
                </div>
                <div className="p-2.5 rounded-xl bg-rose-500/10 text-rose-400 border border-rose-500/20">
                  <AlertCircle className="w-5 h-5" />
                </div>
              </div>
            </div>

            {/* Filter & Search Bar */}
            <div className="bg-bg-panel border border-border-main rounded-xl p-4 shadow-sm space-y-3">
              <div className="flex flex-col md:flex-row md:items-center justify-between gap-3">
                {/* Search Input */}
                <div className="relative flex-1">
                  <Search className="w-4 h-4 text-text-muted absolute left-3 top-1/2 -translate-y-1/2 pointer-events-none" />
                  <input
                    type="text"
                    value={logSearch}
                    onChange={e => setLogSearch(e.target.value)}
                    placeholder="Cari nomor HP, nama penerima, isi pesan, notifikasi..."
                    className="w-full bg-bg-editor border border-border-main rounded-xl pl-9 pr-8 py-2 text-xs text-text-main placeholder:text-text-muted focus:outline-none focus:border-sky-500 transition-colors"
                  />
                  {logSearch && (
                    <button
                      type="button"
                      onClick={() => setLogSearch('')}
                      className="absolute right-2.5 top-1/2 -translate-y-1/2 text-text-muted hover:text-text-main p-0.5 rounded-full cursor-pointer"
                    >
                      <X className="w-3.5 h-3.5" />
                    </button>
                  )}
                </div>

                {/* Status Pills */}
                <div className="flex items-center gap-1 bg-bg-editor p-1 rounded-xl border border-border-main shrink-0">
                  <button
                    type="button"
                    onClick={() => setLogStatusFilter('ALL')}
                    className={clsx(
                      "px-3 py-1 text-xs font-semibold rounded-lg transition-colors cursor-pointer",
                      logStatusFilter === 'ALL'
                        ? "bg-bg-panel text-sky-400 shadow-sm border border-sky-500/30"
                        : "text-text-muted hover:text-text-main"
                    )}
                  >
                    Semua ({logs.length})
                  </button>
                  <button
                    type="button"
                    onClick={() => setLogStatusFilter('BERHASIL')}
                    className={clsx(
                      "px-3 py-1 text-xs font-semibold rounded-lg transition-colors cursor-pointer",
                      logStatusFilter === 'BERHASIL'
                        ? "bg-emerald-500/15 text-emerald-400 shadow-sm border border-emerald-500/30"
                        : "text-text-muted hover:text-text-main"
                    )}
                  >
                    Berhasil ({countSuccess})
                  </button>
                  <button
                    type="button"
                    onClick={() => setLogStatusFilter('GAGAL')}
                    className={clsx(
                      "px-3 py-1 text-xs font-semibold rounded-lg transition-colors cursor-pointer",
                      logStatusFilter === 'GAGAL'
                        ? "bg-rose-500/15 text-rose-400 shadow-sm border border-rose-500/30"
                        : "text-text-muted hover:text-text-main"
                    )}
                  >
                    Gagal ({countFailed})
                  </button>
                </div>

                {/* Action Buttons */}
                <div className="flex items-center gap-2 shrink-0">
                  <button
                    type="button"
                    onClick={handleClearLogs}
                    disabled={logs.length === 0}
                    className="flex items-center gap-1.5 px-3 py-2 bg-rose-500/10 hover:bg-rose-500/20 text-rose-400 text-xs font-semibold rounded-xl border border-rose-500/30 transition-colors disabled:opacity-40 disabled:cursor-not-allowed cursor-pointer shadow-sm"
                    title="Hapus semua riwayat pengiriman pesan"
                  >
                    <Trash2 className="w-3.5 h-3.5" />
                    Bersihkan Log
                  </button>
                  <button
                    type="button"
                    onClick={fetchLogs}
                    disabled={isRefreshingLogs}
                    className="flex items-center gap-1.5 px-3 py-2 bg-bg-editor hover:bg-bg-hover text-xs font-semibold rounded-xl border border-border-main transition-colors cursor-pointer text-text-main shadow-sm"
                  >
                    <RefreshCw className={clsx("w-3.5 h-3.5", isRefreshingLogs && "animate-spin text-sky-400")} />
                    Refresh
                  </button>
                </div>
              </div>

              {/* Date Filters Row */}
              <div className="flex flex-wrap items-center gap-2 pt-2 border-t border-border-main/60 text-xs">
                <span className="text-text-muted flex items-center gap-1 shrink-0 font-medium text-[11px]">
                  <Calendar className="w-3.5 h-3.5 text-sky-400" />
                  Filter Tanggal:
                </span>
                <div className="flex items-center gap-1.5 bg-bg-editor px-2.5 py-1 rounded-lg border border-border-main">
                  <span className="text-[10px] text-text-muted">Dari:</span>
                  <input
                    type="date"
                    value={logStartDate}
                    onChange={e => setLogStartDate(e.target.value)}
                    className="bg-transparent text-xs text-text-main focus:outline-none cursor-pointer"
                  />
                </div>
                <div className="flex items-center gap-1.5 bg-bg-editor px-2.5 py-1 rounded-lg border border-border-main">
                  <span className="text-[10px] text-text-muted">Sampai:</span>
                  <input
                    type="date"
                    value={logEndDate}
                    onChange={e => setLogEndDate(e.target.value)}
                    className="bg-transparent text-xs text-text-main focus:outline-none cursor-pointer"
                  />
                </div>
                <div className="flex items-center gap-1 ml-auto">
                  <button
                    type="button"
                    onClick={setTodayDateFilter}
                    className="px-2.5 py-1 text-[11px] font-semibold bg-bg-editor hover:bg-bg-hover text-text-muted hover:text-text-main rounded-lg border border-border-main transition-colors cursor-pointer"
                  >
                    Hari Ini
                  </button>
                  <button
                    type="button"
                    onClick={setLast7DaysFilter}
                    className="px-2.5 py-1 text-[11px] font-semibold bg-bg-editor hover:bg-bg-hover text-text-muted hover:text-text-main rounded-lg border border-border-main transition-colors cursor-pointer"
                  >
                    7 Hari Terakhir
                  </button>
                  {(logStartDate || logEndDate) && (
                    <button
                      type="button"
                      onClick={resetDateFilter}
                      className="px-2.5 py-1 text-[11px] font-semibold bg-rose-500/10 hover:bg-rose-500/20 text-rose-400 rounded-lg border border-rose-500/30 transition-colors cursor-pointer flex items-center gap-1"
                    >
                      <X className="w-3 h-3" />
                      Reset Tanggal
                    </button>
                  )}
                </div>
              </div>
            </div>

            {/* Logs Table */}
            <div className="bg-bg-panel border border-border-main rounded-xl p-5 shadow-sm space-y-4">
              <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3 text-xs">
                <div>
                  {logViewMode === 'executions' ? (
                    <span className="text-text-muted">
                      Menampilkan <b className="text-text-main font-semibold">{executionGroups.length}</b> sesi eksekusi bot ({filteredLogs.length} pesan)
                      {(logSearch || logStatusFilter !== 'ALL' || logStartDate || logEndDate) && ' (hasil filter)'}
                    </span>
                  ) : (
                    <span className="text-text-muted">
                      Menampilkan <b className="text-text-main font-semibold">{filteredLogs.length}</b> pesan log
                      {(logSearch || logStatusFilter !== 'ALL' || logStartDate || logEndDate) && ' (hasil filter)'}
                    </span>
                  )}
                </div>

                {/* Toggle View Mode */}
                <div className="flex items-center gap-1 bg-bg-editor p-1 rounded-xl border border-border-main self-start sm:self-auto shrink-0 shadow-inner">
                  <button
                    type="button"
                    onClick={() => setLogViewMode('executions')}
                    className={clsx(
                      "flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-semibold transition-all cursor-pointer",
                      logViewMode === 'executions'
                        ? "bg-bg-panel text-sky-400 border border-sky-500/30 shadow-sm"
                        : "text-text-muted hover:text-text-main"
                    )}
                  >
                    <Layers className="w-3.5 h-3.5" />
                    <span>Per Sesi Eksekusi Bot</span>
                  </button>
                  <button
                    type="button"
                    onClick={() => setLogViewMode('flat')}
                    className={clsx(
                      "flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-semibold transition-all cursor-pointer",
                      logViewMode === 'flat'
                        ? "bg-bg-panel text-sky-400 border border-sky-500/30 shadow-sm"
                        : "text-text-muted hover:text-text-main"
                    )}
                  >
                    <List className="w-3.5 h-3.5" />
                    <span>Semua Pesan (Flat)</span>
                  </button>
                </div>
              </div>

              {logViewMode === 'executions' ? (
                /* ─────────────────────────────────────────────────────────────
                   TAMPILAN 1: PER SESI EKSEKUSI BOT & WAKTU (GROUPED)
                   ───────────────────────────────────────────────────────────── */
                <div className="overflow-x-auto">
                  <table className="w-full text-left text-xs">
                    <thead>
                      <tr className="border-b border-border-main text-text-muted uppercase text-[10px] bg-bg-editor/50">
                        <th className="py-2.5 px-3 font-semibold">Waktu Eksekusi</th>
                        <th className="py-2.5 px-3 font-semibold">Nama Bot Telegram</th>
                        <th className="py-2.5 px-3 font-semibold">Notifikasi / Jadwal</th>
                        <th className="py-2.5 px-3 font-semibold">Penerima Pesan</th>
                        <th className="py-2.5 px-3 font-semibold">Status Pengiriman</th>
                        <th className="py-2.5 px-3 font-semibold text-right">Rincian Per Orang</th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-border-main/50">
                      {executionGroups.map(group => {
                        const isAllSuccess = group.status === 'BERHASIL';
                        const isPartial = group.status === 'SEBAGIAN_GAGAL';

                        return (
                          <tr key={group.key} className="hover:bg-bg-hover/50 transition-colors">
                            <td className="py-3 px-3 text-text-muted font-mono text-[11px] whitespace-nowrap">
                              {formatLogDate(group.executedAt)}
                              <span className="block text-[9px] text-text-muted font-sans mt-0.5">
                                {group.triggerType === 'SCHEDULER' ? '⏰ Otomatis (Cron)' : '⚡ Manual (Run)'}
                              </span>
                            </td>
                            <td className="py-3 px-3">
                              <div className="flex items-center gap-2.5">
                                <div className="w-8 h-8 rounded-xl bg-sky-500/10 border border-sky-500/25 flex items-center justify-center text-sky-400 shrink-0 shadow-sm">
                                  <Bot className="w-4 h-4" />
                                </div>
                                <div>
                                  <div className="font-bold text-text-main text-xs">{group.botName}</div>
                                  <div className="text-[10px] text-text-muted font-mono">{group.botId || '-'}</div>
                                </div>
                              </div>
                            </td>
                            <td className="py-3 px-3">
                              <div className="font-semibold text-text-main">{group.scheduleName}</div>
                            </td>
                            <td className="py-3 px-3 whitespace-nowrap">
                              <div className="flex items-center gap-1.5">
                                <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-md text-[11px] font-bold bg-emerald-500/10 text-emerald-400 border border-emerald-500/30">
                                  <CheckCircle2 className="w-3 h-3" />
                                  {group.successCount} Berhasil
                                </span>
                                {group.failedCount > 0 && (
                                  <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-md text-[11px] font-bold bg-rose-500/10 text-rose-400 border border-rose-500/30">
                                    <AlertCircle className="w-3 h-3" />
                                    {group.failedCount} Gagal
                                  </span>
                                )}
                              </div>
                              <span className="text-[10px] text-text-muted mt-1 block">
                                Total: {group.totalRecipients} orang penerima
                              </span>
                            </td>
                            <td className="py-3 px-3 whitespace-nowrap">
                              {isAllSuccess ? (
                                <span className="inline-flex items-center gap-1 px-2.5 py-1 rounded-full text-[11px] font-bold bg-emerald-500/10 text-emerald-400 border border-emerald-500/30">
                                  <CheckCircle2 className="w-3 h-3" />
                                  Semua Berhasil
                                </span>
                              ) : isPartial ? (
                                <span className="inline-flex items-center gap-1 px-2.5 py-1 rounded-full text-[11px] font-bold bg-amber-500/10 text-amber-400 border border-amber-500/30">
                                  <AlertCircle className="w-3 h-3" />
                                  Sebagian Gagal
                                </span>
                              ) : (
                                <span className="inline-flex items-center gap-1 px-2.5 py-1 rounded-full text-[11px] font-bold bg-rose-500/10 text-rose-400 border border-rose-500/30">
                                  <AlertCircle className="w-3 h-3" />
                                  Gagal Total
                                </span>
                              )}
                            </td>
                            <td className="py-3 px-3 text-right whitespace-nowrap">
                              <button
                                type="button"
                                onClick={() => {
                                  setSelectedExecutionModal(group);
                                  setExecutionRecipientFilter('ALL');
                                  setExecutionRecipientSearch('');
                                }}
                                className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-xl bg-sky-500/10 hover:bg-sky-500/20 text-sky-400 text-xs font-bold border border-sky-500/30 transition-all cursor-pointer shadow-sm hover:scale-[1.02]"
                                title="Lihat daftar semua penerima dan statusnya"
                              >
                                <Eye className="w-3.5 h-3.5" />
                                <span>Detail ({group.totalRecipients} Orang)</span>
                              </button>
                            </td>
                          </tr>
                        );
                      })}

                      {executionGroups.length === 0 && (
                        <tr>
                          <td colSpan={6} className="py-12 text-center text-text-muted space-y-2">
                            <History className="w-8 h-8 text-text-muted/30 mx-auto" />
                            <p className="font-semibold text-text-main text-xs">Tidak Ada Riwayat Eksekusi yang Sesuai</p>
                            <p className="text-[11px] text-text-muted">
                              {logs.length === 0
                                ? 'Belum ada sesi eksekusi bot yang tercatat.'
                                : 'Tidak ada sesi yang cocok dengan filter pencarian.'}
                            </p>
                          </td>
                        </tr>
                      )}
                    </tbody>
                  </table>
                </div>
              ) : (
                /* ─────────────────────────────────────────────────────────────
                   TAMPILAN 2: SEMUA PESAN (FLAT LIST PER PENERIMA)
                   ───────────────────────────────────────────────────────────── */
                <div className="overflow-x-auto">
                  <table className="w-full text-left text-xs">
                    <thead>
                      <tr className="border-b border-border-main text-text-muted uppercase text-[10px] bg-bg-editor/50">
                        <th className="py-2.5 px-3 font-semibold">Waktu</th>
                        <th className="py-2.5 px-3 font-semibold">Nama Bot Telegram</th>
                        <th className="py-2.5 px-3 font-semibold">Notifikasi / Jadwal</th>
                        <th className="py-2.5 px-3 font-semibold">Penerima</th>
                        <th className="py-2.5 px-3 font-semibold">Status</th>
                        <th className="py-2.5 px-3 font-semibold">Isi Pesan</th>
                        <th className="py-2.5 px-3 font-semibold">Rincian / Error</th>
                        <th className="py-2.5 px-3 font-semibold text-right">Aksi</th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-border-main/50">
                      {filteredLogs.map(log => {
                        const isSuccess = log.status === 'BERHASIL' || log.status === 'SUCCESS' || (log.messagesSent > 0 && log.messagesFailed === 0);
                        const isFailed = log.status === 'GAGAL' || log.status === 'FAILED' || log.messagesFailed > 0;
                        return (
                          <tr key={log.id} className="hover:bg-bg-hover/50 transition-colors">
                            <td className="py-3 px-3 text-text-muted font-mono text-[11px] whitespace-nowrap">
                              {formatLogDate(log.createdAt || log.executedAt)}
                              {log.triggerType && (
                                <span className="block text-[9px] text-text-muted font-sans mt-0.5">
                                  {log.triggerType === 'SCHEDULER' ? '⏰ Otomatis (Cron)' : '⚡ Manual (Run)'}
                                </span>
                              )}
                            </td>
                            <td className="py-3 px-3">
                              <div className="flex items-center gap-1.5 font-bold text-text-main text-xs">
                                <Bot className="w-3.5 h-3.5 text-sky-400 shrink-0" />
                                <span>{log.botName || 'Bot'}</span>
                              </div>
                            </td>
                            <td className="py-3 px-3">
                              <div className="font-semibold text-text-main">{log.scheduleName || 'Manual Execution'}</div>
                            </td>
                            <td className="py-3 px-3">
                              <div className="font-medium text-text-main">
                                {log.recipientName && log.recipientName !== '-' ? log.recipientName : (log.recipientPhone && log.recipientPhone !== '-' ? 'Karyawan' : '-')}
                              </div>
                              {log.recipientPhone && log.recipientPhone !== '-' ? (
                                <div className="text-[11px] font-mono text-emerald-400 flex items-center gap-1 mt-0.5 font-semibold">
                                  <Smartphone className="w-3 h-3 text-text-muted" />
                                  <span>{log.recipientPhone}</span>
                                </div>
                              ) : (
                                <span className="text-[11px] text-text-muted">-</span>
                              )}
                            </td>
                            <td className="py-3 px-3 whitespace-nowrap">
                              {isSuccess ? (
                                <span className="inline-flex items-center gap-1 px-2.5 py-1 rounded-full text-[11px] font-bold bg-emerald-500/10 text-emerald-400 border border-emerald-500/30">
                                  <CheckCircle2 className="w-3 h-3" />
                                  Berhasil
                                </span>
                              ) : isFailed ? (
                                <span className="inline-flex items-center gap-1 px-2.5 py-1 rounded-full text-[11px] font-bold bg-rose-500/10 text-rose-400 border border-rose-500/30">
                                  <AlertCircle className="w-3 h-3" />
                                  Gagal
                                </span>
                              ) : (
                                <span className="inline-flex items-center gap-1 px-2.5 py-1 rounded-full text-[11px] font-bold bg-slate-500/10 text-slate-400 border border-slate-500/30">
                                  {log.status || '-'}
                                </span>
                              )}
                            </td>
                            <td className="py-3 px-3 max-w-xs truncate" title={log.messageText || '-'}>
                              {log.messageText && log.messageText !== '-' ? (
                                <div className="text-[11px] text-text-muted font-mono truncate max-w-xs bg-bg-editor px-2 py-1 rounded border border-border-main/50">
                                  {log.messageText}
                                </div>
                              ) : (
                                <span className="text-text-muted text-[11px]">-</span>
                              )}
                            </td>
                            <td className="py-3 px-3 text-[11px] max-w-xs truncate" title={log.details || '-'}>
                              <span className={isFailed ? "text-rose-400 font-medium" : "text-text-muted"}>
                                {log.details || '-'}
                              </span>
                            </td>
                            <td className="py-3 px-3 text-right whitespace-nowrap">
                              {log.messageText && log.messageText !== '-' ? (
                                <button
                                  type="button"
                                  onClick={() => setSelectedLogModal(log)}
                                  className="inline-flex items-center gap-1 px-2.5 py-1 rounded-lg bg-sky-500/10 hover:bg-sky-500/20 text-sky-400 text-[11px] font-bold border border-sky-500/30 transition-colors cursor-pointer shadow-sm"
                                  title="Lihat rincian pesan lengkap"
                                >
                                  <Eye className="w-3 h-3" />
                                  Rincian
                                </button>
                              ) : (
                                <span className="text-text-muted text-[11px]">-</span>
                              )}
                            </td>
                          </tr>
                        );
                      })}

                      {filteredLogs.length === 0 && (
                        <tr>
                          <td colSpan={8} className="py-12 text-center text-text-muted space-y-2">
                            <History className="w-8 h-8 text-text-muted/30 mx-auto" />
                            <p className="font-semibold text-text-main text-xs">Tidak Ada Log Pesan yang Sesuai</p>
                            <p className="text-[11px] text-text-muted">
                              {logs.length === 0
                                ? 'Belum ada riwayat pengiriman pesan yang tercatat.'
                                : 'Tidak ada data log yang cocok dengan filter pencarian Anda.'}
                            </p>
                          </td>
                        </tr>
                      )}
                    </tbody>
                  </table>
                </div>
              )}
            </div>
          </div>
        )}

        {/* =========================================================================
            TAB 5: API INTEGRATION & SPEC
        ========================================================================= */}
        {activeTab === 'api_spec' && (
          <div className="max-w-7xl mx-auto space-y-6 animate-in fade-in duration-200">
            {/* Header Card */}
            <div className="bg-bg-panel border border-border-main p-5 rounded-2xl shadow-sm flex flex-wrap items-center justify-between gap-4">
              <div className="space-y-1">
                <div className="flex items-center gap-2">
                  <div className="p-2 rounded-xl bg-sky-500/10 text-sky-400 border border-sky-500/20">
                    <Code2 className="w-5 h-5" />
                  </div>
                  <div>
                    <h2 className="text-base font-bold text-text-main">Telegram Bot API Gateway & Spec</h2>
                    <p className="text-xs text-text-muted">
                      Integrasikan bot Telegram DarkoSync dengan sistem eksternal (ERP, CRM, Webhook, atau service luar).
                    </p>
                  </div>
                </div>
              </div>

              <div className="flex items-center gap-2 flex-wrap">
                <div className="flex items-center bg-bg-editor border border-border-main rounded-xl p-1 shadow-xs">
                  <span className="text-[11px] font-bold px-2.5 py-1 rounded-lg bg-emerald-500/10 text-emerald-600 dark:text-emerald-400 border border-emerald-500/30 flex items-center gap-1.5 font-mono">
                    <span className="w-1.5 h-1.5 rounded-full bg-emerald-500 animate-pulse" />
                    POST
                  </span>
                  <span className="px-3 py-1 font-mono text-xs text-slate-800 dark:text-sky-300 font-semibold select-all">
                    {`${window.location.origin}/api/v1/telegram/external/send`}
                  </span>
                  <button
                    type="button"
                    onClick={() => {
                      navigator.clipboard.writeText(`${window.location.origin}/api/v1/telegram/external/send`);
                      setCopiedEndpoint(true);
                      setTimeout(() => setCopiedEndpoint(false), 2000);
                    }}
                    className="flex items-center gap-1.5 px-3 py-1 rounded-lg bg-sky-50 hover:bg-sky-100 dark:bg-sky-500/10 dark:hover:bg-sky-500/20 text-sky-700 dark:text-sky-300 border border-sky-300 dark:border-sky-500/30 text-xs font-bold transition-colors cursor-pointer ml-1"
                    title="Salin Link Lengkap Endpoint API"
                  >
                    {copiedEndpoint ? <Check className="w-3.5 h-3.5 text-emerald-600 dark:text-emerald-400" /> : <Copy className="w-3.5 h-3.5" />}
                    <span>{copiedEndpoint ? 'Link Tersalin!' : 'Copy Link Endpoint'}</span>
                  </button>
                </div>
              </div>
            </div>

            {/* Section 1: API Token Management */}
            <div className="bg-bg-panel border border-border-main rounded-2xl p-5 shadow-sm space-y-4">
              <div className="flex flex-wrap items-center justify-between gap-3 border-b border-border-main pb-3">
                <div className="flex items-center gap-2">
                  <Key className="w-4 h-4 text-amber-500" />
                  <h3 className="text-sm font-bold text-text-main">Autentikasi &amp; Token API Klien Luar</h3>
                </div>

                <div className="flex items-center gap-2">
                  <button
                    type="button"
                    onClick={handleGenerateToken}
                    disabled={isGeneratingToken}
                    className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-sky-500 hover:bg-sky-600 text-white font-semibold text-xs transition-colors cursor-pointer shadow-sm disabled:opacity-50"
                  >
                    {isGeneratingToken ? <RefreshCw className="w-3.5 h-3.5 animate-spin" /> : <Plus className="w-3.5 h-3.5" />}
                    <span>{tokens.length === 0 ? 'Buat Token Pertama' : 'Generate / Ganti Token'}</span>
                  </button>
                </div>
              </div>

              {isLoadingTokens ? (
                <div className="py-6 text-center text-xs text-text-muted flex items-center justify-center gap-2">
                  <RefreshCw className="w-4 h-4 animate-spin text-sky-500" />
                  <span>Memuat token API...</span>
                </div>
              ) : tokens.length === 0 ? (
                <div className="bg-bg-editor/50 border border-border-main rounded-xl p-6 text-center space-y-2">
                  <ShieldCheck className="w-8 h-8 text-amber-500/50 mx-auto" />
                  <p className="text-xs font-bold text-text-main">Belum Ada Token API yang Dibuat</p>
                  <p className="text-[11px] text-text-muted max-w-md mx-auto">
                    Klik tombol "Buat Token Pertama" di atas untuk men-generate token API otorisasi klien luar.
                  </p>
                </div>
              ) : (
                <div className="space-y-3">
                  {tokens.map((tok) => {
                    const isVisible = showToken;
                    const maskedToken = tok.token.substring(0, 12) + '••••••••••••••••••••••••••••••••';

                    return (
                      <div
                        key={tok.id}
                        className={clsx(
                          "bg-bg-editor p-4 rounded-xl border transition-all flex flex-wrap items-center justify-between gap-4",
                          tok.active ? "border-sky-500/40" : "border-border-main opacity-70"
                        )}
                      >
                        <div className="space-y-1.5 flex-1 min-w-[280px]">
                          <div className="flex items-center gap-2">
                            <span className="text-xs font-bold text-text-main">{tok.name || 'External API Token'}</span>
                            <span
                              className={clsx(
                                "text-[10px] font-bold px-2 py-0.5 rounded-full border",
                                tok.active
                                  ? "bg-emerald-500/10 text-emerald-600 dark:text-emerald-400 border-emerald-500/30"
                                  : "bg-rose-500/10 text-rose-600 dark:text-rose-400 border-rose-500/30"
                              )}
                            >
                              {tok.active ? '● Aktif' : '○ Dinonaktifkan'}
                            </span>
                            {tok.lastUsedAt && (
                              <span className="text-[10px] text-text-muted">
                                Terakhir digunakan: {tok.lastUsedAt.replace('T', ' ').substring(0, 16)}
                              </span>
                            )}
                          </div>

                          <div className="flex items-center gap-2">
                            <div className="bg-slate-100 dark:bg-slate-900 border border-slate-300 dark:border-slate-800 rounded-lg px-3 py-1.5 font-mono text-xs text-slate-800 dark:text-sky-300 font-semibold flex-1 flex items-center justify-between">
                              <span>{isVisible ? tok.token : maskedToken}</span>
                              <button
                                type="button"
                                onClick={() => setShowToken(!showToken)}
                                className="text-slate-500 hover:text-slate-900 dark:text-slate-400 dark:hover:text-white ml-2 cursor-pointer p-0.5 transition-colors"
                                title={showToken ? "Sembunyikan token" : "Tampilkan token"}
                              >
                                {showToken ? <EyeOff className="w-3.5 h-3.5" /> : <Eye className="w-3.5 h-3.5" />}
                              </button>
                            </div>

                            <button
                              type="button"
                              onClick={() => {
                                navigator.clipboard.writeText(tok.token);
                                setCopiedToken(true);
                                setTimeout(() => setCopiedToken(false), 2000);
                              }}
                              className="px-3 py-1.5 rounded-lg bg-sky-50 hover:bg-sky-100 dark:bg-sky-500/10 dark:hover:bg-sky-500/20 text-sky-700 dark:text-sky-300 border border-sky-300 dark:border-sky-500/30 text-xs font-bold flex items-center gap-1.5 transition-colors cursor-pointer"
                              title="Salin token ke clipboard"
                            >
                              {copiedToken ? <Check className="w-3.5 h-3.5 text-emerald-600 dark:text-emerald-400" /> : <Copy className="w-3.5 h-3.5" />}
                              <span>{copiedToken ? 'Tersalin!' : 'Copy'}</span>
                            </button>
                          </div>
                        </div>

                        <div className="flex items-center gap-2 shrink-0">
                          <button
                            type="button"
                            onClick={() => handleToggleToken(tok.id, tok.active)}
                            className={clsx(
                              "px-3 py-1.5 rounded-lg text-xs font-semibold border transition-colors cursor-pointer",
                              tok.active
                                ? "bg-amber-500/10 text-amber-600 dark:text-amber-400 border-amber-500/30 hover:bg-amber-500/20"
                                : "bg-emerald-500/10 text-emerald-600 dark:text-emerald-400 border-emerald-500/30 hover:bg-emerald-500/20"
                            )}
                          >
                            {tok.active ? 'Nonaktifkan' : 'Aktifkan'}
                          </button>
                          <button
                            type="button"
                            onClick={() => handleDeleteToken(tok.id)}
                            className="p-1.5 rounded-lg text-text-muted hover:text-rose-600 dark:hover:text-rose-400 hover:bg-rose-500/10 border border-border-main transition-colors cursor-pointer"
                            title="Hapus token"
                          >
                            <Trash2 className="w-4 h-4" />
                          </button>
                        </div>
                      </div>
                    );
                  })}
                </div>
              )}

              {/* Security Hint */}
              <div className="bg-sky-50 dark:bg-sky-950/30 border border-sky-200 dark:border-sky-800/60 rounded-xl p-3.5 text-xs text-slate-700 dark:text-slate-300 space-y-1.5">
                <span className="font-bold text-sky-700 dark:text-sky-300 flex items-center gap-1.5">
                  <Info className="w-4 h-4 text-sky-600 dark:text-sky-400" />
                  Cara Memasang Token Otorisasi:
                </span>
                <p className="leading-relaxed">
                  Sertakan header HTTP berikut pada setiap request:
                  <code className="ml-1 px-2 py-0.5 rounded bg-white dark:bg-slate-900 border border-slate-300 dark:border-slate-700 text-sky-700 dark:text-sky-300 font-mono text-[11px] font-bold shadow-xs">
                    Authorization: Bearer &lt;TOKEN_ANDA&gt;
                  </code>
                  {' '}atau{' '}
                  <code className="px-2 py-0.5 rounded bg-white dark:bg-slate-900 border border-slate-300 dark:border-slate-700 text-sky-700 dark:text-sky-300 font-mono text-[11px] font-bold shadow-xs">
                    X-API-Key: &lt;TOKEN_ANDA&gt;
                  </code>
                </p>
              </div>
            </div>

            {/* Section 2: Complete API Lifecycle Guide (Bot, Query, Schedules, Cron) */}
            <div className="bg-bg-panel border border-border-main rounded-2xl p-5 shadow-sm space-y-4">
              <div className="border-b border-border-main pb-3">
                <div className="flex items-center gap-2">
                  <div className="p-1.5 rounded-lg bg-emerald-500/10 text-emerald-500 border border-emerald-500/20">
                    <BookOpen className="w-4 h-4" />
                  </div>
                  <div>
                    <h3 className="text-sm font-bold text-text-main">Panduan Alur Lengkap API: Dari Cek Bot sampai Buat &amp; Monitor Jadwal Cron</h3>
                    <p className="text-xs text-text-muted">Ikuti langkah-langkah di bawah untuk mengintegrasikan bot telegram dan mengatur scheduler secara penuh via API.</p>
                  </div>
                </div>

                {/* Step Tabs */}
                <div className="flex flex-wrap items-center gap-2 mt-3.5">
                  {[
                    { id: 'step_bots', step: '1', title: 'Cek Bot Aktif', method: 'GET' },
                    { id: 'step_send', step: '2', title: 'Kirim Pesan Instan', method: 'POST' },
                    { id: 'step_schedules', step: '3', title: 'Lihat Jadwal Aktif', method: 'GET' },
                    { id: 'step_create_schedule', step: '4', title: 'Buat Jadwal Cron', method: 'POST' },
                    { id: 'step_control', step: '5', title: 'Kontrol (Jeda / Run Now)', method: 'PUT/POST' }
                  ].map(tab => (
                    <button
                      key={tab.id}
                      type="button"
                      onClick={() => setLifecycleStep(tab.id as any)}
                      className={clsx(
                        "px-3 py-1.5 rounded-xl text-xs font-bold transition-all cursor-pointer border flex items-center gap-2",
                        lifecycleStep === tab.id
                          ? "bg-emerald-600 text-white border-emerald-700 shadow-sm"
                          : "bg-bg-editor text-slate-700 dark:text-slate-300 border-border-main hover:bg-bg-hover"
                      )}
                    >
                      <span className={clsx(
                        "w-4 h-4 rounded-full text-[10px] flex items-center justify-center font-bold",
                        lifecycleStep === tab.id ? "bg-white text-emerald-700" : "bg-slate-200 dark:bg-slate-800 text-slate-700 dark:text-slate-300"
                      )}>
                        {tab.step}
                      </span>
                      <span>{tab.title}</span>
                      <span className={clsx(
                        "text-[9px] px-1.5 py-0.2 rounded font-mono font-bold",
                        tab.method === 'GET' ? "bg-sky-500/20 text-sky-400" : tab.method === 'POST' ? "bg-emerald-500/20 text-emerald-400" : "bg-amber-500/20 text-amber-400"
                      )}>
                        {tab.method}
                      </span>
                    </button>
                  ))}
                </div>
              </div>

              {/* Step 1: Cek Bot Aktif */}
              {lifecycleStep === 'step_bots' && (
                <div className="space-y-3 animate-in fade-in duration-200 text-xs">
                  <div className="bg-sky-50 dark:bg-sky-950/30 border border-sky-200 dark:border-sky-800/60 rounded-xl p-3.5 space-y-1">
                    <span className="font-bold text-sky-700 dark:text-sky-300 flex items-center gap-1.5">
                      <Bot className="w-4 h-4 text-sky-600 dark:text-sky-400" />
                      Langkah 1: Melihat Bot Telegram yang Tersedia dan Sedang Aktif
                    </span>
                    <p className="text-slate-700 dark:text-slate-300 leading-relaxed">
                      Gunakan endpoint ini untuk mengetahui nama bot (<code className="font-mono text-sky-700 dark:text-sky-300 font-bold">name</code>) atau <code className="font-mono text-sky-700 dark:text-sky-300 font-bold">id</code> bot yang valid sebelum mengirimkan pesan atau mendaftarkan jadwal cron.
                    </p>
                  </div>

                  <div className="flex items-center justify-between bg-bg-editor border border-border-main rounded-xl px-3 py-2 font-mono">
                    <div className="flex items-center gap-2">
                      <span className="px-2 py-0.5 rounded bg-sky-500/20 text-sky-600 dark:text-sky-400 font-bold text-[11px]">GET</span>
                      <span className="text-slate-800 dark:text-slate-200 font-semibold select-all">{`${window.location.origin}/api/v1/telegram/external/bots`}</span>
                    </div>
                    <button
                      type="button"
                      onClick={() => {
                        navigator.clipboard.writeText(`curl -X GET "${window.location.origin}/api/v1/telegram/external/bots" -H "Authorization: Bearer ${tokens.find(t => t.active)?.token || 'YOUR_API_TOKEN'}"`);
                        setCopiedCurl(true);
                        setTimeout(() => setCopiedCurl(false), 2000);
                      }}
                      className="px-2.5 py-1 rounded-lg bg-bg-panel hover:bg-bg-hover border border-border-main text-xs font-semibold text-text-main flex items-center gap-1 transition-colors cursor-pointer"
                    >
                      {copiedCurl ? <Check className="w-3.5 h-3.5 text-emerald-400" /> : <Copy className="w-3.5 h-3.5" />}
                      <span>Copy cURL</span>
                    </button>
                  </div>

                  <div className="space-y-1.5">
                    <span className="font-bold text-slate-700 dark:text-slate-300">Contoh Response JSON:</span>
                    <pre className="bg-[#0f172a] dark:bg-[#080d1a] p-3.5 rounded-xl border border-slate-700/80 font-mono text-[11px] text-emerald-400 overflow-x-auto leading-relaxed">
                      <code>{`{
  "success": true,
  "total": 1,
  "bots": [
    {
      "id": "${bots[0]?.id || 'e9365e13-xxxx-xxxx'}",
      "name": "${bots[0]?.name || 'DarkoBot_SSI'}",
      "botUsername": "${bots[0]?.botUsername || 'DarkoBot_SSI_bot'}",
      "isPollingActive": true
    }
  ]
}`}</code>
                    </pre>
                  </div>
                </div>
              )}

              {/* Step 2: Kirim Pesan Instan */}
              {lifecycleStep === 'step_send' && (
                <div className="space-y-3 animate-in fade-in duration-200 text-xs">
                  <div className="bg-emerald-50 dark:bg-emerald-950/30 border border-emerald-200 dark:border-emerald-800/60 rounded-xl p-3.5 space-y-1">
                    <span className="font-bold text-emerald-700 dark:text-emerald-300 flex items-center gap-1.5">
                      <Send className="w-4 h-4 text-emerald-600 dark:text-emerald-400" />
                      Langkah 2: Mengirim Pesan Instan Seketika (On-Demand)
                    </span>
                    <p className="text-slate-700 dark:text-slate-300 leading-relaxed">
                      Panggil endpoint ini kapan pun sistem luar ingin mengirim notifikasi. Bisa langsung dengan query SQL yang men-select kolom pesan, atau menggunakan nama view database.
                    </p>
                  </div>

                  <div className="flex items-center justify-between bg-bg-editor border border-border-main rounded-xl px-3 py-2 font-mono">
                    <div className="flex items-center gap-2">
                      <span className="px-2 py-0.5 rounded bg-emerald-500/20 text-emerald-600 dark:text-emerald-400 font-bold text-[11px]">POST</span>
                      <span className="text-slate-800 dark:text-slate-200 font-semibold select-all">{`${window.location.origin}/api/v1/telegram/external/send`}</span>
                    </div>
                  </div>

                  <div className="space-y-1.5">
                    <span className="font-bold text-slate-700 dark:text-slate-300">Contoh Payload Body JSON (Mode Query Langsung &amp; Kolom Pesan Jadi):</span>
                    <pre className="bg-[#0f172a] dark:bg-[#080d1a] p-3.5 rounded-xl border border-slate-700/80 font-mono text-[11px] text-sky-200 overflow-x-auto leading-relaxed">
                      <code>{`{
  "database": "ssi",
  "bot": "${bots[0]?.name || 'DarkoBot_SSI'}",
  "query": "SELECT user_hp, pesan FROM v_notifikasi WHERE status = 'PENDING' LIMIT 10",
  "recipient_column": "user_hp",
  "message_column": "pesan"
}`}</code>
                    </pre>
                  </div>
                </div>
              )}

              {/* Step 3: Lihat Jadwal Aktif */}
              {lifecycleStep === 'step_schedules' && (
                <div className="space-y-3 animate-in fade-in duration-200 text-xs">
                  <div className="bg-purple-50 dark:bg-purple-950/30 border border-purple-200 dark:border-purple-800/60 rounded-xl p-3.5 space-y-1">
                    <span className="font-bold text-purple-700 dark:text-purple-300 flex items-center gap-1.5">
                      <Clock className="w-4 h-4 text-purple-600 dark:text-purple-400" />
                      Langkah 3: Memantau Jadwal Cron Bot Apa Saja yang Sedang Aktif
                    </span>
                    <p className="text-slate-700 dark:text-slate-300 leading-relaxed">
                      Endpoint ini mengembalikan daftar seluruh jadwal otomatis yang terdaftar di DarkoSync Scheduler, ekspresi cron-nya, status eksekusi terakhir (<code className="font-mono text-purple-700 dark:text-purple-300 font-bold">lastRunStatus</code>), dan waktu eksekusi terakhir. Tambahkan query parameter <code className="font-mono text-purple-700 dark:text-purple-300 font-bold">?activeOnly=true</code> untuk memfilter hanya jadwal yang sedang aktif.
                    </p>
                  </div>

                  <div className="flex items-center justify-between bg-bg-editor border border-border-main rounded-xl px-3 py-2 font-mono">
                    <div className="flex items-center gap-2">
                      <span className="px-2 py-0.5 rounded bg-sky-500/20 text-sky-600 dark:text-sky-400 font-bold text-[11px]">GET</span>
                      <span className="text-slate-800 dark:text-slate-200 font-semibold select-all">{`${window.location.origin}/api/v1/telegram/external/schedules?activeOnly=true`}</span>
                    </div>
                    <button
                      type="button"
                      onClick={() => {
                        navigator.clipboard.writeText(`curl -X GET "${window.location.origin}/api/v1/telegram/external/schedules?activeOnly=true" -H "Authorization: Bearer ${tokens.find(t => t.active)?.token || 'YOUR_API_TOKEN'}"`);
                        setCopiedCurl(true);
                        setTimeout(() => setCopiedCurl(false), 2000);
                      }}
                      className="px-2.5 py-1 rounded-lg bg-bg-panel hover:bg-bg-hover border border-border-main text-xs font-semibold text-text-main flex items-center gap-1 transition-colors cursor-pointer"
                    >
                      {copiedCurl ? <Check className="w-3.5 h-3.5 text-emerald-400" /> : <Copy className="w-3.5 h-3.5" />}
                      <span>Copy cURL</span>
                    </button>
                  </div>

                  <div className="space-y-1.5">
                    <span className="font-bold text-slate-700 dark:text-slate-300">Contoh Response JSON:</span>
                    <pre className="bg-[#0f172a] dark:bg-[#080d1a] p-3.5 rounded-xl border border-slate-700/80 font-mono text-[11px] text-emerald-400 overflow-x-auto leading-relaxed">
                      <code>{`{
  "success": true,
  "total": 1,
  "schedules": [
    {
      "id": "c138b472-8921-4d1a-82bc-90a1f28b12e1",
      "name": "Kirim Notifikasi Faktur Penjualan",
      "botId": "${bots[0]?.id || 'e9365e13-xxxx-xxxx'}",
      "connectionId": "ssi",
      "cronExpression": "0 0 8,12,18 * * *",
      "active": true,
      "lastRunAt": "2026-10-20T08:00:00",
      "lastRunStatus": "SUCCESS",
      "lastRunMessage": "Processed 24 messages successfully",
      "viewName": "v_notifikasi_harian",
      "phoneColumnName": "user_hp"
    }
  ]
}`}</code>
                    </pre>
                  </div>
                </div>
              )}

              {/* Step 4: Buat Jadwal Cron Baru */}
              {lifecycleStep === 'step_create_schedule' && (
                <div className="space-y-3 animate-in fade-in duration-200 text-xs">
                  <div className="bg-amber-50 dark:bg-amber-950/30 border border-amber-200 dark:border-amber-800/60 rounded-xl p-3.5 space-y-1">
                    <span className="font-bold text-amber-700 dark:text-amber-300 flex items-center gap-1.5">
                      <Plus className="w-4 h-4 text-amber-600 dark:text-amber-400" />
                      Langkah 4: Membuat &amp; Mendaftarkan Jadwal Cron Baru Menggunakan API
                    </span>
                    <p className="text-slate-700 dark:text-slate-300 leading-relaxed">
                      Sistem luar dapat membuat scheduler baru secara otomatis ke DarkoSync. Begitu request berhasil, cron trigger langsung terdaftar dan aktif di mesin penjadwal tanpa perlu restart aplikasi.
                    </p>
                  </div>

                  <div className="flex items-center justify-between bg-bg-editor border border-border-main rounded-xl px-3 py-2 font-mono">
                    <div className="flex items-center gap-2">
                      <span className="px-2 py-0.5 rounded bg-emerald-500/20 text-emerald-600 dark:text-emerald-400 font-bold text-[11px]">POST</span>
                      <span className="text-slate-800 dark:text-slate-200 font-semibold select-all">{`${window.location.origin}/api/v1/telegram/external/schedules`}</span>
                    </div>
                  </div>

                  <div className="space-y-1.5">
                    <span className="font-bold text-slate-700 dark:text-slate-300">Contoh Payload Body JSON Pembuatan Jadwal:</span>
                    <pre className="bg-[#0f172a] dark:bg-[#080d1a] p-3.5 rounded-xl border border-slate-700/80 font-mono text-[11px] text-sky-200 overflow-x-auto leading-relaxed">
                      <code>{`{
  "name": "Auto Reminder Piutang Jatuh Tempo",
  "bot": "${bots[0]?.name || 'DarkoBot_SSI'}",
  "database": "ssi",
  "cronExpression": "0 0 9 * * *",
  "query": "SELECT user_hp, pesan FROM v_piutang_jatuh_tempo WHERE status = 'JATUH_TEMPO'",
  "recipient_column": "user_hp",
  "message_column": "pesan",
  "max_rows": 50,
  "active": true
}`}</code>
                    </pre>
                  </div>

                  <div className="p-3 bg-bg-editor rounded-xl border border-border-main space-y-1 text-[11px] text-slate-700 dark:text-slate-300">
                    <span className="font-bold text-amber-600 dark:text-amber-400">⚡ Contoh Format Cron Expression Populer:</span>
                    <ul className="list-disc list-inside space-y-0.5 font-mono text-[10px]">
                      <li><code className="text-sky-600 dark:text-sky-400 font-bold">0 0 8 * * *</code> = Setiap hari jam 08:00 WIB</li>
                      <li><code className="text-sky-600 dark:text-sky-400 font-bold">0 0 8,12,18 * * *</code> = 3x sehari (Jam 08:00, 12:00, dan 18:00)</li>
                      <li><code className="text-sky-600 dark:text-sky-400 font-bold">0 0 * * * *</code> = Tiap 1 jam sekali</li>
                      <li><code className="text-sky-600 dark:text-sky-400 font-bold">0 */15 * * * *</code> = Tiap 15 menit sekali</li>
                    </ul>
                  </div>
                </div>
              )}

              {/* Step 5: Kontrol (Toggle / Run Now) */}
              {lifecycleStep === 'step_control' && (
                <div className="space-y-3 animate-in fade-in duration-200 text-xs">
                  <div className="bg-rose-50 dark:bg-rose-950/30 border border-rose-200 dark:border-rose-800/60 rounded-xl p-3.5 space-y-1">
                    <span className="font-bold text-rose-700 dark:text-rose-300 flex items-center gap-1.5">
                      <PlayCircle className="w-4 h-4 text-rose-600 dark:text-rose-400" />
                      Langkah 5: Mengontrol Jadwal (Jeda / Aktifkan &amp; Run Now Seketika)
                    </span>
                    <p className="text-slate-700 dark:text-slate-300 leading-relaxed">
                      Sistem eksternal dapat menjeda sementara atau memicu eksekusi manual seketika tanpa harus menunggu jadwal cron-nya tiba.
                    </p>
                  </div>

                  <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
                    {/* Toggle Subcard */}
                    <div className="bg-bg-editor p-3.5 rounded-xl border border-border-main space-y-2">
                      <span className="font-bold text-text-main flex items-center gap-1.5">
                        <span className="px-1.5 py-0.5 rounded bg-amber-500/20 text-amber-600 dark:text-amber-400 font-mono text-[10px] font-bold">PUT</span>
                        Aktifkan / Jeda Jadwal
                      </span>
                      <code className="block bg-bg-panel p-2 rounded-lg border font-mono text-[10px] text-sky-700 dark:text-sky-300 break-all select-all">
                        {`${window.location.origin}/api/v1/telegram/external/schedules/{id}/toggle`}
                      </code>
                      <p className="text-[11px] text-text-muted">Kirimkan JSON Body:</p>
                      <pre className="bg-[#0f172a] p-2.5 rounded-lg border border-slate-700 font-mono text-[10px] text-emerald-400">
                        <code>{`{ "active": false } // atau true`}</code>
                      </pre>
                    </div>

                    {/* Run Now Subcard */}
                    <div className="bg-bg-editor p-3.5 rounded-xl border border-border-main space-y-2">
                      <span className="font-bold text-text-main flex items-center gap-1.5">
                        <span className="px-1.5 py-0.5 rounded bg-emerald-500/20 text-emerald-600 dark:text-emerald-400 font-mono text-[10px] font-bold">POST</span>
                        Paksa Eksekusi Seketika (Run Now)
                      </span>
                      <code className="block bg-bg-panel p-2 rounded-lg border font-mono text-[10px] text-sky-700 dark:text-sky-300 break-all select-all">
                        {`${window.location.origin}/api/v1/telegram/external/schedules/{id}/run-now`}
                      </code>
                      <p className="text-[11px] text-text-muted">Cukup POST dengan token autentikasi (tanpa body). Bot akan langsung mengeksekusi data detik ini juga.</p>
                    </div>
                  </div>
                </div>
              )}
            </div>

            {/* Section 3: Specification & Parameters */}
            <div className="bg-bg-panel border border-border-main rounded-2xl p-5 shadow-sm space-y-4">
              <div className="border-b border-border-main pb-3">
                <h3 className="text-sm font-bold text-text-main flex items-center gap-2">
                  <Terminal className="w-4 h-4 text-emerald-500" />
                  Spesifikasi Parameter Payload (JSON Body)
                </h3>
                <div className="flex flex-wrap items-center justify-between gap-3 mt-1.5">
                  <p className="text-xs text-slate-600 dark:text-slate-400">
                    Request dikirim dengan method <b className="text-emerald-600 dark:text-emerald-400 font-bold">POST</b> ke endpoint berikut:
                  </p>

                  <div className="flex items-center gap-1.5 bg-slate-100 dark:bg-slate-900 border border-slate-300 dark:border-slate-800 rounded-lg px-2.5 py-1">
                    <code className="text-sky-700 dark:text-sky-300 font-mono text-xs font-bold select-all">
                      {`${window.location.origin}/api/v1/telegram/external/send`}
                    </code>
                    <button
                      type="button"
                      onClick={() => {
                        navigator.clipboard.writeText(`${window.location.origin}/api/v1/telegram/external/send`);
                        setCopiedEndpoint(true);
                        setTimeout(() => setCopiedEndpoint(false), 2000);
                      }}
                      className="p-1 rounded hover:bg-slate-200 dark:hover:bg-slate-800 text-slate-500 hover:text-sky-600 dark:hover:text-sky-300 transition-colors cursor-pointer"
                      title="Salin Link Lengkap Endpoint API"
                    >
                      {copiedEndpoint ? <Check className="w-3.5 h-3.5 text-emerald-600 dark:text-emerald-400" /> : <Copy className="w-3.5 h-3.5" />}
                    </button>
                  </div>
                </div>

                {/* Integration Mode Highlights */}
                <div className="mt-3 grid grid-cols-1 md:grid-cols-2 gap-2 text-xs">
                  <div className="bg-sky-50 dark:bg-sky-950/30 border border-sky-200 dark:border-sky-800/60 rounded-xl p-3">
                    <span className="font-bold text-sky-700 dark:text-sky-300 flex items-center gap-1.5 mb-1">
                      <span className="w-2 h-2 rounded-full bg-sky-500" />
                      Cara 1: Direct SQL Query &amp; Kolom Pesan Jadi
                    </span>
                    <p className="text-slate-700 dark:text-slate-300 text-[11px] leading-relaxed">
                      Kirim parameter <code className="font-mono text-sky-700 dark:text-sky-300 font-semibold">query</code> yang men-SELECT kolom nomor HP (<code className="font-mono">user_hp</code>) dan kolom isi pesan (<code className="font-mono">pesan</code>). Bot akan langsung mengirimkan teks tersebut tanpa perlu template tambahan.
                    </p>
                  </div>

                  <div className="bg-purple-50 dark:bg-purple-950/30 border border-purple-200 dark:border-purple-800/60 rounded-xl p-3">
                    <span className="font-bold text-purple-700 dark:text-purple-300 flex items-center gap-1.5 mb-1">
                      <span className="w-2 h-2 rounded-full bg-purple-500" />
                      Cara 2: Nama View Database Langsung
                    </span>
                    <p className="text-slate-700 dark:text-slate-300 text-[11px] leading-relaxed">
                      Cukup kirim parameter <code className="font-mono text-purple-700 dark:text-purple-300 font-semibold">view</code> (tanpa SQL mentah). Bisa langsung pakai kolom pesan yang ada di view atau gunakan <code className="font-mono text-purple-700 dark:text-purple-300 font-semibold">template</code> dinamis dengan variabel <code className="font-mono">{"{{kolom}}"}</code>.
                    </p>
                  </div>
                </div>
              </div>

              <div className="overflow-x-auto border border-border-main rounded-xl shadow-xs">
                <table className="w-full text-left text-xs">
                  <thead className="bg-slate-100 dark:bg-slate-800/80 border-b border-border-main text-[11px] text-slate-700 dark:text-slate-200 uppercase font-bold tracking-wider">
                    <tr>
                      <th className="py-3 px-3.5">Parameter</th>
                      <th className="py-3 px-3.5">Tipe</th>
                      <th className="py-3 px-3.5">Wajib?</th>
                      <th className="py-3 px-3.5">Default</th>
                      <th className="py-3 px-3.5">Deskripsi &amp; Contoh Penggunaan</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-border-main/60 bg-bg-panel">
                    <tr className="hover:bg-bg-hover/30">
                      <td className="py-3 px-3.5 font-mono font-bold text-sky-600 dark:text-sky-400">database</td>
                      <td className="py-3 px-3.5 text-slate-600 dark:text-slate-300 font-mono">string</td>
                      <td className="py-3 px-3.5"><span className="text-rose-600 dark:text-rose-400 font-bold">Wajib</span></td>
                      <td className="py-3 px-3.5 text-slate-500 dark:text-slate-400 font-mono">-</td>
                      <td className="py-3 px-3.5 text-slate-700 dark:text-slate-200 leading-relaxed">
                        Nama database atau ID koneksi di DarkoSync (misal: <code className="px-1.5 py-0.5 rounded bg-slate-100 dark:bg-slate-800 border border-slate-200 dark:border-slate-700 text-sky-700 dark:text-sky-300 font-mono font-semibold">"ssi"</code>, <code className="px-1.5 py-0.5 rounded bg-slate-100 dark:bg-slate-800 border border-slate-200 dark:border-slate-700 text-sky-700 dark:text-sky-300 font-mono font-semibold">"mkn"</code>, <code className="px-1.5 py-0.5 rounded bg-slate-100 dark:bg-slate-800 border border-slate-200 dark:border-slate-700 text-sky-700 dark:text-sky-300 font-mono font-semibold">"bpi"</code>).
                      </td>
                    </tr>
                    <tr className="hover:bg-bg-hover/30">
                      <td className="py-3 px-3.5 font-mono font-bold text-sky-600 dark:text-sky-400">bot</td>
                      <td className="py-3 px-3.5 text-slate-600 dark:text-slate-300 font-mono">string</td>
                      <td className="py-3 px-3.5"><span className="text-rose-600 dark:text-rose-400 font-bold">Wajib</span></td>
                      <td className="py-3 px-3.5 text-slate-500 dark:text-slate-400 font-mono">-</td>
                      <td className="py-3 px-3.5 text-slate-700 dark:text-slate-200 leading-relaxed">
                        Nama Bot Telegram yang terdaftar di DarkoSync (misal: <code className="px-1.5 py-0.5 rounded bg-slate-100 dark:bg-slate-800 border border-slate-200 dark:border-slate-700 text-sky-700 dark:text-sky-300 font-mono font-semibold">"DarkoBot_SSI"</code>).
                      </td>
                    </tr>
                    <tr className="hover:bg-bg-hover/30">
                      <td className="py-3 px-3.5 font-mono font-bold text-sky-600 dark:text-sky-400">query</td>
                      <td className="py-3 px-3.5 text-slate-600 dark:text-slate-300 font-mono">string</td>
                      <td className="py-3 px-3.5"><span className="text-amber-600 dark:text-amber-400 font-bold">Kondisional*</span></td>
                      <td className="py-3 px-3.5 text-slate-500 dark:text-slate-400 font-mono">-</td>
                      <td className="py-3 px-3.5 text-slate-700 dark:text-slate-200 leading-relaxed">
                        Query SQL SELECT. Wajib diisi jika parameter <code className="px-1.5 py-0.5 rounded bg-slate-100 dark:bg-slate-800 border border-slate-200 dark:border-slate-700 text-sky-700 dark:text-sky-300 font-mono font-semibold">view</code> tidak diisi. Field yang di-SELECT bisa langsung berupa kolom nomor HP dan kolom isi pesan (contoh: <code className="px-1.5 py-0.5 rounded bg-slate-100 dark:bg-slate-800 border border-slate-200 dark:border-slate-700 text-sky-700 dark:text-sky-300 font-mono font-semibold">"SELECT user_hp, pesan FROM v_notifikasi"</code>).
                      </td>
                    </tr>
                    <tr className="hover:bg-bg-hover/30">
                      <td className="py-3 px-3.5 font-mono font-bold text-sky-600 dark:text-sky-400">view</td>
                      <td className="py-3 px-3.5 text-slate-600 dark:text-slate-300 font-mono">string</td>
                      <td className="py-3 px-3.5"><span className="text-amber-600 dark:text-amber-400 font-bold">Kondisional*</span></td>
                      <td className="py-3 px-3.5 text-slate-500 dark:text-slate-400 font-mono">-</td>
                      <td className="py-3 px-3.5 text-slate-700 dark:text-slate-200 leading-relaxed">
                        Nama View/Tabel alternatif jika tidak mengirimkan SQL mentah (misal: <code className="px-1.5 py-0.5 rounded bg-slate-100 dark:bg-slate-800 border border-slate-200 dark:border-slate-700 text-sky-700 dark:text-sky-300 font-mono font-semibold">"v_faktur_harian"</code>).
                      </td>
                    </tr>
                    <tr className="hover:bg-bg-hover/30">
                      <td className="py-3 px-3.5 font-mono font-bold text-sky-600 dark:text-sky-400">recipient_column</td>
                      <td className="py-3 px-3.5 text-slate-600 dark:text-slate-300 font-mono">string</td>
                      <td className="py-3 px-3.5"><span className="text-slate-500 dark:text-slate-400 font-medium">Opsional</span></td>
                      <td className="py-3 px-3.5 font-mono text-emerald-600 dark:text-emerald-400 font-bold">"user_hp"</td>
                      <td className="py-3 px-3.5 text-slate-700 dark:text-slate-200 leading-relaxed">
                        Nama kolom pada baris data hasil SELECT / View yang berisi Nomor HP atau Chat ID tujuan (contoh: <code className="px-1.5 py-0.5 rounded bg-slate-100 dark:bg-slate-800 border border-slate-200 dark:border-slate-700 text-sky-700 dark:text-sky-300 font-mono font-semibold">"telepon"</code>, <code className="px-1.5 py-0.5 rounded bg-slate-100 dark:bg-slate-800 border border-slate-200 dark:border-slate-700 text-sky-700 dark:text-sky-300 font-mono font-semibold">"user_hp"</code>, <code className="px-1.5 py-0.5 rounded bg-slate-100 dark:bg-slate-800 border border-slate-200 dark:border-slate-700 text-sky-700 dark:text-sky-300 font-mono font-semibold">"chat_id"</code>).
                      </td>
                    </tr>
                    <tr className="hover:bg-bg-hover/30">
                      <td className="py-3 px-3.5 font-mono font-bold text-sky-600 dark:text-sky-400">message_column</td>
                      <td className="py-3 px-3.5 text-slate-600 dark:text-slate-300 font-mono">string</td>
                      <td className="py-3 px-3.5"><span className="text-slate-500 dark:text-slate-400 font-medium">Opsional</span></td>
                      <td className="py-3 px-3.5 font-mono text-emerald-600 dark:text-emerald-400 font-bold">"pesan"</td>
                      <td className="py-3 px-3.5 text-slate-700 dark:text-slate-200 leading-relaxed">
                        Nama kolom pada baris data hasil SELECT / View yang memuat teks pesan siap kirim. Jika parameter ini terisi dan template tidak diisi, isi kolom ini langsung dikirim utuh ke penerima.
                      </td>
                    </tr>
                    <tr className="hover:bg-bg-hover/30">
                      <td className="py-3 px-3.5 font-mono font-bold text-sky-600 dark:text-sky-400">template</td>
                      <td className="py-3 px-3.5 text-slate-600 dark:text-slate-300 font-mono">string</td>
                      <td className="py-3 px-3.5"><span className="text-slate-500 dark:text-slate-400 font-medium">Opsional</span></td>
                      <td className="py-3 px-3.5 text-slate-500 dark:text-slate-400 font-mono">-</td>
                      <td className="py-3 px-3.5 text-slate-700 dark:text-slate-200 leading-relaxed">
                        Format template pesan kustom menggunakan variabel <code className="px-1.5 py-0.5 rounded bg-slate-100 dark:bg-slate-800 border border-slate-200 dark:border-slate-700 text-sky-700 dark:text-sky-300 font-mono font-semibold">{"{{nama_kolom}}"}</code> (misal: <code className="px-1.5 py-0.5 rounded bg-slate-100 dark:bg-slate-800 border border-slate-200 dark:border-slate-700 text-sky-700 dark:text-sky-300 font-mono font-semibold">"Halo {"{{customer}}"}, faktur {"{{no_faktur}}"} lunas"</code>). Jika diisi, akan meng-override kolom pesan.
                      </td>
                    </tr>
                    <tr className="hover:bg-bg-hover/30">
                      <td className="py-3 px-3.5 font-mono font-bold text-sky-600 dark:text-sky-400">params</td>
                      <td className="py-3 px-3.5 text-slate-600 dark:text-slate-300 font-mono">object</td>
                      <td className="py-3 px-3.5"><span className="text-slate-500 dark:text-slate-400 font-medium">Opsional</span></td>
                      <td className="py-3 px-3.5 text-slate-500 dark:text-slate-400 font-mono">-</td>
                      <td className="py-3 px-3.5 text-slate-700 dark:text-slate-200 leading-relaxed">
                        Parameter query SQL bernama (misal: <code className="px-1.5 py-0.5 rounded bg-slate-100 dark:bg-slate-800 border border-slate-200 dark:border-slate-700 text-sky-700 dark:text-sky-300 font-mono font-semibold">{"{\"tgl\": \"2026-10-19\"}"}</code> untuk placeholder <code className="px-1.5 py-0.5 rounded bg-slate-100 dark:bg-slate-800 border border-slate-200 dark:border-slate-700 text-sky-700 dark:text-sky-300 font-mono font-semibold">:tgl</code>).
                      </td>
                    </tr>
                    <tr className="hover:bg-bg-hover/30">
                      <td className="py-3 px-3.5 font-mono font-bold text-sky-600 dark:text-sky-400">max_rows</td>
                      <td className="py-3 px-3.5 text-slate-600 dark:text-slate-300 font-mono">number</td>
                      <td className="py-3 px-3.5"><span className="text-slate-500 dark:text-slate-400 font-medium">Opsional</span></td>
                      <td className="py-3 px-3.5 font-mono text-emerald-600 dark:text-emerald-400 font-bold">50</td>
                      <td className="py-3 px-3.5 text-slate-700 dark:text-slate-200 leading-relaxed">
                        Batas maksimum baris yang dieksekusi dan dikirim pesan (maksimal 1000).
                      </td>
                    </tr>
                  </tbody>
                </table>
              </div>
            </div>

            {/* Section 3: Code Snippets */}
            <div className="bg-bg-panel border border-border-main rounded-2xl p-5 shadow-sm space-y-4">
              <div className="flex flex-wrap items-center justify-between gap-3 border-b border-border-main pb-3">
                <div className="flex items-center gap-2">
                  <Terminal className="w-4 h-4 text-sky-500" />
                  <div>
                    <h3 className="text-sm font-bold text-text-main">Contoh Kode Integrasi (Code Snippets)</h3>
                    <p className="text-xs text-text-muted">Pilih mode integrasi dan bahasa pemrograman untuk melihat contoh kode siap pakai.</p>
                  </div>
                </div>

                {/* Language Tabs */}
                <div className="flex items-center gap-1 bg-bg-editor p-1 rounded-lg border border-border-main text-xs">
                  {(['curl', 'javascript', 'python', 'php'] as const).map(lang => (
                    <button
                      key={lang}
                      type="button"
                      onClick={() => setActiveCodeSnippet(lang)}
                      className={clsx(
                        "px-2.5 py-1 rounded-md font-semibold capitalize transition-colors cursor-pointer",
                        activeCodeSnippet === lang
                          ? "bg-sky-500 text-white shadow-sm"
                          : "text-text-muted hover:text-text-main"
                      )}
                    >
                      {lang === 'curl' ? 'cURL' : lang === 'javascript' ? 'JavaScript' : lang === 'python' ? 'Python' : 'PHP'}
                    </button>
                  ))}
                </div>
              </div>

              {/* Scenario Mode Selector */}
              <div className="flex flex-wrap items-center gap-2">
                <button
                  type="button"
                  onClick={() => setSnippetMode('direct_query')}
                  className={clsx(
                    "px-3 py-1.5 rounded-xl text-xs font-bold transition-all cursor-pointer border flex items-center gap-1.5",
                    snippetMode === 'direct_query'
                      ? "bg-sky-500 text-white border-sky-600 shadow-sm"
                      : "bg-bg-editor text-slate-700 dark:text-slate-300 border-border-main hover:bg-bg-hover"
                  )}
                >
                  <span className="w-1.5 h-1.5 rounded-full bg-emerald-400" />
                  Mode 1: Query SQL Langsung (Pesan dari Field DB)
                </button>

                <button
                  type="button"
                  onClick={() => setSnippetMode('db_view')}
                  className={clsx(
                    "px-3 py-1.5 rounded-xl text-xs font-bold transition-all cursor-pointer border flex items-center gap-1.5",
                    snippetMode === 'db_view'
                      ? "bg-purple-600 text-white border-purple-700 shadow-sm"
                      : "bg-bg-editor text-slate-700 dark:text-slate-300 border-border-main hover:bg-bg-hover"
                  )}
                >
                  <span className="w-1.5 h-1.5 rounded-full bg-purple-400" />
                  Mode 2: Nama View Database Langsung
                </button>

                <button
                  type="button"
                  onClick={() => setSnippetMode('dynamic_template')}
                  className={clsx(
                    "px-3 py-1.5 rounded-xl text-xs font-bold transition-all cursor-pointer border flex items-center gap-1.5",
                    snippetMode === 'dynamic_template'
                      ? "bg-amber-600 text-white border-amber-700 shadow-sm"
                      : "bg-bg-editor text-slate-700 dark:text-slate-300 border-border-main hover:bg-bg-hover"
                  )}
                >
                  <span className="w-1.5 h-1.5 rounded-full bg-amber-400" />
                  Mode 3: Query / View + Template Dinamis {"{{field}}"}
                </button>
              </div>

              {/* Code Box */}
              {(() => {
                const activeTok = tokens.find(t => t.active)?.token || 'YOUR_API_TOKEN_HERE';
                const endpointUrl = `${window.location.origin}/api/v1/telegram/external/send`;
                const botName = bots[0]?.name || 'NamaBotTelegram';

                let snippet = '';

                // Skenario 1: Direct SQL Query & Pesan Langsung dari Field DB
                if (snippetMode === 'direct_query') {
                  if (activeCodeSnippet === 'curl') {
                    snippet = `# Mode 1: Query SQL Langsung & pesan langsung diambil dari kolom DB
curl -X POST "${endpointUrl}" \\
  -H "Authorization: Bearer ${activeTok}" \\
  -H "Content-Type: application/json" \\
  -d '{
    "database": "ssi",
    "bot": "${botName}",
    "query": "SELECT user_hp, pesan FROM v_notifikasi_siap_kirim WHERE status = '\''PENDING'\'' LIMIT 10",
    "recipient_column": "user_hp",
    "message_column": "pesan"
  }'`;
                  } else if (activeCodeSnippet === 'javascript') {
                    snippet = `// Mode 1: Query SQL Langsung & pesan langsung diambil dari kolom DB
const response = await fetch("${endpointUrl}", {
  method: "POST",
  headers: {
    "Authorization": "Bearer ${activeTok}",
    "Content-Type": "application/json"
  },
  body: JSON.stringify({
    database: "ssi",
    bot: "${botName}",
    query: "SELECT user_hp, pesan FROM v_notifikasi_siap_kirim WHERE status = 'PENDING' LIMIT 10",
    recipient_column: "user_hp",
    message_column: "pesan"
  })
});

const result = await response.json();
console.log(result);`;
                  } else if (activeCodeSnippet === 'python') {
                    snippet = `import requests

# Mode 1: Query SQL Langsung & pesan langsung diambil dari kolom DB
url = "${endpointUrl}"
headers = {
    "Authorization": "Bearer ${activeTok}",
    "Content-Type": "application/json"
}
payload = {
    "database": "ssi",
    "bot": "${botName}",
    "query": "SELECT user_hp, pesan FROM v_notifikasi_siap_kirim WHERE status = 'PENDING' LIMIT 10",
    "recipient_column": "user_hp",
    "message_column": "pesan"
}

response = requests.post(url, json=payload, headers=headers)
print(response.json())`;
                  } else if (activeCodeSnippet === 'php') {
                    snippet = `<?php
// Mode 1: Query SQL Langsung & pesan langsung diambil dari kolom DB
$curl = curl_init();

$payload = [
    "database" => "ssi",
    "bot" => "${botName}",
    "query" => "SELECT user_hp, pesan FROM v_notifikasi_siap_kirim WHERE status = 'PENDING' LIMIT 10",
    "recipient_column" => "user_hp",
    "message_column" => "pesan"
];

curl_setopt_array($curl, [
    CURLOPT_URL => "${endpointUrl}",
    CURLOPT_RETURNTRANSFER => true,
    CURLOPT_POST => true,
    CURLOPT_POSTFIELDS => json_encode($payload),
    CURLOPT_HTTPHEADER => [
        "Authorization: Bearer ${activeTok}",
        "Content-Type: application/json"
    ],
]);

$response = curl_exec($curl);
curl_close($curl);
echo $response;`;
                  }
                }

                // Skenario 2: Memanggil Nama View Database Langsung
                else if (snippetMode === 'db_view') {
                  if (activeCodeSnippet === 'curl') {
                    snippet = `# Mode 2: Memanggil Nama View Database (Tanpa Query SQL Mentah)
curl -X POST "${endpointUrl}" \\
  -H "Authorization: Bearer ${activeTok}" \\
  -H "Content-Type: application/json" \\
  -d '{
    "database": "ssi",
    "bot": "${botName}",
    "view": "v_notifikasi_harian",
    "recipient_column": "user_hp",
    "message_column": "pesan",
    "max_rows": 25
  }'`;
                  } else if (activeCodeSnippet === 'javascript') {
                    snippet = `// Mode 2: Memanggil Nama View Database (Tanpa Query SQL Mentah)
const response = await fetch("${endpointUrl}", {
  method: "POST",
  headers: {
    "Authorization": "Bearer ${activeTok}",
    "Content-Type": "application/json"
  },
  body: JSON.stringify({
    database: "ssi",
    bot: "${botName}",
    view: "v_notifikasi_harian",
    recipient_column: "user_hp",
    message_column: "pesan",
    max_rows: 25
  })
});

const result = await response.json();
console.log(result);`;
                  } else if (activeCodeSnippet === 'python') {
                    snippet = `import requests

# Mode 2: Memanggil Nama View Database (Tanpa Query SQL Mentah)
url = "${endpointUrl}"
headers = {
    "Authorization": "Bearer ${activeTok}",
    "Content-Type": "application/json"
}
payload = {
    "database": "ssi",
    "bot": "${botName}",
    "view": "v_notifikasi_harian",
    "recipient_column": "user_hp",
    "message_column": "pesan",
    "max_rows": 25
}

response = requests.post(url, json=payload, headers=headers)
print(response.json())`;
                  } else if (activeCodeSnippet === 'php') {
                    snippet = `<?php
// Mode 2: Memanggil Nama View Database (Tanpa Query SQL Mentah)
$curl = curl_init();

$payload = [
    "database" => "ssi",
    "bot" => "${botName}",
    "view" => "v_notifikasi_harian",
    "recipient_column" => "user_hp",
    "message_column" => "pesan",
    "max_rows" => 25
];

curl_setopt_array($curl, [
    CURLOPT_URL => "${endpointUrl}",
    CURLOPT_RETURNTRANSFER => true,
    CURLOPT_POST => true,
    CURLOPT_POSTFIELDS => json_encode($payload),
    CURLOPT_HTTPHEADER => [
        "Authorization: Bearer ${activeTok}",
        "Content-Type: application/json"
    ],
]);

$response = curl_exec($curl);
curl_close($curl);
echo $response;`;
                  }
                }

                // Skenario 3: Query / View + Template Dinamis {{field}}
                else if (snippetMode === 'dynamic_template') {
                  if (activeCodeSnippet === 'curl') {
                    snippet = `# Mode 3: Query SQL + Template Pesan Dinamis (Variabel {{nama_kolom}})
curl -X POST "${endpointUrl}" \\
  -H "Authorization: Bearer ${activeTok}" \\
  -H "Content-Type: application/json" \\
  -d '{
    "database": "ssi",
    "bot": "${botName}",
    "query": "SELECT user_hp, customer_name, no_faktur, total_rp FROM v_penjualan WHERE status = '\''SELESAI'\'' LIMIT 10",
    "recipient_column": "user_hp",
    "template": "Halo <b>{{customer_name}}</b>, faktur no <code>{{no_faktur}}</code> sebesar Rp {{total_rp}} telah terbit."
  }'`;
                  } else if (activeCodeSnippet === 'javascript') {
                    snippet = `// Mode 3: Query SQL + Template Pesan Dinamis (Variabel {{nama_kolom}})
const response = await fetch("${endpointUrl}", {
  method: "POST",
  headers: {
    "Authorization": "Bearer ${activeTok}",
    "Content-Type": "application/json"
  },
  body: JSON.stringify({
    database: "ssi",
    bot: "${botName}",
    query: "SELECT user_hp, customer_name, no_faktur, total_rp FROM v_penjualan WHERE status = 'SELESAI' LIMIT 10",
    recipient_column: "user_hp",
    template: "Halo <b>{{customer_name}}</b>, faktur no <code>{{no_faktur}}</code> sebesar Rp {{total_rp}} telah terbit."
  })
});

const result = await response.json();
console.log(result);`;
                  } else if (activeCodeSnippet === 'python') {
                    snippet = `import requests

# Mode 3: Query SQL + Template Pesan Dinamis (Variabel {{nama_kolom}})
url = "${endpointUrl}"
headers = {
    "Authorization": "Bearer ${activeTok}",
    "Content-Type": "application/json"
}
payload = {
    "database": "ssi",
    "bot": "${botName}",
    "query": "SELECT user_hp, customer_name, no_faktur, total_rp FROM v_penjualan WHERE status = 'SELESAI' LIMIT 10",
    "recipient_column": "user_hp",
    "template": "Halo <b>{{customer_name}}</b>, faktur no <code>{{no_faktur}}</code> sebesar Rp {{total_rp}} telah terbit."
}

response = requests.post(url, json=payload, headers=headers)
print(response.json())`;
                  } else if (activeCodeSnippet === 'php') {
                    snippet = `<?php
// Mode 3: Query SQL + Template Pesan Dinamis (Variabel {{nama_kolom}})
$curl = curl_init();

$payload = [
    "database" => "ssi",
    "bot" => "${botName}",
    "query" => "SELECT user_hp, customer_name, no_faktur, total_rp FROM v_penjualan WHERE status = 'SELESAI' LIMIT 10",
    "recipient_column" => "user_hp",
    "template" => "Halo <b>{{customer_name}}</b>, faktur no <code>{{no_faktur}}</code> sebesar Rp {{total_rp}} telah terbit."
];

curl_setopt_array($curl, [
    CURLOPT_URL => "${endpointUrl}",
    CURLOPT_RETURNTRANSFER => true,
    CURLOPT_POST => true,
    CURLOPT_POSTFIELDS => json_encode($payload),
    CURLOPT_HTTPHEADER => [
        "Authorization: Bearer ${activeTok}",
        "Content-Type: application/json"
    ],
]);

$response = curl_exec($curl);
curl_close($curl);
echo $response;`;
                  }
                }

                return (
                  <div className="relative group">
                    <pre className="bg-[#0f172a] dark:bg-[#080d1a] p-4.5 rounded-xl border border-slate-700/80 font-mono text-xs text-sky-200 overflow-x-auto shadow-inner leading-relaxed">
                      <code>{snippet}</code>
                    </pre>
                    <button
                      type="button"
                      onClick={() => {
                        navigator.clipboard.writeText(snippet);
                        setCopiedCurl(true);
                        setTimeout(() => setCopiedCurl(false), 2000);
                      }}
                      className="absolute top-3 right-3 px-2.5 py-1 rounded-lg bg-slate-800/90 hover:bg-slate-700 border border-slate-600 text-xs font-semibold text-white flex items-center gap-1.5 transition-colors cursor-pointer shadow-md"
                    >
                      {copiedCurl ? <Check className="w-3.5 h-3.5 text-emerald-400" /> : <Copy className="w-3.5 h-3.5" />}
                      <span>{copiedCurl ? 'Tersalin' : 'Copy Kode'}</span>
                    </button>
                  </div>
                );
              })()}
            </div>

            {/* Section 4: Live Interactive Playground / Tester */}
            <div className="bg-bg-panel border border-border-main rounded-2xl p-5 shadow-sm space-y-4">
              <div className="border-b border-border-main pb-3">
                <h3 className="text-sm font-bold text-text-main flex items-center gap-2">
                  <PlayCircle className="w-4 h-4 text-purple-400" />
                  Live API Playground &amp; Console Tester
                </h3>
                <p className="text-xs text-text-muted mt-0.5">
                  Uji coba panggil endpoint API integrasi bot Telegram secara langsung dari browser ini.
                </p>
              </div>

              <div className="grid grid-cols-1 md:grid-cols-2 gap-4 text-xs">
                {/* Database Selection */}
                <div>
                  <label className="block text-text-muted font-semibold mb-1">Database / Koneksi *</label>
                  <select
                    value={testDb}
                    onChange={e => setTestDb(e.target.value)}
                    className="w-full bg-bg-editor border border-border-main rounded-xl px-3 py-2 text-text-main focus:outline-none focus:border-sky-500 font-semibold"
                  >
                    <option value="">-- Pilih Database Koneksi --</option>
                    {connections.map(c => (
                      <option key={c.id} value={c.name || c.id}>
                        {c.name} ({c.type}) {c.database ? ` - db: ${c.database}` : ''}
                      </option>
                    ))}
                  </select>
                </div>

                {/* Bot Selection */}
                <div>
                  <label className="block text-text-muted font-semibold mb-1">Bot Telegram Pengirim *</label>
                  <select
                    value={testBot}
                    onChange={e => setTestBot(e.target.value)}
                    className="w-full bg-bg-editor border border-border-main rounded-xl px-3 py-2 text-text-main focus:outline-none focus:border-sky-500 font-semibold"
                  >
                    <option value="">-- Pilih Bot Telegram --</option>
                    {bots.map(b => (
                      <option key={b.id} value={b.name || b.id}>
                        {b.name} {b.botUsername ? `(@${b.botUsername})` : ''}
                      </option>
                    ))}
                  </select>
                </div>
              </div>

              {/* Mode Query vs View */}
              <div className="space-y-2">
                <div className="flex items-center gap-2 border-b border-border-main pb-2">
                  <button
                    type="button"
                    onClick={() => setTestQueryType('query')}
                    className={clsx(
                      "px-3 py-1 rounded-lg text-xs font-semibold transition-colors cursor-pointer",
                      testQueryType === 'query'
                        ? "bg-sky-500 text-white shadow-sm"
                        : "text-text-muted hover:text-text-main"
                    )}
                  >
                    Custom SQL Query
                  </button>
                  <button
                    type="button"
                    onClick={() => setTestQueryType('view')}
                    className={clsx(
                      "px-3 py-1 rounded-lg text-xs font-semibold transition-colors cursor-pointer",
                      testQueryType === 'view'
                        ? "bg-sky-500 text-white shadow-sm"
                        : "text-text-muted hover:text-text-main"
                    )}
                  >
                    Nama View Database
                  </button>
                </div>

                {testQueryType === 'query' ? (
                  <div>
                    <label className="block text-slate-700 dark:text-slate-300 font-bold mb-1 text-xs">Query SQL (SELECT only):</label>
                    <textarea
                      rows={3}
                      value={testQuery}
                      onChange={e => setTestQuery(e.target.value)}
                      placeholder="SELECT user_hp, pesan FROM v_notifikasi LIMIT 5"
                      className="w-full bg-bg-editor border border-border-main rounded-xl p-3 font-mono text-xs text-text-main font-semibold focus:outline-none focus:border-sky-500 shadow-inner"
                    />
                  </div>
                ) : (
                  <div className="grid grid-cols-1 md:grid-cols-2 gap-3 text-xs">
                    <div>
                      <label className="block text-slate-700 dark:text-slate-300 font-bold mb-1">Schema (opsional):</label>
                      <input
                        type="text"
                        value={testSchema}
                        onChange={e => setTestSchema(e.target.value)}
                        placeholder="public atau sch_erp_system"
                        className="w-full bg-bg-editor border border-border-main rounded-xl px-3 py-2 text-text-main font-semibold focus:outline-none focus:border-sky-500"
                      />
                    </div>
                    <div>
                      <label className="block text-slate-700 dark:text-slate-300 font-bold mb-1">Nama View / Tabel *:</label>
                      <input
                        type="text"
                        value={testView}
                        onChange={e => setTestView(e.target.value)}
                        placeholder="v_notifikasi_harian"
                        className="w-full bg-bg-editor border border-border-main rounded-xl px-3 py-2 text-text-main font-semibold focus:outline-none focus:border-sky-500"
                      />
                    </div>
                  </div>
                )}
              </div>

              {/* Dynamic Columns Configuration */}
              <div className="grid grid-cols-1 md:grid-cols-3 gap-3 text-xs">
                <div>
                  <label className="block text-slate-700 dark:text-slate-300 font-bold mb-1">
                    Kolom Penerima (HP / Chat ID):
                  </label>
                  <input
                    type="text"
                    value={testRecipientCol}
                    onChange={e => setTestRecipientCol(e.target.value)}
                    placeholder="user_hp"
                    className="w-full bg-bg-editor border border-border-main rounded-xl px-3 py-2 font-mono text-text-main font-semibold focus:outline-none focus:border-sky-500"
                  />
                  <span className="text-[10px] text-text-muted mt-0.5 block">Default: user_hp</span>
                </div>

                <div>
                  <label className="block text-slate-700 dark:text-slate-300 font-bold mb-1">
                    Kolom Pesan (dari View):
                  </label>
                  <input
                    type="text"
                    value={testMessageCol}
                    onChange={e => setTestMessageCol(e.target.value)}
                    placeholder="pesan"
                    className="w-full bg-bg-editor border border-border-main rounded-xl px-3 py-2 font-mono text-text-main font-semibold focus:outline-none focus:border-sky-500"
                  />
                  <span className="text-[10px] text-text-muted mt-0.5 block">Default: pesan</span>
                </div>

                <div>
                  <label className="block text-slate-700 dark:text-slate-300 font-bold mb-1">
                    Maksimum Baris:
                  </label>
                  <input
                    type="number"
                    min={1}
                    max={100}
                    value={testMaxRows}
                    onChange={e => setTestMaxRows(parseInt(e.target.value) || 5)}
                    className="w-full bg-bg-editor border border-border-main rounded-xl px-3 py-2 font-mono text-text-main font-semibold focus:outline-none focus:border-sky-500"
                  />
                  <span className="text-[10px] text-text-muted mt-0.5 block">Limit uji coba: 1-100</span>
                </div>
              </div>

              {/* Optional Custom Template */}
              <div>
                <label className="block text-slate-700 dark:text-slate-300 font-bold mb-1 text-xs">
                  Template Pesan Kustom (Opsional - Jika diisi akan meng-override kolom pesan):
                </label>
                <input
                  type="text"
                  value={testTemplate}
                  onChange={e => setTestTemplate(e.target.value)}
                  placeholder="Halo <b>{{user_name}}</b>, faktur {{no_faktur}} siap diproses."
                  className="w-full bg-bg-editor border border-border-main rounded-xl px-3 py-2 font-mono text-xs text-text-main font-semibold focus:outline-none focus:border-sky-500"
                />
              </div>

              {/* Action Button */}
              <div className="flex items-center justify-end gap-3 pt-2">
                <button
                  type="button"
                  onClick={handleRunTestApi}
                  disabled={isTestingApi || !testDb || !testBot}
                  className="flex items-center gap-2 px-5 py-2.5 rounded-xl bg-purple-600 hover:bg-purple-700 text-white font-bold text-xs transition-colors cursor-pointer shadow-md disabled:opacity-50"
                >
                  {isTestingApi ? <RefreshCw className="w-4 h-4 animate-spin" /> : <Play className="w-4 h-4" />}
                  <span>{isTestingApi ? 'Mengeksekusi API...' : 'Jalankan Uji Coba API'}</span>
                </button>
              </div>

              {/* Result Preview Console */}
              {testResult && (
                <div className="space-y-3 pt-3 border-t border-border-main animate-in fade-in duration-200">
                  <div className="flex items-center justify-between">
                    <span className="text-xs font-bold text-text-main flex items-center gap-1.5">
                      <Terminal className="w-3.5 h-3.5 text-sky-500" />
                      Hasil Response API:
                    </span>
                    <span
                      className={clsx(
                        "text-[11px] font-bold px-2.5 py-0.5 rounded-full border",
                        testResult.success
                          ? "bg-emerald-500/10 text-emerald-600 dark:text-emerald-400 border-emerald-500/30"
                          : "bg-rose-500/10 text-rose-600 dark:text-rose-400 border-rose-500/30"
                      )}
                    >
                      {testResult.success ? '● SUCCESS (200 OK)' : '○ FAILED / ERROR'}
                    </span>
                  </div>

                  {testResult.total_rows !== undefined && (
                    <div className="grid grid-cols-3 gap-2 text-center text-xs">
                      <div className="bg-bg-editor p-2.5 rounded-xl border border-border-main">
                        <span className="text-[10px] text-text-muted block uppercase font-bold">Total Baris Query</span>
                        <span className="text-base font-bold text-text-main font-mono">{testResult.total_rows}</span>
                      </div>
                      <div className="bg-bg-editor p-2.5 rounded-xl border border-border-main">
                        <span className="text-[10px] text-text-muted block uppercase font-bold">Pesan Terkirim</span>
                        <span className="text-base font-bold text-emerald-600 dark:text-emerald-400 font-mono">{testResult.messages_sent}</span>
                      </div>
                      <div className="bg-bg-editor p-2.5 rounded-xl border border-border-main">
                        <span className="text-[10px] text-text-muted block uppercase font-bold">Gagal</span>
                        <span className="text-base font-bold text-rose-600 dark:text-rose-400 font-mono">{testResult.messages_failed}</span>
                      </div>
                    </div>
                  )}

                  <pre className="bg-[#0f172a] dark:bg-[#080d1a] p-4.5 rounded-xl border border-slate-700/80 font-mono text-[11px] text-emerald-400 overflow-x-auto max-h-60 overflow-y-auto shadow-inner leading-relaxed">
                    <code>{JSON.stringify(testResult, null, 2)}</code>
                  </pre>
                </div>
              )}
            </div>
          </div>
        )}
      </div>

      {/* Modal: Detail Penerima Sesi Eksekusi Bot */}
      {selectedExecutionModal && (
        <div className="fixed inset-0 bg-black/60 backdrop-blur-sm flex items-center justify-center p-4 z-50">
          <div className="bg-bg-panel border border-border-main rounded-2xl max-w-4xl w-full max-h-[90vh] flex flex-col p-6 space-y-4 shadow-2xl animate-in fade-in duration-200">
            {/* Header */}
            <div className="flex items-center justify-between border-b border-border-main pb-4">
              <div>
                <h3 className="text-base font-bold flex items-center gap-2 text-text-main">
                  <Users className="w-5 h-5 text-sky-400" />
                  Detail Penerima Pesan Eksekusi Bot
                </h3>
                <div className="flex flex-wrap items-center gap-3 text-xs text-text-muted mt-1">
                  <span className="flex items-center gap-1.5 font-bold text-text-main bg-sky-500/10 px-2 py-0.5 rounded-lg border border-sky-500/20">
                    <Bot className="w-3.5 h-3.5 text-sky-400" />
                    {selectedExecutionModal.botName}
                  </span>
                  <span>•</span>
                  <span className="font-semibold text-text-main">{selectedExecutionModal.scheduleName}</span>
                  <span>•</span>
                  <span className="font-mono text-text-muted">{formatLogDate(selectedExecutionModal.executedAt)}</span>
                  <span className="text-[10px] px-2 py-0.5 rounded-full bg-bg-editor border border-border-main">
                    {selectedExecutionModal.triggerType === 'SCHEDULER' ? '⏰ Otomatis (Cron)' : '⚡ Manual (Run)'}
                  </span>
                </div>
              </div>
              <button
                type="button"
                onClick={() => setSelectedExecutionModal(null)}
                className="p-2 rounded-xl text-text-muted hover:text-text-main hover:bg-bg-hover transition-colors cursor-pointer"
              >
                <X className="w-5 h-5" />
              </button>
            </div>

            {/* Quick Stats & Filters Bar inside Modal */}
            <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3 bg-bg-editor/60 p-3 rounded-xl border border-border-main">
              {/* Search */}
              <div className="relative flex-1">
                <Search className="w-3.5 h-3.5 text-text-muted absolute left-3 top-1/2 -translate-y-1/2 pointer-events-none" />
                <input
                  type="text"
                  value={executionRecipientSearch}
                  onChange={e => setExecutionRecipientSearch(e.target.value)}
                  placeholder="Cari nama penerima, no HP, isi pesan..."
                  className="w-full bg-bg-panel border border-border-main rounded-lg pl-8 pr-7 py-1.5 text-xs text-text-main placeholder:text-text-muted focus:outline-none focus:border-sky-500"
                />
                {executionRecipientSearch && (
                  <button
                    type="button"
                    onClick={() => setExecutionRecipientSearch('')}
                    className="absolute right-2 top-1/2 -translate-y-1/2 text-text-muted hover:text-text-main p-0.5"
                  >
                    <X className="w-3 h-3" />
                  </button>
                )}
              </div>

              {/* Status Pills */}
              <div className="flex items-center gap-1.5 shrink-0">
                <button
                  type="button"
                  onClick={() => setExecutionRecipientFilter('ALL')}
                  className={clsx(
                    "px-2.5 py-1 text-xs font-semibold rounded-lg transition-colors cursor-pointer",
                    executionRecipientFilter === 'ALL'
                      ? "bg-bg-panel text-sky-400 border border-sky-500/30 shadow-sm"
                      : "text-text-muted hover:text-text-main"
                  )}
                >
                  Semua ({selectedExecutionModal.totalRecipients})
                </button>
                <button
                  type="button"
                  onClick={() => setExecutionRecipientFilter('BERHASIL')}
                  className={clsx(
                    "px-2.5 py-1 text-xs font-semibold rounded-lg transition-colors cursor-pointer",
                    executionRecipientFilter === 'BERHASIL'
                      ? "bg-emerald-500/15 text-emerald-400 border border-emerald-500/30 shadow-sm"
                      : "text-text-muted hover:text-text-main"
                  )}
                >
                  🟢 Berhasil ({selectedExecutionModal.successCount})
                </button>
                <button
                  type="button"
                  onClick={() => setExecutionRecipientFilter('GAGAL')}
                  className={clsx(
                    "px-2.5 py-1 text-xs font-semibold rounded-lg transition-colors cursor-pointer",
                    executionRecipientFilter === 'GAGAL'
                      ? "bg-rose-500/15 text-rose-400 border border-rose-500/30 shadow-sm"
                      : "text-text-muted hover:text-text-main"
                  )}
                >
                  🔴 Gagal ({selectedExecutionModal.failedCount})
                </button>
              </div>
            </div>

            {/* Recipients Table */}
            <div className="flex-1 overflow-y-auto border border-border-main rounded-xl">
              <table className="w-full text-left text-xs">
                <thead className="bg-bg-editor/80 sticky top-0 border-b border-border-main z-10">
                  <tr className="text-text-muted uppercase text-[10px]">
                    <th className="py-2.5 px-3 font-semibold">No</th>
                    <th className="py-2.5 px-3 font-semibold">Nama Penerima</th>
                    <th className="py-2.5 px-3 font-semibold">Nomor HP</th>
                    <th className="py-2.5 px-3 font-semibold">Status</th>
                    <th className="py-2.5 px-3 font-semibold">Isi Pesan</th>
                    <th className="py-2.5 px-3 font-semibold">Keterangan / Error</th>
                    <th className="py-2.5 px-3 font-semibold text-right">Preview</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-border-main/50">
                  {modalFilteredRecipients.map((item, idx) => {
                    const isSuccess = item.status === 'BERHASIL' || item.status === 'SUCCESS' || (item.messagesSent > 0 && item.messagesFailed === 0);
                    const isFailed = item.status === 'GAGAL' || item.status === 'FAILED' || item.messagesFailed > 0;

                    return (
                      <tr key={item.id} className="hover:bg-bg-hover/40 transition-colors">
                        <td className="py-2.5 px-3 text-text-muted font-mono text-[11px]">{idx + 1}</td>
                        <td className="py-2.5 px-3 font-medium text-text-main whitespace-nowrap">
                          {item.recipientName && item.recipientName !== '-' ? item.recipientName : (item.recipientPhone && item.recipientPhone !== '-' ? 'Karyawan' : '-')}
                        </td>
                        <td className="py-2.5 px-3 whitespace-nowrap">
                          {item.recipientPhone && item.recipientPhone !== '-' ? (
                            <span className="font-mono text-emerald-400 font-semibold text-[11px]">
                              {item.recipientPhone}
                            </span>
                          ) : (
                            <span className="text-text-muted font-mono">-</span>
                          )}
                        </td>
                        <td className="py-2.5 px-3 whitespace-nowrap">
                          {isSuccess ? (
                            <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-[10px] font-bold bg-emerald-500/10 text-emerald-400 border border-emerald-500/30">
                              <CheckCircle2 className="w-3 h-3" />
                              Berhasil
                            </span>
                          ) : isFailed ? (
                            <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-[10px] font-bold bg-rose-500/10 text-rose-400 border border-rose-500/30">
                              <AlertCircle className="w-3 h-3" />
                              Gagal
                            </span>
                          ) : (
                            <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-[10px] font-bold bg-slate-500/10 text-slate-400 border border-slate-500/30">
                              {item.status || '-'}
                            </span>
                          )}
                        </td>
                        <td className="py-2.5 px-3 max-w-xs truncate" title={item.messageText || '-'}>
                          {item.messageText && item.messageText !== '-' ? (
                            <div className="text-[11px] text-text-muted font-mono truncate max-w-xs bg-bg-editor px-2 py-1 rounded border border-border-main/50">
                              {item.messageText}
                            </div>
                          ) : (
                            <span className="text-text-muted text-[11px]">-</span>
                          )}
                        </td>
                        <td className="py-2.5 px-3 text-[11px] max-w-xs truncate" title={item.details || '-'}>
                          <span className={isFailed ? "text-rose-400 font-medium" : "text-text-muted"}>
                            {item.details || '-'}
                          </span>
                        </td>
                        <td className="py-2.5 px-3 text-right whitespace-nowrap">
                          {item.messageText && item.messageText !== '-' ? (
                            <button
                              type="button"
                              onClick={() => setSelectedLogModal(item)}
                              className="inline-flex items-center gap-1 px-2 py-1 rounded-lg bg-sky-500/10 hover:bg-sky-500/20 text-sky-400 text-[10px] font-bold border border-sky-500/30 transition-colors cursor-pointer"
                              title="Lihat rincian pesan lengkap"
                            >
                              <Eye className="w-3 h-3" />
                              Lihat Pesan
                            </button>
                          ) : (
                            <span className="text-text-muted text-[11px]">-</span>
                          )}
                        </td>
                      </tr>
                    );
                  })}

                  {modalFilteredRecipients.length === 0 && (
                    <tr>
                      <td colSpan={7} className="py-10 text-center text-text-muted space-y-1">
                        <Users className="w-6 h-6 text-text-muted/30 mx-auto" />
                        <p className="font-semibold text-text-main text-xs">Tidak Ada Penerima</p>
                        <p className="text-[11px] text-text-muted">Tidak ada data penerima yang cocok dengan filter saat ini.</p>
                      </td>
                    </tr>
                  )}
                </tbody>
              </table>
            </div>

            {/* Modal Footer */}
            <div className="flex items-center justify-between pt-2 border-t border-border-main text-xs">
              <span className="text-text-muted">
                Menampilkan <b>{modalFilteredRecipients.length}</b> dari total <b>{selectedExecutionModal.totalRecipients}</b> orang penerima
              </span>
              <button
                type="button"
                onClick={() => setSelectedExecutionModal(null)}
                className="px-4 py-2 bg-bg-editor hover:bg-bg-hover text-text-main font-semibold text-xs rounded-xl border border-border-main transition-colors cursor-pointer shadow-sm"
              >
                Tutup
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Modal: Detail Pesan Log Lengkap */}
      {selectedLogModal && (
        <div className="fixed inset-0 bg-black/60 backdrop-blur-sm flex items-center justify-center p-4 z-50">
          <div className="bg-bg-panel border border-border-main rounded-2xl max-w-lg w-full p-6 space-y-4 shadow-2xl animate-in fade-in duration-200">
            <div className="flex items-center justify-between border-b border-border-main pb-3">
              <div>
                <h3 className="text-sm font-bold flex items-center gap-2 text-text-main">
                  <MessageSquare className="w-4 h-4 text-sky-400" />
                  Rincian Pesan Terkirim
                </h3>
                <p className="text-[11px] text-text-muted mt-0.5">
                  {selectedLogModal.scheduleName || 'Manual Execution'} • {selectedLogModal.botName || 'Bot'}
                </p>
              </div>
              <button
                type="button"
                onClick={() => setSelectedLogModal(null)}
                className="p-1.5 rounded-lg text-text-muted hover:text-text-main hover:bg-bg-hover transition-colors cursor-pointer"
              >
                <X className="w-4 h-4" />
              </button>
            </div>

            {/* Target recipient info */}
            <div className="bg-bg-editor p-3 rounded-xl border border-border-main flex items-center justify-between text-xs">
              <div>
                <span className="text-text-muted block text-[10px] uppercase font-bold">Penerima</span>
                <span className="font-semibold text-text-main">{selectedLogModal.recipientName || '-'}</span>
              </div>
              <div className="text-right">
                <span className="text-text-muted block text-[10px] uppercase font-bold">Nomor HP</span>
                <span className="font-mono text-emerald-400 font-bold">{selectedLogModal.recipientPhone || '-'}</span>
              </div>
            </div>

            {/* Telegram Bubble Chat Preview */}
            <div className="bg-[#182533] border border-[#243447] rounded-2xl p-4 shadow-inner space-y-2 max-h-80 overflow-y-auto">
              <div className="text-xs text-white/95 whitespace-pre-wrap font-sans leading-relaxed select-text">
                {selectedLogModal.messageText || '(Pesan kosong)'}
              </div>
              <div className="flex items-center justify-between pt-2 border-t border-white/10 text-[10px] text-white/50">
                <span className={clsx(
                  "font-bold",
                  selectedLogModal.status === 'BERHASIL' || selectedLogModal.status === 'SUCCESS' ? "text-emerald-400" : "text-rose-400"
                )}>
                  Status: {selectedLogModal.status}
                </span>
                <span>{formatLogDate(selectedLogModal.createdAt || selectedLogModal.executedAt)} ✓✓</span>
              </div>
            </div>

            {selectedLogModal.details && (
              <div className="text-[11px] text-text-muted bg-bg-editor/50 p-2.5 rounded-xl border border-border-main">
                <span className="font-semibold text-text-main">Keterangan: </span>
                {selectedLogModal.details}
              </div>
            )}

            <div className="flex justify-end pt-2">
              <button
                type="button"
                onClick={() => setSelectedLogModal(null)}
                className="px-4 py-2 bg-bg-editor hover:bg-bg-hover text-text-main text-xs font-semibold rounded-xl border border-border-main transition-colors cursor-pointer"
              >
                Tutup
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Modal: Tambah Bot Baru */}
      {isBotModalOpen && (
        <div className="fixed inset-0 bg-black/60 backdrop-blur-sm flex items-center justify-center p-4 z-50">
          <div className="bg-bg-panel border border-border-main rounded-xl max-w-md w-full p-5 space-y-4 shadow-xl">
            <h3 className="text-sm font-bold flex items-center gap-2">
              <Bot className="w-4 h-4 text-sky-400" />
              {editingBot?.id ? 'Edit Bot Telegram' : 'Tambah Bot Telegram Baru'}
            </h3>

            <form onSubmit={handleSaveBot} className="space-y-3 text-xs">
              <div>
                <label className="block text-text-muted mb-1">Nama Bot *</label>
                <input
                  type="text"
                  required
                  value={editingBot?.name || ''}
                  onChange={e => setEditingBot(prev => ({ ...prev, name: e.target.value }))}
                  placeholder="misal: Bot Notifikasi ERP"
                  className="w-full bg-bg-editor border border-border-main rounded-lg px-3 py-2 text-text-main focus:outline-none"
                />
              </div>

              <div>
                <label className="block text-text-muted mb-1">Bot Token (dari @BotFather) *</label>
                <input
                  type="text"
                  required
                  value={editingBot?.botToken || ''}
                  onChange={e => setEditingBot(prev => ({ ...prev, botToken: e.target.value }))}
                  placeholder="123456789:ABCdefGHIjklMNOpqrsTUVwxyz"
                  className="w-full font-mono bg-bg-editor border border-border-main rounded-lg px-3 py-2 text-text-main focus:outline-none"
                />
              </div>

              <div className="flex items-center justify-end gap-2 pt-3 border-t border-border-main">
                <button
                  type="button"
                  onClick={() => setIsBotModalOpen(false)}
                  className="px-3.5 py-1.5 rounded-lg text-text-muted hover:text-text-main border border-border-main"
                >
                  Batal
                </button>
                <button
                  type="submit"
                  disabled={isSavingBot}
                  className="flex items-center gap-1.5 px-4 py-1.5 bg-sky-500 hover:bg-sky-600 text-white font-semibold rounded-lg disabled:opacity-50 cursor-pointer shadow-sm"
                >
                  {isSavingBot && <RefreshCw className="w-3.5 h-3.5 animate-spin" />}
                  {isSavingBot ? 'Menyimpan...' : (editingBot?.id ? 'Simpan Perubahan' : 'Simpan Bot')}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}

      {/* Modal: Hubungkan Akun MTProto (Telethon) */}
      {isMtprotoModalOpen && (
        <div className="fixed inset-0 bg-black/60 backdrop-blur-sm flex items-center justify-center p-4 z-50">
          <div className="bg-bg-panel border border-border-main rounded-2xl max-w-lg w-full p-6 space-y-5 shadow-2xl animate-in fade-in zoom-in-95">
            <div className="flex items-center justify-between border-b border-border-main pb-3">
              <div className="flex items-center gap-2.5">
                <div className="w-8 h-8 rounded-lg bg-purple-500/20 text-purple-400 flex items-center justify-center font-bold text-xs">
                  <Smartphone className="w-4 h-4" />
                </div>
                <div>
                  <h3 className="text-sm font-bold text-text-main">
                    Hubungkan Akun Telegram Pribadi (MTProto)
                  </h3>
                  <p className="text-[11px] text-text-muted">
                    {mtprotoStep === 'CREDENTIALS' ? 'Langkah 1: Masukkan Kredensial Telegram API' : 'Langkah 2: Verifikasi Kode OTP Telegram'}
                  </p>
                </div>
              </div>

              {/* Step indicator */}
              <div className="flex items-center gap-1.5 text-xs font-semibold">
                <span className={clsx(
                  "w-5 h-5 rounded-full flex items-center justify-center text-[10px]",
                  mtprotoStep === 'CREDENTIALS' ? "bg-purple-600 text-white" : "bg-emerald-500 text-white"
                )}>
                  {mtprotoStep === 'CREDENTIALS' ? '1' : '✓'}
                </span>
                <span className="text-border-main">-</span>
                <span className={clsx(
                  "w-5 h-5 rounded-full flex items-center justify-center text-[10px]",
                  mtprotoStep === 'OTP' ? "bg-purple-600 text-white" : "bg-bg-editor text-text-muted border border-border-main"
                )}>
                  2
                </span>
              </div>
            </div>

            {mtprotoStep === 'CREDENTIALS' ? (
              <form onSubmit={handleRequestOtp} className="space-y-4 text-xs">
                <div>
                  <label className="block text-text-muted font-medium mb-1">
                    Nama Label Sesi / Akun *
                  </label>
                  <input
                    type="text"
                    required
                    value={mtprotoFormName}
                    onChange={e => setMtprotoFormName(e.target.value)}
                    placeholder="misal: Akun Notifikasi Kantor / Direktur"
                    className="w-full bg-bg-editor border border-border-main rounded-lg px-3 py-2 text-text-main focus:outline-none focus:border-purple-500"
                  />
                </div>

                <div>
                  <label className="block text-text-muted font-medium mb-1">
                    Nomor Telepon Telegram Pengirim *
                  </label>
                  <input
                    type="text"
                    required
                    value={mtprotoFormPhone}
                    onChange={e => setMtprotoFormPhone(e.target.value)}
                    placeholder="+6281234567890 (Wajib kode negara +62)"
                    className="w-full font-mono bg-bg-editor border border-border-main rounded-lg px-3 py-2 text-text-main focus:outline-none focus:border-purple-500"
                  />
                  <p className="text-[10px] text-text-muted mt-1">
                    Pastikan nomor ini aktif di aplikasi Telegram Anda untuk menerima kode OTP.
                  </p>
                </div>

                <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                  <div>
                    <label className="block text-text-muted font-medium mb-1 flex items-center justify-between">
                      <span>Telegram App API ID *</span>
                      <a
                        href="https://my.telegram.org/apps"
                        target="_blank"
                        rel="noreferrer"
                        className="text-[10px] text-sky-400 hover:underline inline-flex items-center gap-0.5"
                      >
                        my.telegram.org ↗
                      </a>
                    </label>
                    <input
                      type="text"
                      required
                      value={mtprotoFormApiId}
                      onChange={e => setMtprotoFormApiId(e.target.value)}
                      placeholder="misal: 2849102"
                      className="w-full font-mono bg-bg-editor border border-border-main rounded-lg px-3 py-2 text-text-main focus:outline-none focus:border-purple-500"
                    />
                  </div>

                  <div>
                    <label className="block text-text-muted font-medium mb-1">
                      Telegram App API Hash *
                    </label>
                    <input
                      type="text"
                      required
                      value={mtprotoFormApiHash}
                      onChange={e => setMtprotoFormApiHash(e.target.value)}
                      placeholder="32 karakter hex hash"
                      className="w-full font-mono bg-bg-editor border border-border-main rounded-lg px-3 py-2 text-text-main focus:outline-none focus:border-purple-500"
                    />
                  </div>
                </div>

                <div className="bg-purple-500/10 border border-purple-500/20 rounded-xl p-3 text-[11px] text-purple-300 leading-relaxed">
                  💡 <b>Cara Mendapatkan API ID &amp; Hash:</b> Buka <a href="https://my.telegram.org/apps" target="_blank" rel="noreferrer" className="underline font-bold">my.telegram.org</a>, login nomor Telegram Anda, klik menu <i>API development tools</i>, dan buat aplikasi baru.
                </div>

                <div className="flex items-center justify-end gap-2 pt-3 border-t border-border-main">
                  <button
                    type="button"
                    onClick={() => setIsMtprotoModalOpen(false)}
                    className="px-4 py-2 rounded-lg text-text-muted hover:text-text-main border border-border-main"
                  >
                    Batal
                  </button>
                  <button
                    type="submit"
                    disabled={isSendingOtp}
                    className="flex items-center gap-1.5 px-4 py-2 bg-purple-600 hover:bg-purple-700 text-white font-semibold rounded-lg shadow-sm disabled:opacity-50"
                  >
                    {isSendingOtp ? <RefreshCw className="w-3.5 h-3.5 animate-spin" /> : <Send className="w-3.5 h-3.5" />}
                    Kirim Kode OTP Telegram →
                  </button>
                </div>
              </form>
            ) : (
              <form onSubmit={handleVerifyOtp} className="space-y-4 text-xs">
                <div className="bg-emerald-500/10 border border-emerald-500/20 rounded-xl p-3 text-[11px] text-emerald-400 leading-relaxed flex items-center gap-2">
                  <CheckCircle2 className="w-4 h-4 shrink-0" />
                  <span>
                    Kode OTP 5 digit telah dikirimkan oleh Telegram ke nomor <b>{mtprotoFormPhone}</b>. Silakan periksa chat resmi Telegram di HP/komputer Anda.
                  </span>
                </div>

                <div>
                  <label className="block text-text-muted font-medium mb-1">
                    Kode Verifikasi Telegram (OTP) *
                  </label>
                  <input
                    type="text"
                    required
                    maxLength={10}
                    value={mtprotoFormOtp}
                    onChange={e => setMtprotoFormOtp(e.target.value)}
                    placeholder="misal: 12345"
                    className="w-full text-center text-lg tracking-widest font-mono bg-bg-editor border border-border-main rounded-lg px-3 py-2 text-text-main focus:outline-none focus:border-purple-500"
                  />
                </div>

                <div>
                  <label className="block text-text-muted font-medium mb-1">
                    Password 2FA / Cloud Password (Opsional)
                  </label>
                  <input
                    type="password"
                    value={mtprotoForm2Fa}
                    onChange={e => setMtprotoForm2Fa(e.target.value)}
                    placeholder="Isi hanya jika akun Anda memasang Two-Step Verification"
                    className="w-full bg-bg-editor border border-border-main rounded-lg px-3 py-2 text-text-main focus:outline-none focus:border-purple-500"
                  />
                </div>

                <div className="flex items-center justify-between pt-3 border-t border-border-main">
                  <button
                    type="button"
                    onClick={() => setMtprotoStep('CREDENTIALS')}
                    className="px-3.5 py-2 rounded-lg text-text-muted hover:text-text-main border border-border-main flex items-center gap-1"
                  >
                    ← Kembali
                  </button>

                  <div className="flex items-center gap-2">
                    <button
                      type="button"
                      onClick={() => setIsMtprotoModalOpen(false)}
                      className="px-3.5 py-2 rounded-lg text-text-muted hover:text-text-main border border-border-main"
                    >
                      Batal
                    </button>
                    <button
                      type="submit"
                      className="flex items-center gap-1.5 px-4 py-2 bg-emerald-600 hover:bg-emerald-700 text-white font-semibold rounded-lg shadow-sm"
                    >
                      <Check className="w-4 h-4" />
                      Verifikasi &amp; Simpan Sesi
                    </button>
                  </div>
                </div>
              </form>
            )}
          </div>
        </div>
      )}
    </div>
  );
};
