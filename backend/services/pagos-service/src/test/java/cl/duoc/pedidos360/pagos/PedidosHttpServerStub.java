package cl.duoc.pedidos360.pagos;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Servidor HTTP local mínimo (JDK) que simula los endpoints de Pedidos:
 * {@code PUT /pedidos/{id}/estado}, {@code GET /pedidos/{id}} y
 * {@code PUT /internal/pedidos/{id}/confirmacion-pago}.
 */
final class PedidosHttpServerStub implements AutoCloseable {

    private final HttpServer server;
    private final AtomicInteger confirmacionesPut = new AtomicInteger();
    private final AtomicInteger confirmacionesInternas = new AtomicInteger();
    private final AtomicReference<String> ultimoAuthorization = new AtomicReference<>();

    private volatile int putStatus = 200;
    private volatile int getStatus = 200;
    private volatile String getEstado = "CREADO";
    private volatile int internoStatus = 204;

    PedidosHttpServerStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/pedidos/", this::handle);
        server.createContext("/internal/", this::handle);
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

    void responderInternoCon(int status) {
        this.internoStatus = status;
    }

    int confirmacionesPut() {
        return confirmacionesPut.get();
    }

    int confirmacionesInternas() {
        return confirmacionesInternas.get();
    }

    String ultimoAuthorization() {
        return ultimoAuthorization.get();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        ultimoAuthorization.set(authorization);
        try (exchange) {
            if (path.startsWith("/internal/") && "PUT".equals(method) && path.endsWith("/confirmacion-pago")) {
                confirmacionesInternas.incrementAndGet();
                ultimoAuthorization.set(authorization);
                responder(exchange, internoStatus, "");
                return;
            }
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
