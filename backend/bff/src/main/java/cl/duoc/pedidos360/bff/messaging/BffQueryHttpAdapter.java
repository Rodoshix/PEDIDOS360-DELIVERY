package cl.duoc.pedidos360.bff.messaging;

import cl.duoc.pedidos360.messaging.Domain;
import cl.duoc.pedidos360.messaging.relay.QueryTimeoutException;
import cl.duoc.pedidos360.messaging.relay.QueryUnavailableException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.http.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.util.EnumMap;
import java.util.Optional;

/** Selección por consulta, sin fallback. Solo las cuatro rutas equivalentes pueden migrar. */
@Component
public final class BffQueryHttpAdapter {
    enum Mode { HTTP, RABBITMQ }
    private final EnumMap<Domain, Mode> modes = new EnumMap<>(Domain.class);
    private final BffQueryAdapter queries;
    private final JsonMapper json;

    public BffQueryHttpAdapter(Environment env, ObjectProvider<BffQueryAdapter> provider, JsonMapper json) {
        this.json = json;
        for (Domain domain : Domain.values()) {
            String property = "bff.queries." + domain.name().toLowerCase(java.util.Locale.ROOT);
            modes.put(domain, Mode.valueOf(env.getProperty(property, "HTTP")));
        }
        queries = modes.containsValue(Mode.RABBITMQ) ? provider.getIfAvailable() : null;
        if (modes.containsValue(Mode.RABBITMQ) && queries == null)
            throw new IllegalStateException("Consultas RabbitMQ requieren relay ACTIVE");
        if ((modes.get(Domain.USUARIOS) == Mode.RABBITMQ || modes.get(Domain.PAGOS) == Mode.RABBITMQ)
                && !queries.verificaPruebas())
            throw new IllegalStateException("Usuarios/Pagos RabbitMQ requieren verificación de IdentityProof");
        if (queries != null) for (Domain domain : Domain.values()) {
            if (modes.get(domain) == Mode.RABBITMQ || (domain == Domain.USUARIOS && modes.get(Domain.PAGOS) == Mode.RABBITMQ)) {
                String operation = env.getRequiredProperty("pedidos360.messaging.routing." + switch (domain) {
                    case USUARIOS -> "usuario"; case RESTAURANTES -> "restaurante";
                    case PRODUCTOS -> "producto"; case PAGOS -> "pago";
                });
                queries.registrar(domain, new QueryInvoker() {
                    public String operacion() { return operation; }
                    public cl.duoc.pedidos360.messaging.envelope.OperationResult invocar(JsonNode p, String c) {
                        throw new UnsupportedOperationException("La operación se ejecuta exclusivamente en el servicio");
                    }
                });
            }
        }
    }

    /** Vacío significa ruta HTTP elegida antes de realizar cualquier publicación. */
    public Optional<ResponseEntity<?>> consultar(String method, String path, JwtAuthenticationToken token) {
        if (!"GET".equals(method)) return Optional.empty();
        Domain domain;
        var payload = json.createObjectNode();
        if (path.equals("/usuarios/me")) domain = Domain.USUARIOS;
        else if (path.equals("/restaurantes")) domain = Domain.RESTAURANTES;
        else if (path.matches("/productos/restaurante/[1-9][0-9]*/disponibles")) {
            domain = Domain.PRODUCTOS;
            payload.put("restauranteId", Long.parseLong(path.split("/")[3]));
        } else if (path.matches("/pagos/[1-9][0-9]*")) {
            domain = Domain.PAGOS;
            payload.put("pagoId", Long.parseLong(path.substring(7)));
        } else return Optional.empty();
        if (modes.get(domain) == Mode.HTTP) return Optional.empty();
        QueryOperationBudget budget = queries.iniciarOperacion(token);
        try {
            var requestDeadline = budget.originalDeadline();
            if (domain == Domain.PAGOS) {
                var identity = queries.ejecutarVerificado(Domain.USUARIOS, json.createObjectNode(), token, budget, requestDeadline);
                if (identity.identityJws() == null) throw new QueryUnavailableException("Prueba verificada requerida", null);
                requestDeadline = identity.effectiveDeadline();
                budget.requireRemaining(requestDeadline);
                payload.put("pruebaIdentidad", identity.identityJws());
            }
            var result = queries.ejecutarVerificado(domain, payload, token, budget, requestDeadline);
            validatePublicPayload(domain, result.result().payload(), payload);
            // Serialización y proyección consumen el mismo presupuesto que publicación y espera.
            var response = ResponseEntity.status(result.result().status()).cacheControl(CacheControl.noStore())
                    .contentType(MediaType.APPLICATION_JSON).body(json.writeValueAsString(result.result().payload()));
            budget.requireRemaining(result.effectiveDeadline());
            return Optional.of(response);
        } catch (BffQueryAdapter.VerifiedBusinessException business) {
            try {
                var response = error(domain, business.status().value());
                budget.requireRemaining(business.effectiveDeadline);
                return Optional.of(response);
            } catch (QueryTimeoutException expired) {
                budget.invalidate();
                return Optional.of(error(domain, 504));
            }
        } catch (QueryTimeoutException expired) {
            budget.invalidate();
            return Optional.of(error(domain, 504));
        } catch (QueryUnavailableException unavailable) {
            return Optional.of(error(domain, 502));
        }
    }

    private static void validatePublicPayload(Domain domain, JsonNode body, JsonNode request) {
        if (domain == Domain.USUARIOS) return; // Proyección estricta tras validación independiente de Usuarios.
        var fields = switch (domain) {
            case RESTAURANTES -> java.util.Set.of("id", "nombre", "descripcion", "direccion", "estado");
            case PRODUCTOS -> java.util.Set.of("id", "restauranteId", "nombre", "descripcion", "precio", "categoria", "disponible");
            case PAGOS -> java.util.Set.of("pagoId", "pedidoId", "usuarioId", "monto", "moneda", "metodo", "estado", "fecha");
            default -> throw new IllegalStateException();
        };
        if (domain != Domain.PAGOS && !body.isArray()) throw malformed();
        for (JsonNode item : domain == Domain.PAGOS ? java.util.List.of(body) : body) {
            if (!item.isObject()) throw malformed();
            var names = new java.util.HashSet<String>();
            item.properties().forEach(e -> names.add(e.getKey()));
            if (!names.equals(fields)) throw malformed();
            if (domain == Domain.PAGOS && !sameId(item.path("pagoId"), request.path("pagoId"))) throw malformed();
            if (domain == Domain.PRODUCTOS && (!sameId(item.path("restauranteId"), request.path("restauranteId"))
                    || !item.path("disponible").isBoolean() || !item.path("disponible").booleanValue())) throw malformed();
        }
    }

    private static boolean sameId(JsonNode actual, JsonNode expected) {
        return actual.isIntegralNumber() && actual.canConvertToLong() && actual.longValue() == expected.longValue();
    }

    private static QueryUnavailableException malformed() {
        return new QueryUnavailableException("Respuesta no corresponde al DTO público esperado", null);
    }

    private static ResponseEntity<ProblemDetail> error(Domain domain, int status) {
        String detail = status >= 500 ? (domain == Domain.USUARIOS ? "Usuarios no está disponible temporalmente."
                : "El servicio no está disponible temporalmente.") : "La operación no pudo completarse.";
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(status), detail));
    }
}
