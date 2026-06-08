// @ts-nocheck
import React, { useEffect, useState } from 'react';
import { useAppStore, type Toast } from '../store/useAppStore';
import { X, CheckCircle2, AlertCircle, AlertTriangle, Info } from 'lucide-react';
import clsx from 'clsx';

/* ── Individual Toast Item ───────────────────────────────────── */

const ToastItem: React.FC<{ toast: Toast; onDismiss: (id: string) => void }> = ({ toast, onDismiss }) => {
  const [exiting, setExiting] = useState(false);

  const handleDismiss = () => {
    setExiting(true);
    setTimeout(() => onDismiss(toast.id), 200);
  };

  useEffect(() => {
    const duration = toast.duration ?? 4000;
    if (duration <= 0) return;
    const timer = setTimeout(handleDismiss, duration);
    return () => clearTimeout(timer);
  }, [toast.id, toast.duration]);

  const config = {
    success: { icon: CheckCircle2, bg: 'bg-emerald-600', ring: 'ring-emerald-500/30' },
    error:   { icon: AlertCircle,    bg: 'bg-red-600',     ring: 'ring-red-500/30' },
    warning: { icon: AlertTriangle,  bg: 'bg-amber-600',   ring: 'ring-amber-500/30' },
    info:    { icon: Info,           bg: 'bg-blue-600',    ring: 'ring-blue-500/30' },
  }[toast.type];

  const IconComp = config.icon;

  return (
    <div
      className={clsx(
        'flex items-start gap-3 w-[360px] p-3.5 rounded-xl shadow-lg border border-white/10',
        'bg-gray-900/95 backdrop-blur-md text-white',
        'transition-all duration-200',
        exiting ? 'opacity-0 translate-x-4 scale-95' : 'opacity-100 translate-x-0 scale-100',
      )}
    >
      <div className={clsx('w-8 h-8 rounded-lg flex items-center justify-center shrink-0', config.bg)}>
        <IconComp className="w-4.5 h-4.5" />
      </div>
      <div className="flex-1 min-w-0 pt-0.5">
        {toast.title && (
          <p className="text-sm font-semibold leading-tight">{toast.title}</p>
        )}
        {toast.message && (
          <p className="text-xs text-gray-300/80 mt-0.5 leading-relaxed line-clamp-2">{toast.message}</p>
        )}
      </div>
      <button
        onClick={handleDismiss}
        className="p-0.5 rounded-md text-gray-400 hover:text-white hover:bg-white/10 transition-colors shrink-0 -mr-0.5 -mt-0.5"
      >
        <X className="w-3.5 h-3.5" />
      </button>
    </div>
  );
};

/* ── Toast Container ─────────────────────────────────────────── */

export const ToastContainer: React.FC = () => {
  const store = useAppStore();
  const toasts = store.toasts || [];
  const removeToast = store.removeToast || (() => {});

  if (toasts.length === 0) return null;

  return (
    <div className="fixed bottom-4 right-4 z-[9999] flex flex-col-reverse gap-2 pointer-events-none">
      {toasts.map((toast) => (
        <div key={toast.id} className="pointer-events-auto">
          <ToastItem toast={toast} onDismiss={removeToast} />
        </div>
      ))}
    </div>
  );
};
