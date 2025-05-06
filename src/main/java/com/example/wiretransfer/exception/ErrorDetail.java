package com.example.wiretransfer.exception;

import lombok.Getter;
import java.util.List;
import java.util.Map;

// ErrorDetail.java - Represents a single validation error
@Getter
public class ErrorDetail {
    private String field;
    private String message;
    // private String code; // Optional: for error codes

    public ErrorDetail(String field, String message) {
        this.field = field;
        this.message = message;
    }
}