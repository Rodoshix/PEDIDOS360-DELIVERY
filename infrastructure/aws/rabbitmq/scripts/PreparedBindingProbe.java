// Offline Spring binding probe. Uses the existing RabbitAdmin Maven classpath;
// no application context, network, keys or database are started.
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.amqp.autoconfigure.RabbitProperties;

public class PreparedBindingProbe {
    public static void main(String[] args) throws Exception {
        var root = Path.of(args[0]);
        Map<?, ?> overlay = new Yaml().load(Files.readString(root.resolve("infrastructure/aws/compose.ep2-prepared.yml")));
        var services = (Map<?, ?>) overlay.get("services");
        int count = 0;
        for (String name : new String[]{"bff", "usuarios", "restaurantes", "productos", "pedidos", "pagos", "carrito"}) {
            var service = (Map<?, ?>) services.get(name);
            @SuppressWarnings("unchecked")
            var values = (Map<String, Object>) service.get("environment");
            var env = new StandardEnvironment();
            env.getPropertySources().remove("systemProperties");
            env.getPropertySources().remove("systemEnvironment");
            env.getPropertySources().addFirst(new SystemEnvironmentPropertySource("systemEnvironment", values));
            var yaml = new YamlPropertiesFactoryBean();
            yaml.setResources(new FileSystemResource(root.resolve("backend/" + (name.equals("bff") ? "bff" : "services/" + name + "-service") + "/src/main/resources/application.yml")));
            env.getPropertySources().addLast(new PropertiesPropertySource("application", yaml.getObject()));
            verify(Binder.get(env).bind("spring.rabbitmq", RabbitProperties.class).get(), name);
            count++;
            if (name.equals("pagos")) {
                var p = Binder.get(env).bind("pagos.consultas.rabbitmq", RabbitProperties.class).get();
                verify(p, name + " query");
                if (!p.getUsername().equals("p360-pagos-consumer")) throw new AssertionError("Query identity");
                count++;
            }
            if (name.equals("pedidos") || name.equals("carrito")) {
                verify(Binder.get(env).bind("pedidos360.messaging.carrito", RabbitProperties.class).get(), name + " cart");
                count++;
            }
        }
        System.out.println("PASS: " + count + " Spring RabbitProperties bindings, AMQPS and certificate/hostname verification enabled");
    }
    static void verify(RabbitProperties p, String name) {
        if (!p.getHost().equals("p360-rabbitmq") || p.getPort() != 5671
            || !p.getVirtualHost().equals("pedidos360") || !Boolean.TRUE.equals(p.getSsl().getEnabled())
            || !p.getSsl().isVerifyHostname() || !p.getSsl().isValidateServerCertificate())
            throw new AssertionError("Incorrect prepared binding: " + name);
    }
}
