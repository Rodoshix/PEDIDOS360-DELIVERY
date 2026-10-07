package cl.duoc.pedidos360.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Queue;

import cl.duoc.pedidos360.messaging.actor.ActorContext;
import cl.duoc.pedidos360.messaging.actor.ActorContextException;
import cl.duoc.pedidos360.messaging.actor.ActorContextSigner;
import cl.duoc.pedidos360.messaging.actor.SigningKey;
import cl.duoc.pedidos360.messaging.actor.SigningKeyProvider;
import cl.duoc.pedidos360.messaging.envelope.EnvelopeException;
import cl.duoc.pedidos360.messaging.envelope.QueryResponse;
import cl.duoc.pedidos360.messaging.envelope.RequestEnvelope;
import cl.duoc.pedidos360.messaging.envelope.RequestEnvelopeContext;
import cl.duoc.pedidos360.messaging.envelope.ResponseSchema;
import tools.jackson.databind.json.JsonMapper;

/**
 * Contrato de envelope, respuesta, topologia y contexto de actor.
 *
 * <p>Cubre los casos 1, 17, 18, 19, 20 y 22 del issue #77 sin broker: publicacion, identidad estable,
 * replyTo fuera de contrato, actor invalido, payload invalido y ausencia de bucle por requeue.
 */
class MessagingContractTests {

    private static final Instant AHORA = Instant.parse("2026-10-07T01:30:00Z");
    private static final Clock RELOJ = Clock.fixed(AHORA, ZoneOffset.UTC);
    private static final byte[] MATERIAL = "clave-de-prueba-con-al-menos-32-bytes".getBytes(StandardCharsets.UTF_8);
    private static final UUID CLAVE = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final String TENANT = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";

    private final JsonMapper json = JsonMapper.builder().build();
    private final RequestEnvelopeContext contexto = new RequestEnvelopeContext();
    private final ResponseSchema esquema = new ResponseSchema();

    static MessagingProperties propiedades() {
        return new MessagingProperties(RelayMode.ACTIVE, RelayMode.Role.SERVICE,
                new MessagingProperties.Exchanges("p360.queries", "p360.retry", "p360.dlx"),
                new MessagingProperties.Queues("p360.bff.consultas.respuestas.q"),
                new MessagingProperties.Naming("p360.", ".consultas.q", ".consultas.retry.1s.q", ".consultas.dlq",
                        ".retry.1s", ".failed"),
                new MessagingProperties.DomainRouting("usuario.consultar-actual.v1", "restaurante.listar.v1",
                        "producto.listar-disponibles.v1", "pago.consultar.v1", "usuario.consultar-actual",
                        "restaurante.listar", "producto.listar-disponibles", "pago.consultar"),
                Duration.ofSeconds(5), Duration.ofSeconds(4), Duration.ofSeconds(1), Duration.ofSeconds(3),
                Duration.ofMillis(500), 2, 1024, 262_144);
    }

    static SigningKeyProvider claves() {
        var firma = new SigningKey(CLAVE, MATERIAL);
        return new SigningKeyProvider() {
            @Override
            public Optional<SigningKey> claveParaFirmar() {
                return Optional.of(firma);
            }

            @Override
            public Optional<SigningKey> clavePorId(UUID keyId) {
                return firma.keyId().equals(keyId) ? Optional.of(firma) : Optional.empty();
            }
        };
    }

    static ActorContextSigner firmante() {
        return new ActorContextSigner(claves(), RELOJ, Duration.ofSeconds(5));
    }

    static ActorContext actor(String destino, Instant expira) {
        return new ActorContext(UUID.fromString(TENANT), UUID.fromString("12345678-1234-1234-1234-123456789012"),
                Set.of("CLIENTE"), Set.of("access_as_user"), AHORA, expira, destino, CLAVE);
    }

    private RequestEnvelope envelope(String operacion, Instant expira, String sobre) {
        return RequestEnvelope.crear(UUID.fromString("7cbd68bf-bcf7-49dd-b1e2-8e15a7a0a681"), operacion,
                json.createObjectNode().put("restauranteId", 7), sobre, AHORA, expira);
    }

