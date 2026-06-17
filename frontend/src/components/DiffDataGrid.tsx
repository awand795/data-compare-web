// @ts-nocheck
import React, { useMemo, useCallback } from 'react';
import { useAppStore } from '../store/useAppStore';
import { Database, ArrowRight } from 'lucide-react';
import { buildEffectiveQuery } from '../utils/queryHelpers';

import { DataEditor, GridCellKind } from '@glideapps/glide-data-grid';
import type { GridCell, GridColumn, Theme } from '@glideapps/glide-data-grid';
import '@glideapps/glide-data-grid/dist/index.css';

interface DiffDataGridProps {
  mappingId?: string | null;
  filterStatus: 'ALL' | 'DIFFERENT' | 'SOURCE_ONLY' | 'TARGET_ONLY' | 'IDENTICAL';
  directResult?: any;
}

export const DiffDataGrid: React.FC<DiffDataGridProps> = ({ mappingId, filterStatus, directResult }) => {
  const { diffResults, addToast, theme } = useAppStore();
  const diffResult = directResult || (mappingId ? diffResults[mappingId] : null);

  const filteredData = useMemo(() => {
    if (!diffResult) return [];
    if (filterStatus === 'ALL') return diffResult.rows;
    if (filterStatus === 'IDENTICAL') return diffResult.rows.filter((r: any) => r.status === 'MATCH');
    return diffResult.rows.filter((r: any) => r.status === filterStatus);
  }, [diffResult?._v, diffResult?.rows, filterStatus]);

  const columns = useMemo<GridColumn[]>(() => {
    if (!diffResult) return [];
    const cols: GridColumn[] = [
      { title: 'Status', width: 100, id: 'status' },
      { title: 'Key', width: 150, id: 'rowKey' },
    ];
    diffResult.columns.forEach((colName: string) => {
      cols.push({ title: colName, width: 150, id: colName });
    });
    return cols;
  }, [diffResult?.columns]);

  const getCellContent = useCallback(
    ([colIdx, rowIdx]: readonly [number, number]): GridCell => {
      const row = filteredData[rowIdx];
      const colId = columns[colIdx].id;

      let displayData = '';
      let themeOverride: Partial<Theme> | undefined = undefined;

      if (colId === 'status') {
        displayData = row.status;
        if (row.status === 'DIFFERENT') themeOverride = { textDark: '#f59e0b', bgCell: 'rgba(245, 158, 11, 0.05)' };
        else if (row.status === 'SOURCE_ONLY') themeOverride = { textDark: '#ef4444', bgCell: 'rgba(239, 68, 68, 0.05)' };
        else if (row.status === 'TARGET_ONLY') themeOverride = { textDark: '#10b981', bgCell: 'rgba(16, 185, 129, 0.05)' };
        else themeOverride = { textDark: '#64748b' };
      } else if (colId === 'rowKey') {
        displayData = row.rowKey;
        themeOverride = { textDark: '#3b82f6', baseFontStyle: '600 12px monospace' };
      } else {
        const cell = row.cells[colId];
        if (!cell) {
          displayData = '—';
          themeOverride = { textDark: '#94a3b8' };
        } else if (cell.isDifferent) {
          const src = String(cell.sourceValue ?? 'NULL');
          const tgt = String(cell.targetValue ?? 'NULL');
          displayData = `SRC: ${src} \nTGT: ${tgt}`;
          themeOverride = { textDark: '#f59e0b', bgCell: 'rgba(245, 158, 11, 0.05)', baseFontStyle: '11px monospace' };
        } else {
          displayData = String(cell.sourceValue ?? 'NULL');
          themeOverride = { baseFontStyle: '11px monospace' };
        }
      }

      const isDataCol = colId !== 'status' && colId !== 'rowKey';

      return {
        kind: GridCellKind.Text,
        allowOverlay: true,
        allowWrapping: isDataCol,
        displayData,
        data: displayData,
        themeOverride,
      };
    },
    [filteredData, columns]
  );

  /* ── Export handlers ─────────────────────────────────────────── */

  const buildExportPayload = useCallback(() => {
    if (!mappingId) return null;
    const store = useAppStore.getState();
    const mapping = store.tableMappings.find(m => m.id === mappingId);
    const sourceConn = store.connections.find(c => c.id === store.sourceConnectionId);
    const targetConn = store.connections.find(c => c.id === store.targetConnectionId);
    
    if (!mapping || !sourceConn || !targetConn) return null;
    
    const sqFinal = buildEffectiveQuery(mapping.sourceTable, mapping, 'source');
    const tqFinal = buildEffectiveQuery(mapping.targetTable, mapping, 'target');

    return {
      sourceConnection: sourceConn,
      targetConnection: targetConn,
      tableName: null,
      customQuerySource: sqFinal,
      customQueryTarget: tqFinal,
      primaryKeys: mapping.primaryKeys || null,
      excludeColumns: mapping.excludeColumns || null,
    };
  }, [mappingId]);

  const triggerDownload = async (url: string, filename: string) => {
    const payload = buildExportPayload();
    if (!payload) {
      addToast({ type: 'error', title: 'Export Failed', message: 'Cannot export: table mapping or connection details are missing.' });
      return;
    }

    try {
      document.body.style.cursor = 'wait';
      const response = await fetch(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload),
      });

      if (!response.ok) {
        throw new Error(`Export failed with status: ${response.status}`);
      }

      const blob = await response.blob();
      const downloadUrl = window.URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = downloadUrl;
      link.download = filename;
      document.body.appendChild(link);
      link.click();
      link.remove();
      window.URL.revokeObjectURL(downloadUrl);
    } catch (err: any) {
      console.error(err);
      addToast({ type: 'error', title: 'Export Failed', message: err.message || 'An unexpected error occurred during export.' });
    } finally {
      document.body.style.cursor = 'default';
    }
  };

  const handleExportExcel = useCallback(() => {
    triggerDownload(`/api/export-excel?filterStatus=${filterStatus}`, `data-compare-${mappingId || 'export'}.xlsx`);
  }, [buildExportPayload, filterStatus, mappingId]);

  const handleExportPDF = useCallback(() => {
    triggerDownload(`/api/export-pdf?filterStatus=${filterStatus}`, `data-compare-${mappingId || 'export'}.pdf`);
  }, [buildExportPayload, filterStatus, mappingId]);


  /* ── Empty state ─────────────────────────────────────────────── */

  if (!diffResult) {
    return (
      <div className="h-full flex flex-col items-center justify-center text-slate-500 gap-3">
        <div className="flex items-center gap-2 text-slate-500 opacity-50">
          <Database className="w-6 h-6" />
          <ArrowRight className="w-4 h-4" />
          <Database className="w-6 h-6" />
        </div>
        <span className="text-xs">Select table mappings and click <strong className="text-slate-800 dark:text-slate-200">Compare</strong> to view differences</span>
      </div>
    );
  }

  const getRowHeight = useCallback((rowIdx: number) => {
    const row = filteredData[rowIdx];
    if (row.status === 'DIFFERENT') {
      return 60; // Larger height for DIFFERENT rows to show both SRC and TGT
    }
    return 40; // Default slightly larger height for wrapping normal cells
  }, [filteredData]);

  return (
    <div className="h-full flex flex-col w-full bg-white dark:bg-[#0b1120]">
      <div className="flex-1 overflow-hidden relative">
        {filteredData.length > 0 ? (
          <DataEditor
            getCellContent={getCellContent}
            columns={columns}
            rows={filteredData.length}
            rowHeight={getRowHeight}
            smoothScrollX={true}
            smoothScrollY={true}
            theme={theme === 'dark' ? {
              bgCell: '#0b1120',
              bgHeader: '#0f172a',
              textDark: '#e2e8f0',
              textHeader: '#94a3b8',
              borderColor: '#1e293b',
              fontFamily: 'Inter, sans-serif',
              baseFontStyle: '12px',
              headerFontStyle: '600 11px',
            } : {
              bgCell: '#ffffff',
              bgHeader: '#f8fafc',
              textDark: '#334155',
              textHeader: '#64748b',
              borderColor: '#e2e8f0',
              fontFamily: 'Inter, sans-serif',
              baseFontStyle: '12px',
              headerFontStyle: '600 11px',
            }}
          />
        ) : (
          <div className="p-12 h-full text-center text-xs text-slate-500 flex flex-col items-center justify-center gap-2">
            {diffResult.status === 'comparing' && diffResult.rows.length === 0 ? (
              <>
                <div className="w-5 h-5 border-2 border-blue-500 border-t-transparent rounded-full animate-spin" />
                <span className="text-blue-500 animate-pulse">Comparing in progress...</span>
              </>
            ) : (
              <div className="flex flex-col items-center gap-1">
                <Database className="w-8 h-8 text-slate-500/30" />
                <span>No data matches this filter.</span>
                {diffResult.rows.length > 0 && (
                  <span className="text-[10px] text-slate-500/60">
                    {diffResult.rows.length.toLocaleString()} total rows, none match "{filterStatus.replace(/_/g, ' ')}".
                  </span>
                )}
              </div>
            )}
          </div>
        )}
      </div>

      {/* Footer stats + export */}
      <div className="shrink-0 bg-slate-50 dark:bg-[#0f172a] border-t border-slate-200 dark:border-slate-800 px-3 py-2 flex items-center gap-4 text-xs text-slate-500 dark:text-slate-400">
        <span>Total: <strong className="text-slate-800 dark:text-slate-200">{diffResult.rows.length}</strong> rows</span>
        <span>Source: <strong className="text-slate-800 dark:text-slate-200">{diffResult.totalSourceRows}</strong></span>
        <span>Target: <strong className="text-slate-800 dark:text-slate-200">{diffResult.totalTargetRows}</strong></span>
        <span className="text-amber-500 dark:text-amber-400 font-semibold">Δ {diffResult.totalDifferences}</span>
        <span>Showing: <strong className="text-slate-800 dark:text-slate-200">{filteredData.length}</strong></span>

        {/* Spacer */}
        <div className="flex-1" />

        {/* Export buttons */}
        <div className="flex items-center gap-2">
          <button
            onClick={handleExportExcel}
            className="px-3 py-1.5 bg-green-600/10 text-green-600 dark:text-green-400 hover:bg-green-600/20 border border-green-600/20 rounded-md text-xs font-semibold flex items-center gap-1.5 transition-colors"
            title="Export current view to Excel"
          >
            Excel
          </button>
          <button
            onClick={handleExportPDF}
            className="px-3 py-1.5 bg-red-600/10 text-red-600 dark:text-red-400 hover:bg-red-600/20 border border-red-600/20 rounded-md text-xs font-semibold flex items-center gap-1.5 transition-colors"
            title="Export current view to PDF"
          >
            PDF
          </button>
        </div>
      </div>
    </div>
  );
};
