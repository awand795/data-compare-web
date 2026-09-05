package com.dbdiff.repository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Map;

@Repository
public class PipelineMetadataRepository {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    public void savePipelineMetadata(String deployId, String query, String sourceConnectionId, String targetTable, String targetConnectionId, String targetDatabase) {
        savePipelineMetadata(deployId, query, sourceConnectionId, targetTable, targetConnectionId, targetDatabase, sourceConnectionId);
    }

    public void savePipelineMetadata(String deployId, String query, String sourceConnectionId, String targetTable, String targetConnectionId, String targetDatabase, String sourceConnectionIds) {
        jdbcTemplate.update(
            "INSERT INTO data_warehouse_pipelines (deploy_id, query, source_connection_id, target_table, target_connection_id, target_database, source_connection_ids) VALUES (?, ?, ?, ?, ?, ?, ?) " +
            "ON CONFLICT (deploy_id) DO UPDATE SET query = EXCLUDED.query, source_connection_id = EXCLUDED.source_connection_id, " +
            "target_table = EXCLUDED.target_table, target_connection_id = EXCLUDED.target_connection_id, target_database = EXCLUDED.target_database, " +
            "source_connection_ids = CASE WHEN EXCLUDED.source_connection_ids IS NOT NULL AND EXCLUDED.source_connection_ids <> '' THEN EXCLUDED.source_connection_ids ELSE data_warehouse_pipelines.source_connection_ids END",
            deployId, query, sourceConnectionId, targetTable, targetConnectionId, targetDatabase, sourceConnectionIds
        );
    }

    // Keep backward compat
    public void saveOriginalQuery(String deployId, String query) {
        jdbcTemplate.update(
            "INSERT INTO data_warehouse_pipelines (deploy_id, query) VALUES (?, ?) ON CONFLICT (deploy_id) DO UPDATE SET query = EXCLUDED.query",
            deployId, query
        );
    }

    public String getOriginalQuery(String deployId) {
        try {
            return jdbcTemplate.queryForObject(
                "SELECT query FROM data_warehouse_pipelines WHERE deploy_id = ?",
                new Object[]{deployId}, String.class
            );
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    public Map<String, Object> getPipelineMetadata(String deployId) {
        try {
            return jdbcTemplate.queryForMap(
                "SELECT deploy_id, query, source_connection_id, target_table, target_connection_id, target_database, source_connection_ids FROM data_warehouse_pipelines WHERE deploy_id = ?",
                deployId
            );
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    public Map<String, Object> findPipelineByTargetTable(String targetTable) {
        if (targetTable == null || targetTable.trim().isEmpty()) return null;
        try {
            java.util.List<Map<String, Object>> list = jdbcTemplate.queryForList(
                "SELECT deploy_id, query, source_connection_id, target_table, target_connection_id, target_database, source_connection_ids, created_at " +
                "FROM data_warehouse_pipelines WHERE target_table = ? ORDER BY created_at DESC LIMIT 1",
                targetTable.trim()
            );
            return list.isEmpty() ? null : list.get(0);
        } catch (Exception e) {
            return null;
        }
    }

    public java.util.Set<String> getAllSourceConnectionIdsForTargetTable(String targetTable) {
        java.util.Set<String> result = new java.util.LinkedHashSet<>();
        if (targetTable == null || targetTable.trim().isEmpty()) return result;
        try {
            java.util.List<Map<String, Object>> list = jdbcTemplate.queryForList(
                "SELECT source_connection_id, source_connection_ids FROM data_warehouse_pipelines WHERE target_table = ? AND (target_database <> 'default' OR target_database IS NULL)",
                targetTable.trim()
            );
            for (Map<String, Object> row : list) {
                String single = (String) row.get("source_connection_id");
                if (single != null && !single.isBlank()) result.add(single.trim());
                String multiple = (String) row.get("source_connection_ids");
                if (multiple != null && !multiple.isBlank()) {
                    for (String s : multiple.split(",")) {
                        if (!s.isBlank()) result.add(s.trim());
                    }
                }
            }
        } catch (Exception ignored) {}
        return result;
    }

    public void updateSourceConnectionIds(String deployId, String sourceConnectionIds) {
        jdbcTemplate.update(
            "UPDATE data_warehouse_pipelines SET source_connection_ids = ? WHERE deploy_id = ?",
            sourceConnectionIds, deployId
        );
    }

    public void updateQuery(String deployId, String newQuery) {
        jdbcTemplate.update(
            "UPDATE data_warehouse_pipelines SET query = ? WHERE deploy_id = ?",
            newQuery, deployId
        );
    }

    public void updateTargetTable(String deployId, String newTargetTable) {
        jdbcTemplate.update(
            "UPDATE data_warehouse_pipelines SET target_table = ? WHERE deploy_id = ?",
            newTargetTable, deployId
        );
    }

    public void deletePipelineMetadata(String deployId) {
        jdbcTemplate.update("DELETE FROM data_warehouse_pipelines WHERE deploy_id = ?", deployId);
    }

    public java.util.List<java.util.Map<String, Object>> getAllPipelinesWithCreatedAt() {
        return jdbcTemplate.queryForList("SELECT deploy_id, created_at FROM data_warehouse_pipelines");
    }

    public void deletePipelinesByDeployIds(java.util.List<String> deployIds) {
        if (deployIds == null || deployIds.isEmpty()) return;
        for (String id : deployIds) {
            jdbcTemplate.update("DELETE FROM data_warehouse_pipelines WHERE deploy_id = ?", id);
        }
    }

    public int countPipelinesBySourceConnectionId(String sourceConnectionId) {
        if (sourceConnectionId == null) return 0;
        Integer count = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM data_warehouse_pipelines WHERE source_connection_id = ?",
            new Object[]{sourceConnectionId}, Integer.class
        );
        return count != null ? count : 0;
    }

    public java.util.List<java.util.Map<String, Object>> getPipelinesBySourceConnectionId(String sourceConnectionId) {
        if (sourceConnectionId == null) return java.util.Collections.emptyList();
        return jdbcTemplate.queryForList(
            "SELECT deploy_id, query, target_table FROM data_warehouse_pipelines WHERE source_connection_id = ?",
            sourceConnectionId
        );
    }
}
