package org.hhclicker.backend.shared.exception;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.net.URI;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApplicationException.class)
    ProblemDetail handleApplicationException(ApplicationException exception, HttpServletRequest request) {
        HttpStatus status = switch (exception.category()) {
            case VALIDATION -> HttpStatus.BAD_REQUEST;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
            case EXTERNAL_SYSTEM -> HttpStatus.BAD_GATEWAY;
            case INTERNAL -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        return problem(status, exception.machineCode(), exception.category().name(), exception.safeMessage(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handleMethodArgumentNotValid(MethodArgumentNotValidException exception, HttpServletRequest request) {
        String detail = exception.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> "%s: %s".formatted(error.getField(), error.getDefaultMessage()))
                .orElse("Проверьте переданные данные.");
        return problem(HttpStatus.BAD_REQUEST, "REQUEST_VALIDATION_FAILED", "VALIDATION", detail, request);
    }

    @ExceptionHandler({
            ConstraintViolationException.class,
            HandlerMethodValidationException.class,
            HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class
    })
    ProblemDetail handleInvalidRequest(Exception exception, HttpServletRequest request) {
        return problem(
                HttpStatus.BAD_REQUEST,
                "REQUEST_VALIDATION_FAILED",
                "VALIDATION",
                "Проверьте формат переданных данных.",
                request
        );
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpectedException(Exception exception, HttpServletRequest request) {
        log.error("Unhandled request error", exception);
        return problem(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR",
                "INTERNAL",
                "Не удалось выполнить операцию.",
                request
        );
    }

    private ProblemDetail problem(
            HttpStatus status,
            String machineCode,
            String category,
            String detail,
            HttpServletRequest request
    ) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(status.getReasonPhrase());
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", machineCode);
        problem.setProperty("category", category);
        return problem;
    }
}
