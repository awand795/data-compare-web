// @ts-nocheck
import React, { useState, useEffect } from 'react';
import axios from 'axios';
import { X, Clock, Calendar, CheckCircle, AlertTriangle, Info, Database, Eye, Plus } from 'lucide-react';
import { ScheduleDataViewer } from './ScheduleDataViewer';
import clsx from 'clsx';

interface ScheduleResult {
  id: string;
  scheduleId: string;
  runTime: string;
  matchCount: number;
  differentCount: number;
  sourceOnlyCount: number;
  targetOnlyCount: number;
  errorMessage?: string;
  details?: string; // JSON array string
}

interface ScheduleResultsModalProps {
  scheduleId: string;
  scheduleName: string;
  onClose: () => void;
}

export const ScheduleResultsModal: React.FC<ScheduleResultsModalProps> = ({ scheduleId, scheduleName, onClose }) => {
  const [results, setResults] = useState<ScheduleResult[]>([]);
  const [loading, setLoading] = useState(true);
  const [expandedResultIds, setExpandedResultIds] = useState<string[]>([]);
  const [viewingData, setViewingData] = useState<{resultId: string, tableName: string} | null>(null);

  useEffect(() => {
    setLoading(true);
    axios.get(`/api/schedules/${scheduleId}/results`)
      .then(res => setResults(res.data || []))
      .catch(err => console.error("Failed to fetch results", err))
      .finally(() => setLoading(false));
  }, [scheduleId]);

  const toggleExpand = (id: string) => {
    setExpandedResultIds(prev => prev.includes(id) ? prev.filter(x => x !== id) : [...prev, id]);
  };

  return (
    <div className="fixed inset-0 bg-black/60 backdrop-blur-sm flex items-center justify-center z-[60] p-4">
      <div className="bg-bg-panel border border-border-main rounded-xl shadow-2xl w-full max-w-5xl max-h-[85vh] flex flex-col">
        <div className="p-4 border-b border-border-main flex justify-between items-center bg-bg-header rounded-t-xl">
          <div className="flex items-center gap-3">
            <div className="w-8 h-8 rounded-lg bg-blue-500/10 flex items-center justify-center">
              <Calendar className="w-5 h-5 text-blue-400" />
            </div>
            <div>
              <h2 className="font-bold text-lg">Execution History</h2>
              <p className="text-xs text-text-muted">{scheduleName} (Group Summary)</p>
            </div>
          </div>
          <button onClick={onClose} className="p-1.5 rounded-lg hover:bg-bg-hover text-text-muted transition-colors">
            <X className="w-5 h-5" />
          </button>
        </div>

        <div className="flex-1 overflow-auto p-4">
          {loading ? (
            <div className="py-20 text-center text-text-muted">Loading history...</div>
          ) : results.length === 0 ? (
            <div className="py-20 text-center text-text-muted flex flex-col items-center gap-3">
              <Info className="w-12 h-12 opacity-20" />
              <p>No execution history found for this job yet.</p>
            </div>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-left text-xs border-collapse">
                <thead className="bg-bg-subtle text-[10px] text-text-muted uppercase tracking-wider sticky top-0">
                  <tr>
                    <th className="py-3 px-4 font-bold border-b border-border-main w-8"></th>
                    <th className="py-3 px-4 font-bold border-b border-border-main">Run Time</th>
                    <th className="py-3 px-4 font-bold border-b border-border-main text-center">Total Matches</th>
                    <th className="py-3 px-4 font-bold border-b border-border-main text-center">Total Diffs</th>
                    <th className="py-3 px-4 font-bold border-b border-border-main text-center">Total Src Only</th>
                    <th className="py-3 px-4 font-bold border-b border-border-main text-center">Total Tgt Only</th>
                    <th className="py-3 px-4 font-bold border-b border-border-main">Status</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-border-item">
                  {results.map((r) => {
                    const isExpanded = expandedResultIds.includes(r.id);
                    let tableDetails: any[] = [];
                    try {
                      tableDetails = typeof r.details === 'string' ? JSON.parse(r.details) : (r.details || []);
                    } catch (e) {}

                    // Fallback for legacy results (pre-grouping)
                    if (tableDetails.length === 0 && (r.matchCount > 0 || r.differentCount > 0 || r.sourceOnlyCount > 0 || r.targetOnlyCount > 0)) {
                      tableDetails = [{
                        tableName: 'Legacy Job',
                        match: r.matchCount,
                        different: r.differentCount,
                        sourceOnly: r.sourceOnlyCount,
                        targetOnly: r.targetOnlyCount
                      }];
                    }

                    return (
                      <React.Fragment key={r.id}>
                        <tr className="hover:bg-bg-hover transition-colors">
                          <td className="py-3 px-4">
                            <button onClick={() => toggleExpand(r.id)} className="p-1 hover:bg-bg-hover rounded text-text-muted">
                              <Plus className={clsx("w-3 h-3 transition-transform", isExpanded && "rotate-45")} />
                            </button>
                          </td>
                          <td className="py-3 px-4 font-medium">
                            {new Date(r.runTime).toLocaleString()}
                          </td>
                          <td className="py-3 px-4 text-center">
                            <span className="px-2 py-0.5 rounded-full bg-emerald-500/10 text-emerald-500 font-bold">
                              {r.matchCount}
                            </span>
                          </td>
                          <td className="py-3 px-4 text-center">
                            <span className={clsx("px-2 py-0.5 rounded-full font-bold", r.differentCount > 0 ? "bg-red-500/10 text-red-500" : "bg-bg-hover text-text-muted")}>
                              {r.differentCount}
                            </span>
                          </td>
                          <td className={clsx("py-3 px-4 text-center font-bold", r.sourceOnlyCount > 0 ? "text-orange-500" : "text-text-muted")}>{r.sourceOnlyCount}</td>
                          <td className={clsx("py-3 px-4 text-center font-bold", r.targetOnlyCount > 0 ? "text-blue-500" : "text-text-muted")}>{r.targetOnlyCount}</td>
                          <td className="py-3 px-4">
                            {r.errorMessage ? (
                              <div className="flex items-center gap-1.5 text-red-400 group relative">
                                <AlertTriangle className="w-3.5 h-3.5" />
                                <span className="truncate max-w-[120px]">{r.errorMessage}</span>
                              </div>
                            ) : (
                              <div className="flex items-center gap-1.5 text-emerald-500">
                                <CheckCircle className="w-3.5 h-3.5" />
                                <span>Success</span>
                              </div>
                            )}
                          </td>
                        </tr>
                        {isExpanded && (
                          <tr className="bg-bg-subtle/40 border-b border-border-item">
                            <td colSpan={7} className="p-0">
                              <div className="px-14 py-4 border-l-2 border-blue-500/30">
                                <h4 className="text-[10px] font-bold text-text-muted uppercase tracking-widest mb-3 flex items-center gap-2">
                                  <Database className="w-3 h-3" /> Detailed Results Per Table
                                </h4>
                                <table className="w-full text-[11px] text-left border-collapse">
                                  <thead>
                                    <tr className="text-text-muted border-b border-border-item bg-bg-panel/50">
                                      <th className="py-2 px-3 font-bold">Table Name</th>
                                      <th className="py-2 px-3 font-bold text-center">Match</th>
                                      <th className="py-2 px-3 font-bold text-center">Diff</th>
                                      <th className="py-2 px-3 font-bold text-center">Src Only</th>
                                      <th className="py-2 px-3 font-bold text-center">Tgt Only</th>
                                      <th className="py-2 px-3 font-bold text-center">Actions</th>
                                    </tr>
                                  </thead>
                                  <tbody className="divide-y divide-border-item/30">
                                    {tableDetails.map((td, tidx) => {
                                      const hasDiff = (td.different ?? 0) > 0;
                                      const hasSrcOnly = (td.sourceOnly ?? 0) > 0;
                                      const hasTgtOnly = (td.targetOnly ?? 0) > 0;
                                      const hasIssue = hasDiff || hasSrcOnly || hasTgtOnly;
                                      const rowHighlight = !hasIssue
                                        ? ""
                                        : hasDiff
                                          ? "bg-red-500/[0.04] border-l-2 border-l-red-500/40"
                                          : hasSrcOnly
                                            ? "bg-orange-500/[0.04] border-l-2 border-l-orange-500/40"
                                            : "bg-blue-500/[0.04] border-l-2 border-l-blue-500/40";
                                      return (
                                      <tr key={tidx} className={clsx("hover:bg-bg-panel/60 transition-colors", rowHighlight)}>
                                        <td className="py-2 px-3 font-mono font-medium text-text-main">{td.tableName}</td>
                                        <td className="py-2 px-3 text-center text-emerald-500 font-bold">{td.match ?? '-'}</td>
                                        <td className={clsx("py-2 px-3 text-center font-bold", (td.different ?? 0) > 0 ? "text-red-500" : "text-text-muted")}>{td.different ?? '-'}</td>
                                        <td className={clsx("py-2 px-3 text-center font-bold", (td.sourceOnly ?? 0) > 0 ? "text-orange-500" : "text-text-muted")}>{td.sourceOnly ?? '-'}</td>
                                        <td className={clsx("py-2 px-3 text-center font-bold", (td.targetOnly ?? 0) > 0 ? "text-blue-500" : "text-text-muted")}>{td.targetOnly ?? '-'}</td>
                                        <td className="py-2 px-3 text-center">
                                          {td.error ? (
                                            <span className="text-red-400 italic text-[10px]">{td.error}</span>
                                          ) : (td.different === 0 && td.sourceOnly === 0 && td.targetOnly === 0) ? (
                                            <span className="flex items-center gap-1 mx-auto justify-center text-emerald-500 font-bold text-[10px]">
                                              <CheckCircle className="w-3 h-3" /> Identical
                                            </span>
                                          ) : (
                                            <button 
                                              onClick={() => setViewingData({resultId: r.id, tableName: td.tableName})}
                                              className="flex items-center gap-1 mx-auto text-blue-400 hover:text-blue-300 font-bold text-[10px] bg-blue-500/10 px-2 py-0.5 rounded transition-colors"
                                            >
                                              <Eye className="w-3 h-3" /> View Data
                                            </button>
                                          )}
                                        </td>
                                      </tr>
                                    );
                                    })}
                                  </tbody>
                                </table>
                              </div>
                            </td>
                          </tr>
                        )}
                      </React.Fragment>
                    );
                  })}
                </tbody>
              </table>
            </div>
          )}
        </div>

        <div className="p-4 border-t border-border-main flex justify-end bg-bg-header rounded-b-xl">
          <button
            onClick={onClose}
            className="px-4 py-2 bg-bg-panel border border-border-input rounded-lg text-sm font-semibold hover:bg-bg-hover transition-colors"
          >
            Close
          </button>
        </div>
      </div>

      {viewingData && (
        <ScheduleDataViewer 
          resultId={viewingData.resultId} 
          tableName={viewingData.tableName} 
          onClose={() => setViewingData(null)} 
        />
      )}
    </div>
  );
};
