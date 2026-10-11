package cl.duoc.pedidos360.bff.messaging;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import cl.duoc.pedidos360.messaging.MessagingProperties;
import cl.duoc.pedidos360.messaging.QueryMessagingConfiguration;
import cl.duoc.pedidos360.messaging.actor.ActorContextSigner;
import cl.duoc.pedidos360.messaging.envelope.RequestEnvelopeContext;
import cl.duoc.pedidos360.messaging.envelope.ResponseSchema;
import cl.duoc.pedidos360.messaging.relay.PendingCorrelationRegistry;
import cl.duoc.pedidos360.messaging.relay.RequestPublisher;

/**
 * Adaptador de consultas del BFF.
 *
 * <p>Se activa solo con {@code pedidos360.messaging.relay-mode=ACTIVE}. HTTP sigue siendo el
 * transporte predeterminado: con el modo deshabilitado no se crea ninguna correlacion, ningun
 * listener de respuestas y ningun cambio de rutas.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "pedidos360.messaging", name = "relay-mode", havingValue = "ACTIVE")
@EnableConfigurationProperties(BffActorProperties.class)
@org.springframework.context.annotation.Import(QueryMessagingConfiguration.class)
public class BffQueryConfiguration {

    @Bean
    @ConditionalOnMissingBean
    PendingCorrelationRegistry pendingCorrelationRegistry(MessagingProperties properties) {
        return new PendingCorrelationRegistry(properties.maxPendingCorrelations());
    }

    @Bean
    @ConditionalOnMissingBean
    ResponseSchema responseSchema() {
        return new ResponseSchema();
    }

    @Bean
    @ConditionalOnMissingBean
    BffActorContextFactory bffActorContextFactory(ActorContextSigner firmante, BffActorProperties propiedades,
            Environment env) {
        if (env.getProperty(QueryMessagingConfiguration.EMISOR_PROPERTY) == null)
            throw new IllegalStateException("relay-mode ACTIVE exige el emisor del sobre en "
                    + QueryMessagingConfiguration.EMISOR_PROPERTY);
        return new BffActorContextFactory(firmante, propiedades);
    }

    @Bean
    @ConditionalOnMissingBean
    RequestFactory requestFactory(MessagingProperties properties, BffActorContextFactory actores) {
        return new RequestFactory(properties, actores);
    }

    @Bean
    @ConditionalOnMissingBean
    BffQueryAdapter bffQueryAdapter(RequestFactory fabrica, RequestPublisher publicador,
            PendingCorrelationRegistry correlaciones, RequestEnvelopeContext contexto, ResponseSchema esquema,
            MessagingProperties properties, ObjectProvider<QueryInvoker> operaciones, ObjectProvider<BffIdentityProofValidator> pruebas) {
        var adaptador = new BffQueryAdapter(fabrica, publicador, correlaciones, contexto, esquema, properties);
        adaptador.configurarPruebas(pruebas.getIfAvailable());
        operaciones.forEach(operacion -> adaptador.registrar(dominioDe(properties, operacion.operacion()), operacion));
        return adaptador;
    }

    @Bean
    @ConditionalOnProperty(prefix = "pedidos360.messaging.identity-proof", name = "enabled", havingValue = "true")
    BffIdentityProofValidator bffIdentityProofValidator(Environment env) {
        String prefix = "pedidos360.messaging.identity-proof.";
        if (!env.getProperty(prefix + "private-jwk", "").isBlank())
            throw new IllegalStateException("BFF no puede cargar privada de prueba de identidad");
        var keys = cl.duoc.pedidos360.messaging.identity.IdentityProofKeys.load(env.getProperty(prefix + "public-jwks"),
                env.getProperty("pedidos360.messaging.actor.public-jwks"));
        return new BffIdentityProofValidator(new cl.duoc.pedidos360.messaging.identity.IdentityProofVerifier(keys,
                java.time.Clock.systemUTC(), env.getProperty(prefix + "clock-margin", java.time.Duration.class, java.time.Duration.ofMillis(250))));
    }

    /** Resuelve el dominio cuya routing key configurada coincide con la operacion declarada. */
    private static cl.duoc.pedidos360.messaging.Domain dominioDe(MessagingProperties properties, String operacion) {
        for (var domain : cl.duoc.pedidos360.messaging.Domain.values()) {
            if (properties.routing().operacion(domain).equals(operacion)) return domain;
        }
        throw new IllegalStateException("la operacion declarada no corresponde a ningun dominio configurado: "
                + operacion);
    }
}
