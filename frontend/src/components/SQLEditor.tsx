import React, { useState, useEffect, useMemo, useCallback } from 'react';
import CodeMirror from '@uiw/react-codemirror';
import { sql } from '@codemirror/lang-sql';
import { vscodeDark, vscodeLight } from '@uiw/codemirror-theme-vscode';
import { format } from 'sql-formatter';
import { useAppStore } from '../store/useAppStore';
import { AlignLeft, Search, Loader2, Maximize2, Minimize2, X } from 'lucide-react';
import { linter, lintGutter } from '@codemirror/lint';
import type { Diagnostic } from '@codemirror/lint';
import { syntaxTree } from '@codemirror/language';
import type { Completion } from '@codemirror/autocomplete';
import axios from 'axios';
import clsx from 'clsx';
import { createPortal } from 'react-dom';

interface SQLEditorProps {
  value: string;
  onChange?: (value: string) => void;
  connectionId?: string | null;
  placeholder?: string;
  height?: string;
  onExecute?: () => void;
  className?: string;
  showMaximize?: boolean;
  readOnly?: boolean;
}

const schemaCache: Record<string, { tables: string[], schema: Record<string, (string | Completion)[]> }> = {};

export const SQLEditor: React.FC<SQLEditorProps> = ({
  value,
  onChange,
  connectionId,
  placeholder = "SELECT * FROM ...",
  height = "100%",
  onExecute,
  className,
  showMaximize = true,
  readOnly = false
}) => {
  const { theme, connections } = useAppStore();
  const [loadingSchema, setLoadingSchema] = useState(false);
  const [schemaData, setSchemaData] = useState<Record<string, (string | Completion)[]>>({});
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
        axios.post('/api/table-info', { connection: conn, tableName }).then(res => {
            const cols = res.data;
            const completions: Completion[] = cols.map((c: any) => ({
                label: c.columnName || '',
                type: c.primaryKeySource ? "keyword" : "property",
                info: `${c.sourceType || 'unknown'}${c.sourceSize ? `(${c.sourceSize})` : ''} ${c.primaryKeySource ? 'PK' : ''}`,
                detail: c.sourceType || ''
            }));
            setSchemaData(prev => {
                const updated = { ...prev, [tableName]: completions };
                if (schemaCache[connectionId]) schemaCache[connectionId].schema = updated;
                return updated;
            });
        }).catch(() => {
            // Fallback to /api/columns if /api/table-info fails or isn't supported
            axios.post('/api/columns', { connection: conn, tableName }).then(res => {
                setSchemaData(prev => {
                    const updated = { ...prev, [tableName]: res.data };
                    if (schemaCache[connectionId]) schemaCache[connectionId].schema = updated;
                    return updated;
                });
            }).catch(() => {});
        });
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

  // Handle body scroll locking when maximized
  useEffect(() => {
    if (isMaximized) {
      document.body.style.overflow = 'hidden';
    } else {
      document.body.style.overflow = '';
    }
    return () => { document.body.style.overflow = ''; };
  }, [isMaximized]);

  const handleFormat = () => {
    try {
      const formatted = format(value, { language: 'sql', keywordCase: 'upper' });
      onChange?.(formatted);
    } catch (err) {
      console.warn("SQL Formatting failed", err);
    }
  };

  const sqlExtension = useMemo(() => sql({ schema: schemaData, upperCaseKeywords: true }), [schemaData]);

  const sqlLinter = useMemo(() => linter((view) => {
    const diagnostics: Diagnostic[] = [];
    syntaxTree(view.state).cursor().iterate((node) => {
      if (node.type.isError) {
        diagnostics.push({
          from: node.from,
          to: node.to,
          severity: "error",
          message: "Syntax error",
        });
      }
    });
    return diagnostics;
  }), []);

  const editorUI = (isFullScreen: boolean) => (
    <div 
      className={clsx(
        "flex flex-col bg-bg-panel border border-border-main overflow-hidden",
        isFullScreen ? "fixed inset-0 z-[10000] p-6 shadow-2xl" : "h-full w-full rounded-md"
      )}
      style={!isFullScreen ? { height } : {}}
    >
      <div className="flex items-center justify-between px-3 py-2 bg-bg-header border-b border-border-main shrink-0">
        <div className="flex items-center gap-2">
            {isFullScreen ? (
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
                    <span className="text-[10px] text-text-muted font-medium uppercase tracking-wider">SQL Editor</span>
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
                isFullScreen ? "text-orange-500 bg-orange-500/10 hover:bg-orange-500/20" : "text-text-muted hover:text-blue-500 hover:bg-bg-hover"
              )}
              title={isFullScreen ? "Restore" : "Maximize to Fullscreen"}
            >
              {isFullScreen ? <Minimize2 className="w-3.5 h-3.5" /> : <Maximize2 className="w-3.5 h-3.5" />}
            </button>
          )}

          {isFullScreen && (
              <button
                onClick={() => setIsMaximized(false)}
                className="p-1.5 bg-red-500 text-white hover:bg-red-600 rounded ml-1 shadow-sm"
                title="Close Fullscreen"
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
          extensions={[sqlExtension, sqlLinter, lintGutter()]}
          onChange={onChange || (() => {})}
          placeholder={placeholder}
          editable={!readOnly}
          readOnly={readOnly}
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
            isFullScreen ? "text-base" : "text-[12px]"
          )}
          onKeyDown={(e) => {
            if (e.ctrlKey && e.key === 'Enter' && onExecute) {
              e.preventDefault();
              onExecute();
            }
            if (e.key === 'Escape' && isFullScreen) {
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
      
      {isFullScreen && (
          <div className="flex items-center justify-between px-4 py-3 bg-bg-header border-t border-border-main text-[11px] text-text-muted shrink-0">
              <div className="flex gap-6">
                  <span><strong className="text-text-main">Lines:</strong> {value.split('\n').length}</span>
                  <span><strong className="text-text-main">Characters:</strong> {value.length}</span>
              </div>
              <div className="flex gap-4">
                  <span><kbd className="bg-bg-input px-1.5 py-0.5 rounded border border-border-main font-mono text-text-main">ESC</kbd> to Restore</span>
                  <span><kbd className="bg-bg-input px-1.5 py-0.5 rounded border border-border-main font-mono text-text-main">CTRL + ENTER</kbd> to Run</span>
              </div>
          </div>
      )}
    </div>
  );

  return (
    <>
      {/* Normal mode rendering */}
      {!isMaximized && (
        <div className={clsx("relative", className)} style={{ height }}>
          {editorUI(false)}
        </div>
      )}

      {/* Maximized mode rendering via Portal to Body */}
      {isMaximized && createPortal(
        <div className="fixed inset-0 z-[10000] bg-black/50 backdrop-blur-sm flex items-center justify-center">
            <div className="w-full h-full animate-in fade-in zoom-in-95 duration-200">
                {editorUI(true)}
            </div>
        </div>,
        document.body
      )}
    </>
  );
};
