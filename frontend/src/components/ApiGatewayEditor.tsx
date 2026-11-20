import React, { useMemo, useState } from 'react';
import {
  Plus, Trash2, ChevronDown, ChevronUp, Globe, Database, GitBranch,
  Layers, Code2, ShieldAlert, Clock, RotateCcw, Sparkles, ArrowRight
} from 'lucide-react';
import clsx from 'clsx';
import type { EndpointTarget } from './EndpointTargetsModal';

export interface GatewayRoute {
  id: string;
  name: string;
  enabled: boolean;
  targetType: 'HTTP' | 'SQL';
  condition: string;
  responseKey: string;
  // HTTP fields
  method: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';
  url: string;
  contentType: 'JSON' | 'FORM_URLENCODED' | 'NONE';
  authType: 'NONE' | 'BEARER' | 'BASIC' | 'API_KEY';
  authValue: string;
  headers: Record<string, string>;
  queryParams: Record<string, string>;
  bodyTemplate: string;
  requestScript: string;
  // SQL fields
  connectionId: string;
  sqlQuery: string;
  // Resilience
  timeoutMs: number;
  retryCount: number;
  errorPolicy: 'FAIL_FAST' | 'CONTINUE_ON_ERROR';
  // Per-route response transform
  responseMapping: string;
  responseScript: string;
}

export interface GatewayConfig {
  executionMode: 'SWITCH' | 'AGGREGATE';
  routes: GatewayRoute[];
  responseTemplate: string;
  responseScript: string;
}

export const DEFAULT_GATEWAY_CONFIG: GatewayConfig = {
  executionMode: 'SWITCH',
  routes: [
    {
      id: 'route-1',
      name: 'Primary Upstream API',
      enabled: true,
      targetType: 'HTTP',
      condition: '',
      responseKey: 'primary',
      method: 'POST',
      url: 'https://api.example.com/v1/endpoint',
      contentType: 'JSON',
      authType: 'NONE',
      authValue: '',
      headers: {},
      queryParams: {},
      bodyTemplate: '{\n  "id": "{{id}}"\n}',
      requestScript: '',
      connectionId: '',
      sqlQuery: '',
      timeoutMs: 15000,
      retryCount: 0,
      errorPolicy: 'FAIL_FAST',
      responseMapping: '',
      responseScript: ''
    }
  ],
  responseTemplate: '',
  responseScript: ''
};

export function parseGatewayConfig(raw?: string): GatewayConfig {
  if (!raw || !raw.trim()) return JSON.parse(JSON.stringify(DEFAULT_GATEWAY_CONFIG));
  try {
    const parsed = JSON.parse(raw);
    return {
      executionMode: parsed.executionMode === 'AGGREGATE' ? 'AGGREGATE' : 'SWITCH',
      routes: Array.isArray(parsed.routes) && parsed.routes.length > 0
        ? parsed.routes.map((r: any, idx: number) => ({
            id: r.id || `route-${idx + 1}`,
            name: r.name || `Route ${idx + 1}`,
            enabled: r.enabled !== false,
            targetType: r.targetType === 'SQL' ? 'SQL' : 'HTTP',
            condition: r.condition || '',
            responseKey: r.responseKey || `route_${idx + 1}`,
            method: r.method || 'GET',
            url: r.url || '',
            contentType: r.contentType || 'JSON',
            authType: r.authType || 'NONE',
            authValue: r.authValue || '',
            headers: r.headers && typeof r.headers === 'object' ? r.headers : {},
            queryParams: r.queryParams && typeof r.queryParams === 'object' ? r.queryParams : {},
            bodyTemplate: r.bodyTemplate || '',
            requestScript: r.requestScript || '',
            connectionId: r.connectionId || '',
            sqlQuery: r.sqlQuery || '',
            timeoutMs: typeof r.timeoutMs === 'number' ? r.timeoutMs : 15000,
            retryCount: typeof r.retryCount === 'number' ? r.retryCount : 0,
            errorPolicy: r.errorPolicy === 'CONTINUE_ON_ERROR' ? 'CONTINUE_ON_ERROR' : 'FAIL_FAST',
            responseMapping: r.responseMapping || '',
            responseScript: r.responseScript || ''
          }))
        : JSON.parse(JSON.stringify(DEFAULT_GATEWAY_CONFIG.routes)),
      responseTemplate: parsed.responseTemplate || '',
      responseScript: parsed.responseScript || ''
    };
  } catch {
    return JSON.parse(JSON.stringify(DEFAULT_GATEWAY_CONFIG));
  }
}

