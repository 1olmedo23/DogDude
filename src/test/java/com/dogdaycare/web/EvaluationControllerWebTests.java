package com.dogdaycare.web;

import com.dogdaycare.controller.EvaluationController;
import com.dogdaycare.model.EvaluationRequest;
import com.dogdaycare.repository.EvaluationRepository;
import com.dogdaycare.service.EmailService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.io.File;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = EvaluationController.class)
@AutoConfigureMockMvc(addFilters = false)
class EvaluationControllerWebTests {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private EvaluationRepository evaluationRepository;

    @MockBean
    private EmailService emailService;

    @Test
    void submitEvaluation_formattedPhone_isNormalizedBeforeSave()
            throws Exception {

        when(evaluationRepository.findByEmail(
                "customer@test.local"
        )).thenReturn(Optional.empty());

        when(evaluationRepository.save(
                any(EvaluationRequest.class)
        )).thenAnswer(invocation ->
                invocation.getArgument(0)
        );

        var request =
                multipart("/evaluation")
                        .param("clientName", "Test Customer")
                        .param("phone", "(206) 555-1234")
                        .param("email", "customer@test.local")
                        .param("dogName", "Buddy")
                        .param("dogBreed", "Labrador");

        mvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(view().name(
                        "evaluation-success"
                ));

        ArgumentCaptor<EvaluationRequest> evaluationCaptor =
                ArgumentCaptor.forClass(
                        EvaluationRequest.class
                );

        verify(evaluationRepository)
                .save(evaluationCaptor.capture());

        EvaluationRequest saved =
                evaluationCaptor.getValue();

        /*
         * The database value must contain digits only.
         */
        assertEquals(
                "2065551234",
                saved.getPhone()
        );

        /*
         * The business-facing email should still display
         * the phone in readable U.S. formatting.
         */
        ArgumentCaptor<String> businessMessageCaptor =
                ArgumentCaptor.forClass(String.class);

        verify(emailService)
                .sendEmailWithAttachments(
                        anyString(),
                        eq("New Evaluation Request"),
                        businessMessageCaptor.capture(),
                        anyList()
                );

        String businessMessage =
                businessMessageCaptor.getValue();

        org.junit.jupiter.api.Assertions.assertTrue(
                businessMessage.contains(
                        "Phone: (206) 555-1234"
                )
        );

        /*
         * Customer confirmation email is still sent.
         */
        verify(emailService)
                .sendHtmlEmail(
                        eq("customer@test.local"),
                        eq("We received your evaluation – Fremont Dog Plaza"),
                        anyString()
                );
    }
}