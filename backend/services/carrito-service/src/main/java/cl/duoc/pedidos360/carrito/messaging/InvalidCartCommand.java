package cl.duoc.pedidos360.carrito.messaging;

public final class InvalidCartCommand extends RuntimeException {
  public InvalidCartCommand() {
    super("Invalid cart command");
  }
}
