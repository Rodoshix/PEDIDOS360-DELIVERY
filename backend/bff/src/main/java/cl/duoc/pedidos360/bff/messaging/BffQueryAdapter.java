package cl.duoc.pedidos360.bff.messaging;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import cl.duoc.pedidos360.messaging.Domain;
import cl.duoc.pedidos360.messaging.MessagingProperties;
import cl.duoc.pedidos360.messaging.envelope.OperationResult;
import cl.duoc.pedidos360.messaging.envelope.QueryBusinessException;
import cl.duoc.pedidos360.messaging.envelope.QueryResponse;
import cl.duoc.pedidos360.messaging.envelope.RequestEnvelope;
import cl.duoc.pedidos360.messaging.envelope.RequestEnvelopeContext;
import cl.duoc.pedidos360.messaging.envelope.ResponseSchema;
import cl.duoc.pedidos360.messaging.relay.PendingCorrelationRegistry;
import cl.duoc.pedidos360.messaging.relay.QueryTimeoutException;
import cl.duoc.pedidos360.messaging.relay.QueryUnavailableException;
import cl.duoc.pedidos360.messaging.relay.RequestPublisher;
import tools.jackson.databind.JsonNode;

/**
 * Adaptador comun del BFF para consultas internas.
 *
 * <pre>
 * HTTP -> RequestFactory -> RequestPublisher -> broker
 *      -> espera correlacionada -> QueryInvoker (dominio)
 *      -> respuesta correlacionada -> resultado equivalente al HTTP
 * </pre>
 *
 * <p>Garantias cubiertas:
 * <ul>
 *   <li>plazo absoluto configurable, sin reintentos implicitos: la publicacion confirmada descuenta su
 *       tiempo del presupuesto, de modo que confirm + espera nunca superan el deadline;</li>
 *   <li>descarte de respuestas tardias o duplicadas por parte del registro de correlaciones;</li>
 *   <li>limpieza de la correlacion al vencer, al cancelar la espera o al cerrar el BFF;</li>
 *   <li>broker no disponible o publicacion sin ruta: error equivalente al HTTP 502;</li>
 *   <li>plazo agotado: error equivalente al HTTP 504.</li>
 * </ul>
 *
 * <p>El adaptador no declara endpoints ni cambia rutas: HTTP sigue siendo el transporte
 * predeterminado hasta el corte de #70. Ninguna operacion concreta esta implementada aqui.
 */
public class BffQueryAdapter {

    private static final Logger log = LoggerFactory.getLogger(BffQueryAdapter.class);

    private final RequestFactory fabrica;
    private final RequestPublisher publicador;
    private final PendingCorrelationRegistry correlaciones;
    private final RequestEnvelopeContext contexto;
    private final ResponseSchema esquema;
    private final MessagingProperties properties;
    private final Map<Domain, QueryInvoker> operaciones = new EnumMap<>(Domain.class);
    private BffIdentityProofValidator pruebas;

    public void configurarPruebas(BffIdentityProofValidator pruebas) { this.pruebas = pruebas; }

    public BffQueryAdapter(RequestFactory fabrica, RequestPublisher publicador,
            PendingCorrelationRegistry correlaciones, RequestEnvelopeContext contexto, ResponseSchema esquema,
            MessagingProperties properties) {
        this.fabrica = fabrica;
        this.publicador = publicador;
        this.correlaciones = correlaciones;
        this.contexto = contexto;
        this.esquema = esquema;
        this.properties = properties;
    }

    /** Registra una operacion de dominio. Idempotente por dominio. */
    public void registrar(Domain domain, QueryInvoker operacion) {
        if (!fabrica.properties().routing().operacion(domain).equals(operacion.operacion()))
            throw new IllegalArgumentException(
                    "la operacion declarada no coincide con la routing key configurada para el dominio");
        operaciones.put(domain, operacion);
    }

    public Optional<QueryInvoker> operacion(Domain domain) {
        return Optional.ofNullable(operaciones.get(domain));
    }

    /**
     * Ejecuta la consulta completa del dominio con el actor autenticado.
     *
     * @throws QueryUnavailableException si el broker no esta disponible o la publicacion no se
     *     confirma.
     * @throws QueryTimeoutException si el plazo se agota sin respuesta correlacionada.
     * @throws QueryBusinessException si el dominio responde un error de negocio esperado.
     */
    public OperationResult ejecutar(Domain domain, JsonNode payload, JwtAuthenticationToken token) {
        return ejecutar(domain, payload, token, fabrica.iniciarOperacion(token));
    }

