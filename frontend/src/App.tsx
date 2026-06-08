import { useEffect, useState } from 'react';
import axios from 'axios';
import { useAppStore } from './store/useAppStore';
import { DatabaseExplorer } from './components/DatabaseExplorer';
import { DataCompareView } from './components/DataCompareView';
import { SchemaCompareView } from './components/SchemaCompareView';
import { QueryWorkspace } from './components/QueryWorkspace';
import { TableDetailPanel } from './components/TableDetailPanel';
import { ExcelCompareView } from './components/ExcelCompareView';
import { ScheduleManagerView } from './components/ScheduleManagerView';
import { Panel, Group, Separator } from 'react-resizable-panels';
import { DatabaseZap, GitCompareArrows, Table2, Terminal, Settings, HelpCircle, Sun, Moon, FileSpreadsheet, CalendarClock } from 'lucide-react';
import { SettingsModal } from './components/SettingsModal';
import { HelpModal } from './components/HelpModal';
import { AlertModal } from './components/AlertModal';
import clsx from 'clsx';

function App() {
  const { appMode, setAppMode, theme, setTheme, setConnections } = useAppStore();
  const [isSettingsOpen, setIsSettingsOpen] = useState(false);
  const [isHelpOpen, setIsHelpOpen] = useState(false);

  useEffect(() => {
    // Load persisted connections
    axios.get('/api/connections')
      .then(res => {
        if (res.data && Array.isArray(res.data)) {
          setConnections(res.data);
          // Warm up connections in the background
          if (res.data.length > 0) {
            axios.post('/api/warmup', res.data)
              .catch(e => console.warn('Warmup failed:', e));
          }
        }
      })
      .catch(err => console.error('Failed to load connections:', err));
  }, [setConnections]);

  useEffect(() => {
    if (theme === 'dark') {
      document.documentElement.classList.add('dark');
    } else {
      document.documentElement.classList.remove('dark');
    }
  }, [theme]);

  const modes = [
    { id: 'data' as const, label: 'Data Compare', icon: GitCompareArrows, desc: 'Compare row data between databases' },
    { id: 'schema' as const, label: 'Schema Compare', icon: Table2, desc: 'Compare table structures' },
    { id: 'query' as const, label: 'Query Workspace', icon: Terminal, desc: 'Run custom SQL queries and compare results' },
    { id: 'excel' as const, label: 'Excel Compare', icon: FileSpreadsheet, desc: 'Compare DB table against an uploaded Excel file' },
    { id: 'schedule' as const, label: 'Scheduled Jobs', icon: CalendarClock, desc: 'Automated data comparison tasks' },
  ];

  return (
    <div className="h-screen flex flex-col bg-bg-main text-text-main font-sans overflow-hidden">
      {/* Top Header Bar */}
      <header className="bg-bg-panel border-b border-border-main flex items-center justify-between px-4 h-11 shrink-0">
        <div className="flex items-center gap-3">
          <div className="flex items-center gap-2">
            <div className="w-7 h-7 rounded-lg bg-gradient-to-br from-blue-500 to-cyan-400 flex items-center justify-center shadow-lg shadow-blue-500/20">
              <DatabaseZap className="w-4 h-4 text-white" />
            </div>
            <h1 className="text-sm font-bold tracking-wide bg-gradient-to-r from-blue-400 to-cyan-300 bg-clip-text text-transparent">
              DataSync Studio
            </h1>
          </div>
          
          <div className="h-5 w-px bg-border-main mx-1" />
          
          {/* Mode tabs */}
          <nav className="flex items-center gap-0.5">
            {modes.map(m => (
              <button
                key={m.id}
                onClick={() => setAppMode(m.id)}
                data-tooltip={m.desc}
                className={clsx(
                  "flex items-center gap-1.5 px-3 py-1.5 rounded-md text-xs font-medium transition-all",
                  appMode === m.id
                    ? "bg-blue-600/20 text-blue-600 dark:text-blue-400 shadow-inner border border-blue-500/30"
                    : "text-text-muted hover:text-text-main hover:bg-bg-hover"
                )}
              >
                <m.icon className="w-3.5 h-3.5" />
                {m.label}
              </button>
            ))}
          </nav>
        </div>
        
        <div className="flex items-center gap-2">
          {/* Theme Toggle Button */}
          <button 
            onClick={() => setTheme(theme === 'dark' ? 'light' : 'dark')}
            className="p-1.5 text-text-muted hover:text-text-main rounded transition-colors"
            data-tooltip={theme === 'dark' ? "Switch to Light Theme" : "Switch to Dark Theme"}
          >
            {theme === 'dark' ? <Sun className="w-4 h-4 text-amber-400" /> : <Moon className="w-4 h-4 text-slate-600" />}
          </button>

          <button 
            onClick={() => setIsSettingsOpen(true)}
            className="p-1.5 text-text-muted hover:text-text-main rounded transition-colors" 
            data-tooltip="Settings"
          >
            <Settings className="w-4 h-4" />
          </button>
          <button 
            onClick={() => setIsHelpOpen(true)}
            className="p-1.5 text-text-muted hover:text-text-main rounded transition-colors" 
            data-tooltip="Help"
          >
            <HelpCircle className="w-4 h-4" />
          </button>
        </div>
      </header>

      {/* Main Content */}
      <main className="flex-1 overflow-hidden">
        <Group orientation="horizontal">
          {/* Left Sidebar - Explorer */}
          <Panel defaultSize="18%" minSize="12%" maxSize="30%">
            <div className="h-full bg-bg-panel border-r border-border-main flex flex-col">
              <DatabaseExplorer />
            </div>
          </Panel>
          
          <Separator className="w-1 transition-all" />
          
          {/* Central Workspace */}
          <Panel defaultSize="82%">
            <div className="h-full flex flex-col overflow-hidden">
              <div className={clsx("h-full flex flex-col overflow-hidden", appMode !== 'data' && "hidden")}>
                <DataCompareView />
              </div>
              <div className={clsx("h-full flex flex-col overflow-hidden", appMode !== 'schema' && "hidden")}>
                <SchemaCompareView />
              </div>
              <div className={clsx("h-full flex flex-col overflow-hidden", appMode !== 'query' && "hidden")}>
                <QueryWorkspace />
              </div>
              <div className={clsx("h-full flex flex-col overflow-hidden", appMode !== 'excel' && "hidden")}>
                <ExcelCompareView />
              </div>
              <div className={clsx("h-full flex flex-col overflow-hidden", appMode !== 'schedule' && "hidden")}>
                <ScheduleManagerView />
              </div>
              <div className={clsx("h-full flex flex-col overflow-hidden", appMode !== 'explorer' && "hidden")}>
                <TableDetailPanel />
              </div>
            </div>
          </Panel>
        </Group>
      </main>

      {/* Status Bar */}
      <footer className="bg-bg-editor border-t border-border-main h-6 flex items-center px-3 text-[10px] text-text-muted shrink-0">
        <span>DataSync Studio v2.0</span>
        <div className="flex-1" />
        <span className="flex items-center gap-1">
          <span className="w-1.5 h-1.5 rounded-full bg-green-500 animate-pulse" />
          Ready
        </span>
      </footer>

      {/* Modals */}
      {isSettingsOpen && <SettingsModal onClose={() => setIsSettingsOpen(false)} />}
      {isHelpOpen && <HelpModal onClose={() => setIsHelpOpen(false)} />}
      <AlertModal />
    </div>
  );
}

export default App;
