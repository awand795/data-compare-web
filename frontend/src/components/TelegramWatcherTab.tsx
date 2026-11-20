import React, { useEffect, useState } from 'react';
import axios from 'axios';
import {
  Zap, Plus, Play, Trash2, Edit2, Power, Radio, Webhook,
  Code2, Copy, Check, RotateCcw, X, Clock
} from 'lucide-react';
import clsx from 'clsx';

export interface TelegramWatcherItem {
  id?: string;
  name: string;
  botId: string;
  connectionId: string;
  watcherType: 'POLLING_WATERMARK' | 'PG_LISTEN_NOTIFY' | 'WEBHOOK_TRIGGER';
  sqlQuery: string;
  watermarkColumn: string;
  lastWatermark?: string;
  pollIntervalSeconds: number;
  flagUpdateSql?: string;
  pgChannelName?: string;
  templateMessage: string;
  recipientMode: 'MASTER' | 'DYNAMIC_COLUMN';
  masterRecipientIds: string;
  phoneColumnName?: string;
  active: boolean;
  totalSentCount?: number;
  lastTriggeredAt?: string;
  lastStatus?: string;
  lastMessage?: string;
}

interface TelegramWatcherTabProps {
  bots: Array<{ id: string; name: string; botUsername?: string }>;
  contacts: Array<{ id: string; name?: string; firstName?: string; username?: string; chatId: string; phoneNumber?: string }>;
  connections: Array<{ id: string; name: string; type: string }>;
  tokens: Array<{ id: string; token: string; isActive?: boolean; active?: boolean }>;
  onCopyText: (text: string, key?: string) => void;
}

