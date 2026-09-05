package cl.duoc.pedidos360.usuarios.exception;

import java.net.URI;
import java.util.LinkedHashMap;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ApiException.class)
    ProblemDetail api(ApiException error, HttpServletRequest request) {
        return problema(error.getStatus(), error.getMessage(), request);
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

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail json(HttpServletRequest request) {
        return problema(HttpStatus.BAD_REQUEST,
                "JSON inválido o campos no permitidos. Solo se acepta nombre, apellido, email y telefono.",
                request);
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class, HandlerMethodValidationException.class,
            ConstraintViolationException.class})
    ProblemDetail parametros(HttpServletRequest request) {
        return problema(HttpStatus.BAD_REQUEST, "Los datos o parámetros no son válidos.", request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail conflicto(HttpServletRequest request) {
        return problema(HttpStatus.CONFLICT, "Los datos entran en conflicto con un perfil existente.", request);
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ProblemDetail concurrencia(HttpServletRequest request) {
        return problema(HttpStatus.CONFLICT, "El perfil cambió durante la operación. Vuelve a consultarlo.", request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail permisos(HttpServletRequest request) {
        return problema(HttpStatus.FORBIDDEN, "No tienes permiso para esta operación.", request);
    }

    @ExceptionHandler(AuthenticationException.class)
    ProblemDetail autenticacion(HttpServletRequest request) {
        return problema(HttpStatus.UNAUTHORIZED, "Se requiere una identidad validada.", request);
    }

    private ProblemDetail problema(HttpStatus status, String detalle, HttpServletRequest request) {
        var problem = ProblemDetail.forStatusAndDetail(status, detalle);
        problem.setInstance(URI.create(request.getRequestURI()));
        return problem;
    }
}
