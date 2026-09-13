package cl.duoc.pedidos360.pagos.client;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("pagos.pedidos")
public record PedidosClientProperties(String baseUrl, boolean internoHabilitado) {
}
