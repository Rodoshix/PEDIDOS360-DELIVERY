package cl.duoc.pedidos360.carrito;

import cl.duoc.pedidos360.carrito.messaging.CarritoReceiptStore;
import cl.duoc.pedidos360.messaging.command.VaciarCarritoPorPedido;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

/**
 * Test-only child JVM: actual service context, manual processor invocation, no listener fixture.
 */
public final class CarritoRestartProbe {
  public static void main(String[] args) throws Exception {
    String body =
        new java.io.BufferedReader(
                new java.io.InputStreamReader(System.in, java.nio.charset.StandardCharsets.UTF_8))
            .readLine();
    boolean awaitCrash = Boolean.parseBoolean(args[0]);
    try (var context =
        new SpringApplicationBuilder(CarritoApplication.class)
            .web(WebApplicationType.NONE)
            .run(java.util.Arrays.copyOfRange(args, 1, args.length))) {
      var command =
          VaciarCarritoPorPedido.leer(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      var store = context.getBean(CarritoReceiptStore.class);
      store.accept(command, false);
      var result = store.process(command, false);
      System.out.println("PROBE_COMMITTED=" + result);
      System.out.flush();
      if (awaitCrash) Thread.sleep(120000);
    }
  }
}
