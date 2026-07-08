package com.dbdiff.service;

import com.dbdiff.model.DataWarehouseDeployRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.io.IOException;

@Service
public class DataWarehouseService {
    private static final Logger logger = LoggerFactory.getLogger(DataWarehouseService.class);
    private final RestTemplate restTemplate = new RestTemplate();
    private static final String DEBEZIUM_URL = "http://darkosync-debezium:8083/connectors";

    private void sendLog(SseEmitter emitter, String message) throws IOException {
        logger.info(message);
        emitter.send(SseEmitter.event().data(message));
    }

    public void deployPipeline(DataWarehouseDeployRequest request, SseEmitter emitter) {
        try {
            sendLog(emitter, "Deploying Data Warehouse pipeline for source " + request.getSourceConnection().getName() + " to target table " + request.getTargetTable());
            
            // Generate unique names for connectors
            String baseName = request.getSourceConnection().getName().replaceAll("[^a-zA-Z0-9_-]", "").toLowerCase();
            String sourceConnectorName = "source-" + baseName + "-" + System.currentTimeMillis();
            String sinkConnectorName = "sink-clickhouse-" + request.getTargetTable().replaceAll("[^a-zA-Z0-9_-]", "") + "-" + System.currentTimeMillis();
            
            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);

            // STEP 1: Register Source Connector in Debezium
            sendLog(emitter, "Configuring Debezium Source Connector (" + sourceConnectorName + ")...");
            
            java.util.Map<String, Object> sourceConfig = new java.util.HashMap<>();
            if ("postgresql".equalsIgnoreCase(request.getSourceConnection().getType())) {
                sourceConfig.put("connector.class", "io.debezium.connector.postgresql.PostgresConnector");
                sourceConfig.put("plugin.name", "pgoutput");
            } else if ("mysql".equalsIgnoreCase(request.getSourceConnection().getType())) {
                sourceConfig.put("connector.class", "io.debezium.connector.mysql.MySqlConnector");
            } else {
                sourceConfig.put("connector.class", "io.debezium.connector." + request.getSourceConnection().getType().toLowerCase() + "." + request.getSourceConnection().getType() + "Connector");
            }
            
            sourceConfig.put("tasks.max", "1");
            sourceConfig.put("database.hostname", request.getSourceConnection().getHost());
            sourceConfig.put("database.port", String.valueOf(request.getSourceConnection().getPort()));
            sourceConfig.put("database.user", request.getSourceConnection().getUsername());
            sourceConfig.put("database.password", request.getSourceConnection().getPassword());
            // For MySQL it's database.include.list, for PG it's database.dbname
            sourceConfig.put("database.dbname", request.getSourceConnection().getDatabase());
            sourceConfig.put("database.server.name", sourceConnectorName); 
            
            // Use query as table.include.list (e.g. "public.users, public.orders")
            String tables = request.getQuery() != null && !request.getQuery().trim().isEmpty() ? request.getQuery() : "public." + request.getTargetTable();
            sourceConfig.put("table.include.list", tables);
            
            java.util.Map<String, Object> sourcePayload = new java.util.HashMap<>();
            sourcePayload.put("name", sourceConnectorName);
            sourcePayload.put("config", sourceConfig);
            
            try {
                org.springframework.http.HttpEntity<java.util.Map<String, Object>> sourceEntity = new org.springframework.http.HttpEntity<>(sourcePayload, headers);
                org.springframework.http.ResponseEntity<String> sourceResponse = restTemplate.postForEntity(DEBEZIUM_URL, sourceEntity, String.class);
                sendLog(emitter, "Source connector registered successfully: " + sourceResponse.getStatusCode());
            } catch (Exception e) {
                sendLog(emitter, "ERROR: Could not register source connector in Debezium: " + e.getMessage());
                throw e; // Abort if source fails
            }

            // STEP 2: Wait for Kafka topic to be initialized
            sendLog(emitter, "Waiting for Kafka topic to initialize...");
            Thread.sleep(3000);

            // STEP 3: Register ClickHouse Sink Connector
            sendLog(emitter, "Configuring ClickHouse Sink Connector (" + sinkConnectorName + ") for target " + request.getTargetConnection().getName() + "...");
            
            java.util.Map<String, Object> sinkConfig = new java.util.HashMap<>();
            // Assuming ClickHouse JDBC sink or ClickHouse Sink Connector is installed in the Debezium connect image
            sinkConfig.put("connector.class", "com.clickhouse.kafka.connect.ClickHouseSinkConnector");
            sinkConfig.put("tasks.max", "1");
            sinkConfig.put("topics.regex", sourceConnectorName + ".*");
            sinkConfig.put("clickhouse.server.host", request.getTargetConnection().getHost());
            sinkConfig.put("clickhouse.server.port", String.valueOf(request.getTargetConnection().getPort()));
            sinkConfig.put("clickhouse.server.user", request.getTargetConnection().getUsername());
            sinkConfig.put("clickhouse.server.password", request.getTargetConnection().getPassword());
            sinkConfig.put("clickhouse.database", request.getTargetConnection().getDatabase());
            sinkConfig.put("value.converter", "org.apache.kafka.connect.json.JsonConverter");
            sinkConfig.put("value.converter.schemas.enable", "false");
            
            java.util.Map<String, Object> sinkPayload = new java.util.HashMap<>();
            sinkPayload.put("name", sinkConnectorName);
            sinkPayload.put("config", sinkConfig);
            
            try {
                org.springframework.http.HttpEntity<java.util.Map<String, Object>> sinkEntity = new org.springframework.http.HttpEntity<>(sinkPayload, headers);
                org.springframework.http.ResponseEntity<String> sinkResponse = restTemplate.postForEntity(DEBEZIUM_URL, sinkEntity, String.class);
                sendLog(emitter, "Sink connector registered successfully: " + sinkResponse.getStatusCode());
            } catch (Exception e) {
                sendLog(emitter, "WARNING: Could not register sink connector: " + e.getMessage() + ". Note: You might need to install ClickHouse Sink Connector plugin in Debezium.");
            }
            
            sendLog(emitter, "Pipeline deployment completed successfully.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Deployment interrupted", e);
        } catch (Exception e) {
            logger.error("Failed to deploy pipeline", e);
            try {
                emitter.send(SseEmitter.event().data("DEPLOYMENT FAILED: " + e.getMessage()));
            } catch (IOException ioException) {
                // Ignore
            }
            throw new RuntimeException("Failed to deploy pipeline: " + e.getMessage(), e);
        }
    }
}
