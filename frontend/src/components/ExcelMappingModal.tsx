// @ts-nocheck
import React, { useState, useEffect } from 'react';
import { useAppStore, type TableMapping } from '../store/useAppStore';
import { ArrowRight, X, Code, Plus, Pencil, Database, GitMerge, MinusCircle, LayoutTemplate } from 'lucide-react';
import axios from 'axios';
import clsx from 'clsx';
import { SQLEditor } from './SQLEditor';

interface ExcelMappingModalProps {

  sourceTables: string[];
  targetTables: string[];
  editingMapping?: TableMapping | null;
  excelIsTarget: boolean;
  onClose: () => void;
}

interface JoinTable {
  table: string;
  alias: string;
  onClause: string;
  columns: string[];
  selectedColumns: string[];
}

export const ExcelMappingModal: React.FC<Props> = ({ sourceTables, targetTables, editingMapping, excelIsTarget, onClose }) => {
  const { 
    addExcelMapping, updateExcelMapping,
    targetConnectionId, connections,
    theme
  } = useAppStore();

  const sourceConn = connections.find(c => c.id === targetConnectionId);
  const targetConn = connections.find(c => c.id === targetConnectionId);

  const isEdit = !!editingMapping;

  const [label, setLabel]             = useState(editingMapping?.label || '');
  const [sourceTable, setSourceTable] = useState(editingMapping?.sourceTable || '');
  const [targetTable, setTargetTable] = useState(editingMapping?.targetTable || '');
  const [sourceQuery, setSourceQuery] = useState(editingMapping?.customQuerySource || '');
  const [targetQuery, setTargetQuery] = useState(editingMapping?.customQueryTarget || '');
  const [primaryKeys, setPrimaryKeys] = useState((editingMapping?.primaryKeys || []).join(', '));
  const [excludeCols, setExcludeCols] = useState((editingMapping?.excludeColumns || []).join(', '));
  const [sortColumns, setSortColumns] = useState((editingMapping?.sortColumns || []).join(', '));
  const [extraWhereSrc, setExtraWhereSrc] = useState(editingMapping?.extraWhereSource || '');
  const [extraWhereTgt, setExtraWhereTgt] = useState(editingMapping?.extraWhereTarget || '');
  const [rowLimit, setRowLimit]       = useState<string>(editingMapping?.rowLimit?.toString() || '');
  const [showAdvanced, setShowAdvanced] = useState(false);
  const [queryMode, setQueryMode]     = useState<'simple' | 'join' | 'custom'>('simple');

  // Visual Builder state
  const [sourceBaseCols, setSourceBaseCols] = useState<string[]>([]);
  const [sourceBaseSelected, setSourceBaseSelected] = useState<string[]>([]);
  const [targetBaseCols, setTargetBaseCols] = useState<string[]>([]);
  const [targetBaseSelected, setTargetBaseSelected] = useState<string[]>([]);
  
  const [joinSources, setJoinSources] = useState<JoinTable[]>([]);
  const [joinTargets, setJoinTargets] = useState<JoinTable[]>([]);

  useEffect(() => {
    if (editingMapping?.customQuerySource || editingMapping?.customQueryTarget) {
      setQueryMode('custom');
    }
  }, []);

  const fetchColumns = async (conn: any, tableName: string) => {
    if (!conn || !tableName) return [];
    try {
      const res = await axios.post('/api/columns', {
        connection: conn,
        tableName: tableName
      });
      return res.data as string[];
    } catch (err) {
      console.error("Error fetching columns", err);
      return [];
    }
  };

  useEffect(() => {
    if (queryMode !== 'join' || !sourceTable || !sourceConn) return;
    fetchColumns(sourceConn, sourceTable).then(cols => {
      setSourceBaseCols(cols);
      setSourceBaseSelected(cols);
    });
  }, [sourceTable, queryMode, sourceConn]);

  useEffect(() => {
    if (queryMode !== 'join' || !targetTable || !targetConn) return;
    fetchColumns(targetConn, targetTable).then(cols => {
      setTargetBaseCols(cols);
      setTargetBaseSelected(cols);
    });
  }, [targetTable, queryMode, targetConn]);

  const handleJoinTableChange = async (side: 'source' | 'target', i: number, newTable: string) => {
    const isSource = side === 'source';
    const conn = isSource ? sourceConn : targetConn;
    const baseTable = isSource ? sourceTable : targetTable;
    const baseCols = isSource ? sourceBaseCols : targetBaseCols;
    const items = isSource ? joinSources : joinTargets;
    const setItems = isSource ? setJoinSources : setJoinTargets as any;

    setItems((prev: JoinTable[]) => prev.map((j, idx) => idx === i ? { ...j, table: newTable } : j));

    if (!newTable || !conn) return;
    const cols = await fetchColumns(conn, newTable);
    
    let autoOn = items[i].onClause;
    const alias = items[i].alias || `t${i + 1}`;
    
    if (!autoOn && baseTable && baseCols.length > 0) {
      if (baseCols.includes(`${newTable}_id`)) {
        autoOn = `t0.${newTable}_id = ${alias}.id`;
      } else if (cols.includes(`${baseTable}_id`)) {
        autoOn = `t0.id = ${alias}.${baseTable}_id`;
      } else {
        const common = cols.find(c => c !== 'id' && baseCols.includes(c));
        if (common) autoOn = `t0.${common} = ${alias}.${common}`;
      }
    }

    setItems((prev: JoinTable[]) => prev.map((j, idx) => idx === i ? { 
      ...j, 
      columns: cols, 
      selectedColumns: cols, 
      onClause: j.onClause || autoOn 
    } : j));
  };

  const toggleColumn = (side: 'source'|'target', type: 'base' | 'join', idx: number, col: string) => {
    if (type === 'base') {
      const setter = side === 'source' ? setSourceBaseSelected : setTargetBaseSelected;
      setter(prev => prev.includes(col) ? prev.filter(c => c !== col) : [...prev, col]);
    } else {
      const setter = side === 'source' ? setJoinSources : setJoinTargets as any;
      setter((prev: JoinTable[]) => prev.map((j, i) => {
        if (i !== idx) return j;
        const selected = j.selectedColumns.includes(col) 
          ? j.selectedColumns.filter(c => c !== col) 
          : [...j.selectedColumns, col];
        return { ...j, selectedColumns: selected };
      }));
    }
  };

  const toggleAllColumns = (side: 'source'|'target', type: 'base'|'join', idx: number, allCols: string[], selected: string[]) => {
    const isAll = selected.length === allCols.length;
    if (type === 'base') {
      const setter = side === 'source' ? setSourceBaseSelected : setTargetBaseSelected;
      setter(isAll ? [] : allCols);
    } else {
      const setter = side === 'source' ? setJoinSources : setJoinTargets as any;
      setter((prev: JoinTable[]) => prev.map((j, i) => i === idx ? { ...j, selectedColumns: isAll ? [] : allCols } : j));
    }
  };

  const buildJoinQuery = (side: 'source' | 'target') => {
    const base = side === 'source' ? sourceTable : targetTable;
    const baseSelected = side === 'source' ? sourceBaseSelected : targetBaseSelected;
    const joins = side === 'source' ? joinSources : joinTargets;
    
    if (!base) return '';
    
    const alias = 't0';
    let selectParts: string[] = [];
    
    if (baseSelected.length === 0 && joins.length === 0) {
      selectParts.push(`${alias}.*`);
    } else {
      baseSelected.forEach(c => selectParts.push(`${alias}.${c}`));
      joins.forEach((j, i) => {
        const jAlias = j.alias || `t${i + 1}`;
        j.selectedColumns.forEach(c => selectParts.push(`${jAlias}.${c}`));
      });
    }

    if (selectParts.length === 0) selectParts.push('*');

    const selectClause = `SELECT ${selectParts.join(', ')}`;
    
    const joinClauses = joins
      .filter(j => j.table)
      .map((j, i) => {
        const a = j.alias || `t${i + 1}`;
        return `  JOIN ${j.table} AS ${a} ON ${j.onClause || `${alias}.id = ${a}.id`}`;
      })
      .join('\n');
      
    let query = `${selectClause}\nFROM ${base} AS ${alias}`;
    if (joinClauses) query += `\n${joinClauses}`;
    
    const extraWhere = side === 'source' ? extraWhereSrc : extraWhereTgt;
    if (extraWhere.trim()) {
      query += `\nWHERE ${extraWhere.trim()}`;
    }
    if (rowLimit) {
      query += `\nLIMIT ${rowLimit}`;
    }
    
    return query;
  };

  const handleSave = () => {
    let sq = sourceQuery.trim();
    let tq = targetQuery.trim();

    if (queryMode === 'join') {
      sq = buildJoinQuery('source');
      tq = buildJoinQuery('target');
    } else if (queryMode === 'simple') {
      const buildSimple = (conn: any, table: string, extraWhere: string) => {
        if (!table) return '';
        const fullTable = conn?.schema && (conn.type === 'postgresql' || conn.type === 'sqlserver') ? `${conn.schema}.${table}` : table;
        const conditions: string[] = [];
        if (extraWhere.trim()) conditions.push(`(${extraWhere.trim()})`);
        const where = conditions.length ? ` WHERE ${conditions.join(' AND ')}` : '';
        const limit = rowLimit ? ` LIMIT ${rowLimit}` : '';
        return `SELECT * FROM ${fullTable}${where}${limit}`;
      };
      sq = buildSimple(sourceConn, sourceTable, extraWhereSrc);
      tq = buildSimple(targetConn, targetTable, extraWhereTgt);
    }

    const baseMapping: Omit<TableMapping, 'id'> = {
      label: label.trim() || undefined,
      sourceTable,
      targetTable,
      customQuerySource: sq || undefined,
      customQueryTarget: tq || undefined,
      isManualQuerySource: queryMode === 'custom' && !!sq,
      isManualQueryTarget: queryMode === 'custom' && !!tq,
      primaryKeys: primaryKeys.trim() ? primaryKeys.split(',').map(s => s.trim()).filter(Boolean) : undefined,
      excludeColumns: excludeCols.trim() ? excludeCols.split(',').map(s => s.trim()).filter(Boolean) : undefined,
      sortColumns: sortColumns.trim() ? sortColumns.split(',').map(s => s.trim()).filter(Boolean) : undefined,
      extraWhereSource: queryMode === 'simple' ? (extraWhereSrc.trim() || undefined) : undefined,
      extraWhereTarget: queryMode === 'simple' ? (extraWhereTgt.trim() || undefined) : undefined,
      rowLimit: rowLimit ? parseInt(rowLimit) : undefined,
    };

    if (isEdit && editingMapping) {
      updateExcelMapping(editingMapping.id, baseMapping);
    } else {
      addExcelMapping({ id: `custom-${Date.now()}`, ...baseMapping });
    }
    onClose();
  };

  const renderColumnSelector = (
    title: string, 
    cols: string[], 
    selected: string[], 
    onToggle: (c: string) => void,
    onToggleAll: () => void
  ) => {
    if (!cols.length) return null;
    const allSelected = selected.length === cols.length && cols.length > 0;
    return (
      <div className="mt-2 bg-bg-main border border-border-item rounded-lg p-2 max-h-40 overflow-y-auto">
        <div className="flex items-center justify-between mb-2 pb-2 border-b border-border-item">
          <span className="text-[10px] font-bold text-text-muted">{title}</span>
          <button 
            onClick={onToggleAll}
            className="text-[10px] text-blue-500 hover:text-blue-400 font-medium"
          >
            {allSelected ? 'Deselect All' : 'Select All'}
          </button>
        </div>
        <div className="grid grid-cols-3 gap-2">
          {cols.map(c => (
            <label key={c} className="flex items-center gap-1.5 cursor-pointer group">
              <input 
                type="checkbox" 
                checked={selected.includes(c)}
                onChange={() => onToggle(c)}
                className="w-3 h-3 rounded border-border-input text-blue-500 bg-bg-input focus:ring-0 cursor-pointer"
              />
              <span className="text-[10px] font-mono text-text-main group-hover:text-blue-400 truncate" title={c}>{c}</span>
            </label>
          ))}
        </div>
      </div>
    );
  };

  const renderJoinEditor = (side: 'source' | 'target') => {
    const isSource = side === 'source';
    const items = isSource ? joinSources : joinTargets;
    const setItems = isSource ? setJoinSources : setJoinTargets as any;
    const tables = isSource ? sourceTables : targetTables;
    const addJoin = () => setItems((p: JoinTable[]) => [...p, { table: '', alias: '', onClause: '', columns: [], selectedColumns: [] }]);
    const update = (i: number, f: keyof JoinTable, v: any) => setItems((p: JoinTable[]) => p.map((j, idx) => idx === i ? { ...j, [f]: v } : j));
    const remove = (i: number) => setItems((p: JoinTable[]) => p.filter((_, idx) => idx !== i));
    const color = isSource ? 'blue' : 'emerald';

    return (
      <div className="flex flex-col gap-2 mt-4">
        <div className="flex items-center justify-between">
          <label className={`text-[10px] font-bold text-${color}-500/80 uppercase tracking-wider`}>JOIN Tables ({side})</label>
          <button onClick={addJoin} className={`text-[10px] text-${color}-500 hover:text-${color}-400 font-medium flex items-center gap-1`}>
            <Plus className="w-3 h-3" /> Add JOIN
          </button>
        </div>
        {items.length === 0 && (
          <p className="text-[10px] text-text-muted italic">No JOIN tables added. Click "Add JOIN".</p>
        )}
        {items.map((j, i) => (
          <div key={i} className="flex flex-col gap-2 bg-bg-hover rounded-lg p-3 border border-border-item shadow-sm">
            <div className="grid grid-cols-12 gap-2 items-end">
              <div className="flex flex-col col-span-4">
                <span className="text-[9px] text-text-muted mb-0.5">Table</span>
                <input
                  list={`${side}-join-table-options`}
                  value={j.table}
                  onChange={e => handleJoinTableChange(side, i, e.target.value)}
                  placeholder="table_name"
                  className="w-full px-2 py-1.5 text-[11px] font-mono bg-bg-input border border-border-input rounded text-text-input outline-none focus:border-blue-500"
                />
                <datalist id={`${side}-join-table-options`}>{tables.map(t => <option key={t} value={t} />)}</datalist>
              </div>
              <div className="flex flex-col col-span-2">
                <span className="text-[9px] text-text-muted mb-0.5">Alias</span>
                <input
                  value={j.alias}
                  onChange={e => update(i, 'alias', e.target.value)}
                  placeholder={`t${i + 1}`}
                  className="w-full px-2 py-1.5 text-[11px] font-mono bg-bg-input border border-border-input rounded text-text-input outline-none focus:border-blue-500"
                />
              </div>
              <div className="flex flex-col col-span-5">
                <span className="text-[9px] text-text-muted mb-0.5">ON Clause (Auto)</span>
                <input
                  value={j.onClause}
                  onChange={e => update(i, 'onClause', e.target.value)}
                  placeholder="t0.id = t1.id"
                  className="w-full px-2 py-1.5 text-[11px] font-mono bg-bg-input border border-border-input rounded text-text-input outline-none focus:border-blue-500"
                />
              </div>
              <button onClick={() => remove(i)} className="col-span-1 text-red-500 hover:text-red-400 p-1.5 bg-red-500/10 rounded w-full flex items-center justify-center">
                <MinusCircle className="w-4 h-4" />
              </button>
            </div>
            
            {renderColumnSelector(
              `Select Columns for ${j.table || 'JOIN'}`,
              j.columns,
              j.selectedColumns,
              (c) => toggleColumn(side, 'join', i, c),
              () => toggleAllColumns(side, 'join', i, j.columns, j.selectedColumns)
            )}
          </div>
        ))}
      </div>
    );
  };

  return (
    <div className="fixed inset-0 bg-black/60 backdrop-blur-sm flex items-center justify-center z-50 p-4">
      <div className="bg-bg-panel border border-border-main rounded-xl shadow-2xl w-full max-w-[850px] flex flex-col text-text-main max-h-[90vh] overflow-hidden">

        <div className="flex items-center justify-between px-5 py-3.5 border-b border-border-main shrink-0">
          <div className="flex items-center gap-2 text-text-main font-semibold text-sm">
            {isEdit ? <Pencil className="w-4 h-4 text-blue-500" /> : <Plus className="w-4 h-4 text-blue-500" />}
            {isEdit ? 'Edit Mapping' : 'Add Excel Mapping'}
          </div>
          <button onClick={onClose} className="text-text-muted hover:text-text-main p-1 rounded hover:bg-bg-hover transition-colors">
            <X className="w-4 h-4" />
          </button>
        </div>

        <div className="flex-1 overflow-y-auto">
          <div className="p-5 flex flex-col gap-4">

            <div className="flex flex-col gap-1">
              <label className="text-[10px] font-bold text-text-muted uppercase tracking-widest">Mapping Label (optional)</label>
              <input
                value={label}
                onChange={e => setLabel(e.target.value)}
                placeholder="e.g. Orders merged, Sales 2024..."
                className="px-2.5 py-2 bg-bg-input border border-border-input rounded-md text-xs text-text-input outline-none focus:border-blue-500"
              />
            </div>

            <div className="flex flex-col gap-2">
              <label className="text-[10px] font-bold text-text-muted uppercase tracking-widest">Query Mode</label>
              <div className="flex gap-1">
                {([
                  { id: 'simple', label: 'Simple', icon: Database, desc: 'Single table, optional WHERE & LIMIT' },
                  { id: 'join', label: 'Visual Builder', icon: LayoutTemplate, desc: 'Visually select multiple tables and columns' },
                  { id: 'custom', label: 'Custom SQL', icon: Code, desc: 'Write any SQL query manually' },
                ] as const).map(mode => (
                  <button
                    key={mode.id}
                    onClick={() => setQueryMode(mode.id as any)}
                    className={`flex items-center gap-1.5 px-3 py-1.5 rounded-md text-[11px] font-medium border transition-all ${
                      queryMode === mode.id
                        ? 'bg-blue-600/20 border-blue-500/40 text-blue-600 dark:text-blue-400'
                        : 'bg-bg-input border-border-input text-text-muted hover:text-text-main'
                    }`}
                    title={mode.desc}
                  >
                    <mode.icon className="w-3 h-3" />
                    {mode.label}
                  </button>
                ))}
              </div>
            </div>

            {/* ── Simple Mode ── */}
            {queryMode === 'simple' && (
              <div className="flex flex-col gap-3 animate-in fade-in slide-in-from-bottom-2 duration-300">
                <div className="grid grid-cols-[1fr_auto_1fr] gap-3 items-end">
                  <div className="flex flex-col">
                    <label className="text-[10px] font-bold text-blue-500/70 uppercase tracking-widest mb-1">Source Table</label>
                    {!excelIsTarget ? (
                      <input value={sourceTable} disabled className="w-full px-2.5 py-2 bg-gray-100 dark:bg-gray-800 border border-border-input rounded-md text-xs text-gray-500 cursor-not-allowed outline-none" title="Uploaded Excel Data" />
                    ) : (
                      <>
                        <input list="source-tables-list" value={sourceTable} onChange={e => setSourceTable(e.target.value)}
                          placeholder="Select source table..." className="w-full px-2.5 py-2 bg-bg-input border border-border-input rounded-md text-xs text-text-input outline-none focus:border-blue-500" />
                        <datalist id="source-tables-list">{sourceTables.filter(t => !t.startsWith('excel_import_')).map(t => <option key={t} value={t} />)}</datalist>
                      </>
                    )}
                  </div>
                  <div className="pb-1"><ArrowRight className="w-4 h-4 text-text-muted opacity-50" /></div>
                  <div className="flex flex-col">
                    <label className="text-[10px] font-bold text-emerald-600/70 uppercase tracking-widest mb-1">Target Table</label>
                    {excelIsTarget ? (
                      <input value={targetTable} disabled className="w-full px-2.5 py-2 bg-gray-100 dark:bg-gray-800 border border-border-input rounded-md text-xs text-gray-500 cursor-not-allowed outline-none" title="Uploaded Excel Data" />
                    ) : (
                      <>
                        <input list="target-tables-list" value={targetTable} onChange={e => setTargetTable(e.target.value)}
                          placeholder="Select target table..." className="w-full px-2.5 py-2 bg-bg-input border border-border-input rounded-md text-xs text-text-input outline-none focus:border-blue-500" />
                        <datalist id="target-tables-list">{targetTables.filter(t => !t.startsWith('excel_import_')).map(t => <option key={t} value={t} />)}</datalist>
                      </>
                    )}
                  </div>
                </div>
                <div className="grid grid-cols-2 gap-3">
                  <div className="flex flex-col">
                    <label className="text-[10px] font-bold text-text-muted uppercase mb-1">Extra WHERE — Source</label>
                    <input value={extraWhereSrc} onChange={e => setExtraWhereSrc(e.target.value)} placeholder="e.g. status = 'active'" className="px-2.5 py-2 text-[11px] font-mono bg-bg-input border border-border-input rounded text-text-input placeholder-slate-500 focus:border-blue-500 outline-none" />
                  </div>
                  <div className="flex flex-col">
                    <label className="text-[10px] font-bold text-text-muted uppercase mb-1">Extra WHERE — Target</label>
                    <input value={extraWhereTgt} onChange={e => setExtraWhereTgt(e.target.value)} placeholder="e.g. status = 'active'" className="px-2.5 py-2 text-[11px] font-mono bg-bg-input border border-border-input rounded text-text-input placeholder-slate-500 focus:border-blue-500 outline-none" />
                  </div>
                </div>
                <div className="flex items-center gap-2">
                  <label className="text-[10px] font-bold text-text-muted uppercase">Row Limit:</label>
                  <input type="number" min={0} value={rowLimit} onChange={e => setRowLimit(e.target.value)} placeholder="No limit" className="px-2.5 py-1.5 text-[11px] font-mono bg-bg-input border border-border-input rounded w-28 outline-none focus:border-blue-500 text-text-input" />
                  <span className="text-[10px] text-text-muted">rows (0 = unlimited)</span>
                </div>
              </div>
            )}

            {/* ── Visual Builder Mode ── */}
            {queryMode === 'join' && (
              <div className="flex flex-col gap-4 animate-in fade-in slide-in-from-bottom-2 duration-300">
                <p className="text-[11px] text-text-muted leading-relaxed bg-bg-hover rounded-lg p-3 border border-border-item">
                  <strong className="text-blue-500">Visual Query Builder:</strong> Select multiple tables and choose exactly which columns you want to extract. 
                  The `ON` join clause will be automatically guessed if columns match.
                </p>

                <div className="grid grid-cols-2 gap-6 relative">
                  <div className="absolute left-1/2 top-0 bottom-0 w-px bg-border-main hidden md:block"></div>
                  
                  {/* SOURCE SIDE */}
                  <div className="flex flex-col gap-2">
                    <div className="flex flex-col">
                      <label className="text-[10px] font-bold text-blue-500 uppercase tracking-widest mb-1 flex items-center gap-1.5">
                        <Database className="w-3 h-3" /> Base Source Table
                      </label>
                      <input list="source-tables-join" value={sourceTable} onChange={e => setSourceTable(e.target.value)} placeholder="Main source table..." className="w-full px-2.5 py-2 bg-bg-input border border-border-input rounded-md text-xs text-text-input outline-none focus:border-blue-500 shadow-sm" />
                    </div>
                    {renderColumnSelector('Base Table Columns (t0)', sourceBaseCols, sourceBaseSelected, 
                      (c) => toggleColumn('source', 'base', 0, c),
                      () => toggleAllColumns('source', 'base', 0, sourceBaseCols, sourceBaseSelected)
                    )}
                    {renderJoinEditor('source')}
                    
                    {sourceTable && (
                      <div className="mt-3 bg-bg-editor rounded-lg p-3 border border-border-item">
                        <span className="text-[9px] font-bold text-text-muted uppercase tracking-wider mb-2 block">Generated Source SQL</span>
                        <div className="font-mono text-[10px] text-blue-400 whitespace-pre-wrap">{buildJoinQuery('source')}</div>
                      </div>
                    )}
                  </div>

                  {/* TARGET SIDE */}
                  <div className="flex flex-col gap-2">
                    <div className="flex flex-col">
                      <label className="text-[10px] font-bold text-emerald-500 uppercase tracking-widest mb-1 flex items-center gap-1.5">
                        <Database className="w-3 h-3" /> Base Target Table
                      </label>
                      <input list="target-tables-join" value={targetTable} onChange={e => setTargetTable(e.target.value)} placeholder="Main target table..." className="w-full px-2.5 py-2 bg-bg-input border border-border-input rounded-md text-xs text-text-input outline-none focus:border-emerald-500 shadow-sm" />
                    </div>
                    {renderColumnSelector('Base Table Columns (t0)', targetBaseCols, targetBaseSelected, 
                      (c) => toggleColumn('target', 'base', 0, c),
                      () => toggleAllColumns('target', 'base', 0, targetBaseCols, targetBaseSelected)
                    )}
                    {renderJoinEditor('target')}

                    {targetTable && (
                      <div className="mt-3 bg-bg-editor rounded-lg p-3 border border-border-item">
                        <span className="text-[9px] font-bold text-text-muted uppercase tracking-wider mb-2 block">Generated Target SQL</span>
                        <div className="font-mono text-[10px] text-emerald-400 whitespace-pre-wrap">{buildJoinQuery('target')}</div>
                      </div>
                    )}
                  </div>
                </div>

                <div className="flex items-center gap-2 mt-2">
                  <label className="text-[10px] font-bold text-text-muted uppercase">Row Limit:</label>
                  <input type="number" min={0} value={rowLimit} onChange={e => setRowLimit(e.target.value)} placeholder="No limit" className="px-2.5 py-1.5 text-[11px] font-mono bg-bg-input border border-border-input rounded w-28 outline-none focus:border-blue-500 text-text-input" />
                </div>
              </div>
            )}

            {/* ── Custom SQL Mode ── */}
            {queryMode === 'custom' && (
              <div className="flex flex-col gap-3 animate-in fade-in slide-in-from-bottom-2 duration-300">
                <p className="text-[11px] text-text-muted bg-bg-hover rounded p-2 leading-relaxed">
                  Tulis query SQL bebas. Bisa JOIN beberapa tabel, subquery, CTE, dan lain-lain.
                  Kolom yang dikembalikan harus sama antara source dan target.
                </p>
                <div className="grid grid-cols-2 gap-3">
                  <div className="flex flex-col">
                    <label className="text-[10px] font-bold text-blue-500/70 uppercase tracking-widest mb-1">Source SQL</label>
                    <div className="w-full h-44 border border-border-input rounded-lg overflow-hidden bg-bg-editor">
                      <SQLEditor
                        value={sourceQuery}
                        onChange={(val) => setSourceQuery(val)}
                        connectionId={excelIsTarget ? targetConnectionId : null}
                        placeholder="SELECT * FROM table_name ..."
                        height="100%"
                      />
                    </div>
                  </div>
                  <div className="flex flex-col">
                    <label className="text-[10px] font-bold text-emerald-600/70 uppercase tracking-widest mb-1">Target SQL</label>
                    <div className="w-full h-44 border border-border-input rounded-lg overflow-hidden bg-bg-editor">
                      <SQLEditor
                        value={targetQuery}
                        onChange={(val) => setTargetQuery(val)}
                        connectionId={excelIsTarget ? null : targetConnectionId}
                        placeholder="SELECT * FROM table_name ..."
                        height="100%"
                      />
                    </div>
                  </div>
                </div>
                <div className="grid grid-cols-[1fr_auto_1fr] gap-3 items-end">
                  <div className="flex flex-col">
                    <label className="text-[10px] font-bold text-blue-500/70 uppercase tracking-widest mb-1">Source Table (ref only)</label>
                    <input list="source-tables-custom" value={sourceTable} onChange={e => setSourceTable(e.target.value)} placeholder="For display..." className="px-2.5 py-2 bg-bg-input border border-border-input rounded-md text-xs text-text-input outline-none focus:border-blue-500" />
                  </div>
                  <div className="pb-1"><ArrowRight className="w-4 h-4 text-text-muted opacity-50" /></div>
                  <div className="flex flex-col">
                    <label className="text-[10px] font-bold text-emerald-600/70 uppercase tracking-widest mb-1">Target Table (ref only)</label>
                    <input list="target-tables-custom" value={targetTable} onChange={e => setTargetTable(e.target.value)} placeholder="For display..." className="px-2.5 py-2 bg-bg-input border border-border-input rounded-md text-xs text-text-input outline-none focus:border-blue-500" />
                  </div>
                </div>
              </div>
            )}

            {/* ── Advanced Options ── */}
            <button
              onClick={() => setShowAdvanced(!showAdvanced)}
              className="flex items-center gap-1.5 text-[10px] text-blue-500 hover:text-blue-600 dark:text-blue-400 font-medium self-start transition-colors mt-2"
            >
              <Code className="w-3 h-3" />
              {showAdvanced ? 'Hide' : 'Show'} Advanced Options (Primary Keys, Unique Keys, Exclude Columns)
            </button>

            {showAdvanced && (
              <div className="flex flex-col gap-3 pl-3 border-l-2 border-blue-500/30 animate-in slide-in-from-top-1 duration-200">
                <div className="flex flex-col">
                  <label className="text-[10px] font-bold text-text-muted uppercase mb-1">Primary Key Columns <span className="text-[9px] normal-case font-normal">(comma-separated — used as row key for matching)</span></label>
                  <input value={primaryKeys} onChange={e => setPrimaryKeys(e.target.value)} placeholder="e.g. id, order_no" className="px-2.5 py-2 text-[11px] font-mono bg-bg-input border border-border-input rounded text-text-input placeholder-slate-500 focus:border-blue-500 outline-none" />
                </div>
                <div className="flex flex-col">
                  <label className="text-[10px] font-bold text-text-muted uppercase mb-1">Kolom Unik / Unique Key <span className="text-[9px] normal-case font-normal">(comma-separated — used to match rows when no physical primary keys are present, highly recommended for views)</span></label>
                  <input value={sortColumns} onChange={e => setSortColumns(e.target.value)} placeholder="e.g. created_at, customer_id" className="px-2.5 py-2 text-[11px] font-mono bg-bg-input border border-border-input rounded text-text-input placeholder-slate-500 focus:border-blue-500 outline-none" />
                </div>
                <div className="flex flex-col">
                  <label className="text-[10px] font-bold text-text-muted uppercase mb-1">Exclude Columns <span className="text-[9px] normal-case font-normal">(comma-separated — these columns will be ignored in diff)</span></label>
                  <input value={excludeCols} onChange={e => setExcludeCols(e.target.value)} placeholder="e.g. updated_at, created_by" className="px-2.5 py-2 text-[11px] font-mono bg-bg-input border border-border-input rounded text-text-input placeholder-slate-500 focus:border-blue-500 outline-none" />
                </div>
              </div>
            )}
          </div>
        </div>

        <div className="px-5 py-3.5 border-t border-border-main flex justify-end gap-2 bg-bg-header rounded-b-xl shrink-0">
          <button onClick={onClose} className="px-4 py-2 text-xs font-medium border border-orange-500/30 bg-orange-500/15 text-orange-500 hover:bg-orange-500/25 hover:text-orange-400 rounded-md transition-colors">
            Cancel
          </button>
          <button
            onClick={handleSave}
            disabled={!sourceTable && !targetTable && !sourceQuery && !targetQuery}
            className="px-5 py-2 text-xs font-bold bg-blue-600 hover:bg-blue-500 text-white rounded-md disabled:opacity-40 shadow-lg shadow-blue-500/20 transition-all flex items-center gap-1.5"
          >
            {isEdit ? 'Save Changes' : 'Add Mapping'}
          </button>
        </div>
      </div>
    </div>
  );
};
