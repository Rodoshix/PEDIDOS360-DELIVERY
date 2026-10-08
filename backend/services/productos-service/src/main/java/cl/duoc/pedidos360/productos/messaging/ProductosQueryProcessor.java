package cl.duoc.pedidos360.productos.messaging;

import cl.duoc.pedidos360.messaging.actor.ActorContext;
import cl.duoc.pedidos360.messaging.envelope.EnvelopeException;
import cl.duoc.pedidos360.messaging.envelope.RequestEnvelope;
import cl.duoc.pedidos360.messaging.relay.QueryProcessor;
import cl.duoc.pedidos360.productos.service.ProductoService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Adaptador de la consulta existente; el consumer compartido verifica actor y operacion. */
public final class ProductosQueryProcessor implements QueryProcessor {
    private final ProductoService productos;
    private final JsonMapper json;

    public ProductosQueryProcessor(ProductoService productos, JsonMapper json) {
        this.productos = productos;
        this.json = json;
    }

    @Override
    public JsonNode procesar(ActorContext actor, RequestEnvelope request) {
        JsonNode payload = request.payload();
        JsonNode id = payload.get("restauranteId");
        if (payload.size() != 1 || id == null || !id.isIntegralNumber()
                || !id.canConvertToLong() || id.longValue() <= 0) {
            throw new EnvelopeException(EnvelopeException.Reason.ESQUEMA_INVALIDO,
                    "payload exige solamente restauranteId entero positivo de 64 bits");
        }
        return json.valueToTree(productos.listarDisponiblesPorRestaurante(id.longValue()));
    }
}
