package com.dbdiff.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class ApiParameterValidator {

    private final ObjectMapper objectMapper = new ObjectMapper();

    public static class ValidationResult {
        private boolean valid;
        private List<String> errors;
        private Map<String, Object> params;

        public ValidationResult(boolean valid, List<String> errors, Map<String, Object> params) {
            this.valid = valid;
            this.errors = errors;
            this.params = params;
        }

        public boolean isValid() { return valid; }
        public List<String> getErrors() { return errors; }
        public Map<String, Object> getParams() { return params; }
    }

    public static class ParameterDef {
        public String name;
        public String type;
        public boolean required;
        public String defaultValue;
        public String description;
    }

    public ValidationResult validate(String parametersJson, Map<String, Object> incomingParams) {
        if (parametersJson == null || parametersJson.trim().isEmpty() || parametersJson.equals("[]")) {
            return new ValidationResult(true, new ArrayList<>(), incomingParams);
        }

        List<String> errors = new ArrayList<>();
        Map<String, Object> resultParams = new HashMap<>(incomingParams != null ? incomingParams : new HashMap<>());

        try {
            List<ParameterDef> defs = objectMapper.readValue(parametersJson, new TypeReference<List<ParameterDef>>() {});
            for (ParameterDef def : defs) {
                String name = def.name;
                Object valueObj = resultParams.get(name);
                String value = valueObj != null ? valueObj.toString() : null;
                boolean isBlank = (value == null || value.trim().isEmpty());

                if (isBlank) {
                    if (def.required) {
                        errors.add("Missing required parameter: " + name);
                    } else if (def.defaultValue != null && !def.defaultValue.trim().isEmpty()) {
                        resultParams.put(name, def.defaultValue);
                    } else {
                        resultParams.put(name, null);
                    }
                } else {
                    try {
                        String type = def.type != null ? def.type.toLowerCase() : "string";
                        switch (type) {
                            case "integer":
                                resultParams.put(name, Integer.parseInt(value));
                                break;
                            case "number":
                                resultParams.put(name, Double.parseDouble(value));
                                break;
                            case "boolean":
                                String lowerVal = value.toLowerCase();
                                if (lowerVal.equals("true") || lowerVal.equals("1")) {
                                    resultParams.put(name, true);
                                } else if (lowerVal.equals("false") || lowerVal.equals("0")) {
                                    resultParams.put(name, false);
                                } else {
                                    throw new IllegalArgumentException();
                                }
                                break;
                            case "date":
                                resultParams.put(name, LocalDate.parse(value));
                                break;
                            case "string":
                            default:
                                resultParams.put(name, value);
                                break;
                        }
                    } catch (Exception e) {
                        errors.add("Parameter '" + name + "' must be of type " + def.type);
                    }
                }
            }
        } catch (Exception e) {
            // If json parse fails, we just ignore and use what we have so far
        }

        return new ValidationResult(errors.isEmpty(), errors, resultParams);
    }
}
