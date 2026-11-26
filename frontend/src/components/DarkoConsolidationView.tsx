import React, { useEffect, useMemo, useState } from 'react';
import axios from 'axios';
import clsx from 'clsx';
import {
  Building2,
  CheckCircle2,
  Database,
  Edit3,
  Eye,
  GitMerge,
  Layers,
  Loader2,
  Plus,
  RefreshCw,
  Search,
  Sparkles,
  X,
} from 'lucide-react';
import { useAppStore } from '../store/useAppStore';

interface PerusahaanItem {
  kode_perusahaan: string;
  nama_perusahaan: string;
  nama_singkat: string;
  url_erp: string;
  akses?: string;
}

interface NamaDataOption {
  kode_nama: string;
  nama_data: string;
}

interface ColumnDef {
  label: string;
  value: string;
  number?: boolean;
}

const NAMA_DATA_OPTIONS: NamaDataOption[] = [
  { kode_nama: 'KN001', nama_data: 'DATA PELANGGAN/TOKO' },
];

const KONSOLIDASI_COLUMNS_ERP: ColumnDef[] = [
  { label: 'ID/KODE', value: 'kode_langganan' },
  { label: 'NAMA PELANGGAN', value: 'nama_langganan' },
  { label: 'ALAMAT PELANGGAN', value: 'alamat' },
  { label: 'KELURAHAN PELANGGAN', value: 'kode_kelurahan_lgn' },
  { label: 'WILAYAH PELANGGAN', value: 'wilayah_deskripsi_lgn' },
  { label: 'ALAMAT GUDANG', value: 'alamat_gudang' },
  { label: 'KELURAHAN GUDANG', value: 'kode_kelurahan' },
  { label: 'WILAYAH GUDANG', value: 'wilayah_deskripsi' },
  { label: 'CONTACT PERSON', value: 'contact_person' },
  { label: 'NO.TELEPON', value: 'telepon' },
  { label: 'NO.HP', value: 'hp' },
  { label: 'NO.HP 2', value: 'hp2' },
  { label: 'EMAIL 1', value: 'email1' },
  { label: 'EMAIL 2', value: 'email2' },
  { label: 'TAHUN BERDIRI', value: 'tahun_berdiri_init' },
  { label: 'NO.NPWP', value: 'npwp' },
  { label: 'ALAMAT NPWP', value: 'alamat_npwp' },
  { label: 'NO.KTP', value: 'nomor_ktp' },
  { label: 'NAMA KTP', value: 'nama_ktp' },
  { label: 'ALAMAT KTP', value: 'alamat_ktp' },
  { label: 'LATITUDE', value: 'latitude', number: true },
  { label: 'LONGITUDE', value: 'longitude', number: true },
  { label: 'ALTITUDE', value: 'altitude', number: true },
];

const KONSOLIDASI_COLUMNS_KS: ColumnDef[] = [
  { label: 'ID/KODE', value: 'id_langganan' },
  ...KONSOLIDASI_COLUMNS_ERP.slice(1),
];

const MONITORING_COLUMNS: ColumnDef[] = [
  { label: 'ID/KODE', value: 'id_langganan' },
  { label: 'NAMA PELANGGAN', value: 'nama_langganan' },
  { label: 'ALAMAT PELANGGAN', value: 'alamat' },
  { label: 'KELURAHAN PELANGGAN', value: 'kode_kelurahan_lgn' },
  { label: 'WILAYAH PELANGGAN', value: 'wilayah_deskripsi_lgn' },
  { label: 'ALAMAT GUDANG', value: 'alamat_gudang' },
  { label: 'KELURAHAN GUDANG', value: 'kode_kelurahan' },
  { label: 'WILAYAH GUDANG', value: 'wilayah_deskripsi' },
  { label: 'CONTACT PERSON', value: 'contact_person' },
  { label: 'NO.TELEPON', value: 'telepon' },
  { label: 'NO.HP', value: 'hp' },
  { label: 'NO.HP 2', value: 'hp2' },
  { label: 'EMAIL 1', value: 'email1' },
  { label: 'EMAIL 2', value: 'email2' },
  { label: 'TAHUN BERDIRI', value: 'tahun_berdiri' },
  { label: 'NO.NPWP', value: 'npwp' },
  { label: 'ALAMAT NPWP', value: 'alamat_npwp' },
  { label: 'NO.KTP', value: 'nomor_ktp' },
  { label: 'NAMA KTP', value: 'nama_ktp' },
  { label: 'ALAMAT KTP', value: 'alamat_ktp' },
  { label: 'LATITUDE', value: 'latitude', number: true },
  { label: 'LONGITUDE', value: 'longitude', number: true },
  { label: 'ALTITUDE', value: 'altitude', number: true },
];

const DETAIL_ORDER_ERP = [
  'kode_langganan',
  'nama_langganan',
  'alamat',
  'wilayah_deskripsi_lgn',
  'kode_kelurahan_lgn',
  'telepon',
  'hp',
  'hp2',
  'npwp',
  'alamat_npwp',
  'nomor_ktp',
  'nama_ktp',
  'alamat_ktp',
  'alamat_gudang',
  'wilayah_deskripsi',
  'kode_kelurahan',
  'contact_person',
  'altitude',
  'latitude',
  'longitude',
  'email1',
  'email2',
  'tahun_berdiri_init',
];

const DETAIL_ORDER_KS = ['id_langganan', ...DETAIL_ORDER_ERP.slice(1)];

const MONITORING_DETAIL_ORDER = [
  'id_langganan',
  'nama_langganan',
  'alamat',
  'wilayah_deskripsi_lgn',
  'kode_kelurahan_lgn',
  'telepon',
  'hp',
  'hp2',
  'npwp',
  'alamat_npwp',
  'nomor_ktp',
  'nama_ktp',
  'alamat_ktp',
  'alamat_gudang',
  'wilayah_deskripsi',
  'kode_kelurahan',
  'contact_person',
  'altitude',
  'latitude',
  'longitude',
  'email1',
  'email2',
  'tahun_berdiri',
];

