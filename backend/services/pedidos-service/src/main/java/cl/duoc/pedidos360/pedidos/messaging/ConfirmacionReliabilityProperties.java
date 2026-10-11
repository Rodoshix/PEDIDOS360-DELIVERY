package cl.duoc.pedidos360.pedidos.messaging;
import java.time.Duration;
import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
@Validated
@ConfigurationProperties("pedidos360.messaging.reliability")
public record ConfirmacionReliabilityProperties(@NotBlank String retryExchange,@NotBlank String dlx,
 @NotBlank String dlq,@NotBlank String failedRoutingKey,@NotBlank String retry5Queue,
 @NotBlank String retry30Queue,@NotBlank String retry120Queue,@NotBlank String retry5Key,
 @NotBlank String retry30Key,@NotBlank String retry120Key,@NotNull Duration recoveryBackoff,
 boolean platformReady) {
 @AssertTrue public boolean isBackoffValid() { return recoveryBackoff!=null && recoveryBackoff.toMillis()>=1000; }
 public String retryKey(int count) { return switch(count) {case 0 -> retry5Key; case 1 -> retry30Key; case 2 -> retry120Key; default -> throw new IllegalArgumentException("Retry exhausted");}; }
}
