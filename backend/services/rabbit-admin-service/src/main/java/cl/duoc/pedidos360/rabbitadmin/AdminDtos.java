package cl.duoc.pedidos360.rabbitadmin;

import jakarta.validation.constraints.*;

public final class AdminDtos {
    private AdminDtos() {}
    // Existing durable queues remain protected. Disposable queues explicitly opt into
    // connection-scoped lifetime; they are not a storage mechanism for business data.
    public record QueueRequest(@NotNull Boolean durable, Boolean disposable) {
        public QueueRequest(Boolean durable) { this(durable, false); }
    }
    public record ExchangeRequest(@NotNull @Pattern(regexp="direct|topic|fanout") String type,
                                  @NotNull @AssertTrue Boolean durable) {}
    public record BindingRequest(@NotNull @Pattern(regexp=SandboxRules.NAME) String queue,
                                 @NotNull @Pattern(regexp=SandboxRules.NAME) String exchange,
                                 @NotNull @Pattern(regexp=SandboxRules.KEY) String routingKey) {}
    public record QueueResult(String name, int messages, int consumers) {}
    public record ExchangeResult(String name, String type, boolean durable) {}
    public record BindingResult(String bindingId) {}
}
