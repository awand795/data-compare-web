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
        
        // Mark as used
        apiShareTokenRepository.markAsUsed(token);
        
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
        
        String fullUrl = baseUrl + "/api/dynamic" + endpoint.getEndpointPath();
        
        String authBadge = endpoint.isPublic() 
            ? "<span class='badge public'>Public</span>" 
            : "<span class='badge auth'>Requires Bearer Token</span>";
            
        String paramsHtml = "";
        if (endpoint.getParameters() != null && !endpoint.getParameters().trim().isEmpty() && !endpoint.getParameters().equals("[]")) {
            paramsHtml = """
                <div class="section">
                    <h2>Parameters</h2>
                    <pre><code>%s</code></pre>
                </div>
                """.formatted(endpoint.getParameters());
        } else {
            paramsHtml = """
                <div class="section">
                    <h2>Parameters</h2>
                    <p class="text-muted">No parameters required.</p>
                </div>
                """;
        }
        
        String queryHtml = "";
        if (endpoint.getQuery() != null && !endpoint.getQuery().trim().isEmpty()) {
            queryHtml = """
                <div class="section">
                    <h2>Base Query</h2>
                    <pre><code class="language-sql">%s</code></pre>
                </div>
                """.formatted(endpoint.getQuery());
        }

        String curlExample = "";
        String curlHeader = endpoint.isPublic() ? "" : " -H \"Authorization: Bearer " + endpoint.getAuthToken() + "\"";
        curlExample = "curl -X " + endpoint.getMethod() + " \"" + fullUrl + "\"" + curlHeader;
        
        String curlHtml = """
                <div class="section">
                    <h2>CURL Example</h2>
                    <pre><code class="language-bash">%s</code></pre>
                </div>
                """.formatted(curlExample);

        return """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                    <meta charset="UTF-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1.0">
                    <title>API Spec - %s</title>
                    <style>
                        body {
                            background-color: #0b1120;
                            color: #e2e8f0;
                            font-family: 'Inter', -apple-system, sans-serif;
                            margin: 0;
                            padding: 2rem 1rem;
                            line-height: 1.6;
                        }
                        .container {
                            max-width: 800px;
                            margin: 0 auto;
                        }
                        .warning {
                            background-color: rgba(245, 158, 11, 0.1);
                            border: 1px solid rgba(245, 158, 11, 0.2);
                            color: #fcd34d;
                            padding: 1rem;
                            border-radius: 6px;
                            margin-bottom: 2rem;
                            display: flex;
                            align-items: center;
                            gap: 0.5rem;
                        }
                        h1 {
                            color: #f8fafc;
                            margin-top: 0;
                            margin-bottom: 0.5rem;
                        }
                        .subtitle {
                            color: #94a3b8;
                            margin-bottom: 2rem;
                        }
                        .section {
                            background-color: #0f172a;
                            border: 1px solid #1e293b;
                            border-radius: 8px;
                            padding: 1.5rem;
                            margin-bottom: 1.5rem;
                        }
                        .section h2 {
                            margin-top: 0;
                            font-size: 1.25rem;
                            color: #f1f5f9;
                            border-bottom: 1px solid #1e293b;
                            padding-bottom: 0.75rem;
                            margin-bottom: 1rem;
                        }
                        .endpoint-row {
                            display: flex;
                            align-items: center;
                            background-color: #1e293b;
                            padding: 0.75rem;
                            border-radius: 6px;
                            gap: 1rem;
                            word-break: break-all;
                        }
                        .method-badge {
                            background-color: %s;
                            color: #fff;
                            font-weight: 600;
                            padding: 0.25rem 0.75rem;
                            border-radius: 4px;
                            font-size: 0.875rem;
                        }
                        .url {
                            font-family: monospace;
                            font-size: 1rem;
                            color: #e2e8f0;
                        }
                        .badge {
                            display: inline-block;
                            padding: 0.25rem 0.75rem;
                            border-radius: 9999px;
                            font-size: 0.75rem;
                            font-weight: 600;
                        }
                        .badge.public {
                            background-color: rgba(34, 197, 94, 0.1);
                            color: #4ade80;
                            border: 1px solid rgba(34, 197, 94, 0.2);
                        }
                        .badge.auth {
                            background-color: rgba(245, 158, 11, 0.1);
                            color: #fbbf24;
                            border: 1px solid rgba(245, 158, 11, 0.2);
                        }
                        pre {
                            background-color: #1e293b;
                            padding: 1rem;
                            border-radius: 6px;
                            overflow-x: auto;
                            margin: 0;
                        }
                        code {
                            font-family: monospace;
                            color: #e2e8f0;
                        }
                        .footer {
                            text-align: center;
                            margin-top: 3rem;
                            color: #64748b;
                            font-size: 0.875rem;
                        }
                        .text-muted {
                            color: #94a3b8;
                        }
                    </style>
                </head>
                <body>
                    <div class="container">
                        <div class="warning">
                            ⚠️ This is a one-time share link. This page will not be accessible again once you close or refresh it.
                        </div>
                        
                        <h1>API Specification</h1>
                        <div class="subtitle">%s</div>
                        
                        <div class="section">
                            <h2>Endpoint</h2>
                            <div class="endpoint-row">
                                <span class="method-badge">%s</span>
                                <span class="url">%s</span>
                            </div>
                        </div>
                        
                        <div class="section">
                            <h2>Authentication</h2>
                            %s
                        </div>
                        
                        %s
                        
                        %s
                        
                        %s
                        
                        <div class="footer">
                            Generated by DataCompare API Builder
                        </div>
                    </div>
                </body>
                </html>
                """.formatted(
                    endpoint.getName(),
                    methodColor,
                    endpoint.getName(),
                    endpoint.getMethod(),
                    fullUrl,
                    authBadge,
                    paramsHtml,
                    queryHtml,
                    curlHtml
                );
    }
}
