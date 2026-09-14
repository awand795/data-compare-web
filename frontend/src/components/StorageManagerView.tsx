import { useState, useEffect, useCallback, useRef } from 'react';
import axios from 'axios';
import {
  HardDrive, Plus, Trash2, RefreshCw, Edit2, Save, X,
  Upload, Copy, CheckCircle, AlertCircle, Folder, FileText,
  Sliders, ShieldCheck, Zap, ExternalLink
} from 'lucide-react';
import clsx from 'clsx';

interface StorageBucket {
  name: string;
  description: string;
  allowed_extensions: string;
  max_size_mb: number;
  auto_compress: boolean;
  max_width: number;
  max_height: number;
  image_quality: number;
  is_public: boolean;
  custom_error_message: string;
  file_count?: number;
  total_bytes?: number;
  total_size_formatted?: string;
  created_at?: string;
}

interface StoredFile {
  filename: string;
  url: string;
  size: number;
  size_formatted: string;
  mime_type: string;
  modified_at: string;
}

interface BucketFormData {
  name: string;
  description: string;
  allowed_extensions: string;
  max_size_mb: number;
  auto_compress: boolean;
  max_width: number;
  max_height: number;
  image_quality: number;
  custom_error_message: string;
}

const DEFAULT_FORM: BucketFormData = {
  name: '',
  description: '',
  allowed_extensions: 'jpg,jpeg,png,webp',
  max_size_mb: 10,
  auto_compress: true,
  max_width: 1920,
  max_height: 1920,
  image_quality: 0.82,
  custom_error_message: '',
};

