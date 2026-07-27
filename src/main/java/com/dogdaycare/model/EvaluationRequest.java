package com.dogdaycare.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.AssertTrue;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

// JSON (de)serialization
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

@Entity
@Getter
@Setter
public class EvaluationRequest {

    // ---- Minimal persistence for extra dogs: one JSON column + transient list ----
    @Column(name = "additional_dogs_json")
    private String additionalDogsJson;

    @Transient
    private List<AdditionalDog> additionalDogs = new ArrayList<>();

    public List<AdditionalDog> getAdditionalDogs() {
        return additionalDogs;
    }
    public void setAdditionalDogs(List<AdditionalDog> additionalDogs) {
        this.additionalDogs = additionalDogs;
    }

    public static class AdditionalDog {
        private String name;
        private String breed;

        public AdditionalDog() {}
        public AdditionalDog(String name, String breed) {
            this.name = name;
            this.breed = breed;
        }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getBreed() { return breed; }
        public void setBreed(String breed) { this.breed = breed; }
    }

    @PrePersist
    @PreUpdate
    private void writeExtrasToJson() {
        try {
            if (additionalDogs == null || additionalDogs.isEmpty()) {
                this.additionalDogsJson = null;
            } else {
                ObjectMapper om = new ObjectMapper();
                this.additionalDogsJson = om.writeValueAsString(additionalDogs);
            }
        } catch (Exception e) {
            // fail-safe
            this.additionalDogsJson = null;
        }
    }

    @PostLoad
    private void readExtrasFromJson() {
        try {
            if (this.additionalDogsJson == null || this.additionalDogsJson.isBlank()) {
                this.additionalDogs = new ArrayList<>();
            } else {
                ObjectMapper om = new ObjectMapper();
                this.additionalDogs = om.readValue(
                        this.additionalDogsJson,
                        new TypeReference<List<AdditionalDog>>() {}
                );
            }
        } catch (Exception e) {
            this.additionalDogs = new ArrayList<>();
        }
    }
    // ---- End extras JSON support ----

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private boolean approved = false;

    @NotBlank(message = "Client name is required")
    private String clientName;

    @NotBlank(message = "Phone is required")
    private String phone;

    @AssertTrue(message = "Phone number must contain exactly 10 digits")
    public boolean isPhoneDigitCountValid() {
        if (phone == null || phone.isBlank()) {
            return true;
        }

        // Only permit digits and common U.S. phone-number formatting characters.
        if (!phone.matches("^[0-9().\\s-]+$")) {
            return false;
        }

        String digits = phone.replaceAll("\\D", "");
        return digits.length() == 10;
    }

    @Email(message = "Invalid email format")
    @NotBlank(message = "Email is required")
    private String email;

    @NotBlank(message = "Dog name is required")
    private String dogName;

    @NotBlank(message = "Dog breed is required")
    private String dogBreed;

    private LocalDateTime createdAt;
}
