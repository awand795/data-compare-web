import { useState, useEffect, useCallback } from 'react';
import axios from 'axios';
import {
  Hash, Plus, Trash2, RefreshCw, Edit2, Save, X, RotateCcw,
  Copy, CheckCircle, AlertCircle
} from 'lucide-react';
import clsx from 'clsx';

interface Sequence {
  id: number;
  seq_key: string;
  description: string;
  prefix: string;
  suffix: string;
  current_value: number;
  pad_length: number;
  reset_type: 'DAILY' | 'MONTHLY' | 'YEARLY' | 'NEVER';
  date_format: string;
  date_separator: string;
  last_reset_date: string;
  created_at: string;
  updated_at: string;
}

interface SequenceFormData {
  seq_key: string;
  description: string;
  prefix: string;
  suffix: string;
  pad_length: number;
  reset_type: 'DAILY' | 'MONTHLY' | 'YEARLY' | 'NEVER';
  date_format: string;
  date_separator: string;
}

const DEFAULT_FORM: SequenceFormData = {
  seq_key: '',
  description: '',
  prefix: '',
  suffix: '',
  pad_length: 3,
  reset_type: 'DAILY',
  date_format: 'yyMMdd',
  date_separator: '-',
};

const DATE_FORMAT_PRESETS = [
  { label: 'None', value: '' },
  { label: 'YYMMDD (250503)', value: 'yyMMdd' },
  { label: 'YYYYMMDD (20250503)', value: 'yyyyMMdd' },
  { label: 'YYYY (2025)', value: 'yyyy' },
  { label: 'YYYYMM (202505)', value: 'yyyyMM' },
  { label: 'MM-YYYY (05-2025)', value: 'MM-yyyy' },
];

const RESET_LABELS: Record<string, string> = {
  DAILY: 'Reset Harian',
  MONTHLY: 'Reset Bulanan',
  YEARLY: 'Reset Tahunan',
  NEVER: 'Tidak Reset',
};

const RESET_COLORS: Record<string, string> = {
  DAILY: 'text-blue-500 bg-blue-500/10',
  MONTHLY: 'text-purple-500 bg-purple-500/10',
  YEARLY: 'text-amber-500 bg-amber-500/10',
  NEVER: 'text-gray-500 bg-gray-500/10',
};

function buildPreview(form: SequenceFormData, nextVal: number): string {
  const padded = String(nextVal).padStart(form.pad_length, '0');
  const sep = form.date_separator || '-';
  let datePart = '';
  if (form.date_format) {
    const now = new Date();
    const y4 = now.getFullYear();
    const y2 = String(y4).slice(2);
    const mo = String(now.getMonth() + 1).padStart(2, '0');
    const dd = String(now.getDate()).padStart(2, '0');
    datePart = form.date_format
      .replace('yyyyMMdd', `${y4}${mo}${dd}`)
      .replace('yyMMdd', `${y2}${mo}${dd}`)
      .replace('yyyy', String(y4))
      .replace('yyyyMM', `${y4}${mo}`)
      .replace('MM-yyyy', `${mo}-${y4}`);
    datePart = sep + datePart;
  }
  let result = '';
  if (form.prefix && datePart) result = form.prefix + datePart + sep + padded;
  else if (form.prefix) result = form.prefix + sep + padded;
  else result = padded;
  if (form.suffix) result += sep + form.suffix;
  return result;
}

