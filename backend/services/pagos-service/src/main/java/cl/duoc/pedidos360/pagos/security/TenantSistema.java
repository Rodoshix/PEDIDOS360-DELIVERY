package cl.duoc.pedidos360.pagos.security;
import java.util.UUID;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** V1 is single-tenant: trusted process configuration, never message/client headers. */
@Component
public class TenantSistema {
    private final Environment env;
    public TenantSistema(Environment env) { this.env=env; }
    public UUID obtener() {
        String value=env.getProperty("entra.tenant-id", "");
        if (value.isBlank() && env.getProperty("pagos.identidad-local.enabled",Boolean.class,false)) {
            if (env.getActiveProfiles().length!=1 || !"local".equals(env.getActiveProfiles()[0])
                || !java.util.Set.of("127.0.0.1","::1").contains(env.getProperty("server.address","")))
                throw new IllegalStateException("Identidad local fuera de loopback.");
            value=env.getProperty("pagos.identidad-local.tenant-id","");
        }
        if (!value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
            throw new IllegalStateException("Tenant de sistema requerido.");
        UUID tenant=UUID.fromString(value);
        String worker=env.getProperty("pagos.worker.tenant-id", "");
        if (!worker.isBlank() && !tenant.equals(UUID.fromString(worker)))
            throw new IllegalStateException("Tenant de worker discordante.");
        return tenant;
    }
}
