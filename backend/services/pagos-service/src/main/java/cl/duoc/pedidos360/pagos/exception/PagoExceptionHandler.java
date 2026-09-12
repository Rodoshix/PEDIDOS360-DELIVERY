package cl.duoc.pedidos360.pagos.exception;

import java.net.URI;
import java.util.LinkedHashMap;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
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
public class PagoExceptionHandler {

    @ExceptionHandler(PagoException.class)
    ProblemDetail pago(PagoException error, HttpServletRequest request) {
        return problema(error.getStatus(), error.getMessage(), request);
    }

    @ExceptionHandler(PagoNoEncontradoException.class)
    ProblemDetail noEncontrado(PagoNoEncontradoException error, HttpServletRequest request) {
        return problema(HttpStatus.NOT_FOUND, error.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail validacion(MethodArgumentNotValidException error, HttpServletRequest request) {
        var problem = problema(HttpStatus.BAD_REQUEST, "Revisa los campos enviados.", request);
        var campos = new LinkedHashMap<String, String>();
        error.getBindingResult().getFieldErrors()
                .forEach(field -> campos.putIfAbsent(field.getField(), field.getDefaultMessage()));
        problem.setProperty("errores", campos);
        return problem;
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ProblemDetail validacionMetodo(HttpServletRequest request) {
        return problema(HttpStatus.BAD_REQUEST, "Revisa los datos enviados.", request);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, ConstraintViolationException.class})
    ProblemDetail malaPeticion(HttpServletRequest request) {
        return problema(HttpStatus.BAD_REQUEST, "El JSON o los datos no son válidos.", request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail conflicto(HttpServletRequest request) {
        return problema(HttpStatus.CONFLICT, "Los datos entran en conflicto con un pago existente.", request);
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
