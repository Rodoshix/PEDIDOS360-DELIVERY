package cl.duoc.pedidos360.repartidores.security;

import java.util.Set;
import java.util.UUID;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalIdentityFilterTests {
    private final IdentidadUsuario identidad = new IdentidadUsuario(
            UUID.randomUUID(), UUID.randomUUID(), Set.of(IdentidadUsuario.Rol.ADMIN));
    private final LocalIdentityFilter filter = new LocalIdentityFilter(identidad);

    @AfterEach
    void limpiarContexto() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void peticionRemotaNoSeAutenticaAunqueFalsifiqueForwardedHeaders() throws Exception {
        var request = new MockHttpServletRequest("GET", "/repartidores/me");
        request.setRemoteAddr("203.0.113.10");
        request.addHeader("X-Forwarded-For", "127.0.0.1");
        request.addHeader("Forwarded", "for=127.0.0.1");
        request.addHeader("X-Roles", "ADMIN");

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) ->
                assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void localhostSoloRecibeLaIdentidadConfiguradaYNoLaDeLosHeaders() throws Exception {
        for (String address : new String[]{"127.0.0.1", "::1"}) {
            var request = new MockHttpServletRequest("GET", "/repartidores/me");
            request.setRemoteAddr(address);
            request.addHeader("X-Roles", "CLIENTE");
            request.addHeader("X-Object-Id", UUID.randomUUID().toString());

            filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
                var authentication = SecurityContextHolder.getContext().getAuthentication();
                assertThat(authentication.getPrincipal()).isEqualTo(identidad);
                assertThat(authentication.getAuthorities()).extracting("authority")
                        .containsExactly("ROLE_ADMIN");
            });
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        }
    }

    @Test
    void limpiaIdentidadInclusoSiElControladorFalla() {
        var request = new MockHttpServletRequest("GET", "/repartidores/me");
        request.setRemoteAddr("127.0.0.1");

        assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
            throw new ServletException("Fallo de prueba");
        })).isInstanceOf(ServletException.class);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
