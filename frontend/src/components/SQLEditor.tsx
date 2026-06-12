import React, { useState, useEffect, useMemo, useCallback } from 'react';
import CodeMirror from '@uiw/react-codemirror';
import { sql } from '@codemirror/lang-sql';
import { vscodeDark, vscodeLight } from '@uiw/codemirror-theme-vscode';
import { format } from 'sql-formatter';
import { useAppStore } from '../store/useAppStore';
import { Play, AlignLeft, Search, Loader2 } from 'lucide-react';
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
}

// Simple cache to avoid redundant metadata fetches
const schemaCache: Record<string, { tables: string[], schema: Record<string, string[]> }> = {};

export const SQLEditor: React.FC<SQLEditorProps> = ({
  value,
  onChange,
  connectionId,
  placeholder = "SELECT * FROM ...",
  height = "100%",
  onExecute,
  className
}) => {
  const { theme, connections } = useAppStore();
  const [loadingSchema, setLoadingSchema] = useState(false);
  const [schemaData, setSchemaData] = useState<Record<string, string[]>>({});

  const conn = useMemo(() => connections.find(c => c.id === connectionId), [connections, connectionId]);

  const fetchSchema = useCallback(async () => {
    if (!connectionId || !conn) return;
    
    // Check cache
    if (schemaCache[connectionId]) {
      setSchemaData(schemaCache[connectionId].schema);
      return;
    }

    setLoadingSchema(true);
    try {
      // 1. Fetch tables
      const tablesRes = await axios.post('/api/tables', conn);
      const tables: any[] = tablesRes.data;
      
      const newSchema: Record<string, string[]> = {};
      
      // For performance, we only fetch columns for the first 50 tables 
      // or we can just fetch tables for now and suggest them.
      // DBeaver fetches columns on demand, but CodeMirror's sql() extension 
      // likes a static schema object for basic autocompletion.
      
      for (const table of tables) {
        newSchema[table.name] = [];
      }
      
      // Fetch columns for some tables if needed, or just leave as empty arrays (will still autocomplete table names)
      setSchemaData(newSchema);
      schemaCache[connectionId] = { tables: tables.map(t => t.name), schema: newSchema };
      
      // Speculatively fetch columns for the first 50 tables to make it look "alive"
      const tablesToFetch = tables.slice(0, 50).map(t => t.name);
      
      // Use a batch approach or just loop if the API is fast
      for (const tableName of tablesToFetch) {
        axios.post('/api/columns', { connection: conn, tableName }).then(res => {
            const cols = res.data;
            setSchemaData(prev => {
                const updated = { ...prev, [tableName]: cols };
                if (schemaCache[connectionId]) {
                    schemaCache[connectionId].schema = updated;
                }
                return updated;
            });
        }).catch(() => {});
      }

    } catch (err) {
      console.error("Failed to fetch schema for autocomplete", err);
    } finally {
      setLoadingSchema(false);
    }
  }, [connectionId, conn]);

  useEffect(() => {
    fetchSchema();
  }, [fetchSchema]);

  const handleFormat = () => {
    try {
      const formatted = format(value, {
        language: 'sql',
        keywordCase: 'upper',
      });
      onChange(formatted);
    } catch (err) {
      console.warn("SQL Formatting failed", err);
    }
  };

  const sqlExtension = useMemo(() => sql({ schema: schemaData }), [schemaData]);

  return (
    <div className={clsx("relative flex flex-col group", className)} style={{ height }}>
      <div className="absolute right-4 top-2 z-10 flex items-center gap-1 opacity-0 group-hover:opacity-100 transition-opacity">
        {loadingSchema && (
            <div className="flex items-center gap-1.5 px-2 py-1 bg-bg-panel/80 backdrop-blur border border-border-main rounded text-[10px] text-text-muted mr-2">
                <Loader2 className="w-3 h-3 animate-spin text-blue-500" />
                Updating Schema...
            </div>
        )}
        <button
          onClick={handleFormat}
          className="p-1.5 bg-bg-panel hover:bg-bg-hover border border-border-main rounded text-text-muted hover:text-blue-500 transition-all shadow-sm"
          title="Format SQL (Beautify)"
        >
          <AlignLeft className="w-3.5 h-3.5" />
        </button>
        {onExecute && (
          <button
            onClick={onExecute}
            className="p-1.5 bg-blue-500 hover:bg-blue-600 border border-blue-400/20 rounded text-white transition-all shadow-sm"
            title="Execute Query (Ctrl+Enter)"
          >
            <Play className="w-3.5 h-3.5 fill-current" />
          </button>
        )}
      </div>

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
        className="flex-1 text-[13px] font-mono leading-relaxed overflow-hidden rounded-md border border-border-main"
        onKeyDown={(e) => {
          if (e.ctrlKey && e.key === 'Enter' && onExecute) {
            e.preventDefault();
            onExecute();
          }
        }}
      />
      
      <div className="absolute bottom-2 left-12 z-10 pointer-events-none">
         {!value && (
            <div className="text-[11px] text-text-muted/40 italic flex items-center gap-1.5">
                <Search className="w-3 h-3" />
                Type to see suggestions (tables, columns, keywords)
            </div>
         )}
      </div>
    </div>
  );
};
