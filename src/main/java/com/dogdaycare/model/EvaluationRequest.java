package com.dogdaycare.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

@Entity
@Getter
@Setter
public class EvaluationRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private boolean approved = false;

    @NotBlank(message = "Client name is required")
    private String clientName;

    @NotBlank(message = "Phone is required")
    @Pattern(
            regexp = "^(\\+\\d{1,2}\\s?)?\\(?\\d{3}\\)?[\\s.-]?\\d{3}[\\s.-]?\\d{4}$",
            message = "Invalid phone number format"
    )
    private String phone;

    @Email(message = "Invalid email format")
    @NotBlank(message = "Email is required")
    private String email;

    @NotBlank(message = "Dog name is required")
    private String dogName;

    @NotBlank(message = "Dog breed is required")
    private String dogBreed;

    private LocalDateTime createdAt;
}