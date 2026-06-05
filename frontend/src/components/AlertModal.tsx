// @ts-nocheck
import React, { useEffect } from 'react';
import { useAppStore } from '../store/useAppStore';
import { X, AlertTriangle, CheckCircle2, Info, AlertOctagon, ChevronDown, ChevronUp } from 'lucide-react';
import clsx from 'clsx';

export const AlertModal: React.FC = () => {
  const { alert, hideAlert } = useAppStore();
  const [showDetails, setShowDetails] = React.useState(false);

  // Reset showDetails whenever alert changes
  useEffect(() => {
    setShowDetails(false);
  }, [alert]);

  // Handle keyboard Events (Esc to close)
  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape' && alert?.isOpen) {
        hideAlert();
      }
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [alert, hideAlert]);

  if (!alert || !alert.isOpen) return null;

  const { title, message, type, details, confirmLabel, cancelLabel, onConfirm, onCancel } = alert;

  // Determine themes
  const config = {
    error: {
      icon: AlertOctagon,
      iconClass: 'text-red-500 bg-red-500/10 border-red-500/20',
      btnClass: 'bg-red-600 hover:bg-red-500 focus:ring-red-500/30 text-white',
    },
    warning: {
      icon: AlertTriangle,
      iconClass: 'text-amber-500 bg-amber-500/10 border-amber-500/20',
      btnClass: 'bg-amber-600 hover:bg-amber-500 focus:ring-amber-500/30 text-white',
    },
    success: {
      icon: CheckCircle2,
      iconClass: 'text-emerald-500 bg-emerald-500/10 border-emerald-500/20',
      btnClass: 'bg-emerald-600 hover:bg-emerald-500 focus:ring-emerald-500/30 text-white',
    },
    info: {
      icon: Info,
      iconClass: 'text-blue-500 bg-blue-500/10 border-blue-500/20',
      btnClass: 'bg-blue-600 hover:bg-blue-500 focus:ring-blue-500/30 text-white',
    },
  }[type];

  const IconComponent = config.icon;

  const handleConfirm = () => {
    if (onConfirm) onConfirm();
    hideAlert();
  };

  const handleCancel = () => {
    if (onCancel) onCancel();
    hideAlert();
  };

  return (
    <div className="fixed inset-0 z-[200] flex items-center justify-center bg-black/60 backdrop-blur-sm p-4 animate-in fade-in duration-200">
      {/* Backdrop click to dismiss if not a strict confirmation */}
      <div className="absolute inset-0" onClick={onConfirm ? undefined : handleCancel} />

      <div className="bg-bg-panel border border-border-main rounded-2xl shadow-2xl w-full max-w-md overflow-hidden flex flex-col animate-in fade-in zoom-in-95 duration-200 relative z-10">
        
        {/* Close Button */}
        <button
          onClick={handleCancel}
          className="absolute top-4 right-4 p-1.5 text-text-muted hover:text-text-main rounded-lg hover:bg-bg-hover transition-colors"
        >
          <X className="w-4 h-4" />
        </button>

        {/* Modal Header & Content */}
        <div className="p-6 pb-4 flex gap-4">
          <div className={clsx("w-10 h-10 rounded-xl flex items-center justify-center border shrink-0", config.iconClass)}>
            <IconComponent className="w-5 h-5" />
          </div>
          
          <div className="flex-1 min-w-0">
            <h3 className="text-base font-bold text-text-main tracking-tight leading-6">
              {title}
            </h3>
            <p className="text-[13px] text-text-muted leading-relaxed mt-1.5 whitespace-pre-wrap">
              {message}
            </p>

            {/* Collapsible Details */}
            {details && (
              <div className="mt-3.5">
                <button
                  onClick={() => setShowDetails(!showDetails)}
                  className="flex items-center gap-1 text-[11px] font-semibold text-blue-500 dark:text-blue-400 hover:underline transition-all"
                >
                  {showDetails ? (
                    <>
                      <ChevronUp className="w-3.5 h-3.5" /> Hide raw details
                    </>
                  ) : (
                    <>
                      <ChevronDown className="w-3.5 h-3.5" /> View raw details
                    </>
                  )}
                </button>
                {showDetails && (
                  <div className="mt-2 p-3 bg-bg-editor border border-border-item rounded-lg font-mono text-[11px] text-text-main max-h-48 overflow-y-auto whitespace-pre-wrap select-text leading-normal scrollbar-thin">
                    {details}
                  </div>
                )}
              </div>
            )}
          </div>
        </div>

        {/* Footer Actions */}
        <div className="px-6 py-4 bg-bg-header border-t border-border-main flex justify-end gap-2 shrink-0">
          {onConfirm ? (
            <>
              <button
                onClick={handleCancel}
                className="px-4 py-2 border border-border-input hover:bg-bg-hover text-text-muted hover:text-text-main rounded-lg text-xs font-semibold transition-colors"
              >
                {cancelLabel || 'Cancel'}
              </button>
              <button
                onClick={handleConfirm}
                className={clsx("px-4 py-2 rounded-lg text-xs font-semibold focus:outline-none focus:ring-2 transition-colors", config.btnClass)}
              >
                {confirmLabel || 'Confirm'}
              </button>
            </>
          ) : (
            <button
              onClick={handleCancel}
              className={clsx("px-4 py-2 rounded-lg text-xs font-semibold focus:outline-none focus:ring-2 transition-colors w-24", config.btnClass)}
            >
              OK
            </button>
          )}
        </div>

      </div>
    </div>
  );
};
