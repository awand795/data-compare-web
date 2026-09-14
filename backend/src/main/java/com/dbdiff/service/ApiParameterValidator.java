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

        // ── Advanced Validation Rules ──────────────────────────────────────────
        public String pattern;            // Regex pattern (e.g. ^[A-Z]{1,2}\s?[0-9]{1,4}\s?[A-Z]{0,3}$)
        public Integer minLength;         // String min length
        public Integer maxLength;         // String max length
        public Double min;                // Number/Integer min value
        public Double max;                // Number/Integer max value
        public List<String> allowedValues;// Enum / allowed value choices
        public String transform;          // "none", "trim", "uppercase", "lowercase"
        public String customErrorMessage; // Custom error message with fallback to default
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

                // 1. Text Transformation (trim, uppercase, lowercase)
                if (value != null && def.transform != null && !def.transform.equalsIgnoreCase("none")) {
                    String tr = def.transform.toLowerCase();
                    if (tr.contains("trim")) value = value.trim();
                    if (tr.contains("upper")) value = value.toUpperCase();
                    if (tr.contains("lower")) value = value.toLowerCase();
                    resultParams.put(name, value);
                }

                boolean isBlank = (value == null || value.trim().isEmpty());

                // 2. Required Check
                if (isBlank) {
                    if (def.required) {
                        String errMsg = (def.customErrorMessage != null && !def.customErrorMessage.trim().isEmpty())
                                ? def.customErrorMessage
                                : "Parameter '" + name + "' wajib diisi.";
                        errors.add(errMsg);
                    } else if (def.defaultValue != null && !def.defaultValue.trim().isEmpty()) {
                        resultParams.put(name, def.defaultValue);
                    } else {
                        resultParams.put(name, null);
                    }
                    continue;
                }

                // 3. Type parsing & Range Check
                String type = def.type != null ? def.type.toLowerCase() : "string";
                try {
                    switch (type) {
                        case "integer": {
                            int intVal = Integer.parseInt(value.trim());
                            resultParams.put(name, intVal);
                            if (def.min != null && intVal < def.min) {
                                String errMsg = (def.customErrorMessage != null && !def.customErrorMessage.trim().isEmpty())
                                        ? def.customErrorMessage
                                        : "Nilai '" + name + "' minimal " + (def.min == Math.floor(def.min) ? String.valueOf(def.min.longValue()) : String.valueOf(def.min)) + ".";
                                errors.add(errMsg);
                            }
                            if (def.max != null && intVal > def.max) {
                                String errMsg = (def.customErrorMessage != null && !def.customErrorMessage.trim().isEmpty())
                                        ? def.customErrorMessage
                                        : "Nilai '" + name + "' maksimal " + (def.max == Math.floor(def.max) ? String.valueOf(def.max.longValue()) : String.valueOf(def.max)) + ".";
                                errors.add(errMsg);
                            }
                            break;
                        }
                        case "number": {
                            double numVal = Double.parseDouble(value.trim());
                            resultParams.put(name, numVal);
                            if (def.min != null && numVal < def.min) {
                                String errMsg = (def.customErrorMessage != null && !def.customErrorMessage.trim().isEmpty())
                                        ? def.customErrorMessage
                                        : "Nilai '" + name + "' minimal " + def.min + ".";
                                errors.add(errMsg);
                            }
                            if (def.max != null && numVal > def.max) {
                                String errMsg = (def.customErrorMessage != null && !def.customErrorMessage.trim().isEmpty())
                                        ? def.customErrorMessage
                                        : "Nilai '" + name + "' maksimal " + def.max + ".";
                                errors.add(errMsg);
                            }
                            break;
                        }
                        case "boolean": {
                            String lowerVal = value.trim().toLowerCase();
                            if (lowerVal.equals("true") || lowerVal.equals("1")) {
                                resultParams.put(name, true);
                            } else if (lowerVal.equals("false") || lowerVal.equals("0")) {
                                resultParams.put(name, false);
                            } else {
                                throw new IllegalArgumentException("Not a boolean");
                            }
                            break;
                        }
                        case "date": {
                            resultParams.put(name, LocalDate.parse(value.trim()));
                            break;
                        }
                        case "string":
                        default: {
                            resultParams.put(name, value);
                            if (def.minLength != null && value.length() < def.minLength) {
                                String errMsg = (def.customErrorMessage != null && !def.customErrorMessage.trim().isEmpty())
                                        ? def.customErrorMessage
                                        : "Panjang '" + name + "' minimal " + def.minLength + " karakter.";
                                errors.add(errMsg);
                            }
                            if (def.maxLength != null && value.length() > def.maxLength) {
                                String errMsg = (def.customErrorMessage != null && !def.customErrorMessage.trim().isEmpty())
                                        ? def.customErrorMessage
                                        : "Panjang '" + name + "' maksimal " + def.maxLength + " karakter.";
                                errors.add(errMsg);
                            }
                            break;
                        }
                    }
                } catch (Exception e) {
                    String errMsg = (def.customErrorMessage != null && !def.customErrorMessage.trim().isEmpty())
                            ? def.customErrorMessage
                            : "Parameter '" + name + "' harus berupa " + def.type + " yang valid.";
                    errors.add(errMsg);
                    continue;
                }

                // 4. Pattern / Regex validation
                if (def.pattern != null && !def.pattern.trim().isEmpty()) {
                    try {
                        java.util.regex.Pattern regex = java.util.regex.Pattern.compile(def.pattern);
                        if (!regex.matcher(value).matches()) {
                            String errMsg = (def.customErrorMessage != null && !def.customErrorMessage.trim().isEmpty())
                                    ? def.customErrorMessage
                                    : "Format parameter '" + name + "' tidak valid.";
                            errors.add(errMsg);
                        }
                    } catch (Exception regexEx) {
                        // Invalid regex pattern in definition, ignore error or log
                    }
                }

                // 5. Allowed Values (Enum) validation
                if (def.allowedValues != null && !def.allowedValues.isEmpty()) {
                    boolean matched = false;
                    for (String allowed : def.allowedValues) {
                        if (allowed != null && allowed.trim().equalsIgnoreCase(value.trim())) {
                            matched = true;
                            break;
                        }
                    }
                    if (!matched) {
                        String errMsg = (def.customErrorMessage != null && !def.customErrorMessage.trim().isEmpty())
                                ? def.customErrorMessage
                                : "Nilai '" + name + "' harus salah satu dari: [" + String.join(", ", def.allowedValues) + "].";
                        errors.add(errMsg);
                    }
                }
            }
        } catch (Exception e) {
            // If json parse fails, we just ignore and use what we have so far
        }

        return new ValidationResult(errors.isEmpty(), errors, resultParams);
    }
}
