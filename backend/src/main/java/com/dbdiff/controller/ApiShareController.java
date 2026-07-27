package com.dbdiff.controller;

import com.dbdiff.model.ApiEndpoint;
import com.dbdiff.model.ApiShareToken;
import com.dbdiff.repository.ApiEndpointRepository;
import com.dbdiff.repository.ApiShareTokenRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/share")
public class ApiShareController {

    @Autowired
    private ApiShareTokenRepository apiShareTokenRepository;

    @Autowired
    private ApiEndpointRepository apiEndpointRepository;

    @GetMapping("/{token}")
    public ResponseEntity<Map<String, Object>> getShareData(@PathVariable String token, HttpServletRequest request) {
        Map<String, Object> response = new HashMap<>();

        Optional<ApiShareToken> tokenOpt = apiShareTokenRepository.findByToken(token);
        
        if (tokenOpt.isEmpty() || tokenOpt.get().isUsed()) {
            response.put("valid", false);
            response.put("message", "This share link has already been used or has expired.");
            return ResponseEntity.ok(response);
        }
        
        ApiShareToken shareToken = tokenOpt.get();
        Optional<ApiEndpoint> endpointOpt = apiEndpointRepository.findById(shareToken.getApiEndpointId());
        
        if (endpointOpt.isEmpty()) {
            response.put("valid", false);
            response.put("message", "The requested API endpoint no longer exists or the link has expired.");
            return ResponseEntity.ok(response);
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
                         
        response.put("valid", true);
        response.put("endpoint", endpoint);
        response.put("baseUrl", baseUrl);
        return ResponseEntity.ok(response);
    }
}

