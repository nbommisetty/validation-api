package com.example.wiretransfer.service;

import com.example.wiretransfer.exception.ErrorDetail;
import com.example.wiretransfer.exception.ValidationException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Service;
import org.springframework.util.ReflectionUtils;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@Service
public class JsonValidationService {

    private static final Logger logger = LoggerFactory.getLogger(JsonValidationService.class);

    private final ObjectMapper objectMapper;
    private final ResourcePatternResolver resourceResolver; // For scanning multiple resources

    private JsonNode validationDefinitions;
    private final Map<String, JsonNode> specificValidationConfigs = new HashMap<>();

    // For holiday checking - simplified for example
    private static final Set<LocalDate> PUBLIC_HOLIDAYS_2025 = new HashSet<>();

    static {
        // Initialize holidays (in a real app, this would come from a DB or config service)
        PUBLIC_HOLIDAYS_2025.add(LocalDate.of(2025, 1, 1));
        PUBLIC_HOLIDAYS_2025.add(LocalDate.of(2025, 1, 20));
        // ... (add other holidays as before)
        PUBLIC_HOLIDAYS_2025.add(LocalDate.of(2025, 12, 25));
    }

    @Autowired
    public JsonValidationService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.resourceResolver = new PathMatchingResourcePatternResolver();
    }

    @PostConstruct
    public void loadAllValidationConfigs() throws IOException {
        // 1. Load common definitions
        try {
            Resource definitionsResource = resourceResolver.getResource("classpath:validation/common/validationDefinitions.json");
            if (!definitionsResource.exists()) {
                logger.warn("Common validation definitions file not found: classpath:validation/common/validationDefinitions.json");
                this.validationDefinitions = objectMapper.createObjectNode(); // Empty definitions
            } else {
                try (InputStream inputStream = definitionsResource.getInputStream()) {
                    this.validationDefinitions = objectMapper.readTree(inputStream).path("definitions");
                    logger.info("Successfully loaded common validation definitions.");
                }
            }
        } catch (IOException e) {
            logger.error("Failed to load common validation definitions.", e);
            throw e; // Or handle more gracefully, e.g., by setting empty definitions
        }


        // 2. Load all specific DTO validation configurations. Eventually, this would come from a DB or config service.
        try {
            Resource[] resources = resourceResolver.getResources("classpath:validation/specifics/*.json");
            for (Resource resource : resources) {
                String filename = resource.getFilename();
                if (filename != null) {
                    String configKey = filename.substring(0, filename.lastIndexOf(".json"));
                    try (InputStream inputStream = resource.getInputStream()) {
                        JsonNode specificConfig = objectMapper.readTree(inputStream);
                        specificValidationConfigs.put(configKey, specificConfig);
                        logger.info("Loaded validation config for DTO: {}", configKey);
                    }
                }
            }
            if (specificValidationConfigs.isEmpty()) {
                logger.warn("No specific DTO validation configuration files found in classpath:validation/specifics/");
            }
        } catch (IOException e) {
            logger.error("Failed to load specific DTO validation configurations.", e);
            // Depending on requirements, you might throw e or allow the service to start with no specific configs
        }
    }


    /**
     * Validates the given data object based on its class name mapping to a JSON configuration.
     * @param data The DTO to validate.
     * @throws ValidationException if validation fails.
     * @throws IllegalArgumentException if no validation configuration is found for the DTO.
     */
    public void validate(Object data) {
        if (data == null) {
            throw new IllegalArgumentException("Data object to validate cannot be null.");
        }
        String configKey = data.getClass().getSimpleName();
        validate(data, configKey);
    }


    /**
     * Validates the given data object against the specified configuration key.
     * This method can be kept for cases where explicit key-based validation is needed,
     * or the new validate(Object data) method can be the primary one.
     * @param data The object to validate.
     * @param configKey The key for the specific validation configuration (e.g., "WireTransferRequest").
     * @throws ValidationException if validation fails.
     */
    public void validate(Object data, String configKey) {
        JsonNode specificRulesNode = specificValidationConfigs.get(configKey);

        if (specificRulesNode == null || specificRulesNode.isMissingNode()) {
            throw new IllegalArgumentException("Validation configuration not found for key/DTO: " + configKey +
                                               ". Ensure a corresponding JSON file (e.g., " + configKey + ".json) exists in validation/specifics/.");
        }

        List<ErrorDetail> errors = new ArrayList<>();

        specificRulesNode.fields().forEachRemaining(entry -> {
            String fieldName = entry.getKey();
            JsonNode fieldRulesSource = entry.getValue();
            JsonNode fieldRules = resolveRef(fieldRulesSource, validationDefinitions);

            try {
                Field javaField = ReflectionUtils.findField(data.getClass(), fieldName);
                if (javaField == null) {
                    logger.warn("Field '{}' defined in validation config for {} not found in DTO class {}.",
                                fieldName, configKey, data.getClass().getName());
                    // errors.add(new ErrorDetail(fieldName, "Field definition mismatch.")); // Optional: report this
                    return; // Skip to next field
                }
                javaField.setAccessible(true);
                Object value = javaField.get(data);
                checkFieldRules(fieldName, value, fieldRules, errors);
            } catch (IllegalAccessException e) {
                logger.error("Error accessing field '{}' on DTO {}: {}", fieldName, configKey, e.getMessage());
                errors.add(new ErrorDetail(fieldName, "Error accessing field value."));
            }
        });

        if (!errors.isEmpty()) {
            throw new ValidationException(errors);
        }
    }

    // resolveRef, checkFieldRules, validateString, validateNumber, validateDate methods remain the same
    // as in artifact springboot_validation_service_v1, ensure they are present here.
    // For brevity, they are not repeated but are essential.

    private JsonNode resolveRef(JsonNode ruleNode, JsonNode definitions) {
        if (ruleNode.has("$ref")) {
            String refPath = ruleNode.get("$ref").asText().replace("#/definitions/", "");
            JsonNode definitionRule = definitions.path(refPath);
            if (definitionRule.isMissingNode()) {
                throw new IllegalArgumentException("Validation definition not found for $ref: " + ruleNode.get("$ref").asText() +
                                                   ". Check validation/common/validationDefinitions.json.");
            }
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
        String type = rules.path("type").asText("string");

        if (isRequired) {
            boolean isEmpty = false;
            if (value == null) {
                isEmpty = true;
            } else if (value instanceof String && ((String) value).trim().isEmpty()) {
                isEmpty = true;
            }
            if (isEmpty) {
                errors.add(new ErrorDetail(fieldName, rules.path("errorMessageRequired").asText(fieldName + " is required.")));
                return; 
            }
        } else if (value == null || (value instanceof String && ((String) value).trim().isEmpty())) {
            return;
        }

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
            default:
                logger.warn("Unsupported validation type: {} for field: {}", type, fieldName);
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
        } else if (value instanceof String && !((String)value).trim().isEmpty()) { // check for non-empty string
            try {
                numberValue = new BigDecimal(((String) value).trim());
            } catch (NumberFormatException e) {
                errors.add(new ErrorDetail(fieldName, rules.path("errorMessageType").asText("Must be a valid number.")));
                return;
            }
        } else if (value instanceof String && ((String)value).trim().isEmpty()){ // if it's an empty string and not required, it's fine
            if(rules.path("required").asBoolean(false)) { // if required, it should have been caught already
                 errors.add(new ErrorDetail(fieldName, rules.path("errorMessageType").asText("Must be a valid number.")));
            }
            return;
        }
         else {
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
        } else if (value instanceof String && !((String)value).trim().isEmpty()) { // check for non-empty string
            try {
                dateValue = LocalDate.parse(((String) value).trim(), DateTimeFormatter.ISO_LOCAL_DATE);
            } catch (DateTimeParseException e) {
                errors.add(new ErrorDetail(fieldName, rules.path("errorMessageType").asText("Invalid date format. Expected yyyy-MM-dd.")));
                return;
            }
        } else if (value instanceof String && ((String)value).trim().isEmpty()){ // if it's an empty string and not required, it's fine
             if(rules.path("required").asBoolean(false)) { // if required, it should have been caught already
                 errors.add(new ErrorDetail(fieldName, rules.path("errorMessageType").asText("Invalid date format. Expected yyyy-MM-dd.")));
            }
            return;
        }
        else {
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