export function StorageManagerView() {
  const [buckets, setBuckets] = useState<StorageBucket[]>([]);
  const [loading, setLoading] = useState(false);
  const [selectedBucket, setSelectedBucket] = useState<string>('foto_kendaraan');
  const [files, setFiles] = useState<StoredFile[]>([]);
  const [filesLoading, setFilesLoading] = useState(false);
  const [showBucketModal, setShowBucketModal] = useState(false);
  const [editingBucket, setEditingBucket] = useState<string | null>(null);
  const [form, setForm] = useState<BucketFormData>(DEFAULT_FORM);
  const [saving, setSaving] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [uploadResult, setUploadResult] = useState<any>(null);
  const [toast, setToast] = useState<{ msg: string; type: 'ok' | 'err' } | null>(null);
  const [copiedUrl, setCopiedUrl] = useState<string | null>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);

  const showToast = (msg: string, type: 'ok' | 'err' = 'ok') => {
    setToast({ msg, type });
    setTimeout(() => setToast(null), 3500);
  };

  const loadBuckets = useCallback(async () => {
    setLoading(true);
    try {
      const { data } = await axios.get('/api/storage/buckets');
      setBuckets(data);
      if (data.length > 0 && !data.find((b: any) => b.name === selectedBucket)) {
        setSelectedBucket(data[0].name);
      }
    } catch (e: any) {
      showToast(e?.response?.data?.error || 'Gagal memuat bucket', 'err');
    } finally {
      setLoading(false);
    }
  }, [selectedBucket]);

  const loadFiles = useCallback(async (bName: string) => {
    if (!bName) return;
    setFilesLoading(true);
    try {
      const { data } = await axios.get(`/api/storage/buckets/${bName}/files`);
      setFiles(data);
    } catch (e: any) {
      setFiles([]);
    } finally {
      setFilesLoading(false);
    }
  }, []);

  useEffect(() => {
    loadBuckets();
  }, [loadBuckets]);

  useEffect(() => {
    if (selectedBucket) {
      loadFiles(selectedBucket);
    }
  }, [selectedBucket, loadFiles]);

  const openCreateBucket = () => {
    setForm(DEFAULT_FORM);
    setEditingBucket(null);
    setShowBucketModal(true);
  };

  const openEditBucket = (b: StorageBucket) => {
    setForm({
      name: b.name,
      description: b.description || '',
      allowed_extensions: b.allowed_extensions || 'jpg,jpeg,png,webp',
      max_size_mb: b.max_size_mb ?? 10,
      auto_compress: b.auto_compress ?? true,
      max_width: b.max_width ?? 1920,
      max_height: b.max_height ?? 1920,
      image_quality: b.image_quality ?? 0.82,
      custom_error_message: b.custom_error_message || '',
    });
    setEditingBucket(b.name);
    setShowBucketModal(true);
  };

  const handleSaveBucket = async () => {
    if (!form.name.trim()) {
      showToast('Nama bucket wajib diisi', 'err');
      return;
    }
    setSaving(true);
    try {
      await axios.post('/api/storage/buckets', form);
      showToast('Konfigurasi bucket berhasil disimpan!');
      setShowBucketModal(false);
      loadBuckets();
    } catch (e: any) {
      showToast(e?.response?.data?.error || 'Gagal menyimpan bucket', 'err');
    } finally {
      setSaving(false);
    }
  };

  const handleDeleteBucket = async (name: string) => {
    if (!confirm(`Hapus bucket "${name}"? File di dalam folder tetap aman.`)) return;
    try {
      await axios.delete(`/api/storage/buckets/${name}`);
      showToast('Bucket berhasil dihapus');
      loadBuckets();
    } catch (e: any) {
      showToast(e?.response?.data?.error || 'Gagal menghapus bucket', 'err');
    }
  };

  const handleFileUpload = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;

    setUploading(true);
    setUploadResult(null);

    const formData = new FormData();
    formData.append('file', file);
    formData.append('bucket', selectedBucket);

    try {
      const { data } = await axios.post('/api/storage/upload', formData, {
        headers: { 'Content-Type': 'multipart/form-data' },
      });
      setUploadResult(data);
      showToast('File berhasil di-upload dan divalidasi!');
      loadFiles(selectedBucket);
      loadBuckets();
    } catch (err: any) {
      const errMsg = err?.response?.data?.error || 'Gagal mengupload file';
      showToast(errMsg, 'err');
    } finally {
      setUploading(false);
      if (fileInputRef.current) fileInputRef.current.value = '';
    }
  };

  const handleDeleteFile = async (filename: string) => {
    if (!confirm(`Hapus file "${filename}"?`)) return;
    try {
      await axios.delete(`/api/storage/files/${selectedBucket}/${filename}`);
      showToast('File berhasil dihapus');
      loadFiles(selectedBucket);
      loadBuckets();
    } catch (e: any) {
      showToast(e?.response?.data?.error || 'Gagal menghapus file', 'err');
    }
  };

  const copyUrl = (url: string) => {
    const fullUrl = window.location.origin + url;
    navigator.clipboard.writeText(fullUrl);
    setCopiedUrl(url);
    setTimeout(() => setCopiedUrl(null), 2000);
  };

  const activeBucketObj = buckets.find(b => b.name === selectedBucket);

  return (
    <div className="h-full flex flex-col bg-bg-main overflow-hidden">
      {/* Header */}
      <div className="border-b border-border-main bg-bg-panel px-4 py-3 flex items-center justify-between shrink-0">
        <div className="flex items-center gap-2.5">
          <div className="w-8 h-8 rounded-lg bg-gradient-to-br from-emerald-500 to-teal-600 flex items-center justify-center shadow-lg shadow-emerald-500/20">
            <HardDrive className="w-4 h-4 text-white" />
          </div>
          <div>
            <h2 className="font-semibold text-text-main text-sm">Storage &amp; Bucket Manager</h2>
            <p className="text-[11px] text-text-muted">Penyimpanan fisik, validasi ukuran &amp; format, kompresi otomatis</p>
          </div>
        </div>
        <div className="flex items-center gap-2">
          <button
            onClick={loadBuckets}
            className="p-1.5 text-text-muted hover:text-text-main rounded hover:bg-bg-hover transition-colors"
            title="Refresh"
          >
            <RefreshCw className={clsx('w-4 h-4', loading && 'animate-spin')} />
          </button>
          <button
            onClick={openCreateBucket}
            className="flex items-center gap-1.5 px-3 py-1.5 bg-emerald-600 hover:bg-emerald-500 text-white text-xs font-medium rounded-lg transition-colors shadow-sm"
          >
            <Plus className="w-3.5 h-3.5" /> Buat Bucket
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

      {/* Main Grid */}
      <div className="flex-1 flex overflow-hidden">
        {/* Left Pane: Bucket List */}
        <div className="w-80 border-r border-border-main bg-bg-panel flex flex-col shrink-0 overflow-hidden">
          <div className="p-3 border-b border-border-main bg-bg-editor/50 flex items-center justify-between">
            <span className="text-xs font-semibold text-text-main uppercase tracking-wider">Storage Buckets</span>
            <span className="text-[11px] font-mono text-text-muted">{buckets.length} bucket</span>
          </div>
          <div className="flex-1 overflow-auto p-2 space-y-2">
            {buckets.map(b => {
              const isSelected = b.name === selectedBucket;
              return (
                <div
                  key={b.name}
                  onClick={() => setSelectedBucket(b.name)}
                  className={clsx(
                    'p-3 rounded-xl border text-left cursor-pointer transition-all',
                    isSelected
                      ? 'bg-emerald-500/10 border-emerald-500/40 shadow-sm'
                      : 'bg-bg-panel border-border-main hover:border-emerald-500/30 hover:bg-bg-hover'
                  )}
                >
                  <div className="flex items-center justify-between mb-1">
                    <div className="flex items-center gap-2">
                      <Folder className={clsx('w-4 h-4', isSelected ? 'text-emerald-400' : 'text-text-muted')} />
                      <span className="font-mono text-xs font-semibold text-text-main">{b.name}</span>
                    </div>
                    <div className="flex items-center gap-1">
                      <button
                        onClick={(e) => { e.stopPropagation(); openEditBucket(b); }}
                        className="p-1 text-text-muted hover:text-text-main rounded hover:bg-bg-hover"
                        title="Edit Aturan"
                      >
                        <Edit2 className="w-3 h-3" />
                      </button>
                      <button
                        onClick={(e) => { e.stopPropagation(); handleDeleteBucket(b.name); }}
                        className="p-1 text-text-muted hover:text-red-400 rounded hover:bg-bg-hover"
                        title="Hapus Bucket"
                      >
                        <Trash2 className="w-3 h-3" />
                      </button>
                    </div>
                  </div>
                  <p className="text-[11px] text-text-muted line-clamp-1 mb-2">{b.description || 'Tidak ada deskripsi'}</p>
                  <div className="flex flex-wrap gap-1.5 text-[10px]">
                    <span className="px-1.5 py-0.5 rounded bg-bg-editor border border-border-main font-mono text-text-muted">
                      Max: {b.max_size_mb} MB
                    </span>
                    <span className="px-1.5 py-0.5 rounded bg-bg-editor border border-border-main font-mono text-text-muted">
                      {b.allowed_extensions === '*' ? 'Semua format' : b.allowed_extensions}
                    </span>
                    {b.auto_compress && (
                      <span className="px-1.5 py-0.5 rounded bg-emerald-500/20 text-emerald-300 font-medium flex items-center gap-1">
                        <Zap className="w-2.5 h-2.5" /> Auto-Compress
                      </span>
                    )}
                  </div>
                  <div className="mt-2 pt-2 border-t border-border-main/50 flex justify-between text-[10px] text-text-muted">
                    <span>{b.file_count ?? 0} file tersimpan</span>
                    <span className="font-mono">{b.total_size_formatted || '0 B'}</span>
                  </div>
                </div>
              );
            })}
          </div>
        </div>

        {/* Right Pane: Bucket Detail & File Explorer */}
        <div className="flex-1 flex flex-col overflow-hidden bg-bg-main">
          {activeBucketObj ? (
            <>
              {/* Bucket Rules Info Banner */}
              <div className="p-4 border-b border-border-main bg-bg-panel shrink-0">
                <div className="flex items-center justify-between mb-3">
                  <div>
                    <div className="flex items-center gap-2">
                      <h3 className="font-mono text-base font-bold text-text-main">{activeBucketObj.name}</h3>
                      <span className="text-xs px-2 py-0.5 rounded-full bg-emerald-500/15 text-emerald-400 border border-emerald-500/30">
                        Aktif
                      </span>
                    </div>
                    <p className="text-xs text-text-muted mt-0.5">{activeBucketObj.description}</p>
                  </div>
                  <div className="flex items-center gap-2">
                    <input
                      type="file"
                      ref={fileInputRef}
                      onChange={handleFileUpload}
                      className="hidden"
                    />
                    <button
                      onClick={() => fileInputRef.current?.click()}
                      disabled={uploading}
                      className="flex items-center gap-1.5 px-3 py-1.5 bg-emerald-600 hover:bg-emerald-500 text-white text-xs font-medium rounded-lg transition-colors shadow-sm disabled:opacity-50"
                    >
                      <Upload className={clsx('w-3.5 h-3.5', uploading && 'animate-bounce')} />
                      {uploading ? 'Mengupload...' : 'Test Upload File'}
                    </button>
                    <button
                      onClick={() => openEditBucket(activeBucketObj)}
                      className="flex items-center gap-1 px-3 py-1.5 bg-bg-editor border border-border-main hover:bg-bg-hover text-text-main text-xs font-medium rounded-lg transition-colors"
                    >
                      <Sliders className="w-3.5 h-3.5" /> Konfigurasi
                    </button>
                  </div>
                </div>

                {/* Rules Pill Summary */}
                <div className="grid grid-cols-2 sm:grid-cols-4 gap-2 text-xs">
                  <div className="p-2 rounded-lg bg-bg-editor border border-border-main">
                    <span className="text-[10px] text-text-muted block">Format Diizinkan</span>
                    <span className="font-mono font-medium text-emerald-400">
                      {activeBucketObj.allowed_extensions === '*' ? 'Semua (*)' : activeBucketObj.allowed_extensions}
                    </span>
                  </div>
                  <div className="p-2 rounded-lg bg-bg-editor border border-border-main">
                    <span className="text-[10px] text-text-muted block">Batas Ukuran</span>
                    <span className="font-mono font-medium text-text-main">{activeBucketObj.max_size_mb} MB</span>
                  </div>
                  <div className="p-2 rounded-lg bg-bg-editor border border-border-main">
                    <span className="text-[10px] text-text-muted block">Kompresi Gambar</span>
                    <span className="font-medium text-text-main flex items-center gap-1">
                      {activeBucketObj.auto_compress ? (
                        <>
                          <Zap className="w-3 h-3 text-emerald-400" />
                          <span>Maks {activeBucketObj.max_width}px ({Math.round((activeBucketObj.image_quality ?? 0.82) * 100)}%)</span>
                        </>
                      ) : (
                        <span className="text-text-muted">Nonaktif</span>
                      )}
                    </span>
                  </div>
                  <div className="p-2 rounded-lg bg-bg-editor border border-border-main">
                    <span className="text-[10px] text-text-muted block">Keamanan Magic Bytes</span>
                    <span className="font-medium text-text-main flex items-center gap-1 text-teal-400">
                      <ShieldCheck className="w-3 h-3" /> Apache Tika
                    </span>
                  </div>
                </div>

                {/* Upload Result Box */}
                {uploadResult && (
                  <div className="mt-3 p-3 rounded-xl bg-emerald-500/10 border border-emerald-500/30 flex items-center justify-between">
                    <div className="flex items-center gap-2 min-w-0">
                      <CheckCircle className="w-4 h-4 text-emerald-400 shrink-0" />
                      <div className="text-xs min-w-0">
                        <span className="font-medium text-emerald-300">Upload Berhasil: </span>
                        <span className="font-mono text-text-main truncate">{uploadResult.originalName}</span>
                        {uploadResult.compressed && (
                          <span className="ml-2 text-[10px] px-1.5 py-0.5 rounded bg-emerald-500/20 text-emerald-300">
                            Dioptimasi {uploadResult.saved_percentage}
                          </span>
                        )}
                        <span className="ml-2 text-[10px] text-text-muted font-mono">({uploadResult.size_formatted})</span>
                      </div>
                    </div>
                    <button
                      onClick={() => copyUrl(uploadResult.url)}
                      className="px-2.5 py-1 rounded bg-emerald-600/30 hover:bg-emerald-600/50 text-emerald-200 text-xs font-mono flex items-center gap-1 shrink-0 transition-colors"
                    >
                      <Copy className="w-3 h-3" /> Salin URL
                    </button>
                  </div>
                )}
              </div>

              {/* File Explorer Table */}
              <div className="flex-1 overflow-auto p-4">
                <div className="flex items-center justify-between mb-3">
                  <h4 className="text-xs font-semibold text-text-main uppercase tracking-wider">
                    File Tersimpan ({files.length})
                  </h4>
                  <span className="text-[11px] text-text-muted">
                    Endpoint: <code className="text-emerald-400">POST /api/storage/upload?bucket={activeBucketObj.name}</code>
                  </span>
                </div>

                {filesLoading ? (
                  <div className="flex items-center justify-center h-32 text-text-muted">
                    <RefreshCw className="w-5 h-5 animate-spin mr-2" /> Memuat file...
                  </div>
                ) : files.length === 0 ? (
                  <div className="flex flex-col items-center justify-center h-48 border-2 border-dashed border-border-main rounded-xl text-text-muted gap-2">
                    <Upload className="w-8 h-8 opacity-30" />
                    <p className="text-xs">Belum ada file di bucket ini</p>
                    <button
                      onClick={() => fileInputRef.current?.click()}
                      className="px-3 py-1 bg-emerald-600/20 hover:bg-emerald-600/40 text-emerald-300 text-xs rounded-lg transition-colors font-medium"
                    >
                      Upload File Pertama
                    </button>
                  </div>
                ) : (
                  <div className="grid grid-cols-1 sm:grid-cols-2 md:grid-cols-3 lg:grid-cols-4 gap-3">
                    {files.map(f => {
                      const isImage = f.mime_type.startsWith('image/');
                      return (
                        <div
                          key={f.filename}
                          className="bg-bg-panel border border-border-main rounded-xl overflow-hidden hover:border-emerald-500/40 transition-all flex flex-col group"
                        >
                          {/* Thumbnail / Icon preview */}
                          <div className="h-32 bg-bg-editor flex items-center justify-center overflow-hidden relative">
                            {isImage ? (
                              <img
                                src={f.url}
                                alt={f.filename}
                                className="w-full h-full object-cover group-hover:scale-105 transition-transform duration-200"
                                loading="lazy"
                              />
                            ) : (
                              <FileText className="w-12 h-12 text-text-muted opacity-40" />
                            )}
                            <a
                              href={f.url}
                              target="_blank"
                              rel="noreferrer"
                              className="absolute top-2 right-2 p-1 rounded-lg bg-black/60 text-white opacity-0 group-hover:opacity-100 transition-opacity hover:bg-black"
                              title="Buka File"
                            >
                              <ExternalLink className="w-3.5 h-3.5" />
                            </a>
                          </div>

                          {/* File Details */}
                          <div className="p-3 flex-1 flex flex-col justify-between">
                            <div>
                              <p className="font-mono text-xs text-text-main truncate font-medium" title={f.filename}>
                                {f.filename}
                              </p>
                              <div className="flex items-center justify-between text-[10px] text-text-muted mt-1">
                                <span>{f.size_formatted}</span>
                                <span>{new Date(f.modified_at).toLocaleDateString('id-ID')}</span>
                              </div>
                            </div>

                            <div className="mt-2 pt-2 border-t border-border-main flex items-center justify-between">
                              <button
                                onClick={() => copyUrl(f.url)}
                                className="text-[11px] text-text-muted hover:text-emerald-400 flex items-center gap-1 transition-colors"
                              >
                                {copiedUrl === f.url ? (
                                  <CheckCircle className="w-3 h-3 text-emerald-400" />
                                ) : (
                                  <Copy className="w-3 h-3" />
                                )}
                                <span>{copiedUrl === f.url ? 'Disalin' : 'Salin URL'}</span>
                              </button>
                              <button
                                onClick={() => handleDeleteFile(f.filename)}
                                className="p-1 text-text-muted hover:text-red-400 transition-colors"
                                title="Hapus File"
                              >
                                <Trash2 className="w-3.5 h-3.5" />
                              </button>
                            </div>
                          </div>
                        </div>
                      );
                    })}
                  </div>
                )}
              </div>
            </>
          ) : (
            <div className="flex-1 flex items-center justify-center text-text-muted">
              Pilih bucket di sebelah kiri
            </div>
          )}
        </div>
      </div>

      {/* Modal Buat / Edit Bucket */}
      {showBucketModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-4">
          <div className="bg-bg-panel border border-border-main rounded-2xl w-full max-w-lg shadow-2xl overflow-hidden animate-in fade-in zoom-in-95">
            <div className="flex items-center justify-between px-5 py-4 border-b border-border-main bg-bg-editor">
              <div className="flex items-center gap-2">
                <HardDrive className="w-4 h-4 text-emerald-400" />
                <h3 className="font-semibold text-sm text-text-main">
                  {editingBucket ? `Edit Aturan Bucket: ${editingBucket}` : 'Buat Storage Bucket Baru'}
                </h3>
              </div>
              <button
                onClick={() => setShowBucketModal(false)}
                className="text-text-muted hover:text-text-main p-1 rounded hover:bg-bg-hover"
              >
                <X className="w-4 h-4" />
              </button>
            </div>

            <div className="p-5 space-y-4 max-h-[80vh] overflow-y-auto">
              {/* Nama Bucket */}
              <div>
                <label className="block text-xs font-medium text-text-muted mb-1">Nama Bucket *</label>
                <input
                  value={form.name}
                  onChange={e => setForm(f => ({ ...f, name: e.target.value.toLowerCase().replace(/\s/g, '_') }))}
                  disabled={!!editingBucket}
                  placeholder="foto_kendaraan, stnk_armada, sparepart_foto..."
                  className="w-full bg-bg-editor border border-border-main rounded-lg px-3 py-2 text-xs font-mono text-text-main focus:outline-none focus:border-emerald-500 disabled:opacity-50"
                />
                <p className="text-[10px] text-text-muted mt-1">Nama unik folder penyimpanan (huruf kecil, underscore).</p>
              </div>

              {/* Deskripsi */}
              <div>
                <label className="block text-xs font-medium text-text-muted mb-1">Deskripsi Bucket</label>
                <input
                  value={form.description}
                  onChange={e => setForm(f => ({ ...f, description: e.target.value }))}
                  placeholder="Foto fisik kendaraan saat pertama kali masuk gate bengkel"
                  className="w-full bg-bg-editor border border-border-main rounded-lg px-3 py-2 text-xs text-text-main focus:outline-none focus:border-emerald-500"
                />
              </div>

              {/* Format File / Allowed Extensions */}
              <div>
                <label className="block text-xs font-medium text-text-muted mb-1">Format File yang Diizinkan</label>
                <input
                  value={form.allowed_extensions}
                  onChange={e => setForm(f => ({ ...f, allowed_extensions: e.target.value.toLowerCase() }))}
                  placeholder="jpg,jpeg,png,webp atau *"
                  className="w-full bg-bg-editor border border-border-main rounded-lg px-3 py-2 text-xs font-mono text-text-main focus:outline-none focus:border-emerald-500"
                />
                {/* Presets */}
                <div className="flex gap-1.5 mt-1.5">
                  <button
                    type="button"
                    onClick={() => setForm(f => ({ ...f, allowed_extensions: 'jpg,jpeg,png,webp' }))}
                    className="px-2 py-0.5 rounded bg-bg-editor border border-border-main text-[10px] text-text-muted hover:text-emerald-400 hover:border-emerald-500"
                  >
                    Foto (JPG, PNG, WEBP)
                  </button>
                  <button
                    type="button"
                    onClick={() => setForm(f => ({ ...f, allowed_extensions: 'pdf,doc,docx,xls,xlsx' }))}
                    className="px-2 py-0.5 rounded bg-bg-editor border border-border-main text-[10px] text-text-muted hover:text-emerald-400 hover:border-emerald-500"
                  >
                    Dokumen (PDF, Word, Excel)
                  </button>
                  <button
                    type="button"
                    onClick={() => setForm(f => ({ ...f, allowed_extensions: '*' }))}
                    className="px-2 py-0.5 rounded bg-bg-editor border border-border-main text-[10px] text-text-muted hover:text-emerald-400 hover:border-emerald-500"
                  >
                    Semua Format (*)
                  </button>
                </div>
              </div>

              {/* Max Size in MB */}
              <div>
                <div className="flex justify-between items-center mb-1">
                  <label className="text-xs font-medium text-text-muted">Batas Maksimal Ukuran File</label>
                  <span className="font-mono text-xs font-bold text-emerald-400">{form.max_size_mb} MB</span>
                </div>
                <input
                  type="range"
                  min={1}
                  max={50}
                  value={form.max_size_mb}
                  onChange={e => setForm(f => ({ ...f, max_size_mb: +e.target.value }))}
                  className="w-full accent-emerald-500"
                />
                <div className="flex justify-between text-[10px] text-text-muted">
                  <span>1 MB</span>
                  <span>10 MB (Standar Foto)</span>
                  <span>50 MB (Max)</span>
                </div>
              </div>

              {/* Auto Compression Toggle & Config */}
              <div className="p-3 bg-bg-editor rounded-xl border border-border-main space-y-3">
                <div className="flex items-center justify-between">
                  <div>
                    <label className="text-xs font-medium text-text-main flex items-center gap-1.5 cursor-pointer">
                      <Zap className="w-3.5 h-3.5 text-emerald-400" />
                      Kompresi Gambar Otomatis (Thumbnailator)
                    </label>
                    <p className="text-[10px] text-text-muted">Menghemat storage hingga 85% untuk foto HP beresolusi tinggi.</p>
                  </div>
                  <input
                    type="checkbox"
                    checked={form.auto_compress}
                    onChange={e => setForm(f => ({ ...f, auto_compress: e.target.checked }))}
                    className="w-4 h-4 accent-emerald-500 rounded cursor-pointer"
                  />
                </div>

                {form.auto_compress && (
                  <div className="grid grid-cols-2 gap-3 pt-2 border-t border-border-main">
                    <div>
                      <label className="block text-[10px] text-text-muted mb-1">Maksimal Dimensi (px)</label>
                      <select
                        value={form.max_width}
                        onChange={e => setForm(f => ({ ...f, max_width: +e.target.value, max_height: +e.target.value }))}
                        className="w-full bg-bg-panel border border-border-main rounded px-2 py-1.5 text-xs text-text-main focus:outline-none focus:border-emerald-500"
                      >
                        <option value={1280}>1280 x 1280 (Hemat)</option>
                        <option value={1920}>1920 x 1920 (Full HD, Standar)</option>
                        <option value={2560}>2560 x 2560 (2K)</option>
                      </select>
                    </div>
                    <div>
                      <label className="block text-[10px] text-text-muted mb-1">Kualitas JPEG / WEBP</label>
                      <select
                        value={form.image_quality}
                        onChange={e => setForm(f => ({ ...f, image_quality: +e.target.value }))}
                        className="w-full bg-bg-panel border border-border-main rounded px-2 py-1.5 text-xs text-text-main focus:outline-none focus:border-emerald-500"
                      >
                        <option value={0.70}>70% (Sangat Ringan)</option>
                        <option value={0.82}>82% (Optimal Jelas &amp; Ringan)</option>
                        <option value={0.90}>90% (Kualitas Tinggi)</option>
                      </select>
                    </div>
                  </div>
                )}
              </div>

              {/* Custom Error Message */}
              <div>
                <label className="block text-xs font-medium text-text-muted mb-1">Pesan Error Kustom (Opsional)</label>
                <input
                  value={form.custom_error_message}
                  onChange={e => setForm(f => ({ ...f, custom_error_message: e.target.value }))}
                  placeholder="Foto kendaraan wajib format JPG/PNG dan maksimal 5MB"
                  className="w-full bg-bg-editor border border-border-main rounded-lg px-3 py-2 text-xs text-text-main focus:outline-none focus:border-emerald-500"
                />
                <p className="text-[10px] text-text-muted mt-1">Ditampilkan ke pengguna frontend jika melanggar ukuran atau format.</p>
              </div>
            </div>

            <div className="flex justify-end gap-2 px-5 py-3.5 border-t border-border-main bg-bg-editor">
              <button
                type="button"
                onClick={() => setShowBucketModal(false)}
                className="px-3.5 py-1.5 text-xs text-text-muted hover:text-text-main rounded-lg border border-border-main hover:bg-bg-hover transition-colors"
              >
                Batal
              </button>
              <button
                type="button"
                onClick={handleSaveBucket}
                disabled={saving}
                className="flex items-center gap-1.5 px-4 py-1.5 bg-emerald-600 hover:bg-emerald-500 text-white text-xs font-medium rounded-lg transition-colors shadow-sm disabled:opacity-50"
              >
                <Save className="w-3.5 h-3.5" />
                {saving ? 'Menyimpan...' : 'Simpan Bucket'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
