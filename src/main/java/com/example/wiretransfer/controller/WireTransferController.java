package com.example.wiretransfer.controller;

import com.example.wiretransfer.dto.WireTransferRequest;
import com.example.wiretransfer.exception.ErrorDetail;
import com.example.wiretransfer.exception.ValidationException;
import com.example.wiretransfer.service.JsonValidationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/wires")
public class WireTransferController {

    private static final Logger logger = LoggerFactory.getLogger(WireTransferController.class);

    private final JsonValidationService jsonValidationService;

    @Autowired
    public WireTransferController(JsonValidationService jsonValidationService) {
        this.jsonValidationService = jsonValidationService;
    }

    @PostMapping
    public ResponseEntity<?> createWireTransfer(@RequestBody WireTransferRequest request) {
        try {
            // Perform validation using the JSON configuration
            jsonValidationService.validate(request, "wireTransferRequest");

            // If validation passes, proceed with business logic (mocked here)
            logger.info("Wire transfer request validated successfully: {}", request.getBeneficiaryName());
            // ... process wire transfer ...

            Map<String, String> successResponse = new HashMap<>();
            successResponse.put("message", "Wire transfer request received and validated successfully.");
            successResponse.put("beneficiaryName", request.getBeneficiaryName());
            // Add transaction ID or other relevant info here
            return ResponseEntity.ok(successResponse);

        } catch (ValidationException e) {
            logger.warn("Validation failed for wire transfer request: {}", e.getErrors());
            // Return a 400 Bad Request with structured error messages
            return ResponseEntity.badRequest().body(e.getErrors());
        } catch (IllegalArgumentException e) {
            // Catch errors related to missing validation config keys, etc.
            logger.error("Configuration error during validation: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                                 .body(List.of(new ErrorDetail("configuration", e.getMessage())));
        } catch (Exception e) {
            // Catch-all for other unexpected errors during processing
            logger.error("Unexpected error processing wire transfer: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                                 .body(List.of(new ErrorDetail("general", "An unexpected error occurred.")));
        }
    }

    // Optional: Global exception handler for ValidationException if you prefer
    // @ExceptionHandler(ValidationException.class)
    // public ResponseEntity<List<ErrorDetail>> handleValidationException(ValidationException ex) {
    //     return ResponseEntity.badRequest().body(ex.getErrors());
    // }
}
