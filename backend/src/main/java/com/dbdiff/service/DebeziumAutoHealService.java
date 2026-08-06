package com.dbdiff.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Service
public class DebeziumAutoHealService {

    private static final Logger logger = LoggerFactory.getLogger(DebeziumAutoHealService.class);
    
    private static final String DEBEZIUM_BASE_URL = System.getenv()
            .getOrDefault("DEBEZIUM_BASE_URL", "http://debezium:8083");
    
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
            
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Runs every 60 seconds
    @Scheduled(fixedDelay = 60000)
    public void checkAndHealConnectors() {
        try {
            HttpRequest getRequest = HttpRequest.newBuilder()
                    .uri(URI.create(DEBEZIUM_BASE_URL + "/connectors"))
                    .GET()
                    .build();
                    
            HttpResponse<String> response = httpClient.send(getRequest, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() != 200) {
                return;
            }
            
            JsonNode connectors = objectMapper.readTree(response.body());
            if (!connectors.isArray()) return;
            
            for (JsonNode connectorNode : connectors) {
                String connectorName = connectorNode.asText();
                checkAndRestartIfFailed(connectorName);
            }
            
        } catch (Exception e) {
            // Log as debug because this might fail frequently if Debezium container is down or starting
            logger.debug("Auto-Heal: Failed to connect to Debezium API: {}", e.getMessage());
        }
    }
    
    private void checkAndRestartIfFailed(String connectorName) {
        try {
            HttpRequest statusReq = HttpRequest.newBuilder()
                    .uri(URI.create(DEBEZIUM_BASE_URL + "/connectors/" + connectorName + "/status"))
                    .GET()
                    .build();
                    
            HttpResponse<String> response = httpClient.send(statusReq, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return;
            
            JsonNode statusJson = objectMapper.readTree(response.body());
            JsonNode connectorStatus = statusJson.path("connector");
            
            boolean isFailed = "FAILED".equalsIgnoreCase(connectorStatus.path("state").asText());
            
            JsonNode tasks = statusJson.path("tasks");
            if (tasks.isArray()) {
                for (JsonNode task : tasks) {
                    if ("FAILED".equalsIgnoreCase(task.path("state").asText())) {
                        isFailed = true;
                        break;
                    }
                }
            }
            
            if (isFailed) {
                logger.warn("Auto-Heal: Detected FAILED state for connector '{}'. Attempting restart...", connectorName);
                
                HttpRequest restartReq = HttpRequest.newBuilder()
                        .uri(URI.create(DEBEZIUM_BASE_URL + "/connectors/" + connectorName + "/restart?includeTasks=true&onlyFailed=true"))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build();
                        
                HttpResponse<String> restartResp = httpClient.send(restartReq, HttpResponse.BodyHandlers.ofString());
                if (restartResp.statusCode() >= 200 && restartResp.statusCode() < 300) {
                    logger.info("Auto-Heal: Successfully sent restart command for connector '{}'", connectorName);
                } else {
                    logger.error("Auto-Heal: Failed to restart connector '{}', HTTP {}", connectorName, restartResp.statusCode());
                }
            }
            
        } catch (Exception e) {
            logger.error("Auto-Heal: Error checking status for connector '{}': {}", connectorName, e.getMessage());
        }
    }
}
