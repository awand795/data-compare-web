// @ts-nocheck
import React, { useState } from 'react';
import { X, HelpCircle, Command, BookOpen, GitCompareArrows, Terminal, Table2 } from 'lucide-react';
import clsx from 'clsx';

export const HelpModal: React.FC<{ onClose: () => void }> = ({ onClose }) => {
  const [activeTab, setActiveTab] = useState<'guide' | 'shortcuts' | 'about'>('guide');

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm p-4">
      <div className="bg-bg-panel border border-border-main rounded-xl shadow-2xl w-full max-w-3xl overflow-hidden flex flex-col max-h-[85vh] animate-in fade-in zoom-in-95 duration-200">
        
        {/* Header */}
        <div className="px-6 py-4 border-b border-border-main flex items-center justify-between bg-bg-header shrink-0">
          <div className="flex items-center gap-3">
            <div className="p-2 bg-emerald-500/10 rounded-lg">
              <HelpCircle className="w-5 h-5 text-emerald-500" />
            </div>
            <h2 className="text-lg font-bold text-text-main">Help & Documentation</h2>
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
              { id: 'guide', label: 'User Guide', icon: BookOpen },
              { id: 'shortcuts', label: 'Shortcuts', icon: Command },
              { id: 'about', label: 'About DataSync', icon: HelpCircle },
            ].map(tab => (
              <button
                key={tab.id}
                onClick={() => setActiveTab(tab.id as any)}
                className={clsx(
                  "flex items-center gap-2.5 px-3 py-2 rounded-lg text-[13px] font-medium transition-all text-left",
                  activeTab === tab.id 
                    ? "bg-emerald-500/10 text-emerald-600 dark:text-emerald-400" 
                    : "text-text-muted hover:bg-bg-hover hover:text-text-main"
                )}
              >
                <tab.icon className="w-4 h-4" />
                {tab.label}
              </button>
            ))}
          </div>

          {/* Content */}
          <div className="flex-1 p-6 overflow-y-auto bg-bg-panel text-text-main">
            
            {activeTab === 'guide' && (
              <div className="space-y-8 animate-in slide-in-from-right-2 duration-300">
                <section>
                  <h3 className="text-sm font-bold flex items-center gap-2 mb-3 text-blue-500">
                    <GitCompareArrows className="w-4 h-4" />
                    Data Compare
                  </h3>
                  <p className="text-[13px] text-text-muted leading-relaxed mb-3">
                    Compare exact row data between two databases. Click <strong>Add Mapping</strong> to pair a Source table with a Target table. 
                    You can set primary keys to correctly align rows, or use the <strong>Custom SQL</strong> button to write a complex query.
                  </p>
                  <div className="bg-amber-500/10 border border-amber-500/20 rounded-md p-3 text-[12px] text-amber-600 dark:text-amber-400">
                    <strong>Tip:</strong> If comparing large tables, use the Date Filter. You MUST enter the Column Name (e.g. `created_at`) for the date filter to apply!
                  </div>
                </section>

                <section>
                  <h3 className="text-sm font-bold flex items-center gap-2 mb-3 text-purple-500">
                    <Table2 className="w-4 h-4" />
                    Schema Compare
                  </h3>
                  <p className="text-[13px] text-text-muted leading-relaxed">
                    Quickly identify structural differences between databases. It checks column names, data types, nullability, sizes, and primary key constraints. Mismatches are highlighted in orange.
                  </p>
                </section>

                <section>
                  <h3 className="text-sm font-bold flex items-center gap-2 mb-3 text-emerald-500">
                    <Terminal className="w-4 h-4" />
                    Query Workspace
                  </h3>
                  <p className="text-[13px] text-text-muted leading-relaxed">
                    Write raw SQL queries to fetch data from both databases side-by-side. 
                    If you navigate here from a Table Mapping, your query will automatically inherit any Date Filters you set. You can also click <strong>Compare Diff</strong> directly in the workspace to see the row-level differences!
                  </p>
                </section>
              </div>
            )}

            {activeTab === 'shortcuts' && (
              <div className="space-y-6 animate-in slide-in-from-right-2 duration-300">
                <h3 className="text-sm font-bold mb-4">Keyboard Shortcuts</h3>
                
                <div className="space-y-3">
                  {[
                    { keys: ['Ctrl', 'Enter'], desc: 'Execute queries in Query Workspace' },
                    { keys: ['Esc'], desc: 'Close open modals' },
                    { keys: ['Click'], desc: 'Toggle row expansion in Diff Data Grid' },
                  ].map((s, i) => (
                    <div key={i} className="flex items-center justify-between p-3 rounded-lg border border-border-item bg-bg-main">
                      <span className="text-[13px] text-text-muted">{s.desc}</span>
                      <div className="flex gap-1.5">
                        {s.keys.map(k => (
                          <kbd key={k} className="px-2 py-1 bg-bg-input border border-border-input rounded shadow-sm text-[11px] font-mono font-bold text-text-main">
                            {k}
                          </kbd>
                        ))}
                      </div>
                    </div>
                  ))}
                </div>
              </div>
            )}

            {activeTab === 'about' && (
              <div className="flex flex-col items-center justify-center text-center h-full animate-in zoom-in-95 duration-300">
                <div className="w-16 h-16 rounded-2xl bg-gradient-to-br from-blue-500 to-cyan-400 flex items-center justify-center shadow-lg shadow-blue-500/20 mb-4">
                  <GitCompareArrows className="w-8 h-8 text-white" />
                </div>
                <h2 className="text-2xl font-bold text-text-main mb-2">DataSync Studio</h2>
                <div className="text-[13px] font-medium text-text-muted bg-bg-hover px-3 py-1 rounded-full mb-6">
                  Version 2.0.4
                </div>
                <p className="text-[13px] text-text-muted max-w-sm leading-relaxed">
                  The ultimate database comparison tool for developers. Build, compare, and sync your data with confidence.
                </p>
              </div>
            )}

          </div>
        </div>
      </div>
    </div>
  );
};
