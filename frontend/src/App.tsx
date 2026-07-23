import { useEffect, useState } from 'react';
import axios from 'axios';
import { useAppStore } from './store/useAppStore';
import { DatabaseExplorer } from './components/DatabaseExplorer';
import { DataCompareView } from './components/DataCompareView';
import { SchemaCompareView } from './components/SchemaCompareView';
import { QueryWorkspace } from './components/QueryWorkspace';
import { TableDetailPanel } from './components/TableDetailPanel';
import { FileUploadView } from './components/FileUploadView';
import { ScheduleManagerView } from './components/ScheduleManagerView';
import { DataWarehouseView } from './components/DataWarehouseView';
import { ApiBuilderView } from './components/ApiBuilderView';
import { Panel, Group, Separator } from 'react-resizable-panels';
import { DatabaseZap, GitCompareArrows, Table2, Settings, HelpCircle, Sun, Moon, FileSpreadsheet, CalendarClock, Code2, Database, Webhook } from 'lucide-react';
import { SettingsModal } from './components/SettingsModal';
import { HelpModal } from './components/HelpModal';
import { AlertModal } from './components/AlertModal';
import { ToastContainer } from './components/ToastContainer';
import { LoginScreen } from './components/LoginScreen';
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
  const [isAuthenticated, setIsAuthenticated] = useState(() => sessionStorage.getItem('darkosync_auth') === 'true');

  const [isMobile, setIsMobile] = useState(window.innerWidth < 768);
  const [isMobileSidebarOpen, setIsMobileSidebarOpen] = useState(false);

  useEffect(() => {
    const handleResize = () => setIsMobile(window.innerWidth < 768);
    window.addEventListener('resize', handleResize);
    return () => window.removeEventListener('resize', handleResize);
  }, []);

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

    // Load templates
    axios.get('/api/templates')
      .then(res => {
        if (res.data && Array.isArray(res.data)) {
          const parsed = res.data.map((t: any) => {
            let parsedMappings = undefined;
            if (t.tableMappings) {
              try { parsedMappings = JSON.parse(t.tableMappings); } catch(e) {}
            }
            return { ...t, tableMappings: parsedMappings };
          });
          useAppStore.getState().setTemplates(parsed);
        }
      })
      .catch(err => console.error('Failed to load templates:', err));
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
    { id: 'query' as const, label: 'Query Workspace', icon: Code2, desc: 'Run custom SQL queries and compare results' },
    { id: 'file_upload' as const, label: 'Upload File', icon: FileSpreadsheet, desc: 'Upload Excel & CSV files to database (sch_excel schema)' },
    { id: 'schedule' as const, label: 'Scheduled Jobs', icon: CalendarClock, desc: 'Automated data comparison tasks' },
    { id: 'dwh' as const, label: 'Data Warehouse', icon: Database, desc: 'Configure ClickHouse replication via Debezium & Kafka' },
    { id: 'api_builder' as const, label: 'API Builder', icon: Webhook, desc: 'Build and deploy dynamic APIs from SQL queries' },
  ];

  if (!isAuthenticated) {
    return <LoginScreen onLogin={() => {
      sessionStorage.setItem('darkosync_auth', 'true');
      setIsAuthenticated(true);
    }} />;
  }

  return (
    <div className={clsx(
      "h-screen flex flex-col bg-bg-main text-text-main font-sans overflow-hidden",
      `font-size-${fontSize}`,
      `grid-${gridDensity}`
    )}>
      {/* Top Header Bar */}
      <header className="bg-bg-panel border-b border-border-main flex items-center justify-between px-2 md:px-4 h-14 shrink-0 relative z-10">
        <div className="flex items-center gap-2 md:gap-3 min-w-0">
          <div className="flex items-center gap-2 md:gap-2.5 shrink-0">
            {/* Mobile Menu Button */}
            <button 
              className="md:hidden p-1.5 text-text-muted hover:text-text-main hover:bg-bg-hover rounded transition-colors"
              onClick={() => setIsMobileSidebarOpen(true)}
            >
              <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M4 6h16M4 12h16M4 18h16" />
              </svg>
            </button>
            <div className="w-7 h-7 md:w-8 md:h-8 rounded-lg bg-gradient-to-br from-blue-500 to-cyan-400 flex items-center justify-center shadow-lg shadow-blue-500/20 hidden md:flex">
              <DatabaseZap className="w-4 h-4 md:w-5 md:h-5 text-white" />
            </div>
            <h1 className="text-sm md:text-base font-bold tracking-wide bg-gradient-to-r from-blue-400 to-cyan-300 bg-clip-text text-transparent truncate hidden sm:block">
              Darkosync Studio
            </h1>
          </div>
          
          <div className="h-6 w-px bg-border-main mx-1 shrink-0 hidden sm:block" />
          
          {/* Mode tabs */}
          <nav className="flex items-center gap-1 min-w-0 overflow-x-auto no-scrollbar">
            {modes.map(m => (
              <button
                key={m.id}
                onClick={() => setAppMode(m.id)}
                title={m.desc}
                className={clsx(
                  "flex items-center gap-1.5 px-2 md:px-3.5 py-2 text-[12px] md:text-[13px] font-medium transition-all shrink-0 whitespace-nowrap border-b-2",
                  appMode === m.id
                    ? "border-blue-500 text-blue-500 dark:text-blue-400 bg-transparent"
                    : "border-transparent text-text-muted hover:text-text-main hover:border-text-muted/30"
                )}
              >
                <m.icon className="w-3.5 h-3.5 md:w-4 md:h-4" />
                {m.label}
              </button>
            ))}
          </nav>
        </div>
        
        <div className="flex items-center gap-1 md:gap-1.5 shrink-0">
          <div className="h-5 w-px bg-border-main mx-1 hidden md:block" />
          {/* Theme Toggle */}
          <button 
            onClick={() => setTheme(theme === 'dark' ? 'light' : 'dark')}
            className="flex flex-col items-center px-1 md:px-2 py-1 text-text-muted hover:text-text-main rounded transition-colors"
            title={theme === 'dark' ? "Switch to Light Theme" : "Switch to Dark Theme"}
          >
            {theme === 'dark' ? <Sun className="w-4 h-4 text-amber-400" /> : <Moon className="w-4 h-4 text-slate-600" />}
            <span className="text-[10px] leading-none mt-0.5 hidden md:block">Theme</span>
          </button>

          <button 
            onClick={() => setIsSettingsOpen(true)}
            className="flex flex-col items-center px-1 md:px-2 py-1 text-text-muted hover:text-text-main rounded transition-colors" 
            title="Settings"
          >
            <Settings className="w-4 h-4" />
            <span className="text-[10px] leading-none mt-0.5 hidden md:block">Settings</span>
          </button>
          <button 
            onClick={() => setIsHelpOpen(true)}
            className="flex flex-col items-center px-1 md:px-2 py-1 text-text-muted hover:text-text-main rounded transition-colors" 
            title="Help"
          >
            <HelpCircle className="w-4 h-4" />
            <span className="text-[10px] leading-none mt-0.5 hidden md:block">Help</span>
          </button>
        </div>
      </header>

      {/* Main Content */}
      <main className="flex-1 overflow-hidden relative">
        {/* Mobile Sidebar Overlay */}
        {isMobile && isMobileSidebarOpen && (
          <div className="absolute inset-0 z-50 flex">
            <div 
              className="absolute inset-0 bg-black/50" 
              onClick={() => setIsMobileSidebarOpen(false)}
            />
            <div className="relative w-[85%] max-w-sm h-full bg-bg-panel flex flex-col border-r border-border-main animate-in slide-in-from-left duration-200 shadow-xl">
              <div className="flex items-center justify-between p-3 border-b border-border-main">
                <div className="flex items-center gap-2">
                  <DatabaseZap className="w-5 h-5 text-blue-500" />
                  <span className="font-semibold text-text-main">Database Explorer</span>
                </div>
                <button 
                  onClick={() => setIsMobileSidebarOpen(false)}
                  className="p-1 rounded hover:bg-bg-editor text-text-muted hover:text-text-main"
                >
                  <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg">
                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" />
                  </svg>
                </button>
              </div>
              <div className="flex-1 overflow-hidden">
                <DatabaseExplorer />
              </div>
            </div>
          </div>
        )}

        {isMobile ? (
          <div className="h-full flex flex-col overflow-hidden w-full">
            <div className={clsx("h-full flex flex-col overflow-hidden", appMode !== 'data' && "hidden")}>
              <DataCompareView />
            </div>
            <div className={clsx("h-full flex flex-col overflow-hidden", appMode !== 'schema' && "hidden")}>
              <SchemaCompareView />
            </div>
            <div className={clsx("h-full flex flex-col overflow-hidden", appMode !== 'query' && "hidden")}>
              <QueryWorkspace />
            </div>
            <div className={clsx("h-full flex flex-col overflow-hidden", appMode !== 'file_upload' && "hidden")}>
              <FileUploadView />
            </div>
            <div className={clsx("h-full flex flex-col overflow-hidden", appMode !== 'schedule' && "hidden")}>
              <ScheduleManagerView />
            </div>
            <div className={clsx("h-full flex flex-col overflow-hidden", appMode !== 'explorer' && "hidden")}>
              <TableDetailPanel />
            </div>
            <div className={clsx("h-full flex flex-col overflow-hidden", appMode !== 'dwh' && "hidden")}>
              <DataWarehouseView />
            </div>
            <div className={clsx("h-full flex flex-col overflow-hidden", appMode !== 'api_builder' && "hidden")}>
              <ApiBuilderView />
            </div>
          </div>
        ) : (
          <Group orientation="horizontal">
            {/* Left Sidebar - Explorer */}
            <Panel defaultSize="18%" minSize="12%" maxSize="30%">
              <div className="h-full bg-bg-panel border-r border-border-main flex flex-col">
                <DatabaseExplorer />
              </div>
            </Panel>
            
            <Separator className="w-1 transition-all hover:bg-blue-500/50" />
            
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
                <div className={clsx("h-full flex flex-col overflow-hidden", appMode !== 'file_upload' && "hidden")}>
                  <FileUploadView />
                </div>
                <div className={clsx("h-full flex flex-col overflow-hidden", appMode !== 'schedule' && "hidden")}>
                  <ScheduleManagerView />
                </div>
                <div className={clsx("h-full flex flex-col overflow-hidden", appMode !== 'explorer' && "hidden")}>
                  <TableDetailPanel />
                </div>
                <div className={clsx("h-full flex flex-col overflow-hidden", appMode !== 'dwh' && "hidden")}>
                  <DataWarehouseView />
                </div>
                <div className={clsx("h-full flex flex-col overflow-hidden", appMode !== 'api_builder' && "hidden")}>
                  <ApiBuilderView />
                </div>
              </div>
            </Panel>
          </Group>
        )}
      </main>

      {/* Status Bar */}
      <footer className="bg-bg-editor border-t border-border-main h-7 flex items-center px-3 text-[11px] text-text-muted shrink-0 z-10">
        <span className="font-semibold text-text-main/60 hidden sm:block">Darkosync Studio <span className="font-normal text-text-muted/60">v2.0</span></span>
        <div className="flex-1" />
        <span className="flex items-center gap-1.5 text-[11px]">
          <DatabaseZap className="w-3 h-3 text-blue-500/60" />
          <span className="text-text-muted">{connections.length} <span className="hidden sm:inline">connection{connections.length !== 1 ? 's' : ''}</span></span>
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