    private String valido(String operacion, String destino) {
        return new String(contexto.escribir(envelope(operacion, AHORA.plusSeconds(5),
                firmante().emitir(actor(destino, AHORA.plusSeconds(4)), AHORA.plusSeconds(5)))),
                StandardCharsets.UTF_8);
    }

    @Test
    void envelopeViajaConOchoCamposEnOrdenYSeReleeIdentico() {
        RequestEnvelope original = envelope("producto.listar-disponibles.v1", AHORA.plusSeconds(5),
                firmante().emitir(actor("p360.productos.consultas.q", AHORA.plusSeconds(4)), AHORA.plusSeconds(5)));
        byte[] cuerpo = contexto.escribir(original);
        RequestEnvelope leido = contexto.leer(cuerpo, 262_144);
        assertThat(leido).isEqualTo(original);
        assertThat(new String(cuerpo, StandardCharsets.UTF_8)).startsWith(
                "{\"messageId\":\"7cbd68bf-bcf7-49dd-b1e2-8e15a7a0a681\",\"type\":\"ConsultaPedidos360\"");
        assertThat(json.readTree(cuerpo).propertyNames()).containsExactly("messageId", "type", "version",
                "occurredAt", "expiresAt", "actor", "operacion", "payload");
    }

    @Test
    void envelopeRechazaCampoExtraFaltanteDuplicadoYNoCanonico() {
        String valido = valido("usuario.consultar-actual.v1", "p360.usuarios.consultas.q");
        String conExtra = valido.substring(0, valido.lastIndexOf('}')) + ",\"extra\":1}";
        assertThatThrownBy(() -> contexto.leer(conExtra.getBytes(StandardCharsets.UTF_8), 262_144))
                .isInstanceOf(EnvelopeException.class).extracting("reason")
                .isEqualTo(EnvelopeException.Reason.ESQUEMA_INVALIDO);
        String conDuplicado = valido.substring(0, valido.lastIndexOf('}')) + ",\"version\":1}";
        assertThatThrownBy(() -> contexto.leer(conDuplicado.getBytes(StandardCharsets.UTF_8), 262_144))
                .isInstanceOf(EnvelopeException.class);
        assertThatThrownBy(() -> contexto.leer("{\"type\":\"ConsultaPedidos360\"}".getBytes(StandardCharsets.UTF_8),
                262_144)).isInstanceOf(EnvelopeException.class).extracting("reason")
                .isEqualTo(EnvelopeException.Reason.ESQUEMA_INVALIDO);
        assertThatThrownBy(() -> contexto.leer(valido.replace("7cbd68bf-bcf7-49dd-b1e2-8e15a7a0a681",
                "7CBD68BF-BCF7-49DD-B1E2-8E15A7A0A681").getBytes(StandardCharsets.UTF_8), 262_144))
                .isInstanceOf(EnvelopeException.class).extracting("reason")
                .isEqualTo(EnvelopeException.Reason.IDENTIDAD_INVALIDA);
    }

    @Test
    void envelopeRechazaInstanteSinUtcJsonInvalidoYExcesoDeTamano() {
        String valido = valido("usuario.consultar-actual.v1", "p360.usuarios.consultas.q");
        assertThatThrownBy(() -> contexto.leer(
                valido.replace("2026-10-07T01:30:00Z", "2026-10-07T01:30:00").getBytes(StandardCharsets.UTF_8),
                262_144)).isInstanceOf(EnvelopeException.class).extracting("reason")
                .isEqualTo(EnvelopeException.Reason.TIEMPO_INVALIDO);
        assertThatThrownBy(() -> contexto.leer("no-json".getBytes(StandardCharsets.UTF_8), 262_144))
                .isInstanceOf(EnvelopeException.class).extracting("reason")
                .isEqualTo(EnvelopeException.Reason.JSON_INVALIDO);
        assertThatThrownBy(() -> contexto.leer(valido.getBytes(StandardCharsets.UTF_8), 16))
                .isInstanceOf(EnvelopeException.class).extracting("reason")
                .isEqualTo(EnvelopeException.Reason.EXCEDE_TAMANO);
        assertThatThrownBy(() -> contexto.leer(new byte[0], 262_144)).isInstanceOf(EnvelopeException.class)
                .extracting("reason").isEqualTo(EnvelopeException.Reason.VACIO);
    }

