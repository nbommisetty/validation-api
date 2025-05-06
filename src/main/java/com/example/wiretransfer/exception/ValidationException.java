package com.example.wiretransfer.exception;

import lombok.Getter;
import java.util.List;
import java.util.Map;

// ValidationException.java - Custom exception for validation failures
@Getter
public class ValidationException extends RuntimeException {
    private final List<ErrorDetail> errors;
    // Can also hold a map if errors are grouped by field directly
    // private final Map<String, String> fieldErrors;


    public ValidationException(List<ErrorDetail> errors) {
        super("Validation failed");
        this.errors = errors;
    }

    // Constructor for a single error message (less common for field-specific validation)
    public ValidationException(String message) {
        super(message);
        this.errors = List.of(new ErrorDetail("general", message));
    }
}
