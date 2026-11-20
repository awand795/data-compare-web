package com.dbdiff.service;

import com.dbdiff.model.ApiEndpoint;
import com.dbdiff.model.ConnectionDetails;
import com.dbdiff.repository.ConnectionRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.classic.methods.*;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.entity.UrlEncodedFormEntity;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.NameValuePair;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.http.message.BasicNameValuePair;
import org.apache.hc.core5.net.URIBuilder;
import org.apache.hc.core5.util.Timeout;
import org.mozilla.javascript.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ApiGatewayService {

    private static final Logger log = LoggerFactory.getLogger(ApiGatewayService.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{\\{\\s*([a-zA-Z0-9_.-]+)\\s*\\}\\}");
    private static final ExecutorService GATEWAY_EXECUTOR = Executors.newCachedThreadPool();

    @Autowired
    private ConnectionRepository connectionRepository;

    @Autowired
    private ConnectionManagerService connectionManagerService;

    public static class GatewayResult {
        private int statusCode = 200;
        private Object body;
        private List<Map<String, Object>> debugTrace = new ArrayList<>();
        private long durationMs;
        private String error;

        public int getStatusCode() { return statusCode; }
        public void setStatusCode(int statusCode) { this.statusCode = statusCode; }
        public Object getBody() { return body; }
        public void setBody(Object body) { this.body = body; }
        public List<Map<String, Object>> getDebugTrace() { return debugTrace; }
        public void setDebugTrace(List<Map<String, Object>> debugTrace) { this.debugTrace = debugTrace; }
        public long getDurationMs() { return durationMs; }
        public void setDurationMs(long durationMs) { this.durationMs = durationMs; }
        public String getError() { return error; }
        public void setError(String error) { this.error = error; }
    }

    @SuppressWarnings("unchecked")
    public GatewayResult executeGateway(ApiEndpoint endpoint, Map<String, Object> incomingParams, boolean debugMode) {
        long startAll = System.currentTimeMillis();
        GatewayResult result = new GatewayResult();
        Map<String, Object> params = incomingParams != null ? new LinkedHashMap<>(incomingParams) : new LinkedHashMap<>();

        try {
            if (endpoint.getGatewayConfig() == null || endpoint.getGatewayConfig().trim().isEmpty()) {
                result.setStatusCode(400);
                result.setError("Gateway configuration is empty");
                result.setBody(Map.of("success", false, "error", "Gateway configuration is empty"));
                result.setDurationMs(System.currentTimeMillis() - startAll);
                return result;
            }

            Map<String, Object> config = mapper.readValue(endpoint.getGatewayConfig(), new TypeReference<Map<String, Object>>() {});
            String executionMode = config.get("executionMode") != null
                    ? config.get("executionMode").toString().trim().toUpperCase()
                    : "SWITCH";
            List<Map<String, Object>> routes = config.get("routes") instanceof List
                    ? (List<Map<String, Object>>) config.get("routes")
                    : Collections.emptyList();

            if (routes.isEmpty()) {
                result.setStatusCode(400);
                result.setError("No routes configured in API Gateway");
                result.setBody(Map.of("success", false, "error", "No routes configured in API Gateway"));
                result.setDurationMs(System.currentTimeMillis() - startAll);
                return result;
            }

            List<Map<String, Object>> selectedRoutes = new ArrayList<>();
            if ("SWITCH".equals(executionMode)) {
                Map<String, Object> defaultRoute = null;
                for (Map<String, Object> route : routes) {
                    if (!isRouteEnabled(route)) continue;
                    String condition = str(route.get("condition"));
                    if (condition.isEmpty()) {
                        if (defaultRoute == null) defaultRoute = route;
                    } else if (evaluateCondition(condition, params)) {
                        selectedRoutes.add(route);
                        break;
                    }
                }
                if (selectedRoutes.isEmpty() && defaultRoute != null) {
                    selectedRoutes.add(defaultRoute);
                }
            } else {
                // AGGREGATE mode: execute all enabled routes whose condition is empty or true
                for (Map<String, Object> route : routes) {
                    if (!isRouteEnabled(route)) continue;
                    String condition = str(route.get("condition"));
                    if (condition.isEmpty() || evaluateCondition(condition, params)) {
                        selectedRoutes.add(route);
                    }
                }
            }

            if (selectedRoutes.isEmpty()) {
                result.setStatusCode(404);
                result.setError("No matching route found for incoming request parameters");
                result.setBody(Map.of("success", false, "error", "No matching route found"));
                result.setDurationMs(System.currentTimeMillis() - startAll);
                return result;
            }

            // Execute selected routes (parallel in AGGREGATE if > 1, direct if 1)
            List<Map<String, Object>> routeExecOutputs = new ArrayList<>();
            if (selectedRoutes.size() == 1) {
                routeExecOutputs.add(executeSingleRoute(selectedRoutes.get(0), params));
            } else {
                List<Future<Map<String, Object>>> futures = new ArrayList<>();
                for (Map<String, Object> route : selectedRoutes) {
                    futures.add(GATEWAY_EXECUTOR.submit(() -> executeSingleRoute(route, params)));
                }
                for (Future<Map<String, Object>> f : futures) {
                    routeExecOutputs.add(f.get());
                }
            }

            Map<String, Object> routeResultsByKey = new LinkedHashMap<>();
            List<Map<String, Object>> traceList = new ArrayList<>();
            boolean hasFatalFailure = false;
            String fatalErrorMessage = null;
            int fatalStatus = 502;
            Object firstRouteOutput = null;

            for (int i = 0; i < routeExecOutputs.size(); i++) {
                Map<String, Object> execOut = routeExecOutputs.get(i);
                Map<String, Object> trace = (Map<String, Object>) execOut.get("trace");
                traceList.add(trace);

                String key = str(execOut.get("responseKey"));
                if (key.isEmpty()) key = "route_" + (i + 1);

                boolean ok = Boolean.TRUE.equals(execOut.get("success"));
                String errorPolicy = str(execOut.get("errorPolicy"));
                if (errorPolicy.isEmpty()) errorPolicy = "FAIL_FAST";

                if (!ok && "FAIL_FAST".equalsIgnoreCase(errorPolicy) && !hasFatalFailure) {
                    hasFatalFailure = true;
                    fatalErrorMessage = str(trace.get("error"));
                    Object hs = trace.get("httpStatus");
                    if (hs instanceof Number && ((Number) hs).intValue() >= 400) {
                        fatalStatus = ((Number) hs).intValue();
                    }
                }

                Object transformed = execOut.get("transformed");
                if (i == 0) firstRouteOutput = transformed;
                if (ok) {
                    routeResultsByKey.put(key, transformed);
                } else {
                    routeResultsByKey.put(key, Map.of(
                        "_error", true,
                        "message", str(trace.get("error")),
                        "status", trace.get("httpStatus") != null ? trace.get("httpStatus") : 502
                    ));
                }
            }

            result.setDebugTrace(traceList);

            if (hasFatalFailure) {
                result.setStatusCode(fatalStatus);
                result.setError(fatalErrorMessage);
                result.setBody(Map.of(
                    "success", false,
                    "error", fatalErrorMessage != null && !fatalErrorMessage.isEmpty() ? fatalErrorMessage : "Upstream route failed",
                    "routes", routeResultsByKey
                ));
                result.setDurationMs(System.currentTimeMillis() - startAll);
                return result;
            }

            // Build global response
            String globalScript = str(config.get("responseScript"));
            String globalTemplate = str(config.get("responseTemplate"));

            Object finalResponse;
            if (!globalScript.isEmpty()) {
                Object inputForScript = "SWITCH".equals(executionMode) ? firstRouteOutput : routeResultsByKey;
                finalResponse = executeRhinoTransformResponse(globalScript, inputForScript, params, routeResultsByKey);
            } else if (!globalTemplate.isEmpty()) {
                Map<String, Object> tplCtx = new LinkedHashMap<>(params);
                tplCtx.put("routes", routeResultsByKey);
                if (firstRouteOutput != null) {
                    tplCtx.put("data", firstRouteOutput);
                }
                finalResponse = renderJsonTemplate(globalTemplate, tplCtx);
            } else {
                finalResponse = "SWITCH".equals(executionMode) ? firstRouteOutput : routeResultsByKey;
            }

            result.setStatusCode(200);
            result.setBody(finalResponse);
        } catch (Exception ex) {
            log.error("Gateway execution error: {}", ex.getMessage(), ex);
            result.setStatusCode(500);
            result.setError(ex.getMessage());
            result.setBody(Map.of("success", false, "error", ex.getMessage() != null ? ex.getMessage() : "Gateway execution error"));
        }

        result.setDurationMs(System.currentTimeMillis() - startAll);
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> executeSingleRoute(Map<String, Object> route, Map<String, Object> params) {
        long startRoute = System.currentTimeMillis();
        String routeId = str(route.get("id"));
        String routeName = str(route.get("name"));
        String targetType = str(route.get("targetType")).toUpperCase();
        if (targetType.isEmpty()) targetType = "HTTP";
        String responseKey = str(route.get("responseKey"));
        String errorPolicy = str(route.get("errorPolicy"));
        if (errorPolicy.isEmpty()) errorPolicy = "FAIL_FAST";

        int timeoutMs = toInt(route.get("timeoutMs"), 15000);
        if (timeoutMs < 500) timeoutMs = 500;
        if (timeoutMs > 120000) timeoutMs = 120000;

        int retryCount = toInt(route.get("retryCount"), 0);
        if (retryCount < 0) retryCount = 0;
        if (retryCount > 5) retryCount = 5;

        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("routeId", routeId);
        trace.put("routeName", routeName.isEmpty() ? routeId : routeName);
        trace.put("targetType", targetType);

        int attempts = 0;
        Exception lastException = null;
        Object rawData = null;
        int httpStatus = 200;
        String effectiveUrl = null;
        String effectiveReqBody = null;

        while (attempts <= retryCount) {
            attempts++;
            try {
                if ("SQL".equals(targetType)) {
                    rawData = executeSqlRoute(route, params);
                    httpStatus = 200;
                    lastException = null;
                    break;
                } else {
                    HttpRouteOutcome outcome = executeHttpRoute(route, params, timeoutMs);
                    httpStatus = outcome.statusCode;
                    effectiveUrl = outcome.url;
                    effectiveReqBody = outcome.requestBody;
                    rawData = outcome.parsedBody;
                    if (httpStatus >= 200 && httpStatus < 300) {
                        lastException = null;
                        break;
                    } else {
                        lastException = new RuntimeException("HTTP " + httpStatus + ": " + outcome.rawText);
                    }
                }
            } catch (Exception ex) {
                lastException = ex;
            }
        }

        trace.put("attempts", attempts);
        trace.put("httpStatus", httpStatus);
        if (effectiveUrl != null) trace.put("requestUrl", effectiveUrl);
        if (effectiveReqBody != null) trace.put("requestBody", effectiveReqBody);
        trace.put("rawResponse", rawData);

        Object transformed = rawData;
        boolean success = (lastException == null);
        if (success) {
            try {
                String respScript = str(route.get("responseScript"));
                String respMapping = str(route.get("responseMapping"));
                if (!respScript.isEmpty()) {
                    transformed = executeRhinoTransformResponse(respScript, rawData, params, Collections.singletonMap("route", route));
                } else if (!respMapping.isEmpty()) {
                    Map<String, Object> ctx = new LinkedHashMap<>(params);
                    ctx.put("raw", rawData);
                    ctx.put("data", rawData);
                    transformed = renderJsonTemplate(respMapping, ctx);
                }
            } catch (Exception ex) {
                success = false;
                lastException = new RuntimeException("Response transform error: " + ex.getMessage(), ex);
            }
        }

        trace.put("transformedResponse", transformed);
        trace.put("success", success);
        if (lastException != null) {
            trace.put("error", lastException.getMessage());
        }
        trace.put("durationMs", System.currentTimeMillis() - startRoute);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", success);
        out.put("transformed", transformed);
        out.put("responseKey", responseKey);
        out.put("errorPolicy", errorPolicy);
        out.put("trace", trace);
        return out;
    }

    private static class HttpRouteOutcome {
        int statusCode;
        String url;
        String requestBody;
        String rawText;
        Object parsedBody;
    }

    @SuppressWarnings("unchecked")
    private HttpRouteOutcome executeHttpRoute(Map<String, Object> route, Map<String, Object> params, int timeoutMs) throws Exception {
        String method = str(route.get("method")).toUpperCase();
        if (method.isEmpty()) method = "GET";
        String url = interpolateString(str(route.get("url")), params);
        String contentType = str(route.get("contentType")).toUpperCase();
        if (contentType.isEmpty()) contentType = "JSON";

        Map<String, String> headers = new LinkedHashMap<>();
        if (route.get("headers") instanceof Map) {
            Map<String, Object> rawHeaders = (Map<String, Object>) route.get("headers");
            for (Map.Entry<String, Object> e : rawHeaders.entrySet()) {
                if (e.getKey() != null && !e.getKey().trim().isEmpty()) {
                    headers.put(e.getKey().trim(), interpolateString(e.getValue() != null ? e.getValue().toString() : "", params));
                }
            }
        }

        // Auth helper
        String authType = str(route.get("authType")).toUpperCase();
        String authValue = interpolateString(str(route.get("authValue")), params);
        if ("BEARER".equals(authType) && !authValue.isEmpty()) {
            headers.putIfAbsent("Authorization", authValue.startsWith("Bearer ") ? authValue : "Bearer " + authValue);
        } else if ("BASIC".equals(authType) && !authValue.isEmpty()) {
            String encoded = Base64.getEncoder().encodeToString(authValue.getBytes(StandardCharsets.UTF_8));
            headers.putIfAbsent("Authorization", "Basic " + encoded);
        } else if ("API_KEY".equals(authType) && !authValue.isEmpty()) {
            int idx = authValue.indexOf(':');
            if (idx > 0) {
                headers.putIfAbsent(authValue.substring(0, idx).trim(), authValue.substring(idx + 1).trim());
            } else {
                headers.putIfAbsent("X-API-Key", authValue);
            }
        }

        Map<String, String> queryParams = new LinkedHashMap<>();
        if (route.get("queryParams") instanceof Map) {
            Map<String, Object> rawQp = (Map<String, Object>) route.get("queryParams");
            for (Map.Entry<String, Object> e : rawQp.entrySet()) {
                if (e.getKey() != null && !e.getKey().trim().isEmpty()) {
                    queryParams.put(e.getKey().trim(), interpolateString(e.getValue() != null ? e.getValue().toString() : "", params));
                }
            }
        }

        String bodyTemplate = str(route.get("bodyTemplate"));
        Object preparedBody = null;
        if (!bodyTemplate.isEmpty()) {
            if ("FORM_URLENCODED".equals(contentType)) {
                preparedBody = renderJsonTemplate(bodyTemplate, params);
            } else {
                preparedBody = interpolateString(bodyTemplate, params);
            }
        }

        // Optional requestScript override
        String requestScript = str(route.get("requestScript"));
        if (!requestScript.isEmpty()) {
            Map<String, Object> reqObj = new LinkedHashMap<>();
            reqObj.put("method", method);
            reqObj.put("url", url);
            reqObj.put("headers", headers);
            reqObj.put("queryParams", queryParams);
            reqObj.put("body", preparedBody);

            Object scriptResult = executeRhinoTransformRequest(requestScript, params, reqObj);
            if (scriptResult instanceof Map) {
                Map<String, Object> sr = (Map<String, Object>) scriptResult;
                if (sr.get("url") != null) url = sr.get("url").toString();
                if (sr.get("method") != null) method = sr.get("method").toString().toUpperCase();
                if (sr.get("headers") instanceof Map) {
                    headers.clear();
                    ((Map<?, ?>) sr.get("headers")).forEach((k, v) -> {
                        if (k != null && v != null) headers.put(k.toString(), v.toString());
                    });
                }
                if (sr.get("queryParams") instanceof Map) {
                    queryParams.clear();
                    ((Map<?, ?>) sr.get("queryParams")).forEach((k, v) -> {
                        if (k != null && v != null) queryParams.put(k.toString(), v.toString());
                    });
                }
                if (sr.containsKey("body")) {
                    preparedBody = sr.get("body");
                }
            }
        }

        URIBuilder uriBuilder = new URIBuilder(url);
        for (Map.Entry<String, String> qp : queryParams.entrySet()) {
            uriBuilder.addParameter(qp.getKey(), qp.getValue());
        }
        URI finalUri = uriBuilder.build();

        RequestConfig reqConfig = RequestConfig.custom()
                .setConnectionRequestTimeout(Timeout.ofMilliseconds(timeoutMs))
                .setResponseTimeout(Timeout.ofMilliseconds(timeoutMs))
                .build();

        try (CloseableHttpClient client = HttpClients.custom().setDefaultRequestConfig(reqConfig).build()) {
            ClassicHttpRequest httpReq = createHttpRequest(method, finalUri);
            for (Map.Entry<String, String> h : headers.entrySet()) {
                httpReq.setHeader(h.getKey(), h.getValue());
            }

            String reqBodyDebug = null;
            if (!"GET".equals(method) && !"DELETE".equals(method) && !"NONE".equals(contentType) && preparedBody != null) {
                if ("FORM_URLENCODED".equals(contentType)) {
                    Map<String, Object> formMap = toMap(preparedBody);
                    List<NameValuePair> nvps = new ArrayList<>();
                    for (Map.Entry<String, Object> entry : formMap.entrySet()) {
                        nvps.add(new BasicNameValuePair(entry.getKey(), entry.getValue() != null ? entry.getValue().toString() : ""));
                    }
                    httpReq.setEntity(new UrlEncodedFormEntity(nvps, StandardCharsets.UTF_8));
                    reqBodyDebug = mapper.writeValueAsString(formMap);
                } else {
                    String jsonBody = (preparedBody instanceof String) ? (String) preparedBody : mapper.writeValueAsString(preparedBody);
                    httpReq.setEntity(new StringEntity(jsonBody, ContentType.APPLICATION_JSON));
                    reqBodyDebug = jsonBody;
                }
            }

            HttpRouteOutcome outcome = new HttpRouteOutcome();
            outcome.url = finalUri.toString();
            outcome.requestBody = reqBodyDebug;

            try (ClassicHttpResponse resp = client.executeOpen(null, httpReq, null)) {
                outcome.statusCode = resp.getCode();
                String rawText = resp.getEntity() != null ? EntityUtils.toString(resp.getEntity(), StandardCharsets.UTF_8) : "";
                outcome.rawText = rawText;
                outcome.parsedBody = tryParseJson(rawText);
            }
            return outcome;
        }
    }

    private ClassicHttpRequest createHttpRequest(String method, URI uri) {
        return switch (method) {
            case "POST" -> new HttpPost(uri);
            case "PUT" -> new HttpPut(uri);
            case "PATCH" -> new HttpPatch(uri);
            case "DELETE" -> new HttpDelete(uri);
            default -> new HttpGet(uri);
        };
    }

    private Object executeSqlRoute(Map<String, Object> route, Map<String, Object> params) throws Exception {
        String connectionId = str(route.get("connectionId"));
        String sqlQuery = str(route.get("sqlQuery"));
        if (connectionId.isEmpty() || sqlQuery.isEmpty()) {
            throw new IllegalArgumentException("SQL route requires connectionId and sqlQuery");
        }
        ConnectionDetails conn = connectionRepository.findById(connectionId);
        if (conn == null) {
            throw new IllegalArgumentException("Database connection not found: " + connectionId);
        }
        DataSource dataSource = connectionManagerService.getDataSource(conn);
        NamedParameterJdbcTemplate jdbcTemplate = new NamedParameterJdbcTemplate(dataSource);
        String trimmedUpper = sqlQuery.trim().toUpperCase();
        if (trimmedUpper.startsWith("SELECT") || trimmedUpper.startsWith("WITH") || trimmedUpper.startsWith("SHOW")) {
            return jdbcTemplate.queryForList(sqlQuery, params);
        } else {
            int affected = jdbcTemplate.update(sqlQuery, params);
            return Map.of("affectedRows", affected);
        }
    }

    private boolean evaluateCondition(String condition, Map<String, Object> params) {
        try {
            ExpressionParser parser = new SpelExpressionParser();
            StandardEvaluationContext ctx = new StandardEvaluationContext();
            ctx.setVariable("p", params);
            for (Map.Entry<String, Object> e : params.entrySet()) {
                ctx.setVariable(e.getKey(), e.getValue());
            }
            Boolean res = parser.parseExpression(condition).getValue(ctx, Boolean.class);
            return Boolean.TRUE.equals(res);
        } catch (Exception ex) {
            log.warn("Gateway route condition '{}' evaluation failed: {}", condition, ex.getMessage());
            return false;
        }
    }

    private Object executeRhinoTransformRequest(String script, Map<String, Object> params, Map<String, Object> requestObj) throws Exception {
        Context cx = createSandboxedRhinoContext();
        try {
            Scriptable scope = cx.initSafeStandardObjects();
            String paramsJson = mapper.writeValueAsString(params);
            String reqJson = mapper.writeValueAsString(requestObj);
            String wrapper = "var __params = JSON.parse(" + JSONObjectQuote(paramsJson) + ");\n"
                    + "var __req = JSON.parse(" + JSONObjectQuote(reqJson) + ");\n"
                    + script + "\n"
                    + "var __res = (typeof transformRequest === 'function') ? transformRequest(__params, __req) : __req;\n"
                    + "JSON.stringify(__res);";
            Object evalRes = cx.evaluateString(scope, wrapper, "transformRequest.js", 1, null);
            return tryParseJson(Context.toString(evalRes));
        } finally {
            Context.exit();
        }
    }

    private Object executeRhinoTransformResponse(String script, Object rawData, Map<String, Object> params, Object extra) throws Exception {
        Context cx = createSandboxedRhinoContext();
        try {
            Scriptable scope = cx.initSafeStandardObjects();
            String dataJson = mapper.writeValueAsString(rawData);
            String paramsJson = mapper.writeValueAsString(params);
            String extraJson = mapper.writeValueAsString(extra);
            String wrapper = "var __data = JSON.parse(" + JSONObjectQuote(dataJson) + ");\n"
                    + "var __params = JSON.parse(" + JSONObjectQuote(paramsJson) + ");\n"
                    + "var __extra = JSON.parse(" + JSONObjectQuote(extraJson) + ");\n"
                    + script + "\n"
                    + "var __res = (typeof transformResponse === 'function') ? transformResponse(__data, __params, __extra) : __data;\n"
                    + "JSON.stringify(__res);";
            Object evalRes = cx.evaluateString(scope, wrapper, "transformResponse.js", 1, null);
            return tryParseJson(Context.toString(evalRes));
        } finally {
            Context.exit();
        }
    }

    private Context createSandboxedRhinoContext() {
        ContextFactory factory = new ContextFactory() {
            @Override
            protected Context makeContext() {
                Context cx = super.makeContext();
                cx.setOptimizationLevel(-1);
                cx.setInstructionObserverThreshold(50000);
                cx.setClassShutter(fullClassName -> false); // Block all Java class instantiation/reflection
                return cx;
            }

            @Override
            protected void observeInstructionCount(Context cx, int instructionCount) {
                Object startObj = cx.getThreadLocal("startTime");
                if (startObj instanceof Long) {
                    if (System.currentTimeMillis() - (Long) startObj > 3000) {
                        throw new RuntimeException("JavaScript execution timed out (> 3000ms)");
                    }
                }
            }
        };
        Context cx = factory.enterContext();
        cx.putThreadLocal("startTime", System.currentTimeMillis());
        return cx;
    }

    private String JSONObjectQuote(String jsonText) {
        try {
            return mapper.writeValueAsString(jsonText);
        } catch (Exception e) {
            return "\"{}\"";
        }
    }

    private Object renderJsonTemplate(String template, Map<String, Object> context) {
        String interpolated = interpolateString(template, context);
        return tryParseJson(interpolated);
    }

    private String interpolateString(String template, Map<String, Object> context) {
        if (template == null || template.isEmpty()) return "";
        Matcher m = PLACEHOLDER_PATTERN.matcher(template);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String path = m.group(1);
            Object val = resolvePath(context, path);
            String replacement = val != null ? val.toString() : "";
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private Object resolvePath(Object root, String dotPath) {
        if (root == null || dotPath == null || dotPath.isEmpty()) return null;
        String[] parts = dotPath.split("\\.");
        Object cur = root;
        for (String part : parts) {
            if (cur == null) return null;
            if (cur instanceof Map) {
                cur = ((Map<String, Object>) cur).get(part);
            } else if (cur instanceof List) {
                try {
                    int idx = Integer.parseInt(part);
                    List<?> list = (List<?>) cur;
                    cur = (idx >= 0 && idx < list.size()) ? list.get(idx) : null;
                } catch (NumberFormatException nfe) {
                    return null;
                }
            } else {
                return null;
            }
        }
        return cur;
    }

    private Object tryParseJson(String text) {
        if (text == null) return null;
        String trimmed = text.trim();
        if (trimmed.isEmpty()) return "";
        try {
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                return mapper.readValue(trimmed, Object.class);
            }
        } catch (Exception ignored) {}
        return text;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(Object obj) throws Exception {
        if (obj instanceof Map) return (Map<String, Object>) obj;
        if (obj instanceof String) {
            return mapper.readValue((String) obj, new TypeReference<Map<String, Object>>() {});
        }
        return mapper.convertValue(obj, new TypeReference<Map<String, Object>>() {});
    }

    private boolean isRouteEnabled(Map<String, Object> route) {
        Object en = route.get("enabled");
        return en == null || Boolean.TRUE.equals(en) || "true".equalsIgnoreCase(en.toString());
    }

    private String str(Object o) {
        return o == null ? "" : o.toString().trim();
    }

    private int toInt(Object o, int defaultVal) {
        if (o instanceof Number) return ((Number) o).intValue();
        if (o != null) {
            try { return Integer.parseInt(o.toString().trim()); } catch (Exception ignored) {}
        }
        return defaultVal;
    }
}
