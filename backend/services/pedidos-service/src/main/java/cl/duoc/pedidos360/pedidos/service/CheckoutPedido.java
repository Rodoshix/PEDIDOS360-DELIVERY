package cl.duoc.pedidos360.pedidos.service;

import cl.duoc.pedidos360.messaging.command.CarritoCommandProperties;
import cl.duoc.pedidos360.pedidos.dto.*;
import cl.duoc.pedidos360.pedidos.security.IdentidadUsuario;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

/** Remote snapshot before the local write transaction. HTTP remains the default. */
@Service
@EnableConfigurationProperties(CarritoCommandProperties.class)
public class CheckoutPedido {
  private final PedidoService pedidos;
  private final CarritoSnapshotClient snapshots;
  private final CarritoCommandProperties config;

  public CheckoutPedido(PedidoService p, CarritoSnapshotClient s, CarritoCommandProperties c) {
    pedidos = p;
    snapshots = s;
    config = c;
  }

  public PedidoResponse crear(IdentidadUsuario identity, CrearPedidoRequest request) {
    if (config.mode() == CarritoCommandProperties.Mode.HTTP)
      return pedidos.crear(identity, request);
    return pedidos.crearConCarrito(identity, request, snapshots.obtener(identity, request));
  }
}
