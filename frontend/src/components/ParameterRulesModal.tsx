import React, { useState } from 'react';
import { X, Check, SlidersHorizontal, Sparkles, AlertCircle, Trash2 } from 'lucide-react';
import clsx from 'clsx';
import type { ApiParameter } from './ApiBuilderView';

interface ParameterRulesModalProps {
  param: ApiParameter;
  onClose: () => void;
  onSave: (updatedParam: ApiParameter) => void;
}

const REGEX_PRESETS = [
  { label: 'Email Address', regex: '^[A-Za-z0-9+_.-]+@(.+)$', desc: 'Validasi format email standar' },
  { label: 'No. HP Indo (08/62)', regex: '^(08|\\+628)[0-9]{8,12}$', desc: 'Contoh: 081234567890 / +6281234567890' },
  { label: 'Angka Saja (Digits)', regex: '^\\d+$', desc: 'Hanya karakter angka 0-9' },
  { label: 'Huruf & Spasi', regex: '^[a-zA-Z\\s]+$', desc: 'Hanya huruf alphabet dan spasi' },
  { label: 'Plat Nomor Fleet (BK-xxxx-XX)', regex: '^[A-Z]{1,2}-[0-9]{1,4}-[A-Z]{1,3}$', desc: 'Format plat nomor kendaraan bengkel' },
  { label: 'UUID', regex: '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$', desc: 'Format standard UUID v4' },
  { label: 'Format Tanggal (YYYY-MM-DD)', regex: '^\\d{4}-\\d{2}-\\d{2}$', desc: 'Format kalender ISO YYYY-MM-DD' },
  { label: 'Alphanumeric & Underscore', regex: '^[a-zA-Z0-9_]+$', desc: 'Huruf, angka, atau underscore' },
];

