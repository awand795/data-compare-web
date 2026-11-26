package com.dbdiff.consolidation.utils;

import java.util.HashMap;
import java.util.Map;

import org.json.JSONException;
import org.json.JSONObject;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

public class ResponseData {
    public static ResponseEntity<Object> buildResponseBody(int statusCode, String message, Object payload) {
        return createResponseBody(statusCode, message, payload);
    }

    public static ResponseEntity<Object> buildResponseBody(int statusCode, String message) {
        return createResponseBody(statusCode, message, null);
    }

    public static ResponseEntity<Object> buildResponseBody(int statusCode, Object payload) {
        return createResponseBody(statusCode, null, payload);
    }

    public static ResponseEntity<Object> buildResponse(int statusCode, String status, Object payload) {
        return createResponse(statusCode, status, payload, null);
    }

    public static ResponseEntity<Object> buildResponse(int statusCode, String status, Object payload, Integer partReq) {
        return createResponse(statusCode, status, payload, partReq);
    }

    public static JSONObject buildResponseLog(int statusCode, String message) throws JSONException {
        return createResponseLog(statusCode, message, null, null, null);
    }

    public static JSONObject buildResponseLog(int statusCode, String message, String reqUrl) throws JSONException {
        return createResponseLog(statusCode, message, reqUrl, null, null);
    }

    public static JSONObject buildResponseLog(int statusCode, String message, String reqUrl, Object reqBody) throws JSONException {
        return createResponseLog(statusCode, message, reqUrl, reqBody, null);
    }

    public static JSONObject buildResponseLog(int statusCode, String message, String reqUrl, Object reqBody, Object resBody) throws JSONException {
        return createResponseLog(statusCode, message, reqUrl, reqBody, resBody);
    }

    private static ResponseEntity<Object> createResponseBody(Integer statusCode, String message, Object payload) {
        Map<String, Object> obj = new HashMap<>();
        obj.put("success", ((statusCode / 200) >= 1 && (statusCode / 200) < 1.5));
        obj.put("statusCode", statusCode);
        obj.put("statusName", HttpStatus.valueOf(statusCode));
        obj.put("message", message);
        obj.put("time", Formatter.getDateString(null, "yyyy-MM-dd HH:mm:ss"));
        obj.put("data", payload);
        return new ResponseEntity<>(obj, HttpStatus.valueOf(statusCode));
    }

    private static ResponseEntity<Object> createResponse(Integer statusCode, String status, Object payload, Integer partReq) {
        Map<String, Object> obj = new HashMap<>();
        obj.put("STATUS", status);
        obj.put("RESULT", payload);
        if (partReq != null) {
            obj.put("PART_REQ", partReq);
        }
        return new ResponseEntity<>(obj, HttpStatus.valueOf(statusCode));
    }

    private static JSONObject createResponseLog(Integer statusCode, String message, String reqUrl, Object reqBody, Object resBody) throws JSONException {
        JSONObject obj = new JSONObject();
        obj.put("success", ((statusCode / 200) >= 1 && (statusCode / 200) < 1.5));
        obj.put("statusCode", statusCode);
        obj.put("statusName", HttpStatus.valueOf(statusCode));
        obj.put("message", message);
        obj.put("time", Formatter.getDateString(null, "yyyy-MM-dd HH:mm:ss"));
        obj.put("requestBody", reqBody);
        obj.put("responseBody", resBody);
        obj.put("requestUrl", reqUrl);
        return obj;
    }
}
