import React, { useState, useEffect, useMemo, useCallback } from 'react';
import CodeMirror from '@uiw/react-codemirror';
import { sql } from '@codemirror/lang-sql';
import { vscodeDark, vscodeLight } from '@uiw/codemirror-theme-vscode';
import { format } from 'sql-formatter';
import { useAppStore } from '../store/useAppStore';
import { Play, AlignLeft, Search, Loader2, Maximize2, Minimize2, X } from 'lucide-react';
import axios from 'axios';
import clsx from 'clsx';

interface SQLEditorProps {
  value: string;
  onChange: (value: string) => void;
  connectionId?: string | null;
  placeholder?: string;
  height?: string;
  onExecute?: () => void;
  className?: string;
  showMaximize?: boolean;
}

const schemaCache: Record<string, { tables: string[], schema: Record<string, string[]> }> = {};

export const SQLEditor: React.FC<SQLEditorProps> = ({
  value,
  onChange,
  connectionId,
  placeholder = "SELECT * FROM ...",
  height = "100%",
  onExecute,
  className,
  showMaximize = true
}) => {
  const { theme, connections } = useAppStore();
  const [loadingSchema, setLoadingSchema] = useState(false);
  const [schemaData, setSchemaData] = useState<Record<string, string[]>>({});
  const [isMaximized, setIsMaximized] = useState(false);

  const conn = useMemo(() => connections.find(c => c.id === connectionId), [connections, connectionId]);

  const fetchSchema = useCallback(async () => {
    if (!connectionId || !conn) return;
    if (schemaCache[connectionId]) {
      setSchemaData(schemaCache[connectionId].schema);
      return;
    }

    setLoadingSchema(true);
    try {
      const tablesRes = await axios.post('/api/tables', conn);
      const tables: any[] = tablesRes.data;
      const newSchema: Record<string, string[]> = {};
      for (const table of tables) {
        newSchema[table.name] = [];
      }
      setSchemaData(newSchema);
      schemaCache[connectionId] = { tables: tables.map(t => t.name), schema: newSchema };
      
      const tablesToFetch = tables.slice(0, 50).map(t => t.name);
      for (const tableName of tablesToFetch) {
        axios.post('/api/columns', { connection: conn, tableName }).then(res => {
            const cols = res.data;
            setSchemaData(prev => {
                const updated = { ...prev, [tableName]: cols };
                if (schemaCache[connectionId]) schemaCache[connectionId].schema = updated;
                return updated;
            });
        }).catch(() => {});
      }
    } catch (err) {
      console.error("Failed to fetch schema", err);
    } finally {
      setLoadingSchema(false);
    }
  }, [connectionId, conn]);

  useEffect(() => {
    fetchSchema();
  }, [fetchSchema]);

  const handleFormat = () => {
    try {
      const formatted = format(value, { language: 'sql', keywordCase: 'upper' });
      onChange(formatted);
    } catch (err) {
      console.warn("SQL Formatting failed", err);
    }
  };

  const sqlExtension = useMemo(() => sql({ schema: schemaData }), [schemaData]);

  return (
    <div 
      className={clsx(
        "flex flex-col bg-bg-panel border border-border-main rounded-md overflow-hidden transition-all duration-200",
        isMaximized ? "fixed inset-0 z-[9999] m-0 rounded-none p-6" : "relative h-full w-full",
        className
      )}
      style={!isMaximized ? { height } : {}}
    >
      {/* Toolbar */}
      <div className="flex items-center justify-between px-3 py-2 bg-bg-header border-b border-border-main shrink-0">
        <div className="flex items-center gap-2">
            {isMaximized ? (
                <div className="flex items-center gap-2">
                    <Maximize2 className="w-4 h-4 text-blue-500" />
                    <span className="text-sm font-bold text-text-main">SQL Editor (Full View)</span>
                    <span className="text-xs text-text-muted px-2 py-0.5 bg-bg-input rounded border border-border-main ml-2 font-mono">
                        {conn?.name || 'No Connection'}
                    </span>
                </div>
            ) : (
                <div className="flex items-center gap-1.5">
                    {loadingSchema && <Loader2 className="w-3 h-3 animate-spin text-blue-500" />}
                    <span className="text-[10px] text-text-muted font-medium uppercase tracking-wider">Editor</span>
                </div>
            )}
        </div>

        <div className="flex items-center gap-1">
          <button
            onClick={handleFormat}
            className="p-1.5 hover:bg-bg-hover rounded text-text-muted hover:text-blue-500 transition-colors"
            title="Format SQL"
          >
            <AlignLeft className="w-3.5 h-3.5" />
          </button>

          {showMaximize && (
            <button
              onClick={() => setIsMaximized(!isMaximized)}
              className={clsx(
                "p-1.5 rounded transition-colors",
                isMaximized ? "text-orange-500 hover:bg-orange-500/10" : "text-text-muted hover:text-blue-500 hover:bg-bg-hover"
              )}
              title={isMaximized ? "Restore" : "Maximize"}
            >
              {isMaximized ? <Minimize2 className="w-3.5 h-3.5" /> : <Maximize2 className="w-3.5 h-3.5" />}
            </button>
          )}

          {isMaximized && (
              <button
                onClick={() => setIsMaximized(false)}
                className="p-1.5 bg-red-500/10 text-red-500 hover:bg-red-500/20 rounded ml-1"
                title="Close"
              >
                <X className="w-3.5 h-3.5" />
              </button>
          )}
        </div>
      </div>

      <div className="flex-1 relative min-h-0 bg-bg-editor">
        <CodeMirror
          value={value}
          height="100%"
          theme={theme === 'dark' ? vscodeDark : vscodeLight}
          extensions={[sqlExtension]}
          onChange={onChange}
          placeholder={placeholder}
          basicSetup={{
            lineNumbers: true,
            highlightActiveLine: true,
            bracketMatching: true,
            closeBrackets: true,
            autocompletion: true,
            foldGutter: true,
          }}
          className={clsx(
            "h-full font-mono leading-relaxed",
            isMaximized ? "text-base" : "text-[12px]"
          )}
          onKeyDown={(e) => {
            if (e.ctrlKey && e.key === 'Enter' && onExecute) {
              e.preventDefault();
              onExecute();
            }
            if (e.key === 'Escape' && isMaximized) {
              setIsMaximized(false);
            }
          }}
        />
        
        {!value && (
            <div className="absolute bottom-3 left-12 pointer-events-none opacity-40 flex items-center gap-1.5 text-[10px]">
                <Search className="w-3 h-3" />
                Type for suggestions...
            </div>
        )}
      </div>
      
      {isMaximized && (
          <div className="flex items-center justify-between px-4 py-2 bg-bg-header border-t border-border-main text-[10px] text-text-muted shrink-0">
              <div className="flex gap-4">
                  <span>Lines: {value.split('\n').length}</span>
                  <span>Chars: {value.length}</span>
              </div>
              <div className="flex gap-3">
                  <span><kbd className="bg-bg-input px-1 rounded border border-border-main">ESC</kbd> to Restore</span>
                  <span><kbd className="bg-bg-input px-1 rounded border border-border-main">CTRL+ENTER</kbd> to Run</span>
              </div>
          </div>
      )}
    </div>
  );
};
