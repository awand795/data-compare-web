import React, { useEffect, useState } from 'react';
import { useAppStore } from '../store/useAppStore';
import {
  Clock, Play, Plus, Trash2, Edit3, RefreshCw, CheckCircle2,
  AlertTriangle, XCircle, Loader2, Sparkles, Database, X
} from 'lucide-react';
import clsx from 'clsx';

interface OptimizeSchedule {
  id: string;
  name: string;
  connectionId?: string | null;
  databaseName: string;
  includeCdcTables: boolean;
  tablePattern?: string | null;
  cronExpression: string;
  active: boolean;
  lastRun?: string | null;
  lastStatus?: string | null;
  lastSummary?: string | null;
  nextRun?: string | null;
}

const CRON_PRESETS = [
  { label: 'Tiap 5 Bulan (03:00 WIB)', cron: '0 0 3 1 */5 *' },
  { label: 'Tiap Bulan (03:00 WIB)', cron: '0 0 3 1 * *' },
  { label: 'Tiap Minggu (03:00 WIB)', cron: '0 0 3 * * 0' },
  { label: 'Tiap Hari (03:00 WIB)', cron: '0 0 3 * * *' },
];

export const ClickHouseOptimizePanel: React.FC = () => {
  const { connections, addToast } = useAppStore();
  const chConnections = connections.filter(c => c.type?.toLowerCase() === 'clickhouse');

  const [schedules, setSchedules] = useState<OptimizeSchedule[]>([]);
  const [loading, setLoading] = useState(true);
  const [showForm, setShowForm] = useState(false);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  const [formName, setFormName] = useState('All & CDC Version Cleanup');
  const [formConnId, setFormConnId] = useState('');
  const [formDb, setFormDb] = useState('dw_erp');
  const [formIncludeCdc, setFormIncludeCdc] = useState(true);
  const [formPattern, setFormPattern] = useState('');
  const [formCron, setFormCron] = useState('0 0 3 1 */5 *');
  const [formActive, setFormActive] = useState(true);

  const fetchSchedules = async () => {
    try {
      const res = await fetch('/api/dwh/optimize-schedules');
      if (res.ok) {
        const data: OptimizeSchedule[] = await res.json();
        setSchedules(data);
      }
    } catch (e) {
      console.error('Failed to fetch ClickHouse optimize schedules', e);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchSchedules();
  }, []);

  // Poll every 5s when any schedule is RUNNING
  useEffect(() => {
    const hasRunning = schedules.some(s => s.lastStatus === 'RUNNING');
    if (!hasRunning) return;
    const timer = setInterval(fetchSchedules, 5000);
    return () => clearInterval(timer);
  }, [schedules]);

  const openCreate = () => {
    setEditingId(null);
    setFormName('All & CDC Version Cleanup');
    setFormConnId(chConnections[0]?.id || '');
    setFormDb('dw_erp');
    setFormIncludeCdc(true);
    setFormPattern('');
    setFormCron('0 0 3 1 */5 *');
    setFormActive(true);
    setShowForm(true);
  };

  const openEdit = (s: OptimizeSchedule) => {
    setEditingId(s.id);
    setFormName(s.name);
    setFormConnId(s.connectionId || '');
    setFormDb(s.databaseName || 'dw_erp');
    setFormIncludeCdc(s.includeCdcTables);
    setFormPattern(s.tablePattern || '');
    setFormCron(s.cronExpression);
    setFormActive(s.active);
    setShowForm(true);
  };

  const handleSave = async (e: React.FormEvent) => {
    e.preventDefault();
    setSaving(true);
    try {
      const payload = {
        name: formName.trim() || 'ClickHouse Version Cleanup',
        connectionId: formConnId || null,
        databaseName: formDb.trim() || 'dw_erp',
        includeCdcTables: formIncludeCdc,
        tablePattern: formPattern.trim() || null,
        cronExpression: formCron.trim(),
        active: formActive,
      };
      const url = editingId ? `/api/dwh/optimize-schedules/${editingId}` : '/api/dwh/optimize-schedules';
      const method = editingId ? 'PUT' : 'POST';
      const res = await fetch(url, {
        method,
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload),
      });
      if (!res.ok) {
        const err = await res.json().catch(() => ({}));
        throw new Error(err.error || 'Failed to save schedule');
      }
      addToast({ type: 'success', title: 'Schedule Saved', message: 'ClickHouse optimize schedule updated.' });
      setShowForm(false);
      await fetchSchedules();
    } catch (err: any) {
      addToast({ type: 'error', title: 'Invalid Schedule', message: err.message });
    } finally {
      setSaving(false);
    }
  };

  const handleToggleActive = async (s: OptimizeSchedule) => {
    try {
      const res = await fetch(`/api/dwh/optimize-schedules/${s.id}/active`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ active: !s.active }),
      });
      if (res.ok) {
        await fetchSchedules();
      }
    } catch (err: any) {
      addToast({ type: 'error', title: 'Error', message: err.message });
    }
  };

  const handleRunNow = async (s: OptimizeSchedule) => {
    try {
      const res = await fetch(`/api/dwh/optimize-schedules/${s.id}/run`, { method: 'POST' });
      const data = await res.json();
      if (!res.ok) throw new Error(data.error || 'Failed to run');
      addToast({
        type: 'info',
        title: 'Cleanup Started',
        message: data.message || 'Running OPTIMIZE TABLE FINAL in background...',
      });
      await fetchSchedules();
    } catch (err: any) {
      addToast({ type: 'error', title: 'Run Failed', message: err.message });
    }
  };

  const handleDelete = async (id: string) => {
    try {
      const res = await fetch(`/api/dwh/optimize-schedules/${id}`, { method: 'DELETE' });
      if (res.ok) {
        addToast({ type: 'success', title: 'Deleted', message: 'Schedule removed.' });
        await fetchSchedules();
      }
    } catch (err: any) {
      addToast({ type: 'error', title: 'Error', message: err.message });
    }
  };

  const renderStatusBadge = (status?: string | null) => {
    if (!status) return <span className="text-[10px] px-2 py-0.5 rounded bg-bg-editor text-text-muted">Never Run</span>;
    if (status === 'RUNNING') {
      return (
        <span className="inline-flex items-center gap-1 text-[10px] px-2 py-0.5 rounded bg-indigo-500/15 text-indigo-400 font-semibold">
          <Loader2 className="w-3 h-3 animate-spin" /> RUNNING
        </span>
      );
    }
    if (status === 'SUCCESS') {
      return (
        <span className="inline-flex items-center gap-1 text-[10px] px-2 py-0.5 rounded bg-emerald-500/15 text-emerald-400 font-semibold">
          <CheckCircle2 className="w-3 h-3" /> SUCCESS
        </span>
      );
    }
    if (status === 'PARTIAL_ERROR') {
      return (
        <span className="inline-flex items-center gap-1 text-[10px] px-2 py-0.5 rounded bg-amber-500/15 text-amber-400 font-semibold">
          <AlertTriangle className="w-3 h-3" /> PARTIAL
        </span>
      );
    }
    return (
      <span className="inline-flex items-center gap-1 text-[10px] px-2 py-0.5 rounded bg-red-500/15 text-red-400 font-semibold">
        <XCircle className="w-3 h-3" /> {status}
      </span>
    );
  };

  return (
    <div className="h-full flex flex-col overflow-hidden">
      <div className="p-3 border-b border-border-main/60 flex items-center justify-between gap-2 bg-bg-header/40">
        <div className="flex items-center gap-2">
          <Sparkles className="w-4 h-4 text-indigo-400" />
          <span className="text-xs font-bold text-text-main uppercase tracking-wider">ClickHouse Version Cleanup</span>
        </div>
        <div className="flex items-center gap-1.5">
          <button
            onClick={fetchSchedules}
            className="p-1.5 rounded-lg border border-border-input hover:bg-bg-editor text-text-muted hover:text-text-main transition-colors"
            title="Refresh"
          >
            <RefreshCw className="w-3.5 h-3.5" />
          </button>
          <button
            onClick={openCreate}
            className="px-2.5 py-1.5 rounded-lg bg-indigo-600 hover:bg-indigo-500 text-white text-[11px] font-bold flex items-center gap-1 transition-colors"
          >
            <Plus className="w-3.5 h-3.5" /> Add Schedule
          </button>
        </div>
      </div>

      <div className="px-3 py-2 bg-indigo-500/10 border-b border-indigo-500/20 text-[11px] text-text-muted leading-relaxed">
        Menjalankan <code className="text-indigo-400 font-mono">OPTIMIZE TABLE ... FINAL</code> pada tabel <code className="font-mono">ReplacingMergeTree</code> untuk menghapus versi lama &amp; menyisakan versi terbaru saja. Data dengan <code className="font-mono">is_deleted = 1</code> <strong>tetap disimpan</strong>.
      </div>

      <div className="flex-1 overflow-y-auto p-3 flex flex-col gap-3">
        {loading ? (
          <div className="flex items-center justify-center py-10 text-text-muted text-xs gap-2">
            <Loader2 className="w-4 h-4 animate-spin" /> Loading schedules...
          </div>
        ) : schedules.length === 0 ? (
          <div className="text-center py-10 text-text-muted text-xs">
            Belum ada jadwal Version Cleanup. Klik <strong>Add Schedule</strong> untuk membuat.
          </div>
        ) : (
          schedules.map(s => (
            <div
              key={s.id}
              className="p-3.5 rounded-xl border border-border-input bg-bg-editor/60 hover:border-indigo-500/40 transition-all flex flex-col gap-2.5"
            >
              <div className="flex items-start justify-between gap-2">
                <div className="min-w-0">
                  <div className="font-bold text-xs text-text-main truncate">{s.name}</div>
                  <div className="flex flex-wrap items-center gap-1.5 mt-1">
                    <span className="inline-flex items-center gap-1 text-[10px] font-mono px-1.5 py-0.5 rounded bg-bg-panel border border-border-main text-indigo-400">
                      <Clock className="w-3 h-3" /> {s.cronExpression}
                    </span>
                    <span className="inline-flex items-center gap-1 text-[10px] px-1.5 py-0.5 rounded bg-bg-panel border border-border-main text-text-muted">
                      <Database className="w-3 h-3" /> {s.databaseName}
                    </span>
                    <span className={clsx(
                      "text-[10px] px-1.5 py-0.5 rounded border font-medium",
                      s.includeCdcTables
                        ? "bg-emerald-500/10 border-emerald-500/30 text-emerald-400"
                        : "bg-bg-panel border-border-main text-text-muted"
                    )}>
                      {s.includeCdcTables ? 'All + CDC Tables' : 'Main Tables Only'}
                    </span>
                    {s.tablePattern && (
                      <span className="text-[10px] font-mono px-1.5 py-0.5 rounded bg-bg-panel border border-border-main text-amber-400">
                        {s.tablePattern}
                      </span>
                    )}
                  </div>
                </div>

                <label className="relative inline-flex items-center cursor-pointer shrink-0" title={s.active ? 'Disable Schedule' : 'Enable Schedule'}>
                  <input
                    type="checkbox"
                    checked={s.active}
                    onChange={() => handleToggleActive(s)}
                    className="sr-only peer"
                  />
                  <div className="w-8 h-4.5 bg-border-input peer-focus:outline-none rounded-full peer peer-checked:after:translate-x-full after:content-[''] after:absolute after:top-[2px] after:left-[2px] after:bg-white after:rounded-full after:h-3.5 after:w-3.5 after:transition-all peer-checked:bg-indigo-600"></div>
                </label>
              </div>

              <div className="text-[11px] text-text-muted flex flex-col gap-1 border-t border-border-main/50 pt-2">
                <div className="flex items-center justify-between gap-2">
                  <span>Next Run: <strong className="text-text-main">{s.nextRun || '-'}</strong></span>
                  {renderStatusBadge(s.lastStatus)}
                </div>
                {s.lastRun && (
                  <div className="text-[10px] opacity-80">
                    Last Run: {new Date(s.lastRun).toLocaleString('id-ID')}
                  </div>
                )}
                {s.lastSummary && (
                  <div className="text-[10px] font-mono bg-bg-panel/80 p-1.5 rounded border border-border-main/50 text-text-main break-words mt-0.5">
                    {s.lastSummary}
                  </div>
                )}
              </div>

              <div className="flex items-center justify-end gap-1.5 pt-1">
                <button
                  onClick={() => handleRunNow(s)}
                  disabled={s.lastStatus === 'RUNNING'}
                  className="px-2.5 py-1 rounded-lg bg-emerald-600/90 hover:bg-emerald-500 disabled:opacity-50 text-white text-[11px] font-bold flex items-center gap-1 transition-colors"
                >
                  <Play className="w-3 h-3 fill-current" /> Run Now
                </button>
                <button
                  onClick={() => openEdit(s)}
                  className="p-1.5 rounded-lg border border-border-input hover:bg-bg-panel text-text-muted hover:text-text-main transition-colors"
                  title="Edit Schedule"
                >
                  <Edit3 className="w-3.5 h-3.5" />
                </button>
                <button
                  onClick={() => handleDelete(s.id)}
                  className="p-1.5 rounded-lg border border-border-input hover:bg-red-500/10 text-text-muted hover:text-red-400 transition-colors"
                  title="Delete Schedule"
                >
                  <Trash2 className="w-3.5 h-3.5" />
                </button>
              </div>
            </div>
          ))
        )}
      </div>

      {showForm && (
        <div className="fixed inset-0 z-50 bg-black/60 backdrop-blur-xs flex items-center justify-center p-4">
          <div className="bg-bg-panel border border-border-input rounded-2xl shadow-2xl w-full max-w-md overflow-hidden">
            <div className="px-4 py-3 border-b border-border-main flex items-center justify-between bg-bg-header">
              <h3 className="text-sm font-bold text-text-main">
                {editingId ? 'Edit ClickHouse Optimize Schedule' : 'New ClickHouse Optimize Schedule'}
              </h3>
              <button onClick={() => setShowForm(false)} className="text-text-muted hover:text-text-main">
                <X className="w-4 h-4" />
              </button>
            </div>
            <form onSubmit={handleSave} className="p-4 flex flex-col gap-3 text-xs">
              <div>
                <label className="block font-bold text-text-muted mb-1">Schedule Name</label>
                <input
                  type="text"
                  required
                  value={formName}
                  onChange={e => setFormName(e.target.value)}
                  className="w-full px-3 py-2 rounded-lg bg-bg-editor border border-border-input text-text-main outline-none focus:border-indigo-500"
                />
              </div>

              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label className="block font-bold text-text-muted mb-1">ClickHouse Connection</label>
                  <select
                    value={formConnId}
                    onChange={e => setFormConnId(e.target.value)}
                    className="w-full px-2.5 py-2 rounded-lg bg-bg-editor border border-border-input text-text-main outline-none focus:border-indigo-500"
                  >
                    <option value="">Auto (Default ClickHouse)</option>
                    {chConnections.map(c => (
                      <option key={c.id} value={c.id}>{c.name}</option>
                    ))}
                  </select>
                </div>
                <div>
                  <label className="block font-bold text-text-muted mb-1">Database Name</label>
                  <input
                    type="text"
                    required
                    value={formDb}
                    onChange={e => setFormDb(e.target.value)}
                    className="w-full px-3 py-2 rounded-lg bg-bg-editor border border-border-input text-text-main outline-none focus:border-indigo-500"
                  />
                </div>
              </div>

              <div>
                <label className="block font-bold text-text-muted mb-1">Spring Cron Expression (WIB)</label>
                <input
                  type="text"
                  required
                  value={formCron}
                  onChange={e => setFormCron(e.target.value)}
                  placeholder="0 0 3 1 */5 *"
                  className="w-full px-3 py-2 rounded-lg bg-bg-editor border border-border-input font-mono text-text-main outline-none focus:border-indigo-500"
                />
                <div className="flex flex-wrap gap-1.5 mt-1.5">
                  {CRON_PRESETS.map(p => (
                    <button
                      type="button"
                      key={p.cron}
                      onClick={() => setFormCron(p.cron)}
                      className={clsx(
                        "px-2 py-0.5 rounded text-[10px] border transition-colors",
                        formCron === p.cron
                          ? "bg-indigo-500/20 border-indigo-500 text-indigo-300 font-semibold"
                          : "bg-bg-editor border-border-main text-text-muted hover:text-text-main"
                      )}
                    >
                      {p.label}
                    </button>
                  ))}
                </div>
              </div>

              <div>
                <label className="block font-bold text-text-muted mb-1">Table Filter Pattern (Optional)</label>
                <input
                  type="text"
                  value={formPattern}
                  onChange={e => setFormPattern(e.target.value)}
                  placeholder="Kosongkan untuk semua tabel (misal: cdc_p003_%)"
                  className="w-full px-3 py-2 rounded-lg bg-bg-editor border border-border-input font-mono text-text-main outline-none focus:border-indigo-500"
                />
              </div>

              <div className="flex items-center justify-between pt-1">
                <label className="flex items-center gap-2 cursor-pointer">
                  <input
                    type="checkbox"
                    checked={formIncludeCdc}
                    onChange={e => setFormIncludeCdc(e.target.checked)}
                    className="w-4 h-4 rounded text-indigo-600"
                  />
                  <span className="font-medium text-text-main">Include CDC Tables (cdc_*)</span>
                </label>

                <label className="flex items-center gap-2 cursor-pointer">
                  <input
                    type="checkbox"
                    checked={formActive}
                    onChange={e => setFormActive(e.target.checked)}
                    className="w-4 h-4 rounded text-indigo-600"
                  />
                  <span className="font-medium text-text-main">Active</span>
                </label>
              </div>

              <div className="flex justify-end gap-2 pt-3 border-t border-border-main">
                <button
                  type="button"
                  onClick={() => setShowForm(false)}
                  className="px-3 py-1.5 rounded-lg border border-border-input text-text-muted hover:text-text-main"
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  disabled={saving}
                  className="px-4 py-1.5 rounded-lg bg-indigo-600 hover:bg-indigo-500 text-white font-bold flex items-center gap-1.5"
                >
                  {saving && <Loader2 className="w-3.5 h-3.5 animate-spin" />}
                  Save Schedule
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
};
