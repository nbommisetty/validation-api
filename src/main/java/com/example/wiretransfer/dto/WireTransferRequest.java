package com.example.wiretransfer.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data // Lombok: Generates getters, setters, toString, equals, hashCode
@NoArgsConstructor
@AllArgsConstructor
public class WireTransferRequest {

    private String beneficiaryName;
    private String beneficiaryAccountNumber;
    private String routingNumber;
    private BigDecimal amount; // Use BigDecimal for monetary values
    private String currency;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    private LocalDate transferDate; // Expecting yyyy-MM-dd from frontend

    private String memo;
}
