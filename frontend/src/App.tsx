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
import { ToastContainer } from './components/ToastContainer';
import clsx from 'clsx';

function App() {
  const { 
    appMode, setAppMode, 
    theme, setTheme, 
    fontSize, gridDensity,
    setConnections,
    connections 
  } = useAppStore();
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
    <div className={clsx(
      "h-screen flex flex-col bg-bg-main text-text-main font-sans overflow-hidden",
      `font-size-${fontSize}`,
      `grid-${gridDensity}`
    )}>
      {/* Top Header Bar */}
      <header className="bg-bg-panel border-b border-border-main flex items-center justify-between px-4 h-14 shrink-0">
        <div className="flex items-center gap-3 min-w-0">
          <div className="flex items-center gap-2.5 shrink-0">
            <div className="w-8 h-8 rounded-lg bg-gradient-to-br from-blue-500 to-cyan-400 flex items-center justify-center shadow-lg shadow-blue-500/20">
              <DatabaseZap className="w-5 h-5 text-white" />
            </div>
            <h1 className="text-base font-bold tracking-wide bg-gradient-to-r from-blue-400 to-cyan-300 bg-clip-text text-transparent">
              DataSync Studio
            </h1>
          </div>
          
          <div className="h-6 w-px bg-border-main mx-1 shrink-0" />
          
          {/* Mode tabs */}
          <nav className="flex items-center gap-1 min-w-0 overflow-x-auto">
            {modes.map(m => (
              <button
                key={m.id}
                onClick={() => setAppMode(m.id)}
                title={m.desc}
                className={clsx(
                  "flex items-center gap-1.5 px-3.5 py-2 text-[13px] font-medium transition-all shrink-0 whitespace-nowrap border-b-2",
                  appMode === m.id
                    ? "border-blue-500 text-blue-500 dark:text-blue-400 bg-transparent"
                    : "border-transparent text-text-muted hover:text-text-main hover:border-text-muted/30"
                )}
              >
                <m.icon className="w-4 h-4" />
                {m.label}
              </button>
            ))}
          </nav>
        </div>
        
        <div className="flex items-center gap-1.5 shrink-0">
          <div className="h-5 w-px bg-border-main mx-1" />
          {/* Theme Toggle */}
          <button 
            onClick={() => setTheme(theme === 'dark' ? 'light' : 'dark')}
            className="flex flex-col items-center px-2 py-1 text-text-muted hover:text-text-main rounded transition-colors"
            title={theme === 'dark' ? "Switch to Light Theme" : "Switch to Dark Theme"}
          >
            {theme === 'dark' ? <Sun className="w-4 h-4 text-amber-400" /> : <Moon className="w-4 h-4 text-slate-600" />}
            <span className="text-[10px] leading-none mt-0.5">Theme</span>
          </button>

          <button 
            onClick={() => setIsSettingsOpen(true)}
            className="flex flex-col items-center px-2 py-1 text-text-muted hover:text-text-main rounded transition-colors" 
            title="Settings"
          >
            <Settings className="w-4 h-4" />
            <span className="text-[10px] leading-none mt-0.5">Settings</span>
          </button>
          <button 
            onClick={() => setIsHelpOpen(true)}
            className="flex flex-col items-center px-2 py-1 text-text-muted hover:text-text-main rounded transition-colors" 
            title="Help"
          >
            <HelpCircle className="w-4 h-4" />
            <span className="text-[10px] leading-none mt-0.5">Help</span>
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
      <footer className="bg-bg-editor border-t border-border-main h-7 flex items-center px-3 text-[11px] text-text-muted shrink-0">
        <span className="font-semibold text-text-main/60">DataSync Studio <span className="font-normal text-text-muted/60">v2.0</span></span>
        <div className="flex-1" />
        <span className="flex items-center gap-1.5 text-[11px]">
          <DatabaseZap className="w-3 h-3 text-blue-500/60" />
          <span className="text-text-muted">{connections.length} connection{connections.length !== 1 ? 's' : ''}</span>
          <span className="mx-1.5 text-border-main">|</span>
          <span className="flex items-center gap-1">
            <span className="w-1.5 h-1.5 rounded-full bg-green-500 animate-pulse" />
            <span className="text-green-500/80 font-medium">Ready</span>
          </span>
        </span>
      </footer>

      {/* Modals */}
      {isSettingsOpen && <SettingsModal onClose={() => setIsSettingsOpen(false)} />}
      {isHelpOpen && <HelpModal onClose={() => setIsHelpOpen(false)} />}
      <AlertModal />
      <ToastContainer />
    </div>
  );
}

export default App;
