// @ts-nocheck
import React, { useState, useEffect, useCallback } from 'react';
import axios from 'axios';
import { useAppStore } from '../store/useAppStore';
import {
  UploadCloud, FileSpreadsheet, FileText, Database, Trash2, Eye,
  RefreshCw, Search, CheckCircle2, AlertCircle, Loader2, Table,
  X, ChevronLeft, ChevronRight, FileCode, Layers, Info
} from 'lucide-react';
import clsx from 'clsx';

type UploadedTable = {
  table_name: string;
  original_filename: string;
  file_type: string;
  description: string;
  row_count: number;
  created_at: string;
};

type TablePreviewData = {
  tableName: string;
  columns: string[];
  rows: Record<string, any>[];
  totalRows: number;
  page: number;
  pageSize: number;
  totalPages: number;
};

export const FileUploadView: React.FC = () => {
  const { addToast, showAlert } = useAppStore();

  const [tables, setTables] = useState<UploadedTable[]>([]);
  const [loadingTables, setLoadingTables] = useState<boolean>(false);
  const [searchTerm, setSearchTerm] = useState<string>('');

  // Upload Form state
  const [selectedFile, setSelectedFile] = useState<File | null>(null);
  const [tableName, setTableName] = useState<string>('');
  const [description, setDescription] = useState<string>('');
  const [isUploading, setIsUploading] = useState<boolean>(false);

  // Drag & drop highlight state
  const [isDragOver, setIsDragOver] = useState<boolean>(false);

  // Data Preview Modal state
  const [previewModalOpen, setPreviewModalOpen] = useState<boolean>(false);
  const [previewData, setPreviewData] = useState<TablePreviewData | null>(null);
  const [loadingPreview, setLoadingPreview] = useState<boolean>(false);
  const [activePreviewTable, setActivePreviewTable] = useState<string | null>(null);
  const [previewPage, setPreviewPage] = useState<number>(1);
  const [previewPageSize] = useState<number>(50);

  // Helper to format date string to YYYYMMDD
  const getFormattedDateCode = () => {
    const d = new Date();
    const yyyy = d.getFullYear();
    const mm = String(d.getMonth() + 1).padStart(2, '0');
    const dd = String(d.getDate()).padStart(2, '0');
    return `${yyyy}${mm}${dd}`;
  };

  // Helper to sanitize filename to table name
  const generateDefaultTableName = (fileName: string) => {
    const nameWithoutExt = fileName.substring(0, fileName.lastIndexOf('.')) || fileName;
    const cleanName = nameWithoutExt
      .toLowerCase()
      .replace(/[^a-z0-9_]/g, '_')
      .replace(/_+/g, '_')
      .replace(/^_|_$/g, '');
    
    let base = cleanName;
    if (!base || base.match(/^[0-9]/)) {
      base = `tbl_${base}`;
    }
    return `${base}_${getFormattedDateCode()}`;
  };

  // Helper to format current timestamp for description
  const generateDefaultDescription = (fileName: string) => {
    const now = new Date();
    const dateStr = now.toLocaleDateString('id-ID', {
      year: 'numeric',
      month: 'short',
      day: 'numeric',
      hour: '2-digit',
      minute: '2-digit'
    });
    return `Uploaded from ${fileName} on ${dateStr}`;
  };

  // Fetch list of uploaded tables in sch_excel
  const fetchUploadedTables = useCallback(async () => {
    setLoadingTables(true);
    try {
      const res = await axios.get('/api/file-upload/tables');
      if (Array.isArray(res.data)) {
        setTables(res.data);
      }
    } catch (err: any) {
      console.error('Failed to fetch uploaded tables:', err);
      addToast?.({
        type: 'error',
        title: 'Gagal Mengambil Data',
        message: err.response?.data?.error || 'Tidak dapat memuat daftar tabel'
      });
    } finally {
      setLoadingTables(false);
    }
  }, [addToast]);

  useEffect(() => {
    fetchUploadedTables();
  }, [fetchUploadedTables]);

  // Handle File Selection
  const handleFileSelect = (file: File) => {
    const ext = file.name.substring(file.name.lastIndexOf('.')).toLowerCase();
    if (ext !== '.xlsx' && ext !== '.xls' && ext !== '.csv') {
      addToast?.({
        type: 'error',
        title: 'Format File Tidak Sesuai',
        message: 'Silakan pilih file dengan ekstensi .xlsx, .xls, atau .csv'
      });
      return;
    }

    setSelectedFile(file);
    setTableName(generateDefaultTableName(file.name));
    setDescription(generateDefaultDescription(file.name));
  };

  // Handle File Drop
  const handleDrop = (e: React.DragEvent) => {
    e.preventDefault();
    setIsDragOver(false);
    if (e.dataTransfer.files && e.dataTransfer.files.length > 0) {
      handleFileSelect(e.dataTransfer.files[0]);
    }
  };

  // Submit Upload to Backend
  const handleUploadSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!selectedFile) {
      addToast?.({ type: 'warning', title: 'Pilih File', message: 'Harap pilih file terlebih dahulu' });
      return;
    }
    if (!tableName.trim()) {
      addToast?.({ type: 'warning', title: 'Nama Tabel Kosong', message: 'Harap isi nama tabel' });
      return;
    }

    setIsUploading(true);
    const formData = new FormData();
    formData.append('file', selectedFile);
    formData.append('tableName', tableName.trim());
    formData.append('description', description.trim());

    try {
      const res = await axios.post('/api/file-upload/upload', formData, {
        headers: { 'Content-Type': 'multipart/form-data' }
      });

      addToast?.({
        type: 'success',
        title: 'Upload Berhasil!',
        message: `Tabel sch_excel."${res.data.tableName}" berhasil dibuat (${res.data.rowCount} baris)`
      });

      // Reset form
      setSelectedFile(null);
      setTableName('');
      setDescription('');

      // Refresh table list
      fetchUploadedTables();
    } catch (err: any) {
      console.error('Upload failed:', err);
      addToast?.({
        type: 'error',
        title: 'Upload Gagal',
        message: err.response?.data?.error || 'Gagal memproses dan mengunggah file ke database'
      });
    } finally {
      setIsUploading(false);
    }
  };

  // Load Table Preview
  const handleOpenPreview = async (tableNameToPreview: string, page: number = 1) => {
    setActivePreviewTable(tableNameToPreview);
    setPreviewPage(page);
    setLoadingPreview(true);
    setPreviewModalOpen(true);

    try {
      const res = await axios.get(`/api/file-upload/tables/${tableNameToPreview}/preview`, {
        params: { page, pageSize: previewPageSize }
      });
      setPreviewData(res.data);
    } catch (err: any) {
      console.error('Failed to preview table:', err);
      addToast?.({
        type: 'error',
        title: 'Gagal Membaca Tabel',
        message: err.response?.data?.error || 'Tidak dapat membaca data preview tabel'
      });
    } finally {
      setLoadingPreview(false);
    }
  };

  // Delete Table
  const handleDeleteTable = (tName: string) => {
    showAlert({
      title: 'Hapus Tabel Database?',
      message: `Apakah Anda yakin ingin menghapus tabel sch_excel."${tName}" dari database? Tindakan ini tidak dapat dibatalkan.`,
      type: 'warning',
      confirmLabel: 'Ya, Hapus Tabel',
      cancelLabel: 'Batal',
      onConfirm: async () => {
        try {
          await axios.delete(`/api/file-upload/tables/${tName}`);
          addToast?.({
            type: 'success',
            title: 'Tabel Dihapus',
            message: `Tabel sch_excel."${tName}" berhasil dihapus`
          });
          fetchUploadedTables();
        } catch (err: any) {
          console.error('Failed to delete table:', err);
          addToast?.({
            type: 'error',
            title: 'Gagal Menghapus Tabel',
            message: err.response?.data?.error || 'Tabel gagal dihapus dari database'
          });
        }
      }
    });
  };

  const filteredTables = tables.filter(t => 
    t.table_name.toLowerCase().includes(searchTerm.toLowerCase()) ||
    (t.description && t.description.toLowerCase().includes(searchTerm.toLowerCase())) ||
    (t.original_filename && t.original_filename.toLowerCase().includes(searchTerm.toLowerCase()))
  );

  return (
    <div className="h-full flex flex-col bg-bg-main text-text-main overflow-y-auto p-4 md:p-6 space-y-6">
      {/* Header Banner */}
      <div className="flex flex-col md:flex-row md:items-center justify-between gap-4 pb-4 border-b border-border-main">
        <div className="flex items-center gap-3">
          <div className="w-10 h-10 rounded-xl bg-gradient-to-tr from-emerald-600 to-teal-400 flex items-center justify-center shadow-lg shadow-emerald-500/20 text-white">
            <UploadCloud className="w-5 h-5" />
          </div>
          <div>
            <h1 className="text-xl font-bold tracking-tight bg-gradient-to-r from-emerald-400 to-teal-300 bg-clip-text text-transparent">
              Upload File
            </h1>
            <p className="text-xs text-text-muted mt-0.5">
              Upload file Excel (.xlsx, .xls) & CSV (.csv) ke database internal (Schema: <code className="px-1.5 py-0.5 rounded bg-bg-editor font-mono text-emerald-400">sch_excel</code>)
            </p>
          </div>
        </div>
        <button
          onClick={fetchUploadedTables}
          disabled={loadingTables}
          className="flex items-center gap-2 px-3 py-1.5 text-xs font-medium bg-bg-panel border border-border-main hover:bg-bg-hover rounded-lg transition-colors shadow-sm disabled:opacity-50 self-start md:self-auto"
        >
          <RefreshCw className={clsx("w-3.5 h-3.5 text-emerald-400", loadingTables && "animate-spin")} />
          Refresh Tabel
        </button>
      </div>

      {/* Grid Layout: Left Upload Form, Right Uploaded Tables */}
      <div className="grid grid-cols-1 lg:grid-cols-12 gap-6">
        {/* Left Column: Upload Form */}
        <div className="lg:col-span-5 space-y-4">
          <div className="bg-bg-panel border border-border-main rounded-xl p-5 shadow-sm space-y-4">
            <h2 className="text-sm font-semibold flex items-center gap-2 text-text-main">
              <FileSpreadsheet className="w-4 h-4 text-emerald-400" />
              Upload File Excel / CSV Baru
            </h2>

            {/* Dropzone Area */}
            <div
              onDragOver={(e) => { e.preventDefault(); setIsDragOver(true); }}
              onDragLeave={() => setIsDragOver(false)}
              onDrop={handleDrop}
              className={clsx(
                "border-2 border-dashed rounded-xl p-6 text-center transition-all cursor-pointer relative flex flex-col items-center justify-center min-h-[160px]",
                isDragOver
                  ? "border-emerald-500 bg-emerald-500/10 shadow-inner scale-[0.99]"
                  : selectedFile
                  ? "border-emerald-500/50 bg-emerald-500/5"
                  : "border-border-main hover:border-emerald-500/50 hover:bg-bg-hover"
              )}
            >
              <input
                type="file"
                accept=".xlsx,.xls,.csv"
                onChange={(e) => e.target.files?.[0] && handleFileSelect(e.target.files[0])}
                className="absolute inset-0 opacity-0 cursor-pointer w-full h-full"
              />

              {selectedFile ? (
                <div className="flex flex-col items-center space-y-2">
                  {selectedFile.name.toLowerCase().endsWith('.csv') ? (
                    <FileText className="w-10 h-10 text-amber-400 animate-bounce" />
                  ) : (
                    <FileSpreadsheet className="w-10 h-10 text-emerald-400 animate-bounce" />
                  )}
                  <p className="text-xs font-semibold text-text-main max-w-[240px] truncate">
                    {selectedFile.name}
                  </p>
                  <p className="text-[11px] text-text-muted">
                    {(selectedFile.size / 1024).toFixed(1)} KB — Click atau Drag untuk ganti file
                  </p>
                </div>
              ) : (
                <div className="flex flex-col items-center space-y-2 text-text-muted">
                  <UploadCloud className="w-10 h-10 text-emerald-400/80 mb-1" />
                  <p className="text-xs font-medium text-text-main">
                    Drag & Drop file disini atau <span className="text-emerald-400 underline">Pilih File</span>
                  </p>
                  <p className="text-[11px]">Format: Excel (.xlsx, .xls) atau CSV (.csv)</p>
                </div>
              )}
            </div>

            {/* Form Inputs (Rendered when file is selected or always available) */}
            <form onSubmit={handleUploadSubmit} className="space-y-4">
              <div>
                <label className="block text-xs font-medium text-text-muted mb-1.5">
                  Nama Tabel Database (<code className="text-emerald-400 font-mono">sch_excel.&lt;nama_tabel&gt;</code>)
                </label>
                <div className="relative">
                  <input
                    type="text"
                    value={tableName}
                    onChange={(e) => setTableName(e.target.value)}
                    placeholder="Contoh: data_penjualan_20260723"
                    className="w-full bg-bg-editor border border-border-main rounded-lg px-3 py-2 text-xs font-mono focus:outline-none focus:border-emerald-500 text-text-main placeholder-text-muted/50"
                    required
                  />
                </div>
                <p className="text-[11px] text-text-muted mt-1">
                  * Otomatis terisi nama file + tanggal. Karakter khusus akan diubah secara aman.
                </p>
              </div>

              <div>
                <label className="block text-xs font-medium text-text-muted mb-1.5">
                  Deskripsi Tabel
                </label>
                <textarea
                  value={description}
                  onChange={(e) => setDescription(e.target.value)}
                  placeholder="Keterangan singkat mengenai isi tabel..."
                  rows={2}
                  className="w-full bg-bg-editor border border-border-main rounded-lg px-3 py-2 text-xs focus:outline-none focus:border-emerald-500 text-text-main placeholder-text-muted/50 resize-none"
                />
              </div>

              <button
                type="submit"
                disabled={!selectedFile || isUploading}
                className="w-full flex items-center justify-center gap-2 py-2.5 px-4 bg-gradient-to-r from-emerald-600 to-teal-500 hover:from-emerald-500 hover:to-teal-400 text-white font-medium text-xs rounded-lg shadow-md shadow-emerald-500/20 transition-all disabled:opacity-50 disabled:cursor-not-allowed disabled:shadow-none"
              >
                {isUploading ? (
                  <>
                    <Loader2 className="w-4 h-4 animate-spin" />
                    Sedang Mengunggah & Mengonversi...
                  </>
                ) : (
                  <>
                    <UploadCloud className="w-4 h-4" />
                    Upload ke Database (sch_excel)
                  </>
                )}
              </button>
            </form>
          </div>
        </div>

        {/* Right Column: Uploaded Tables List */}
        <div className="lg:col-span-7 space-y-4">
          <div className="bg-bg-panel border border-border-main rounded-xl p-5 shadow-sm space-y-4 flex flex-col h-full">
            <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3">
              <div className="flex items-center gap-2">
                <Database className="w-4 h-4 text-emerald-400" />
                <h2 className="text-sm font-semibold text-text-main">
                  Daftar Tabel Tersimpan di <span className="font-mono text-emerald-400">sch_excel</span>
                </h2>
                <span className="px-2 py-0.5 text-[11px] font-medium bg-emerald-500/10 text-emerald-400 rounded-full border border-emerald-500/20">
                  {tables.length} Tabel
                </span>
              </div>

              {/* Search input */}
              <div className="relative w-full sm:w-56">
                <Search className="w-3.5 h-3.5 absolute left-2.5 top-2.5 text-text-muted" />
                <input
                  type="text"
                  value={searchTerm}
                  onChange={(e) => setSearchTerm(e.target.value)}
                  placeholder="Cari nama tabel / file..."
                  className="w-full bg-bg-editor border border-border-main rounded-lg pl-8 pr-3 py-1.5 text-xs text-text-main focus:outline-none focus:border-emerald-500 placeholder-text-muted/50"
                />
              </div>
            </div>

            {/* Table Cards List */}
            {loadingTables ? (
              <div className="flex flex-col items-center justify-center py-12 text-text-muted space-y-2">
                <Loader2 className="w-6 h-6 animate-spin text-emerald-400" />
                <span className="text-xs">Memuat daftar tabel...</span>
              </div>
            ) : filteredTables.length === 0 ? (
              <div className="flex flex-col items-center justify-center py-12 border border-dashed border-border-main rounded-xl text-text-muted space-y-2 text-center">
                <Table className="w-8 h-8 text-text-muted/40" />
                <p className="text-xs font-medium text-text-main">Belum Ada Tabel Upload</p>
                <p className="text-[11px] text-text-muted max-w-xs">
                  {searchTerm ? 'Tidak ada tabel yang cocok dengan pencarian' : 'Upload file Excel atau CSV pertama Anda menggunakan form di sebelah kiri.'}
                </p>
              </div>
            ) : (
              <div className="space-y-3 max-h-[520px] overflow-y-auto pr-1">
                {filteredTables.map((t) => (
                  <div
                    key={t.table_name}
                    className="p-3.5 bg-bg-editor border border-border-main hover:border-emerald-500/40 rounded-xl transition-all flex flex-col sm:flex-row sm:items-center justify-between gap-3 group"
                  >
                    <div 
                      className="flex-1 min-w-0 cursor-pointer"
                      onClick={() => handleOpenPreview(t.table_name)}
                    >
                      <div className="flex items-center gap-2">
                        {t.file_type === 'csv' ? (
                          <span className="px-1.5 py-0.5 text-[10px] font-bold uppercase bg-amber-500/10 text-amber-400 border border-amber-500/20 rounded">
                            CSV
                          </span>
                        ) : (
                          <span className="px-1.5 py-0.5 text-[10px] font-bold uppercase bg-emerald-500/10 text-emerald-400 border border-emerald-500/20 rounded">
                            EXCEL
                          </span>
                        )}
                        <h3 className="text-xs font-bold font-mono text-emerald-400 group-hover:underline truncate">
                          {t.table_name}
                        </h3>
                      </div>

                      {t.description && (
                        <p className="text-xs text-text-main/80 mt-1 line-clamp-1">
                          {t.description}
                        </p>
                      )}

                      <div className="flex items-center gap-3 text-[11px] text-text-muted mt-1.5">
                        <span>File: <strong className="text-text-main/70">{t.original_filename || '-'}</strong></span>
                        <span>•</span>
                        <span>{t.row_count?.toLocaleString() || 0} Baris</span>
                        <span>•</span>
                        <span>{t.created_at ? new Date(t.created_at).toLocaleDateString('id-ID', { day: '2-digit', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit' }) : '-'}</span>
                      </div>
                    </div>

                    <div className="flex items-center gap-2 self-end sm:self-center shrink-0">
                      <button
                        onClick={() => handleOpenPreview(t.table_name)}
                        className="flex items-center gap-1 px-2.5 py-1.5 text-xs font-medium bg-bg-panel hover:bg-emerald-500/10 text-emerald-400 border border-border-main hover:border-emerald-500/30 rounded-lg transition-colors"
                        title="Lihat Data Tabel"
                      >
                        <Eye className="w-3.5 h-3.5" />
                        <span className="hidden sm:inline">Lihat Isi</span>
                      </button>

                      <button
                        onClick={() => handleDeleteTable(t.table_name)}
                        className="p-1.5 text-red-400 hover:text-red-300 hover:bg-red-500/10 border border-transparent hover:border-red-500/20 rounded-lg transition-colors"
                        title="Hapus Tabel"
                      >
                        <Trash2 className="w-4 h-4" />
                      </button>
                    </div>
                  </div>
                ))}
              </div>
            )}
          </div>
        </div>
      </div>

      {/* Modal Data Preview */}
      {previewModalOpen && (
        <div className="fixed inset-0 z-50 bg-black/60 backdrop-blur-sm flex items-center justify-center p-4">
          <div className="bg-bg-panel border border-border-main rounded-2xl w-full max-w-5xl max-h-[85vh] flex flex-col shadow-2xl overflow-hidden animate-in fade-in zoom-in-95 duration-200">
            {/* Modal Header */}
            <div className="flex items-center justify-between px-5 py-4 border-b border-border-main bg-bg-editor">
              <div className="flex items-center gap-2.5">
                <Table className="w-5 h-5 text-emerald-400" />
                <div>
                  <h3 className="text-sm font-bold font-mono text-emerald-400">
                    sch_excel."{activePreviewTable}"
                  </h3>
                  <p className="text-[11px] text-text-muted">
                    Total {previewData?.totalRows?.toLocaleString() || 0} Baris Data
                  </p>
                </div>
              </div>
              <button
                onClick={() => setPreviewModalOpen(false)}
                className="p-1 text-text-muted hover:text-text-main rounded-lg hover:bg-bg-hover transition-colors"
              >
                <X className="w-5 h-5" />
              </button>
            </div>

            {/* Modal Content */}
            <div className="flex-1 overflow-auto p-4">
              {loadingPreview ? (
                <div className="flex flex-col items-center justify-center py-20 text-text-muted space-y-2">
                  <Loader2 className="w-8 h-8 animate-spin text-emerald-400" />
                  <span className="text-xs">Membaca isi tabel...</span>
                </div>
              ) : !previewData || previewData.rows.length === 0 ? (
                <div className="text-center py-16 text-text-muted text-xs">
                  Tabel ini tidak memiliki data / kosong.
                </div>
              ) : (
                <div className="overflow-x-auto border border-border-main rounded-xl">
                  <table className="w-full text-left text-xs border-collapse font-sans">
                    <thead className="bg-bg-editor text-text-muted font-mono uppercase text-[11px] border-b border-border-main sticky top-0">
                      <tr>
                        <th className="px-3 py-2 border-r border-border-main w-12 text-center text-text-muted/60">#</th>
                        {previewData.columns.map((col) => (
                          <th key={col} className="px-3 py-2 border-r border-border-main font-semibold text-text-main whitespace-nowrap">
                            {col}
                          </th>
                        ))}
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-border-main text-text-main">
                      {previewData.rows.map((row, idx) => (
                        <tr key={idx} className="hover:bg-bg-hover/50 transition-colors">
                          <td className="px-3 py-2 border-r border-border-main text-center text-text-muted font-mono text-[11px]">
                            {(previewData.page - 1) * previewData.pageSize + idx + 1}
                          </td>
                          {previewData.columns.map((col) => (
                            <td key={col} className="px-3 py-2 border-r border-border-main whitespace-nowrap font-mono text-[11px] max-w-xs truncate">
                              {row[col] !== null && row[col] !== undefined ? String(row[col]) : <span className="text-text-muted/40 italic">null</span>}
                            </td>
                          ))}
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </div>

            {/* Modal Footer / Pagination */}
            {previewData && previewData.totalPages > 1 && (
              <div className="flex items-center justify-between px-5 py-3 border-t border-border-main bg-bg-editor text-xs text-text-muted">
                <span>
                  Halaman {previewData.page} dari {previewData.totalPages} ({previewData.totalRows} Total)
                </span>
                <div className="flex items-center gap-2">
                  <button
                    onClick={() => handleOpenPreview(activePreviewTable!, previewPage - 1)}
                    disabled={previewPage <= 1 || loadingPreview}
                    className="p-1.5 rounded-lg border border-border-main hover:bg-bg-hover disabled:opacity-40 transition-colors"
                  >
                    <ChevronLeft className="w-4 h-4" />
                  </button>
                  <span className="font-mono px-2">{previewPage}</span>
                  <button
                    onClick={() => handleOpenPreview(activePreviewTable!, previewPage + 1)}
                    disabled={previewPage >= previewData.totalPages || loadingPreview}
                    className="p-1.5 rounded-lg border border-border-main hover:bg-bg-hover disabled:opacity-40 transition-colors"
                  >
                    <ChevronRight className="w-4 h-4" />
                  </button>
                </div>
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  );
};
