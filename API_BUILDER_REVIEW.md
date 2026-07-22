# 🚀 API Builder — UI/UX Review & Feature Roadmap

> **Dibuat:** 21 Juli 2026  
> **File:** `frontend/src/components/ApiBuilderView.tsx`  
> **Backend:** `ApiEndpointController.java`  

---

## 📋 Daftar Isi

1. [✅ Current Strengths](#-current-strengths)
2. [🚩 UI/UX Issues](#-uiux-issues)
3. [💡 Feature Recommendations](#-feature-recommendations)
4. [🔧 Priority Task Plan (Urgent → Nice-to-Have)](#-priority-task-plan)
5. [🏗️ Architecture Refactor Plan](#%EF%B8%8F-architecture-refactor-plan)
6. [📝 Detailed Code Annotations](#-detailed-code-annotations)

---

## ✅ Current Strengths

| Aspect | Detail |
|--------|--------|
| **Visual Design** | Clean dark theme, HTTP method badges, gradient accents, consistent spacing & rounded corners |
| **UX Flow** | Three clear states: List → Edit → Spec, with smooth transitions between them |
| **Live URL Preview** | Top bar shows rendered endpoint path with params — excellent DX |
| **Auto Parameter Detection** | `:param_name` regex extraction from SQL → auto-syncs to config panel |
| **Integrated Test Console** | Collapsible bottom panel with parameter inputs + JSON response viewer |
| **Spec Export (3 formats)** | cURL, Postman (HTTP), Bruno (.bru) with one-click copy |
| **One-Time Share Links** | Generate shareable spec links with nice success UI |
| **Empty States** | Custom "No APIs yet" and "No parameters" empty states |
| **Pagination Documentation** | Detailed pagination explanation with examples baked into the spec view |

---

## 🚩 UI/UX Issues

### 🔴 P0 — Must Fix

#### 1. No Form Validation Before Save
**Lokasi:** `ApiBuilderView.tsx` — `handleSave()`
**Masalah:** Tidak ada validasi sama sekali:
- Nama API kosong → tersimpan sebagai `"New API"`
- Tidak pilih connection → backend return 500
- SQL query kosong → endpoint tidak berguna
- Path endpoint duplikat → tidak ada peringatan

**Fix:** Tambahkan validasi di frontend sebelum `handleSave()`:
```typescript
const validate = (): string[] => {
  const errors: string[] = [];
  if (!currentApi.name.trim()) errors.push('API name is required');
  if (!currentApi.connectionId) errors.push('Database connection is required');
  if (!currentApi.sqlQuery.trim()) errors.push('SQL query is required');
  if (!currentApi.endpointPath.trim() || currentApi.endpointPath === '/') 
    errors.push('Endpoint path is required');
  // Check for duplicate paths
  const duplicate = endpoints.find(e => 
    e.endpointPath === currentApi.endpointPath && e.id !== currentApi.id
  );
  if (duplicate) errors.push(`Endpoint path "${currentApi.endpointPath}" already exists`);
  return errors;
};
```

#### 2. Connection ID Ditampilkan Mentah
**Lokasi:** Spec view — line:
```tsx
Connection: {currentApi.connectionId}
```
**Masalah:** Yang muncul adalah UUID koneksi, bukan nama koneksi. User tidak tahu database mana yang digunakan.

**Fix:** Cari connection name dari store:
```tsx
const connName = connections.find(c => c.id === currentApi.connectionId)?.name || currentApi.connectionId;
```

#### 3. Save Button Tidak Ada Loading State
**Masalah:** User bisa double-click save → API ter-create 2 kali.

**Fix:**
```tsx
const [isSaving, setIsSaving] = useState(false);

const handleSave = async () => {
  setIsSaving(true);
  try { /* ... */ } 
  finally { setIsSaving(false); }
};
```

### 🟡 P1 — Should Fix

#### 4. No Unsaved Changes Warning
**Masalah:** Kalau user edit lalu klik tombol back (`←`), semua perubahan hilang.

**Fix:** Gunakan `beforeunload` event + `window.onbeforeunload`:
```tsx
useEffect(() => {
  if (isDirty) {
    window.onbeforeunload = () => 'You have unsaved changes!';
  } else {
    window.onbeforeunload = null;
  }
  return () => { window.onbeforeunload = null; };
}, [isDirty]);
```

#### 5. Parameter Cards Terlalu Besar
**Masalah:** Setiap parameter dirender sebagai card (`p-4`) dengan toggle, 2 kolom grid, dll. Kalau ada 10+ parameter, user harus scroll panjang.

**Saran:** Gunakan layout compact seperti tabel:
```
┌──────────┬──────────┬──────────┬──────────┬──────────────┐
│ :id      │ integer  │ Required │ —        │ User ID      │
│ :status  │ string   │ Optional │ 'active' │ Filter status │
└──────────┴──────────┴──────────┴──────────┴──────────────┘
```

#### 6. Test Console Absolute Positioning
**Lokasi:** Line:
```tsx
className={clsx(
  "absolute bottom-0 right-0 lg:w-[calc(100%-58.333333%)] w-full ...",
  ...
)}
```
**Masalah:** Posisi `absolute` menyebabkan overlap di layar kecil atau ketika panel kiri di-scroll. Juga tidak terintegrasi dengan layout grid.

**Saran:** Gunakan grid rows di container utama:
```tsx
<div className="flex-1 grid grid-rows-[1fr_auto]">
  {/* SQL Editor + Config */}
  {/* Test Console (bukan absolute) */}
</div>
```

#### 7. SQL Editor Connection Context Tidak Real-Time
**Masalah:** User ganti connection dropdown, tapi schema SQL editor tidak langsung berubah.

**Fix:** Tambahkan `key` prop ke SQLEditor yang berubah saat connection berubah:
```tsx
<SQLEditor key={currentApi.connectionId} ... />
```

### 🟢 P2 — Nice to Fix

#### 8. No Keyboard Shortcuts
**Saran:**
- `Ctrl+S` → Save API
- `Ctrl+Enter` → Run Test
- `Escape` → Close test console / back to list

#### 9. Accessibility Gaps
- Toggle switch `required` tidak focusable via keyboard
- Icon buttons (`Pencil`, `Trash2`, `Copy`) tanpa `aria-label`
- Warna HTTP method badges tidak dibedakan untuk color blindness — tambahkan icon atau label teks

#### 10. Tidak Ada "Dirty State" Indicator
Tidak ada indikator visual bahwa ada perubahan yang belum disimpan (misal: dot merah di tab, atau border berubah warna).

---

## 💡 Feature Recommendations

### 🌟 High Impact

| Feature | Description | Complexity |
|---------|-------------|------------|
| **Response Schema Inference** | After test, show inferred JSON schema (field name, type, nullability) | Medium |
| **Request History** | Save & replay test parameter sets (like Postman history) | Medium |
| **Query Templates/Snippets** | One-click insert common patterns: "Get by ID", "Search with pagination", "Aggregate" | Low |
| **API Versioning** | Prefix endpoints with `/v1/`, `/v2/` | Low |

### 🧠 Medium Impact

| Feature | Description | Complexity |
|---------|-------------|------------|
| **Bulk Import/Export** | JSON export/import of all API configurations for backup/CI-CD | Medium |
| **OpenAPI/Swagger Export** | Generate OpenAPI 3.0 spec file download | Medium |
| **CORS Configuration** | Allow configuring allowed origins | Low |
| **Rate Limiting Dashboard** | Show requests/hour, avg response time, error rate per endpoint | High |
| **Caching Config** | Toggle response caching with configurable TTL | Medium |

### ✨ Nice-to-Have

| Feature | Description |
|---------|-------------|
| **Response History** | Keep last N responses per endpoint |
| **Webhook on API Call** | Trigger webhook when endpoint is accessed |
| **API Key Rotation** | Auto-rotate auth tokens on a schedule |
| **Endpoint Clone** | Duplicate an existing endpoint as a starting point |
| **Query Explain Plan** | Show `EXPLAIN` output for the SQL query |
| **Dark/Light Preview Toggle** | Toggle theme directly in spec view |

---

## 🔧 Priority Task Plan

### Phase 1 — Critical Fixes (1-2 jam)
Urutan eksekusi:

```
[x] 1. Form validation sebelum save ✅
     - Validasi: nama, connection, SQL, path
     - Tampilkan error sebagai toast atau inline
     
[x] 2. Connection name di spec view ✅
     - Ganti currentApi.connectionId → cari nama dari store
     
[x] 3. Save button loading state ✅
     - + isSaving state, disable button, spinner
     
[x] 4. Fix: Key prop untuk SQLEditor saat connection berubah ✅
     - <SQLEditor key={currentApi.connectionId}>
```

### Phase 2 — UX Improvements (2-3 jam)
```
[x] 5. Unsaved changes warning ✅
     - Dirty state tracking
     - beforeunload event
     - Confirm dialog saat klik "back"
     
[x] 6. Compact parameter cards → table layout ✅
     - Redesign dari card ke table/grid compact
     - Inline editing
     
[x] 7. Fix test console absolute positioning ✅
     - Pindah ke grid layout
     - Responsive behavior
```

### Phase 3 — Polish (1-2 jam)
```
[x] 8. Keyboard shortcuts (Ctrl+S, Ctrl+Enter) ✅
[x] 9. Accessibility: aria-labels, keyboard-nav toggle ✅
[x] 10. Dirty state indicator (dot on tab + badge) ✅
[x] 11. Connection name cache/fallback display ✅
```

### Phase 4 — New Features
```
[x] 12. Query templates / snippets ✅
[ ] 13. Response schema inference
[ ] 14. Bulk import/export
[ ] 15. API versioning
[ ] 16. Endpoint clone (duplicate) ✅
[ ] 17. Response copy + pretty-print toggle ✅
[ ] 18. Error detail collapsible (stack trace) ✅
```

---

## 🏗️ Architecture Refactor Plan

**Current:** ~800 lines in a single file → `ApiBuilderView.tsx`

**Target:** Split into logical modules:

```
frontend/src/components/api-builder/
├── ApiBuilderView.tsx          ← Orchestrator (~80 lines, manages viewMode)
├── ApiListView.tsx             ← Table of APIs
├── ApiEditView.tsx             ← Edit layout (SQL left + Config right)
├── ApiSpecView.tsx             ← Specification view
├── ApiParameterCard.tsx        ← Individual parameter config (compact)
├── TestConsole.tsx             ← Bottom test panel
├── SpecCodeBlock.tsx           ← Code examples (cURL / Postman / Bruno)
└── hooks/
    └── useApiBuilder.ts        ← Custom hook: CRUD, validation, dirty state
```

**State Extraction:**
```
Current (all in component):        Target (custom hook):
- endpoints                        - endpoints
- currentApi                       - currentApi
- parameterMeta                    - parameterMeta
- testResult                       - testResult
- testParams                       - testParams
- isTesting                        - isTesting
- isSaving                         - isSaving
- isTestConsoleOpen                - isTestConsoleOpen
- paramCount                       - paramCount
- generatedShareUrl                - generatedShareUrl
- copiedStates                     - copiedStates
- activeSpecTab                    - activeSpecTab
- isLoadingList                    - methods: handleSave, handleTest, etc.
                                   - computed: isDirty, validationErrors
```

---

## 📝 Detailed Code Annotations

### Issues Found by Line Number

| Line | Issue | Severity |
|------|-------|----------|
| 42 | `connections` from store — perlu pastikan sudah terisi sebelum render | 🟡 |
| 65 | `fetchEndpoints` di `useEffect([], [])` — tidak refresh saat component mount ulang | 🟢 |
| 78 | `currentApi.connectionId` default ke `connections[0]?.id` — bisa null | 🔴 |
| 98-101 | `JSON.parse(api.parameters)` tanpa try-catch lengkap | 🟡 |
| 118-128 | `handleSave` — tidak ada validasi, tidak ada loading state | 🔴 |
| 166-180 | `detectParams` regex — bisa false positive untuk `::text` cast syntax PostgreSQL | 🟢 |
| 215 | Line terlalu panjang (>200 chars) | 🟢 |
| 320 | `handleShare` — tidak handle error spesifik (403, 404) | 🟡 |
| 345 | Spec view: `currentApi.connectionId` ditampilkan sebagai UUID | 🔴 |
| 475-503 | Pagination params di spec view — hardcoded semua, tidak reusable | 🟢 |
| 630 | Test console menggunakan absolute positioning | 🟡 |
| 690 | Test response area — tidak ada pretty-print toggle atau copy button | 🟢 |
| 750+ | Banyak CSS class yang bisa disederhanakan dengan utility | 🟢 |

---

## 📐 CSS / Style Notes

### Current Design Tokens Used

```css
/* Dark theme (default) */
--bg-main: #0b1120;
--bg-panel: #101827;
--bg-editor: #080e1a;
--text-main: #e2e8f0;
--text-muted: #64748b;
--border-main: rgba(55, 75, 105, 0.7);
```

### Consistency Issues

- **Border radius:** Ada yang `rounded-lg` (8px), `rounded-xl` (12px), `rounded-2xl` (16px) — tidak konsisten
- **Shadow:** Ada yang `shadow-sm`, `shadow-inner`, `shadow-xl`, `shadow-2xl` — terlalu banyak variasi
- **Spacing parameter cards:** `p-4` (16px) dengan jarak `gap-3` (12px) — bisa dikompres jadi `p-3` `gap-2`

---

## 🚀 Quick Win Checklist (Besok Bisa Langsung Kerjain)

- [ ] **P0-1:** Form validation di `handleSave()` — cuma ~30 baris tambahan
- [ ] **P0-2:** Ganti `currentApi.connectionId` dengan `connName` di spec view — ~5 baris
- [ ] **P0-3:** Add `isSaving` state + disable save button — ~10 baris
- [ ] **P0-4:** Add `key={currentApi.connectionId}` ke SQLEditor — 1 baris
- [ ] **P0-5:** Handle error lebih baik di `handleTest` — tampilkan stack trace di response viewer
- [ ] **P1-1:** Track dirty state dengan `useEffect(() => {...}, [currentApi])`
- [ ] **P2-1:** Add `Ctrl+S` handler di edit mode

---

## 📎 Referensi

- **Component file:** `frontend/src/components/ApiBuilderView.tsx` (~800 lines)
- **Backend controller:** `backend/src/main/java/com/dbdiff/controller/ApiEndpointController.java`
- **SQL Editor:** `frontend/src/components/SQLEditor.tsx`
- **Store:** `frontend/src/store/useAppStore.ts`
- **CSS Theme:** `frontend/src/index.css`
