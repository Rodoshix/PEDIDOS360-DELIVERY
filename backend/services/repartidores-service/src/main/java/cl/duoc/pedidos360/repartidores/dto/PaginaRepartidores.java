package cl.duoc.pedidos360.repartidores.dto;

import java.util.List;

public record PaginaRepartidores(List<RepartidorResponse> contenido, int pagina, int tamanio,
                                 long totalElementos, int totalPaginas) {
}
