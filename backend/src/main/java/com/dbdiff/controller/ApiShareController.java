package com.dbdiff.controller;

import com.dbdiff.model.ApiEndpoint;
import com.dbdiff.model.ApiShareToken;
import com.dbdiff.repository.ApiEndpointRepository;
import com.dbdiff.repository.ApiShareTokenRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

@RestController
@RequestMapping("/api/share")
public class ApiShareController {

    @Autowired
    private ApiShareTokenRepository apiShareTokenRepository;

    @Autowired
    private ApiEndpointRepository apiEndpointRepository;

    @GetMapping(value = "/{token}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> getSharePage(@PathVariable String token, HttpServletRequest request) {
        Optional<ApiShareToken> tokenOpt = apiShareTokenRepository.findByToken(token);
        
        if (tokenOpt.isEmpty() || tokenOpt.get().isUsed()) {
            return ResponseEntity.ok(getExpiredHtml());
        }
        
        ApiShareToken shareToken = tokenOpt.get();
        Optional<ApiEndpoint> endpointOpt = apiEndpointRepository.findById(shareToken.getApiEndpointId());
        
        if (endpointOpt.isEmpty()) {
            return ResponseEntity.ok(getExpiredHtml());
        }
        
        ApiEndpoint endpoint = endpointOpt.get();
        
        // Ignore browser pre-fetch / pre-render background requests so the user's actual view is preserved
        String prefetchHeader = request.getHeader("Sec-Purpose");
        if (prefetchHeader == null) prefetchHeader = request.getHeader("Purpose");
        boolean isPrefetch = prefetchHeader != null && (prefetchHeader.contains("prefetch") || prefetchHeader.contains("prerender"));
        
        if (!isPrefetch) {
            // Mark as USED immediately on first real human view!
            apiShareTokenRepository.markAsUsed(token);
        }
        
        String baseUrl = request.getScheme() + "://" + request.getServerName() + 
                         (request.getServerPort() != 80 && request.getServerPort() != 443 ? ":" + request.getServerPort() : "");
                         
        return ResponseEntity.ok(getSpecHtml(endpoint, baseUrl));
    }
    
    private String getExpiredHtml() {
        return """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                    <meta charset="UTF-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1.0">
                    <title>Link Expired</title>
                    <style>
                        body {
                            background-color: #0b1120;
                            color: #e2e8f0;
                            font-family: 'Inter', -apple-system, sans-serif;
                            display: flex;
                            justify-content: center;
                            align-items: center;
                            height: 100vh;
                            margin: 0;
                        }
                        .container {
                            text-align: center;
                            padding: 2rem;
                            border: 1px solid #1e293b;
                            border-radius: 8px;
                            background-color: #0f172a;
                            max-width: 400px;
                        }
                        .icon {
                            font-size: 3rem;
                            margin-bottom: 1rem;
                        }
                        h1 {
                            margin: 0 0 1rem 0;
                            font-size: 1.5rem;
                            color: #f8fafc;
                        }
                        p {
                            margin: 0;
                            color: #94a3b8;
                        }
                    </style>
                </head>
                <body>
                    <div class="container">
                        <div class="icon">🔒</div>
                        <h1>Link Expired</h1>
                        <p>This share link has already been used or has expired.</p>
                    </div>
                </body>
                </html>
                """;
    }
    
