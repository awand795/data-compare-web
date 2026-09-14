import { useState, useEffect, useCallback } from 'react';
import axios from 'axios';
import {
  ShieldCheck, Plus, Trash2, RefreshCw, Edit2, Save, X,
  Copy, CheckCircle, AlertCircle, KeyRound, Clock, Check
} from 'lucide-react';
import clsx from 'clsx';

export interface AuthApp {
  id: string;
  name: string;
  description: string;
  allowedRoles: string;
  accessTokenTtlMinutes: number;
  refreshTokenTtlDays: number;
  isActive: boolean;
  createdAt?: string;
  updatedAt?: string;
}

interface AuthAppFormData {
  id: string;
  name: string;
  description: string;
  allowedRoles: string;
  accessTokenTtlMinutes: number;
  refreshTokenTtlDays: number;
  isActive: boolean;
}

const DEFAULT_FORM: AuthAppFormData = {
  id: '',
  name: '',
  description: '',
  allowedRoles: 'CUSTOMER,SECURITY,SA,FOREMAN,MEKANIK,WAREHOUSE,ADMIN_INVOICE,ADMIN',
  accessTokenTtlMinutes: 15,
  refreshTokenTtlDays: 30,
  isActive: true,
};

const COMMON_ROLE_PRESETS = [
  'CUSTOMER', 'ADMIN', 'SECURITY', 'SA', 'FOREMAN', 'MEKANIK', 'WAREHOUSE', 'ADMIN_INVOICE', 'CASHIER', 'SUPERVISOR'
];

function toSlug(str: string): string {
  return str.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '');
}

