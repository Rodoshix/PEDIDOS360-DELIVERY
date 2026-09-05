package cl.duoc.pedidos360.pedidos.exception;

import java.net.URI;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(PedidoNoEncontradoException.class)
    ProblemDetail noEncontrado(PedidoNoEncontradoException error, HttpServletRequest request) {
        return problema(HttpStatus.NOT_FOUND, error.getMessage(), request);
    }

    @ExceptionHandler(TransicionInvalidaException.class)
    ProblemDetail transicionInvalida(TransicionInvalidaException error, HttpServletRequest request) {
        return problema(HttpStatus.CONFLICT, error.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail validacionBody(MethodArgumentNotValidException error, HttpServletRequest request) {
        return problema(HttpStatus.BAD_REQUEST, "Revisa los campos enviados.", request);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ProblemDetail validacionMetodo(HttpServletRequest request) {
        return problema(HttpStatus.BAD_REQUEST, "Revisa los datos enviados.", request);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, ConstraintViolationException.class})
    ProblemDetail malaPeticion(HttpServletRequest request) {
        return problema(HttpStatus.BAD_REQUEST, "El JSON o los datos no son válidos.", request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail prohibido(HttpServletRequest request) {
        return problema(HttpStatus.FORBIDDEN, "No tienes permiso para esta operación.", request);
    }

    @ExceptionHandler(AuthenticationException.class)
    ProblemDetail noAutenticado(HttpServletRequest request) {
        return problema(HttpStatus.UNAUTHORIZED, "Se requiere una identidad validada.", request);
    }

    private ProblemDetail problema(HttpStatus status, String detalle, HttpServletRequest request) {
        var problem = ProblemDetail.forStatusAndDetail(status, detalle);
        problem.setInstance(URI.create(request.getRequestURI()));
        return problem;
    }
}
