// @ts-nocheck
import React, { useState } from 'react';
import { useAppStore } from '../store/useAppStore';
import { X, Settings2, Palette, Database, ListFilter, ArrowRight } from 'lucide-react';
import clsx from 'clsx';

export const SettingsModal: React.FC<{ onClose: () => void }> = ({ onClose }) => {
  const { 
    theme, setTheme, 
    fontSize, setFontSize,
    gridDensity, setGridDensity,
    defaultRowLimit, setDefaultRowLimit,
    defaultFetchSize, setDefaultFetchSize
  } = useAppStore();
  const [activeTab, setActiveTab] = useState<'general' | 'appearance' | 'advanced'>('general');

  // Dummy states for features we might implement later
  const [ignoreCase, setIgnoreCase] = useState(false);
  const [trimWhitespace, setTrimWhitespace] = useState(true);

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm p-4">
      <div className="bg-bg-panel border border-border-main rounded-xl shadow-2xl w-full max-w-2xl overflow-hidden flex flex-col max-h-[85vh] animate-in fade-in zoom-in-95 duration-200">
        
        {/* Header */}
        <div className="px-6 py-4 border-b border-border-main flex items-center justify-between bg-bg-header shrink-0">
          <div className="flex items-center gap-3">
            <div className="p-2 bg-blue-500/10 rounded-lg">
              <Settings2 className="w-5 h-5 text-blue-500" />
            </div>
            <h2 className="text-lg font-bold text-text-main">Settings</h2>
          </div>
          <button
            onClick={onClose}
            className="p-1.5 text-text-muted hover:text-text-main rounded-lg hover:bg-bg-hover transition-colors"
          >
            <X className="w-5 h-5" />
          </button>
        </div>

        {/* Body */}
        <div className="flex flex-1 overflow-hidden">
          {/* Sidebar Tabs */}
          <div className="w-48 border-r border-border-main bg-bg-main p-3 flex flex-col gap-1 shrink-0">
            {[
              { id: 'general', label: 'General', icon: Settings2 },
              { id: 'appearance', label: 'Appearance', icon: Palette },
              { id: 'advanced', label: 'Advanced Compare', icon: ListFilter },
            ].map(tab => (
              <button
                key={tab.id}
                onClick={() => setActiveTab(tab.id as any)}
                className={clsx(
                  "flex items-center gap-2.5 px-3 py-2 rounded-lg text-[13px] font-medium transition-all text-left",
                  activeTab === tab.id 
                    ? "bg-blue-500/10 text-blue-500" 
                    : "text-text-muted hover:bg-bg-hover hover:text-text-main"
                )}
              >
                <tab.icon className="w-4 h-4" />
                {tab.label}
              </button>
            ))}
          </div>

          {/* Content */}
          <div className="flex-1 p-6 overflow-y-auto bg-bg-panel">
            
            {activeTab === 'general' && (
              <div className="space-y-6 animate-in slide-in-from-right-2 duration-300">
                <div>
                  <h3 className="text-sm font-bold text-text-main mb-4 flex items-center gap-2">
                    <Database className="w-4 h-4 text-blue-500" />
                    Query Defaults
                  </h3>
                  
                  <div className="space-y-4">
                    <div className="flex items-center justify-between p-3 rounded-lg border border-border-item bg-bg-main">
                      <div>
                        <div className="text-[13px] font-semibold text-text-main">Default Row Limit</div>
                        <div className="text-[11px] text-text-muted mt-0.5">The default number of rows fetched for auto-generated queries.</div>
                      </div>
                      <div className="flex items-center gap-2">
                        <input 
                          type="number" 
                          value={defaultRowLimit}
                          onChange={e => setDefaultRowLimit(Number(e.target.value) || 100)}
                          className="w-24 px-3 py-1.5 bg-bg-input border border-border-input rounded-md text-[13px] text-text-main outline-none focus:border-blue-500"
                        />
                      </div>
                    </div>
                  </div>
                </div>
              </div>
            )}

            {activeTab === 'appearance' && (
              <div className="space-y-8 animate-in slide-in-from-right-2 duration-300">
                {/* Theme Selection */}
                <div>
                  <h3 className="text-[11px] font-bold text-text-muted mb-3 uppercase tracking-wider flex items-center gap-2">
                    <Palette className="w-3.5 h-3.5 text-emerald-500" />
                    Theme Preference
                  </h3>
                  <div className="grid grid-cols-2 gap-3">
                    {[
                      { id: 'light', label: 'Light Mode', desc: 'Clean and bright' },
                      { id: 'dark', label: 'Dark Mode', desc: 'Easy on the eyes' }
                    ].map(t => (
                      <button
                        key={t.id}
                        onClick={() => setTheme(t.id as 'light' | 'dark')}
                        className={clsx(
                          "p-3 rounded-xl border text-left transition-all",
                          theme === t.id 
                            ? "border-emerald-500 bg-emerald-500/10 shadow-[0_0_15px_rgba(16,185,129,0.1)]"
                            : "border-border-item bg-bg-main hover:border-border-main"
                        )}
                      >
                        <div className={clsx("text-xs font-bold", theme === t.id ? "text-emerald-500" : "text-text-main")}>
                          {t.label}
                        </div>
                        <div className="text-[10px] text-text-muted mt-0.5">{t.desc}</div>
                      </button>
                    ))}
                  </div>
                </div>

                {/* Font Size Selection */}
                <div>
                  <h3 className="text-[11px] font-bold text-text-muted mb-3 uppercase tracking-wider flex items-center gap-2">
                    <span className="w-3.5 h-3.5 flex items-center justify-center font-bold text-blue-500">A</span>
                    Font Size
                  </h3>
                  <div className="flex gap-2">
                    {([
                      { id: 'small', label: 'Small' },
                      { id: 'medium', label: 'Medium' },
                      { id: 'large', label: 'Large' }
                    ] as const).map(s => (
                      <button
                        key={s.id}
                        onClick={() => setFontSize(s.id)}
                        className={clsx(
                          "flex-1 py-2 rounded-lg border text-xs font-medium transition-all",
                          fontSize === s.id
                            ? "border-blue-500 bg-blue-500/10 text-blue-500"
                            : "border-border-item bg-bg-main text-text-muted hover:border-border-main"
                        )}
                      >
                        {s.label}
                      </button>
                    ))}
                  </div>
                </div>

                {/* Grid Density Selection */}
                <div>
                  <h3 className="text-[11px] font-bold text-text-muted mb-3 uppercase tracking-wider flex items-center gap-2">
                    <ListFilter className="w-3.5 h-3.5 text-amber-500" />
                    Grid Density
                  </h3>
                  <div className="flex gap-2">
                    {([
                      { id: 'compact', label: 'Compact' },
                      { id: 'comfortable', label: 'Comfortable' }
                    ] as const).map(d => (
                      <button
                        key={d.id}
                        onClick={() => setGridDensity(d.id)}
                        className={clsx(
                          "flex-1 py-2 rounded-lg border text-xs font-medium transition-all",
                          gridDensity === d.id
                            ? "border-amber-500 bg-amber-500/10 text-amber-600 dark:text-amber-400"
                            : "border-border-item bg-bg-main text-text-muted hover:border-border-main"
                        )}
                      >
                        {d.label}
                      </button>
                    ))}
                  </div>
                </div>
              </div>
            )}

            {activeTab === 'advanced' && (
              <div className="space-y-6 animate-in slide-in-from-right-2 duration-300">
                <div>
                  <h3 className="text-sm font-bold text-text-main mb-4 flex items-center gap-2">
                    <ListFilter className="w-4 h-4 text-amber-500" />
                    Data Comparison Rules
                  </h3>
                  
                  <div className="space-y-3">
                    {[
                      { 
                        id: 'ignoreCase', 
                        label: 'Case-Insensitive String Match', 
                        desc: 'Treat "Apple" and "apple" as identical.',
                        state: ignoreCase,
                        setState: setIgnoreCase
                      },
                      { 
                        id: 'trimWhitespace', 
                        label: 'Ignore Trailing Whitespace', 
                        desc: 'Trim spaces before comparing cell values.',
                        state: trimWhitespace,
                        setState: setTrimWhitespace
                      }
                    ].map(setting => (
                      <label key={setting.id} className="flex items-start gap-3 p-3 rounded-lg border border-border-item bg-bg-main cursor-pointer hover:border-border-main transition-colors">
                        <div className="mt-0.5">
                          <input 
                            type="checkbox" 
                            checked={setting.state}
                            onChange={(e) => setting.setState(e.target.checked)}
                            className="w-4 h-4 rounded text-blue-500 accent-blue-500 focus:ring-blue-500 cursor-pointer"
                          />
                        </div>
                        <div>
                          <div className="text-[13px] font-semibold text-text-main">{setting.label}</div>
                          <div className="text-[11px] text-text-muted mt-0.5">{setting.desc}</div>
                        </div>
                      </label>
                    ))}
                  </div>
                </div>
              </div>
            )}

          </div>
        </div>
        
        {/* Footer */}
        <div className="px-6 py-4 border-t border-border-main bg-bg-header flex justify-end shrink-0">
          <button 
            onClick={onClose}
            className="px-5 py-2 bg-blue-500 hover:bg-blue-600 text-white text-sm font-bold rounded-lg shadow-md transition-colors"
          >
            Done
          </button>
        </div>
      </div>
    </div>
  );
};