    @Test
    void payloadDebeSerObjetoYElArregloSeRechaza() {
        assertThatThrownBy(() -> RequestEnvelope.crear(UUID.randomUUID(), "pago.consultar.v1",
                json.createArrayNode(), "sobre", AHORA, AHORA.plusSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("payload");
        assertThat(RequestEnvelope.crear(UUID.randomUUID(), "pago.consultar.v1", json.createObjectNode(), "sobre",
                AHORA, AHORA.plusSeconds(5)).payload().isObject()).isTrue();
    }

    @Test
    void messageIdEsEstableYUnRetryNoRenuevaElPlazo() {
        RequestEnvelope original = envelope("pago.consultar.v1", AHORA.plusSeconds(5),
                firmante().emitir(actor("p360.pagos.consultas.q", AHORA.plusSeconds(4)), AHORA.plusSeconds(5)));
        RequestEnvelope reenviado = contexto.leer(contexto.escribir(original), 262_144);
        assertThat(reenviado.messageId()).isEqualTo(original.messageId());
        assertThat(reenviado.expiresAt()).isEqualTo(original.expiresAt());
        assertThat(reenviado.vencido(AHORA.plusSeconds(5))).isTrue();
        assertThat(reenviado.vencido(AHORA.plusSeconds(4))).isFalse();
    }

    @Test
    void respuestaExitosaYDeErrorViajanConCorrelacionYReferenciaAlRequest() {
        RequestEnvelope request = envelope("usuario.consultar-actual.v1", AHORA.plusSeconds(5),
                firmante().emitir(actor("p360.usuarios.consultas.q", AHORA.plusSeconds(4)), AHORA.plusSeconds(5)));
        QueryResponse exito = QueryResponse.exito(request, "corr-1",
                json.createObjectNode().put("id", 5).put("nombre", "Ana"), AHORA);
        QueryResponse leida = esquema.leer(contexto.escribirRespuesta(exito), 262_144).orElseThrow();
        assertThat(leida.success()).isTrue();
        assertThat(leida.status()).isEqualTo(200);
        assertThat(leida.correlationId()).isEqualTo("corr-1");
        assertThat(leida.messageId()).isEqualTo(request.messageId());
        assertThat(leida.operacion()).isEqualTo("usuario.consultar-actual.v1");

        QueryResponse error = QueryResponse.error(request, "corr-2",
                new QueryResponse.ErrorDetail("ACCESO_DENEGADO", "Acceso denegado", "No pertenece.", 403), AHORA);
        QueryResponse leidaError = esquema.leer(contexto.escribirRespuesta(error), 262_144).orElseThrow();
        assertThat(leidaError.success()).isFalse();
        assertThat(leidaError.status()).isEqualTo(403);
        assertThat(leidaError.error().code()).isEqualTo("ACCESO_DENEGADO");
        assertThat(leidaError.payload()).isNull();
    }

    @Test
    void respuestaFueraDeContratoSeDescarta() {
        assertThat(esquema.leer("{\"status\":200}".getBytes(StandardCharsets.UTF_8), 1024)).isEmpty();
        assertThat(esquema.leer(new byte[0], 1024)).isEmpty();
        assertThat(esquema.leer("{\"a\":1}".getBytes(StandardCharsets.UTF_8), 1024)).isEmpty();
        assertThat(esquema.leer("no-json".getBytes(StandardCharsets.UTF_8), 1024)).isEmpty();
        // Un campo omitido no es un mensaje completo: el contrato exige los ocho.
        String sinError = "{\"operacion\":\"pago.consultar.v1\",\"correlationId\":\"c\",\"messageId\":\""
                + UUID.randomUUID() + "\",\"success\":true,\"status\":200,\"payload\":{},\"timestamp\":\""
                + AHORA + "\"}";
        assertThat(esquema.leer(sinError.getBytes(StandardCharsets.UTF_8), 1024)).isEmpty();
        String conExtra = "{\"operacion\":\"pago.consultar.v1\",\"correlationId\":\"c\",\"messageId\":\""
                + UUID.randomUUID() + "\",\"success\":true,\"status\":200,\"payload\":{},\"error\":null,"
                + "\"timestamp\":\"" + AHORA + "\",\"extra\":1}";
        assertThat(esquema.leer(conExtra.getBytes(StandardCharsets.UTF_8), 1024)).isEmpty();
    }

    @Test
    void topologiaDelDominioRespetaElInventarioAprobado() {
        QueryTopology usuarios = QueryTopology.of(propiedades(), Domain.USUARIOS);
        assertThat(usuarios.queue()).isEqualTo("p360.usuarios.consultas.q");
        assertThat(usuarios.retryQueue()).isEqualTo("p360.usuarios.consultas.retry.1s.q");
        assertThat(usuarios.dlq()).isEqualTo("p360.usuarios.consultas.dlq");
        assertThat(usuarios.routingKey()).isEqualTo("usuario.consultar-actual.v1");
        assertThat(usuarios.retryRoutingKey()).isEqualTo("usuario.consultar-actual.retry.1s");
        assertThat(usuarios.failedRoutingKey()).isEqualTo("usuario.consultar-actual.failed");
        assertThat(QueryTopology.of(propiedades(), Domain.PAGOS).queue()).isEqualTo("p360.pagos.consultas.q");
        assertThat(QueryTopology.of(propiedades(), Domain.RESTAURANTES).queue())
                .isEqualTo("p360.restaurantes.consultas.q");
        assertThat(QueryTopology.of(propiedades(), Domain.PRODUCTOS).queue()).isEqualTo("p360.productos.consultas.q");
        assertThat(QueryTopology.of(propiedades(), Domain.PRODUCTOS).mapNotFound()).isFalse();
        assertThat(QueryTopology.of(propiedades(), Domain.PAGOS).mapNotFound()).isTrue();
    }

    @Test
    void colaFuncionalDeclaraRetryCortoConTtlAprobadoYRetornoALaMismaCola() {
        QueryTopology productos = QueryTopology.of(propiedades(), Domain.PRODUCTOS);
        Queue funcional = productos.functional();
        assertThat(funcional.isDurable()).isTrue();
        assertThat(funcional.getArguments()).containsEntry("x-dead-letter-exchange", "p360.retry")
                .containsEntry("x-dead-letter-routing-key", "producto.listar-disponibles.retry.1s");
        Queue retry = productos.retry();
        assertThat(retry.isDurable()).isTrue();
        assertThat(retry.getArguments()).containsEntry("x-message-ttl", 1000)
                .containsEntry("x-dead-letter-exchange", "p360.queries")
                .containsEntry("x-dead-letter-routing-key", "producto.listar-disponibles.v1");
        assertThat(productos.declarations().getDeclarables())
                .as("3 exchanges, 3 colas y 3 bindings").hasSize(9);
        assertThat(productos.bindings()).containsKeys("p360.productos.consultas.q",
                "p360.productos.consultas.retry.1s.q", "p360.productos.consultas.dlq");
    }

    @Test
    void sobreDeActorSeFirmaYVerificaConDestinoEmisorYPlazo() {
        ActorContextSigner firma = firmante();
        String sobre = firma.emitir(actor("p360.pagos.consultas.q", AHORA.plusSeconds(4)), AHORA.plusSeconds(5));
        ActorContext verificado = firma.verificar(sobre, TENANT, List.of("p360.pagos.consultas.q"),
                AHORA.plusSeconds(5));
        assertThat(verificado.sujetoId()).isEqualTo(UUID.fromString("12345678-1234-1234-1234-123456789012"));
        assertThat(verificado.roles()).containsExactly("CLIENTE");
        assertThat(verificado.scopes()).containsExactly("access_as_user");
        assertThat(verificado.audiencia()).isEqualTo("p360.pagos.consultas.q");
        assertThat(verificado.keyId()).isEqualTo(CLAVE);
        assertThat(sobre.split("\\.")).hasSize(3);
    }

    @Test
    void sobreConDestinoAjenoEmisorAjenoOFirmaManipuladaSeRechaza() {
        ActorContextSigner firma = firmante();
        String sobre = firma.emitir(actor("p360.pagos.consultas.q", AHORA.plusSeconds(4)), AHORA.plusSeconds(5));
        assertThatThrownBy(() -> firma.verificar(sobre, TENANT, List.of("p360.usuarios.consultas.q"),
                AHORA.plusSeconds(5))).isInstanceOf(ActorContextException.class).extracting("reason")
                .isEqualTo(ActorContextException.Reason.DESTINO_INVALIDO);
        assertThatThrownBy(() -> firma.verificar(sobre, "99999999-bbbb-cccc-dddd-eeeeeeeeeeee",
                List.of("p360.pagos.consultas.q"), AHORA.plusSeconds(5)))
                .isInstanceOf(ActorContextException.class).extracting("reason")
                .isEqualTo(ActorContextException.Reason.EMISOR_INVALIDO);
        String manipulado = sobre.substring(0, sobre.length() - 2) + "aa";
        assertThatThrownBy(() -> firma.verificar(manipulado, TENANT, List.of("p360.pagos.consultas.q"),
                AHORA.plusSeconds(5))).isInstanceOf(ActorContextException.class).extracting("reason")
                .isEqualTo(ActorContextException.Reason.FIRMA_INVALIDA);
        assertThatThrownBy(() -> firma.verificar(null, TENANT, List.of("p360.pagos.consultas.q"), AHORA))
                .isInstanceOf(ActorContextException.class).extracting("reason")
                .isEqualTo(ActorContextException.Reason.SOBRE_AUSENTE);
    }

    @Test
    void sobreVencidoOConVigenciaPosteriorAlRequestSeRechaza() {
        // Emitido cuando el actor estaba vigente; verificado despues de su vencimiento (caso 7: el
        // plazo no se renueva y el sobre no sobrevive al request).
        ActorContextSigner emisor = new ActorContextSigner(claves(), RELOJ, Duration.ofSeconds(5));
        String vencido = emisor.emitir(actor("p360.usuarios.consultas.q", AHORA.plusSeconds(2)),
                AHORA.plusSeconds(5));
        ActorContextSigner posterior = new ActorContextSigner(claves(), Clock.fixed(AHORA.plusSeconds(3),
                ZoneOffset.UTC), Duration.ofSeconds(5));
        assertThatThrownBy(() -> posterior.verificar(vencido, TENANT, List.of("p360.usuarios.consultas.q"),
                AHORA.plusSeconds(5))).isInstanceOf(ActorContextException.class).extracting("reason")
                .isEqualTo(ActorContextException.Reason.PLAZO_VENCIDO);
        String vigente = emisor.emitir(actor("p360.usuarios.consultas.q", AHORA.plusSeconds(4)), AHORA.plusSeconds(5));
        assertThatThrownBy(() -> emisor.verificar(vigente, TENANT, List.of("p360.usuarios.consultas.q"),
                AHORA.plusSeconds(2))).isInstanceOf(ActorContextException.class).extracting("reason")
                .isEqualTo(ActorContextException.Reason.PLAZO_INCOHERENTE);
    }

    @Test
    void emisorSinClaveNoFirmaNiVerifica() {
        var sinClave = new ActorContextSigner(new SigningKeyProvider() {
            @Override
            public Optional<SigningKey> claveParaFirmar() {
                return Optional.empty();
            }

            @Override
            public Optional<SigningKey> clavePorId(UUID keyId) {
                return Optional.empty();
            }
        }, RELOJ, Duration.ofSeconds(5));
        assertThatThrownBy(() -> sinClave.emitir(actor("p360.pagos.consultas.q", AHORA.plusSeconds(4)),
                AHORA.plusSeconds(5))).isInstanceOf(ActorContextException.class).extracting("reason")
                .isEqualTo(ActorContextException.Reason.CLAVE_DESCONOCIDA);
        assertThat(sinClave.claveVigente()).isEmpty();
    }

    @Test
    void vigenciaDelActorNoPuedeSuperarElPlazoDelRequest() {
        assertThatThrownBy(() -> firmante().emitir(actor("p360.pagos.consultas.q", AHORA.plusSeconds(9)),
                AHORA.plusSeconds(5))).isInstanceOf(ActorContextException.class).extracting("reason")
                .isEqualTo(ActorContextException.Reason.PLAZO_INCOHERENTE);
    }

    @Test
    void rolesConCaracteresFueraDeContratoSeRechazanAunqueLaFirmaSeaValida() {
        String payload = sobreManipuladoConRoles(List.of("CLIENTE'; DROP TABLE"));
        assertThatThrownBy(() -> firmante().verificar("p360act1." + payload + "." + firmaDe(payload), TENANT,
                List.of("p360.pagos.consultas.q"), AHORA.plusSeconds(5)))
                .isInstanceOf(ActorContextException.class).extracting("reason")
                .isEqualTo(ActorContextException.Reason.FORMATO_INVALIDO);
    }

    @Test
    void configuracionIncompletaNoSeConsideraValida() {
        MessagingProperties incompleta = new MessagingProperties(RelayMode.DISABLED, RelayMode.Role.SERVICE,
                new MessagingProperties.Exchanges("", "p360.retry", "p360.dlx"),
                new MessagingProperties.Queues("p360.bff.consultas.respuestas.q"),
                new MessagingProperties.Naming("p360.", ".consultas.q", ".consultas.retry.1s.q", ".consultas.dlq",
                        ".retry.1s", ".failed"),
                new MessagingProperties.DomainRouting("usuario.consultar-actual.v1", "restaurante.listar.v1",
                        "producto.listar-disponibles.v1", "pago.consultar.v1", "usuario.consultar-actual",
                        "restaurante.listar", "producto.listar-disponibles", "pago.consultar"),
                Duration.ofSeconds(5), Duration.ofSeconds(4), Duration.ofSeconds(1), Duration.ofSeconds(3),
                Duration.ofMillis(500), 2, 1024, 262_144);
        assertThat(incompleta.isNombresValidos()).isFalse();
        assertThat(propiedades().isNombresValidos()).isTrue();
        assertThat(propiedades().isPlantillasValidas()).isTrue();
        assertThat(propiedades().isTiemposValidos()).isTrue();
        assertThat(propiedades().isLimitesValidos()).isTrue();
    }

    @Test
    void politicaAprobadaEsUnSoloRetryCortoConPlazoDeCincoSegundos() {
        assertThat(propiedades().retryDelay()).isEqualTo(Duration.ofSeconds(1));
        assertThat(propiedades().deadline()).isEqualTo(Duration.ofSeconds(5));
        assertThat(propiedades().actorTtl()).isLessThan(propiedades().deadline());
        assertThat(propiedades().maxPendingCorrelations()).isPositive();
    }

    private String sobreManipuladoConRoles(List<String> roles) {
        var claims = new LinkedHashMap<String, Object>();
        claims.put("v", "p360act1");
        claims.put("tenantId", TENANT);
        claims.put("sujetoId", "12345678-1234-1234-1234-123456789012");
        claims.put("roles", roles);
        claims.put("scopes", List.of("access_as_user"));
        claims.put("emitidoEn", AHORA.toString());
        claims.put("expiraEn", AHORA.plusSeconds(4).toString());
        claims.put("audiencia", "p360.pagos.consultas.q");
        claims.put("keyId", CLAVE.toString());
        return java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.writeValueAsBytes(claims));
    }

    private String firmaDe(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(MATERIAL, "HmacSHA256"));
            return java.util.Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(("p360act1." + payload).getBytes(StandardCharsets.US_ASCII)));
        } catch (java.security.GeneralSecurityException imposible) {
            throw new IllegalStateException(imposible);
        }
    }
}