export function SequenceView() {
  const [sequences, setSequences] = useState<Sequence[]>([]);
  const [loading, setLoading] = useState(false);
  const [showForm, setShowForm] = useState(false);
  const [editKey, setEditKey] = useState<string | null>(null);
  const [form, setForm] = useState<SequenceFormData>(DEFAULT_FORM);
  const [saving, setSaving] = useState(false);
  const [toast, setToast] = useState<{ msg: string; type: 'ok' | 'err' } | null>(null);
  const [previews, setPreviews] = useState<Record<string, string>>({});
  const [testingKey, setTestingKey] = useState<string | null>(null);
  const [resetModal, setResetModal] = useState<{ key: string; value: number } | null>(null);
  const [copiedKey, setCopiedKey] = useState<string | null>(null);

  const showToast = (msg: string, type: 'ok' | 'err' = 'ok') => {
    setToast({ msg, type });
    setTimeout(() => setToast(null), 3000);
  };

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const { data } = await axios.get('/api/sequence/list');
      setSequences(data);
    } catch (e: any) {
      showToast(e?.response?.data?.error || 'Gagal memuat data', 'err');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { load(); }, [load]);

  const openCreate = () => {
    setForm(DEFAULT_FORM);
    setEditKey(null);
    setShowForm(true);
  };

  const openEdit = (seq: Sequence) => {
    setForm({
      seq_key: seq.seq_key,
      description: seq.description || '',
      prefix: seq.prefix || '',
      suffix: seq.suffix || '',
      pad_length: seq.pad_length,
      reset_type: seq.reset_type,
      date_format: seq.date_format || '',
      date_separator: seq.date_separator || '-',
    });
    setEditKey(seq.seq_key);
    setShowForm(true);
  };

  const handleSave = async () => {
    if (!form.seq_key.trim()) { showToast('Sequence Key wajib diisi', 'err'); return; }
    setSaving(true);
    try {
      await axios.post('/api/sequence/create', form);
      showToast('Sequence berhasil disimpan!');
      setShowForm(false);
      load();
    } catch (e: any) {
      showToast(e?.response?.data?.error || 'Gagal menyimpan', 'err');
    } finally {
      setSaving(false);
    }
  };

  const handleDelete = async (key: string) => {
    if (!confirm(`Hapus sequence "${key}"?`)) return;
    try {
      await axios.delete(`/api/sequence/${key}`);
      showToast('Sequence dihapus');
      load();
    } catch (e: any) {
      showToast(e?.response?.data?.error || 'Gagal menghapus', 'err');
    }
  };

  const handleTest = async (key: string) => {
    setTestingKey(key);
    try {
      const { data } = await axios.post(`/api/sequence/next?key=${key}`);
      setPreviews(p => ({ ...p, [key]: data.formatted }));
      showToast(`Next: ${data.formatted}`);
      load();
    } catch (e: any) {
      showToast(e?.response?.data?.error || 'Gagal generate', 'err');
    } finally {
      setTestingKey(null);
    }
  };

  const handleReset = async () => {
    if (!resetModal) return;
    try {
      await axios.post(`/api/sequence/reset/${resetModal.key}?value=${resetModal.value}`);
      showToast(`Sequence "${resetModal.key}" direset ke ${resetModal.value}`);
      setResetModal(null);
      load();
    } catch (e: any) {
      showToast(e?.response?.data?.error || 'Gagal reset', 'err');
    }
  };

  const copyEndpoint = (key: string) => {
    const endpoint = `POST /api/sequence/next?key=${key}`;
    navigator.clipboard.writeText(endpoint);
    setCopiedKey(key);
    setTimeout(() => setCopiedKey(null), 2000);
  };

  const preview = buildPreview(form, (sequences.find(s => s.seq_key === editKey)?.current_value || 0) + 1);

  return (
    <div className="h-full flex flex-col bg-bg-main overflow-hidden">
      {/* Header */}
      <div className="border-b border-border-main bg-bg-panel px-4 py-3 flex items-center justify-between shrink-0">
        <div className="flex items-center gap-2">
          <div className="w-8 h-8 rounded-lg bg-gradient-to-br from-violet-500 to-purple-600 flex items-center justify-center shadow-lg">
            <Hash className="w-4 h-4 text-white" />
          </div>
          <div>
            <h2 className="font-semibold text-text-main text-sm">Sequence Manager</h2>
            <p className="text-[11px] text-text-muted">Auto-number generator — universal untuk semua project</p>
          </div>
        </div>
        <div className="flex items-center gap-2">
          <button onClick={load} className="p-1.5 text-text-muted hover:text-text-main rounded transition-colors hover:bg-bg-hover" title="Refresh">
            <RefreshCw className={clsx('w-4 h-4', loading && 'animate-spin')} />
          </button>
          <button
            onClick={openCreate}
            className="flex items-center gap-1.5 px-3 py-1.5 bg-violet-600 hover:bg-violet-500 text-white text-xs font-medium rounded-lg transition-colors"
          >
            <Plus className="w-3.5 h-3.5" /> Buat Sequence
          </button>
        </div>
      </div>

      {/* Toast */}
      {toast && (
        <div className={clsx(
          'fixed top-4 right-4 z-50 flex items-center gap-2 px-4 py-2.5 rounded-lg shadow-lg text-sm font-medium animate-in fade-in slide-in-from-top-2',
          toast.type === 'ok' ? 'bg-green-600 text-white' : 'bg-red-600 text-white'
        )}>
          {toast.type === 'ok' ? <CheckCircle className="w-4 h-4" /> : <AlertCircle className="w-4 h-4" />}
          {toast.msg}
        </div>
      )}

      <div className="flex-1 overflow-auto p-4">
        {/* INFO CARD */}
        <div className="mb-4 p-3 bg-violet-500/10 border border-violet-500/20 rounded-lg">
          <p className="text-xs text-violet-300 font-medium mb-1">Cara Penggunaan di API Builder</p>
          <div className="font-mono text-[11px] text-violet-200 bg-black/20 rounded px-2 py-1.5 flex items-center gap-2">
            <span>POST /api/sequence/next?key=&#123;seq_key&#125;</span>
            <span className="text-violet-400">→ returns formatted number (e.g. MK-250903-001)</span>
          </div>
          <p className="text-[11px] text-violet-300/70 mt-1">Bisa dipanggil sebelum INSERT di endpoint API Builder sebagai pre-check logic, atau langsung dari frontend.</p>
        </div>

        {/* Form */}
        {showForm && (
          <div className="mb-4 bg-bg-panel border border-border-main rounded-xl overflow-hidden">
            <div className="flex items-center justify-between px-4 py-3 border-b border-border-main bg-bg-editor">
              <h3 className="font-semibold text-sm text-text-main">
                {editKey ? `Edit Sequence: ${editKey}` : 'Buat Sequence Baru'}
              </h3>
              <button onClick={() => setShowForm(false)} className="text-text-muted hover:text-text-main p-1 rounded hover:bg-bg-hover transition-colors">
                <X className="w-4 h-4" />
              </button>
            </div>
            <div className="p-4 grid grid-cols-2 gap-4">
              {/* Seq Key */}
              <div className="col-span-2 sm:col-span-1">
                <label className="block text-xs text-text-muted mb-1 font-medium">Sequence Key *</label>
                <input
                  value={form.seq_key}
                  onChange={e => setForm(f => ({ ...f, seq_key: e.target.value.replace(/\s/g, '-').toLowerCase() }))}
                  disabled={!!editKey}
                  placeholder="memo-keluar, invoice-po, tiket-support..."
                  className="w-full bg-bg-editor border border-border-main rounded-lg px-3 py-2 text-sm font-mono text-text-main focus:outline-none focus:border-violet-500 disabled:opacity-50"
                />
                <p className="text-[10px] text-text-muted mt-1">Huruf kecil + tanda hubung. Ini adalah ID unik sequence.</p>
              </div>
              {/* Description */}
              <div className="col-span-2 sm:col-span-1">
                <label className="block text-xs text-text-muted mb-1 font-medium">Deskripsi</label>
                <input
                  value={form.description}
                  onChange={e => setForm(f => ({ ...f, description: e.target.value }))}
                  placeholder="Nomor Memo Keluar Bengkel KIM3..."
                  className="w-full bg-bg-editor border border-border-main rounded-lg px-3 py-2 text-sm text-text-main focus:outline-none focus:border-violet-500"
                />
              </div>
              {/* Prefix */}
              <div>
                <label className="block text-xs text-text-muted mb-1 font-medium">Prefix</label>
                <input
                  value={form.prefix}
                  onChange={e => setForm(f => ({ ...f, prefix: e.target.value.toUpperCase() }))}
                  placeholder="MK / INV / PO / TKT"
                  className="w-full bg-bg-editor border border-border-main rounded-lg px-3 py-2 text-sm font-mono text-text-main focus:outline-none focus:border-violet-500"
                />
              </div>
              {/* Suffix */}
              <div>
                <label className="block text-xs text-text-muted mb-1 font-medium">Suffix (opsional)</label>
                <input
                  value={form.suffix}
                  onChange={e => setForm(f => ({ ...f, suffix: e.target.value.toUpperCase() }))}
                  placeholder="KIM3 / MDN..."
                  className="w-full bg-bg-editor border border-border-main rounded-lg px-3 py-2 text-sm font-mono text-text-main focus:outline-none focus:border-violet-500"
                />
              </div>
              {/* Date Format */}
              <div>
                <label className="block text-xs text-text-muted mb-1 font-medium">Format Tanggal</label>
                <select
                  value={form.date_format}
                  onChange={e => setForm(f => ({ ...f, date_format: e.target.value }))}
                  className="w-full bg-bg-editor border border-border-main rounded-lg px-3 py-2 text-sm text-text-main focus:outline-none focus:border-violet-500"
                >
                  {DATE_FORMAT_PRESETS.map(p => (
                    <option key={p.value} value={p.value}>{p.label}</option>
                  ))}
                </select>
              </div>
              {/* Separator */}
              <div>
                <label className="block text-xs text-text-muted mb-1 font-medium">Separator</label>
                <select
                  value={form.date_separator}
                  onChange={e => setForm(f => ({ ...f, date_separator: e.target.value }))}
                  className="w-full bg-bg-editor border border-border-main rounded-lg px-3 py-2 text-sm font-mono text-text-main focus:outline-none focus:border-violet-500"
                >
                  <option value="-">Tanda hubung ( - )</option>
                  <option value="/">Garis miring ( / )</option>
                  <option value=".">Titik ( . )</option>
                  <option value="_">Underscore ( _ )</option>
                </select>
              </div>
              {/* Pad Length */}
              <div>
                <label className="block text-xs text-text-muted mb-1 font-medium">Panjang Angka (padding)</label>
                <div className="flex items-center gap-2">
                  <input
                    type="range" min={1} max={8} value={form.pad_length}
                    onChange={e => setForm(f => ({ ...f, pad_length: +e.target.value }))}
                    className="flex-1 accent-violet-500"
                  />
                  <span className="text-sm font-mono text-violet-400 w-8 text-center font-bold">{form.pad_length}</span>
                </div>
                <p className="text-[10px] text-text-muted mt-1">
                  Contoh pad=3: 001, 002, ... 999
                </p>
              </div>
              {/* Reset Type */}
              <div>
                <label className="block text-xs text-text-muted mb-1 font-medium">Aturan Reset</label>
                <div className="grid grid-cols-2 gap-1.5">
                  {(['DAILY', 'MONTHLY', 'YEARLY', 'NEVER'] as const).map(rt => (
                    <button
                      key={rt}
                      onClick={() => setForm(f => ({ ...f, reset_type: rt }))}
                      className={clsx(
                        'px-2 py-1.5 rounded-lg text-xs font-medium border transition-all',
                        form.reset_type === rt
                          ? 'border-violet-500 bg-violet-500/20 text-violet-300'
                          : 'border-border-main bg-bg-editor text-text-muted hover:border-violet-500/50'
                      )}
                    >
                      {RESET_LABELS[rt]}
                    </button>
                  ))}
                </div>
              </div>
              {/* Preview */}
              <div className="col-span-2 bg-bg-editor border border-violet-500/30 rounded-xl p-3">
                <p className="text-[11px] text-text-muted mb-1 font-medium">Preview Nomor Berikutnya:</p>
                <div className="font-mono text-2xl font-bold text-violet-400 tracking-wider">{preview}</div>
                <p className="text-[10px] text-text-muted mt-1">Format: {form.prefix}{form.date_format ? form.date_separator + '(' + form.date_format + ')' : ''}{form.date_format ? form.date_separator : form.prefix ? form.date_separator : ''}{'0'.repeat(form.pad_length - 1)}1{form.suffix ? form.date_separator + form.suffix : ''}</p>
              </div>
            </div>
            <div className="flex justify-end gap-2 px-4 py-3 border-t border-border-main bg-bg-editor">
              <button onClick={() => setShowForm(false)} className="px-3 py-1.5 text-xs text-text-muted hover:text-text-main rounded-lg border border-border-main hover:bg-bg-hover transition-colors">
                Batal
              </button>
              <button onClick={handleSave} disabled={saving} className="flex items-center gap-1.5 px-4 py-1.5 bg-violet-600 hover:bg-violet-500 text-white text-xs font-medium rounded-lg transition-colors disabled:opacity-50">
                <Save className="w-3.5 h-3.5" />
                {saving ? 'Menyimpan...' : 'Simpan Sequence'}
              </button>
            </div>
          </div>
        )}

        {/* Sequence List */}
        {loading ? (
          <div className="flex items-center justify-center h-32 text-text-muted">
            <RefreshCw className="w-5 h-5 animate-spin mr-2" /> Memuat...
          </div>
        ) : sequences.length === 0 ? (
          <div className="flex flex-col items-center justify-center h-48 text-text-muted gap-3">
            <Hash className="w-10 h-10 opacity-30" />
            <p className="text-sm">Belum ada sequence. Buat yang pertama!</p>
            <button onClick={openCreate} className="px-4 py-2 bg-violet-600 hover:bg-violet-500 text-white text-xs rounded-lg font-medium transition-colors">
              Buat Sequence
            </button>
          </div>
        ) : (
          <div className="grid gap-3">
            {sequences.map(seq => (
              <div key={seq.seq_key} className="bg-bg-panel border border-border-main rounded-xl overflow-hidden hover:border-violet-500/30 transition-all group">
                <div className="flex items-center gap-3 px-4 py-3">
                  <div className="w-9 h-9 rounded-lg bg-violet-500/15 flex items-center justify-center shrink-0">
                    <Hash className="w-4 h-4 text-violet-400" />
                  </div>
                  <div className="flex-1 min-w-0">
                    <div className="flex items-center gap-2 flex-wrap">
                      <span className="font-mono font-semibold text-sm text-text-main">{seq.seq_key}</span>
                      <span className={clsx('text-[10px] px-1.5 py-0.5 rounded font-medium', RESET_COLORS[seq.reset_type])}>
                        {RESET_LABELS[seq.reset_type]}
                      </span>
                    </div>
                    <p className="text-[11px] text-text-muted mt-0.5 truncate">{seq.description || 'Tidak ada deskripsi'}</p>
                  </div>
                  <div className="flex items-center gap-1 shrink-0">
                    {/* Current value */}
                    <div className="text-right mr-2 hidden sm:block">
                      <div className="font-mono text-lg font-bold text-violet-400">{seq.current_value}</div>
                      <div className="text-[10px] text-text-muted">nilai saat ini</div>
                    </div>
                    {/* Copy endpoint */}
                    <button
                      onClick={() => copyEndpoint(seq.seq_key)}
                      className="p-1.5 text-text-muted hover:text-text-main rounded transition-colors hover:bg-bg-hover"
                      title="Copy endpoint"
                    >
                      {copiedKey === seq.seq_key ? <CheckCircle className="w-4 h-4 text-green-400" /> : <Copy className="w-4 h-4" />}
                    </button>
                    {/* Test / Generate */}
                    <button
                      onClick={() => handleTest(seq.seq_key)}
                      disabled={testingKey === seq.seq_key}
                      className="flex items-center gap-1 px-2.5 py-1 bg-violet-600/20 hover:bg-violet-600/40 text-violet-300 text-xs rounded-lg transition-colors disabled:opacity-50"
                      title="Generate next number (akan increment)"
                    >
                      <RefreshCw className={clsx('w-3 h-3', testingKey === seq.seq_key && 'animate-spin')} />
                      Test
                    </button>
                    {/* Reset */}
                    <button
                      onClick={() => setResetModal({ key: seq.seq_key, value: 0 })}
                      className="p-1.5 text-text-muted hover:text-amber-400 rounded transition-colors hover:bg-bg-hover"
                      title="Reset counter"
                    >
                      <RotateCcw className="w-4 h-4" />
                    </button>
                    {/* Edit */}
                    <button
                      onClick={() => openEdit(seq)}
                      className="p-1.5 text-text-muted hover:text-text-main rounded transition-colors hover:bg-bg-hover"
                      title="Edit"
                    >
                      <Edit2 className="w-4 h-4" />
                    </button>
                    {/* Delete */}
                    <button
                      onClick={() => handleDelete(seq.seq_key)}
                      className="p-1.5 text-text-muted hover:text-red-400 rounded transition-colors hover:bg-bg-hover"
                      title="Hapus"
                    >
                      <Trash2 className="w-4 h-4" />
                    </button>
                  </div>
                </div>
                {/* Preview test result */}
                {previews[seq.seq_key] && (
                  <div className="border-t border-border-main bg-bg-editor px-4 py-2 flex items-center gap-2">
                    <CheckCircle className="w-3.5 h-3.5 text-green-400" />
                    <span className="text-[11px] text-text-muted">Last generated:</span>
                    <span className="font-mono text-sm font-bold text-green-400">{previews[seq.seq_key]}</span>
                  </div>
                )}
                {/* Format info */}
                <div className="border-t border-border-main bg-bg-editor/50 px-4 py-1.5 flex items-center gap-4 text-[10px] text-text-muted">
                  <span>Prefix: <code className="text-violet-300">{seq.prefix || '(kosong)'}</code></span>
                  <span>Format tanggal: <code className="text-violet-300">{seq.date_format || 'tidak ada'}</code></span>
                  <span>Padding: <code className="text-violet-300">{seq.pad_length} digit</code></span>
                  <span>Separator: <code className="text-violet-300">"{seq.date_separator}"</code></span>
                </div>
              </div>
            ))}
          </div>
        )}
      </div>

      {/* Reset Modal */}
      {resetModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60">
          <div className="bg-bg-panel border border-border-main rounded-xl w-80 p-5 shadow-xl">
            <h3 className="font-semibold text-text-main mb-1">Reset Sequence</h3>
            <p className="text-xs text-text-muted mb-4">Reset counter "{resetModal.key}" ke nilai tertentu.</p>
            <label className="block text-xs text-text-muted mb-1">Reset ke nilai</label>
            <input
              type="number" min={0}
              value={resetModal.value}
              onChange={e => setResetModal(m => m ? { ...m, value: +e.target.value } : null)}
              className="w-full bg-bg-editor border border-border-main rounded-lg px-3 py-2 text-sm text-text-main mb-4 focus:outline-none focus:border-amber-500"
            />
            <div className="flex gap-2 justify-end">
              <button onClick={() => setResetModal(null)} className="px-3 py-1.5 text-xs text-text-muted border border-border-main rounded-lg hover:bg-bg-hover transition-colors">
                Batal
              </button>
              <button onClick={handleReset} className="px-4 py-1.5 bg-amber-600 hover:bg-amber-500 text-white text-xs rounded-lg font-medium transition-colors">
                Reset
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
