package com.dbdiff.consolidation.utils;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.entity.UrlEncodedFormEntity;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.NameValuePair;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.message.BasicNameValuePair;
import org.json.JSONArray;
import org.json.JSONObject;

public class DataSourceType {

    public static JSONObject dataSourceERP(String url, JSONObject authParam, JSONObject reqParam)
            throws UnsupportedEncodingException {
        JSONObject result = new JSONObject();

        try (CloseableHttpClient client = HttpClients.createDefault()) {
            HttpPost httpPost = new HttpPost(url);

            String basicAuth = TransCode.encodeBasicAuth(authParam.getString("user_auth"),
                    authParam.getString("pass_auth"));
            httpPost.setHeader("Authorization", basicAuth);
            httpPost.setHeader("Content-type", authParam.getString("content_type"));

            List<NameValuePair> nvps = new ArrayList<>();
            nvps.add(new BasicNameValuePair("data", reqParam.toString()));
            httpPost.setEntity(new UrlEncodedFormEntity(nvps, StandardCharsets.UTF_8));

            result = client.execute(httpPost, response -> {
                String resStr = response.getEntity() != null
                        ? EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8)
                        : "{}";
                return new JSONObject(resStr);
            });
        } catch (Exception e) {
            Logger.getLogger(DataSourceType.class.getName()).log(Level.SEVERE, e.getMessage(), e);
        }

        return result;
    }

    public static JSONArray dataSourceERPExtern(String url, JSONObject reqParam) throws UnsupportedEncodingException {
        JSONArray result = new JSONArray();

        try (CloseableHttpClient client = HttpClients.createDefault()) {
            HttpPost httpPost = new HttpPost(url);
            httpPost.setHeader("Content-type", "application/x-www-form-urlencoded");

            List<NameValuePair> nvps = new ArrayList<>();
            nvps.add(new BasicNameValuePair("data", reqParam.toString()));
            httpPost.setEntity(new UrlEncodedFormEntity(nvps, StandardCharsets.UTF_8));

            result = client.execute(httpPost, response -> {
                String resStr = response.getEntity() != null
                        ? EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8)
                        : "[]";
                return new JSONArray(resStr);
            });
        } catch (Exception e) {
            Logger.getLogger(DataSourceType.class.getName()).log(Level.SEVERE, e.getMessage(), e);
        }

        return result;
    }
}
