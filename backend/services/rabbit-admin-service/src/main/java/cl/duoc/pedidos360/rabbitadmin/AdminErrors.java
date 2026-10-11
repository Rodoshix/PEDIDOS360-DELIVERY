package cl.duoc.pedidos360.rabbitadmin;

import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class AdminErrors {
    @ExceptionHandler(AdminFailure.class) ResponseEntity<ProblemDetail> failure(AdminFailure e) {
        return ResponseEntity.status(e.status()).body(ProblemDetail.forStatusAndDetail(e.status(),e.getMessage()));
    }
    @ExceptionHandler({MethodArgumentNotValidException.class,HttpMessageNotReadableException.class})
    ResponseEntity<ProblemDetail> validation(Exception ignored) { return failure(AdminFailure.validation()); }
}