    private String getSpecHtml(ApiEndpoint endpoint, String baseUrl) {
        String methodColor = switch (endpoint.getMethod().toUpperCase()) {
            case "GET" -> "#22c55e";
            case "POST" -> "#3b82f6";
            case "PUT" -> "#f59e0b";
            case "PATCH" -> "#a855f7";
            case "DELETE" -> "#ef4444";
            default -> "#64748b";
        };
        
        String fullUrl = baseUrl + "/api/data" + endpoint.getEndpointPath();
        
        String authBadge = endpoint.isPublic() 
            ? "<span class='badge public'>Public Access</span>" 
            : "<span class='badge auth'>Requires Bearer Token</span>";
            
        // Process parameters into HTML rows
        StringBuilder paramsRows = new StringBuilder();
        try {
            if (endpoint.getParameters() != null && !endpoint.getParameters().trim().isEmpty() && !endpoint.getParameters().equals("[]")) {
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                com.fasterxml.jackson.databind.JsonNode paramsNode = mapper.readTree(endpoint.getParameters());
                if (paramsNode.isArray()) {
                    for (com.fasterxml.jackson.databind.JsonNode param : paramsNode) {
                        String name = param.has("name") ? param.get("name").asText() : "";
                        String type = param.has("type") ? param.get("type").asText() : "string";
                        boolean required = param.has("required") && param.get("required").asBoolean();
                        String defaultValue = param.has("defaultValue") && !param.get("defaultValue").asText().isEmpty() ? param.get("defaultValue").asText() : "&mdash;";
                        String desc = param.has("description") && !param.get("description").asText().isEmpty() ? param.get("description").asText() : "&mdash;";
                        
                        String requiredBadge = required 
                            ? "<span class='req-badge required'>Required</span>" 
                            : "<span class='req-badge optional'>Optional</span>";
                            
                        paramsRows.append("<tr>");
                        paramsRows.append("<td><code>").append(name).append("</code></td>");
                        paramsRows.append("<td class='capitalize'>").append(type).append("</td>");
                        paramsRows.append("<td>").append(requiredBadge).append("</td>");
                        paramsRows.append("<td class='mono text-muted'>").append(defaultValue).append("</td>");
                        paramsRows.append("<td class='text-muted'>").append(desc).append("</td>");
                        paramsRows.append("</tr>");
                    }
                }
            }
        } catch (Exception e) {
            paramsRows.append("<tr><td colspan='5'>Error parsing parameters.</td></tr>");
        }

        // Pagination Docs
        String paginationHtml = "";
        if (endpoint.isEnablePagination()) {
            paramsRows.append("<tr class='pagination-row'>");
            paramsRows.append("<td><code>limit</code> <span class='alias'>(or size)</span></td>");
            paramsRows.append("<td class='capitalize'>integer</td>");
            paramsRows.append("<td><span class='req-badge optional'>Optional</span></td>");
            paramsRows.append("<td class='mono text-muted'>100</td>");
            paramsRows.append("<td class='text-muted'>Number of records to return (or items per page if using <code>size</code>).</td>");
            paramsRows.append("</tr>");
            
            paramsRows.append("<tr class='pagination-row'>");
            paramsRows.append("<td><code>offset</code> <span class='alias'>(or page)</span></td>");
            paramsRows.append("<td class='capitalize'>integer</td>");
            paramsRows.append("<td><span class='req-badge optional'>Optional</span></td>");
            paramsRows.append("<td class='mono text-muted'>0</td>");
            paramsRows.append("<td class='text-muted'>Number of records to skip (or page number starting from 1 if using <code>page</code>).</td>");
            paramsRows.append("</tr>");
            
            paginationHtml = """
                <div class="info-card">
                    <div class="info-icon">📄</div>
                    <div>
                        <h4>Pagination Enabled</h4>
                        <p style="margin-bottom: 0.75rem;">This endpoint supports automatic pagination. You can control the result set using two methods:</p>
                        <ul style="margin: 0; padding-left: 1.5rem; color: var(--text-muted); font-size: 0.95rem; line-height: 1.6;">
                            <li><strong>Limit/Offset:</strong> Use <code>limit</code> (max items to return) and <code>offset</code> (exact number of items to skip).</li>
                            <li><strong>Page/Size:</strong> Use <code>page</code> (page number starting from 1) and <code>size</code> (items per page).</li>
                        </ul>
                        <p style="margin-top: 0.75rem; margin-bottom: 0; color: var(--text-muted); font-size: 0.9rem;">
                            <em>Example: <code>?page=2&size=50</code> will return items 51 to 100.</em>
                        </p>
                    </div>
                </div>
            """;
        }

        String paramsTableHtml = paramsRows.length() > 0 ? """
            <div class="table-container">
                <table>
                    <thead>
                        <tr>
                            <th>Name</th>
                            <th>Type</th>
                            <th>Required</th>
                            <th>Default</th>
                            <th>Description</th>
                        </tr>
                    </thead>
                    <tbody>
                        %s
                    </tbody>
                </table>
            </div>
        """.formatted(paramsRows.toString()) : "<p class='text-muted'>No parameters required for this endpoint.</p>";

        // Auth Docs
        String authHtml = endpoint.isPublic() ? """
            <div class="info-card success">
                <div class="info-icon">🛡️</div>
                <div>
                    <h4>Public Endpoint</h4>
                    <p>No authorization headers are required to access this endpoint.</p>
                </div>
            </div>
        """ : """
            <div class="info-card warning">
                <div class="info-icon">🔐</div>
                <div class="w-full">
                    <h4>Protected Endpoint</h4>
                    <p>Include the following header in your HTTP requests:</p>
                    <div class="copy-box mt-3">
                        <code id="authHeader">Authorization: Bearer %s</code>
                        <button onclick="copyText('authHeader')">Copy</button>
                    </div>
                </div>
            </div>
        """.formatted(endpoint.getAuthToken());

        // cURL builder
        StringBuilder curlCmd = new StringBuilder("curl -X " + endpoint.getMethod() + " ");
        
        // Build URL with query params for GET
        boolean hasParams = paramsRows.length() > 0;
        String finalUrl = fullUrl;
        
        if (endpoint.getMethod().equalsIgnoreCase("GET") && hasParams) {
            finalUrl += "?param=value";
            if (endpoint.isEnablePagination()) finalUrl += "&limit=100&offset=0";
        }
        
        curlCmd.append("\"").append(finalUrl).append("\" \\\n");
        curlCmd.append("  -H \"Accept: application/json\"");
        
        if (!endpoint.isPublic()) {
            curlCmd.append(" \\\n  -H \"Authorization: Bearer ").append(endpoint.getAuthToken()).append("\"");
        }
        
        if (!endpoint.getMethod().equalsIgnoreCase("GET") && hasParams) {
            curlCmd.append(" \\\n  -H \"Content-Type: application/json\" \\\n  -d '{\n    \"param\": \"value\"\n  }'");
        }

        // Response format
        String responseHtml = """
            <div class="response-cards">
                <div class="res-card success">
                    <div class="res-header">
                        <span class="status-code">200 OK</span>
                    </div>
                    <pre><code>[
  {
    "column1": "value1",
    "column2": "value2"
  }
]</code></pre>
                </div>
                <div class="res-card error">
                    <div class="res-header">
                        <span class="status-code err">400 / 401 / 500</span>
                    </div>
                    <pre><code>{
  "error": "Error message description"
}</code></pre>
                </div>
            </div>
        """;

        return """
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>API Spec - %s</title>
    <link rel="preconnect" href="https://fonts.googleapis.com">
    <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
    <link href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700;800&family=JetBrains+Mono:wght@400;500&display=swap" rel="stylesheet">
    <style>
        :root {
            --bg-main: #0b1120;
            --bg-card: #0f172a;
            --bg-card-hover: #1e293b;
            --border: #1e293b;
            --text-main: #f8fafc;
            --text-muted: #94a3b8;
            --primary: #3b82f6;
            --primary-glow: rgba(59, 130, 246, 0.15);
            --method-color: %s;
        }
        
        body {
            background-color: var(--bg-main);
            color: var(--text-main);
            font-family: 'Inter', -apple-system, sans-serif;
            margin: 0;
            padding: 0;
            line-height: 1.6;
            -webkit-font-smoothing: antialiased;
        }
        
        .container {
            max-width: 900px;
            margin: 0 auto;
            padding: 2rem;
        }
        
        /* Header */
        .header {
            margin-bottom: 3rem;
            padding-bottom: 2rem;
            border-bottom: 1px solid var(--border);
        }
        
        .header h1 {
            font-size: 2.5rem;
            font-weight: 800;
            margin: 0 0 0.5rem 0;
            background: linear-gradient(to right, #fff, #94a3b8);
            -webkit-background-clip: text;
            -webkit-text-fill-color: transparent;
        }
        
        .header p {
            color: var(--text-muted);
            margin: 0;
            font-size: 1.1rem;
        }
        
        /* Warning Banner */
        .banner {
            background-color: rgba(245, 158, 11, 0.1);
            border: 1px solid rgba(245, 158, 11, 0.2);
            color: #fcd34d;
            padding: 1rem 1.5rem;
            border-radius: 8px;
            margin-bottom: 2rem;
            display: flex;
            align-items: center;
            gap: 1rem;
            font-size: 0.95rem;
        }
        
        .section {
            background-color: var(--bg-card);
            border: 1px solid var(--border);
            border-radius: 12px;
            padding: 2rem;
            margin-bottom: 2rem;
            box-shadow: 0 4px 6px -1px rgba(0, 0, 0, 0.1), 0 2px 4px -1px rgba(0, 0, 0, 0.06);
        }
        
        .section h2 {
            margin-top: 0;
            font-size: 1.5rem;
            font-weight: 700;
            margin-bottom: 1.5rem;
            display: flex;
            align-items: center;
            gap: 0.75rem;
        }
        
        /* URL Display */
        .url-box {
            display: flex;
            align-items: center;
            background: #000;
            border: 1px solid var(--border);
            border-radius: 8px;
            overflow: hidden;
        }
        
        .method {
            background-color: var(--method-color);
            color: white;
            font-weight: 700;
            padding: 1rem 1.5rem;
            font-size: 0.9rem;
            letter-spacing: 0.05em;
        }
        
        .url {
            font-family: 'JetBrains Mono', monospace;
            padding: 1rem 1.5rem;
            flex: 1;
            color: #e2e8f0;
            overflow-x: auto;
            white-space: nowrap;
        }
        
        /* Buttons */
        button {
            background: var(--bg-card-hover);
            border: 1px solid var(--border);
            color: var(--text-main);
            cursor: pointer;
            padding: 0.5rem 1rem;
            border-radius: 6px;
            font-family: 'Inter', sans-serif;
            font-weight: 500;
            transition: all 0.2s;
        }
        
        button:hover {
            background: #2dd4bf;
            color: #000;
            border-color: #2dd4bf;
        }
        
        .url-box button {
            border: none;
            border-left: 1px solid var(--border);
            border-radius: 0;
            padding: 1rem 1.5rem;
            height: 100%;
            background: transparent;
        }
        
        .url-box button:hover {
            background: rgba(255,255,255,0.1);
            color: white;
        }
        
        /* Tables */
        .table-container {
            overflow-x: auto;
            border: 1px solid var(--border);
            border-radius: 8px;
        }
        
        table {
            width: 100%;
            border-collapse: collapse;
            text-align: left;
        }
        
        th, td {
            padding: 1rem 1.5rem;
            border-bottom: 1px solid var(--border);
        }
        
        th {
            background: rgba(0,0,0,0.2);
            font-weight: 600;
            font-size: 0.85rem;
            text-transform: uppercase;
            letter-spacing: 0.05em;
            color: var(--text-muted);
        }
        
        tr:last-child td {
            border-bottom: none;
        }
        
        tr.pagination-row {
            background: rgba(168, 85, 247, 0.05);
        }
        
        /* Badges */
        .req-badge {
            font-size: 0.75rem;
            padding: 0.25rem 0.6rem;
            border-radius: 999px;
            font-weight: 600;
            text-transform: uppercase;
        }
        
        .req-badge.required {
            background: rgba(245, 158, 11, 0.1);
            color: #fbbf24;
            border: 1px solid rgba(245, 158, 11, 0.2);
        }
        
        .req-badge.optional {
            background: rgba(34, 197, 94, 0.1);
            color: #4ade80;
            border: 1px solid rgba(34, 197, 94, 0.2);
        }
        
        .badge {
            display: inline-block;
            padding: 0.35rem 1rem;
            border-radius: 9999px;
            font-size: 0.85rem;
            font-weight: 600;
            margin-bottom: 1.5rem;
        }
        
        .badge.public { background: rgba(34, 197, 94, 0.1); color: #4ade80; border: 1px solid rgba(34, 197, 94, 0.2); }
        .badge.auth { background: rgba(245, 158, 11, 0.1); color: #fbbf24; border: 1px solid rgba(245, 158, 11, 0.2); }
        
        /* Code & Pre */
        pre {
            background-color: #000;
            padding: 1.5rem;
            border-radius: 8px;
            overflow-x: auto;
            margin: 0;
            border: 1px solid var(--border);
        }
        
        code {
            font-family: 'JetBrains Mono', monospace;
            font-size: 0.9rem;
        }
        
        .copy-box {
            display: flex;
            align-items: center;
            background: #000;
            border: 1px solid var(--border);
            border-radius: 6px;
            padding: 0.5rem 0.5rem 0.5rem 1rem;
            justify-content: space-between;
        }
        
        .copy-box code { color: #f59e0b; }
        
        /* Info Cards */
        .info-card {
            display: flex;
            gap: 1.25rem;
            padding: 1.5rem;
            border-radius: 8px;
            background: rgba(255,255,255,0.02);
            border: 1px solid var(--border);
            margin-bottom: 1.5rem;
        }
        
        .info-card.warning { border-color: rgba(245, 158, 11, 0.3); background: rgba(245, 158, 11, 0.05); }
        .info-card.success { border-color: rgba(34, 197, 94, 0.3); background: rgba(34, 197, 94, 0.05); }
        
        .info-card h4 { margin: 0 0 0.5rem 0; font-size: 1.1rem; }
        .info-card p { margin: 0; color: var(--text-muted); }
        .info-icon { font-size: 1.5rem; }
        
        /* Response Cards */
        .response-cards { display: grid; grid-template-columns: 1fr 1fr; gap: 1.5rem; }
        .res-card { border: 1px solid var(--border); border-radius: 8px; overflow: hidden; }
        .res-header { padding: 0.75rem 1rem; background: var(--bg-card-hover); border-bottom: 1px solid var(--border); }
        .status-code { font-family: 'JetBrains Mono', monospace; font-size: 0.85rem; font-weight: 700; color: #4ade80; }
        .status-code.err { color: #f87171; }
        .res-card pre { border: none; border-radius: 0; }
        
        @media (max-width: 768px) {
            .response-cards { grid-template-columns: 1fr; }
            .url-box { flex-direction: column; align-items: stretch; }
            .url-box button { border-left: none; border-top: 1px solid var(--border); }
        }
        
        .capitalize { text-transform: capitalize; }
        .mono { font-family: 'JetBrains Mono', monospace; }
        .alias { color: #a855f7; font-size: 0.8rem; }
        .w-full { width: 100%; }
        .mt-3 { margin-top: 0.75rem; }
        
        .toast {
            position: fixed;
            bottom: 2rem;
            right: 2rem;
            background: #22c55e;
            color: #000;
            padding: 1rem 2rem;
            border-radius: 8px;
            font-weight: 600;
            transform: translateY(150%);
            transition: transform 0.3s cubic-bezier(0.4, 0, 0.2, 1);
            z-index: 50;
            box-shadow: 0 10px 15px -3px rgba(0,0,0,0.1);
        }
        
        .toast.show { transform: translateY(0); }
    </style>
</head>
<body>
    <div class="container">
        <div class="banner">
            <div>⚠️</div>
            <div><strong>One-time share link.</strong> This documentation will expire and become inaccessible once you close or refresh this page. Save any necessary information now.</div>
        </div>
        
        <div class="header">
            <h1>%s</h1>
            <p>API Specification Documentation</p>
        </div>
        
        <div class="section">
            %s
            <h2>Endpoint Configuration</h2>
            <div class="url-box">
                <div class="method">%s</div>
                <div class="url" id="endpointUrl">%s</div>
                <button onclick="copyText('endpointUrl')">Copy URL</button>
            </div>
        </div>
        
        <div class="section">
            <h2>Authentication</h2>
            %s
        </div>
        
        <div class="section">
            <h2>Parameters</h2>
            %s
            %s
        </div>
        
        <div class="section">
            <h2>cURL Example</h2>
            <div style="position: relative;">
                <pre><code id="curlCode">%s</code></pre>
                <button onclick="copyText('curlCode')" style="position: absolute; top: 1rem; right: 1rem;">Copy</button>
            </div>
        </div>
        
        <div class="section">
            <h2>Response Format</h2>
            <p style="color: var(--text-muted); margin-bottom: 1.5rem;">The API responds with <code>application/json</code> payloads.</p>
            %s
        </div>
    </div>

    <div id="toast" class="toast">Copied to clipboard!</div>

    <script>
        function copyText(elementId) {
            const text = document.getElementById(elementId).innerText;
            navigator.clipboard.writeText(text).then(() => {
                const toast = document.getElementById('toast');
                toast.classList.add('show');
                setTimeout(() => toast.classList.remove('show'), 3000);
            }).catch(err => {
                const textArea = document.createElement("textarea");
                textArea.value = text;
                document.body.appendChild(textArea);
                textArea.select();
                try {
                    document.execCommand('copy');
                    const toast = document.getElementById('toast');
                    toast.classList.add('show');
                    setTimeout(() => toast.classList.remove('show'), 3000);
                } catch (e) {
                    console.error('Failed to copy', e);
                }
                document.body.removeChild(textArea);
            });
        }
    </script>
</body>
</html>
""".formatted(
            endpoint.getName(),
            methodColor,
            authBadge,
            endpoint.getMethod(),
            fullUrl,
            authHtml,
            paginationHtml,
            paramsTableHtml,
            curlCmd.toString(),
            responseHtml
        );
    }
}
