package cl.duoc.pedidos360.rabbitadmin;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.stereotype.Component;
import static cl.duoc.pedidos360.rabbitadmin.AdminDtos.*;

/** Broker-enforced ownership: no other connection can consume/basic.get.
 * The private owner channel never delivers messages. if-empty consequently checks
 * all messages, not just the ready subset of a queue with external deliveries.
 * Lifetime is deliberately ephemeral; owner failure removes these demo queues.
 */
@Component
public final class DisposableQueues {
    private record Owned(Connection connection, Channel channel) {}
    private final Map<String, Owned> owned = new HashMap<>();
    private final CachingConnectionFactory factory;
    public DisposableQueues(CachingConnectionFactory factory) { this.factory = factory; }

    public static boolean namespace(String name) { return name.startsWith("p360.demo.disposable."); }

    public synchronized QueueResult create(String name) {
        if (!namespace(name)) throw AdminFailure.validation();
        var existing = owned.get(name);
        if (existing != null) return read(name);
        if (owned.size() >= 16) throw AdminFailure.conflict();
        Connection connection = null;
        try {
            connection = factory.getRabbitConnectionFactory().newConnection("p360-admin-disposable");
            var channel = connection.createChannel();
            var declared = channel.queueDeclare(name, false, true, false, Map.of("x-queue-type", "classic"));
            owned.put(name, new Owned(connection, channel));
            return new QueueResult(declared.getQueue(), declared.getMessageCount(), declared.getConsumerCount());
        } catch (IOException | TimeoutException failure) {
            if (connection != null) try { connection.close(); } catch (IOException ignored) { }
            throw new AmqpException("Disposable queue operation unconfirmed", failure);
        }
    }

    public synchronized QueueResult read(String name) {
        var channel = require(name).channel();
        try {
            var declared = channel.queueDeclarePassive(name);
            return new QueueResult(declared.getQueue(), declared.getMessageCount(), declared.getConsumerCount());
        } catch (IOException failure) { throw new AmqpException("Disposable queue read failed", failure); }
    }

    public synchronized void bind(BindingRequest request, boolean remove) {
        try {
            var channel = require(request.queue()).channel();
            if (remove) channel.queueUnbind(request.queue(), request.exchange(), request.routingKey());
            else channel.queueBind(request.queue(), request.exchange(), request.routingKey());
        } catch (IOException failure) { throw new AmqpException("Disposable binding unconfirmed", failure); }
    }

    public synchronized void delete(String name) {
        var resource = require(name);
        try {
            // Redeclaration also rejects a replaced durable/non-exclusive queue.
            resource.channel().queueDeclare(name, false, true, false, Map.of("x-queue-type", "classic"));
            resource.channel().queueDelete(name, true, true);
            owned.remove(name); // Only after broker reply. No metrics-based authorization.
            try { resource.connection().close(); } catch (IOException ignored) { }
        } catch (IOException failure) {
            // A PRECONDITION_FAILED closes the channel, not the exclusive owner connection.
            // Retain that connection and its messages; use a fresh control channel.
            if (resource.connection().isOpen() && !resource.channel().isOpen()) {
                try { owned.put(name, new Owned(resource.connection(), resource.connection().createChannel())); }
                catch (IOException ignored) { }
            }
            throw new AmqpException("Disposable delete unconfirmed", failure);
        }
    }

    private Owned require(String name) {
        var resource = owned.get(name);
        if (resource == null || !resource.connection().isOpen() || !resource.channel().isOpen())
            throw AdminFailure.conflict();
        return resource;
    }

    @PreDestroy public synchronized void shutdown() {
        // Explicitly ephemeral resources; never touch durable queues or shared connections.
        for (var resource : owned.values()) try { resource.connection().close(); } catch (IOException ignored) { }
        owned.clear();
    }
}