    /** Conserva el presupuesto original; ningún endpoint invoca aún una cadena Usuarios→Pagos. */
    public OperationResult ejecutar(Domain domain, JsonNode payload, JwtAuthenticationToken token, QueryOperationBudget budget) {
        return ejecutar(domain, payload, token, budget, budget.originalDeadline());
    }

    /** Subdeadline explícito, sin orquestación ni renovación del presupuesto original. */
    public OperationResult ejecutar(Domain domain, JsonNode payload, JwtAuthenticationToken token,
            QueryOperationBudget budget, Instant requestDeadline) {
        return ejecutarVerificado(domain, payload, token, budget, requestDeadline).result();
    }

    QueryOperationBudget iniciarOperacion(JwtAuthenticationToken token) { return fabrica.iniciarOperacion(token); }

    boolean verificaPruebas() { return pruebas != null; }

    /** Resultado interno de una consulta correlacionada; nunca se serializa como DTO HTTP. */
    record VerifiedResult(OperationResult result, Instant effectiveDeadline, String identityJws) {
        @Override public String toString() { return "VerifiedResult[redacted]"; }
    }

    static final class VerifiedBusinessException extends QueryBusinessException {
        final Instant effectiveDeadline;
        VerifiedBusinessException(QueryBusinessException error, Instant deadline) {
            super(error.status(), error.code(), "La operación no pudo completarse.");
            effectiveDeadline = deadline;
        }
    }

    VerifiedResult ejecutarVerificado(Domain domain, JsonNode payload, JwtAuthenticationToken token,
            QueryOperationBudget budget, Instant requestDeadline) {
        QueryInvoker operacion = operacion(domain).orElseThrow(() -> new IllegalStateException(
                "el dominio " + domain + " no tiene una operacion registrada en el BFF"));
        if (domain == Domain.USUARIOS && pruebas != null) budget.claimUsuarios();
        RequestPlan plan = fabrica.planificar(domain, operacion.operacion(), payload, token, budget, requestDeadline);
        var actor = fabrica.verificarActor(domain, plan);
        Instant limit = earlier(plan.envelope().expiresAt(), actor.expiraEn());
        if (domain == Domain.PAGOS) {
            if (pruebas == null) throw new QueryUnavailableException("Pagos requiere verificador de prueba de identidad", null);
            limit = earlier(limit, pruebas.limitePagos(plan, budget, actor));
        }
        final Instant effectiveDeadline = limit;
        budget.requireRemaining(effectiveDeadline);
        CompletableFuture<byte[]> espera = correlaciones.registrar(plan.correlationId());
        long inicio = System.nanoTime();
        try {
            publicador.publicarConMedicion(plan.envelope(), plan.correlationId(), () -> budget.requireRemaining(effectiveDeadline));
            // El deadline es absoluto: la espera de la respuesta recibe solo lo que queda del
            // presupuesto, no un plazo nuevo. Antes se sumaban confirm (3 s) + espera (5 s).
            long restante = budget.requireRemaining(effectiveDeadline);
            if (restante <= 0) throw new TimeoutException("presupuesto agotado en la publicacion");
            byte[] cuerpo = espera.get(restante, TimeUnit.NANOSECONDS);
            budget.requireRemaining(effectiveDeadline);
            var resolved = resolver(domain, plan, cuerpo, System.nanoTime() - inicio, budget, effectiveDeadline);
            budget.requireRemaining(budget.originalDeadline());
            budget.requireRemaining(earlier(effectiveDeadline, resolved.effectiveDeadline()));
            return new VerifiedResult(resolved.result(), earlier(effectiveDeadline, resolved.effectiveDeadline()), resolved.identityJws());
        } catch (QueryBusinessException negocio) {
            budget.requireRemaining(effectiveDeadline);
            throw new VerifiedBusinessException(negocio, effectiveDeadline);
        } catch (QueryUnavailableException sinBroker) {
            correlaciones.descartar(plan.correlationId());
            throw sinBroker;
        } catch (QueryTimeoutException vencido) {
            budget.invalidate();
            correlaciones.descartar(plan.correlationId());
            throw vencido;
        } catch (TimeoutException vencido) {
            budget.invalidate();
            correlaciones.descartar(plan.correlationId());
            log.warn("Consulta domain={} correlationId={} presupuesto de {} ms agotado", domain,
                    plan.correlationId(), properties.deadline().toMillis());
            throw new QueryTimeoutException("plazo de la consulta agotado");
        } catch (InterruptedException interrumpido) {
            correlaciones.descartar(plan.correlationId());
            Thread.currentThread().interrupt();
            throw new QueryUnavailableException("consulta interrumpida", interrumpido);
        } catch (ExecutionException fallo) {
            correlaciones.descartar(plan.correlationId());
            throw new QueryUnavailableException("la espera de la consulta fallo", fallo.getCause());
        }
    }

