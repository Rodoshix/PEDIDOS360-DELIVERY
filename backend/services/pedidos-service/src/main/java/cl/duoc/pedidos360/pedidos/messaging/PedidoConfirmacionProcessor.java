package cl.duoc.pedidos360.pedidos.messaging;

import java.time.Instant;
import java.util.UUID;
import org.springframework.amqp.core.Message;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;
import cl.duoc.pedidos360.pedidos.exception.*;
import cl.duoc.pedidos360.pedidos.service.PedidoService;
import static cl.duoc.pedidos360.pedidos.messaging.ConfirmacionDefinitivaException.Reason.*;

@Service
public class PedidoConfirmacionProcessor {
    private final JsonMapper json;
    private final PedidoService pedidos;
    private final cl.duoc.pedidos360.pedidos.security.TenantSistema tenant;
    public PedidoConfirmacionProcessor(JsonMapper json,PedidoService pedidos, cl.duoc.pedidos360.pedidos.security.TenantSistema tenant) {
        this.json=json.rebuild()
            .enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build(); this.pedidos=pedidos; this.tenant=tenant;
    }
    public UUID process(Message message) {
        ConfirmarPedidoPorPago command;
        try {
            var tree=json.readTree(message.getBody());
            if (!tree.isObject() || tree.size()!=6
                || !tree.path("messageId").isString() || !tree.path("type").isString()
                || !tree.path("occurredAt").isString()
                || !tree.path("version").isIntegralNumber() || !tree.path("version").canConvertToInt() || tree.path("version").intValue()!=1
                || !tree.path("pedidoId").isIntegralNumber() || !tree.path("pedidoId").canConvertToLong()
                || !tree.path("pagoId").isIntegralNumber() || !tree.path("pagoId").canConvertToLong()
                || tree.path("pedidoId").longValue()<1 || tree.path("pagoId").longValue()<1
                || !"ConfirmarPedidoPorPago".equals(tree.path("type").stringValue()))
                throw new IllegalArgumentException("Invalid V1 schema");
            String id=tree.path("messageId").stringValue();
            UUID uuid=UUID.fromString(id);
            if (!uuid.toString().equals(id) || !id.equals(message.getMessageProperties().getMessageId()))
                throw new IllegalArgumentException("Invalid message identity");
            String occurred=tree.path("occurredAt").stringValue();
            if (!occurred.endsWith("Z")) throw new IllegalArgumentException("UTC required");
            command=new ConfirmarPedidoPorPago(uuid,"ConfirmarPedidoPorPago",1,Instant.parse(occurred),
                tree.path("pedidoId").longValue(),tree.path("pagoId").longValue());
        } catch (RuntimeException invalid) { throw new ConfirmacionDefinitivaException(INVALID_MESSAGE,invalid); }
        try {
            // Proxied local service commits before returning; no remote lookup or transport here.
            pedidos.confirmarPorPago(tenant.obtener(), command.pedidoId());
        } catch (PedidoNoEncontradoException missing) {
            throw new ConfirmacionDefinitivaException(PEDIDO_INEXISTENTE,missing);
        } catch (PedidoHistoricoRetenidoException retained) {
            throw new ConfirmacionDefinitivaException(HISTORICO_RETENIDO,retained);
        } catch (PedidoException domain) {
            if (domain.getStatus()==HttpStatus.CONFLICT)
                throw new ConfirmacionDefinitivaException(PEDIDO_CANCELADO,domain);
            throw domain;
        }
        return command.messageId();
    }
}
