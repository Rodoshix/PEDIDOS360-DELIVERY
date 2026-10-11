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
     * @throws IllegalStateException si el {@code correlationId} ya tiene una espera viva. Sobrescribir
     *     la espera anterior perderia la respuesta de la primera consulta y dejaria su hilo esperando
     *     hasta el timeout.
     */
    public CompletableFuture<byte[]> registrar(String correlationId) {
        if (correlationId == null || correlationId.isBlank())
            throw new IllegalArgumentException("correlationId requerido");
        var futuro = new CompletableFuture<byte[]>();
        if (pendientes.putIfAbsent(correlationId, futuro) != null)
            throw new IllegalStateException("correlationId duplicado: ya existe una espera viva");
        if (enVuelo.incrementAndGet() > maximo) {
            enVuelo.decrementAndGet();
            pendientes.remove(correlationId, futuro);
            throw new IllegalStateException("demasiadas consultas concurrentes en vuelo");
        }
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

    /**
     * Cancela todas las esperas pendientes: se invoca al apagar el BFF.
     *
     * <p>Sin esta limpieza, un cierre deja hilos esperando hasta su timeout y futuros huerfanos. Las
     * esperas se completan de forma excepcional para que ningun llamador quede colgado.
     *
     * @return cantidad de correlaciones canceladas.
     */
    public int cancelarTodo() {
        int canceladas = 0;
        for (var entrada : pendientes.entrySet()) {
            if (pendientes.remove(entrada.getKey(), entrada.getValue())) {
                enVuelo.decrementAndGet();
                entrada.getValue().completeExceptionally(
                        new QueryUnavailableException("el BFF se esta cerrando", null));
                canceladas++;
            }
        }
        return canceladas;
    }

    public Optional<CompletableFuture<byte[]>> pendiente(String correlationId) {
        return Optional.ofNullable(pendientes.get(correlationId));
    }
}
