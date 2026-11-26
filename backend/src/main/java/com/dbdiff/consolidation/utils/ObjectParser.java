package com.dbdiff.consolidation.utils;

import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

public class ObjectParser {

    private static final ObjectMapper oMapper = new ObjectMapper();

    public static JSONObject toJSONObject(Object data) {
        try {
            String json = oMapper.writeValueAsString(data);
            return Optional.ofNullable(new JSONObject(json)).orElse(new JSONObject());
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> toMap(Object data) {
        Map<String, Object> toReturn = new HashMap<>();
        try {
            toReturn = Optional.ofNullable(oMapper.convertValue(data, HashMap.class)).orElse(new HashMap<>());
        } catch (Exception er) {
            toReturn = new HashMap<>();
        }
        return toReturn;
    }

    public static Object toJSON(String data) throws JSONException {
        try {
            return new JSONObject(data);
        } catch (Exception e) {
            try {
                return new JSONArray(data);
            } catch (Exception ex) {
                return data;
            }
        }
    }

    public static Object toMapJSON(Object data) {
        try {
            return oMapper.readValue(data.toString(), new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return data;
        }
    }

    public static String jsonToString(JSONObject src) {
        try {
            return oMapper.writeValueAsString(src);
        } catch (Exception e) {
            Logger.getLogger(ObjectParser.class.getName()).log(Level.SEVERE, e.getMessage());
            return "{}";
        }
    }

    public static JSONObject parseParamObject(JSONObject objParam, JSONObject objSource, String keyParam) {
        try {
            if (objParam.has(keyParam)) {
                JSONObject objKeyParam = objParam.getJSONObject(keyParam);
                for (Iterator<String> it = objKeyParam.keys(); it.hasNext();) {
                    String key = it.next();
                    String valueKey = objKeyParam.getString(key);
                    if (valueKey.substring(0, 2).equalsIgnoreCase("_$")) {
                        if (objSource.has(valueKey.replace("_$", "").toLowerCase())) {
                            objKeyParam.put(key, objSource.get(valueKey.replace("_$", "").toLowerCase()));
                        }
                    }
                }
                objParam.put(keyParam, objKeyParam);
            }
        } catch (Exception e) {
            return objParam;
        }
        return objParam;
    }

    public static JSONObject parseParamObject(JSONObject objParam, JSONObject objSource) {
        try {
            for (Iterator<String> it = objParam.keys(); it.hasNext();) {
                String keys = it.next();
                if (objParam.get(keys) instanceof String) {
                    String value = objParam.getString(keys);
                    if (value.substring(0, 2).equalsIgnoreCase("_$")) {
                        objParam.put(keys, Optional
                                .ofNullable(objSource.opt(value.replace("_$", "")))
                                .orElse(""));
                    }
                } else {
                    JSONObject valueObj = objParam.getJSONObject(keys);
                    for (Iterator<String> itr = valueObj.keys(); itr.hasNext();) {
                        String keyParam = itr.next();
                        if (valueObj.get(keyParam) instanceof String) {
                            String valueKeyParam = valueObj.getString(keyParam);
                            if (valueKeyParam.substring(0, 2).equalsIgnoreCase("_$")) {
                                valueObj.put(keyParam, Optional
                                        .ofNullable(objSource.opt(valueKeyParam.replace("_$", "")))
                                        .orElse(""));
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
        }
        return objParam;
    }

    public static String toQueryParamString(String url, JSONObject _obj) {
        StringBuilder queryString = new StringBuilder();
        try {
            for (Iterator<String> it = _obj.keys(); it.hasNext();) {
                String keys = it.next();
                String value = URLEncoder.encode(_obj.get(keys).toString(), "UTF-8");
                queryString.append(keys).append("=").append(value);
                if (it.hasNext()) {
                    queryString.append("&");
                }
            }
            if (!queryString.isEmpty()) {
                url += "?" + queryString.toString();
            }
        } catch (Exception e) {
        }
        return url;
    }

    public static MultiValueMap<String, String> toMultiValueMap(JSONObject _obj) {
        MultiValueMap<String, String> multiValueMap = new LinkedMultiValueMap<>();
        try {
            if (!Objects.isNull(_obj)) {
                for (Iterator<String> it = _obj.keys(); it.hasNext();) {
                    String key = it.next();
                    multiValueMap.add(key, _obj.get(key).toString());
                }
            }
        } catch (Exception e) {
        }
        return multiValueMap;
    }
}
