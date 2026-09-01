package com.dogdaycare.model;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class EvaluationRequestValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setupValidator() {
        ValidatorFactory factory =
                Validation.buildDefaultValidatorFactory();

        validator = factory.getValidator();
    }

    @Test
    void phone_plainTenDigits_isValid() {

        EvaluationRequest request =
                validEvaluationRequest();

        request.setPhone("2065551234");

        Set<ConstraintViolation<EvaluationRequest>> violations =
                validator.validate(request);

        assertFalse(
                hasPhoneViolation(violations)
        );
    }

    @Test
    void phone_commonFormatting_isValid() {

        EvaluationRequest request =
                validEvaluationRequest();

        String[] validPhones = {
                "206-555-1234",
                "(206) 555-1234"
        };

        for (String phone : validPhones) {

            request.setPhone(phone);

            Set<ConstraintViolation<EvaluationRequest>> violations =
                    validator.validate(request);

            assertFalse(
                    hasPhoneViolation(violations),
                    "Expected valid phone: " + phone
            );
        }
    }

    @Test
    void phone_nineDigits_isInvalid() {

        EvaluationRequest request =
                validEvaluationRequest();

        request.setPhone("206555123");

        Set<ConstraintViolation<EvaluationRequest>> violations =
                validator.validate(request);

        assertTrue(
                hasViolationMessage(
                        violations,
                        "Phone number must contain exactly 10 digits"
                )
        );
    }

    @Test
    void phone_elevenDigits_isInvalid() {

        EvaluationRequest request =
                validEvaluationRequest();

        request.setPhone("20655512345");

        Set<ConstraintViolation<EvaluationRequest>> violations =
                validator.validate(request);

        assertTrue(
                hasViolationMessage(
                        violations,
                        "Phone number must contain exactly 10 digits"
                )
        );
    }

    @Test
    void phone_lettersAreInvalid() {

        EvaluationRequest request =
                validEvaluationRequest();

        request.setPhone("206-ABC-1234");

        Set<ConstraintViolation<EvaluationRequest>> violations =
                validator.validate(request);

        assertTrue(
                hasViolationMessage(
                        violations,
                        "Phone number must contain exactly 10 digits"
                )
        );
    }

    @Test
    void phone_blank_isRejectedAsRequired() {

        EvaluationRequest request =
                validEvaluationRequest();

        request.setPhone("");

        Set<ConstraintViolation<EvaluationRequest>> violations =
                validator.validate(request);

        assertTrue(
                hasViolationMessage(
                        violations,
                        "Phone is required"
                )
        );
    }

    private EvaluationRequest validEvaluationRequest() {

        EvaluationRequest request =
                new EvaluationRequest();

        request.setClientName("Test Customer");
        request.setPhone("2065551234");
        request.setEmail("customer@test.local");
        request.setDogName("Buddy");
        request.setDogBreed("Labrador");

        return request;
    }

    private boolean hasPhoneViolation(
            Set<ConstraintViolation<EvaluationRequest>> violations
    ) {

        return violations.stream()
                .anyMatch(v ->
                        "phone".equals(
                                v.getPropertyPath().toString()
                        )
                                ||
                                "phoneDigitCountValid".equals(
                                        v.getPropertyPath().toString()
                                )
                );
    }

    private boolean hasViolationMessage(
            Set<ConstraintViolation<EvaluationRequest>> violations,
            String message
    ) {

        return violations.stream()
                .anyMatch(v ->
                        message.equals(v.getMessage())
                );
    }
}