
package com.dogdaycare.exception;

import org.apache.catalina.connector.ClientAbortException;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

@ControllerAdvice
public class GlobalExceptionHandler {

    // A client disconnected while receiving a response.
    // Do not attempt to render an HTML error page.
    @ExceptionHandler(ClientAbortException.class)
    public void handleClientAbort(ClientAbortException e) {
        // No response body is needed because the client disconnected.
    }

    // Preserve existing handling for other exceptions.
    @ExceptionHandler(Exception.class)
    public String handleException(Exception e, Model model) {
        model.addAttribute("errorMessage", e.getMessage());
        return "error-page";
    }
}
