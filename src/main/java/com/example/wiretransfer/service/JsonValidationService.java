package com.example.wiretransfer.service;

import com.example.wiretransfer.exception.ErrorDetail;
import com.example.wiretransfer.exception.ValidationException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import org.springframework.util.ReflectionUtils;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;


@Service
public class JsonValidationService {

    private final ObjectMapper objectMapper;
    private final ResourceLoader resourceLoader;

    private JsonNode validationDefinitions;
    private JsonNode wireTransferValidationConfig; // To hold the specific wire transfer config

    // For holiday checking - simplified for example
    private static final Set<LocalDate> PUBLIC_HOLIDAYS_2025 = new HashSet<>();

    static {
        // Initialize holidays (in a real app, this would come from a DB or config service)
        PUBLIC_HOLIDAYS_2025.add(LocalDate.of(2025, 1, 1));   // New Year's Day
        PUBLIC_HOLIDAYS_2025.add(LocalDate.of(2025, 1, 20));  // Martin Luther King, Jr. Day
        PUBLIC_HOLIDAYS_2025.add(LocalDate.of(2025, 2, 17));  // Washington's Birthday
        PUBLIC_HOLIDAYS_2025.add(LocalDate.of(2025, 5, 26));  // Memorial Day
        PUBLIC_HOLIDAYS_2025.add(LocalDate.of(2025, 6, 19));  // Juneteenth
        PUBLIC_HOLIDAYS_2025.add(LocalDate.of(2025, 7, 4));   // Independence Day
        PUBLIC_HOLIDAYS_2025.add(LocalDate.of(2025, 9, 1));   // Labor Day
        PUBLIC_HOLIDAYS_2025.add(LocalDate.of(2025, 10, 13)); // Columbus Day
        PUBLIC_HOLIDAYS_2025.add(LocalDate.of(2025, 11, 11)); // Veterans Day
        PUBLIC_HOLIDAYS_2025.add(LocalDate.of(2025, 11, 27)); // Thanksgiving Day
        PUBLIC_HOLIDAYS_2025.add(LocalDate.of(2025, 12, 25)); // Christmas Day
    }


    @Autowired
    public JsonValidationService(ObjectMapper objectMapper, ResourceLoader resourceLoader) {
        this.objectMapper = objectMapper;
        this.resourceLoader = resourceLoader;
    }

    @PostConstruct
    public void loadValidationConfigs() throws IOException {
        validationDefinitions = loadJsonFromClasspath("classpath:validation/validationDefinitions.json").path("definitions");
        wireTransferValidationConfig = loadJsonFromClasspath("classpath:validation/wireTransferValidationConfig.json");
    }

    private JsonNode loadJsonFromClasspath(String path) throws IOException {
        Resource resource = resourceLoader.getResource(path);
        if (!resource.exists()) {
            throw new IOException("Resource not found: " + path);
        }
        try (InputStream inputStream = resource.getInputStream()) {
            return objectMapper.readTree(inputStream);
        }
    }

    /**
     * Validates the given data object against the specified configuration key.
     * @param data The object to validate.
     * @param configKey The key for the specific validation configuration (e.g., "wireTransferRequest").
     * @throws ValidationException if validation fails.
     */
    public void validate(Object data, String configKey) {
        JsonNode specificRulesNode = wireTransferValidationConfig.path(configKey);
        if (specificRulesNode.isMissingNode()) {
            throw new IllegalArgumentException("Validation configuration not found for key: " + configKey);
        }

        List<ErrorDetail> errors = new ArrayList<>();

        specificRulesNode.fields().forEachRemaining(entry -> {
            String fieldName = entry.getKey();
            JsonNode fieldRulesSource = entry.getValue();
            JsonNode fieldRules = resolveRef(fieldRulesSource, validationDefinitions);

            try {
                Field javaField = ReflectionUtils.findField(data.getClass(), fieldName);
                if (javaField == null) {
                    // This case should ideally not happen if DTO matches config
                    errors.add(new ErrorDetail(fieldName, "Field not found in request object."));
                    return; // Skip to next field
                }
                javaField.setAccessible(true);
                Object value = javaField.get(data);
                checkFieldRules(fieldName, value, fieldRules, errors);
            } catch (IllegalAccessException e) {
                errors.add(new ErrorDetail(fieldName, "Error accessing field value: " + e.getMessage()));
            }
        });

        if (!errors.isEmpty()) {
            throw new ValidationException(errors);
        }
    }

    private JsonNode resolveRef(JsonNode ruleNode, JsonNode definitions) {
        if (ruleNode.has("$ref")) {
            String refPath = ruleNode.get("$ref").asText().replace("#/definitions/", "");
            JsonNode definitionRule = definitions.path(refPath);
            if (definitionRule.isMissingNode()) {
                throw new IllegalArgumentException("Validation definition not found for $ref: " + ruleNode.get("$ref").asText());
            }
            // Merge: specific rules can override definition rules
            ObjectNode merged = definitionRule.deepCopy();
            ruleNode.fields().forEachRemaining(entry -> {
                if (!entry.getKey().equals("$ref")) {
                    merged.set(entry.getKey(), entry.getValue());
                }
            });
            return merged;
        }
        return ruleNode;
    }