function computeJaro(raw1: unknown, raw2: unknown): number {
  const char1 = raw1 == null ? '' : String(raw1);
  const char2 = raw2 == null ? '' : String(raw2);

  if (char1.length === 0 || char2.length === 0) return 0;
  if (char1 === char2) return 100;

  const range = Math.floor(Math.max(char1.length, char2.length) / 2) - 1;
  const s1Matches = new Array<boolean>(char1.length);
  const s2Matches = new Array<boolean>(char2.length);
  let m = 0;

  for (let i = 0; i < char1.length; i++) {
    const low = i >= range ? i - range : 0;
    const high = i + range <= char2.length ? i + range : char2.length - 1;
    for (let j = low; j <= high; j++) {
      if (!s1Matches[i] && !s2Matches[j] && char1[i] === char2[j]) {
        m++;
        s1Matches[i] = true;
        s2Matches[j] = true;
        break;
      }
    }
  }

  if (m === 0) return 0;

  let k = 0;
  let nTrans = 0;
  for (let i = 0; i < char1.length; i++) {
    if (s1Matches[i]) {
      let j = k;
      for (; j < char2.length; j++) {
        if (s2Matches[j]) {
          k = j + 1;
          break;
        }
      }
      if (char1[i] !== char2[j]) {
        nTrans++;
      }
    }
  }

  const jaro = (m / char1.length + m / char2.length + (m - nTrans / 2) / m) / 3;
  return Number.isFinite(jaro) ? jaro * 100 : 0;
}

function normalizeRow(row: Record<string, any>): Record<string, any> {
  const out: Record<string, any> = {};
  for (const [k, v] of Object.entries(row)) {
    out[k] = v === null || v === undefined ? '' : v;
  }
  const tb = out.tahun_berdiri;
  out.tahun_berdiri_init = tb === 0 || tb === '0' || tb === '' ? '' : tb;
  return out;
}

