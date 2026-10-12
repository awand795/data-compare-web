import React, { useState, useEffect } from 'react';
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
  ExternalLink
} from 'lucide-react';
import clsx from 'clsx';

// ─────────────────────────────────────────────────────────────────────────────
// TYPES
// ─────────────────────────────────────────────────────────────────────────────
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
  phone: string;
  username?: string;
  firstName?: string;
  lastName?: string;
  registeredVia?: string;
  active: boolean;
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
  executedAt: string;
}

export const TelegramBotView: React.FC = () => {
  const { connections, showAlert } = useAppStore();

  // Top Level Navigation
  // 'messages' = List View | 'create_edit' = Form View
  const [viewMode, setViewMode] = useState<'list' | 'create_edit'>('list');
  const [activeTab, setActiveTab] = useState<'messages' | 'contacts' | 'bots' | 'logs'>('messages');

  // Master Data
  const [bots, setBots] = useState<TelegramBot[]>([]);
  const [contacts, setContacts] = useState<TelegramContact[]>([]);
  const [schedules, setSchedules] = useState<TelegramSchedule[]>([]);
  const [logs, setLogs] = useState<TelegramLog[]>([]);

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
  const [formBotId, setFormBotId] = useState('');
  const [formConnectionId, setFormConnectionId] = useState('');
  const [sourceMode, setSourceMode] = useState<'VIEW' | 'CUSTOM_SQL'>('VIEW');
  const [formSchema, setFormSchema] = useState('sch_erp_system');
  const [formView, setFormView] = useState('view_all_notifikasi');
  const [filterSchedule, setFilterSchedule] = useState('ALL'); // 'ALL' | 'Y' | 'N'
  const [filterKodeNotifikasi, setFilterKodeNotifikasi] = useState('');
  const [filterKodeCabang, setFilterKodeCabang] = useState('');
  const [formSqlQuery, setFormSqlQuery] = useState('');
  const [formPhoneColumn, setFormPhoneColumn] = useState('user_hp');
  const [formMaxRows, setFormMaxRows] = useState(50);

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

  // ─────────────────────────────────────────────────────────────────────────────
  // DATA FETCHING
  // ─────────────────────────────────────────────────────────────────────────────
  useEffect(() => {
    fetchBots();
    fetchContacts();
    fetchSchedules();
    fetchLogs();
  }, []);

  // Pick default connection
  useEffect(() => {
    if (connections.length > 0 && !formConnectionId) {
      const devPos = connections.find(c => c.name.toLowerCase().includes('dev-pos') || c.database?.toLowerCase().includes('dev_pos'));
      setFormConnectionId(devPos ? devPos.id : connections[0].id);
    }
  }, [connections]);

  useEffect(() => {
    if (formConnectionId) {
      loadSchemas(formConnectionId);
    }
  }, [formConnectionId]);

  useEffect(() => {
    if (formConnectionId && formSchema) {
      loadViews(formConnectionId, formSchema);
    }
  }, [formConnectionId, formSchema]);

  // Synchronize SQL Query based on View & Filters
  useEffect(() => {
    if (sourceMode === 'VIEW' && formSchema && formView) {
      let q = `SELECT kode_notifikasi, kode_cabang, no_tran, user_id, user_name, user_hp, pesan, schedule FROM ${formSchema}.${formView} WHERE 1=1`;
      if (filterSchedule === 'Y' || filterSchedule === 'N') {
        q += ` AND schedule = '${filterSchedule}'`;
      }
      if (filterKodeNotifikasi.trim()) {
        q += ` AND kode_notifikasi = '${filterKodeNotifikasi.trim()}'`;
      }
      if (filterKodeCabang.trim()) {
        q += ` AND kode_cabang = '${filterKodeCabang.trim()}'`;
      }
      setFormSqlQuery(q);
      setFormPhoneColumn('user_hp');
    }
  }, [sourceMode, formSchema, formView, filterSchedule, filterKodeNotifikasi, filterKodeCabang]);

  const fetchBots = async () => {
    try {
      const res = await axios.get('/api/telegram/bots');
      setBots(res.data);
      if (res.data.length > 0 && !formBotId) {
        setFormBotId(res.data[0].id);
      }
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

  const fetchLogs = async () => {
    try {
      const res = await axios.get('/api/telegram/logs');
      setLogs(res.data);
    } catch (e) {
      console.error('Failed to fetch logs', e);
    }
  };

  const loadSchemas = async (connId: string) => {
    try {
      const res = await axios.get(`/api/connections/${connId}/schemas`);
      if (Array.isArray(res.data)) {
        setSchemas(res.data);
        if (res.data.includes('sch_erp_system')) {
          setFormSchema('sch_erp_system');
        } else if (res.data.length > 0) {
          setFormSchema(res.data[0]);
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
          setFormView('view_all_notifikasi');
        } else if (viewList.length > 0) {
          setFormView(viewList[0].name);
        }
      }
    } catch (e) {
      console.error('Failed to load views', e);
    }
  };

  // ─────────────────────────────────────────────────────────────────────────────
  // HANDLERS FOR CREATE / EDIT FORM
  // ─────────────────────────────────────────────────────────────────────────────
  const handleOpenCreate = () => {
    setEditingId(null);
    setFormName('');
    if (bots.length > 0) setFormBotId(bots[0].id);
    setSourceMode('VIEW');
    setFilterSchedule('ALL');
    setFilterKodeNotifikasi('');
    setFilterKodeCabang('');
    setIsCronEnabled(false);
    setCronTriggers(['0 0 8 * * *']);
    setFormMaxRows(50);
    setFormActive(true);
    setPreviewRows([]);
    setViewMode('create_edit');
  };

  const handleOpenEdit = (s: TelegramSchedule) => {
    setEditingId(s.id);
    setFormName(s.name);
    setFormBotId(s.botId);
    setFormConnectionId(s.connectionId);
    setFormSqlQuery(s.sqlQuery);
    setFormPhoneColumn(s.phoneColumnName || 'user_hp');
    setFormMaxRows(s.maxRows || 50);
    setFormActive(s.active);

    if (s.viewName) {
      setSourceMode('VIEW');
      setFormSchema(s.schemaName || 'sch_erp_system');
      setFormView(s.viewName);
      setFilterSchedule(s.filterSchedule || 'ALL');
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
    if (!formConnectionId || !formSqlQuery) {
      showAlert({ title: 'Parameter Belum Siap', message: 'Pilih database dan pastikan query/view sudah dipilih.', type: 'warning' });
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
    if (!formBotId) {
      showAlert({ title: 'Bot Pengirim Kosong', message: 'Silakan pilih Bot Telegram pengirim.', type: 'warning' });
      return;
    }
    if (!formConnectionId) {
      showAlert({ title: 'Koneksi Database Kosong', message: 'Silakan pilih koneksi database sumber.', type: 'warning' });
      return;
    }

    setSavingForm(true);
    try {
      const payload: Partial<TelegramSchedule> = {
        name: formName.trim(),
        botId: formBotId,
        connectionId: formConnectionId,
        sqlQuery: formSqlQuery,
        templateMessage: '{{pesan}}', // selalu kirim isi asli kolom pesan dari view
        recipientMode: 'DYNAMIC_COLUMN',
        phoneColumnName: formPhoneColumn || 'user_hp',
        schemaName: sourceMode === 'VIEW' ? formSchema : undefined,
        viewName: sourceMode === 'VIEW' ? formView : undefined,
        filterSchedule: sourceMode === 'VIEW' ? filterSchedule : undefined,
        filterKodeNotifikasi: sourceMode === 'VIEW' ? filterKodeNotifikasi.trim() : undefined,
        filterKodeCabang: sourceMode === 'VIEW' ? filterKodeCabang.trim() : undefined,
        cronExpression: isCronEnabled ? cronTriggers.map(c => c.trim()).filter(Boolean).join(',') : '',
        maxRows: formMaxRows,
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

  const handleDeleteSchedule = async (scheduleId: string, name: string) => {
    if (!confirm(`Hapus konfigurasi pesan "${name}"?`)) return;
    try {
      await axios.delete(`/api/telegram/schedules/${scheduleId}`);
      fetchSchedules();
      showAlert({ title: 'Dihapus', message: 'Pesan notifikasi berhasil dihapus.', type: 'info' });
    } catch (err: any) {
      showAlert({ title: 'Gagal Menghapus', message: err.message, type: 'error' });
    }
  };

  // Bot Management Handlers
  const handleSaveBot = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!editingBot?.name || !editingBot?.botToken) {
      showAlert({ title: 'Input Kurang', message: 'Nama bot dan Token wajib diisi.', type: 'warning' });
      return;
    }
    try {
      if (editingBot.id) {
        await axios.put(`/api/telegram/bots/${editingBot.id}`, editingBot);
      } else {
        await axios.post('/api/telegram/bots', editingBot);
      }
      setIsBotModalOpen(false);
      setEditingBot(null);
      fetchBots();
      showAlert({ title: 'Berhasil', message: 'Bot Telegram berhasil disimpan.', type: 'success' });
    } catch (err: any) {
      showAlert({ title: 'Gagal', message: err.response?.data?.message || err.message, type: 'error' });
    }
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

              <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                <div className="md:col-span-2">
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

                <div>
                  <label className="block text-xs font-medium text-text-muted mb-1.5">
                    Bot Telegram Pengirim *
                  </label>
                  <select
                    value={formBotId}
                    onChange={e => setFormBotId(e.target.value)}
                    className="w-full bg-bg-editor border border-border-main rounded-lg px-3 py-2 text-xs text-text-main focus:outline-none focus:border-sky-500"
                  >
                    {bots.map(b => (
                      <option key={b.id} value={b.id}>
                        {b.name} {b.botUsername ? `(@${b.botUsername})` : ''}
                      </option>
                    ))}
                    {bots.length === 0 && <option value="">(Belum ada bot terdaftar)</option>}
                  </select>
                </div>

                <div>
                  <label className="block text-xs font-medium text-text-muted mb-1.5">
                    Database Sumber *
                  </label>
                  <select
                    value={formConnectionId}
                    onChange={e => setFormConnectionId(e.target.value)}
                    className="w-full bg-bg-editor border border-border-main rounded-lg px-3 py-2 text-xs text-text-main focus:outline-none focus:border-sky-500"
                  >
                    {connections.map(c => (
                      <option key={c.id} value={c.id}>
                        {c.name} ({c.database})
                      </option>
                    ))}
                  </select>
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
                        onChange={e => setFormSchema(e.target.value)}
                        className="w-full bg-bg-editor border border-border-main rounded-lg px-3 py-2 text-xs text-text-main focus:outline-none focus:border-sky-500"
                      >
                        {schemas.map(s => (
                          <option key={s} value={s}>{s}</option>
                        ))}
                        {schemas.length === 0 && <option value="sch_erp_system">sch_erp_system</option>}
                      </select>
                    </div>

                    <div>
                      <label className="block text-xs font-medium text-text-muted mb-1.5">
                        View Notifikasi
                      </label>
                      <select
                        value={formView}
                        onChange={e => setFormView(e.target.value)}
                        className="w-full bg-bg-editor border border-border-main rounded-lg px-3 py-2 text-xs text-text-main focus:outline-none focus:border-sky-500"
                      >
                        {views.map(v => (
                          <option key={v.name} value={v.name}>{v.name}</option>
                        ))}
                        {views.length === 0 && <option value="view_all_notifikasi">view_all_notifikasi</option>}
                      </select>
                    </div>
                  </div>

                  {/* Filter View Card */}
                  <div className="bg-bg-editor/50 border border-border-main rounded-lg p-3.5 space-y-3">
                    <span className="text-xs font-semibold text-text-main flex items-center gap-1.5">
                      <Filter className="w-3.5 h-3.5 text-sky-400" />
                      Filter Cepat Baris Data
                    </span>

                    <div className="space-y-3">
                      <div>
                        <label className="block text-[11px] text-text-muted mb-1.5">
                          Tipe Jadwal (Field <code>schedule</code>):
                        </label>
                        <div className="flex items-center gap-1.5">
                          {[
                            { id: 'ALL', label: 'Semua Notifikasi' },
                            { id: 'Y', label: 'Terjadwal / Laporan (Y)' },
                            { id: 'N', label: 'Realtime / Alert (N)' }
                          ].map(pill => (
                            <button
                              key={pill.id}
                              type="button"
                              onClick={() => setFilterSchedule(pill.id)}
                              className={clsx(
                                "px-2.5 py-1 rounded text-xs transition-colors",
                                filterSchedule === pill.id
                                  ? "bg-sky-500 text-white font-medium"
                                  : "bg-bg-panel text-text-muted hover:text-text-main border border-border-main"
                              )}
                            >
                              {pill.label}
                            </button>
                          ))}
                        </div>
                      </div>

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
              <div className="flex items-center justify-between">
                <div>
                  <h2 className="text-xs font-bold uppercase tracking-wider text-sky-400 flex items-center gap-1.5">
                    <Clock className="w-4 h-4" />
                    3. Jadwal Pengiriman Otomatis (Opsional)
                  </h2>
                  <p className="text-[11px] text-text-muted">
                    Jika diaktifkan, server akan otomatis mengirimkan pesan sesuai waktu yang ditentukan.
                  </p>
                </div>

                <div className="flex items-center gap-2.5">
                  <span className={clsx(
                    "text-[11px] font-semibold px-2 py-0.5 rounded-full transition-colors",
                    isCronEnabled
                      ? "bg-sky-500/15 text-sky-400 border border-sky-500/30"
                      : "bg-slate-700/50 text-slate-400 border border-slate-600"
                  )}>
                    {isCronEnabled ? 'Aktif' : 'Nonaktif'}
                  </span>

                  <button
                    type="button"
                    role="switch"
                    aria-checked={isCronEnabled}
                    onClick={() => setIsCronEnabled(!isCronEnabled)}
                    className={clsx(
                      "relative inline-flex h-6 w-11 shrink-0 cursor-pointer rounded-full border-2 transition-colors duration-200 ease-in-out focus:outline-none focus:ring-2 focus:ring-sky-500/50 shadow-inner",
                      isCronEnabled
                        ? "bg-sky-500 border-sky-500"
                        : "bg-slate-700 border-slate-500 hover:border-slate-400"
                    )}
                    title={isCronEnabled ? "Klik untuk menonaktifkan jadwal" : "Klik untuk mengaktifkan jadwal otomatis"}
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
                            className="w-full bg-transparent border-0 text-xs font-mono font-bold text-sky-300 focus:outline-none placeholder:text-text-muted"
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
                  <p>Klik tombol <b>"Muat Data"</b> untuk melihat live preview pesan dari view saat ini.</p>
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
            Pengaturan Bot ({bots.length})
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
                            {s.filterSchedule && s.filterSchedule !== 'ALL' && (
                              <span className="text-text-muted">
                                • Sched: {s.filterSchedule}
                              </span>
                            )}
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
                          <div className="text-[10px] text-text-muted">
                            Maks {s.maxRows || 50} baris
                          </div>
                        </td>

                        <td className="py-3.5 px-4">
                          <div className="flex items-center gap-1.5 text-text-main font-medium">
                            <Bot className="w-3.5 h-3.5 text-sky-400" />
                            {bot?.name || 'Bot'}
                          </div>
                          {bot?.botUsername && (
                            <div className="text-[10px] text-text-muted font-mono">
                              @{bot.botUsername}
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
            <div className="bg-bg-panel border border-border-main rounded-xl p-5 shadow-sm space-y-4">
              <div className="flex items-center justify-between border-b border-border-main pb-3">
                <div>
                  <h3 className="text-sm font-bold flex items-center gap-2">
                    <Users className="w-4 h-4 text-sky-400" />
                    Master Kontak Telegram Terdaftar
                  </h3>
                  <p className="text-xs text-text-muted">
                    Daftar pengguna yang sudah menekan <code>/start</code> dan membagikan kontak ke Bot Telegram.
                  </p>
                </div>
                <button
                  type="button"
                  onClick={fetchContacts}
                  className="flex items-center gap-1.5 px-3 py-1.5 bg-bg-editor hover:bg-bg-hover text-xs font-semibold rounded-lg border border-border-main"
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
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-border-main/50">
                    {contacts.map(c => {
                      const b = bots.find(bot => bot.id === c.botId);
                      return (
                        <tr key={c.id} className="hover:bg-bg-hover/50">
                          <td className="py-3 px-3 font-semibold text-text-main">
                            {c.firstName || ''} {c.lastName || ''} {c.username ? `(@${c.username})` : ''}
                          </td>
                          <td className="py-3 px-3 font-mono text-sky-400">
                            📱 {c.phone}
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
                        </tr>
                      );
                    })}

                    {contacts.length === 0 && (
                      <tr>
                        <td colSpan={5} className="py-8 text-center text-text-muted">
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
            TAB 3: PENGATURAN BOT
        ========================================================================= */}
        {activeTab === 'bots' && (
          <div className="max-w-7xl mx-auto space-y-4">
            <div className="bg-bg-panel border border-border-main rounded-xl p-5 shadow-sm space-y-4">
              <div className="flex items-center justify-between border-b border-border-main pb-3">
                <div>
                  <h3 className="text-sm font-bold flex items-center gap-2">
                    <Bot className="w-4 h-4 text-sky-400" />
                    Daftar Bot Telegram
                  </h3>
                  <p className="text-xs text-text-muted">
                    Kelola token bot dari @BotFather dan status polling otomatis.
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

                    <div className="text-[11px] text-text-muted border-t border-border-main pt-2">
                      Token: <code className="text-text-main font-mono">••••••••••••••</code>
                    </div>
                  </div>
                ))}
              </div>
            </div>
          </div>
        )}

        {/* =========================================================================
            TAB 4: RIWAYAT LOG
        ========================================================================= */}
        {activeTab === 'logs' && (
          <div className="max-w-7xl mx-auto space-y-4">
            <div className="bg-bg-panel border border-border-main rounded-xl p-5 shadow-sm space-y-4">
              <div className="flex items-center justify-between border-b border-border-main pb-3">
                <div>
                  <h3 className="text-sm font-bold flex items-center gap-2">
                    <History className="w-4 h-4 text-sky-400" />
                    Riwayat Pengiriman Pesan
                  </h3>
                  <p className="text-xs text-text-muted">
                    Catatan lengkap status pengiriman, pesan terkirim, dan rincian kontak yang belum klik START.
                  </p>
                </div>
                <button
                  type="button"
                  onClick={fetchLogs}
                  className="flex items-center gap-1.5 px-3 py-1.5 bg-bg-editor hover:bg-bg-hover text-xs font-semibold rounded-lg border border-border-main"
                >
                  <RefreshCw className="w-3.5 h-3.5" />
                  Refresh Log
                </button>
              </div>

              <div className="overflow-x-auto">
                <table className="w-full text-left text-xs">
                  <thead>
                    <tr className="border-b border-border-main text-text-muted uppercase text-[10px]">
                      <th className="py-2.5 px-3 font-semibold">Waktu Eksekusi</th>
                      <th className="py-2.5 px-3 font-semibold">Nama Notifikasi</th>
                      <th className="py-2.5 px-3 font-semibold">Bot</th>
                      <th className="py-2.5 px-3 font-semibold">Terkirim / Gagal</th>
                      <th className="py-2.5 px-3 font-semibold">Status & Rincian</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-border-main/50">
                    {logs.map(log => (
                      <tr key={log.id} className="hover:bg-bg-hover/50">
                        <td className="py-3 px-3 text-text-muted font-mono text-[11px]">
                          {log.executedAt ? new Date(log.executedAt).toLocaleString() : '-'}
                        </td>
                        <td className="py-3 px-3 font-semibold text-text-main">
                          {log.scheduleName || 'Manual Execution'}
                        </td>
                        <td className="py-3 px-3 text-text-muted">
                          {log.botName || 'Bot'}
                        </td>
                        <td className="py-3 px-3 font-mono">
                          <span className="text-emerald-400">{log.messagesSent} Berhasil</span>
                          {log.messagesFailed > 0 && (
                            <span className="text-rose-400 ml-1.5">• {log.messagesFailed} Gagal</span>
                          )}
                        </td>
                        <td className="py-3 px-3 text-[11px] text-text-muted max-w-md truncate" title={log.details}>
                          {log.details || '-'}
                        </td>
                      </tr>
                    ))}

                    {logs.length === 0 && (
                      <tr>
                        <td colSpan={5} className="py-8 text-center text-text-muted">
                          Belum ada riwayat pengiriman pesan.
                        </td>
                      </tr>
                    )}
                  </tbody>
                </table>
              </div>
            </div>
          </div>
        )}
      </div>

      {/* Modal: Tambah Bot Baru */}
      {isBotModalOpen && (
        <div className="fixed inset-0 bg-black/60 backdrop-blur-sm flex items-center justify-center p-4 z-50">
          <div className="bg-bg-panel border border-border-main rounded-xl max-w-md w-full p-5 space-y-4 shadow-xl">
            <h3 className="text-sm font-bold flex items-center gap-2">
              <Bot className="w-4 h-4 text-sky-400" />
              Tambah Bot Telegram Baru
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
                  className="px-4 py-1.5 bg-sky-500 hover:bg-sky-600 text-white font-semibold rounded-lg"
                >
                  Simpan Bot
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
};
