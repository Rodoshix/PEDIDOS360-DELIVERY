package cl.duoc.pedidos360.rabbitadmin;

import jakarta.validation.constraints.*;

public final class AdminDtos {
    private AdminDtos() {}
    // Fixed durable, non-exclusive, non-auto-delete resources; no client AMQP arguments.
    public record QueueRequest(@NotNull @AssertTrue Boolean durable) {}
    public record ExchangeRequest(@NotNull @Pattern(regexp="direct|topic|fanout") String type,
                                  @NotNull @AssertTrue Boolean durable) {}
    public record BindingRequest(@NotNull @Pattern(regexp=SandboxRules.NAME) String queue,
                                 @NotNull @Pattern(regexp=SandboxRules.NAME) String exchange,
                                 @NotNull @Pattern(regexp=SandboxRules.KEY) String routingKey) {}
    public record QueueResult(String name, int messages, int consumers) {}
    public record ExchangeResult(String name, String type, boolean durable) {}
    public record BindingResult(String bindingId) {}
}
