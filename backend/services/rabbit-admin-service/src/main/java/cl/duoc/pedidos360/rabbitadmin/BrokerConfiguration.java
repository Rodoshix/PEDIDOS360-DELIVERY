package cl.duoc.pedidos360.rabbitadmin;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;

@Configuration(proxyBeanMethods=false)
public class BrokerConfiguration {
    @Bean CachingConnectionFactory rabbitConnectionFactory(Environment env) throws Exception {
        String user=env.getProperty("rabbit-admin.username", "p360-admin-demo");
        String password=env.getRequiredProperty("rabbit-admin.password");
        if (!"p360-admin-demo".equals(user) || password.isBlank())
            throw new IllegalStateException("Credencial sandbox aislada requerida.");
        var client=new com.rabbitmq.client.ConnectionFactory();
        client.setHost(env.getRequiredProperty("rabbit-admin.host"));
        client.setPort(env.getRequiredProperty("rabbit-admin.port",Integer.class));
        client.setVirtualHost(SandboxRules.VHOST); // Never a caller-selected/configurable vhost.
        client.setUsername(user); client.setPassword(password);
        client.setConnectionTimeout(2000); client.setHandshakeTimeout(2000); client.setChannelRpcTimeout(3000);
        if (env.getProperty("rabbit-admin.tls",Boolean.class,false)) {
            client.useSslProtocol(javax.net.ssl.SSLContext.getDefault()); client.enableHostnameVerification();
        }
        return new CachingConnectionFactory(client);
    }
    @Bean RabbitAdmin sandboxAdmin(CachingConnectionFactory cf) {
        var admin=new RabbitAdmin(cf);
        admin.setAutoStartup(false);
        admin.setIgnoreDeclarationExceptions(false);
        admin.setRedeclareManualDeclarations(false);
        return admin;
    }
}
