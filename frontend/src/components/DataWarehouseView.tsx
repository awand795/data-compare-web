import React, { useState } from 'react';
import { useAppStore } from '../store/useAppStore';
import { Database, Play, Loader2, Settings2, Table as TableIcon, Server, Cpu } from 'lucide-react';
import clsx from 'clsx';
import { Panel, Group, Separator } from 'react-resizable-panels';

export const DataWarehouseView: React.FC = () => {
  const { connections, addToast } = useAppStore();
  const [sourceConnId, setSourceConnId] = useState('');
  const [targetConnId, setTargetConnId] = useState('');
  const [query, setQuery] = useState('-- Define the data to sync via Debezium\nSELECT * FROM source_schema.source_table');
  const [targetTable, setTargetTable] = useState('');
  const [isDeploying, setIsDeploying] = useState(false);
  const [logs, setLogs] = useState<string[]>([]);

  const handleDeploy = async () => {
    if (!sourceConnId || !targetConnId || !query || !targetTable) {
      addToast({ type: 'warning', title: 'Missing Fields', message: 'Please fill in all required fields' });
      return;
    }
    
    setIsDeploying(true);
    setLogs(prev => [...prev, `[${new Date().toLocaleTimeString()}] Initializing Data Warehouse pipeline...`]);
    
    try {
      const sourceConn = connections.find(c => c.id === sourceConnId);
      const targetConn = connections.find(c => c.id === targetConnId);
      
      const response = await fetch('/api/dwh/deploy', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          sourceConnection: sourceConn,
          targetConnection: targetConn,
          query: query,
          targetTable: targetTable
        })
      });

      if (!response.ok) {
        throw new Error(await response.text());
      }

      const reader = response.body?.getReader();
      if (!reader) throw new Error('No readable stream available');
      const decoder = new TextDecoder('utf-8');

      while (true) {
        const { value, done } = await reader.read();
        if (done) break;
        if (value) {
          const chunk = decoder.decode(value, { stream: true });
          const lines = chunk.split('\n');
          for (const line of lines) {
            if (line.startsWith('data:')) {
              const logMsg = line.substring(5).trim();
              if (logMsg) {
                setLogs(prev => [...prev, `[${new Date().toLocaleTimeString()}] ${logMsg}`]);
                if (logMsg.startsWith('ERROR:')) {
                  throw new Error(logMsg.substring(6).trim());
                }
              }
            }
          }
        }
      }

      addToast({ type: 'success', title: 'Deployed Successfully', message: 'Data Warehouse pipeline is now active.' });
    } catch (error: any) {
      setLogs(prev => [...prev, `[${new Date().toLocaleTimeString()}] Deployment failed: ${error.message}`]);
      addToast({ type: 'error', title: 'Deployment Failed', message: error.message || 'An error occurred' });
    } finally {
      setIsDeploying(false);
    }
  };

  return (
    <div className="flex flex-col h-full bg-bg-main text-text-main animate-in fade-in duration-300">
      <div className="bg-bg-header border-b border-border-main px-4 py-3 flex items-center justify-between shrink-0 shadow-sm relative z-10">
        <div className="flex items-center gap-3">
          <div className="w-9 h-9 rounded-xl bg-gradient-to-br from-indigo-500 to-purple-500 flex items-center justify-center shadow-lg shadow-indigo-500/20">
            <Server className="w-5 h-5 text-white" />
          </div>
          <div>
            <h2 className="text-sm font-bold text-text-main">Data Warehouse Replication</h2>
            <p className="text-[11px] text-text-muted font-medium">Configure Debezium & Kafka pipelines to ClickHouse</p>
          </div>
        </div>
        <button
          onClick={handleDeploy}
          disabled={isDeploying || !sourceConnId || !targetConnId || !targetTable}
          className="group relative overflow-hidden px-4 py-1.5 bg-gradient-to-r from-indigo-600 to-purple-600 hover:from-indigo-500 hover:to-purple-500 text-white rounded-lg text-[13px] font-bold disabled:opacity-50 transition-all flex items-center gap-1.5 shadow-md shadow-indigo-500/30 hover:shadow-lg hover:shadow-indigo-500/50 hover:-translate-y-0.5 active:translate-y-0 duration-300 whitespace-nowrap"
        >
          <div className="absolute inset-0 bg-white/20 opacity-0 group-hover:opacity-100 transition-opacity"></div>
          {isDeploying ? <Loader2 className="w-3.5 h-3.5 animate-spin relative z-10" /> : <Play className="w-3.5 h-3.5 fill-current relative z-10" />}
          <span className="relative z-10">{isDeploying ? 'Deploying Pipeline...' : 'Deploy Pipeline'}</span>
        </button>
      </div>

      <div className="flex-1 min-h-0">
        <Group orientation="horizontal">
          <Panel defaultSize={65} minSize={30}>
            <div className="h-full flex flex-col p-5 overflow-y-auto">
              
              <div className="grid grid-cols-1 lg:grid-cols-2 gap-5 mb-5">
                <div className="flex flex-col gap-2">
                  <label className="text-[11px] font-bold text-text-muted uppercase tracking-wider flex items-center gap-1.5">
                    <Database className="w-3.5 h-3.5 text-blue-500" /> Source Database
                  </label>
                  <select
                    className="px-3.5 py-2.5 bg-bg-panel border border-border-input hover:border-indigo-500/50 rounded-lg text-sm focus:border-indigo-500 focus:ring-1 focus:ring-indigo-500 outline-none transition-all shadow-sm cursor-pointer"
                    value={sourceConnId}
                    onChange={e => setSourceConnId(e.target.value)}
                  >
                    <option value="">Select source database...</option>
                    {connections.filter(c => c.enableDataWarehouse).map(c => <option key={c.id} value={c.id}>{c.name} ({c.type})</option>)}
                  </select>
                </div>
                <div className="flex flex-col gap-2">
                  <label className="text-[11px] font-bold text-text-muted uppercase tracking-wider flex items-center gap-1.5">
                    <Database className="w-3.5 h-3.5 text-amber-500" /> Target Data Warehouse
                  </label>
                  <select
                    className="px-3.5 py-2.5 bg-bg-panel border border-border-input hover:border-indigo-500/50 rounded-lg text-sm focus:border-indigo-500 focus:ring-1 focus:ring-indigo-500 outline-none transition-all shadow-sm cursor-pointer"
                    value={targetConnId}
                    onChange={e => setTargetConnId(e.target.value)}
                  >
                    <option value="">Select ClickHouse connection...</option>
                    {connections.filter(c => c.type === 'clickhouse').map(c => <option key={c.id} value={c.id}>{c.name} ({c.type})</option>)}
                  </select>
                </div>
              </div>

              <div className="flex flex-col gap-2 mb-6">
                <label className="text-[11px] font-bold text-text-muted uppercase tracking-wider flex items-center gap-1.5">
                  <TableIcon className="w-3.5 h-3.5 text-emerald-500" /> Target Table Name
                </label>
                <input
                  type="text"
                  placeholder="e.g. dwh_sales_fact"
                  className="px-3.5 py-2.5 bg-bg-panel border border-border-input hover:border-indigo-500/50 rounded-lg text-sm focus:border-indigo-500 focus:ring-1 focus:ring-indigo-500 outline-none transition-all shadow-sm"
                  value={targetTable}
                  onChange={e => setTargetTable(e.target.value)}
                />
                <p className="text-[10px] text-text-muted mt-0.5 ml-1">Table will be automatically created in the Data Warehouse if it doesn't exist.</p>
              </div>

              <div className="flex flex-col flex-1 min-h-[300px] border border-border-input rounded-xl overflow-hidden bg-bg-panel shadow-sm">
                <div className="bg-bg-header/50 border-b border-border-main px-4 py-2.5 flex items-center gap-2">
                  <Settings2 className="w-4 h-4 text-indigo-500" />
                  <span className="text-xs font-bold text-text-main">Extraction Query (Source)</span>
                  <span className="ml-auto text-[10px] bg-indigo-500/10 text-indigo-500 px-2 py-0.5 rounded-full font-semibold border border-indigo-500/20">SQL</span>
                </div>
                <div className="flex-1 p-1">
                  <textarea
                    className="w-full h-full bg-transparent text-text-main font-mono text-[13px] p-3 outline-none resize-none"
                    value={query}
                    onChange={(e) => setQuery(e.target.value)}
                    spellCheck={false}
                  />
                </div>
              </div>
            </div>
          </Panel>

          <Separator className="w-1.5 bg-border-main hover:bg-indigo-500/50 transition-colors cursor-col-resize flex items-center justify-center">
            <div className="h-10 w-0.5 bg-border-item rounded-full" />
          </Separator>

          <Panel defaultSize={35} minSize={20}>
            <div className="h-full bg-bg-editor flex flex-col border-l border-border-main relative overflow-hidden">
              {/* Decorative background element */}
              <div className="absolute top-0 right-0 p-32 bg-indigo-500/5 rounded-full blur-3xl pointer-events-none -mr-16 -mt-16" />
              
              <div className="bg-bg-header/80 backdrop-blur-sm border-b border-border-main px-4 py-3 flex items-center justify-between relative z-10">
                <span className="text-xs font-bold text-text-main flex items-center gap-2">
                  <Cpu className="w-4 h-4 text-indigo-500" /> Deployment Console
                </span>
                {logs.length > 0 && (
                  <button 
                    onClick={() => setLogs([])}
                    className="text-[10px] uppercase font-bold text-text-muted hover:text-indigo-500 transition-colors px-2 py-1 rounded hover:bg-indigo-500/10"
                  >
                    Clear Logs
                  </button>
                )}
              </div>
              <div className="flex-1 p-4 overflow-y-auto font-mono text-[12px] leading-relaxed flex flex-col gap-2 relative z-10 no-scrollbar">
                {logs.length === 0 ? (
                  <div className="text-text-muted/40 h-full flex flex-col items-center justify-center gap-3">
                    <Server className="w-12 h-12 stroke-[1]" />
                    <span className="italic">Deployment logs will stream here</span>
                  </div>
                ) : (
                  logs.map((log, idx) => (
                    <div key={idx} className={clsx(
                      "break-words animate-in slide-in-from-bottom-2 duration-300 py-1 border-b border-border-main/30 last:border-0",
                      log.includes('successfully') || log.includes('active') ? "text-emerald-500" :
                      log.includes('error') || log.includes('failed') ? "text-red-500" :
                      "text-text-muted"
                    )}>
                      <span className="opacity-50 mr-2 text-[10px]">►</span> {log}
                    </div>
                  ))
                )}
                {isDeploying && (
                  <div className="text-indigo-500 flex items-center gap-2 mt-2 py-1 animate-pulse">
                    <span className="opacity-50 text-[10px]">►</span> <span className="flex items-center gap-1">Processing <span className="flex gap-0.5"><span className="animate-bounce">.</span><span className="animate-bounce delay-75">.</span><span className="animate-bounce delay-150">.</span></span></span>
                  </div>
                )}
              </div>
            </div>
          </Panel>
        </Group>
      </div>
    </div>
  );
};
