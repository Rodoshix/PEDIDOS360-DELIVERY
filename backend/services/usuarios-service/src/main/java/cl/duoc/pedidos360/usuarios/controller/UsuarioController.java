package cl.duoc.pedidos360.usuarios.controller;

import java.net.URI;

import cl.duoc.pedidos360.usuarios.dto.PaginaUsuarios;
import cl.duoc.pedidos360.usuarios.dto.PerfilRequest;
import cl.duoc.pedidos360.usuarios.dto.UsuarioResponse;
import cl.duoc.pedidos360.usuarios.service.UsuarioService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/usuarios")
public class UsuarioController {
    private final UsuarioService usuarios;

    public UsuarioController(UsuarioService usuarios) {
        this.usuarios = usuarios;
    }

    @GetMapping
    public PaginaUsuarios listar(
            @RequestParam(defaultValue = "0") @Min(0) int pagina,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int tamanio) {
        return usuarios.listar(pagina, tamanio);
    }

    @GetMapping("/me")
    public UsuarioResponse actual() {
        return usuarios.obtenerActual();
    }

    @GetMapping("/{id}")
    public UsuarioResponse obtener(@PathVariable @Positive Long id) {
        return usuarios.obtener(id);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<UsuarioResponse> crear(@Valid @RequestBody PerfilRequest request) {
        var response = usuarios.crear(request);
        return ResponseEntity.created(URI.create("/usuarios/" + response.id())).body(response);
    }

    @PutMapping(value = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public UsuarioResponse actualizar(@PathVariable @Positive Long id,
                                     @Valid @RequestBody PerfilRequest request) {
        return usuarios.actualizar(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void desactivar(@PathVariable @Positive Long id) {
        usuarios.desactivar(id);
    }
}
