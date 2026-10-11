package cl.duoc.pedidos360.restaurantes.messaging;

import cl.duoc.pedidos360.messaging.actor.ActorContext;
import cl.duoc.pedidos360.messaging.envelope.EnvelopeException;
import cl.duoc.pedidos360.messaging.envelope.RequestEnvelope;
import cl.duoc.pedidos360.messaging.relay.QueryProcessor;
import cl.duoc.pedidos360.restaurantes.service.RestauranteService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Consulta del catalogo existente; incluye INACTIVOS y conserva el DTO y orden de listar(). */
public final class RestaurantesQueryProcessor implements QueryProcessor {
    private final RestauranteService restaurantes;
    private final JsonMapper json;

    public RestaurantesQueryProcessor(RestauranteService restaurantes, JsonMapper json) {
        this.restaurantes = restaurantes;
        this.json = json;
    }

    @Override
    public JsonNode procesar(ActorContext actor, RequestEnvelope request) {
        if (!request.payload().isObject() || !request.payload().isEmpty()) {
            throw new EnvelopeException(EnvelopeException.Reason.ESQUEMA_INVALIDO,
                    "payload de listar restaurantes exige un objeto vacio");
        }
        return json.valueToTree(restaurantes.listar());
    }
}
