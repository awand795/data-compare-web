package com.dbdiff.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;

public class ApiEndpoint {
    private String id;
    private String name;
    private String method;
    private String endpointPath;
    private String connectionId;
    private String sqlQuery;
    private String parameters;
    private boolean enablePagination;
    
    @JsonProperty("isPublic")
    @JsonAlias({"public", "isPublic"})
    private boolean isPublic;

    @JsonProperty("allowRawSql")
    @JsonAlias({"allowRawSql"})
    private boolean allowRawSql;

    private String ipAllowlist;
    private String groupName;

    private String authToken;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getMethod() { return method; }
    public void setMethod(String method) { this.method = method; }

    public String getEndpointPath() { return endpointPath; }
    public void setEndpointPath(String endpointPath) { this.endpointPath = endpointPath; }

    public String getConnectionId() { return connectionId; }
    public void setConnectionId(String connectionId) { this.connectionId = connectionId; }

    public String getSqlQuery() { return sqlQuery; }
    public void setSqlQuery(String sqlQuery) { this.sqlQuery = sqlQuery; }

    public String getParameters() { return parameters; }
    public void setParameters(String parameters) { this.parameters = parameters; }

    public boolean isEnablePagination() { return enablePagination; }
    public void setEnablePagination(boolean enablePagination) { this.enablePagination = enablePagination; }

    @JsonProperty("isPublic")
    public boolean isPublic() { return isPublic; }

    @JsonProperty("isPublic")
    public void setPublic(boolean aPublic) { isPublic = aPublic; }

    @JsonProperty("allowRawSql")
    public boolean isAllowRawSql() { return allowRawSql; }

    @JsonProperty("allowRawSql")
    public void setAllowRawSql(boolean allowRawSql) { this.allowRawSql = allowRawSql; }

    public String getIpAllowlist() { return ipAllowlist; }
    public void setIpAllowlist(String ipAllowlist) { this.ipAllowlist = ipAllowlist; }

    public String getGroupName() { return groupName != null && !groupName.trim().isEmpty() ? groupName : "General"; }
    public void setGroupName(String groupName) { this.groupName = groupName; }

    public String getAuthToken() { return authToken; }
    public void setAuthToken(String authToken) { this.authToken = authToken; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