interface ApiGatewayEditorProps {
  gatewayConfigJson?: string;
  onChange: (newJson: string) => void;
  connections: Array<{ id: string; name: string; type: string }>;
  targets: EndpointTarget[];
}

export const ApiGatewayEditor: React.FC<ApiGatewayEditorProps> = ({
  gatewayConfigJson,
  onChange,
  connections,
  targets
}) => {
  const config = useMemo(() => parseGatewayConfig(gatewayConfigJson), [gatewayConfigJson]);
  const [expandedRouteId, setExpandedRouteId] = useState<string>(config.routes[0]?.id || '');
  const [activeSubTab, setActiveSubTab] = useState<Record<string, 'target' | 'transform' | 'resilience'>>({});

  const updateConfig = (next: GatewayConfig) => {
    onChange(JSON.stringify(next, null, 2));
  };

  const updateRoute = (idx: number, patch: Partial<GatewayRoute>) => {
    const nextRoutes = config.routes.map((r, i) => (i === idx ? { ...r, ...patch } : r));
    updateConfig({ ...config, routes: nextRoutes });
  };

  const addRoute = () => {
    const newId = `route-${Date.now().toString().slice(-4)}`;
    const newRoute: GatewayRoute = {
      id: newId,
      name: `Route ${config.routes.length + 1}`,
      enabled: true,
      targetType: 'HTTP',
      condition: '',
      responseKey: `route_${config.routes.length + 1}`,
      method: 'GET',
      url: 'https://api.example.com/v1/resource',
      contentType: 'JSON',
      authType: 'NONE',
      authValue: '',
      headers: {},
      queryParams: {},
      bodyTemplate: '',
      requestScript: '',
      connectionId: connections[0]?.id || '',
      sqlQuery: '',
      timeoutMs: 15000,
      retryCount: 0,
      errorPolicy: 'FAIL_FAST',
      responseMapping: '',
      responseScript: ''
    };
    updateConfig({ ...config, routes: [...config.routes, newRoute] });
    setExpandedRouteId(newId);
  };

  const removeRoute = (idx: number) => {
    if (config.routes.length <= 1) return;
    const nextRoutes = config.routes.filter((_, i) => i !== idx);
    updateConfig({ ...config, routes: nextRoutes });
  };

  const applyTargetPreset = (idx: number, targetId: string) => {
    const t = targets.find(x => x.id === targetId);
    if (!t) return;
    let parsedHeaders: Record<string, string> = {};
    try {
      if (t.headers) parsedHeaders = JSON.parse(t.headers);
    } catch {}
    updateRoute(idx, {
      name: config.routes[idx].name || t.name,
      url: t.url,
      method: (t.method as any) || 'POST',
      headers: parsedHeaders
    });
  };

  const renderKeyValueEditor = (
    label: string,
    obj: Record<string, string>,
    onUpdate: (nextObj: Record<string, string>) => void
  ) => {
    const entries = Object.entries(obj || {});
    return (
      <div className="space-y-1.5">
        <div className="flex items-center justify-between">
          <span className="text-[11px] font-bold text-text-muted uppercase tracking-wider">{label}</span>
          <button
            type="button"
            onClick={() => onUpdate({ ...obj, '': '' })}
            className="text-[11px] text-blue-400 hover:text-blue-300 flex items-center gap-1 font-semibold cursor-pointer"
          >
            <Plus className="w-3 h-3" /> Add
          </button>
        </div>
        {entries.length === 0 ? (
          <div className="text-[11px] text-text-muted/60 italic px-2 py-1.5 bg-bg-main/40 rounded-lg border border-dashed border-border-main/60">
            No {label.toLowerCase()} configured
          </div>
        ) : (
          <div className="space-y-1.5">
            {entries.map(([k, v], i) => (
              <div key={i} className="flex items-center gap-1.5">
                <input
                  type="text"
                  placeholder="Key"
                  value={k}
                  onChange={e => {
                    const next: Record<string, string> = {};
                    entries.forEach(([ek, ev], ei) => {
                      next[ei === i ? e.target.value : ek] = ev;
                    });
                    onUpdate(next);
                  }}
                  className="w-2/5 bg-bg-editor border border-border-main rounded-lg px-2.5 py-1 text-xs font-mono text-text-main focus:border-blue-500 outline-none"
                />
                <input
                  type="text"
                  placeholder="Value (supports {{param}})"
                  value={v}
                  onChange={e => {
                    const next = { ...obj, [k]: e.target.value };
                    onUpdate(next);
                  }}
                  className="flex-1 bg-bg-editor border border-border-main rounded-lg px-2.5 py-1 text-xs font-mono text-text-main focus:border-blue-500 outline-none"
                />
                <button
                  type="button"
                  onClick={() => {
                    const next: Record<string, string> = {};
                    entries.forEach(([ek, ev], ei) => {
                      if (ei !== i) next[ek] = ev;
                    });
                    onUpdate(next);
                  }}
                  className="p-1 text-rose-400 hover:bg-rose-500/10 rounded-lg cursor-pointer"
                >
                  <Trash2 className="w-3.5 h-3.5" />
                </button>
              </div>
            ))}
          </div>
        )}
      </div>
    );
  };

  return (
    <div className="flex flex-col h-full bg-[#080e1a] overflow-hidden">
      {/* TOP TOOLBAR */}
      <div className="bg-bg-panel/95 border-b border-border-main px-4 py-2.5 flex flex-wrap items-center justify-between gap-2 shrink-0">
        <div className="flex items-center gap-2.5">
          <div className="w-7 h-7 rounded-lg bg-purple-500/15 border border-purple-500/30 flex items-center justify-center text-purple-400">
            <GitBranch className="w-4 h-4" />
          </div>
          <div>
            <div className="text-xs font-black text-text-main uppercase tracking-wider">API Gateway Studio</div>
            <div className="text-[10px] text-text-muted">Multi-API Routing, Payload Transform & Aggregation</div>
          </div>
        </div>

        <div className="flex items-center gap-2">
          <div className="flex items-center bg-bg-editor p-0.5 rounded-xl border border-border-main">
            <button
              type="button"
              onClick={() => updateConfig({ ...config, executionMode: 'SWITCH' })}
              className={clsx(
                'px-2.5 py-1 rounded-lg text-[11px] font-bold flex items-center gap-1.5 transition-all cursor-pointer',
                config.executionMode === 'SWITCH'
                  ? 'bg-purple-600 text-white shadow-sm'
                  : 'text-text-muted hover:text-text-main'
              )}
            >
              <GitBranch className="w-3 h-3" /> Dynamic Switch
            </button>
            <button
              type="button"
              onClick={() => updateConfig({ ...config, executionMode: 'AGGREGATE' })}
              className={clsx(
                'px-2.5 py-1 rounded-lg text-[11px] font-bold flex items-center gap-1.5 transition-all cursor-pointer',
                config.executionMode === 'AGGREGATE'
                  ? 'bg-cyan-600 text-white shadow-sm'
                  : 'text-text-muted hover:text-text-main'
              )}
            >
              <Layers className="w-3 h-3" /> Merge / Aggregate
            </button>
          </div>

          <button
            type="button"
            onClick={addRoute}
            className="px-3 py-1.5 bg-blue-600 hover:bg-blue-500 text-white rounded-xl text-xs font-bold flex items-center gap-1.5 shadow-sm cursor-pointer"
          >
            <Plus className="w-3.5 h-3.5" /> Add Route
          </button>
        </div>
      </div>

      {/* BODY SCROLLABLE */}
      <div className="flex-1 overflow-y-auto p-4 space-y-4">
        {/* MODE BANNER */}
        <div className="px-3 py-2 rounded-xl bg-purple-500/10 border border-purple-500/20 text-[11px] text-purple-200 flex items-center gap-2">
          <Sparkles className="w-4 h-4 text-purple-400 shrink-0" />
          {config.executionMode === 'SWITCH' ? (
            <span>
              <strong>Dynamic Switch Mode:</strong> Evaluates route conditions (SpEL like <code className="text-purple-300">#action == 'get_user'</code>) top-to-bottom and executes the first match (or empty condition as Default).
            </span>
          ) : (
            <span>
              <strong>Merge / Aggregate Mode:</strong> Executes all enabled matching routes in parallel and combines results under each route&apos;s <code className="text-cyan-300">Response Key</code>.
            </span>
          )}
        </div>

        {/* ROUTE CARDS */}
        <div className="space-y-3">
          {config.routes.map((route, idx) => {
            const isExpanded = expandedRouteId === route.id;
            const tab = activeSubTab[route.id] || 'target';

            return (
              <div
                key={route.id}
                className={clsx(
                  'rounded-xl border transition-all overflow-hidden',
                  route.enabled
                    ? 'bg-bg-panel/90 border-border-main'
                    : 'bg-bg-panel/40 border-border-main/50 opacity-70'
                )}
              >
                {/* Route Header */}
                <div
                  className="px-3.5 py-2.5 flex items-center justify-between gap-2 cursor-pointer hover:bg-white/5"
                  onClick={() => setExpandedRouteId(isExpanded ? '' : route.id)}
                >
                  <div className="flex items-center gap-2.5 min-w-0">
                    <span className="text-[10px] font-mono font-black px-2 py-0.5 rounded-md bg-slate-800 text-slate-300 border border-slate-700">
                      #{idx + 1}
                    </span>
                    <span
                      className={clsx(
                        'text-[10px] font-black px-2 py-0.5 rounded-md uppercase',
                        route.targetType === 'SQL'
                          ? 'bg-emerald-500/15 text-emerald-400 border border-emerald-500/30'
                          : 'bg-blue-500/15 text-blue-400 border border-blue-500/30'
                      )}
                    >
                      {route.targetType === 'SQL' ? 'SQL DB' : route.method}
                    </span>
                    <span className="text-xs font-bold text-text-main truncate">{route.name || `Route ${idx + 1}`}</span>
                    {route.condition ? (
                      <span className="text-[10px] font-mono text-amber-300 bg-amber-500/10 border border-amber-500/20 px-2 py-0.5 rounded-full truncate max-w-[180px]">
                        if: {route.condition}
                      </span>
                    ) : (
                      <span className="text-[10px] font-mono text-slate-400 bg-slate-800/70 px-2 py-0.5 rounded-full">
                        {config.executionMode === 'SWITCH' ? 'default / fallback' : 'always'}
                      </span>
                    )}
                  </div>

                  <div className="flex items-center gap-2 shrink-0" onClick={e => e.stopPropagation()}>
                    <label className="flex items-center gap-1 text-[11px] text-text-muted cursor-pointer">
                      <input
                        type="checkbox"
                        checked={route.enabled}
                        onChange={e => updateRoute(idx, { enabled: e.target.checked })}
                        className="rounded border-border-main"
                      />
                      Active
                    </label>
                    {config.routes.length > 1 && (
                      <button
                        type="button"
                        onClick={() => removeRoute(idx)}
                        className="p-1 text-rose-400 hover:bg-rose-500/10 rounded-lg cursor-pointer"
                        title="Delete Route"
                      >
                        <Trash2 className="w-3.5 h-3.5" />
                      </button>
                    )}
                    <button
                      type="button"
                      onClick={() => setExpandedRouteId(isExpanded ? '' : route.id)}
                      className="p-1 text-text-muted hover:text-text-main cursor-pointer"
                    >
                      {isExpanded ? <ChevronUp className="w-4 h-4" /> : <ChevronDown className="w-4 h-4" />}
                    </button>
                  </div>
                </div>

                {/* Route Body */}
                {isExpanded && (
                  <div className="border-t border-border-main p-3.5 space-y-3 bg-bg-main/30">
                    {/* Basic Route Metadata */}
                    <div className="grid grid-cols-1 md:grid-cols-4 gap-2.5">
                      <div>
                        <label className="block text-[10px] font-bold text-text-muted uppercase mb-1">Route Name</label>
                        <input
                          type="text"
                          value={route.name}
                          onChange={e => updateRoute(idx, { name: e.target.value })}
                          className="w-full bg-bg-editor border border-border-main rounded-lg px-2.5 py-1.5 text-xs text-text-main outline-none focus:border-blue-500"
                        />
                      </div>
                      <div>
                        <label className="block text-[10px] font-bold text-text-muted uppercase mb-1">Target Type</label>
                        <select
                          value={route.targetType}
                          onChange={e => updateRoute(idx, { targetType: e.target.value as 'HTTP' | 'SQL' })}
                          className="w-full bg-bg-editor border border-border-main rounded-lg px-2.5 py-1.5 text-xs font-bold text-text-main outline-none focus:border-blue-500"
                        >
                          <option value="HTTP">HTTP / REST API</option>
                          <option value="SQL">SQL Database Query</option>
                        </select>
                      </div>
                      <div>
                        <label className="block text-[10px] font-bold text-text-muted uppercase mb-1">Response Key</label>
                        <input
                          type="text"
                          placeholder="e.g. user_profile"
                          value={route.responseKey}
                          onChange={e => updateRoute(idx, { responseKey: e.target.value })}
                          className="w-full bg-bg-editor border border-border-main rounded-lg px-2.5 py-1.5 text-xs font-mono text-cyan-300 outline-none focus:border-blue-500"
                        />
                      </div>
                      <div>
                        <label className="block text-[10px] font-bold text-text-muted uppercase mb-1">Condition (SpEL)</label>
                        <input
                          type="text"
                          placeholder="e.g. #kode_pt == 'SSI'"
                          value={route.condition}
                          onChange={e => updateRoute(idx, { condition: e.target.value })}
                          className="w-full bg-bg-editor border border-border-main rounded-lg px-2.5 py-1.5 text-xs font-mono text-amber-300 outline-none focus:border-blue-500"
                        />
                      </div>
                    </div>

                    {/* Sub-tabs */}
                    <div className="flex items-center gap-1 border-b border-border-main pb-2">
                      <button
                        type="button"
                        onClick={() => setActiveSubTab(prev => ({ ...prev, [route.id]: 'target' }))}
                        className={clsx(
                          'px-2.5 py-1 rounded-lg text-[11px] font-bold flex items-center gap-1 cursor-pointer',
                          tab === 'target' ? 'bg-blue-500/20 text-blue-300 border border-blue-500/30' : 'text-text-muted hover:text-text-main'
                        )}
                      >
                        {route.targetType === 'SQL' ? <Database className="w-3 h-3" /> : <Globe className="w-3 h-3" />}
                        Target &amp; Request
                      </button>
                      <button
                        type="button"
                        onClick={() => setActiveSubTab(prev => ({ ...prev, [route.id]: 'transform' }))}
                        className={clsx(
                          'px-2.5 py-1 rounded-lg text-[11px] font-bold flex items-center gap-1 cursor-pointer',
                          tab === 'transform' ? 'bg-purple-500/20 text-purple-300 border border-purple-500/30' : 'text-text-muted hover:text-text-main'
                        )}
                      >
                        <Code2 className="w-3 h-3" /> Transform (Request / Response)
                      </button>
                      <button
                        type="button"
                        onClick={() => setActiveSubTab(prev => ({ ...prev, [route.id]: 'resilience' }))}
                        className={clsx(
                          'px-2.5 py-1 rounded-lg text-[11px] font-bold flex items-center gap-1 cursor-pointer',
                          tab === 'resilience' ? 'bg-amber-500/20 text-amber-300 border border-amber-500/30' : 'text-text-muted hover:text-text-main'
                        )}
                      >
                        <ShieldAlert className="w-3 h-3" /> Timeout &amp; Retry
                      </button>
                    </div>

                    {/* Sub-tab 1: Target & Request */}
                    {tab === 'target' && route.targetType === 'HTTP' && (
                      <div className="space-y-3">
                        {targets.length > 0 && (
                          <div className="flex items-center gap-2">
                            <span className="text-[10px] font-bold text-text-muted uppercase">Load Saved Target:</span>
                            <select
                              value=""
                              onChange={e => applyTargetPreset(idx, e.target.value)}
                              className="bg-bg-editor border border-border-main rounded-lg px-2.5 py-1 text-xs text-text-main outline-none"
                            >
                              <option value="">-- Optional: Prefill from Endpoint Targets --</option>
                              {targets.map(t => (
                                <option key={t.id} value={t.id}>{t.name} ({t.method} {t.url})</option>
                              ))}
                            </select>
                          </div>
                        )}

                        <div className="flex items-center gap-2">
                          <select
                            value={route.method}
                            onChange={e => updateRoute(idx, { method: e.target.value as any })}
                            className="w-28 bg-bg-editor border border-border-main rounded-lg px-2.5 py-1.5 text-xs font-black text-blue-400 outline-none"
                          >
                            <option value="GET">GET</option>
                            <option value="POST">POST</option>
                            <option value="PUT">PUT</option>
                            <option value="PATCH">PATCH</option>
                            <option value="DELETE">DELETE</option>
                          </select>
                          <input
                            type="text"
                            placeholder="https://api.upstream.com/v1/resource/{{id}}"
                            value={route.url}
                            onChange={e => updateRoute(idx, { url: e.target.value })}
                            className="flex-1 bg-bg-editor border border-border-main rounded-lg px-3 py-1.5 text-xs font-mono text-text-main outline-none focus:border-blue-500"
                          />
                        </div>

                        <div className="grid grid-cols-1 md:grid-cols-3 gap-2.5">
                          <div>
                            <label className="block text-[10px] font-bold text-text-muted uppercase mb-1">Body Format</label>
                            <select
                              value={route.contentType}
                              onChange={e => updateRoute(idx, { contentType: e.target.value as any })}
                              className="w-full bg-bg-editor border border-border-main rounded-lg px-2.5 py-1.5 text-xs text-text-main outline-none"
                            >
                              <option value="JSON">application/json</option>
                              <option value="FORM_URLENCODED">application/x-www-form-urlencoded</option>
                              <option value="NONE">No Body</option>
                            </select>
                          </div>
                          <div>
                            <label className="block text-[10px] font-bold text-text-muted uppercase mb-1">Upstream Auth</label>
                            <select
                              value={route.authType}
                              onChange={e => updateRoute(idx, { authType: e.target.value as any })}
                              className="w-full bg-bg-editor border border-border-main rounded-lg px-2.5 py-1.5 text-xs text-text-main outline-none"
                            >
                              <option value="NONE">None</option>
                              <option value="BEARER">Bearer Token</option>
                              <option value="BASIC">Basic Auth (user:pass)</option>
                              <option value="API_KEY">API Key (Header:Value)</option>
                            </select>
                          </div>
                          <div>
                            <label className="block text-[10px] font-bold text-text-muted uppercase mb-1">Auth Value</label>
                            <input
                              type="text"
                              disabled={route.authType === 'NONE'}
                              placeholder={route.authType === 'BASIC' ? 'user:password' : 'token or X-API-Key:secret'}
                              value={route.authValue}
                              onChange={e => updateRoute(idx, { authValue: e.target.value })}
                              className="w-full bg-bg-editor border border-border-main rounded-lg px-2.5 py-1.5 text-xs font-mono text-text-main outline-none disabled:opacity-40"
                            />
                          </div>
                        </div>

                        <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
                          {renderKeyValueEditor('Query Params', route.queryParams, qp => updateRoute(idx, { queryParams: qp }))}
                          {renderKeyValueEditor('Custom Headers', route.headers, h => updateRoute(idx, { headers: h }))}
                        </div>

                        {route.method !== 'GET' && route.method !== 'DELETE' && route.contentType !== 'NONE' && (
                          <div>
                            <label className="block text-[10px] font-bold text-text-muted uppercase mb-1">
                              Request Body JSON Template (supports <code className="text-cyan-400">{'{{param}}'}</code>)
                            </label>
                            <textarea
                              rows={4}
                              value={route.bodyTemplate}
                              onChange={e => updateRoute(idx, { bodyTemplate: e.target.value })}
                              placeholder={'{\n  "user_id": "{{user_id}}",\n  "amount": {{amount}}\n}'}
                              className="w-full bg-[#050810] border border-border-main rounded-lg p-2.5 text-xs font-mono text-emerald-300 outline-none focus:border-blue-500"
                            />
                          </div>
                        )}
                      </div>
                    )}

                    {tab === 'target' && route.targetType === 'SQL' && (
                      <div className="space-y-3">
                        <div>
                          <label className="block text-[10px] font-bold text-text-muted uppercase mb-1">Database Connection</label>
                          <select
                            value={route.connectionId}
                            onChange={e => updateRoute(idx, { connectionId: e.target.value })}
                            className="w-full bg-bg-editor border border-border-main rounded-lg px-2.5 py-1.5 text-xs font-bold text-emerald-400 outline-none"
                          >
                            <option value="">-- Select Database --</option>
                            {connections.map(c => (
                              <option key={c.id} value={c.id}>{c.name} ({c.type})</option>
                            ))}
                          </select>
                        </div>
                        <div>
                          <label className="block text-[10px] font-bold text-text-muted uppercase mb-1">SQL Query (supports :param)</label>
                          <textarea
                            rows={4}
                            value={route.sqlQuery}
                            onChange={e => updateRoute(idx, { sqlQuery: e.target.value })}
                            placeholder="SELECT * FROM users WHERE id = :id;"
                            className="w-full bg-[#050810] border border-border-main rounded-lg p-2.5 text-xs font-mono text-blue-300 outline-none focus:border-blue-500"
                          />
                        </div>
                      </div>
                    )}

                    {/* Sub-tab 2: Transform */}
                    {tab === 'transform' && (
                      <div className="space-y-3">
                        {route.targetType === 'HTTP' && (
                          <div>
                            <label className="block text-[10px] font-bold text-purple-300 uppercase mb-1">
                              Request Transform Script (Optional JS)
                            </label>
                            <textarea
                              rows={4}
                              value={route.requestScript}
                              onChange={e => updateRoute(idx, { requestScript: e.target.value })}
                              placeholder={'function transformRequest(params, req) {\n  // modify req.url, req.headers, req.queryParams, req.body\n  return req;\n}'}
                              className="w-full bg-[#050810] border border-border-main rounded-lg p-2.5 text-xs font-mono text-purple-200 outline-none focus:border-purple-500"
                            />
                          </div>
                        )}

                        <div>
                          <label className="block text-[10px] font-bold text-cyan-300 uppercase mb-1">
                            Response JSON Template Mapping (Optional — supports <code className="text-cyan-400">{'{{data.field}}'}</code>)
                          </label>
                          <textarea
                            rows={3}
                            value={route.responseMapping}
                            onChange={e => updateRoute(idx, { responseMapping: e.target.value })}
                            placeholder={'{\n  "id": "{{data.user.id}}",\n  "fullName": "{{data.user.nama}}"\n}'}
                            className="w-full bg-[#050810] border border-border-main rounded-lg p-2.5 text-xs font-mono text-cyan-200 outline-none focus:border-cyan-500"
                          />
                        </div>

                        <div>
                          <label className="block text-[10px] font-bold text-emerald-300 uppercase mb-1">
                            Response Transform Script (Optional JS — overrides Template above if filled)
                          </label>
                          <textarea
                            rows={5}
                            value={route.responseScript}
                            onChange={e => updateRoute(idx, { responseScript: e.target.value })}
                            placeholder={'function transformResponse(data, params) {\n  return {\n    ok: true,\n    items: (data.rows || []).map(function(x) { return { id: x.id, label: x.name }; })\n  };\n}'}
                            className="w-full bg-[#050810] border border-border-main rounded-lg p-2.5 text-xs font-mono text-emerald-200 outline-none focus:border-emerald-500"
                          />
                        </div>
                      </div>
                    )}

                    {/* Sub-tab 3: Resilience */}
                    {tab === 'resilience' && (
                      <div className="grid grid-cols-1 md:grid-cols-3 gap-3">
                        <div>
                          <label className="block text-[10px] font-bold text-text-muted uppercase mb-1 flex items-center gap-1">
                            <Clock className="w-3 h-3" /> Timeout (ms)
                          </label>
                          <input
                            type="number"
                            min={500}
                            max={120000}
                            value={route.timeoutMs}
                            onChange={e => updateRoute(idx, { timeoutMs: Number(e.target.value) || 15000 })}
                            className="w-full bg-bg-editor border border-border-main rounded-lg px-2.5 py-1.5 text-xs font-mono text-text-main outline-none"
                          />
                        </div>
                        <div>
                          <label className="block text-[10px] font-bold text-text-muted uppercase mb-1 flex items-center gap-1">
                            <RotateCcw className="w-3 h-3" /> Auto Retry (0 - 5)
                          </label>
                          <input
                            type="number"
                            min={0}
                            max={5}
                            value={route.retryCount}
                            onChange={e => updateRoute(idx, { retryCount: Math.max(0, Math.min(5, Number(e.target.value) || 0)) })}
                            className="w-full bg-bg-editor border border-border-main rounded-lg px-2.5 py-1.5 text-xs font-mono text-text-main outline-none"
                          />
                        </div>
                        <div>
                          <label className="block text-[10px] font-bold text-text-muted uppercase mb-1">On Error Policy</label>
                          <select
                            value={route.errorPolicy}
                            onChange={e => updateRoute(idx, { errorPolicy: e.target.value as any })}
                            className="w-full bg-bg-editor border border-border-main rounded-lg px-2.5 py-1.5 text-xs font-bold text-text-main outline-none"
                          >
                            <option value="FAIL_FAST">FAIL_FAST (Abort &amp; Return Error)</option>
                            <option value="CONTINUE_ON_ERROR">CONTINUE_ON_ERROR (Partial Merge)</option>
                          </select>
                        </div>
                      </div>
                    )}
                  </div>
                )}
              </div>
            );
          })}
        </div>

        {/* GLOBAL FINAL RESPONSE TRANSFORM */}
        <div className="rounded-xl border border-indigo-500/30 bg-indigo-500/5 p-3.5 space-y-3">
          <div className="flex items-center gap-2 text-xs font-black text-indigo-300 uppercase tracking-wider">
            <ArrowRight className="w-4 h-4" /> Global Final Response Combiner (Optional)
          </div>
          <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
            <div>
              <label className="block text-[10px] font-bold text-text-muted uppercase mb-1">
                Final JSON Template (use <code className="text-cyan-300">{'{{routes.<key>.<field>}}'}</code>)
              </label>
              <textarea
                rows={4}
                value={config.responseTemplate}
                onChange={e => updateConfig({ ...config, responseTemplate: e.target.value })}
                placeholder={'{\n  "status": "ok",\n  "user": "{{routes.user.name}}",\n  "orders": "{{routes.orders.total}}"\n}'}
                className="w-full bg-[#050810] border border-border-main rounded-lg p-2.5 text-xs font-mono text-cyan-200 outline-none focus:border-indigo-500"
              />
            </div>
            <div>
              <label className="block text-[10px] font-bold text-text-muted uppercase mb-1">
                Final JS Transform Script (overrides Template above if filled)
              </label>
              <textarea
                rows={4}
                value={config.responseScript}
                onChange={e => updateConfig({ ...config, responseScript: e.target.value })}
                placeholder={'function transformResponse(data, params, routes) {\n  return { success: true, data: data, combined: routes };\n}'}
                className="w-full bg-[#050810] border border-border-main rounded-lg p-2.5 text-xs font-mono text-emerald-200 outline-none focus:border-indigo-500"
              />
            </div>
          </div>
        </div>
      </div>
    </div>
  );
};
