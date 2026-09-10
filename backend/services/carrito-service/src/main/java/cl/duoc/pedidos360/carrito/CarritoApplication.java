package cl.duoc.pedidos360.carrito;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

// No crear un usuario/contraseña de desarrollo: el servicio no usa login por contraseña.
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class CarritoApplication {

    public static void main(String[] args) {
        SpringApplication.run(CarritoApplication.class, args);
    }
}
