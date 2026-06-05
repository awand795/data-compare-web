// @ts-nocheck
import React, { useState, useEffect } from 'react';
import axios from 'axios';
import { X, Loader2, Database, AlertCircle } from 'lucide-react';

interface RowData {
  id: number;
  resultId: string;
  rowKey: string;
  status: string;
  dataJson: string;
}

interface ScheduleDataViewerProps {
  resultId: string;
  tableName: string;
  onClose: () => void;
}

export const ScheduleDataViewer: React.FC<ScheduleDataViewerProps> = ({ resultId, tableName, onClose }) => {
  const [rows, setRows] = useState<RowData[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    setLoading(true);
    axios.get(`http://localhost:8081/api/schedules/results/${resultId}/rows`)
      .then(res => setRows(res.data || []))
      .catch(err => console.error("Failed to fetch rows", err))
      .finally(() => setLoading(false));
  }, [resultId]);

  return (
    <div className="fixed inset-0 bg-black/70 backdrop-blur-md flex items-center justify-center z-[70] p-4">
      <div className="bg-bg-panel border border-border-main rounded-xl shadow-2xl w-full max-w-5xl h-[80vh] flex flex-col">
        <div className="p-4 border-b border-border-main flex justify-between items-center bg-bg-header rounded-t-xl">
          <div className="flex items-center gap-3">
            <div className="w-8 h-8 rounded-lg bg-blue-500/10 flex items-center justify-center">
              <Database className="w-5 h-5 text-blue-400" />
            </div>
            <div>
              <h2 className="font-bold text-lg">Diff Data Details</h2>
              <p className="text-xs text-text-muted">Table: {tableName} | Run ID: {resultId}</p>
            </div>
          </div>
          <button onClick={onClose} className="p-1.5 rounded-lg hover:bg-bg-hover text-text-muted transition-colors">
            <X className="w-5 h-5" />
          </button>
        </div>

        <div className="flex-1 overflow-auto p-4 bg-bg-main/50">
          {loading ? (
            <div className="h-full flex items-center justify-center text-text-muted flex-col gap-3">
              <Loader2 className="w-8 h-8 animate-spin text-blue-500" />
              <p>Loading diff data...</p>
            </div>
          ) : rows.length === 0 ? (
            <div className="h-full flex items-center justify-center text-text-muted flex-col gap-3 opacity-50">
              <AlertCircle className="w-12 h-12" />
              <p>No difference rows saved for this run.</p>
              <p className="text-[10px]">Ensure 'Save full data' was enabled for this job.</p>
            </div>
          ) : (
            <div className="overflow-x-auto border border-border-main rounded-lg bg-bg-panel">
              <table className="w-full text-xs text-left border-collapse">
                <thead className="bg-bg-header text-[10px] text-text-muted uppercase tracking-wider sticky top-0">
                  <tr>
                    <th className="py-2 px-3 border-b border-border-main w-12 text-center">#</th>
                    <th className="py-2 px-3 border-b border-border-main w-32">Status</th>
                    <th className="py-2 px-3 border-b border-border-main w-48">Row Key</th>
                    <th className="py-2 px-3 border-b border-border-main">Column Values (Source vs Target)</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-border-item">
                  {rows.map((row, idx) => {
                    let cells: any = {};
                    try { cells = JSON.parse(row.dataJson); } catch(e) {}
                    
                    return (
                      <tr key={row.id} className="hover:bg-bg-hover transition-colors">
                        <td className="py-2 px-3 text-center text-text-muted">{idx + 1}</td>
                        <td className="py-2 px-3">
                          <span className={`px-2 py-0.5 rounded text-[10px] font-bold ${
                            row.status === 'DIFFERENT' ? 'bg-red-500/10 text-red-500' :
                            row.status === 'SOURCE_ONLY' ? 'bg-blue-500/10 text-blue-500' :
                            'bg-emerald-500/10 text-emerald-500'
                          }`}>
                            {row.status}
                          </span>
                        </td>
                        <td className="py-2 px-3 font-mono text-text-main">{row.rowKey}</td>
                        <td className="py-2 px-3">
                          <div className="flex flex-wrap gap-2">
                            {Object.entries(cells).map(([col, val]: [string, any]) => {
                                if (!val.isDifferent && row.status === 'DIFFERENT') return null;
                                return (
                                    <div key={col} className="bg-bg-subtle border border-border-item rounded px-2 py-1 flex flex-col gap-0.5 min-w-[120px]">
                                        <span className="text-[10px] font-bold text-text-muted border-b border-border-item/50 mb-1">{col}</span>
                                        <div className="flex items-center justify-between gap-4">
                                            <div className="flex flex-col">
                                                <span className="text-[9px] text-blue-400 uppercase font-bold opacity-50">Src</span>
                                                <span className="font-mono text-xs">{String(val.sourceValue ?? 'NULL')}</span>
                                            </div>
                                            <div className="flex flex-col text-right">
                                                <span className="text-[9px] text-emerald-400 uppercase font-bold opacity-50">Tgt</span>
                                                <span className="font-mono text-xs">{String(val.targetValue ?? 'NULL')}</span>
                                            </div>
                                        </div>
                                    </div>
                                )
                            })}
                          </div>
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          )}
        </div>

        <div className="p-4 border-t border-border-main flex justify-end bg-bg-header rounded-b-xl">
          <button onClick={onClose} className="px-6 py-2 bg-bg-panel border border-border-input rounded-lg text-sm font-bold hover:bg-bg-hover transition-colors">
            Close
          </button>
        </div>
      </div>
    </div>
  );
};