function escapeSqlQuotes(obj: Record<string, any>): Record<string, any> {
  const copy: Record<string, any> = { ...obj };
  for (const key of Object.keys(copy)) {
    if (typeof copy[key] === 'string' && copy[key].includes("'")) {
      copy[key] = copy[key].replace(/'/g, "''");
    }
  }
  return copy;
}

function getSimilarityBadgeStyle(prob?: number): string {
  if (prob === undefined || prob === null) return '';
  if (prob > 80) return 'bg-emerald-500/20 text-emerald-700 dark:text-emerald-300 border-l-4 border-l-emerald-500';
  if (prob > 60) return 'bg-yellow-500/20 text-yellow-700 dark:text-yellow-300 border-l-4 border-l-yellow-500';
  if (prob > 40) return 'bg-orange-500/20 text-orange-700 dark:text-orange-300 border-l-4 border-l-orange-500';
  return 'bg-rose-500/20 text-rose-700 dark:text-rose-300 border-l-4 border-l-rose-500';
}

async function callKonsolApi(responseCode: string, requestParam: Record<string, any> = {}) {
  const { data } = await axios.post('/api/konsolidasi', {
    responseCode,
    requestParam,
    authParam: {},
  });
  return data;
}

export const DarkoConsolidationView: React.FC = () => {
  const { addToast } = useAppStore();
  const [subTab, setSubTab] = useState<'konsolidasi' | 'monitoring'>('konsolidasi');

  // Shared company list
  const [companies, setCompanies] = useState<PerusahaanItem[]>([]);
  const [loadingCompanies, setLoadingCompanies] = useState(false);

  // ---------------- KONSOLIDASI DATA STATE ----------------
  const [kodeNama, setKodeNama] = useState<string>('KN001');
  const [selectedCompany, setSelectedCompany] = useState<PerusahaanItem | null>(null);
  const [probabilitas, setProbabilitas] = useState<number>(50);

  const [erpRows, setErpRows] = useState<Record<string, any>[]>([]);
  const [ksOriginRows, setKsOriginRows] = useState<Record<string, any>[]>([]);
  const [ksFilteredRows, setKsFilteredRows] = useState<Record<string, any>[]>([]);

  const [loadingErp, setLoadingErp] = useState(false);
  const [loadingKs, setLoadingKs] = useState(false);
  const [savingAction, setSavingAction] = useState(false);

  const [erpSearch, setErpSearch] = useState('');
  const [ksSearch, setKsSearch] = useState('');

  const [selectedErpRow, setSelectedErpRow] = useState<Record<string, any> | null>(null);
  const [selectedKsRow, setSelectedKsRow] = useState<Record<string, any> | null>(null);
  const [confirmModal, setConfirmModal] = useState<{ action: 'New' | 'Match'; title: string; message: string } | null>(null);

  // ---------------- MONITORING DATA STATE ----------------
  const [monKodeNama, setMonKodeNama] = useState<string>('KN001');
  const [monCompany, setMonCompany] = useState<PerusahaanItem | null>(null);
  const [monRows, setMonRows] = useState<Record<string, any>[]>([]);
  const [loadingMon, setLoadingMon] = useState(false);
  const [monSearch, setMonSearch] = useState('');
  const [editingMonRow, setEditingMonRow] = useState<Record<string, any> | null>(null);
  const [savingMon, setSavingMon] = useState(false);

  // Load companies on mount
  const fetchCompanies = async () => {
    setLoadingCompanies(true);
    try {
      const res = await callKonsolApi('GET-DATA-PERUSAHAAN', { pass: '' });
      if (res?.STATUS === 'Y' && Array.isArray(res.RESULT)) {
        setCompanies(res.RESULT);
      } else {
        addToast({ type: 'warning', title: 'Perusahaan', message: 'Gagal memuat daftar perusahaan dari data_konsol.' });
      }
    } catch (err: any) {
      addToast({
        type: 'error',
        title: 'Koneksi Consolidation DB',
        message: err?.response?.data?.RESULT || err?.message || 'Gagal menghubungi backend konsolidasi.',
      });
    } finally {
      setLoadingCompanies(false);
    }
  };

  useEffect(() => {
    fetchCompanies();
  }, []);

  // Re-score & filter Data Konsolidasi when selectedErpRow or probabilitas changes
  const scoredKsRows = useMemo(() => {
    if (!selectedErpRow) return ksOriginRows;

    const d = { ...selectedErpRow };
    delete d.kode_langganan;
    delete d.altitude;
    delete d.latitude;
    delete d.longitude;

    const scored = ksOriginRows.map((row) => {
      const scores: number[] = [];
      for (const key of Object.keys(row)) {
        if (
          key === 'id_langganan' ||
          key === 'altitude' ||
          key === 'latitude' ||
          key === 'longitude' ||
          key === 'tahun_berdiri_init' ||
          key === 'probabilitas'
        ) {
          continue;
        }
        const jaro = computeJaro(d[key], row[key]);
        if (jaro > 0) scores.push(jaro);
      }
      const avg = scores.length > 0 ? scores.reduce((acc, v) => acc + v / scores.length, 0) : 0;
      return { ...row, probabilitas: avg };
    });

    return scored
      .filter((r) => (r.probabilitas ?? 0) >= probabilitas)
      .sort((a, b) => (b.probabilitas ?? 0) - (a.probabilitas ?? 0));
  }, [ksOriginRows, selectedErpRow, probabilitas]);

  useEffect(() => {
    setKsFilteredRows(scoredKsRows);
  }, [scoredKsRows]);

  const displayedPersen = useMemo(() => {
    if (!selectedKsRow || selectedKsRow.probabilitas === undefined) return null;
    const val = Number(selectedKsRow.probabilitas);
    if (val >= 100) return 100;
    return Math.ceil(val || 0);
  }, [selectedKsRow]);

  const handleLoadKonsolidasi = async () => {
    if (!selectedCompany) {
      addToast({ type: 'info', title: 'Validasi', message: 'Pilih perusahaan (Banding Data) terlebih dahulu.' });
      return;
    }

    setSelectedErpRow(null);
    setSelectedKsRow(null);
    setErpRows([]);
    setKsOriginRows([]);
    setLoadingErp(true);
    setLoadingKs(true);

    const reqPayload = {
      kode_nama: kodeNama,
      nama_data: NAMA_DATA_OPTIONS.find((x) => x.kode_nama === kodeNama)?.nama_data || '',
      kode_perusahaan: selectedCompany.kode_perusahaan,
      nama_singkat: selectedCompany.nama_singkat,
      url_erp: selectedCompany.url_erp,
      probabilitas,
    };

    // Fetch Data Pembanding (ERP)
    callKonsolApi('GET-DATA-LANGGANAN-ERP', reqPayload)
      .then((res) => {
        if (res?.STATUS === 'Y' && Array.isArray(res.RESULT)) {
          setErpRows(res.RESULT.map(normalizeRow));
        } else {
          setErpRows([]);
          addToast({ type: 'warning', title: 'Data ERP', message: res?.RESULT || 'Data ERP kosong / gagal diambil.' });
        }
      })
      .catch((err) => {
        setErpRows([]);
        addToast({ type: 'error', title: 'Data ERP', message: err?.message || 'Gagal mengambil data ERP.' });
      })
      .finally(() => setLoadingErp(false));

    // Fetch Data Konsolidasi (paginated)
    try {
      const firstPage = await callKonsolApi('GET-DATA-LANGGANAN-KONSOLIDASI', { ...reqPayload, page: 1 });
      if (firstPage?.STATUS === 'Y') {
        const totalPages = Number(firstPage.PART_REQ || 1);
        let allRows: Record<string, any>[] = Array.isArray(firstPage.RESULT) ? [...firstPage.RESULT] : [];
        for (let p = 2; p <= totalPages; p++) {
          const nextRes = await callKonsolApi('GET-DATA-LANGGANAN-KONSOLIDASI', { ...reqPayload, page: p });
          if (nextRes?.STATUS === 'Y' && Array.isArray(nextRes.RESULT)) {
            allRows = allRows.concat(nextRes.RESULT);
          }
        }
        setKsOriginRows(allRows.map(normalizeRow));
      } else {
        setKsOriginRows([]);
      }
    } catch (err: any) {
      setKsOriginRows([]);
      addToast({ type: 'error', title: 'Data Konsolidasi', message: err?.message || 'Gagal mengambil data konsolidasi.' });
    } finally {
      setLoadingKs(false);
    }
  };

  const executeKonsolidasiAction = async (action: 'New' | 'Match') => {
    if (!selectedCompany || !selectedErpRow) return;
    setConfirmModal(null);
    setSavingAction(true);

    const formDetail = escapeSqlQuotes(selectedErpRow);
    const baseFilter = {
      kode_nama: kodeNama,
      nama_data: NAMA_DATA_OPTIONS.find((x) => x.kode_nama === kodeNama)?.nama_data || '',
      kode_perusahaan: selectedCompany.kode_perusahaan,
      nama_singkat: selectedCompany.nama_singkat,
      url_erp: selectedCompany.url_erp,
      probabilitas,
    };

    try {
      if (action === 'New') {
        const payload = {
          ...baseFilter,
          ...formDetail,
          kode_langganan: selectedErpRow.kode_langganan,
        };
        const res = await callKonsolApi('SET-DATA-LANGGANAN-KONSOLIDASI', payload);
        if (res?.STATUS === 'Y') {
          addToast({ type: 'success', title: 'Berhasil', message: 'BERHASIL MENAMBAHKAN DATA' });
          await handleLoadKonsolidasi();
        } else {
          addToast({ type: 'error', title: 'Gagal', message: res?.RESULT || 'GAGAL MENAMBAHKAN DATA' });
        }
      } else {
        if (!selectedKsRow) return;
        const payload = {
          ...baseFilter,
          ...formDetail,
          kode_langganan: selectedErpRow.kode_langganan,
          id_langganan: selectedKsRow.id_langganan,
        };
        const res = await callKonsolApi('UPT-DATA-LANGGANAN-KONSOLIDASI', payload);
        if (res?.STATUS === 'Y') {
          addToast({ type: 'success', title: 'Berhasil', message: 'BERHASIL MENCOCOKAN DATA' });
          await handleLoadKonsolidasi();
        } else {
          addToast({ type: 'error', title: 'Gagal', message: res?.RESULT || 'GAGAL MENCOCOKAN DATA' });
        }
      }
    } catch (err: any) {
      addToast({ type: 'error', title: 'Error', message: err?.message || 'Gagal memproses aksi.' });
    } finally {
      setSavingAction(false);
    }
  };

  // ---------------- MONITORING ACTIONS ----------------
  const handleLoadMonitoring = async () => {
    if (!monCompany) {
      addToast({ type: 'info', title: 'Validasi', message: 'Pilih perusahaan terlebih dahulu.' });
      return;
    }
    setLoadingMon(true);
    try {
      const res = await callKonsolApi('GET-DATA-LANGGANAN-PERUSAHAAN', {
        kode_nama: monKodeNama,
        kode_perusahaan: monCompany.kode_perusahaan,
        nama_singkat: monCompany.nama_singkat,
        url_erp: monCompany.url_erp,
      });
      if (res?.STATUS === 'Y' && Array.isArray(res.RESULT)) {
        setMonRows(res.RESULT.map(normalizeRow));
      } else {
        setMonRows([]);
        addToast({ type: 'warning', title: 'Monitoring', message: res?.RESULT || 'Data tidak ditemukan.' });
      }
    } catch (err: any) {
      setMonRows([]);
      addToast({ type: 'error', title: 'Monitoring', message: err?.message || 'Gagal memuat data monitoring.' });
    } finally {
      setLoadingMon(false);
    }
  };

  const handleSaveKoreksi = async () => {
    if (!editingMonRow) return;
    setSavingMon(true);
    try {
      const payload = {
        ...escapeSqlQuotes(editingMonRow),
        id_langganan: editingMonRow.id_langganan,
      };
      const res = await callKonsolApi('UPT-DATA-LANGGANAN-KOREKSI', payload);
      if (res?.STATUS === 'Y') {
        addToast({ type: 'success', title: 'Koreksi Tersimpan', message: 'BERHASIL UPDATE DATA' });
        setEditingMonRow(null);
        await handleLoadMonitoring();
      } else {
        addToast({ type: 'error', title: 'Gagal', message: res?.RESULT || 'GAGAL MENGUBAH DATA' });
      }
    } catch (err: any) {
      addToast({ type: 'error', title: 'Error', message: err?.message || 'Gagal menyimpan koreksi.' });
    } finally {
      setSavingMon(false);
    }
  };

  // Filtered tables for quick search
  const filteredErpRows = useMemo(() => {
    if (!erpSearch.trim()) return erpRows;
    const q = erpSearch.toLowerCase();
    return erpRows.filter((r) =>
      Object.values(r).some((v) => String(v ?? '').toLowerCase().includes(q))
    );
  }, [erpRows, erpSearch]);

  const filteredKsRows = useMemo(() => {
    if (!ksSearch.trim()) return ksFilteredRows;
    const q = ksSearch.toLowerCase();
    return ksFilteredRows.filter((r) =>
      Object.values(r).some((v) => String(v ?? '').toLowerCase().includes(q))
    );
  }, [ksFilteredRows, ksSearch]);

  const filteredMonRows = useMemo(() => {
    if (!monSearch.trim()) return monRows;
    const q = monSearch.toLowerCase();
    return monRows.filter((r) =>
      Object.values(r).some((v) => String(v ?? '').toLowerCase().includes(q))
    );
  }, [monRows, monSearch]);

  return (
    <div className="h-full flex flex-col bg-bg-main text-text-main overflow-hidden">
      {/* Top Bar */}
      <div className="px-4 md:px-6 py-3 bg-bg-panel border-b border-border-main flex flex-wrap items-center justify-between gap-3 shrink-0">
        <div className="flex items-center gap-3">
          <div className="w-9 h-9 rounded-lg bg-indigo-500/15 border border-indigo-500/30 flex items-center justify-center text-indigo-500">
            <Layers className="w-5 h-5" />
          </div>
          <div>
            <h1 className="text-base font-bold tracking-tight flex items-center gap-2">
              Darko Consolidation
              <span className="text-[10px] font-mono px-2 py-0.5 rounded-full bg-indigo-500/15 text-indigo-500 border border-indigo-500/25">
                sch_konsol
              </span>
            </h1>
            <p className="text-xs text-text-muted">
              Konsolidasi & monitoring master data pelanggan antar entitas ERP
            </p>
          </div>
        </div>

        <div className="flex items-center gap-2">
          <div className="flex items-center bg-bg-editor border border-border-main rounded-lg p-1 gap-1">
            <button
              onClick={() => setSubTab('konsolidasi')}
              className={clsx(
                'flex items-center gap-1.5 px-3 py-1.5 rounded-md text-xs font-medium transition-all',
                subTab === 'konsolidasi'
                  ? 'bg-indigo-600 text-white shadow-sm'
                  : 'text-text-muted hover:text-text-main hover:bg-bg-main'
              )}
            >
              <GitMerge className="w-3.5 h-3.5" />
              Konsolidasi Data
            </button>
            <button
              onClick={() => setSubTab('monitoring')}
              className={clsx(
                'flex items-center gap-1.5 px-3 py-1.5 rounded-md text-xs font-medium transition-all',
                subTab === 'monitoring'
                  ? 'bg-indigo-600 text-white shadow-sm'
                  : 'text-text-muted hover:text-text-main hover:bg-bg-main'
              )}
            >
              <Eye className="w-3.5 h-3.5" />
              Monitoring Data
            </button>
          </div>

          <button
            onClick={fetchCompanies}
            disabled={loadingCompanies}
            title="Refresh daftar perusahaan"
            className="p-2 rounded-lg border border-border-main bg-bg-editor hover:bg-bg-main text-text-muted hover:text-text-main transition-colors disabled:opacity-50"
          >
            <RefreshCw className={clsx('w-3.5 h-3.5', loadingCompanies && 'animate-spin')} />
          </button>
        </div>
      </div>

      {/* Main Content */}
      <div className="flex-1 overflow-auto p-4 md:p-5 space-y-4">
        {subTab === 'konsolidasi' ? (
          <>
            {/* Filter Card */}
            <div className="bg-bg-panel border border-border-main rounded-xl p-4 shadow-sm">
              <div className="grid grid-cols-1 md:grid-cols-12 gap-3 items-end">
                <div className="md:col-span-3">
                  <label className="block text-[11px] font-semibold text-text-muted uppercase tracking-wider mb-1">
                    Nama Data
                  </label>
                  <select
                    value={kodeNama}
                    onChange={(e) => setKodeNama(e.target.value)}
                    className="w-full h-9 px-3 rounded-lg bg-bg-editor border border-border-main text-xs text-text-main focus:outline-none focus:ring-2 focus:ring-indigo-500/40"
                  >
                    {NAMA_DATA_OPTIONS.map((opt) => (
                      <option key={opt.kode_nama} value={opt.kode_nama}>
                        {opt.kode_nama} — {opt.nama_data}
                      </option>
                    ))}
                  </select>
                </div>

                <div className="md:col-span-5">
                  <label className="block text-[11px] font-semibold text-text-muted uppercase tracking-wider mb-1">
                    Banding Data (Perusahaan ERP)
                  </label>
                  <select
                    value={selectedCompany?.kode_perusahaan || ''}
                    onChange={(e) => {
                      const comp = companies.find((c) => c.kode_perusahaan === e.target.value) || null;
                      setSelectedCompany(comp);
                    }}
                    className="w-full h-9 px-3 rounded-lg bg-bg-editor border border-border-main text-xs text-text-main focus:outline-none focus:ring-2 focus:ring-indigo-500/40"
                  >
                    <option value="">-- Pilih Perusahaan --</option>
                    {companies.map((c) => (
                      <option key={c.kode_perusahaan} value={c.kode_perusahaan}>
                        {c.nama_singkat} — {c.nama_perusahaan}
                      </option>
                    ))}
                  </select>
                </div>

                <div className="md:col-span-2">
                  <label className="block text-[11px] font-semibold text-text-muted uppercase tracking-wider mb-1">
                    Persentase Kemiripan (%)
                  </label>
                  <input
                    type="number"
                    min={0}
                    max={100}
                    value={probabilitas}
                    onChange={(e) => setProbabilitas(Math.max(0, Number(e.target.value || 0)))}
                    className="w-full h-9 px-3 rounded-lg bg-bg-editor border border-border-main text-xs text-text-main focus:outline-none focus:ring-2 focus:ring-indigo-500/40"
                  />
                </div>

                <div className="md:col-span-2">
                  <button
                    onClick={handleLoadKonsolidasi}
                    disabled={loadingErp || loadingKs || !selectedCompany}
                    className="w-full h-9 px-4 rounded-lg bg-indigo-600 hover:bg-indigo-700 text-white text-xs font-semibold flex items-center justify-center gap-2 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
                  >
                    {(loadingErp || loadingKs) ? (
                      <Loader2 className="w-3.5 h-3.5 animate-spin" />
                    ) : (
                      <Search className="w-3.5 h-3.5" />
                    )}
                    OK / Muat Data
                  </button>
                </div>
              </div>
            </div>

            {/* Side-by-Side Comparison Tables + Center Kemiripan Meter */}
            <div className="grid grid-cols-1 xl:grid-cols-12 gap-3 items-stretch">
              {/* Left Table: Data Pembanding */}
              <div className="xl:col-span-5 bg-bg-panel border border-border-main rounded-xl overflow-hidden flex flex-col shadow-sm min-h-[380px] max-h-[460px]">
                <div className="px-3.5 py-2.5 bg-slate-600 text-white flex items-center justify-between gap-2 shrink-0">
                  <div className="flex items-center gap-2">
                    <Building2 className="w-4 h-4 opacity-90" />
                    <span className="text-xs font-semibold">Data Pembanding (ERP)</span>
                    <span className="text-[10px] px-1.5 py-0.5 rounded bg-black/25 font-mono">
                      {filteredErpRows.length}
                    </span>
                  </div>
                  <div className="relative">
                    <Search className="w-3 h-3 absolute left-2 top-1/2 -translate-y-1/2 text-slate-300" />
                    <input
                      type="text"
                      value={erpSearch}
                      onChange={(e) => setErpSearch(e.target.value)}
                      placeholder="Cari ERP..."
                      className="h-6 pl-6 pr-2 rounded bg-black/20 text-[11px] text-white placeholder-slate-300 focus:outline-none focus:ring-1 focus:ring-white/40 w-36"
                    />
                  </div>
                </div>

                <div className="flex-1 overflow-auto relative">
                  {loadingErp ? (
                    <div className="h-full flex items-center justify-center py-16 text-xs text-text-muted gap-2">
                      <Loader2 className="w-4 h-4 animate-spin text-indigo-500" />
                      Mengambil data ERP...
                    </div>
                  ) : filteredErpRows.length === 0 ? (
                    <div className="h-full flex flex-col items-center justify-center py-16 text-xs text-text-muted">
                      <Database className="w-7 h-7 mb-2 opacity-30" />
                      Belum ada data pembanding. Pilih perusahaan lalu klik OK.
                    </div>
                  ) : (
                    <table className="w-full text-[11px] border-collapse">
                      <thead className="sticky top-0 bg-bg-editor border-b border-border-main z-10">
                        <tr>
                          {KONSOLIDASI_COLUMNS_ERP.map((col) => (
                            <th
                              key={col.value}
                              className="px-2.5 py-2 text-left font-semibold text-text-muted whitespace-nowrap border-r border-border-main/50"
                            >
                              {col.label}
                            </th>
                          ))}
                        </tr>
                      </thead>
                      <tbody>
                        {filteredErpRows.slice(0, 500).map((row, idx) => {
                          const isSelected = selectedErpRow?.kode_langganan === row.kode_langganan;
                          return (
                            <tr
                              key={`${row.kode_langganan}-${idx}`}
                              onClick={() => {
                                setSelectedErpRow(row);
                                setSelectedKsRow(null);
                              }}
                              className={clsx(
                                'cursor-pointer border-b border-border-main/40 transition-colors',
                                isSelected
                                  ? 'bg-indigo-500/20 text-text-main font-medium'
                                  : 'hover:bg-bg-editor/70'
                              )}
                            >
                              {KONSOLIDASI_COLUMNS_ERP.map((col) => (
                                <td
                                  key={col.value}
                                  className="px-2.5 py-1.5 whitespace-nowrap max-w-[220px] truncate border-r border-border-main/30"
                                >
                                  {String(row[col.value] ?? '')}
                                </td>
                              ))}
                            </tr>
                          );
                        })}
                      </tbody>
                    </table>
                  )}
                </div>
              </div>

              {/* Center Meter & Legend */}
              <div className="xl:col-span-2 flex flex-col items-center justify-center gap-3 py-2">
                {displayedPersen !== null && (
                  <div className="w-full max-w-[140px] rounded-xl bg-sky-600/90 text-white p-3 text-center shadow-md border border-sky-400/40">
                    <div className="text-[11px] font-semibold uppercase tracking-wider opacity-90">
                      Kemiripan
                    </div>
                    <div className="text-2xl font-bold mt-0.5">{displayedPersen}%</div>
                  </div>
                )}

                <div className="w-full max-w-[140px] bg-bg-panel border border-border-main rounded-xl p-2.5 space-y-1.5 text-[11px] font-semibold shadow-sm">
                  <div className="text-[10px] uppercase tracking-wider text-text-muted text-center mb-1">
                    Skala Kemiripan
                  </div>
                  <div className="px-2 py-1 rounded bg-[#87F175] text-slate-900 text-center">81% - 100%</div>
                  <div className="px-2 py-1 rounded bg-[#EAF77A] text-slate-900 text-center">61% - 80%</div>
                  <div className="px-2 py-1 rounded bg-[#EDC584] text-slate-900 text-center">41% - 60%</div>
                  <div className="px-2 py-1 rounded bg-[#E2807B] text-slate-900 text-center">0% - 40%</div>
                </div>
              </div>

              {/* Right Table: Data Konsolidasi */}
              <div className="xl:col-span-5 bg-bg-panel border border-border-main rounded-xl overflow-hidden flex flex-col shadow-sm min-h-[380px] max-h-[460px]">
                <div className="px-3.5 py-2.5 bg-sky-600 text-white flex items-center justify-between gap-2 shrink-0">
                  <div className="flex items-center gap-2">
                    <Sparkles className="w-4 h-4 opacity-90" />
                    <span className="text-xs font-semibold">Data Konsolidasi</span>
                    <span className="text-[10px] px-1.5 py-0.5 rounded bg-black/25 font-mono">
                      {filteredKsRows.length}
                    </span>
                  </div>
                  <div className="relative">
                    <Search className="w-3 h-3 absolute left-2 top-1/2 -translate-y-1/2 text-sky-200" />
                    <input
                      type="text"
                      value={ksSearch}
                      onChange={(e) => setKsSearch(e.target.value)}
                      placeholder="Cari Konsolidasi..."
                      className="h-6 pl-6 pr-2 rounded bg-black/20 text-[11px] text-white placeholder-sky-200 focus:outline-none focus:ring-1 focus:ring-white/40 w-40"
                    />
                  </div>
                </div>

                <div className="flex-1 overflow-auto relative">
                  {loadingKs ? (
                    <div className="h-full flex items-center justify-center py-16 text-xs text-text-muted gap-2">
                      <Loader2 className="w-4 h-4 animate-spin text-sky-500" />
                      Mengambil data konsolidasi...
                    </div>
                  ) : filteredKsRows.length === 0 ? (
                    <div className="h-full flex flex-col items-center justify-center py-16 text-xs text-text-muted">
                      <Database className="w-7 h-7 mb-2 opacity-30" />
                      Tidak ada data konsolidasi yang memenuhi filter.
                    </div>
                  ) : (
                    <table className="w-full text-[11px] border-collapse">
                      <thead className="sticky top-0 bg-bg-editor border-b border-border-main z-10">
                        <tr>
                          {selectedErpRow && (
                            <th className="px-2.5 py-2 text-left font-semibold text-text-muted whitespace-nowrap border-r border-border-main/50">
                              % MIRIP
                            </th>
                          )}
                          {KONSOLIDASI_COLUMNS_KS.map((col) => (
                            <th
                              key={col.value}
                              className="px-2.5 py-2 text-left font-semibold text-text-muted whitespace-nowrap border-r border-border-main/50"
                            >
                              {col.label}
                            </th>
                          ))}
                        </tr>
                      </thead>
                      <tbody>
                        {filteredKsRows.slice(0, 500).map((row, idx) => {
                          const isSelected = selectedKsRow?.id_langganan === row.id_langganan;
                          const rowColor = selectedErpRow ? getSimilarityBadgeStyle(row.probabilitas) : '';
                          return (
                            <tr
                              key={`${row.id_langganan}-${idx}`}
                              onClick={() => setSelectedKsRow(row)}
                              className={clsx(
                                'cursor-pointer border-b border-border-main/40 transition-colors',
                                rowColor,
                                isSelected ? 'ring-2 ring-inset ring-indigo-500 font-semibold' : 'hover:opacity-90'
                              )}
                            >
                              {selectedErpRow && (
                                <td className="px-2.5 py-1.5 whitespace-nowrap font-mono font-bold border-r border-border-main/30">
                                  {Math.ceil(row.probabilitas || 0)}%
                                </td>
                              )}
                              {KONSOLIDASI_COLUMNS_KS.map((col) => (
                                <td
                                  key={col.value}
                                  className="px-2.5 py-1.5 whitespace-nowrap max-w-[220px] truncate border-r border-border-main/30"
                                >
                                  {String(row[col.value] ?? '')}
                                </td>
                              ))}
                            </tr>
                          );
                        })}
                      </tbody>
                    </table>
                  )}
                </div>
              </div>
            </div>

            {/* Bottom Detail Comparison & Action Buttons */}
            <div className="grid grid-cols-1 xl:grid-cols-12 gap-3 items-start">
              {/* Detail Data Pembanding */}
              <div className="xl:col-span-5 bg-bg-panel border border-border-main rounded-xl overflow-hidden shadow-sm">
                <div className="px-3.5 py-2 bg-slate-600 text-white text-xs font-semibold">
                  Detail Data Pembanding
                </div>
                <div className="p-3.5 grid grid-cols-1 sm:grid-cols-2 gap-2.5">
                  {!selectedErpRow ? (
                    <div className="col-span-full py-8 text-center text-xs text-text-muted">
                      Pilih baris pada Data Pembanding untuk melihat detail.
                    </div>
                  ) : (
                    DETAIL_ORDER_ERP.map((field) => {
                      const colDef = KONSOLIDASI_COLUMNS_ERP.find((c) => c.value === field);
                      if (!colDef) return null;
                      return (
                        <div key={field}>
                          <label className="block text-[10px] font-semibold text-text-muted mb-0.5">
                            {colDef.label}
                          </label>
                          <input
                            type="text"
                            readOnly
                            value={String(selectedErpRow[field] ?? '')}
                            className="w-full h-8 px-2.5 rounded-md bg-bg-editor border border-border-main text-xs text-text-main"
                          />
                        </div>
                      );
                    })
                  )}
                </div>
              </div>

              {/* Action Buttons: New & Match */}
              <div className="xl:col-span-2 flex xl:flex-col items-center justify-center gap-2.5 py-4">
                <button
                  disabled={!selectedErpRow || savingAction}
                  onClick={() =>
                    setConfirmModal({
                      action: 'New',
                      title: 'New Data',
                      message: 'Tambahkan data pelanggan ini sebagai master data konsolidasi baru?',
                    })
                  }
                  className="w-full max-w-[140px] h-9 px-3 rounded-lg bg-emerald-600 hover:bg-emerald-700 text-white text-xs font-semibold flex items-center justify-center gap-1.5 transition-colors disabled:opacity-40 disabled:cursor-not-allowed"
                >
                  <Plus className="w-3.5 h-3.5" />
                  New
                </button>

                <button
                  disabled={!selectedErpRow || !selectedKsRow || savingAction}
                  onClick={() =>
                    setConfirmModal({
                      action: 'Match',
                      title: 'Match Data',
                      message: `Cocokkan pelanggan ERP (${selectedErpRow?.kode_langganan}) dengan ID Konsolidasi (${selectedKsRow?.id_langganan})?`,
                    })
                  }
                  className="w-full max-w-[140px] h-9 px-3 rounded-lg bg-indigo-600 hover:bg-indigo-700 text-white text-xs font-semibold flex items-center justify-center gap-1.5 transition-colors disabled:opacity-40 disabled:cursor-not-allowed"
                >
                  <CheckCircle2 className="w-3.5 h-3.5" />
                  Match
                </button>
              </div>

              {/* Detail Data Konsolidasi */}
              <div className="xl:col-span-5 bg-bg-panel border border-border-main rounded-xl overflow-hidden shadow-sm">
                <div className="px-3.5 py-2 bg-sky-600 text-white text-xs font-semibold">
                  Detail Data Konsolidasi
                </div>
                <div className="p-3.5 grid grid-cols-1 sm:grid-cols-2 gap-2.5">
                  {!selectedKsRow ? (
                    <div className="col-span-full py-8 text-center text-xs text-text-muted">
                      Pilih baris pada Data Konsolidasi untuk melihat detail.
                    </div>
                  ) : (
                    DETAIL_ORDER_KS.map((field) => {
                      const colDef = KONSOLIDASI_COLUMNS_KS.find((c) => c.value === field);
                      if (!colDef) return null;
                      return (
                        <div key={field}>
                          <label className="block text-[10px] font-semibold text-text-muted mb-0.5">
                            {colDef.label}
                          </label>
                          <input
                            type="text"
                            readOnly
                            value={String(selectedKsRow[field] ?? '')}
                            className="w-full h-8 px-2.5 rounded-md bg-bg-editor border border-border-main text-xs text-text-main"
                          />
                        </div>
                      );
                    })
                  )}
                </div>
              </div>
            </div>
          </>
        ) : (
          /* ---------------- MONITORING DATA TAB ---------------- */
          <>
            <div className="bg-bg-panel border border-border-main rounded-xl p-4 shadow-sm">
              <div className="grid grid-cols-1 md:grid-cols-12 gap-3 items-end">
                <div className="md:col-span-4">
                  <label className="block text-[11px] font-semibold text-text-muted uppercase tracking-wider mb-1">
                    Nama Data
                  </label>
                  <select
                    value={monKodeNama}
                    onChange={(e) => setMonKodeNama(e.target.value)}
                    className="w-full h-9 px-3 rounded-lg bg-bg-editor border border-border-main text-xs text-text-main focus:outline-none focus:ring-2 focus:ring-indigo-500/40"
                  >
                    {NAMA_DATA_OPTIONS.map((opt) => (
                      <option key={opt.kode_nama} value={opt.kode_nama}>
                        {opt.kode_nama} — {opt.nama_data}
                      </option>
                    ))}
                  </select>
                </div>

                <div className="md:col-span-6">
                  <label className="block text-[11px] font-semibold text-text-muted uppercase tracking-wider mb-1">
                    Perusahaan
                  </label>
                  <select
                    value={monCompany?.kode_perusahaan || ''}
                    onChange={(e) => {
                      const comp = companies.find((c) => c.kode_perusahaan === e.target.value) || null;
                      setMonCompany(comp);
                    }}
                    className="w-full h-9 px-3 rounded-lg bg-bg-editor border border-border-main text-xs text-text-main focus:outline-none focus:ring-2 focus:ring-indigo-500/40"
                  >
                    <option value="">-- Pilih Perusahaan --</option>
                    {companies.map((c) => (
                      <option key={c.kode_perusahaan} value={c.kode_perusahaan}>
                        {c.nama_singkat} — {c.nama_perusahaan}
                      </option>
                    ))}
                  </select>
                </div>

                <div className="md:col-span-2">
                  <button
                    onClick={handleLoadMonitoring}
                    disabled={loadingMon || !monCompany}
                    className="w-full h-9 px-4 rounded-lg bg-indigo-600 hover:bg-indigo-700 text-white text-xs font-semibold flex items-center justify-center gap-2 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
                  >
                    {loadingMon ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Search className="w-3.5 h-3.5" />}
                    OK / Tampilkan
                  </button>
                </div>
              </div>
            </div>

            <div className="bg-bg-panel border-2 border-sky-500/50 rounded-xl overflow-hidden flex flex-col shadow-sm min-h-[520px]">
              <div className="px-4 py-2.5 bg-sky-600 text-white flex flex-wrap items-center justify-between gap-2 shrink-0">
                <div className="flex items-center gap-2">
                  <Database className="w-4 h-4" />
                  <span className="text-xs font-semibold">Data Konsolidasi (Klik baris untuk koreksi)</span>
                  <span className="text-[10px] px-1.5 py-0.5 rounded bg-black/25 font-mono">
                    {filteredMonRows.length}
                  </span>
                </div>
                <div className="relative">
                  <Search className="w-3 h-3 absolute left-2.5 top-1/2 -translate-y-1/2 text-sky-200" />
                  <input
                    type="text"
                    value={monSearch}
                    onChange={(e) => setMonSearch(e.target.value)}
                    placeholder="Cari pelanggan..."
                    className="h-7 pl-7 pr-3 rounded bg-black/20 text-xs text-white placeholder-sky-200 focus:outline-none focus:ring-1 focus:ring-white/40 w-56"
                  />
                </div>
              </div>

              <div className="flex-1 overflow-auto">
                {loadingMon ? (
                  <div className="h-64 flex items-center justify-center text-xs text-text-muted gap-2">
                    <Loader2 className="w-4 h-4 animate-spin text-sky-500" />
                    Memuat data monitoring...
                  </div>
                ) : filteredMonRows.length === 0 ? (
                  <div className="h-64 flex flex-col items-center justify-center text-xs text-text-muted">
                    <Database className="w-8 h-8 mb-2 opacity-30" />
                    Pilih perusahaan dan klik OK untuk menampilkan data konsolidasi.
                  </div>
                ) : (
                  <table className="w-full text-[11px] border-collapse">
                    <thead className="sticky top-0 bg-bg-editor border-b border-border-main z-10">
                      <tr>
                        <th className="px-2.5 py-2 text-left font-semibold text-text-muted whitespace-nowrap border-r border-border-main/50">
                          AKSI
                        </th>
                        {MONITORING_COLUMNS.map((col) => (
                          <th
                            key={col.value}
                            className="px-2.5 py-2 text-left font-semibold text-text-muted whitespace-nowrap border-r border-border-main/50"
                          >
                            {col.label}
                          </th>
                        ))}
                      </tr>
                    </thead>
                    <tbody>
                      {filteredMonRows.map((row, idx) => (
                        <tr
                          key={`${row.id_langganan}-${idx}`}
                          onClick={() => setEditingMonRow({ ...row })}
                          className="cursor-pointer border-b border-border-main/40 hover:bg-sky-500/10 transition-colors"
                        >
                          <td className="px-2.5 py-1.5 whitespace-nowrap border-r border-border-main/30">
                            <span className="inline-flex items-center gap-1 text-indigo-500 font-medium">
                              <Edit3 className="w-3 h-3" /> Koreksi
                            </span>
                          </td>
                          {MONITORING_COLUMNS.map((col) => (
                            <td
                              key={col.value}
                              className="px-2.5 py-1.5 whitespace-nowrap max-w-[240px] truncate border-r border-border-main/30"
                            >
                              {String(row[col.value] ?? '')}
                            </td>
                          ))}
                        </tr>
                      ))}
                    </tbody>
                  </table>
                )}
              </div>
            </div>
          </>
        )}
      </div>

      {/* Confirmation Modal (New / Match) */}
      {confirmModal && (
        <div className="fixed inset-0 z-50 bg-black/50 backdrop-blur-xs flex items-center justify-center p-4">
          <div className="bg-bg-panel border border-border-main rounded-xl max-w-md w-full p-5 shadow-2xl space-y-4">
            <div className="flex items-center justify-between">
              <h3 className="text-sm font-bold">{confirmModal.title}</h3>
              <button onClick={() => setConfirmModal(null)} className="text-text-muted hover:text-text-main">
                <X className="w-4 h-4" />
              </button>
            </div>
            <p className="text-xs text-text-muted leading-relaxed">{confirmModal.message}</p>
            <div className="flex justify-end gap-2 pt-2">
              <button
                onClick={() => setConfirmModal(null)}
                className="px-3.5 py-1.5 rounded-lg border border-border-main text-xs font-medium hover:bg-bg-editor"
              >
                Batal
              </button>
              <button
                disabled={savingAction}
                onClick={() => executeKonsolidasiAction(confirmModal.action)}
                className="px-4 py-1.5 rounded-lg bg-indigo-600 hover:bg-indigo-700 text-white text-xs font-semibold flex items-center gap-1.5"
              >
                {savingAction && <Loader2 className="w-3.5 h-3.5 animate-spin" />}
                Ya, Lanjutkan
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Koreksi Modal (Monitoring Data) */}
      {editingMonRow && (
        <div className="fixed inset-0 z-50 bg-black/50 backdrop-blur-xs flex items-center justify-center p-4">
          <div className="bg-bg-panel border border-border-main rounded-xl max-w-4xl w-full max-h-[90vh] flex flex-col shadow-2xl overflow-hidden">
            <div className="px-5 py-3.5 bg-sky-600 text-white flex items-center justify-between shrink-0">
              <div className="flex items-center gap-2">
                <Edit3 className="w-4 h-4" />
                <h3 className="text-sm font-bold">Data Konsolidasi — Koreksi ({editingMonRow.id_langganan})</h3>
              </div>
              <button onClick={() => setEditingMonRow(null)} className="text-white/80 hover:text-white">
                <X className="w-4 h-4" />
              </button>
            </div>

            <div className="p-5 overflow-y-auto grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-3">
              {MONITORING_DETAIL_ORDER.map((field) => {
                const colDef = MONITORING_COLUMNS.find((c) => c.value === field);
                if (!colDef) return null;
                const isId = field === 'id_langganan';
                return (
                  <div key={field}>
                    <label className="block text-[10px] font-semibold text-text-muted mb-1">
                      {colDef.label}
                    </label>
                    <input
                      type="text"
                      readOnly={isId}
                      disabled={isId}
                      value={String(editingMonRow[field] ?? '')}
                      onChange={(e) =>
                        setEditingMonRow((prev) => (prev ? { ...prev, [field]: e.target.value } : prev))
                      }
                      className={clsx(
                        'w-full h-8 px-2.5 rounded-md border border-border-main text-xs text-text-main',
                        isId ? 'bg-bg-main opacity-70 cursor-not-allowed' : 'bg-bg-editor focus:outline-none focus:ring-2 focus:ring-sky-500/40'
                      )}
                    />
                  </div>
                );
              })}
            </div>

            <div className="px-5 py-3 bg-bg-editor border-t border-border-main flex justify-end gap-2 shrink-0">
              <button
                onClick={() => setEditingMonRow(null)}
                className="px-4 py-1.5 rounded-lg border border-border-main text-xs font-medium hover:bg-bg-main"
              >
                Tutup
              </button>
              <button
                disabled={savingMon}
                onClick={handleSaveKoreksi}
                className="px-4 py-1.5 rounded-lg bg-sky-600 hover:bg-sky-700 text-white text-xs font-semibold flex items-center gap-1.5 disabled:opacity-50"
              >
                {savingMon && <Loader2 className="w-3.5 h-3.5 animate-spin" />}
                Simpan
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
