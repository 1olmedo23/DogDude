package com.dogdaycare.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

@Entity
@Getter
@Setter
public class UploadedFile {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String fileName;
    private String filePath;
    private String fileType;
    private LocalDate expirationDate;
    @ManyToOne
    private EvaluationRequest evaluationRequest;
}