export const TelegramWatcherTab: React.FC<TelegramWatcherTabProps> = ({
  bots,
  contacts,
  connections,
  tokens,
  onCopyText
}) => {
  const [watchers, setWatchers] = useState<TelegramWatcherItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [isModalOpen, setIsModalOpen] = useState(false);
  const [editing, setEditing] = useState<TelegramWatcherItem | null>(null);
  const [sqlGuideWatcher, setSqlGuideWatcher] = useState<TelegramWatcherItem | null>(null);
  const [sqlGuideTable, setSqlGuideTable] = useState('public.tr_spb');
  const [sqlGuideMode, setSqlGuideMode] = useState<'pg_notify' | 'pg_net' | 'mssql'>('pg_notify');
  const [copiedKey, setCopiedKey] = useState<string | null>(null);

  const fetchWatchers = async () => {
    setLoading(true);
    try {
      const res = await axios.get('/api/telegram/watchers');
      setWatchers(res.data || []);
    } catch (e) {
      console.error('Failed to fetch watchers', e);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchWatchers();
  }, []);

  const openCreate = () => {
    setEditing({
      name: '',
      botId: bots[0]?.id || '',
      connectionId: connections[0]?.id || '',
      watcherType: 'POLLING_WATERMARK',
      sqlQuery: 'SELECT id, no_bukti, nama_customer, total FROM tr_spb',
      watermarkColumn: 'id',
      lastWatermark: '',
      pollIntervalSeconds: 5,
      flagUpdateSql: '',
      pgChannelName: 'tg_new_data_channel',
      templateMessage: '<b>Data Baru Masuk!</b>\nNo: {{no_bukti}}\nCustomer: {{nama_customer}}\nTotal: {{total}}',
      recipientMode: 'MASTER',
      masterRecipientIds: contacts[0]?.id || '',
      phoneColumnName: 'chat_id',
      active: true
    });
    setIsModalOpen(true);
  };

  const saveWatcher = async () => {
    if (!editing || !editing.name.trim() || !editing.botId || !editing.templateMessage.trim()) return;
    try {
      if (editing.id) {
        await axios.put(`/api/telegram/watchers/${editing.id}`, editing);
      } else {
        await axios.post('/api/telegram/watchers', editing);
      }
      setIsModalOpen(false);
      setEditing(null);
      fetchWatchers();
    } catch (e) {
      console.error('Failed to save watcher', e);
    }
  };

  const toggleWatcher = async (id: string) => {
    await axios.put(`/api/telegram/watchers/${id}/toggle`);
    fetchWatchers();
  };

  const runNow = async (id: string) => {
    await axios.post(`/api/telegram/watchers/${id}/run-now`);
    fetchWatchers();
  };

  const resetWatermark = async (id: string) => {
    const val = window.prompt('Set Last Watermark baru (kosongkan untuk auto-init ulang ke MAX):', '');
    if (val === null) return;
    await axios.post(`/api/telegram/watchers/${id}/reset-watermark`, { lastWatermark: val });
    fetchWatchers();
  };

  const deleteWatcher = async (id: string) => {
    if (!window.confirm('Hapus watcher ini?')) return;
    await axios.delete(`/api/telegram/watchers/${id}`);
    fetchWatchers();
  };

  const handleCopy = (text: string, key: string) => {
    onCopyText(text, key);
    setCopiedKey(key);
    setTimeout(() => setCopiedKey(null), 2000);
  };

  const activeToken = tokens.find(t => t.isActive || t.active)?.token || '<YOUR_TELEGRAM_API_TOKEN>';
  const originUrl = typeof window !== 'undefined' ? window.location.origin : 'https://your-darkosync-domain';

  const generateTriggerSql = (w: TelegramWatcherItem) => {
    const tbl = sqlGuideTable.trim() || 'public.my_table';
    const cleanTbl = tbl.replace(/[^a-zA-Z0-9_]/g, '_');
    const channel = (w.pgChannelName || 'tg_new_data_channel').replace(/[^a-zA-Z0-9_]/g, '_');

    if (sqlGuideMode === 'pg_notify') {
      return `-- 1. Buat Function Trigger (PostgreSQL - Paling Aman & 0 Delay)
CREATE OR REPLACE FUNCTION fn_notify_${cleanTbl}_tg()
RETURNS trigger AS $$
BEGIN
  PERFORM pg_notify('${channel}', row_to_json(NEW)::text);
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- 2. Pasang Trigger AFTER INSERT pada tabel ${tbl}
DROP TRIGGER IF EXISTS trg_notify_${cleanTbl}_tg ON ${tbl};
CREATE TRIGGER trg_notify_${cleanTbl}_tg
AFTER INSERT ON ${tbl}
FOR EACH ROW
EXECUTE FUNCTION fn_notify_${cleanTbl}_tg();`;
    }

    if (sqlGuideMode === 'pg_net') {
      return `-- Pastikan extension pg_net aktif: CREATE EXTENSION IF NOT EXISTS pg_net;
CREATE OR REPLACE FUNCTION fn_http_${cleanTbl}_tg()
RETURNS trigger AS $$
BEGIN
  PERFORM net.http_post(
    url := '${originUrl}/api/v1/telegram/external/trigger/${w.id}',
    headers := jsonb_build_object(
      'Content-Type', 'application/json',
      'Authorization', 'Bearer ${activeToken}'
    ),
    body := to_jsonb(NEW)
  );
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_http_${cleanTbl}_tg ON ${tbl};
CREATE TRIGGER trg_http_${cleanTbl}_tg
AFTER INSERT ON ${tbl}
FOR EACH ROW
EXECUTE FUNCTION fn_http_${cleanTbl}_tg();`;
    }

    return `-- Contoh SQL Server / MySQL:
-- Karena SQL Server/MySQL tidak punya pg_notify native, cara paling aman adalah:
-- 1) Gunakan mode POLLING_WATERMARK di DarkoSync (cek tiap 3-5 detik via index id/created_at), ATAU
-- 2) Panggil endpoint berikut dari service/trigger/webhook:
--    POST ${originUrl}/api/v1/telegram/external/trigger/${w.id}
--    Header: Authorization: Bearer ${activeToken}
--    Body JSON: { "id": 123, "no_bukti": "SPB-001", "total": 500000 }`;
  };

  const selectedMasterList = (editing?.masterRecipientIds || '')
    .split(/[,;\s]+/)
    .map(s => s.trim())
    .filter(Boolean);

  const toggleMasterContact = (contactId: string) => {
    if (!editing) return;
    const exists = selectedMasterList.includes(contactId);
    const next = exists
      ? selectedMasterList.filter(x => x !== contactId)
      : [...selectedMasterList, contactId];
    setEditing({ ...editing, masterRecipientIds: next.join(',') });
  };

  return (
    <div className="max-w-7xl mx-auto space-y-4 animate-in fade-in duration-200">
      {/* Header Toolbar */}
      <div className="bg-bg-panel border border-border-main p-4 rounded-2xl flex flex-wrap items-center justify-between gap-3">
        <div className="flex items-center gap-3">
          <div className="p-2.5 rounded-xl bg-amber-500/15 border border-amber-500/30 text-amber-400">
            <Zap className="w-5 h-5" />
          </div>
          <div>
            <h2 className="text-sm font-black text-text-main">Real-Time Data Watchers &amp; DB Triggers</h2>
            <p className="text-xs text-text-muted">
              Kirim pesan Telegram otomatis begitu ada baris data baru (Polling Watermark ringan, PostgreSQL LISTEN/NOTIFY, atau Webhook DB Trigger).
            </p>
          </div>
        </div>
        <button
          onClick={openCreate}
          className="px-4 py-2 bg-sky-500 hover:bg-sky-400 text-white rounded-xl text-xs font-bold flex items-center gap-1.5 shadow-sm cursor-pointer"
        >
          <Plus className="w-4 h-4" /> Buat Watcher / Trigger
        </button>
      </div>

      {/* Watchers List */}
      {loading && watchers.length === 0 ? (
        <div className="p-10 text-center text-xs text-text-muted">Memuat watchers...</div>
      ) : watchers.length === 0 ? (
        <div className="bg-bg-panel border border-dashed border-border-main rounded-2xl p-10 text-center space-y-3">
          <Zap className="w-10 h-10 text-amber-400/50 mx-auto" />
          <div className="text-sm font-bold text-text-main">Belum ada Real-Time Watcher</div>
          <p className="text-xs text-text-muted max-w-md mx-auto">
            Buat watcher untuk memantau tabel ERP tiap beberapa detik (Mode Watermark) atau langsung menerima sinyal <code>pg_notify</code> / HTTP dari DB Trigger.
          </p>
          <button
            onClick={openCreate}
            className="px-4 py-2 bg-sky-500 hover:bg-sky-400 text-white rounded-xl text-xs font-bold inline-flex items-center gap-1.5 cursor-pointer"
          >
            <Plus className="w-4 h-4" /> Buat Watcher Pertama
          </button>
        </div>
      ) : (
        <div className="grid grid-cols-1 gap-3">
          {watchers.map(w => {
            const botName = bots.find(b => b.id === w.botId)?.name || w.botId;
            const connName = connections.find(c => c.id === w.connectionId)?.name || '-';
            return (
              <div
                key={w.id}
                className={clsx(
                  'bg-bg-panel border rounded-2xl p-4 transition-all space-y-3',
                  w.active ? 'border-border-main' : 'border-border-main/50 opacity-70'
                )}
              >
                <div className="flex flex-wrap items-center justify-between gap-3">
                  <div className="flex items-center gap-2.5 flex-wrap">
                    <span
                      className={clsx(
                        'px-2.5 py-1 rounded-lg text-[10px] font-black uppercase flex items-center gap-1',
                        w.watcherType === 'PG_LISTEN_NOTIFY'
                          ? 'bg-emerald-500/15 text-emerald-400 border border-emerald-500/30'
                          : w.watcherType === 'WEBHOOK_TRIGGER'
                            ? 'bg-purple-500/15 text-purple-400 border border-purple-500/30'
                            : 'bg-sky-500/15 text-sky-400 border border-sky-500/30'
                      )}
                    >
                      {w.watcherType === 'PG_LISTEN_NOTIFY' && <Radio className="w-3 h-3" />}
                      {w.watcherType === 'WEBHOOK_TRIGGER' && <Webhook className="w-3 h-3" />}
                      {w.watcherType === 'POLLING_WATERMARK' && <Clock className="w-3 h-3" />}
                      {w.watcherType === 'POLLING_WATERMARK'
                        ? `POLLING ${w.pollIntervalSeconds}s`
                        : w.watcherType === 'PG_LISTEN_NOTIFY'
                          ? `PG LISTEN (${w.pgChannelName})`
                          : 'WEBHOOK / DB TRIGGER API'}
                    </span>
                    <span className="text-sm font-black text-text-main">{w.name}</span>
                    <span className="text-[11px] text-text-muted">Bot: <strong className="text-sky-300">{botName}</strong></span>
                    {w.watcherType !== 'WEBHOOK_TRIGGER' && (
                      <span className="text-[11px] text-text-muted">DB: <strong className="text-emerald-300">{connName}</strong></span>
                    )}
                  </div>

                  <div className="flex items-center gap-1.5 flex-wrap">
                    <button
                      onClick={() => setSqlGuideWatcher(w)}
                      className="px-2.5 py-1.5 rounded-lg bg-amber-500/10 hover:bg-amber-500/20 text-amber-300 border border-amber-500/30 text-[11px] font-bold flex items-center gap-1 cursor-pointer"
                    >
                      <Code2 className="w-3.5 h-3.5" /> SQL Trigger Guide
                    </button>
                    {w.watcherType === 'POLLING_WATERMARK' && (
                      <>
                        <button
                          onClick={() => runNow(w.id!)}
                          className="px-2.5 py-1.5 rounded-lg bg-emerald-500/10 hover:bg-emerald-500/20 text-emerald-300 border border-emerald-500/30 text-[11px] font-bold flex items-center gap-1 cursor-pointer"
                        >
                          <Play className="w-3 h-3" /> Check Now
                        </button>
                        <button
                          onClick={() => resetWatermark(w.id!)}
                          className="px-2.5 py-1.5 rounded-lg bg-slate-800 hover:bg-slate-700 text-slate-300 text-[11px] font-bold flex items-center gap-1 cursor-pointer"
                          title="Reset Last Watermark"
                        >
                          <RotateCcw className="w-3 h-3" /> Watermark
                        </button>
                      </>
                    )}
                    <button
                      onClick={() => toggleWatcher(w.id!)}
                      className={clsx(
                        'px-2.5 py-1.5 rounded-lg text-[11px] font-bold flex items-center gap-1 cursor-pointer border',
                        w.active
                          ? 'bg-emerald-500/15 text-emerald-300 border-emerald-500/30'
                          : 'bg-slate-800 text-slate-400 border-slate-700'
                      )}
                    >
                      <Power className="w-3 h-3" /> {w.active ? 'Aktif' : 'Nonaktif'}
                    </button>
                    <button
                      onClick={() => { setEditing(w); setIsModalOpen(true); }}
                      className="p-1.5 rounded-lg bg-bg-editor hover:bg-white/10 text-text-muted hover:text-text-main cursor-pointer"
                    >
                      <Edit2 className="w-3.5 h-3.5" />
                    </button>
                    <button
                      onClick={() => deleteWatcher(w.id!)}
                      className="p-1.5 rounded-lg bg-rose-500/10 hover:bg-rose-500/20 text-rose-400 cursor-pointer"
                    >
                      <Trash2 className="w-3.5 h-3.5" />
                    </button>
                  </div>
                </div>

                {/* Status Bar */}
                <div className="grid grid-cols-2 md:grid-cols-4 gap-2 text-[11px] bg-bg-main/40 rounded-xl p-2.5 border border-border-main/60">
                  <div>
                    <span className="text-text-muted">Total Terkirim:</span>{' '}
                    <strong className="text-emerald-400 font-mono">{w.totalSentCount || 0}</strong>
                  </div>
                  {w.watcherType === 'POLLING_WATERMARK' && (
                    <div>
                      <span className="text-text-muted">Last Watermark ({w.watermarkColumn || '-'}):</span>{' '}
                      <strong className="text-cyan-300 font-mono">{w.lastWatermark || 'auto-init'}</strong>
                    </div>
                  )}
                  <div>
                    <span className="text-text-muted">Last Status:</span>{' '}
                    <strong className={w.lastStatus === 'FAILED' ? 'text-rose-400' : 'text-emerald-300'}>
                      {w.lastStatus || 'STANDBY'}
                    </strong>
                  </div>
                  <div className="truncate">
                    <span className="text-text-muted">Info:</span>{' '}
                    <span className="text-text-main">{w.lastMessage || '-'}</span>
                  </div>
                </div>
              </div>
            );
          })}
        </div>
      )}

      {/* CREATE / EDIT MODAL */}
      {isModalOpen && editing && (
        <div className="fixed inset-0 z-50 bg-black/70 backdrop-blur-sm flex items-center justify-center p-4">
          <div className="bg-bg-panel border border-border-main rounded-2xl max-w-3xl w-full max-h-[90vh] flex flex-col overflow-hidden shadow-2xl">
            <div className="px-5 py-3.5 border-b border-border-main flex items-center justify-between">
              <h3 className="text-sm font-black text-text-main">
                {editing.id ? 'Edit Real-Time Watcher' : 'Buat Real-Time Watcher / DB Trigger'}
              </h3>
              <button onClick={() => setIsModalOpen(false)} className="text-text-muted hover:text-text-main cursor-pointer">
                <X className="w-4 h-4" />
              </button>
            </div>

            <div className="flex-1 overflow-y-auto p-5 space-y-4">
              <div className="grid grid-cols-1 md:grid-cols-3 gap-3">
                <div>
                  <label className="block text-[11px] font-bold text-text-muted uppercase mb-1">Nama Watcher</label>
                  <input
                    type="text"
                    value={editing.name}
                    onChange={e => setEditing({ ...editing, name: e.target.value })}
                    placeholder="e.g. Notif SPB Baru"
                    className="w-full bg-bg-editor border border-border-main rounded-xl px-3 py-2 text-xs text-text-main outline-none focus:border-sky-500"
                  />
                </div>
                <div>
                  <label className="block text-[11px] font-bold text-text-muted uppercase mb-1">Bot Pengirim</label>
                  <select
                    value={editing.botId}
                    onChange={e => setEditing({ ...editing, botId: e.target.value })}
                    className="w-full bg-bg-editor border border-border-main rounded-xl px-3 py-2 text-xs text-text-main outline-none"
                  >
                    <option value="">-- Pilih Bot --</option>
                    {bots.map(b => (
                      <option key={b.id} value={b.id}>{b.name} (@{b.botUsername})</option>
                    ))}
                  </select>
                </div>
                <div>
                  <label className="block text-[11px] font-bold text-text-muted uppercase mb-1">Mekanisme Trigger</label>
                  <select
                    value={editing.watcherType}
                    onChange={e => setEditing({ ...editing, watcherType: e.target.value as any })}
                    className="w-full bg-bg-editor border border-border-main rounded-xl px-3 py-2 text-xs font-bold text-amber-300 outline-none"
                  >
                    <option value="POLLING_WATERMARK">Opsi 1: Polling Watermark (Semua DB)</option>
                    <option value="PG_LISTEN_NOTIFY">Opsi 2B: PostgreSQL LISTEN / NOTIFY</option>
                    <option value="WEBHOOK_TRIGGER">Opsi 2A: DB Trigger / Webhook API</option>
                  </select>
                </div>
              </div>

              {editing.watcherType !== 'WEBHOOK_TRIGGER' && (
                <div>
                  <label className="block text-[11px] font-bold text-text-muted uppercase mb-1">Koneksi Database Sumber</label>
                  <select
                    value={editing.connectionId}
                    onChange={e => setEditing({ ...editing, connectionId: e.target.value })}
                    className="w-full bg-bg-editor border border-border-main rounded-xl px-3 py-2 text-xs text-emerald-300 font-bold outline-none"
                  >
                    <option value="">-- Pilih Database --</option>
                    {connections.map(c => (
                      <option key={c.id} value={c.id}>{c.name} ({c.type})</option>
                    ))}
                  </select>
                </div>
              )}

              {editing.watcherType === 'POLLING_WATERMARK' && (
                <div className="space-y-3 p-3.5 rounded-xl bg-sky-500/5 border border-sky-500/20">
                  <div className="grid grid-cols-1 md:grid-cols-3 gap-3">
                    <div>
                      <label className="block text-[10px] font-bold text-text-muted uppercase mb-1">Kolom Watermark (Index)</label>
                      <input
                        type="text"
                        value={editing.watermarkColumn}
                        onChange={e => setEditing({ ...editing, watermarkColumn: e.target.value })}
                        placeholder="id atau created_at"
                        className="w-full bg-bg-editor border border-border-main rounded-lg px-2.5 py-1.5 text-xs font-mono text-cyan-300 outline-none"
                      />
                    </div>
                    <div>
                      <label className="block text-[10px] font-bold text-text-muted uppercase mb-1">Interval Cek (Detik)</label>
                      <input
                        type="number"
                        min={2}
                        max={3600}
                        value={editing.pollIntervalSeconds}
                        onChange={e => setEditing({ ...editing, pollIntervalSeconds: Math.max(2, Number(e.target.value) || 5) })}
                        className="w-full bg-bg-editor border border-border-main rounded-lg px-2.5 py-1.5 text-xs font-mono text-text-main outline-none"
                      />
                    </div>
                    <div>
                      <label className="block text-[10px] font-bold text-text-muted uppercase mb-1">Last Watermark (Opsional)</label>
                      <input
                        type="text"
                        value={editing.lastWatermark || ''}
                        onChange={e => setEditing({ ...editing, lastWatermark: e.target.value })}
                        placeholder="Kosong = auto MAX saat start"
                        className="w-full bg-bg-editor border border-border-main rounded-lg px-2.5 py-1.5 text-xs font-mono text-text-main outline-none"
                      />
                    </div>
                  </div>
                  <div>
                    <label className="block text-[10px] font-bold text-text-muted uppercase mb-1">
                      Base SQL Query (otomatis difilter <code>WHERE watermark &gt; :last_watermark</code>)
                    </label>
                    <textarea
                      rows={3}
                      value={editing.sqlQuery}
                      onChange={e => setEditing({ ...editing, sqlQuery: e.target.value })}
                      className="w-full bg-[#050810] border border-border-main rounded-lg p-2.5 text-xs font-mono text-blue-300 outline-none"
                    />
                  </div>
                  <div>
                    <label className="block text-[10px] font-bold text-text-muted uppercase mb-1">
                      Opsional: Flag Update SQL Setelah Terkirim (misal <code>UPDATE tr_spb SET is_sent = &apos;Y&apos; WHERE id = :id</code>)
                    </label>
                    <input
                      type="text"
                      value={editing.flagUpdateSql || ''}
                      onChange={e => setEditing({ ...editing, flagUpdateSql: e.target.value })}
                      placeholder="Kosongkan jika ingin murni Read-Only"
                      className="w-full bg-[#050810] border border-border-main rounded-lg px-2.5 py-1.5 text-xs font-mono text-amber-200 outline-none"
                    />
                  </div>
                </div>
              )}

              {editing.watcherType === 'PG_LISTEN_NOTIFY' && (
                <div className="p-3.5 rounded-xl bg-emerald-500/5 border border-emerald-500/20 space-y-2">
                  <label className="block text-[11px] font-bold text-emerald-300 uppercase">Nama Channel PostgreSQL LISTEN / NOTIFY</label>
                  <input
                    type="text"
                    value={editing.pgChannelName || ''}
                    onChange={e => setEditing({ ...editing, pgChannelName: e.target.value })}
                    placeholder="tg_new_data_channel"
                    className="w-full bg-bg-editor border border-border-main rounded-lg px-3 py-2 text-xs font-mono text-emerald-300 outline-none"
                  />
                  <p className="text-[11px] text-text-muted">
                    Setelah disimpan, klik tombol <strong>SQL Trigger Guide</strong> untuk menyalin script <code>CREATE TRIGGER</code> PostgreSQL siap pakai.
                  </p>
                </div>
              )}

              <div>
                <label className="block text-[11px] font-bold text-text-muted uppercase mb-1">
                  Template Pesan Telegram (gunakan <code className="text-cyan-400">{'{{nama_kolom}}'}</code>)
                </label>
                <textarea
                  rows={4}
                  value={editing.templateMessage}
                  onChange={e => setEditing({ ...editing, templateMessage: e.target.value })}
                  className="w-full bg-[#050810] border border-border-main rounded-xl p-3 text-xs font-mono text-emerald-300 outline-none"
                />
              </div>

              <div className="space-y-2">
                <div className="flex items-center gap-3">
                  <label className="text-[11px] font-bold text-text-muted uppercase">Mode Penerima:</label>
                  <select
                    value={editing.recipientMode}
                    onChange={e => setEditing({ ...editing, recipientMode: e.target.value as any })}
                    className="bg-bg-editor border border-border-main rounded-lg px-2.5 py-1 text-xs text-text-main outline-none"
                  >
                    <option value="MASTER">Pilih Kontak / Group Master</option>
                    <option value="DYNAMIC_COLUMN">Dari Kolom Tabel / JSON (Dynamic)</option>
                  </select>
                </div>

                {editing.recipientMode === 'MASTER' ? (
                  <div className="space-y-2">
                    <div className="flex flex-wrap gap-1.5">
                      {contacts.map(c => {
                        const checked = selectedMasterList.includes(c.id);
                        return (
                          <button
                            key={c.id}
                            type="button"
                            onClick={() => toggleMasterContact(c.id)}
                            className={clsx(
                              'px-2.5 py-1 rounded-lg text-xs font-semibold border cursor-pointer',
                              checked
                                ? 'bg-sky-500/20 text-sky-300 border-sky-500/40'
                                : 'bg-bg-editor text-text-muted border-border-main'
                            )}
                          >
                            {c.name || c.firstName || c.username || c.chatId} ({c.chatId})
                          </button>
                        );
                      })}
                    </div>
                    <input
                      type="text"
                      value={editing.masterRecipientIds}
                      onChange={e => setEditing({ ...editing, masterRecipientIds: e.target.value })}
                      placeholder="Atau ketik Chat ID langsung dipisah koma, misal: 123456789,-100987654"
                      className="w-full bg-bg-editor border border-border-main rounded-lg px-3 py-1.5 text-xs font-mono text-text-main outline-none"
                    />
                  </div>
                ) : (
                  <input
                    type="text"
                    value={editing.phoneColumnName || ''}
                    onChange={e => setEditing({ ...editing, phoneColumnName: e.target.value })}
                    placeholder="Nama kolom yang berisi Chat ID / No HP, misal: chat_id"
                    className="w-full bg-bg-editor border border-border-main rounded-lg px-3 py-1.5 text-xs font-mono text-text-main outline-none"
                  />
                )}
              </div>
            </div>

            <div className="px-5 py-3.5 border-t border-border-main flex items-center justify-end gap-2">
              <button
                onClick={() => setIsModalOpen(false)}
                className="px-4 py-2 rounded-xl bg-bg-editor text-xs font-bold text-text-muted hover:text-text-main cursor-pointer"
              >
                Batal
              </button>
              <button
                onClick={saveWatcher}
                className="px-4 py-2 rounded-xl bg-sky-500 hover:bg-sky-400 text-white text-xs font-bold cursor-pointer"
              >
                Simpan Watcher
              </button>
            </div>
          </div>
        </div>
      )}

      {/* SQL TRIGGER GENERATOR MODAL */}
      {sqlGuideWatcher && (
        <div className="fixed inset-0 z-50 bg-black/75 backdrop-blur-sm flex items-center justify-center p-4">
          <div className="bg-bg-panel border border-border-main rounded-2xl max-w-3xl w-full p-5 space-y-4 shadow-2xl">
            <div className="flex items-center justify-between">
              <div>
                <h3 className="text-sm font-black text-text-main flex items-center gap-2">
                  <Code2 className="w-4 h-4 text-amber-400" /> SQL Trigger Generator — {sqlGuideWatcher.name}
                </h3>
                <p className="text-xs text-text-muted">Copy &amp; jalankan SQL ini di database ERP tanpa mengubah kode aplikasi ERP.</p>
              </div>
              <button onClick={() => setSqlGuideWatcher(null)} className="text-text-muted hover:text-text-main cursor-pointer">
                <X className="w-4 h-4" />
              </button>
            </div>

            <div className="flex flex-wrap items-center gap-2">
              <input
                type="text"
                value={sqlGuideTable}
                onChange={e => setSqlGuideTable(e.target.value)}
                placeholder="schema.nama_tabel"
                className="bg-bg-editor border border-border-main rounded-lg px-3 py-1.5 text-xs font-mono text-cyan-300 outline-none"
              />
              <button
                onClick={() => setSqlGuideMode('pg_notify')}
                className={clsx('px-3 py-1.5 rounded-lg text-xs font-bold cursor-pointer', sqlGuideMode === 'pg_notify' ? 'bg-emerald-600 text-white' : 'bg-bg-editor text-text-muted')}
              >
                PostgreSQL pg_notify (Recommended)
              </button>
              <button
                onClick={() => setSqlGuideMode('pg_net')}
                className={clsx('px-3 py-1.5 rounded-lg text-xs font-bold cursor-pointer', sqlGuideMode === 'pg_net' ? 'bg-purple-600 text-white' : 'bg-bg-editor text-text-muted')}
              >
                PostgreSQL pg_net (HTTP API)
              </button>
              <button
                onClick={() => setSqlGuideMode('mssql')}
                className={clsx('px-3 py-1.5 rounded-lg text-xs font-bold cursor-pointer', sqlGuideMode === 'mssql' ? 'bg-sky-600 text-white' : 'bg-bg-editor text-text-muted')}
              >
                SQL Server / MySQL Guide
              </button>
            </div>

            <div className="relative">
              <pre className="bg-[#050810] border border-border-main rounded-xl p-4 text-xs font-mono text-emerald-300 overflow-x-auto max-h-80 whitespace-pre-wrap">
                {generateTriggerSql(sqlGuideWatcher)}
              </pre>
              <button
                onClick={() => handleCopy(generateTriggerSql(sqlGuideWatcher), 'sql-guide')}
                className="absolute top-3 right-3 px-3 py-1.5 rounded-lg bg-slate-800 hover:bg-slate-700 text-white text-xs font-bold flex items-center gap-1.5 cursor-pointer"
              >
                {copiedKey === 'sql-guide' ? <Check className="w-3.5 h-3.5 text-emerald-400" /> : <Copy className="w-3.5 h-3.5" />}
                {copiedKey === 'sql-guide' ? 'Copied' : 'Copy SQL'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
