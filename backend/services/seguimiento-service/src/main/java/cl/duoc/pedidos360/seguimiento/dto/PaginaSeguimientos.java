package cl.duoc.pedidos360.seguimiento.dto;

import java.util.List;

public record PaginaSeguimientos(List<SeguimientoResponse> contenido, int pagina, int tamanio,
                                 long totalElementos, int totalPaginas) {
}
