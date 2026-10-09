package cl.duoc.pedidos360.messaging.relay;

import tools.jackson.databind.JsonNode;

import cl.duoc.pedidos360.messaging.actor.ActorContext;
import cl.duoc.pedidos360.messaging.envelope.RequestEnvelope;

/**
 * Procesador de una operacion de consulta concreta.
 *
 * <p>Es el unico punto que implementan #78 a #81. El procesador ejecuta la operacion de dominio con
 * el actor ya verificado, y decide:
 *
 * <ul>
 *   <li>devolver el {@code payload} equivalente a la respuesta HTTP 200;</li>
 *   <li>lanzar {@code QueryBusinessException} para un error esperado (403/404/409), que viaja como
 *       respuesta correlacionada y se confirma;</li>
 *   <li>lanzar {@code QueryTemporaryException} para un fallo transitorio, que habilita el unico
 *       retry corto;</li>
 *   <li>lanzar cualquier otra excepcion para un fallo no clasificado, que se reintenta una vez y
 *       despues va a DLQ.</li>
 * </ul>
 *
 * <p>El procesador no publica la respuesta ni confirma el mensaje: eso lo hace el consumidor base
 * despues de que la respuesta quede confirmada. Tampoco consulta identidad HTTP: recibe el actor.
 */
@FunctionalInterface
public interface QueryProcessor {

    JsonNode procesar(ActorContext actor, RequestEnvelope request);

    /** Compatible entry point: existing processors retain their original behavior. */
    default JsonNode procesar(ActorContext actor, RequestEnvelope request, QueryDeadlineGuard guard) {
        return procesar(actor, request);
    }
}
