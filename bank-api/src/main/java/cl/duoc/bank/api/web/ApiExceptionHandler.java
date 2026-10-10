package cl.duoc.bank.api.web;

import java.net.URI;
import org.springframework.http.ProblemDetail;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(ApiException.class)
    ProblemDetail api(ApiException exception) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(exception.status(), exception.getMessage());
        detail.setType(URI.create("about:blank"));
        return detail;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail validation(MethodArgumentNotValidException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Solicitud inválida");
    }
}
