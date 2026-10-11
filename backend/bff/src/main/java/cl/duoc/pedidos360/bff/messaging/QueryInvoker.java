package cl.duoc.pedidos360.bff.messaging;

import cl.duoc.pedidos360.messaging.envelope.OperationResult;
import tools.jackson.databind.JsonNode;

/**
 * Operacion de dominio que el BFF puede atender por request/reply.
 *
 * <p>Es el punto de extension del corte de #70: HTTP sigue siendo el transporte predeterminado y
 * cada issue de consulta (#78 a #81) aporta su operacion una vez aprobado su corte.
 *
 * <p>La operacion:
 * <ul>
 *   <li>declara la routing key que atiende (identidad logica, no el nombre de la cola);</li>
 *   <li>reaplica autorizacion de pertenencia con la identidad verificada del sobre;</li>
 *   <li>no usa el {@code SecurityContext} HTTP, que no existe en la ruta Messaging;</li>
 *   <li>lanza {@code QueryBusinessException} para un error esperado (403/404/409);</li>
 *   <li>lanza {@code QueryTemporaryException} para un fallo transitorio.</li>
 * </ul>
 */
public interface QueryInvoker {

    /** Routing key atendida; debe coincidir con la operacion configurada del dominio. */
    String operacion();

    /** Ejecuta la operacion con el envelope ya verificado del lado del servicio. */
    OperationResult invocar(JsonNode payload, String correlationId);
}
