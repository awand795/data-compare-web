// @ts-nocheck
import React, { useState, useEffect } from 'react';
import axios from 'axios';
import { useAppStore } from '../store/useAppStore';
import {
  Send, Bot, Users, CalendarClock, Settings2, History, Play, Pause, Trash2, Edit3,
  Plus, CheckCircle2, AlertCircle, RefreshCw, Copy, ExternalLink, Code2, Database,
  Sparkles, ShieldAlert, MessageSquare, ChevronRight, Check, X, Info, Filter, Layers, Eye
} from 'lucide-react';
import clsx from 'clsx';

interface TelegramBot {
  id: string;
  name: string;
  botToken: string;
  botUsername?: string;
  isPollingActive: boolean;
  createdAt?: string;
}

interface TelegramContact {
  id: string;
  botId: string;
  chatId: string;
  phoneNumber?: string;
  firstName?: string;
  lastName?: string;
  username?: string;
  isActive: boolean;
  registeredAt?: string;
}

interface TelegramSchedule {
  id: string;
  name: string;
  botId: string;
  connectionId: string;
  schemaName?: string;
  viewName?: string;
  filterSchedule?: string;
  filterKodeNotifikasi?: string;
  filterKodeCabang?: string;
  sqlQuery: string;
  templateMessage: string;
  recipientMode: 'MASTER' | 'DYNAMIC_COLUMN';
  masterRecipientIds?: string;
  phoneColumnName?: string;
  cronExpression: string;
  maxRows: number;
  active: boolean;
  createdAt?: string;
  lastRunAt?: string;
  lastRunStatus?: string;
  lastRunMessage?: string;
}

interface TelegramLog {
  id: string;
  scheduleId?: string;
  scheduleName?: string;
  botId?: string;
  botName?: string;
  triggerType: string;
  totalRows: number;
  messagesSent: number;
  messagesFailed: number;
  status: string;
  details?: string;
  createdAt?: string;
}