export const ParameterRulesModal: React.FC<ParameterRulesModalProps> = ({ param, onClose, onSave }) => {
  const [pattern, setPattern] = useState(param.pattern || '');
  const [minLength, setMinLength] = useState<string>(param.minLength !== undefined ? String(param.minLength) : '');
  const [maxLength, setMaxLength] = useState<string>(param.maxLength !== undefined ? String(param.maxLength) : '');
  const [min, setMin] = useState<string>(param.min !== undefined ? String(param.min) : '');
  const [max, setMax] = useState<string>(param.max !== undefined ? String(param.max) : '');
  const [allowedValuesInput, setAllowedValuesInput] = useState(
    Array.isArray(param.allowedValues) ? param.allowedValues.join(', ') : ''
  );
  const [transform, setTransform] = useState<'none' | 'trim' | 'uppercase' | 'lowercase'>(
    param.transform || 'none'
  );
  const [customErrorMessage, setCustomErrorMessage] = useState(param.customErrorMessage || '');

  const handleApply = () => {
    const parsedAllowed = allowedValuesInput
      .split(',')
      .map(v => v.trim())
      .filter(Boolean);

    const updated: ApiParameter = {
      ...param,
      pattern: pattern.trim() || undefined,
      minLength: minLength !== '' ? parseInt(minLength, 10) : undefined,
      maxLength: maxLength !== '' ? parseInt(maxLength, 10) : undefined,
      min: min !== '' ? parseFloat(min) : undefined,
      max: max !== '' ? parseFloat(max) : undefined,
      allowedValues: parsedAllowed.length > 0 ? parsedAllowed : undefined,
      transform: transform !== 'none' ? transform : undefined,
      customErrorMessage: customErrorMessage.trim() || undefined,
    };

    onSave(updated);
  };

  const handleClearAll = () => {
    setPattern('');
    setMinLength('');
    setMaxLength('');
    setMin('');
    setMax('');
    setAllowedValuesInput('');
    setTransform('none');
    setCustomErrorMessage('');
  };

  const isNumericType = param.type === 'integer' || param.type === 'number';
  const isStringType = param.type === 'string' || param.type === 'date';

  return (
    <div className="fixed inset-0 bg-black/75 backdrop-blur-sm z-50 flex items-center justify-center p-4 animate-in fade-in duration-200">
      <div className="bg-bg-panel border border-border-main rounded-2xl max-w-2xl w-full shadow-2xl overflow-hidden flex flex-col max-h-[90vh]">
        
        {/* Modal Header */}
        <div className="bg-bg-editor/80 border-b border-border-main px-6 py-4 flex items-center justify-between shrink-0">
          <div className="flex items-center gap-3">
            <div className="w-9 h-9 rounded-xl bg-purple-500/15 border border-purple-500/30 flex items-center justify-center text-purple-400 shadow-inner">
              <SlidersHorizontal className="w-5 h-5" />
            </div>
            <div>
              <div className="flex items-center gap-2">
                <h3 className="text-base font-bold text-text-main">Validation &amp; Business Rules</h3>
                <span className="font-mono font-bold text-xs text-purple-400 bg-purple-500/10 px-2 py-0.5 rounded-lg border border-purple-500/20">
                  :{param.name}
                </span>
                <span className="text-[10px] font-bold uppercase tracking-wider text-text-muted bg-bg-editor px-2 py-0.5 rounded border border-border-main">
                  {param.type}
                </span>
              </div>
              <p className="text-xs text-text-muted mt-0.5">
                Konfigurasi aturan validasi input backend, transformasi otomatis, dan custom error messages.
              </p>
            </div>
          </div>
          <button
            onClick={onClose}
            className="text-text-muted hover:text-text-main p-1.5 hover:bg-bg-hover rounded-xl transition-colors cursor-pointer"
          >
            <X className="w-5 h-5" />
          </button>
        </div>

        {/* Modal Body */}
        <div className="p-6 overflow-y-auto space-y-6 flex-1 text-xs">

          {/* 1. Value Transformation */}
          <div className="bg-bg-editor/50 border border-border-main rounded-xl p-4 space-y-2.5 shadow-sm">
            <div className="flex items-center justify-between">
              <label className="font-bold text-text-main flex items-center gap-1.5 text-xs">
                <Sparkles className="w-3.5 h-3.5 text-amber-400" /> Auto Transformasi Input Sebelum Validasi
              </label>
              <span className="text-[10px] text-text-muted">Sanitisasi data otomatis</span>
            </div>
            <div className="grid grid-cols-2 sm:grid-cols-4 gap-2">
              {[
                { id: 'none', label: 'None (Asli)', desc: 'Tidak diubah' },
                { id: 'trim', label: 'Trim Spasi', desc: 'Hapus spasi samping' },
                { id: 'uppercase', label: 'UPPERCASE', desc: 'HURUF BESAR' },
                { id: 'lowercase', label: 'lowercase', desc: 'huruf kecil' },
              ].map(opt => (
                <button
                  key={opt.id}
                  type="button"
                  onClick={() => setTransform(opt.id as any)}
                  className={clsx(
                    'p-2.5 rounded-xl border text-left transition-all cursor-pointer flex flex-col justify-between',
                    transform === opt.id
                      ? 'bg-amber-500/15 dark:bg-amber-500/20 border-amber-500 text-amber-800 dark:text-amber-300 font-bold shadow-sm'
                      : 'bg-bg-panel border-border-main text-text-muted hover:text-text-main hover:bg-bg-hover'
                  )}
                >
                  <span className="text-xs block font-bold">{opt.label}</span>
                  <span className="text-[10px] opacity-80 mt-0.5">{opt.desc}</span>
                </button>
              ))}
            </div>
          </div>

          {/* 2. Regex Pattern Matching (For String/Date) */}
          {isStringType && (
            <div className="bg-bg-editor/50 border border-border-main rounded-xl p-4 space-y-3 shadow-sm">
              <div>
                <label className="font-bold text-text-main block mb-1 text-xs">
                  Regex Pattern Match
                </label>
                <input
                  type="text"
                  placeholder="e.g. ^[A-Z0-9_-]{3,16}$"
                  value={pattern}
                  onChange={e => setPattern(e.target.value)}
                  className="w-full bg-bg-panel border border-border-main focus:border-purple-500 focus:ring-1 focus:ring-purple-500/30 rounded-xl px-3 py-2 text-xs font-mono text-text-main dark:text-purple-300 outline-none shadow-inner"
                />
              </div>

              {/* Presets */}
              <div>
                <span className="text-[10px] font-bold uppercase tracking-wider text-text-muted block mb-1.5">
                  Pilihan Template Regex:
                </span>
                <div className="flex flex-wrap gap-1.5">
                  {REGEX_PRESETS.map((preset, idx) => (
                    <button
                      key={idx}
                      type="button"
                      title={preset.desc}
                      onClick={() => setPattern(preset.regex)}
                      className="px-2.5 py-1 rounded-lg bg-bg-panel hover:bg-purple-500/20 text-text-muted hover:text-purple-700 dark:hover:text-purple-300 border border-border-main hover:border-purple-500/40 text-[11px] font-medium transition-colors cursor-pointer"
                    >
                      {preset.label}
                    </button>
                  ))}
                  {pattern && (
                    <button
                      type="button"
                      onClick={() => setPattern('')}
                      className="px-2 py-1 rounded-lg bg-rose-500/10 hover:bg-rose-500/20 text-rose-400 text-[10px] font-bold cursor-pointer transition-colors border border-rose-500/20"
                    >
                      Hapus Regex
                    </button>
                  )}
                </div>
              </div>
            </div>
          )}

          {/* 3. Length Constraints (String) OR Range Constraints (Number) */}
          {isStringType && (
            <div className="grid grid-cols-2 gap-3 bg-bg-editor/50 border border-border-main rounded-xl p-4 shadow-sm">
              <div>
                <label className="font-bold text-text-main block mb-1 text-xs">
                  Panjang Minimum (Min Length)
                </label>
                <input
                  type="number"
                  min="0"
                  placeholder="Contoh: 3"
                  value={minLength}
                  onChange={e => setMinLength(e.target.value)}
                  className="w-full bg-bg-panel border border-border-main focus:border-purple-500 rounded-xl px-3 py-2 text-xs font-mono outline-none shadow-inner text-text-main"
                />
              </div>
              <div>
                <label className="font-bold text-text-main block mb-1 text-xs">
                  Panjang Maksimum (Max Length)
                </label>
                <input
                  type="number"
                  min="0"
                  placeholder="Contoh: 100"
                  value={maxLength}
                  onChange={e => setMaxLength(e.target.value)}
                  className="w-full bg-bg-panel border border-border-main focus:border-purple-500 rounded-xl px-3 py-2 text-xs font-mono outline-none shadow-inner text-text-main"
                />
              </div>
            </div>
          )}

          {isNumericType && (
            <div className="grid grid-cols-2 gap-3 bg-bg-editor/50 border border-border-main rounded-xl p-4 shadow-sm">
              <div>
                <label className="font-bold text-text-main block mb-1 text-xs">
                  Nilai Minimum (Min Value)
                </label>
                <input
                  type="number"
                  placeholder="Contoh: 1"
                  value={min}
                  onChange={e => setMin(e.target.value)}
                  className="w-full bg-bg-panel border border-border-main focus:border-purple-500 rounded-xl px-3 py-2 text-xs font-mono outline-none shadow-inner text-text-main"
                />
              </div>
              <div>
                <label className="font-bold text-text-main block mb-1 text-xs">
                  Nilai Maksimum (Max Value)
                </label>
                <input
                  type="number"
                  placeholder="Contoh: 1000"
                  value={max}
                  onChange={e => setMax(e.target.value)}
                  className="w-full bg-bg-panel border border-border-main focus:border-purple-500 rounded-xl px-3 py-2 text-xs font-mono outline-none shadow-inner text-text-main"
                />
              </div>
            </div>
          )}

          {/* 4. Allowed Values / Enum */}
          <div className="bg-bg-editor/50 border border-border-main rounded-xl p-4 space-y-2 shadow-sm">
            <label className="font-bold text-text-main block text-xs">
              Allowed Values / Enums (Daftar Nilai yang Diizinkan)
            </label>
            <input
              type="text"
              placeholder="Contoh: CHECK_IN, PROCESS, DONE, CANCELLED (pisahkan dengan koma)"
              value={allowedValuesInput}
              onChange={e => setAllowedValuesInput(e.target.value)}
              className="w-full bg-bg-panel border border-border-main focus:border-purple-500 rounded-xl px-3 py-2 text-xs font-mono text-text-main dark:text-purple-300 outline-none shadow-inner"
            />
            <span className="text-[11px] text-text-muted block">
              Jika diisi, request dengan nilai di luar daftar ini akan otomatis ditolak oleh backend validator.
            </span>
          </div>

          {/* 5. Custom Error Message */}
          <div className="bg-bg-editor/50 border border-border-main rounded-xl p-4 space-y-2 shadow-sm">
            <div className="flex items-center justify-between">
              <label className="font-bold text-text-main block text-xs">
                Pesan Error Kustom (Custom Error Message)
              </label>
              <span className="text-[10px] text-emerald-700 dark:text-emerald-400 font-bold bg-emerald-500/10 px-2 py-0.5 rounded border border-emerald-500/20">
                Default Cerdas Aktif
              </span>
            </div>
            <input
              type="text"
              placeholder="Contoh: Nomor lambung armada harus berformat BK-XXXX-XX!"
              value={customErrorMessage}
              onChange={e => setCustomErrorMessage(e.target.value)}
              className="w-full bg-bg-panel border border-border-main focus:border-purple-500 rounded-xl px-3 py-2 text-xs text-text-main outline-none shadow-inner placeholder:text-text-muted"
            />
            <div className="p-2.5 rounded-lg bg-bg-panel/80 border border-border-main text-[11px] text-text-muted flex items-start gap-2">
              <AlertCircle className="w-4 h-4 text-purple-400 shrink-0 mt-0.5" />
              <span>
                <strong>Catatan:</strong> Jika kolom pesan error kustom ini dibiarkan kosong, backend DarkoSync otomatis menampilkan pesan default bahasa Indonesia yang ramah, misalnya: <em>"Format parameter :{param.name} tidak valid."</em> atau <em>"Nilai :{param.name} harus antara X dan Y."</em>
              </span>
            </div>
          </div>

        </div>

        {/* Modal Footer */}
        <div className="bg-bg-editor/80 border-t border-border-main px-6 py-3.5 flex items-center justify-between shrink-0">
          <button
            type="button"
            onClick={handleClearAll}
            className="flex items-center gap-1.5 px-3 py-2 rounded-xl text-xs font-bold text-text-muted hover:text-rose-400 hover:bg-rose-500/10 transition-colors border border-transparent hover:border-rose-500/20 cursor-pointer"
          >
            <Trash2 className="w-3.5 h-3.5" />
            <span>Reset Aturan</span>
          </button>

          <div className="flex items-center gap-2.5">
            <button
              type="button"
              onClick={onClose}
              className="px-4 py-2 rounded-xl text-xs font-bold text-text-muted hover:text-text-main hover:bg-bg-hover transition-colors border border-border-main cursor-pointer"
            >
              Batal
            </button>
            <button
              type="button"
              onClick={handleApply}
              className="flex items-center gap-1.5 px-5 py-2 rounded-xl text-xs font-bold text-white bg-gradient-to-r from-purple-600 to-indigo-600 hover:from-purple-500 hover:to-indigo-500 transition-all shadow-md shadow-purple-500/20 cursor-pointer active:scale-95"
            >
              <Check className="w-4 h-4" />
              <span>Simpan Aturan Validasi</span>
            </button>
          </div>
        </div>

      </div>
    </div>
  );
};
