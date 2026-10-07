package cl.duoc.pedidos360.messaging.relay;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Registro de correlaciones pendientes del BFF.
 *
 * <p>Soporta multiples requests concurrentes, descarte de respuestas tardias o duplicadas,
 * limpieza al vencer el plazo y un limite de correlaciones en vuelo para no crecer sin control.
 *
 * <p>Una correlacion se resuelve una sola vez: {@link #completar} la retira de forma atomica, por lo
 * que una respuesta duplicada o una que llega despues del timeout se descarta y se informa a quien
 * la recibe como desconocida.
 */
public final class PendingCorrelationRegistry {

    private final Map<String, CompletableFuture<byte[]>> pendientes = new ConcurrentHashMap<>();
    private final AtomicInteger enVuelo = new AtomicInteger();
    private final int maximo;

    public PendingCorrelationRegistry(int maximo) {
        if (maximo < 1) throw new IllegalArgumentException("limite de correlaciones debe ser positivo");
        this.maximo = maximo;
    }

    /**
     * Registra una correlacion y devuelve la espera asociada.
     *
     * @throws IllegalStateException si ya hay demasiadas correlaciones en vuelo: es preferible
     *     rechazar la consulta que acumular esperas sin limite.
     */
    public CompletableFuture<byte[]> registrar(String correlationId) {
        if (correlationId == null || correlationId.isBlank())
            throw new IllegalArgumentException("correlationId requerido");
        if (enVuelo.incrementAndGet() > maximo) {
            enVuelo.decrementAndGet();
            throw new IllegalStateException("demasiadas consultas concurrentes en vuelo");
        }
        var futuro = new CompletableFuture<byte[]>();
        pendientes.put(correlationId, futuro);
        return futuro;
    }

    /**
     * Resuelve una correlacion conocida.
     *
     * @return {@code true} si habia una espera pendiente; {@code false} si la correlacion es
     *     desconocida, tardia o duplicada.
     */
    public boolean completar(String correlationId, byte[] cuerpo) {
        if (correlationId == null) return false;
        CompletableFuture<byte[]> futuro = pendientes.remove(correlationId);
        if (futuro == null) return false;
        enVuelo.decrementAndGet();
        return futuro.complete(cuerpo);
    }

    /** Retira una correlacion al vencer el plazo o al cancelar la espera. */
    public boolean descartar(String correlationId) {
        if (correlationId == null) return false;
        CompletableFuture<byte[]> futuro = pendientes.remove(correlationId);
        if (futuro == null) return false;
        enVuelo.decrementAndGet();
        return futuro.completeExceptionally(new QueryTimeoutException("plazo de la consulta agotado"));
    }

    public int enVuelo() {
        return enVuelo.get();
    }

    public Optional<CompletableFuture<byte[]>> pendiente(String correlationId) {
        return Optional.ofNullable(pendientes.get(correlationId));
    }
}
