package cl.duoc.pedidos360.usuarios.dto;

import java.util.List;

public record PaginaUsuarios(List<UsuarioResponse> contenido, int pagina, int tamanio,
                             long totalElementos, int totalPaginas) {
}
