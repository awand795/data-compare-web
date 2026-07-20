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
        jdbcTemplate.update(
            "INSERT INTO data_warehouse_pipelines (deploy_id, query, source_connection_id, target_table, target_connection_id, target_database) VALUES (?, ?, ?, ?, ?, ?) " +
            "ON CONFLICT (deploy_id) DO UPDATE SET query = EXCLUDED.query, source_connection_id = EXCLUDED.source_connection_id, " +
            "target_table = EXCLUDED.target_table, target_connection_id = EXCLUDED.target_connection_id, target_database = EXCLUDED.target_database",
            deployId, query, sourceConnectionId, targetTable, targetConnectionId, targetDatabase
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
                "SELECT deploy_id, query, source_connection_id, target_table, target_connection_id, target_database FROM data_warehouse_pipelines WHERE deploy_id = ?",
                deployId
            );
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
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
}