    /** Cancela las correlaciones en vuelo: el apagado no debe dejar esperas huerfanas. */
    @jakarta.annotation.PreDestroy
    public void alCerrar() {
        int canceladas = correlaciones.cancelarTodo();
        if (canceladas > 0)
            log.warn("BFF cerrando: {} correlaciones en vuelo canceladas", canceladas);
    }

    /** Resuelve el cuerpo de la respuesta en el resultado equivalente al contrato HTTP. */
    private record ResolvedResult(OperationResult result, Instant effectiveDeadline, String identityJws) {
        @Override public String toString() { return "ResolvedResult[redacted]"; }
    }

    private static Instant earlier(Instant a, Instant b) { return a.isBefore(b) ? a : b; }

    private ResolvedResult resolver(Domain domain, RequestPlan plan, byte[] cuerpo, long nanos, QueryOperationBudget budget,
            Instant acceptanceDeadline) {
        budget.requireRemaining(acceptanceDeadline);
        QueryResponse respuesta = esquema.leer(cuerpo, properties.maxBodyBytes(), response -> {
            budget.requireRemaining(acceptanceDeadline);
            return plan.envelope().messageId().equals(response.messageId())
                    && plan.correlationId().equals(response.correlationId())
                    && plan.envelope().operacion().equals(response.operacion());
        }).orElseThrow(
                () -> new QueryUnavailableException("la respuesta no corresponde al contrato", null));
        budget.requireRemaining(acceptanceDeadline);
        if (!plan.envelope().messageId().equals(respuesta.messageId())
                || !plan.correlationId().equals(respuesta.correlationId())
                || !plan.envelope().operacion().equals(respuesta.operacion()))
            throw new QueryUnavailableException("la respuesta no referencia el request enviado", null);
        if (respuesta.success()) {
            if (respuesta.payload() == null || respuesta.payload().isNull())
                throw new QueryUnavailableException("la respuesta exitosa no trae payload", null);
            JsonNode payload = respuesta.payload();
            String identityJws = null;
            Instant effectiveDeadline = plan.envelope().expiresAt();
            if (domain == Domain.USUARIOS && pruebas != null) {
                var verified = pruebas.validate(payload, plan, budget);
                identityJws = payload.path("pruebaIdentidad").stringValue();
                payload = verified.perfil();
                effectiveDeadline = verified.usableUntil();
            }
            else if (payload.has("pruebaIdentidad"))
                throw new QueryUnavailableException("respuesta con prueba de identidad requiere verificador habilitado", null);
            return new ResolvedResult(new OperationResult(respuesta.operacion(), respuesta.status(), payload), effectiveDeadline, identityJws);
        }
        budget.requireRemaining(acceptanceDeadline);
        var detalle = respuesta.error();
        log.info("Consulta operacion={} correlationId={} error de negocio status={} code={} {} ms",
                plan.envelope().operacion(), plan.correlationId(), respuesta.status(),
                detalle == null ? "SIN_CODIGO" : detalle.code(), nanos / 1_000_000);
        var error = new QueryBusinessException(org.springframework.http.HttpStatus.valueOf(respuesta.status()),
                detalle == null ? "ERROR_DE_NEGOCIO" : detalle.code(),
                detalle == null ? "La operacion no pudo completarse." : detalle.detail());
        budget.requireRemaining(acceptanceDeadline);
        throw error;
    }

    /** Envelope de diagnostico y reenvio manual, sin alterar el contrato. */
    public byte[] serializar(RequestEnvelope envelope) {
        return contexto.escribir(envelope);
    }
}