    private void checkFieldRules(String fieldName, Object value, JsonNode rules, List<ErrorDetail> errors) {
        boolean isRequired = rules.path("required").asBoolean(false);
        String type = rules.path("type").asText("string"); // Default type assumption

        // 1. Required Check (handles empty strings, nulls)
        if (isRequired) {
            boolean isEmpty = false;
            if (value == null) {
                isEmpty = true;
            } else if (value instanceof String && ((String) value).trim().isEmpty()) {
                isEmpty = true;
            }
            // Add checks for other types if they can be "empty" e.g. empty collections
            if (isEmpty) {
                errors.add(new ErrorDetail(fieldName, rules.path("errorMessageRequired").asText(fieldName + " is required.")));
                return; // If required and missing, no further checks needed for this field
            }
        } else if (value == null || (value instanceof String && ((String) value).trim().isEmpty())) {
            // If optional and truly empty, no further validation needed for this field
            return;
        }

        // 2. Type-specific validations (only if value is present)
        switch (type.toLowerCase()) {
            case "string":
                validateString(fieldName, (String) value, rules, errors);
                break;
            case "number":
                validateNumber(fieldName, value, rules, errors);
                break;
            case "date":
                validateDate(fieldName, value, rules, errors);
                break;
            // Add cases for boolean, array, etc. if needed
            default:
                // errors.add(new ErrorDetail(fieldName, "Unsupported validation type: " + type));
                break;
        }
    }

    private void validateString(String fieldName, String value, JsonNode rules, List<ErrorDetail> errors) {
        if (rules.has("minLength")) {
            int minLength = rules.get("minLength").asInt();
            if (value.length() < minLength) {
                errors.add(new ErrorDetail(fieldName, rules.path("errorMessageMinLength").asText("Min length is " + minLength).replace("{minLength}", String.valueOf(minLength))));
            }
        }
        if (rules.has("maxLength")) {
            int maxLength = rules.get("maxLength").asInt();
            if (value.length() > maxLength) {
                errors.add(new ErrorDetail(fieldName, rules.path("errorMessageMaxLength").asText("Max length is " + maxLength).replace("{maxLength}", String.valueOf(maxLength))));
            }
        }
        if (rules.has("pattern")) {
            String patternStr = rules.get("pattern").asText();
            if (!Pattern.matches(patternStr, value)) {
                errors.add(new ErrorDetail(fieldName, rules.path("errorMessagePattern").asText("Invalid format.")));
            }
        }
        if (rules.has("allowedValues")) {
            List<String> allowed = StreamSupport.stream(rules.get("allowedValues").spliterator(), false)
                                            .map(JsonNode::asText)
                                            .collect(Collectors.toList());
            if (!allowed.contains(value)) {
                 errors.add(new ErrorDetail(fieldName, rules.path("errorMessageAllowedValues").asText("Invalid value. Allowed: " + String.join(", ", allowed)).replace("{allowedValues}", String.join(", ", allowed))));
            }
        }
    }

    private void validateNumber(String fieldName, Object value, JsonNode rules, List<ErrorDetail> errors) {
        BigDecimal numberValue;
        if (value instanceof BigDecimal) {
            numberValue = (BigDecimal) value;
        } else if (value instanceof Number) {
            numberValue = new BigDecimal(value.toString());
        } else if (value instanceof String) {
            try {
                numberValue = new BigDecimal((String) value);
            } catch (NumberFormatException e) {
                errors.add(new ErrorDetail(fieldName, rules.path("errorMessageType").asText("Must be a valid number.")));
                return;
            }
        } else {
             errors.add(new ErrorDetail(fieldName, rules.path("errorMessageType").asText("Must be a valid number.")));
            return;
        }

        if (rules.has("minValue")) {
            BigDecimal minValue = new BigDecimal(rules.get("minValue").asText());
            if (numberValue.compareTo(minValue) < 0) {
                errors.add(new ErrorDetail(fieldName, rules.path("errorMessageMinValue").asText("Min value is " + minValue).replace("{minValue}", minValue.toPlainString())));
            }
        }
        if (rules.has("maxValue")) {
            BigDecimal maxValue = new BigDecimal(rules.get("maxValue").asText());
            if (numberValue.compareTo(maxValue) > 0) {
                errors.add(new ErrorDetail(fieldName, rules.path("errorMessageMaxValue").asText("Max value is " + maxValue).replace("{maxValue}", maxValue.toPlainString())));
            }
        }
    }

    private void validateDate(String fieldName, Object value, JsonNode rules, List<ErrorDetail> errors) {
        LocalDate dateValue;
        if (value instanceof LocalDate) {
            dateValue = (LocalDate) value;
        } else if (value instanceof String) {
            try {
                // Assuming frontend sends "yyyy-MM-dd"
                dateValue = LocalDate.parse((String) value, DateTimeFormatter.ISO_LOCAL_DATE);
            } catch (DateTimeParseException e) {
                errors.add(new ErrorDetail(fieldName, rules.path("errorMessageType").asText("Invalid date format. Expected yyyy-MM-dd.")));
                return;
            }
        } else {
            errors.add(new ErrorDetail(fieldName, rules.path("errorMessageType").asText("Invalid date type.")));
            return;
        }

        if (rules.has("minDate") && "today".equalsIgnoreCase(rules.get("minDate").asText())) {
            if (dateValue.isBefore(LocalDate.now())) {
                errors.add(new ErrorDetail(fieldName, rules.path("errorMessageMinDate").asText("Date cannot be in the past.")));
            }
        }

        if (rules.has("customRule") && "noWeekendOrHoliday".equals(rules.get("customRule").asText())) {
            DayOfWeek day = dateValue.getDayOfWeek();
            if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) {
                errors.add(new ErrorDetail(fieldName, rules.path("errorMessageCustomRule").asText("Date cannot be a weekend.")));
            } else if (PUBLIC_HOLIDAYS_2025.contains(dateValue)) {
                 errors.add(new ErrorDetail(fieldName, rules.path("errorMessageCustomRule").asText("Date cannot be a public holiday.")));
            }
        }
    }
}
