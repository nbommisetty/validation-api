# Spring Boot Wire Transfer API

This project is a Spring Boot microservice designed to handle wire transfer requests. It features a robust validation mechanism that uses shared JSON configuration files, which can also be used by a frontend client to ensure consistent validation rules across the application stack.

## Table of Contents

1.  [Overview](#overview)
2.  [Features](#features)
3.  [Technologies Used](#technologies-used)
5.  [Setup and Installation](#setup-and-installation)
    * [Prerequisites](#prerequisites)
    * [Configuration](#configuration)
6.  [Building the Application](#building-the-application)
7.  [Running the Application](#running-the-application)
8.  [API Endpoints](#api-endpoints)
    * [Create Wire Transfer](#create-wire-transfer)
9.  [API Testing with cURL](#api-testing-with-curl)
10. [Validation Framework](#validation-framework)
11. [Future Enhancements](#future-enhancements)

## Overview

This microservice exposes an API endpoint to receive wire transfer details. Incoming requests are validated against a set of rules defined in external JSON files. This allows for dynamic validation logic that can be updated and is designed to be shareable with a client application.

## Features

* REST API endpoint for initiating wire transfers.
* Server-side validation using JSON-based configuration.
    * Reusable validation definitions.
    * Form-specific validation configurations.
* Custom validation logic (e.g., checking dates against weekends/holidays).
* Structured JSON error responses for validation failures.
* Built with Spring Boot 3.x and Java 17.

## Technologies Used

* **Java 17**
* **Spring Boot 3.x**
    * Spring Web (for REST APIs)
    * Spring Boot Actuator (optional, for monitoring)
* **Maven** (for dependency management and build)
* **Jackson** (for JSON processing)
* **Lombok** (to reduce boilerplate code)

## Setup and Installation

### Prerequisites

* **Java Development Kit (JDK) 17** or newer.
* **Apache Maven** (latest version recommended).
* An IDE (e.g., IntelliJ IDEA, Eclipse, VS Code) is recommended for development.

### Configuration

1.  **Clone the Repository (if applicable):**
    If you have this project in a Git repository, clone it.
2.  **Validation Files:**
    Ensure the validation configuration files are present in the `src/main/resources/validation/` directory:
    * `validationDefinitions.json`: Contains common, reusable validation rule definitions.
    * `wireTransferValidationConfig.json`: Contains the specific validation rules for the wire transfer request, referencing definitions as needed.
3.  **Application Properties:**
    Review and update `src/main/resources/application.properties` if needed (e.g., for server port, database connections if you add persistence). For this demo, the default settings are usually sufficient.

## Building the Application

1.  Open a terminal or command prompt.
2.  Navigate to the root directory of this Spring Boot project (where `pom.xml` is located).
3.  Run the following Maven command to clean the project and package it into an executable JAR:
    ```bash
    mvn clean package
    ```
    Upon successful completion, an executable JAR file (e.g., `validation-api-0.0.1-SNAPSHOT.jar`) will be created in the `target/` directory.

## Running the Application

1.  **Using the Executable JAR:**
    Navigate to the `target/` directory and run:
    ```bash
    java -jar validation-api-0.0.1-SNAPSHOT.jar
    ```
    (Replace `validation-api-0.0.1-SNAPSHOT.jar` with the actual name of your JAR file).

2.  **Using the Spring Boot Maven Plugin (for development):**
    From the project root directory, run:
    ```bash
    mvn spring-boot:run
    ```
The application will typically start on port `8080`. Check the console logs for the exact port and startup messages.

## API Endpoints

### Create Wire Transfer

* **URL:** `/api/wires`
* **Method:** `POST`
* **Content-Type:** `application/json`
* **Request Body:** See `WireTransferRequest.java` DTO structure. Example:
    ```json
    {
        "beneficiaryName": "Alice Wonderland",
        "beneficiaryAccountNumber": "123456789012",
        "routingNumber": "111000025",
        "amount": 1500.75,
        "currency": "USD",
        "transferDate": "2025-05-07",
        "memo": "Payment for services"
    }
    ```
* **Success Response (200 OK):**
    ```json
    {
        "message": "Wire transfer request received and validated successfully.",
        "beneficiaryName": "Alice Wonderland"
    }
    ```
* **Error Response (400 Bad Request - Validation Error):**
    A JSON array of `ErrorDetail` objects.
    ```json
    [
        { "field": "fieldName", "message": "Error message for this field." },
        { "field": "anotherField", "message": "Another error message." }
    ]
    ```
* **Error Response (500 Internal Server Error):**
    For configuration issues or unexpected server errors.
    ```json
    [
        { "field": "configuration", "message": "Specific configuration error." }
    ]
    ```

## API Testing with cURL

Once the Spring Boot backend is running (typically on `http://localhost:8080`):

**1. Test a VALID Payload:**
(Ensure `transferDate` is a valid weekday and not a holiday as per server config. E.g., May 7, 2025 is a Wednesday.)
```bash
curl -X POST http://localhost:8080/api/wires \
-H "Content-Type: application/json" \
-d '{
    "beneficiaryName": "Alice Wonderland",
    "beneficiaryAccountNumber": "123456789012",
    "routingNumber": "111000025",
    "amount": 1500.75,
    "currency": "USD",
    "transferDate": "2025-05-07",
    "memo": "Payment for services rendered"
}'
```
**2. Test an INVALID Payload (Multiple Errors):**
```bash
curl -X POST http://localhost:8080/api/wires \
-H "Content-Type: application/json" \
-d '{
    "beneficiaryAccountNumber": "123",
    "routingNumber": "ABC123DEF",
    "amount": -50.00,
    "currency": "JPY",
    "transferDate": "2025-01-01",
    "memo": "Invalid test data"
}'
```
**3. Test an INVALID Payload:**
(Transfer Date in the Past):(Assuming today's date is after 2023-01-15)
```bash
curl -X POST http://localhost:8080/api/wires \
-H "Content-Type: application/json" \
-d '{
    "beneficiaryName": "Past Date Test",
    "beneficiaryAccountNumber": "9876543210",
    "routingNumber": "999888777",
    "amount": 200.00,
    "currency": "EUR",
    "transferDate": "2023-01-15",
    "memo": "Testing past date validation"
}'
```

## Validation Framework
The core of the server-side validation lies in JsonValidationService.java.
* It loads validationDefinitions.json (for reusable rule sets like "nonEmptyString") and wireTransferValidationConfig.json (for rules specific to the /api/wires endpoint) from the classpath.
* The service parses these JSON files and validates incoming WireTransferRequest DTOs against the defined rules.
* It supports $ref pointers in the configuration to promote reusability.
* Custom logic, such as checking dates against weekends or a predefined holiday list, is implemented within the service.

## Future Enhancements
* Implement actual wire transfer processing logic (e.g., interacting with a payment gateway or core banking system).
* Add user authentication and authorization (e.g., using Spring Security with JWT or OAuth2).
* Persist transfer data to a database (e.g., using Spring Data JPA with PostgreSQL, MySQL, or H2).
* More sophisticated holiday management (e.g., fetching from a database or an external holiday API).
* Implement unit and integration tests (e.g., using JUnit 5, Mockito, Spring Boot Test).Add logging and monitoring capabilities.
