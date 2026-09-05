package cl.duoc.pedidos360.seguimiento.service;

import java.util.List;

import cl.duoc.pedidos360.seguimiento.dto.CambioEstadoRequest;
import cl.duoc.pedidos360.seguimiento.dto.CrearSeguimientoRequest;
import cl.duoc.pedidos360.seguimiento.dto.PaginaSeguimientos;
import cl.duoc.pedidos360.seguimiento.dto.SeguimientoEventoResponse;
import cl.duoc.pedidos360.seguimiento.dto.SeguimientoHistorialResponse;
import cl.duoc.pedidos360.seguimiento.dto.SeguimientoResponse;
import cl.duoc.pedidos360.seguimiento.entity.Seguimiento;
import cl.duoc.pedidos360.seguimiento.entity.SeguimientoEvento;
import cl.duoc.pedidos360.seguimiento.exception.ApiException;
import cl.duoc.pedidos360.seguimiento.repository.SeguimientoEventoRepository;
import cl.duoc.pedidos360.seguimiento.repository.SeguimientoRepository;
import cl.duoc.pedidos360.seguimiento.security.IdentidadActual;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class SeguimientoService {
    private final SeguimientoRepository seguimientos;
    private final SeguimientoEventoRepository eventos;
    private final IdentidadActual identidadActual;

    public SeguimientoService(SeguimientoRepository seguimientos,
                              SeguimientoEventoRepository eventos,
                              IdentidadActual identidadActual) {
        this.seguimientos = seguimientos;
        this.eventos = eventos;
        this.identidadActual = identidadActual;
    }

    public SeguimientoResponse obtener(Long pedidoId) {
        return SeguimientoResponse.desde(buscarPorPedido(pedidoId));
    }

    public SeguimientoHistorialResponse obtenerHistorial(Long pedidoId) {
        var seguimiento = buscarPorPedido(pedidoId);
        List<SeguimientoEventoResponse> historial = eventos
                .findBySeguimientoIdOrderByOcurridoEnAsc(seguimiento.getId())
                .stream().map(SeguimientoEventoResponse::desde).toList();
        return new SeguimientoHistorialResponse(
                seguimiento.getPedidoId(), seguimiento.getEstadoActual(), historial);
    }

    public PaginaSeguimientos listar(int pagina, int tamanio) {
        var actor = identidadActual.obtener();
        if (!actor.esAdmin()) {
            throw new ApiException(HttpStatus.FORBIDDEN,
                    "Solo ADMIN puede listar los seguimientos.");
        }
        var resultados = seguimientos.findAll(
                PageRequest.of(pagina, tamanio, Sort.by("id")));
        return new PaginaSeguimientos(
                resultados.map(SeguimientoResponse::desde).getContent(),
                pagina, tamanio, resultados.getTotalElements(), resultados.getTotalPages());
    }

    @Transactional
    public SeguimientoResponse crear(CrearSeguimientoRequest request) {
        if (seguimientos.existsByPedidoId(request.pedidoId())) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "El pedido ya tiene un seguimiento iniciado.");
        }
        var seguimiento = seguimientos.saveAndFlush(new Seguimiento(
                request.pedidoId(), request.estadoInicial()));
        eventos.saveAndFlush(new SeguimientoEvento(
                seguimiento.getId(), request.estadoInicial(), "Seguimiento iniciado."));
        return SeguimientoResponse.desde(seguimiento);
    }

    @Transactional
    public SeguimientoResponse cambiarEstado(Long pedidoId, CambioEstadoRequest request) {
        var seguimiento = buscarPorPedido(pedidoId);
        if (seguimiento.getEstadoActual() == request.estado()) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "El pedido ya se encuentra en el estado " + request.estado() + ".");
        }
        seguimiento.cambiarEstado(request.estado());
        seguimientos.flush();
        eventos.saveAndFlush(new SeguimientoEvento(
                seguimiento.getId(), request.estado(), request.nota()));
        return SeguimientoResponse.desde(seguimiento);
    }

    private Seguimiento buscarPorPedido(Long pedidoId) {
        return seguimientos.findByPedidoId(pedidoId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "No se encontró el seguimiento del pedido " + pedidoId + "."));
    }
}
