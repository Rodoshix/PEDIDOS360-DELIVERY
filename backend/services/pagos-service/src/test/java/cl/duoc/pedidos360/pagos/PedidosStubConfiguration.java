package cl.duoc.pedidos360.pagos;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration(proxyBeanMethods = false)
public class PedidosStubConfiguration {

    @Bean
    @Primary
    PedidosClientStub pedidosClientStub() {
        return new PedidosClientStub();
    }
}
