package com.dogdaycare.service;

import com.dogdaycare.model.UploadedFile;
import com.dogdaycare.model.User;
import com.dogdaycare.repository.FileRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
public class UploadService {

    private final FileRepository fileRepository;

    private final Path uploadDir;

    public UploadService(FileRepository fileRepository,
                         @Value("${file.upload-dir}") String uploadDirProp) throws IOException {
        this.fileRepository = fileRepository;
        this.uploadDir = Paths.get(uploadDirProp);
        Files.createDirectories(this.uploadDir); // ensure exists
    }

    public List<UploadedFile> listForUser(User user) {
        return fileRepository.findByUserIdOrderByCreatedAtDesc(user.getId());
    }

    public UploadedFile storeForUser(User user,
                                     MultipartFile file,
                                     String displayName,
                                     LocalDate expirationDate) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("No file provided.");
        }
        final String originalFilename = StringUtils.cleanPath(file.getOriginalFilename());
        final String ext = extractExtension(originalFilename);
        final String storedBase = UUID.randomUUID().toString() + (ext.isEmpty() ? "" : "." + ext);
        final Path target = uploadDir.resolve(storedBase);

        // Save to disk
        Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);

        // Persist metadata
        UploadedFile uf = new UploadedFile();
        uf.setUser(user);
        uf.setFileName(originalFilename);
        uf.setDisplayName((displayName != null && !displayName.isBlank()) ? displayName : null);
        uf.setExpirationDate(expirationDate);
        uf.setFileType(file.getContentType());
        uf.setSizeBytes(file.getSize());
        uf.setFilePath(target.toAbsolutePath().toString());

        return fileRepository.save(uf);
    }

    public Path resolveDownloadPath(UploadedFile uf) {
        return Paths.get(uf.getFilePath());
    }

    private String extractExtension(String filename) {
        if (filename == null) return "";
        int dot = filename.lastIndexOf('.');
        return (dot >= 0 && dot < filename.length() - 1) ? filename.substring(dot + 1) : "";
    }
}
