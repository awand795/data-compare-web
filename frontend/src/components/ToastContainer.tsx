// @ts-nocheck
import React, { useEffect, useRef, useState } from 'react';
import { useAppStore, type Toast } from '../store/useAppStore';
import { X, CheckCircle2, AlertCircle, AlertTriangle, Info } from 'lucide-react';
import clsx from 'clsx';

/* ── Individual Toast Item ───────────────────────────────────── */

const ToastItem: React.FC<{ toast: Toast; onDismiss: (id: string) => void }> = ({ toast, onDismiss }) => {
  const [exiting, setExiting] = useState(false);
  const [progress, setProgress] = useState(100);
  const startTimeRef = useRef(Date.now());
  const rafRef = useRef<number>();
  const duration = toast.duration ?? 5000;

  const handleDismiss = () => {
    setExiting(true);
    setTimeout(() => onDismiss(toast.id), 300);
  };

  // Auto-dismiss animation
  useEffect(() => {
    if (duration <= 0) return;

    const animate = () => {
      const elapsed = Date.now() - startTimeRef.current;
      const remaining = Math.max(0, 100 - (elapsed / duration) * 100);
      setProgress(remaining);
      if (remaining > 0) {
        rafRef.current = requestAnimationFrame(animate);
      } else {
        handleDismiss();
      }
    };
    rafRef.current = requestAnimationFrame(animate);
    return () => {
      if (rafRef.current) cancelAnimationFrame(rafRef.current);
    };
  }, [duration]);

  // Pause on hover
  const handleMouseEnter = () => {
    if (rafRef.current) cancelAnimationFrame(rafRef.current);
  };

  const handleMouseLeave = () => {
    startTimeRef.current = Date.now() - (duration * (100 - progress)) / 100;
    const animate = () => {
      const elapsed = Date.now() - startTimeRef.current;
      const remaining = Math.max(0, 100 - (elapsed / duration) * 100);
      setProgress(remaining);
      if (remaining > 0) {
        rafRef.current = requestAnimationFrame(animate);
      } else {
        handleDismiss();
      }
    };
    rafRef.current = requestAnimationFrame(animate);
  };

  const config = {
    success: {
      icon: CheckCircle2,
      border: 'border-emerald-500/30',
      glow: 'shadow-emerald-500/10',
      bg: 'from-emerald-500/10 to-emerald-500/5',
      bar: 'bg-emerald-400',
      text: 'text-emerald-400',
    },
    error: {
      icon: AlertCircle,
      border: 'border-red-500/30',
      glow: 'shadow-red-500/10',
      bg: 'from-red-500/10 to-red-500/5',
      bar: 'bg-red-400',
      text: 'text-red-400',
    },
    warning: {
      icon: AlertTriangle,
      border: 'border-amber-500/30',
      glow: 'shadow-amber-500/10',
      bg: 'from-amber-500/10 to-amber-500/5',
      bar: 'bg-amber-400',
      text: 'text-amber-400',
    },
    info: {
      icon: Info,
      border: 'border-blue-500/30',
      glow: 'shadow-blue-500/10',
      bg: 'from-blue-500/10 to-blue-500/5',
      bar: 'bg-blue-400',
      text: 'text-blue-400',
    },
  }[toast.type];

  const IconComp = config.icon;

  return (
    <div
      onMouseEnter={handleMouseEnter}
      onMouseLeave={handleMouseLeave}
      className={clsx(
        'relative w-[380px] overflow-hidden rounded-2xl border backdrop-blur-xl bg-gradient-to-br shadow-xl transition-all duration-300',
        config.border,
        config.glow,
        config.bg,
        'dark:bg-gray-900/90 dark:backdrop-blur-xl',
        exiting ? 'opacity-0 translate-x-8 scale-95' : 'opacity-100 translate-x-0 scale-100',
      )}
    >
      {/* Progress bar */}
      {duration > 0 && (
        <div className="absolute bottom-0 left-0 right-0 h-0.5 bg-white/10 dark:bg-white/5">
          <div
            className={clsx('h-full rounded-full transition-all duration-150 ease-linear', config.bar)}
            style={{ width: `${progress}%` }}
          />
        </div>
      )}

      <div className="flex items-start gap-3 p-4 pb-3.5">
        {/* Icon */}
        <div className={clsx('w-9 h-9 rounded-xl flex items-center justify-center shrink-0 bg-white/10 dark:bg-white/5', config.text)}>
          <IconComp className="w-5 h-5" />
        </div>

        {/* Content */}
        <div className="flex-1 min-w-0 pt-0.5">
          <p className="text-sm font-bold text-gray-900 dark:text-white leading-tight">
            {toast.title}
          </p>
          {toast.message && (
            <p className="text-xs text-gray-500 dark:text-gray-400 mt-1 leading-relaxed line-clamp-2">
              {toast.message}
            </p>
          )}
        </div>

        {/* Close */}
        <button
          onClick={handleDismiss}
          className="p-1 rounded-lg text-gray-400 hover:text-gray-600 dark:hover:text-gray-200 hover:bg-white/10 dark:hover:bg-white/5 transition-all shrink-0 -mr-1 -mt-1"
        >
          <X className="w-3.5 h-3.5" />
        </button>
      </div>
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
    <div className="fixed top-4 right-4 z-[9999] flex flex-col gap-3 pointer-events-none">
      {toasts.map((toast) => (
        <div key={toast.id} className="pointer-events-auto">
          <ToastItem toast={toast} onDismiss={removeToast} />
        </div>
      ))}
    </div>
  );
};
