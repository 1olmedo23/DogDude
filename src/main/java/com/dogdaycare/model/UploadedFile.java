package com.dogdaycare.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "uploaded_file")
@Getter
@Setter
public class UploadedFile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // DB: file_name
    @Column(name = "file_name")
    private String fileName;            // original filename (e.g., VetRecord.pdf)

    // DB: file_path
    @Column(name = "file_path")
    private String filePath;            // absolute or server-side stored path

    // DB: file_type
    @Column(name = "file_type")
    private String fileType;            // MIME type or extension

    // DB: expiration_date
    @Column(name = "expiration_date")
    private LocalDate expirationDate;   // optional

    // DB: display_name
    @Column(name = "display_name")
    private String displayName;         // optional user-friendly label

    // DB: size_bytes
    @Column(name = "size_bytes")
    private Long sizeBytes;             // optional

    // DB: created_at (NOT NULL)
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    // For evaluation-time uploads (nullable)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "evaluation_request_id")
    private EvaluationRequest evaluationRequest;

    // NEW: For registered-customer uploads (nullable)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;                  // when customer uploads via portal
}
