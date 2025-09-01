package com.dogdaycare.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.io.File;
import java.util.List;

@Service
public class EmailService {

    private final JavaMailSender mailSender;

    @Value("${spring.mail.username}")
    private String mailSenderUsername;  // Ensures emails have a proper "from" address

    public EmailService(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    // For customer confirmation emails (reliable MimeMessage version)
    public void sendEmail(String to, String subject, String text) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false);
            helper.setFrom(mailSenderUsername);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(text);
            mailSender.send(message);
        } catch (MessagingException e) {
            throw new RuntimeException("Failed to send customer email", e);
        }
    }

    // For business emails with attachments
    public void sendEmailWithAttachments(String to, String subject, String text, List<File> attachments) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true);
            helper.setFrom(mailSenderUsername);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(text);

            if (attachments != null) {
                for (File file : attachments) {
                    helper.addAttachment(file.getName(), new FileSystemResource(file));
                }
            }

            mailSender.send(message);
        } catch (MessagingException e) {
            throw new RuntimeException("Failed to send email with attachments", e);
        }
    }
}