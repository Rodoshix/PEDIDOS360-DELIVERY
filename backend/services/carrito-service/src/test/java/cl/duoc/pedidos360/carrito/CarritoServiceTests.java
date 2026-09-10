package cl.duoc.pedidos360.carrito;

import java.util.Set;
import java.util.UUID;

import cl.duoc.pedidos360.carrito.dto.AgregarProductoRequest;
import cl.duoc.pedidos360.carrito.exception.ApiException;
import cl.duoc.pedidos360.carrito.repository.CarritoRepository;
import cl.duoc.pedidos360.carrito.security.IdentidadActual;
import cl.duoc.pedidos360.carrito.security.IdentidadUsuario;
import cl.duoc.pedidos360.carrito.service.CarritoService;
import cl.duoc.pedidos360.carrito.service.CatalogoProductos;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CarritoServiceTests {
    @Test
    void sinAdaptadorNoInventaPreciosNiGuardaDatos() {
        var repository = mock(CarritoRepository.class);
        var identidad = mock(IdentidadActual.class);
        when(identidad.obtener()).thenReturn(new IdentidadUsuario(UUID.randomUUID(), UUID.randomUUID(), Set.of(IdentidadUsuario.Rol.CLIENTE)));
        var beans = new DefaultListableBeanFactory();
        var service = new CarritoService(repository, identidad, beans.getBeanProvider(CatalogoProductos.class));
        assertThatThrownBy(() -> service.agregar(new AgregarProductoRequest(101L, 1)))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(error.getCause()).isNull();
                });
        verifyNoInteractions(repository);
    }

    @Test
    void llamadaDirectaSinIdentidadNoConsultaRepositorioNiCatalogo() {
        SecurityContextHolder.clearContext();
        try {
            var repository = mock(CarritoRepository.class);
            var catalogo = mock(CatalogoProductos.class);
            var beans = new DefaultListableBeanFactory();
            beans.registerSingleton("catalogo", catalogo);
            var service = new CarritoService(repository, new IdentidadActual(), beans.getBeanProvider(CatalogoProductos.class));
            assertThatThrownBy(service::obtener).isInstanceOf(AuthenticationCredentialsNotFoundException.class);
            assertThatThrownBy(() -> service.agregar(new AgregarProductoRequest(101L, 1))).isInstanceOf(AuthenticationCredentialsNotFoundException.class);
            assertThatThrownBy(service::vaciar).isInstanceOf(AuthenticationCredentialsNotFoundException.class);
            verifyNoInteractions(repository, catalogo);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