export const TelegramBotView: React.FC = () => {
  const { connections, showAlert } = useAppStore();

  const [activeTab, setActiveTab] = useState<'editor' | 'scheduler' | 'contacts' | 'settings' | 'logs'>('editor');

  // Data states
  const [bots, setBots] = useState<TelegramBot[]>([]);
  const [contacts, setContacts] = useState<TelegramContact[]>([]);
  const [schedules, setSchedules] = useState<TelegramSchedule[]>([]);
  const [logs, setLogs] = useState<TelegramLog[]>([]);

  // Source View Mode vs Custom SQL Mode
  const [sourceMode, setSourceMode] = useState<'VIEW' | 'CUSTOM_SQL'>('VIEW');

  // Connection & Schema & View states
  const [selectedBotId, setSelectedBotId] = useState<string>('');
  const [selectedConnectionId, setSelectedConnectionId] = useState<string>('');
  const [schemas, setSchemas] = useState<string[]>([]);
  const [selectedSchema, setSelectedSchema] = useState<string>('sch_erp_system');
  const [views, setViews] = useState<{ name: string; type: string }[]>([]);
  const [selectedView, setSelectedView] = useState<string>('view_all_notifikasi');

  // Filters for View
  const [filterSchedule, setFilterSchedule] = useState<string>('ALL'); // ALL, Y, N
  const [filterKodeNotifikasi, setFilterKodeNotifikasi] = useState<string>('');
  const [filterKodeCabang, setFilterKodeCabang] = useState<string>('');

  // SQL & Template states
  const [sqlQuery, setSqlQuery] = useState<string>(
    "SELECT kode_notifikasi, kode_cabang, no_tran, user_id, user_name, user_hp, pesan, schedule FROM sch_erp_system.view_all_notifikasi"
  );
  const [templateMessage, setTemplateMessage] = useState<string>('{{pesan}}');
  const [recipientMode, setRecipientMode] = useState<'MASTER' | 'DYNAMIC_COLUMN'>('DYNAMIC_COLUMN');
  const [selectedContactIds, setSelectedContactIds] = useState<string[]>([]);
  const [phoneColumnName, setPhoneColumnName] = useState<string>('user_hp');
  const [maxRows, setMaxRows] = useState<number>(50);

  // Preview & View Data states
  const [previewRows, setPreviewRows] = useState<any[]>([]);
  const [previewColumns, setPreviewColumns] = useState<string[]>([]);
  const [previewLoading, setPreviewLoading] = useState(false);
  const [sendingManual, setSendingManual] = useState(false);
  const [senderMethod, setSenderMethod] = useState<'BOT_API' | 'MTPROTO_USER'>('BOT_API');

  // Modals
  const [isBotModalOpen, setIsBotModalOpen] = useState(false);
  const [editingBot, setEditingBot] = useState<Partial<TelegramBot> | null>(null);
  const [testBotResult, setTestBotResult] = useState<{ success: boolean; message?: string; info?: any } | null>(null);

  const [isScheduleModalOpen, setIsScheduleModalOpen] = useState(false);
  const [editingSchedule, setEditingSchedule] = useState<Partial<TelegramSchedule> | null>(null);

  const [isContactModalOpen, setIsContactModalOpen] = useState(false);
  const [editingContact, setEditingContact] = useState<Partial<TelegramContact> | null>(null);

  // Initial Load
  useEffect(() => {
    fetchBots();
    fetchContacts();
    fetchSchedules();
    fetchLogs();
  }, []);

  // Pick default connection (prefer DEV-POS if available)
  useEffect(() => {
    if (connections.length > 0 && !selectedConnectionId) {
      const devPos = connections.find(c => c.name.toLowerCase().includes('dev-pos') || c.database?.toLowerCase().includes('dev_pos'));
      setSelectedConnectionId(devPos ? devPos.id : connections[0].id);
    }
  }, [connections]);

  // Load schemas when selected connection changes
  useEffect(() => {
    if (selectedConnectionId) {
      loadSchemas(selectedConnectionId);
    }
  }, [selectedConnectionId]);

  // Load views/tables when schema changes
  useEffect(() => {
    if (selectedConnectionId && selectedSchema) {
      loadViews(selectedConnectionId, selectedSchema);
    }
  }, [selectedConnectionId, selectedSchema]);

  // Automatically update SQL Query when in VIEW mode
  useEffect(() => {
    if (sourceMode === 'VIEW' && selectedSchema && selectedView) {
      let q = `SELECT kode_notifikasi, kode_cabang, no_tran, user_id, user_name, user_hp, pesan, schedule FROM ${selectedSchema}.${selectedView} WHERE 1=1`;
      if (filterSchedule === 'Y' || filterSchedule === 'N') {
        q += ` AND schedule = '${filterSchedule}'`;
      }
      if (filterKodeNotifikasi.trim()) {
        q += ` AND kode_notifikasi = '${filterKodeNotifikasi.trim()}'`;
      }
      if (filterKodeCabang.trim()) {
        q += ` AND kode_cabang = '${filterKodeCabang.trim()}'`;
      }
      setSqlQuery(q);
      setPhoneColumnName('user_hp');
      setRecipientMode('DYNAMIC_COLUMN');
    }
  }, [sourceMode, selectedSchema, selectedView, filterSchedule, filterKodeNotifikasi, filterKodeCabang]);

  const loadSchemas = async (connId: string) => {
    try {
      const res = await axios.get(`/api/connections/${connId}/schemas`);
      if (Array.isArray(res.data)) {
        setSchemas(res.data);
        if (res.data.includes('sch_erp_system')) {
          setSelectedSchema('sch_erp_system');
        } else if (res.data.length > 0) {
          setSelectedSchema(res.data[0]);
        }
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
        const hasNotifView = viewList.some(v => v.name.toLowerCase().includes('view_all_notifikasi'));
        if (hasNotifView) {
          setSelectedView('view_all_notifikasi');
        } else if (viewList.length > 0) {
          setSelectedView(viewList[0].name);
        }
      }
    } catch (e) {
      console.error('Failed to load views', e);
    }
  };

  const fetchBots = async () => {
    try {
      const res = await axios.get('/api/telegram/bots');
      setBots(res.data);
      if (res.data.length > 0 && !selectedBotId) {
        setSelectedBotId(res.data[0].id);
      }
    } catch (e) {
      console.error('Failed to fetch bots', e);
    }
  };

  const fetchContacts = async (botId?: string) => {
    try {
      const url = botId ? `/api/telegram/contacts?botId=${botId}` : '/api/telegram/contacts';
      const res = await axios.get(url);
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

  const fetchLogs = async () => {
    try {
      const res = await axios.get('/api/telegram/logs');
      setLogs(res.data);
    } catch (e) {
      console.error('Failed to fetch logs', e);
    }
  };

  const activeBot = bots.find(b => b.id === selectedBotId);
  const botContacts = contacts.filter(c => !selectedBotId || c.botId === selectedBotId);

  // ─────────────────────────────────────────────────────────────────────────────
  // PREVIEW DATA FROM VIEW / QUERY
  // ─────────────────────────────────────────────────────────────────────────────
  const handleLoadAndPreview = async () => {
    if (!selectedConnectionId) {
      showAlert({ title: 'Koneksi Belum Dipilih', message: 'Silakan pilih koneksi database terlebih dahulu.', type: 'warning' });
      return;
    }
    setPreviewLoading(true);
    try {
      const res = await axios.post('/api/telegram/preview', {
        connectionId: selectedConnectionId,
        sqlQuery,
        templateMessage
      });

      if (res.data.columns) {
        setPreviewColumns(res.data.columns);
      }
      if (res.data.sampleRow) {
        setPreviewRows([res.data.sampleRow]);
      } else {
        setPreviewRows([]);
      }

      showAlert({
        title: 'Data Berhasil Dimuat',
        message: res.data.rowCount > 0 ? `Ditemukan baris data dari view ${selectedView}.` : 'Query berhasil dijalankan, tetapi data saat ini kosong (0 baris).',
        type: res.data.rowCount > 0 ? 'success' : 'info'
      });
    } catch (err: any) {
      showAlert({
        title: 'Gagal Memuat Data',
        message: err.response?.data?.message || err.message,
        type: 'error'
      });
    } finally {
      setPreviewLoading(false);
    }
  };

  // ─────────────────────────────────────────────────────────────────────────────
  // SEND MANUAL (SATU PER SATU)
  // ─────────────────────────────────────────────────────────────────────────────
  const handleSendManual = async () => {
    if (!selectedBotId) {
      showAlert({ title: 'Bot Belum Dipilih', message: 'Silakan pilih bot Telegram pengirim.', type: 'warning' });
      return;
    }
    if (!selectedConnectionId) {
      showAlert({ title: 'Koneksi Belum Dipilih', message: 'Silakan pilih koneksi database.', type: 'warning' });
      return;
    }

    setSendingManual(true);
    try {
      const payload = {
        botId: selectedBotId,
        connectionId: selectedConnectionId,
        sqlQuery,
        templateMessage,
        recipientMode,
        masterRecipientIds: recipientMode === 'MASTER' ? selectedContactIds.join(',') : null,
        phoneColumnName: recipientMode === 'DYNAMIC_COLUMN' ? phoneColumnName : null,
        maxRows
      };

      const res = await axios.post('/api/telegram/send-manual', payload);
      const log = res.data;

      if (log.status === 'SUCCESS') {
        showAlert({
          title: 'Pengiriman Berhasil!',
          message: `Berhasil mengirim ${log.messagesSent} pesan untuk ${log.totalRows} baris data ke Telegram.`,
          type: 'success'
        });
      } else if (log.status === 'PARTIAL') {
        showAlert({
          title: 'Terkirim Sebagian',
          message: `Terkirim: ${log.messagesSent}, Gagal: ${log.messagesFailed}. ${log.details || ''}`,
          type: 'warning'
        });
      } else {
        showAlert({
          title: 'Pengiriman Gagal / Belum Terdaftar',
          message: log.details || 'Gagal mengirim pesan.',
          type: 'error'
        });
      }
      fetchLogs();
    } catch (err: any) {
      showAlert({
        title: 'Error Pengiriman',
        message: err.response?.data?.message || err.message,
        type: 'error'
      });
    } finally {
      setSendingManual(false);
    }
  };

  // ─────────────────────────────────────────────────────────────────────────────
  // OPEN SCHEDULE FROM VIEW
  // ─────────────────────────────────────────────────────────────────────────────
  const handleOpenScheduleFromEditor = () => {
    const defaultScheduleName = sourceMode === 'VIEW'
      ? `Jadwal View ${selectedView} (${filterSchedule === 'Y' ? 'Terjadwal' : filterSchedule === 'N' ? 'Realtime' : 'All'})`
      : `Jadwal Broadcast ${activeBot?.name || 'Telegram'}`;

    setEditingSchedule({
      name: defaultScheduleName,
      botId: selectedBotId,
      connectionId: selectedConnectionId,
      schemaName: selectedSchema,
      viewName: selectedView,
      filterSchedule,
      filterKodeNotifikasi,
      filterKodeCabang,
      sqlQuery,
      templateMessage,
      recipientMode,
      masterRecipientIds: selectedContactIds.join(','),
      phoneColumnName,
      cronExpression: '0 0 8 * * ?',
      maxRows,
      active: true
    });
    setIsScheduleModalOpen(true);
  };

  // ─────────────────────────────────────────────────────────────────────────────
  // SAVE SCHEDULE
  // ─────────────────────────────────────────────────────────────────────────────
  const handleSaveSchedule = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!editingSchedule?.name || !editingSchedule?.botId || !editingSchedule?.connectionId || !editingSchedule?.cronExpression) {
      showAlert({ title: 'Input Kurang', message: 'Semua field konfigurasi jadwal wajib diisi.', type: 'warning' });
      return;
    }
    try {
      if (editingSchedule.id) {
        await axios.put(`/api/telegram/schedules/${editingSchedule.id}`, editingSchedule);
        showAlert({ title: 'Jadwal Diperbarui', message: 'Jadwal berhasil diperbarui.', type: 'success' });
      } else {
        await axios.post('/api/telegram/schedules', editingSchedule);
        showAlert({ title: 'Jadwal Dibuat', message: 'Jadwal baru berhasil didaftarkan ke background scheduler.', type: 'success' });
      }
      setIsScheduleModalOpen(false);
      setEditingSchedule(null);
      fetchSchedules();
    } catch (err: any) {
      showAlert({ title: 'Gagal Menyimpan Jadwal', message: err.response?.data?.message || err.message, type: 'error' });
    }
  };

  const handleToggleSchedule = async (schedule: TelegramSchedule) => {
    try {
      await axios.put(`/api/telegram/schedules/${schedule.id}/toggle`, { active: !schedule.active });
      fetchSchedules();
    } catch (err: any) {
      showAlert({ title: 'Gagal Ubah Status', message: err.message, type: 'error' });
    }
  };

  const handleRunScheduleNow = async (scheduleId: string) => {
    try {
      showAlert({ title: 'Memulai Tugas', message: 'Menjalankan jadwal Telegram sekarang...', type: 'info' });
      const res = await axios.post(`/api/telegram/schedules/${scheduleId}/run-now`);
      fetchSchedules();
      fetchLogs();
      showAlert({
        title: 'Tugas Selesai',
        message: `Status: ${res.data.status}. Pesan terkirim: ${res.data.messagesSent || 0}`,
        type: res.data.status === 'SUCCESS' ? 'success' : 'warning'
      });
    } catch (err: any) {
      showAlert({ title: 'Gagal Menjalankan', message: err.message, type: 'error' });
    }
  };

  const handleDeleteSchedule = async (scheduleId: string) => {
    if (!confirm('Apakah Anda yakin ingin menghapus jadwal ini?')) return;
    try {
      await axios.delete(`/api/telegram/schedules/${scheduleId}`);
      fetchSchedules();
    } catch (err: any) {
      showAlert({ title: 'Gagal Menghapus', message: err.message, type: 'error' });
    }
  };

  // ─────────────────────────────────────────────────────────────────────────────
  // SAVE BOT CONFIG
  // ─────────────────────────────────────────────────────────────────────────────
  const handleSaveBot = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!editingBot?.name || !editingBot?.botToken) {
      showAlert({ title: 'Input Tidak Lengkap', message: 'Nama bot dan Token Bot wajib diisi.', type: 'warning' });
      return;
    }
    try {
      if (editingBot.id) {
        await axios.put(`/api/telegram/bots/${editingBot.id}`, editingBot);
        showAlert({ title: 'Bot Diperbarui', message: 'Konfigurasi bot berhasil disimpan.', type: 'success' });
      } else {
        const res = await axios.post('/api/telegram/bots', editingBot);
        setSelectedBotId(res.data.id);
        showAlert({ title: 'Bot Ditambahkan', message: `Bot ${editingBot.name} berhasil didaftarkan.`, type: 'success' });
      }
      setIsBotModalOpen(false);
      setEditingBot(null);
      setTestBotResult(null);
      fetchBots();
    } catch (err: any) {
      showAlert({ title: 'Gagal Menyimpan Bot', message: err.response?.data?.message || err.message, type: 'error' });
    }
  };

  const handleTestBotToken = async () => {
    if (!editingBot?.botToken) {
      showAlert({ title: 'Token Kosong', message: 'Masukkan Token Bot terlebih dahulu.', type: 'warning' });
      return;
    }
    try {
      const res = await axios.post('/api/telegram/bots/test', { botToken: editingBot.botToken });
      setTestBotResult(res.data);
      if (res.data.info?.username) {
        setEditingBot(prev => ({ ...prev, botUsername: res.data.info.username }));
      }
    } catch (err: any) {
      setTestBotResult({ success: false, message: err.response?.data?.message || 'Token tidak valid' });
    }
  };

  return (
    <div className="h-full flex flex-col bg-bg-main text-text-main overflow-hidden">
      {/* Top Banner Navigation */}
      <div className="bg-bg-panel border-b border-border-main px-4 py-3 flex flex-wrap items-center justify-between gap-3 shrink-0">
        <div className="flex items-center gap-3">
          <div className="w-9 h-9 rounded-xl bg-gradient-to-tr from-sky-500 to-blue-600 flex items-center justify-center text-white shadow-md shadow-sky-500/20">
            <Send className="w-5 h-5 -rotate-12 translate-x-0.5" />
          </div>
          <div>
            <div className="flex items-center gap-2">
              <h2 className="text-base font-bold bg-gradient-to-r from-sky-400 to-blue-500 bg-clip-text text-transparent">
                Bot Telegram Notifikasi & Broadcast
              </h2>
              <span className="px-2 py-0.5 text-[11px] font-semibold bg-sky-500/10 text-sky-400 rounded-full border border-sky-500/20">
                View-Based Integration
              </span>
            </div>
            <p className="text-xs text-text-muted">
              Baca otomatis dari View (misal: <code>sch_erp_system.view_all_notifikasi</code>), kirim pesan per baris ke nomor tujuan.
            </p>
          </div>
        </div>

        {/* Tab switcher */}
        <div className="flex items-center gap-1 bg-bg-editor p-1 rounded-lg border border-border-main">
          <button
            onClick={() => setActiveTab('editor')}
            className={clsx(
              "flex items-center gap-1.5 px-3 py-1.5 rounded-md text-xs font-medium transition-colors",
              activeTab === 'editor'
                ? "bg-sky-500 text-white shadow-sm"
                : "text-text-muted hover:text-text-main hover:bg-bg-hover"
            )}
          >
            <MessageSquare className="w-3.5 h-3.5" />
            Kirim Pesan (View)
          </button>
          <button
            onClick={() => setActiveTab('scheduler')}
            className={clsx(
              "flex items-center gap-1.5 px-3 py-1.5 rounded-md text-xs font-medium transition-colors",
              activeTab === 'scheduler'
                ? "bg-sky-500 text-white shadow-sm"
                : "text-text-muted hover:text-text-main hover:bg-bg-hover"
            )}
          >
            <CalendarClock className="w-3.5 h-3.5" />
            Jadwal ({schedules.length})
          </button>
          <button
            onClick={() => setActiveTab('contacts')}
            className={clsx(
              "flex items-center gap-1.5 px-3 py-1.5 rounded-md text-xs font-medium transition-colors",
              activeTab === 'contacts'
                ? "bg-sky-500 text-white shadow-sm"
                : "text-text-muted hover:text-text-main hover:bg-bg-hover"
            )}
          >
            <Users className="w-3.5 h-3.5" />
            Master Kontak ({contacts.length})
          </button>
          <button
            onClick={() => setActiveTab('settings')}
            className={clsx(
              "flex items-center gap-1.5 px-3 py-1.5 rounded-md text-xs font-medium transition-colors",
              activeTab === 'settings'
                ? "bg-sky-500 text-white shadow-sm"
                : "text-text-muted hover:text-text-main hover:bg-bg-hover"
            )}
          >
            <Bot className="w-3.5 h-3.5" />
            Daftar Bot ({bots.length})
          </button>
          <button
            onClick={() => setActiveTab('logs')}
            className={clsx(
              "flex items-center gap-1.5 px-3 py-1.5 rounded-md text-xs font-medium transition-colors",
              activeTab === 'logs'
                ? "bg-sky-500 text-white shadow-sm"
                : "text-text-muted hover:text-text-main hover:bg-bg-hover"
            )}
          >
            <History className="w-3.5 h-3.5" />
            Riwayat Log ({logs.length})
          </button>
        </div>
      </div>

      {/* Main Tab Content */}
      <div className="flex-1 overflow-y-auto p-4 md:p-6 space-y-6">
        {/* =========================================================================
            TAB 1: KIRIM PESAN DARI VIEW (EDITOR)
        ========================================================================= */}
        {activeTab === 'editor' && (
          <div className="space-y-6">
            {/* Top Config Card */}
            <div className="bg-bg-panel border border-border-main rounded-xl p-5 shadow-sm space-y-4">
              <div className="flex items-center justify-between border-b border-border-main pb-3">
                <div className="flex items-center gap-2">
                  <Database className="w-4 h-4 text-sky-400" />
                  <h3 className="text-sm font-semibold">
                    1. Pilih Sumber View & Bot Telegram
                  </h3>
                </div>

                {/* Switcher: Mode View vs Custom SQL */}
                <div className="flex items-center gap-1 bg-bg-editor p-1 rounded-lg border border-border-main text-xs">
                  <button
                    type="button"
                    onClick={() => setSourceMode('VIEW')}
                    className={clsx(
                      "px-2.5 py-1 rounded font-medium transition-colors",
                      sourceMode === 'VIEW' ? "bg-sky-500 text-white" : "text-text-muted hover:text-text-main"
                    )}
                  >
                    Mode View (Rekomendasi)
                  </button>
                  <button
                    type="button"
                    onClick={() => setSourceMode('CUSTOM_SQL')}
                    className={clsx(
                      "px-2.5 py-1 rounded font-medium transition-colors",
                      sourceMode === 'CUSTOM_SQL' ? "bg-sky-500 text-white" : "text-text-muted hover:text-text-main"
                    )}
                  >
                    Mode Custom SQL
                  </button>
                </div>
              </div>

              {/* Selector Metode Pengiriman: Bot API vs Akun Pribadi (Telethon) */}
              <div className="bg-bg-editor/40 border border-border-main/60 rounded-xl p-3 space-y-2">
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <span className="text-xs font-semibold text-text-muted flex items-center gap-1.5">
                    ⚙️ Jalur Pengiriman:
                  </span>
                  <div className="flex items-center gap-2">
                    <button
                      type="button"
                      onClick={() => setSenderMethod('BOT_API')}
                      className={clsx(
                        "px-3 py-1.5 rounded-lg text-xs font-medium flex items-center gap-1.5 transition-all",
                        senderMethod === 'BOT_API'
                          ? "bg-sky-500 text-white shadow-sm shadow-sky-500/20 font-semibold"
                          : "bg-bg-panel text-text-muted hover:text-text-main border border-border-main"
                      )}
                    >
                      <Bot className="w-3.5 h-3.5" />
                      Bot Telegram Resmi (Anti-Banned & Stabil)
                    </button>
                    <button
                      type="button"
                      onClick={() => setSenderMethod('MTPROTO_USER')}
                      className={clsx(
                        "px-3 py-1.5 rounded-lg text-xs font-medium flex items-center gap-1.5 transition-all",
                        senderMethod === 'MTPROTO_USER'
                          ? "bg-amber-500 text-white shadow-sm shadow-amber-500/20 font-semibold"
                          : "bg-bg-panel text-text-muted hover:text-text-main border border-border-main"
                      )}
                    >
                      <Send className="w-3.5 h-3.5" />
                      Akun Pribadi / MTProto (Telethon)
                    </button>
                  </div>
                </div>

                {senderMethod === 'BOT_API' ? (
                  <div className="text-[11px] text-text-muted flex items-center gap-1.5 bg-sky-500/10 border border-sky-500/20 rounded-lg px-3 py-2 text-sky-300">
                    <span className="font-semibold text-sky-400">💡 Mode Bot API:</span>
                    Pesan dikirim melalui Bot Telegram Resmi. Mengirim ke nomor HP penerima secara otomatis jika penerima sudah klik <code>/start</code> 1x pada bot. Aman 100% dari banned akun Telegram.
                  </div>
                ) : (
                  <div className="text-[11px] text-amber-300/90 flex flex-col gap-1 bg-amber-500/10 border border-amber-500/30 rounded-lg px-3 py-2">
                    <div className="font-semibold text-amber-400 flex items-center gap-1.5">
                      ⚠️ Mode Akun Pribadi (Telethon / MTProto Client):
                    </div>
                    <div>
                      Mode ini mengirim pesan seolah-olah dari akun Telegram pribadi Anda langsung ke nomor HP kontak.
                      <strong> Perhatian:</strong> Telegram membatasi pengiriman pesan langsung dari akun pribadi ke banyak nomor baru tanpa riwayat obrolan (risiko batasan PeerFlood / Akun Spambot). Untuk notifikasi otomatis sistem ERP harian, disarankan tetap menggunakan <strong>Bot Telegram Resmi</strong>.
                    </div>
                  </div>
                )}
              </div>

              {/* Grid 4 Dropdowns */}
              <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
                {/* 1. Bot Telegram */}
                <div>
                  <label className="block text-xs font-medium text-text-muted mb-1">
                    🤖 Bot Telegram Pengirim *
                  </label>
                  <select
                    value={selectedBotId}
                    onChange={e => {
                      setSelectedBotId(e.target.value);
                      fetchContacts(e.target.value);
                    }}
                    className="w-full bg-bg-editor border border-border-main rounded-lg px-3 py-2 text-xs text-text-main focus:outline-none focus:border-sky-500"
                  >
                    {bots.map(b => (
                      <option key={b.id} value={b.id}>
                        {b.name} {b.botUsername ? `(@${b.botUsername})` : ''}
                      </option>
                    ))}
                    {bots.length === 0 && <option value="">(Belum ada bot)</option>}
                  </select>
                </div>

                {/* 2. Database Connection */}
                <div>
                  <label className="block text-xs font-medium text-text-muted mb-1">
                    🗄️ Database Sumber *
                  </label>
                  <select
                    value={selectedConnectionId}
                    onChange={e => setSelectedConnectionId(e.target.value)}
                    className="w-full bg-bg-editor border border-border-main rounded-lg px-3 py-2 text-xs text-text-main focus:outline-none focus:border-sky-500"
                  >
                    {connections.map(c => (
                      <option key={c.id} value={c.id}>
                        {c.name} ({c.database})
                      </option>
                    ))}
                  </select>
                </div>

                {/* 3. Schema */}
                <div>
                  <label className="block text-xs font-medium text-text-muted mb-1">
                    📁 Schema Database *
                  </label>
                  <select
                    value={selectedSchema}
                    onChange={e => setSelectedSchema(e.target.value)}
                    className="w-full bg-bg-editor border border-border-main rounded-lg px-3 py-2 text-xs text-text-main focus:outline-none focus:border-sky-500"
                  >
                    {schemas.map(s => (
                      <option key={s} value={s}>{s}</option>
                    ))}
                    {schemas.length === 0 && <option value="sch_erp_system">sch_erp_system</option>}
                  </select>
                </div>

                {/* 4. View */}
                <div>
                  <label className="block text-xs font-medium text-text-muted mb-1">
                    📊 View Notifikasi *
                  </label>
                  <select
                    value={selectedView}
                    onChange={e => setSelectedView(e.target.value)}
                    className="w-full bg-bg-editor border border-border-main rounded-lg px-3 py-2 text-xs text-text-main focus:outline-none focus:border-sky-500"
                  >
                    {views.map(v => (
                      <option key={v.name} value={v.name}>{v.name}</option>
                    ))}
                    {views.length === 0 && <option value="view_all_notifikasi">view_all_notifikasi</option>}
                  </select>
                </div>
              </div>

              {/* View Filters (Only shown when sourceMode === 'VIEW') */}
              {sourceMode === 'VIEW' && (
                <div className="bg-bg-editor/60 border border-border-main rounded-xl p-4 space-y-3">
                  <div className="flex items-center justify-between">
                    <span className="text-xs font-bold text-sky-400 flex items-center gap-1.5">
                      <Filter className="w-3.5 h-3.5" />
                      Filter View Notifikasi
                    </span>
                    <span className="text-[11px] text-text-muted">
                      Kolom View: <code>user_hp</code>, <code>user_name</code>, <code>pesan</code>, <code>schedule</code>
                    </span>
                  </div>

                  <div className="grid grid-cols-1 md:grid-cols-3 gap-3">
                    {/* Filter Schedule */}
                    <div>
                      <label className="block text-[11px] font-medium text-text-muted mb-1">
                        Jenis Notifikasi (Field <code>schedule</code>):
                      </label>
                      <select
                        value={filterSchedule}
                        onChange={e => setFilterSchedule(e.target.value)}
                        className="w-full bg-bg-panel border border-border-main rounded-lg px-2.5 py-1.5 text-xs text-text-main focus:outline-none"
                      >
                        <option value="ALL">Semua Notifikasi (Schedule = 'Y' & 'N')</option>
                        <option value="Y">Hanya Terjadwal / Periodic Report (Schedule = 'Y')</option>
                        <option value="N">Hanya Real-Time / Event Alert (Schedule = 'N')</option>
                      </select>
                      <span className="text-[10px] text-text-muted block mt-0.5">
                        * 'Y' = Daily Report / Reminder Tutup Periode. 'N' = Berhasil Tutup Periode / Event Instan.
                      </span>
                    </div>

                    {/* Filter Kode Notifikasi */}
                    <div>
                      <label className="block text-[11px] font-medium text-text-muted mb-1">
                        Filter Kode Notifikasi (Opsional):
                      </label>
                      <input
                        type="text"
                        value={filterKodeNotifikasi}
                        onChange={e => setFilterKodeNotifikasi(e.target.value)}
                        placeholder="misal: DAILY-REPORT-SALES, INFO-TUTUP-INVENTORY"
                        className="w-full bg-bg-panel border border-border-main rounded-lg px-2.5 py-1.5 text-xs text-text-main focus:outline-none"
                      />
                      <span className="text-[10px] text-text-muted block mt-0.5">
                        Kosongkan jika ingin menyertakan semua kode notifikasi.
                      </span>
                    </div>

                    {/* Filter Kode Cabang */}
                    <div>
                      <label className="block text-[11px] font-medium text-text-muted mb-1">
                        Filter Kode Cabang (Opsional):
                      </label>
                      <input
                        type="text"
                        value={filterKodeCabang}
                        onChange={e => setFilterKodeCabang(e.target.value)}
                        placeholder="misal: P001, P002, MBI"
                        className="w-full bg-bg-panel border border-border-main rounded-lg px-2.5 py-1.5 text-xs text-text-main focus:outline-none"
                      />
                      <span className="text-[10px] text-text-muted block mt-0.5">
                        Kosongkan untuk mengambil dari seluruh cabang.
                      </span>
                    </div>
                  </div>
                </div>
              )}

              {/* Custom SQL Box if sourceMode === 'CUSTOM_SQL' */}
              {sourceMode === 'CUSTOM_SQL' && (
                <div>
                  <label className="block text-xs font-medium text-text-muted mb-1">
                    Ketik Query SQL Kustom:
                  </label>
                  <textarea
                    rows={4}
                    value={sqlQuery}
                    onChange={e => setSqlQuery(e.target.value)}
                    className="w-full font-mono text-xs bg-bg-editor border border-border-main rounded-lg p-3 text-text-main focus:outline-none"
                  />
                </div>
              )}

              {/* Load & Preview Button */}
              <div className="flex items-center justify-between pt-2">
                <span className="text-xs text-text-muted">
                  Query Aktif: <code className="text-sky-400 font-mono text-[11px]">{sqlQuery.substring(0, 70)}...</code>
                </span>
                <button
                  type="button"
                  onClick={handleLoadAndPreview}
                  disabled={previewLoading}
                  className="flex items-center gap-1.5 px-4 py-2 bg-sky-500/10 hover:bg-sky-500/20 text-sky-400 text-xs font-semibold rounded-lg border border-sky-500/30 transition-colors"
                >
                  {previewLoading ? <RefreshCw className="w-4 h-4 animate-spin" /> : <Eye className="w-4 h-4" />}
                  Muat Data & Preview View
                </button>
              </div>
            </div>

            {/* Bottom 2 Columns: Table Preview & Message Dispatcher */}
            <div className="grid grid-cols-1 lg:grid-cols-12 gap-6">
              {/* Left Column: Data Rows Preview (7 cols) */}
              <div className="lg:col-span-7 bg-bg-panel border border-border-main rounded-xl p-5 shadow-sm space-y-4">
                <div className="flex items-center justify-between border-b border-border-main pb-3">
                  <h3 className="text-sm font-semibold flex items-center gap-2">
                    <Layers className="w-4 h-4 text-sky-400" />
                    2. Data yang Siap Dikirim dari View
                  </h3>
                  <span className="text-xs text-text-muted">
                    {previewRows.length > 0 ? `Menampilkan ${previewRows.length} contoh baris` : 'Belum memuat data'}
                  </span>
                </div>

                {previewRows.length > 0 ? (
                  <div className="space-y-3">
                    {previewRows.map((row, idx) => (
                      <div key={idx} className="bg-bg-editor border border-border-main rounded-lg p-3.5 space-y-2 text-xs">
                        <div className="flex items-center justify-between">
                          <div className="flex items-center gap-2">
                            <span className="px-2 py-0.5 rounded text-[10px] font-bold bg-sky-500/10 text-sky-400 border border-sky-500/20">
                              {row.kode_notifikasi || 'NOTIF'}
                            </span>
                            <span className="font-semibold text-text-main">
                              Cabang: {row.kode_cabang || '-'}
                            </span>
                          </div>
                          <span className={clsx(
                            "px-2 py-0.5 rounded text-[10px] font-semibold",
                            row.schedule === 'Y' ? "bg-purple-500/10 text-purple-400" : "bg-emerald-500/10 text-emerald-400"
                          )}>
                            Schedule: {row.schedule === 'Y' ? 'Y (Terjadwal)' : 'N (Realtime)'}
                          </span>
                        </div>

                        <div className="grid grid-cols-2 gap-2 text-[11px] text-text-muted">
                          <div>
                            Penerima: <b className="text-text-main">{row.user_name || '-'}</b>
                          </div>
                          <div>
                            Nomor HP: <b className="text-sky-400 font-mono">📱 {row.user_hp || '-'}</b>
                          </div>
                        </div>

                        <div className="p-2.5 bg-bg-panel rounded border border-border-main font-mono text-[11px] text-text-main whitespace-pre-wrap max-h-40 overflow-y-auto">
                          {row.pesan || '-'}
                        </div>
                      </div>
                    ))}
                  </div>
                ) : (
                  <div className="py-12 text-center text-xs text-text-muted space-y-2">
                    <Eye className="w-8 h-8 text-text-muted/40 mx-auto" />
                    <p>Klik tombol <b>"Muat Data & Preview View"</b> di atas untuk melihat data yang ada di view saat ini.</p>
                  </div>
                )}
              </div>

              {/* Right Column: Dispatch Config & Send Button (5 cols) */}
              <div className="lg:col-span-5 space-y-4">
                <div className="bg-bg-panel border border-border-main rounded-xl p-5 shadow-sm space-y-4">
                  <h3 className="text-sm font-semibold flex items-center gap-2 border-b border-border-main pb-3">
                    <Send className="w-4 h-4 text-sky-400" />
                    3. Pengiriman Satu per Satu ke Nomor HP
                  </h3>

                  <div className="space-y-3 text-xs">
                    <div className="p-3 bg-sky-500/10 border border-sky-500/20 rounded-lg text-text-main space-y-1">
                      <span className="font-semibold text-sky-400 block">
                        ⚙️ Mekanisme Otomatis View:
                      </span>
                      <p className="text-[11px] text-text-muted">
                        Setiap baris data yang ada di view akan dibaca nomor HP-nya (kolom <code>user_hp</code>) dan pesannya (kolom <code>pesan</code>). Pesan akan dikirimkan satu per satu ke chat Telegram masing-masing pengguna via Bot terpilih.
                      </p>
                    </div>

                    <div>
                      <label className="block text-xs font-medium text-text-muted mb-1">
                        Template Wrapper (Biarkan <code>{`{{pesan}}`}</code> untuk kirim isi asli):
                      </label>
                      <textarea
                        rows={3}
                        value={templateMessage}
                        onChange={e => setTemplateMessage(e.target.value)}
                        className="w-full font-mono text-xs bg-bg-editor border border-border-main rounded-lg p-2.5 text-text-main focus:outline-none"
                      />
                    </div>

                    <div className="flex items-center justify-between pt-1">
                      <div>
                        <label className="text-xs font-medium text-text-main block">
                          Maksimal Baris per Kirim
                        </label>
                        <span className="text-[10px] text-text-muted">Pengaman batas eksekusi (Max Rows)</span>
                      </div>
                      <input
                        type="number"
                        min={1}
                        max={500}
                        value={maxRows}
                        onChange={e => setMaxRows(Number(e.target.value))}
                        className="w-20 bg-bg-editor border border-border-main rounded-lg px-2.5 py-1.5 text-xs text-center font-mono focus:outline-none"
                      />
                    </div>
                  </div>

                  {/* Action Buttons */}
                  <div className="space-y-2 pt-2 border-t border-border-main">
                    <button
                      type="button"
                      onClick={handleSendManual}
                      disabled={sendingManual}
                      className="w-full flex items-center justify-center gap-2 bg-gradient-to-r from-sky-500 to-blue-600 hover:from-sky-600 hover:to-blue-700 text-white font-semibold py-2.5 px-4 rounded-lg text-xs shadow-md shadow-sky-500/20 transition-all disabled:opacity-50"
                    >
                      {sendingManual ? <RefreshCw className="w-4 h-4 animate-spin" /> : <Send className="w-4 h-4" />}
                      Kirim Pesan Sekarang (Satu per Satu)
                    </button>

                    <button
                      type="button"
                      onClick={handleOpenScheduleFromEditor}
                      className="w-full flex items-center justify-center gap-2 bg-bg-editor hover:bg-bg-hover text-text-main border border-border-main font-semibold py-2.5 px-4 rounded-lg text-xs transition-colors"
                    >
                      <CalendarClock className="w-4 h-4 text-sky-400" />
                      Jadwalkan Pengiriman Otomatis (Multi-Cron)
                    </button>
                  </div>
                </div>
              </div>
            </div>
          </div>
        )}

        {/* =========================================================================
            TAB 2: JADWAL PENGIRIMAN (SCHEDULER)
        ========================================================================= */}
        {activeTab === 'scheduler' && (
          <div className="bg-bg-panel border border-border-main rounded-xl p-5 shadow-sm space-y-4">
            <div className="flex items-center justify-between border-b border-border-main pb-4">
              <div>
                <h3 className="text-base font-bold flex items-center gap-2">
                  <CalendarClock className="w-5 h-5 text-sky-400" />
                  Daftar Jadwal Pengiriman Otomatis (Multi-Cron)
                </h3>
                <p className="text-xs text-text-muted">
                  Tugas otomatis yang mengeksekusi view dan mengirim notifikasi secara berkala di latar belakang server.
                </p>
              </div>
              <button
                onClick={() => {
                  setEditingSchedule({
                    name: `Jadwal View ${selectedView}`,
                    botId: selectedBotId || (bots[0]?.id || ''),
                    connectionId: selectedConnectionId || (connections[0]?.id || ''),
                    schemaName: selectedSchema,
                    viewName: selectedView,
                    filterSchedule,
                    filterKodeNotifikasi,
                    filterKodeCabang,
                    sqlQuery,
                    templateMessage: '{{pesan}}',
                    recipientMode: 'DYNAMIC_COLUMN',
                    phoneColumnName: 'user_hp',
                    cronExpression: '0 0 8 * * ?',
                    maxRows: 50,
                    active: true
                  });
                  setIsScheduleModalOpen(true);
                }}
                className="flex items-center gap-1.5 bg-sky-500 hover:bg-sky-600 text-white text-xs font-semibold px-3 py-2 rounded-lg shadow-sm transition-colors"
              >
                <Plus className="w-4 h-4" /> Tambah Jadwal Baru
              </button>
            </div>

            <div className="overflow-x-auto">
              <table className="w-full text-left text-xs border-collapse">
                <thead>
                  <tr className="border-b border-border-main text-text-muted">
                    <th className="py-2.5 px-3">Nama Jadwal</th>
                    <th className="py-2.5 px-3">Bot & View / Sumber</th>
                    <th className="py-2.5 px-3">Cron Expression</th>
                    <th className="py-2.5 px-3">Filter Schedule</th>
                    <th className="py-2.5 px-3">Status</th>
                    <th className="py-2.5 px-3">Terakhir Jalan</th>
                    <th className="py-2.5 px-3 text-right">Aksi</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-border-main">
                  {schedules.map(s => {
                    const botObj = bots.find(b => b.id === s.botId);
                    return (
                      <tr key={s.id} className="hover:bg-bg-hover/50">
                        <td className="py-3 px-3 font-semibold text-text-main">
                          {s.name}
                        </td>
                        <td className="py-3 px-3">
                          <span className="block text-sky-400 font-medium">🤖 {botObj?.name || s.botId}</span>
                          <span className="text-[11px] text-text-muted">
                            📊 {s.viewName ? `${s.schemaName}.${s.viewName}` : 'Custom SQL'}
                          </span>
                        </td>
                        <td className="py-3 px-3 font-mono text-[11px]">
                          <span className="px-2 py-0.5 bg-bg-editor rounded border border-border-main">
                            {s.cronExpression}
                          </span>
                        </td>
                        <td className="py-3 px-3">
                          <span className={clsx(
                            "px-2 py-0.5 rounded text-[10px] font-semibold",
                            s.filterSchedule === 'Y' ? "bg-purple-500/10 text-purple-400" :
                            s.filterSchedule === 'N' ? "bg-emerald-500/10 text-emerald-400" : "bg-blue-500/10 text-blue-400"
                          )}>
                            {s.filterSchedule === 'Y' ? 'Terjadwal (Y)' : s.filterSchedule === 'N' ? 'Realtime (N)' : 'Semua'}
                          </span>
                        </td>
                        <td className="py-3 px-3">
                          <button
                            onClick={() => handleToggleSchedule(s)}
                            className={clsx(
                              "px-2.5 py-1 rounded-full text-[11px] font-semibold flex items-center gap-1 transition-colors",
                              s.active
                                ? "bg-green-500/10 text-green-400 border border-green-500/30"
                                : "bg-amber-500/10 text-amber-400 border border-amber-500/30"
                            )}
                          >
                            {s.active ? <CheckCircle2 className="w-3 h-3" /> : <Pause className="w-3 h-3" />}
                            {s.active ? 'Aktif' : 'Pause'}
                          </button>
                        </td>
                        <td className="py-3 px-3 text-[11px]">
                          {s.lastRunAt ? (
                            <div>
                              <span className="block text-text-main">{s.lastRunAt.replace('T', ' ')}</span>
                              <span className={clsx(
                                "text-[10px] font-semibold",
                                s.lastRunStatus === 'SUCCESS' ? "text-green-400" : "text-rose-400"
                              )}>
                                {s.lastRunStatus}
                              </span>
                            </div>
                          ) : (
                            <span className="text-text-muted">Belum pernah</span>
                          )}
                        </td>
                        <td className="py-3 px-3 text-right">
                          <div className="flex items-center justify-end gap-1.5">
                            <button
                              onClick={() => handleRunScheduleNow(s.id)}
                              title="Jalankan Sekarang"
                              className="p-1.5 bg-sky-500/10 hover:bg-sky-500/20 text-sky-400 rounded transition-colors"
                            >
                              <Play className="w-3.5 h-3.5" />
                            </button>
                            <button
                              onClick={() => {
                                setEditingSchedule(s);
                                setIsScheduleModalOpen(true);
                              }}
                              title="Edit"
                              className="p-1.5 bg-bg-editor hover:bg-bg-hover text-text-muted hover:text-text-main rounded transition-colors"
                            >
                              <Edit3 className="w-3.5 h-3.5" />
                            </button>
                            <button
                              onClick={() => handleDeleteSchedule(s.id)}
                              title="Hapus"
                              className="p-1.5 bg-rose-500/10 hover:bg-rose-500/20 text-rose-400 rounded transition-colors"
                            >
                              <Trash2 className="w-3.5 h-3.5" />
                            </button>
                          </div>
                        </td>
                      </tr>
                    );
                  })}
                  {schedules.length === 0 && (
                    <tr>
                      <td colSpan={7} className="text-center py-8 text-text-muted">
                        Belum ada jadwal yang didaftarkan. Klik "Tambah Jadwal Baru" untuk memulai.
                      </td>
                    </tr>
                  )}
                </tbody>
              </table>
            </div>
          </div>
        )}

        {/* =========================================================================
            TAB 3: MASTER KONTAK
        ========================================================================= */}
        {activeTab === 'contacts' && (
          <div className="space-y-4">
            {activeBot && (
              <div className="bg-gradient-to-r from-sky-500/10 to-blue-500/10 border border-sky-500/20 rounded-xl p-4 flex flex-col md:flex-row items-start md:items-center justify-between gap-4">
                <div className="space-y-1">
                  <div className="flex items-center gap-2">
                    <Bot className="w-4 h-4 text-sky-400" />
                    <h4 className="text-xs font-bold text-sky-400">
                      Link Aktivasi Bot: {activeBot.name}
                    </h4>
                  </div>
                  <p className="text-xs text-text-muted">
                    Bagikan tautan ini ke staf atau tim Anda. Saat mereka klik tautan ini di HP dan menekan <b>START</b>, bot akan meminta nomor telepon dan menyimpannya secara otomatis ke daftar kontak ini!
                  </p>
                </div>

                <div className="flex items-center gap-2 shrink-0">
                  <div className="bg-bg-editor border border-border-main px-3 py-1.5 rounded-lg text-xs font-mono text-text-main flex items-center gap-2">
                    <span>{`https://t.me/${activeBot.botUsername || 'bot'}`}</span>
                  </div>
                  <button
                    onClick={() => {
                      navigator.clipboard.writeText(`https://t.me/${activeBot.botUsername || ''}`);
                      showAlert({ title: 'Tersalin', message: 'Tautan Telegram bot berhasil disalin ke clipboard.', type: 'success' });
                    }}
                    className="p-2 bg-sky-500 hover:bg-sky-600 text-white rounded-lg shadow-sm transition-colors"
                    title="Copy Link"
                  >
                    <Copy className="w-3.5 h-3.5" />
                  </button>
                  {activeBot.botUsername && (
                    <a
                      href={`https://t.me/${activeBot.botUsername}`}
                      target="_blank"
                      rel="noopener noreferrer"
                      className="p-2 bg-bg-editor hover:bg-bg-hover text-text-main border border-border-main rounded-lg transition-colors"
                      title="Buka Telegram"
                    >
                      <ExternalLink className="w-3.5 h-3.5" />
                    </a>
                  )}
                </div>
              </div>
            )}

            <div className="bg-bg-panel border border-border-main rounded-xl p-5 shadow-sm space-y-4">
              <div className="flex items-center justify-between border-b border-border-main pb-4">
                <div className="flex items-center gap-3">
                  <h3 className="text-sm font-semibold flex items-center gap-2">
                    <Users className="w-4 h-4 text-sky-400" />
                    Daftar Kontak Terdaftar
                  </h3>
                  <select
                    value={selectedBotId}
                    onChange={e => {
                      setSelectedBotId(e.target.value);
                      fetchContacts(e.target.value);
                    }}
                    className="bg-bg-editor border border-border-main rounded-lg px-2.5 py-1 text-xs text-text-main focus:outline-none"
                  >
                    <option value="">Semua Bot</option>
                    {bots.map(b => (
                      <option key={b.id} value={b.id}>{b.name}</option>
                    ))}
                  </select>
                </div>

                <button
                  onClick={() => {
                    setEditingContact({
                      botId: selectedBotId || (bots[0]?.id || ''),
                      chatId: '',
                      phoneNumber: '',
                      firstName: '',
                      isActive: true
                    });
                    setIsContactModalOpen(true);
                  }}
                  className="flex items-center gap-1.5 bg-bg-editor hover:bg-bg-hover text-text-main border border-border-main text-xs font-semibold px-3 py-1.5 rounded-lg transition-colors"
                >
                  <Plus className="w-3.5 h-3.5 text-sky-400" /> Tambah Kontak Manual
                </button>
              </div>

              <div className="overflow-x-auto">
                <table className="w-full text-left text-xs border-collapse">
                  <thead>
                    <tr className="border-b border-border-main text-text-muted">
                      <th className="py-2.5 px-3">Nama</th>
                      <th className="py-2.5 px-3">Nomor Telepon</th>
                      <th className="py-2.5 px-3">Chat ID</th>
                      <th className="py-2.5 px-3">Username</th>
                      <th className="py-2.5 px-3">Status</th>
                      <th className="py-2.5 px-3">Terdaftar Pada</th>
                      <th className="py-2.5 px-3 text-right">Aksi</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-border-main">
                    {botContacts.map(c => (
                      <tr key={c.id} className="hover:bg-bg-hover/50">
                        <td className="py-3 px-3 font-semibold text-text-main">
                          {c.firstName} {c.lastName || ''}
                        </td>
                        <td className="py-3 px-3 font-mono font-medium text-sky-400">
                          {c.phoneNumber ? `📱 ${c.phoneNumber}` : <span className="text-text-muted italic">(Belum kirim kontak)</span>}
                        </td>
                        <td className="py-3 px-3 font-mono text-text-muted">
                          {c.chatId}
                        </td>
                        <td className="py-3 px-3">
                          {c.username ? `@${c.username}` : '-'}
                        </td>
                        <td className="py-3 px-3">
                          <span className={clsx(
                            "px-2 py-0.5 rounded text-[10px] font-semibold",
                            c.isActive ? "bg-green-500/10 text-green-400" : "bg-zinc-500/10 text-zinc-400"
                          )}>
                            {c.isActive ? 'Aktif' : 'Non-aktif'}
                          </span>
                        </td>
                        <td className="py-3 px-3 text-text-muted text-[11px]">
                          {c.registeredAt?.replace('T', ' ') || '-'}
                        </td>
                        <td className="py-3 px-3 text-right">
                          <div className="flex items-center justify-end gap-1.5">
                            <button
                              onClick={() => {
                                setEditingContact(c);
                                setIsContactModalOpen(true);
                              }}
                              className="p-1.5 bg-bg-editor hover:bg-bg-hover text-text-muted hover:text-text-main rounded"
                            >
                              <Edit3 className="w-3.5 h-3.5" />
                            </button>
                            <button
                              onClick={() => handleDeleteContact(c.id)}
                              className="p-1.5 bg-rose-500/10 hover:bg-rose-500/20 text-rose-400 rounded"
                            >
                              <Trash2 className="w-3.5 h-3.5" />
                            </button>
                          </div>
                        </td>
                      </tr>
                    ))}
                    {botContacts.length === 0 && (
                      <tr>
                        <td colSpan={7} className="text-center py-8 text-text-muted">
                          Belum ada kontak terdaftar.
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
            TAB 4: PENGATURAN BOT
        ========================================================================= */}
        {activeTab === 'settings' && (
          <div className="bg-bg-panel border border-border-main rounded-xl p-5 shadow-sm space-y-4">
            <div className="flex items-center justify-between border-b border-border-main pb-4">
              <div>
                <h3 className="text-base font-bold flex items-center gap-2">
                  <Bot className="w-5 h-5 text-sky-400" />
                  Daftar Konfigurasi Bot Telegram
                </h3>
                <p className="text-xs text-text-muted">
                  Kelola token Bot Telegram yang digunakan untuk pengiriman pesan dan listener background polling.
                </p>
              </div>
              <button
                onClick={() => {
                  setEditingBot({ name: '', botToken: '', isPollingActive: true });
                  setIsBotModalOpen(true);
                  setTestBotResult(null);
                }}
                className="flex items-center gap-1.5 bg-sky-500 hover:bg-sky-600 text-white text-xs font-semibold px-3 py-2 rounded-lg shadow-sm transition-colors"
              >
                <Plus className="w-4 h-4" /> Daftarkan Bot Baru
              </button>
            </div>

            <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
              {bots.map(b => (
                <div key={b.id} className="bg-bg-editor border border-border-main rounded-xl p-4 space-y-3 relative group">
                  <div className="flex items-start justify-between">
                    <div>
                      <h4 className="text-sm font-bold text-text-main">{b.name}</h4>
                      {b.botUsername ? (
                        <span className="text-xs text-sky-400 font-mono">@{b.botUsername}</span>
                      ) : (
                        <span className="text-xs text-text-muted italic">(Username belum disinkron)</span>
                      )}
                    </div>
                    <span className={clsx(
                      "px-2 py-0.5 rounded text-[10px] font-semibold flex items-center gap-1",
                      b.isPollingActive ? "bg-green-500/10 text-green-400" : "bg-zinc-500/10 text-zinc-400"
                    )}>
                      <span className={clsx("w-1.5 h-1.5 rounded-full", b.isPollingActive ? "bg-green-500 animate-pulse" : "bg-zinc-500")} />
                      {b.isPollingActive ? 'Polling Aktif' : 'Polling Nonaktif'}
                    </span>
                  </div>

                  <div className="text-[11px] text-text-muted font-mono bg-bg-panel p-2 rounded border border-border-main truncate">
                    Token: {b.botToken.substring(0, 10)}...{b.botToken.substring(b.botToken.length - 5)}
                  </div>

                  <div className="flex items-center justify-between pt-2 border-t border-border-main">
                    <span className="text-[11px] text-text-muted">
                      ID: {b.id.substring(0, 8)}
                    </span>
                    <div className="flex items-center gap-1.5">
                      <button
                        onClick={() => {
                          setEditingBot(b);
                          setIsBotModalOpen(true);
                          setTestBotResult(null);
                        }}
                        className="p-1.5 bg-bg-panel hover:bg-bg-hover text-text-muted hover:text-text-main rounded"
                        title="Edit Bot"
                      >
                        <Edit3 className="w-3.5 h-3.5" />
                      </button>
                      <button
                        onClick={async () => {
                          if (!confirm(`Hapus bot "${b.name}"?`)) return;
                          await axios.delete(`/api/telegram/bots/${b.id}`);
                          fetchBots();
                        }}
                        className="p-1.5 bg-rose-500/10 hover:bg-rose-500/20 text-rose-400 rounded"
                        title="Hapus Bot"
                      >
                        <Trash2 className="w-3.5 h-3.5" />
                      </button>
                    </div>
                  </div>
                </div>
              ))}
              {bots.length === 0 && (
                <div className="col-span-full text-center py-8 text-text-muted text-xs">
                  Belum ada Bot yang didaftarkan. Klik "Daftarkan Bot Baru" untuk mulai menambahkan bot dari BotFather.
                </div>
              )}
            </div>
          </div>
        )}

        {/* =========================================================================
            TAB 5: RIWAYAT LOG
        ========================================================================= */}
        {activeTab === 'logs' && (
          <div className="bg-bg-panel border border-border-main rounded-xl p-5 shadow-sm space-y-4">
            <div className="flex items-center justify-between border-b border-border-main pb-4">
              <div>
                <h3 className="text-base font-bold flex items-center gap-2">
                  <History className="w-5 h-5 text-sky-400" />
                  Riwayat Pengiriman Pesan
                </h3>
                <p className="text-xs text-text-muted">
                  Log pengiriman manual dan tugas terjadwal (scheduler).
                </p>
              </div>
              <div className="flex items-center gap-2">
                <button
                  onClick={fetchLogs}
                  className="flex items-center gap-1.5 bg-bg-editor hover:bg-bg-hover text-text-main text-xs font-semibold px-3 py-1.5 rounded-lg border border-border-main transition-colors"
                >
                  <RefreshCw className="w-3.5 h-3.5" /> Refresh
                </button>
              </div>
            </div>

            <div className="overflow-x-auto">
              <table className="w-full text-left text-xs border-collapse">
                <thead>
                  <tr className="border-b border-border-main text-text-muted">
                    <th className="py-2.5 px-3">Waktu</th>
                    <th className="py-2.5 px-3">Pemicu</th>
                    <th className="py-2.5 px-3">Bot</th>
                    <th className="py-2.5 px-3">Total Row</th>
                    <th className="py-2.5 px-3">Terkirim / Gagal</th>
                    <th className="py-2.5 px-3">Status</th>
                    <th className="py-2.5 px-3">Keterangan</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-border-main">
                  {logs.map(l => (
                    <tr key={l.id} className="hover:bg-bg-hover/50">
                      <td className="py-3 px-3 text-text-main whitespace-nowrap">
                        {l.createdAt?.replace('T', ' ')}
                      </td>
                      <td className="py-3 px-3">
                        <span className={clsx(
                          "px-2 py-0.5 rounded text-[10px] font-semibold",
                          l.triggerType === 'MANUAL' ? "bg-sky-500/10 text-sky-400" : "bg-purple-500/10 text-purple-400"
                        )}>
                          {l.triggerType}
                        </span>
                      </td>
                      <td className="py-3 px-3 font-medium text-text-main">
                        {l.botName || '-'}
                      </td>
                      <td className="py-3 px-3 font-mono">
                        {l.totalRows}
                      </td>
                      <td className="py-3 px-3 font-mono">
                        <span className="text-green-400 font-semibold">{l.messagesSent}</span> / <span className="text-rose-400 font-semibold">{l.messagesFailed}</span>
                      </td>
                      <td className="py-3 px-3">
                        <span className={clsx(
                          "px-2 py-0.5 rounded text-[10px] font-semibold",
                          l.status === 'SUCCESS' ? "bg-green-500/10 text-green-400" :
                          l.status === 'PARTIAL' ? "bg-amber-500/10 text-amber-400" : "bg-rose-500/10 text-rose-400"
                        )}>
                          {l.status}
                        </span>
                      </td>
                      <td className="py-3 px-3 text-text-muted max-w-xs truncate" title={l.details}>
                        {l.details || '-'}
                      </td>
                    </tr>
                  ))}
                  {logs.length === 0 && (
                    <tr>
                      <td colSpan={7} className="text-center py-8 text-text-muted">
                        Belum ada riwayat pengiriman.
                      </td>
                    </tr>
                  )}
                </tbody>
              </table>
            </div>
          </div>
        )}
      </div>

      {/* =========================================================================
          MODAL: TAMBAH / EDIT BOT TELEGRAM
      ========================================================================= */}
      {isBotModalOpen && editingBot && (
        <div className="fixed inset-0 z-50 bg-black/60 backdrop-blur-sm flex items-center justify-center p-4">
          <div className="bg-bg-panel border border-border-main rounded-2xl w-full max-w-md shadow-2xl p-5 space-y-4">
            <div className="flex items-center justify-between border-b border-border-main pb-3">
              <h3 className="text-sm font-bold flex items-center gap-2">
                <Bot className="w-4 h-4 text-sky-400" />
                {editingBot.id ? 'Edit Konfigurasi Bot' : 'Daftarkan Bot Telegram Baru'}
              </h3>
              <button onClick={() => setIsBotModalOpen(false)} className="text-text-muted hover:text-text-main">
                <X className="w-4 h-4" />
              </button>
            </div>

            <form onSubmit={handleSaveBot} className="space-y-3">
              <div>
                <label className="block text-xs font-medium text-text-muted mb-1">
                  Nama Bot (Pengenal di Dashboard) *
                </label>
                <input
                  type="text"
                  required
                  value={editingBot.name || ''}
                  onChange={e => setEditingBot({ ...editingBot, name: e.target.value })}
                  placeholder="misal: Bot Notifikasi POS"
                  className="w-full bg-bg-editor border border-border-main rounded-lg px-3 py-2 text-xs text-text-main focus:outline-none focus:border-sky-500"
                />
              </div>

              <div>
                <label className="block text-xs font-medium text-text-muted mb-1">
                  Bot Token (dari @BotFather) *
                </label>
                <div className="flex gap-2">
                  <input
                    type="password"
                    required
                    value={editingBot.botToken || ''}
                    onChange={e => setEditingBot({ ...editingBot, botToken: e.target.value })}
                    placeholder="123456789:ABCdefGhI..."
                    className="flex-1 bg-bg-editor border border-border-main rounded-lg px-3 py-2 text-xs font-mono text-text-main focus:outline-none focus:border-sky-500"
                  />
                  <button
                    type="button"
                    onClick={handleTestBotToken}
                    className="px-3 py-2 bg-sky-500/10 hover:bg-sky-500/20 text-sky-400 text-xs font-medium rounded-lg border border-sky-500/20 transition-colors shrink-0"
                  >
                    Uji Token
                  </button>
                </div>
              </div>

              {testBotResult && (
                <div className={clsx(
                  "p-2.5 rounded-lg border text-xs",
                  testBotResult.success
                    ? "bg-green-500/10 border-green-500/20 text-green-400"
                    : "bg-rose-500/10 border-rose-500/20 text-rose-400"
                )}>
                  {testBotResult.success ? (
                    <div>
                      ✅ Token Valid! Terhubung ke bot: <b>@{testBotResult.info?.username}</b> ({testBotResult.info?.firstName})
                    </div>
                  ) : (
                    <div>❌ {testBotResult.message}</div>
                  )}
                </div>
              )}

              <div>
                <label className="block text-xs font-medium text-text-muted mb-1">
                  Username Bot (Opsional)
                </label>
                <input
                  type="text"
                  value={editingBot.botUsername || ''}
                  onChange={e => setEditingBot({ ...editingBot, botUsername: e.target.value })}
                  placeholder="misal: my_pos_bot (tanpa @)"
                  className="w-full bg-bg-editor border border-border-main rounded-lg px-3 py-2 text-xs text-text-main focus:outline-none focus:border-sky-500"
                />
              </div>

              <div className="pt-2">
                <label className="flex items-center gap-2 cursor-pointer text-xs">
                  <input
                    type="checkbox"
                    checked={editingBot.isPollingActive ?? true}
                    onChange={e => setEditingBot({ ...editingBot, isPollingActive: e.target.checked })}
                    className="rounded text-sky-500 focus:ring-sky-400"
                  />
                  <span className="text-text-main font-medium">
                    Aktifkan Background Polling (Mendengarkan /start otomatis)
                  </span>
                </label>
              </div>

              <div className="flex items-center justify-end gap-2 pt-3 border-t border-border-main">
                <button
                  type="button"
                  onClick={() => setIsBotModalOpen(false)}
                  className="px-4 py-2 bg-bg-editor hover:bg-bg-hover text-text-muted rounded-lg text-xs"
                >
                  Batal
                </button>
                <button
                  type="submit"
                  className="px-4 py-2 bg-sky-500 hover:bg-sky-600 text-white rounded-lg text-xs font-semibold shadow-sm"
                >
                  Simpan Bot
                </button>
              </div>
            </form>
          </div>
        </div>
      )}

      {/* =========================================================================
          MODAL: BUAT / EDIT JADWAL (SCHEDULER)
      ========================================================================= */}
      {isScheduleModalOpen && editingSchedule && (
        <div className="fixed inset-0 z-50 bg-black/60 backdrop-blur-sm flex items-center justify-center p-4">
          <div className="bg-bg-panel border border-border-main rounded-2xl w-full max-w-xl shadow-2xl p-5 space-y-4 max-h-[90vh] overflow-y-auto">
            <div className="flex items-center justify-between border-b border-border-main pb-3">
              <h3 className="text-sm font-bold flex items-center gap-2">
                <CalendarClock className="w-4 h-4 text-sky-400" />
                {editingSchedule.id ? 'Edit Jadwal Telegram' : 'Konfigurasi Jadwal Baru'}
              </h3>
              <button onClick={() => setIsScheduleModalOpen(false)} className="text-text-muted hover:text-text-main">
                <X className="w-4 h-4" />
              </button>
            </div>

            <form onSubmit={handleSaveSchedule} className="space-y-3">
              <div>
                <label className="block text-xs font-medium text-text-muted mb-1">
                  Nama Jadwal *
                </label>
                <input
                  type="text"
                  required
                  value={editingSchedule.name || ''}
                  onChange={e => setEditingSchedule({ ...editingSchedule, name: e.target.value })}
                  placeholder="misal: Alert Notifikasi ERP Harian"
                  className="w-full bg-bg-editor border border-border-main rounded-lg px-3 py-2 text-xs text-text-main focus:outline-none focus:border-sky-500"
                />
              </div>

              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label className="block text-xs font-medium text-text-muted mb-1">
                    Bot Telegram *
                  </label>
                  <select
                    value={editingSchedule.botId || ''}
                    onChange={e => setEditingSchedule({ ...editingSchedule, botId: e.target.value })}
                    className="w-full bg-bg-editor border border-border-main rounded-lg px-3 py-2 text-xs text-text-main focus:outline-none"
                  >
                    {bots.map(b => (
                      <option key={b.id} value={b.id}>{b.name}</option>
                    ))}
                  </select>
                </div>

                <div>
                  <label className="block text-xs font-medium text-text-muted mb-1">
                    Database *
                  </label>
                  <select
                    value={editingSchedule.connectionId || ''}
                    onChange={e => setEditingSchedule({ ...editingSchedule, connectionId: e.target.value })}
                    className="w-full bg-bg-editor border border-border-main rounded-lg px-3 py-2 text-xs text-text-main focus:outline-none"
                  >
                    {connections.map(c => (
                      <option key={c.id} value={c.id}>{c.name}</option>
                    ))}
                  </select>
                </div>
              </div>

              <div>
                <label className="block text-xs font-medium text-text-muted mb-1">
                  Cron Expression (Dukungan Multi-Cron dipisahkan koma) *
                </label>
                <input
                  type="text"
                  required
                  value={editingSchedule.cronExpression || ''}
                  onChange={e => setEditingSchedule({ ...editingSchedule, cronExpression: e.target.value })}
                  placeholder="0 0 8 * * ? (Tiap jam 8 pagi), 0 0 17 * * ?"
                  className="w-full font-mono text-xs bg-bg-editor border border-border-main rounded-lg px-3 py-2 text-text-main focus:outline-none focus:border-sky-500"
                />
                <div className="flex gap-2 pt-1 text-[10px] text-text-muted">
                  <span className="font-semibold">Preset:</span>
                  <button type="button" onClick={() => setEditingSchedule({ ...editingSchedule, cronExpression: '0 0 8 * * ?' })} className="text-sky-400 hover:underline">Jam 08:00</button>
                  <button type="button" onClick={() => setEditingSchedule({ ...editingSchedule, cronExpression: '0 0 */1 * * ?' })} className="text-sky-400 hover:underline">Tiap Jam</button>
                  <button type="button" onClick={() => setEditingSchedule({ ...editingSchedule, cronExpression: '0 */15 * * * ?' })} className="text-sky-400 hover:underline">Tiap 15 Menit</button>
                </div>
              </div>

              <div>
                <label className="block text-xs font-medium text-text-muted mb-1">
                  SQL Query View *
                </label>
                <textarea
                  rows={3}
                  required
                  value={editingSchedule.sqlQuery || ''}
                  onChange={e => setEditingSchedule({ ...editingSchedule, sqlQuery: e.target.value })}
                  className="w-full font-mono text-xs bg-bg-editor border border-border-main rounded-lg p-2.5 text-text-main focus:outline-none"
                />
              </div>

              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label className="block text-xs font-medium text-text-muted mb-1">
                    Kolom Nomor HP Penerima
                  </label>
                  <input
                    type="text"
                    value={editingSchedule.phoneColumnName || 'user_hp'}
                    onChange={e => setEditingSchedule({ ...editingSchedule, phoneColumnName: e.target.value })}
                    className="w-full bg-bg-editor border border-border-main rounded-lg px-3 py-2 text-xs font-mono text-text-main focus:outline-none"
                  />
                </div>

                <div>
                  <label className="block text-xs font-medium text-text-muted mb-1">
                    Max Rows
                  </label>
                  <input
                    type="number"
                    value={editingSchedule.maxRows || 50}
                    onChange={e => setEditingSchedule({ ...editingSchedule, maxRows: Number(e.target.value) })}
                    className="w-full bg-bg-editor border border-border-main rounded-lg px-3 py-2 text-xs text-text-main focus:outline-none"
                  />
                </div>
              </div>

              <div className="flex items-center justify-end gap-2 pt-3 border-t border-border-main">
                <button
                  type="button"
                  onClick={() => setIsScheduleModalOpen(false)}
                  className="px-4 py-2 bg-bg-editor hover:bg-bg-hover text-text-muted rounded-lg text-xs"
                >
                  Batal
                </button>
                <button
                  type="submit"
                  className="px-4 py-2 bg-sky-500 hover:bg-sky-600 text-white rounded-lg text-xs font-semibold shadow-sm"
                >
                  Simpan Jadwal
                </button>
              </div>
            </form>
          </div>
        </div>
      )}

      {/* =========================================================================
          MODAL: TAMBAH KONTAK MANUAL
      ========================================================================= */}
      {isContactModalOpen && editingContact && (
        <div className="fixed inset-0 z-50 bg-black/60 backdrop-blur-sm flex items-center justify-center p-4">
          <div className="bg-bg-panel border border-border-main rounded-2xl w-full max-w-sm shadow-2xl p-5 space-y-4">
            <div className="flex items-center justify-between border-b border-border-main pb-3">
              <h3 className="text-sm font-bold flex items-center gap-2">
                <Users className="w-4 h-4 text-sky-400" />
                {editingContact.id ? 'Edit Kontak' : 'Tambah Kontak Manual'}
              </h3>
              <button onClick={() => setIsContactModalOpen(false)} className="text-text-muted hover:text-text-main">
                <X className="w-4 h-4" />
              </button>
            </div>

            <form onSubmit={async (e) => {
              e.preventDefault();
              if (!editingContact?.botId || !editingContact?.chatId) return;
              try {
                if (editingContact.id) {
                  await axios.put(`/api/telegram/contacts/${editingContact.id}`, editingContact);
                } else {
                  await axios.post('/api/telegram/contacts', editingContact);
                }
                setIsContactModalOpen(false);
                setEditingContact(null);
                fetchContacts(selectedBotId);
                showAlert({ title: 'Kontak Disimpan', message: 'Kontak berhasil disimpan.', type: 'success' });
              } catch (err: any) {
                showAlert({ title: 'Gagal Menyimpan', message: err.message, type: 'error' });
              }
            }} className="space-y-3">
              <div>
                <label className="block text-xs font-medium text-text-muted mb-1">
                  Bot Telegram *
                </label>
                <select
                  value={editingContact.botId || ''}
                  onChange={e => setEditingContact({ ...editingContact, botId: e.target.value })}
                  className="w-full bg-bg-editor border border-border-main rounded-lg px-3 py-2 text-xs text-text-main focus:outline-none"
                >
                  {bots.map(b => (
                    <option key={b.id} value={b.id}>{b.name}</option>
                  ))}
                </select>
              </div>

              <div>
                <label className="block text-xs font-medium text-text-muted mb-1">
                  Chat ID Telegram *
                </label>
                <input
                  type="text"
                  required
                  value={editingContact.chatId || ''}
                  onChange={e => setEditingContact({ ...editingContact, chatId: e.target.value })}
                  placeholder="misal: 123456789"
                  className="w-full font-mono text-xs bg-bg-editor border border-border-main rounded-lg px-3 py-2 text-text-main focus:outline-none"
                />
              </div>

              <div>
                <label className="block text-xs font-medium text-text-muted mb-1">
                  Nomor Telepon
                </label>
                <input
                  type="text"
                  value={editingContact.phoneNumber || ''}
                  onChange={e => setEditingContact({ ...editingContact, phoneNumber: e.target.value })}
                  placeholder="misal: 08123456789"
                  className="w-full font-mono text-xs bg-bg-editor border border-border-main rounded-lg px-3 py-2 text-text-main focus:outline-none"
                />
              </div>

              <div>
                <label className="block text-xs font-medium text-text-muted mb-1">
                  Nama Penerima
                </label>
                <input
                  type="text"
                  value={editingContact.firstName || ''}
                  onChange={e => setEditingContact({ ...editingContact, firstName: e.target.value })}
                  placeholder="misal: Budi Santoso"
                  className="w-full text-xs bg-bg-editor border border-border-main rounded-lg px-3 py-2 text-text-main focus:outline-none"
                />
              </div>

              <div className="flex items-center justify-end gap-2 pt-3 border-t border-border-main">
                <button
                  type="button"
                  onClick={() => setIsContactModalOpen(false)}
                  className="px-4 py-2 bg-bg-editor hover:bg-bg-hover text-text-muted rounded-lg text-xs"
                >
                  Batal
                </button>
                <button
                  type="submit"
                  className="px-4 py-2 bg-sky-500 hover:bg-sky-600 text-white rounded-lg text-xs font-semibold shadow-sm"
                >
                  Simpan Kontak
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
};
