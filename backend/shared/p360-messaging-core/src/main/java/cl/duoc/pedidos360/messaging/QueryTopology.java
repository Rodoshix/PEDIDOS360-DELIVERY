package cl.duoc.pedidos360.messaging;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;

/**
 * Topologia declarativa de un dominio de consulta.
 *
 * <p>Forma aprobada:
 *
 * <pre>
 * p360.queries  --routing key del dominio-->   cola funcional        (ACK manual desde el consumer)
 * cola funcional --x-dead-letter-exchange-->   p360.retry            (retry corto, un intento)
 * retry         --x-dead-letter-exchange-->    p360.queries          (vuelve a la cola funcional)
 * cola funcional --x-dead-letter-exchange-->   p360.dlx              (fallo definitivo)
 * </pre>
 *
 * <p>Este tipo no declara nada por si mismo: {@link #declarations()} entrega los objetos para que el
 * servicio los registre como beans. La decision de declarar la topologia completa corresponde a #69,
 * coordinada.
 */
public final class QueryTopology {

    /** Cola funcional, retry corto y DLQ de un dominio, con sus routing keys ya compuestas. */
    public record Endpoint(String queue, String retryQueue, String dlq, String routingKey, String retryRoutingKey,
            String failedRoutingKey, boolean mapNotFound) {}

    private final MessagingProperties properties;
    private final Domain domain;
    private final Endpoint endpoint;

    private QueryTopology(MessagingProperties properties, Domain domain, Endpoint endpoint) {
        this.properties = properties;
        this.domain = domain;
        this.endpoint = endpoint;
    }

    public static QueryTopology of(MessagingProperties properties, Domain domain) {
        Objects.requireNonNull(properties, "propiedades requeridas");
        Objects.requireNonNull(domain, "dominio requerido");
        String base = domain.propiedad();
        String routingKey = properties.routing().operacion(domain);
        String routingBase = properties.routing().base(domain);
        return new QueryTopology(properties, domain, new Endpoint(
                properties.naming().prefix() + base + properties.naming().querySuffix(),
                properties.naming().prefix() + base + properties.naming().retrySuffix(),
                properties.naming().prefix() + base + properties.naming().dlqSuffix(),
                routingKey, routingBase + properties.naming().retryKeySuffix(),
                routingBase + properties.naming().failedKeySuffix(), mapNotFound(domain)));
    }

    /** Dominio atendido por esta topologia. */
    public Domain domain() {
        return domain;
    }

    /** Nombres y bindings resueltos del dominio. */
    public Endpoint endpoint() {
        return endpoint;
    }

    public String queue() {
        return endpoint.queue();
    }

    public String retryQueue() {
        return endpoint.retryQueue();
    }

    public String dlq() {
        return endpoint.dlq();
    }

    public String routingKey() {
        return endpoint.routingKey();
    }

    public String retryRoutingKey() {
        return endpoint.retryRoutingKey();
    }

    public String failedRoutingKey() {
        return endpoint.failedRoutingKey();
    }

    /** Un 404 de dominio se responde correlacionado cuando la operacion describe un recurso concreto. */
    public boolean mapNotFound() {
        return endpoint.mapNotFound();
    }

    /** Cola funcional: durable, con DLX hacia el retry corto de su dominio. */
    public Queue functional() {
        return QueueBuilder.durable(queue())
                .deadLetterExchange(properties.exchanges().retry())
                .deadLetterRoutingKey(retryRoutingKey())
                .build();
    }

    /**
     * Cola de retry corto: durable, sin consumer, con TTL aprobado y retorno a la cola funcional.
     *
     * <p>Su unica funcion es devolver el mensaje tras el TTL; no reintenta en bucle ni renueva plazo.
     */
    public Queue retry() {
        return QueueBuilder.durable(retryQueue())
                .ttl((int) properties.retryDelay().toMillis())
                .deadLetterExchange(properties.exchanges().queries())
                .deadLetterRoutingKey(routingKey())
                .build();
    }

    /** DLQ del dominio: durable, sin consumer y sin replay automatico. */
    public Queue deadLetter() {
        return QueueBuilder.durable(dlq()).build();
    }

    /** Exchanges personalizados, durables y sin auto-delete. */
    public List<DirectExchange> exchanges() {
        return List.of(new DirectExchange(properties.exchanges().queries(), true, false),
                new DirectExchange(properties.exchanges().retry(), true, false),
                new DirectExchange(properties.exchanges().dlx(), true, false));
    }

    /** Declaraciones completas del dominio, listas para registrar como beans. */
    public Declarables declarations() {
        var declarables = new java.util.ArrayList<Declarable>();
        var exchanges = exchanges();
        var queries = exchanges.get(0);
        var retry = exchanges.get(1);
        var dlx = exchanges.get(2);
        var functional = functional();
        var retryQueue = retry();
        var dlq = deadLetter();
        // Los tres exchanges personalizados se declaran junto con las colas y los bindings: sin ellos
        // no existe destino para la publicacion ni retorno posible del retry.
        declarables.addAll(exchanges);
        declarables.add(functional);
        declarables.add(retryQueue);
        declarables.add(dlq);
        declarables.add(BindingBuilder.bind(functional).to(queries).with(routingKey()));
        declarables.add(BindingBuilder.bind(retryQueue).to(retry).with(retryRoutingKey()));
        declarables.add(BindingBuilder.bind(dlq).to(dlx).with(failedRoutingKey()));
        return new Declarables(declarables);
    }

    /** Bindings del dominio indexados por nombre, utiles para evidencia y pruebas. */
    public Map<String, String> bindings() {
        var result = new LinkedHashMap<String, String>();
        result.put(queue(), properties.exchanges().queries() + " -> " + routingKey());
        result.put(retryQueue(), properties.exchanges().retry() + " -> " + retryRoutingKey());
        result.put(dlq(), properties.exchanges().dlx() + " -> " + failedRoutingKey());
        return result;
    }

    private static boolean mapNotFound(Domain domain) {
        return switch (domain) {
            case USUARIOS, PAGOS -> true;
            case RESTAURANTES, PRODUCTOS -> false;
        };
    }
}