export function AuthAppsView() {
  const [apps, setApps] = useState<AuthApp[]>([]);
  const [loading, setLoading] = useState(false);
  const [showForm, setShowForm] = useState(false);
  const [isEditing, setIsEditing] = useState(false);
  const [formData, setFormData] = useState<AuthAppFormData>(DEFAULT_FORM);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState<string | null>(null);
  const [copiedId, setCopiedId] = useState<string | null>(null);
  const [deletingId, setDeletingId] = useState<string | null>(null);

  const fetchApps = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const res = await axios.get('/api/auth-apps');
      setApps(Array.isArray(res.data) ? res.data : []);
    } catch (err: any) {
      setError(err?.response?.data?.error || err.message || 'Gagal memuat daftar Auth Apps');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchApps();
  }, [fetchApps]);

  const handleOpenCreate = () => {
    setFormData(DEFAULT_FORM);
    setIsEditing(false);
    setShowForm(true);
    setError(null);
  };

  const handleOpenEdit = (app: AuthApp) => {
    setFormData({
      id: app.id,
      name: app.name,
      description: app.description || '',
      allowedRoles: app.allowedRoles || '',
      accessTokenTtlMinutes: app.accessTokenTtlMinutes || 15,
      refreshTokenTtlDays: app.refreshTokenTtlDays || 30,
      isActive: app.isActive ?? true,
    });
    setIsEditing(true);
    setShowForm(true);
    setError(null);
  };

  const handleNameChange = (name: string) => {
    if (!isEditing) {
      setFormData(prev => ({ ...prev, name, id: toSlug(name) }));
    } else {
      setFormData(prev => ({ ...prev, name }));
    }
  };

  const toggleRole = (role: string) => {
    const currentRoles = formData.allowedRoles
      .split(',')
      .map(r => r.trim().toUpperCase())
      .filter(Boolean);

    let updated: string[];
    if (currentRoles.includes(role)) {
      updated = currentRoles.filter(r => r !== role);
    } else {
      updated = [...currentRoles, role];
    }
    setFormData(prev => ({ ...prev, allowedRoles: updated.join(',') }));
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!formData.name.trim()) {
      setError('Nama Auth App wajib diisi');
      return;
    }
    if (!formData.id.trim()) {
      setError('App ID (Slug) wajib diisi');
      return;
    }

    setSaving(true);
    setError(null);
    try {
      await axios.post('/api/auth-apps', formData);
      setSuccess(`Auth App '${formData.name}' berhasil disimpan!`);
      setTimeout(() => setSuccess(null), 3000);
      setShowForm(false);
      fetchApps();
    } catch (err: any) {
      setError(err?.response?.data?.error || err.message || 'Gagal menyimpan Auth App');
    } finally {
      setSaving(false);
    }
  };

  const handleDelete = async (id: string) => {
    if (!window.confirm(`Apakah Anda yakin ingin menghapus Auth App '${id}'? Seluruh user dan sesi yang terhubung akan terhapus.`)) {
      return;
    }
    setDeletingId(id);
    try {
      await axios.delete(`/api/auth-apps/${id}`);
      setSuccess(`Auth App '${id}' berhasil dihapus`);
      setTimeout(() => setSuccess(null), 3000);
      fetchApps();
    } catch (err: any) {
      setError(err?.response?.data?.error || err.message || 'Gagal menghapus Auth App');
    } finally {
      setDeletingId(null);
    }
  };

  const copyToClipboard = (text: string) => {
    navigator.clipboard.writeText(text);
    setCopiedId(text);
    setTimeout(() => setCopiedId(null), 2000);
  };

  return (
    <div className="h-full flex flex-col p-4 md:p-6 overflow-hidden bg-bg-main">
      {/* Header */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 mb-6 shrink-0">
        <div>
          <div className="flex items-center gap-2 text-text-main font-bold text-lg md:text-xl">
            <div className="p-2 bg-indigo-500/10 text-indigo-500 rounded-xl border border-indigo-500/20">
              <ShieldCheck className="w-5 h-5" />
            </div>
            <span>Auth-as-a-Service (Multi-App Manager)</span>
          </div>
          <p className="text-text-muted text-xs md:text-sm mt-1">
            Registry identitas &amp; auth terisolasi per aplikasi (Web Fleet, POS, Internal Apps) dengan JWT + Refresh Token Rotation.
          </p>
        </div>

        <div className="flex items-center gap-2 self-start sm:self-auto">
          <button
            onClick={fetchApps}
            disabled={loading}
            className="flex items-center gap-1.5 px-3 py-1.5 text-xs font-semibold text-text-muted hover:text-text-main bg-bg-panel hover:bg-bg-editor rounded-lg border border-border-main transition-all cursor-pointer"
          >
            <RefreshCw className={clsx("w-3.5 h-3.5", loading && "animate-spin")} />
            <span>Refresh</span>
          </button>
          <button
            onClick={handleOpenCreate}
            className="flex items-center gap-1.5 px-3.5 py-1.5 text-xs font-semibold text-white bg-indigo-600 hover:bg-indigo-500 rounded-lg shadow-sm transition-all cursor-pointer"
          >
            <Plus className="w-4 h-4" />
            <span>Auth App Baru</span>
          </button>
        </div>
      </div>

      {/* Alerts */}
      {error && (
        <div className="mb-4 p-3 rounded-xl bg-red-500/10 border border-red-500/20 text-red-400 text-xs flex items-center gap-2">
          <AlertCircle className="w-4 h-4 shrink-0" />
          <span>{error}</span>
        </div>
      )}
      {success && (
        <div className="mb-4 p-3 rounded-xl bg-emerald-500/10 border border-emerald-500/20 text-emerald-400 text-xs flex items-center gap-2">
          <CheckCircle className="w-4 h-4 shrink-0" />
          <span>{success}</span>
        </div>
      )}

      {/* Apps Table */}
      <div className="flex-1 bg-bg-panel rounded-2xl border border-border-main overflow-hidden flex flex-col shadow-sm">
        <div className="overflow-x-auto flex-1">
          <table className="w-full text-left border-collapse text-xs">
            <thead>
              <tr className="bg-bg-editor/50 border-b border-border-main text-text-muted font-bold uppercase tracking-wider text-[11px]">
                <th className="py-3 px-4">Nama Aplikasi</th>
                <th className="py-3 px-4">App ID (Slug)</th>
                <th className="py-3 px-4">Allowed Roles</th>
                <th className="py-3 px-4">Access TTL</th>
                <th className="py-3 px-4">Refresh TTL</th>
                <th className="py-3 px-4">Status</th>
                <th className="py-3 px-4 text-right">Aksi</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-border-main/50">
              {loading && apps.length === 0 ? (
                <tr>
                  <td colSpan={7} className="text-center py-12 text-text-muted">
                    <RefreshCw className="w-6 h-6 animate-spin mx-auto mb-2 text-indigo-400" />
                    Memuat daftar Auth Apps...
                  </td>
                </tr>
              ) : apps.length === 0 ? (
                <tr>
                  <td colSpan={7} className="text-center py-12 text-text-muted">
                    <KeyRound className="w-8 h-8 mx-auto mb-2 opacity-30 text-text-muted" />
                    Belum ada Auth App. Klik tombol &ldquo;Auth App Baru&rdquo; untuk membuat.
                  </td>
                </tr>
              ) : (
                apps.map(app => {
                  const roles = (app.allowedRoles || '').split(',').map(r => r.trim()).filter(Boolean);
                  return (
                    <tr key={app.id} className="hover:bg-bg-editor/40 transition-colors">
                      <td className="py-3 px-4">
                        <div className="font-bold text-text-main text-sm">{app.name}</div>
                        {app.description && (
                          <div className="text-text-muted text-[11px] truncate max-w-xs">{app.description}</div>
                        )}
                      </td>
                      <td className="py-3 px-4">
                        <button
                          onClick={() => copyToClipboard(app.id)}
                          title="Klik untuk copy App ID"
                          className="inline-flex items-center gap-1.5 px-2 py-1 bg-bg-editor rounded-md border border-border-main font-mono text-[11px] text-text-main hover:border-indigo-500/40 transition-colors"
                        >
                          <span>{app.id}</span>
                          {copiedId === app.id ? (
                            <Check className="w-3 h-3 text-emerald-400" />
                          ) : (
                            <Copy className="w-3 h-3 text-text-muted" />
                          )}
                        </button>
                      </td>
                      <td className="py-3 px-4">
                        <div className="flex flex-wrap gap-1 max-w-sm">
                          {roles.map(r => (
                            <span
                              key={r}
                              className={clsx(
                                "px-1.5 py-0.5 rounded text-[10px] font-mono font-bold",
                                r === 'ADMIN' ? "bg-red-500/10 text-red-400 border border-red-500/20" :
                                r === 'CUSTOMER' ? "bg-emerald-500/10 text-emerald-400 border border-emerald-500/20" :
                                "bg-indigo-500/10 text-indigo-400 border border-indigo-500/20"
                              )}
                            >
                              {r}
                            </span>
                          ))}
                        </div>
                      </td>
                      <td className="py-3 px-4 font-mono text-text-muted">
                        <span className="inline-flex items-center gap-1">
                          <Clock className="w-3 h-3 text-text-muted" />
                          {app.accessTokenTtlMinutes || 15} mnt
                        </span>
                      </td>
                      <td className="py-3 px-4 font-mono text-text-muted">
                        {app.refreshTokenTtlDays || 30} hari
                      </td>
                      <td className="py-3 px-4">
                        <span
                          className={clsx(
                            "inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-[10px] font-bold",
                            app.isActive
                              ? "bg-emerald-500/10 text-emerald-400 border border-emerald-500/20"
                              : "bg-gray-500/10 text-gray-400 border border-gray-500/20"
                          )}
                        >
                          <span className={clsx("w-1.5 h-1.5 rounded-full", app.isActive ? "bg-emerald-400" : "bg-gray-400")} />
                          {app.isActive ? 'Aktif' : 'Nonaktif'}
                        </span>
                      </td>
                      <td className="py-3 px-4 text-right">
                        <div className="flex items-center justify-end gap-1.5">
                          <button
                            onClick={() => handleOpenEdit(app)}
                            className="p-1.5 text-text-muted hover:text-indigo-400 hover:bg-indigo-500/10 rounded-lg transition-colors"
                            title="Edit Auth App"
                          >
                            <Edit2 className="w-3.5 h-3.5" />
                          </button>
                          <button
                            onClick={() => handleDelete(app.id)}
                            disabled={deletingId === app.id}
                            className="p-1.5 text-text-muted hover:text-red-400 hover:bg-red-500/10 rounded-lg transition-colors"
                            title="Hapus Auth App"
                          >
                            <Trash2 className="w-3.5 h-3.5" />
                          </button>
                        </div>
                      </td>
                    </tr>
                  );
                })
              )}
            </tbody>
          </table>
        </div>
      </div>

      {/* Modal Create / Edit */}
      {showForm && (
        <div className="fixed inset-0 z-50 bg-black/60 backdrop-blur-sm flex items-center justify-center p-4">
          <div className="bg-bg-panel border border-border-main rounded-2xl w-full max-w-lg shadow-2xl overflow-hidden animate-in fade-in zoom-in-95 duration-150">
            <div className="flex items-center justify-between p-4 border-b border-border-main">
              <div className="flex items-center gap-2 font-bold text-text-main text-sm">
                <ShieldCheck className="w-4 h-4 text-indigo-400" />
                <span>{isEditing ? 'Edit Auth App' : 'Buat Auth App Baru'}</span>
              </div>
              <button
                onClick={() => setShowForm(false)}
                className="p-1 text-text-muted hover:text-text-main rounded-lg hover:bg-bg-editor"
              >
                <X className="w-4 h-4" />
              </button>
            </div>

            <form onSubmit={handleSubmit} className="p-4 space-y-4">
              <div>
                <label className="block text-xs font-bold text-text-muted mb-1">
                  Nama Aplikasi <span className="text-red-400">*</span>
                </label>
                <input
                  type="text"
                  required
                  placeholder="Contoh: Bengkel KIM3 Web Fleet"
                  value={formData.name}
                  onChange={e => handleNameChange(e.target.value)}
                  className="w-full bg-bg-editor border border-border-main rounded-xl px-3 py-2 text-xs text-text-main outline-none focus:border-indigo-500 transition-colors"
                />
              </div>

              <div>
                <label className="block text-xs font-bold text-text-muted mb-1">
                  App ID (Slug URL) <span className="text-red-400">*</span>
                </label>
                <input
                  type="text"
                  required
                  disabled={isEditing}
                  placeholder="bengkel-kim3"
                  value={formData.id}
                  onChange={e => setFormData(prev => ({ ...prev, id: toSlug(e.target.value) }))}
                  className="w-full bg-bg-editor border border-border-main rounded-xl px-3 py-2 text-xs font-mono text-indigo-400 outline-none focus:border-indigo-500 transition-colors disabled:opacity-60"
                />
                <p className="text-[10px] text-text-muted mt-1">
                  Dipakai di URL: <code className="text-indigo-400 font-mono">/api/auth/{formData.id || '{appId}'}/login</code>
                </p>
              </div>

              <div>
                <label className="block text-xs font-bold text-text-muted mb-1">Deskripsi</label>
                <textarea
                  rows={2}
                  placeholder="Keterangan singkat tentang frontend / consumer app..."
                  value={formData.description}
                  onChange={e => setFormData(prev => ({ ...prev, description: e.target.value }))}
                  className="w-full bg-bg-editor border border-border-main rounded-xl px-3 py-2 text-xs text-text-main outline-none focus:border-indigo-500 transition-colors resize-none"
                />
              </div>

              <div>
                <label className="block text-xs font-bold text-text-muted mb-1.5">
                  Allowed Roles (Daftar Role yang Diizinkan)
                </label>
                <div className="flex flex-wrap gap-1.5 mb-2">
                  {COMMON_ROLE_PRESETS.map(role => {
                    const active = formData.allowedRoles
                      .split(',')
                      .map(r => r.trim().toUpperCase())
                      .includes(role);
                    return (
                      <button
                        key={role}
                        type="button"
                        onClick={() => toggleRole(role)}
                        className={clsx(
                          "px-2 py-1 rounded-lg text-[10px] font-mono font-bold transition-all cursor-pointer",
                          active
                            ? "bg-indigo-600 text-white shadow-sm"
                            : "bg-bg-editor text-text-muted hover:text-text-main border border-border-main"
                        )}
                      >
                        {active ? `✓ ${role}` : `+ ${role}`}
                      </button>
                    );
                  })}
                </div>
                <input
                  type="text"
                  placeholder="Atau ketik comma-separated (CUSTOMER,SA,ADMIN,...)"
                  value={formData.allowedRoles}
                  onChange={e => setFormData(prev => ({ ...prev, allowedRoles: e.target.value.toUpperCase() }))}
                  className="w-full bg-bg-editor border border-border-main rounded-xl px-3 py-2 text-xs font-mono text-text-main outline-none focus:border-indigo-500 transition-colors"
                />
              </div>

              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label className="block text-xs font-bold text-text-muted mb-1">
                    Access Token TTL (Menit)
                  </label>
                  <input
                    type="number"
                    min={1}
                    max={1440}
                    value={formData.accessTokenTtlMinutes}
                    onChange={e => setFormData(prev => ({ ...prev, accessTokenTtlMinutes: parseInt(e.target.value) || 15 }))}
                    className="w-full bg-bg-editor border border-border-main rounded-xl px-3 py-2 text-xs font-mono text-text-main outline-none focus:border-indigo-500"
                  />
                  <p className="text-[10px] text-text-muted mt-0.5">Rekomendasi: 15 menit</p>
                </div>
                <div>
                  <label className="block text-xs font-bold text-text-muted mb-1">
                    Refresh Token TTL (Hari)
                  </label>
                  <input
                    type="number"
                    min={1}
                    max={365}
                    value={formData.refreshTokenTtlDays}
                    onChange={e => setFormData(prev => ({ ...prev, refreshTokenTtlDays: parseInt(e.target.value) || 30 }))}
                    className="w-full bg-bg-editor border border-border-main rounded-xl px-3 py-2 text-xs font-mono text-text-main outline-none focus:border-indigo-500"
                  />
                  <p className="text-[10px] text-text-muted mt-0.5">Rekomendasi: 30 hari</p>
                </div>
              </div>

              <div className="flex items-center justify-between pt-2">
                <label className="flex items-center gap-2 cursor-pointer text-xs font-semibold text-text-main">
                  <input
                    type="checkbox"
                    checked={formData.isActive}
                    onChange={e => setFormData(prev => ({ ...prev, isActive: e.target.checked }))}
                    className="rounded border-border-main text-indigo-600 focus:ring-indigo-500"
                  />
                  <span>Status Aktif</span>
                </label>
              </div>

              <div className="flex items-center justify-end gap-2 pt-3 border-t border-border-main">
                <button
                  type="button"
                  onClick={() => setShowForm(false)}
                  className="px-3.5 py-1.5 text-xs font-semibold text-text-muted hover:text-text-main bg-bg-editor rounded-xl border border-border-main"
                >
                  Batal
                </button>
                <button
                  type="submit"
                  disabled={saving}
                  className="flex items-center gap-1.5 px-4 py-1.5 text-xs font-semibold text-white bg-indigo-600 hover:bg-indigo-500 rounded-xl shadow-sm transition-all cursor-pointer"
                >
                  <Save className="w-3.5 h-3.5" />
                  <span>{saving ? 'Menyimpan...' : 'Simpan Auth App'}</span>
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
}
