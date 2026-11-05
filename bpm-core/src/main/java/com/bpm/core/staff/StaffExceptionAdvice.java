package com.bpm.core.staff;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import java.io.IOException;

@RestControllerAdvice
@org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
public class StaffExceptionAdvice {
    @ExceptionHandler(StaffFailure.class)
    void failure(StaffFailure failure, HttpServletRequest req, HttpServletResponse res) throws IOException {
        StaffErrorWriter.write(req, res, failure);
    }
}
