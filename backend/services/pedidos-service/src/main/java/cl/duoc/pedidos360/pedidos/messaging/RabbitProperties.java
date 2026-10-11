package cl.duoc.pedidos360.pedidos.messaging;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.constraints.*;
import jakarta.validation.Valid;

@Validated
@ConfigurationProperties("pedidos360.messaging")
public record RabbitProperties(@NotNull Mode coordinationMode, @NotNull @Valid Exchanges exchanges,
        @NotNull @Valid RoutingKeys routingKeys, @NotNull @Valid Queues queues, @NotNull Duration confirmTimeout,
        @NotNull Duration lease, @Min(1) @Max(100) int batchSize,
        @Min(1000) long dispatchIntervalMs, @NotNull Duration publisherRetryDelay) {
    public record Exchanges(@NotBlank String commands) {}
    public record RoutingKeys(@NotBlank String confirmar) {}
    public record Queues(@NotBlank String confirmacion) {}
    public enum Mode { HTTP, RABBITMQ }
    @AssertTrue(message="lease must exceed positive confirmTimeout; retry delay must be positive")
    public boolean isTimingValid() {
        return confirmTimeout != null && lease != null && publisherRetryDelay != null
            && confirmTimeout.isPositive() && lease.compareTo(confirmTimeout) > 0
            && publisherRetryDelay.isPositive();
    }
}
