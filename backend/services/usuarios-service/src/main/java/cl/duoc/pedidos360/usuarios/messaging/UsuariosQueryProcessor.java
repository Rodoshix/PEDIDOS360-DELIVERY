package cl.duoc.pedidos360.usuarios.messaging;

import java.util.stream.Collectors;
import cl.duoc.pedidos360.messaging.actor.ActorContext;
import cl.duoc.pedidos360.messaging.envelope.EnvelopeException;
import cl.duoc.pedidos360.messaging.envelope.QueryBusinessException;
import cl.duoc.pedidos360.messaging.envelope.RequestEnvelope;
import cl.duoc.pedidos360.messaging.relay.QueryProcessor;
import cl.duoc.pedidos360.usuarios.exception.ApiException;
import cl.duoc.pedidos360.usuarios.security.IdentidadUsuario;
import cl.duoc.pedidos360.usuarios.service.UsuarioService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** El consumidor compartido verifica firma, tenant, destino y vigencia antes de invocar. */
public final class UsuariosQueryProcessor implements QueryProcessor {
    private final UsuarioService usuarios;
    private final JsonMapper json;
    private final UsuariosIdentityProofSigner pruebas;

    public UsuariosQueryProcessor(UsuarioService usuarios, JsonMapper json) {
        this(usuarios, json, null);
    }

    public UsuariosQueryProcessor(UsuarioService usuarios, JsonMapper json, UsuariosIdentityProofSigner pruebas) {
        this.usuarios = usuarios;
        this.json = json;
        this.pruebas = pruebas;
    }

    @Override
    public JsonNode procesar(ActorContext actor, RequestEnvelope request) {
        if (!request.payload().isObject() || !request.payload().isEmpty()) {
            throw new EnvelopeException(EnvelopeException.Reason.ESQUEMA_INVALIDO,
                    "payload exige un objeto vacio; la identidad procede del actor verificado");
        }
        var roles = actor.roles().stream()
                .filter(rol -> rol.equals("CLIENTE") || rol.equals("ADMIN"))
                .map(IdentidadUsuario.Rol::valueOf).collect(Collectors.toSet());
        var identidad = new IdentidadUsuario(actor.tenantId(), actor.sujetoId(), roles);
        try {
            if (pruebas != null) {
                var perfil = usuarios.resolverActual(identidad);
                return json.valueToTree(UsuarioQueryResponse.desde(perfil.perfil(), pruebas.emitir(actor, request, perfil)));
            }
            return json.valueToTree(usuarios.obtenerActual(identidad));
        } catch (ApiException fallo) {
            throw switch (fallo.getStatus()) {
                case FORBIDDEN -> QueryBusinessException.prohibido(fallo.getMessage());
                case NOT_FOUND -> QueryBusinessException.noEncontrado(fallo.getMessage());
                default -> fallo;
            };
        }
    }
}
