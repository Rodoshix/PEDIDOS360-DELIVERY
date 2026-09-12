package cl.duoc.pedidos360.pagos;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Servidor HTTP local mínimo (JDK) que simula el endpoint de Pedidos
 * {@code PUT /pedidos/{id}/estado} y {@code GET /pedidos/{id}}.
 * Permite controlar las respuestas para probar el cliente HTTP real.
 */
final class PedidosHttpServerStub implements AutoCloseable {

    private final HttpServer server;
    private final AtomicInteger confirmacionesPut = new AtomicInteger();

    private volatile int putStatus = 200;
    private volatile int getStatus = 200;
    private volatile String getEstado = "CREADO";

    PedidosHttpServerStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/pedidos/", this::handle);
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    void responderPutCon(int status) {
        this.putStatus = status;
    }

    void responderGetCon(int status, String estado) {
        this.getStatus = status;
        this.getEstado = estado;
    }

    int confirmacionesPut() {
        return confirmacionesPut.get();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        try (exchange) {
            if ("PUT".equals(method) && path.endsWith("/estado")) {
                confirmacionesPut.incrementAndGet();
                if (putStatus == 200) {
                    responder(exchange, 200, "");
                } else {
                    responder(exchange, putStatus, "{\"detail\":\"rechazado\"}");
                }
            } else if ("GET".equals(method)) {
                if (getStatus == 200) {
                    responder(exchange, 200, "{\"pedidoId\":1,\"usuarioId\":10,\"estado\":\"" + getEstado
                            + "\",\"total\":13980,\"moneda\":\"CLP\"}");
                } else {
                    responder(exchange, getStatus, "{\"detail\":\"no disponible\"}");
                }
            } else {
                responder(exchange, 405, "");
            }
        }
    }

    private void responder(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
