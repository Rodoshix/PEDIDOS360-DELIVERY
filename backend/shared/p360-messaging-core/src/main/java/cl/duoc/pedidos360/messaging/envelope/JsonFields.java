package cl.duoc.pedidos360.messaging.envelope;

import java.util.Set;

import tools.jackson.databind.JsonNode;

/**
 * Comprobacion estricta de campos de un objeto JSON.
 *
 * <p>Jackson 3 entrega los nombres como {@code Collection}, no como {@code Set}: comparar
 * colecciones directamente produce falsos negativos. Aqui se exige el conjunto exacto contando y
 * verificando presencia, sin depender del tipo devuelto.
 */
final class JsonFields {

    private JsonFields() {}

    /** {@code true} solo si el objeto tiene exactamente esos campos, ni mas ni menos. */
    static boolean exactos(JsonNode objeto, Set<String> esperados) {
        if (objeto == null || !objeto.isObject()) return false;
        var nombres = objeto.propertyNames();
        if (nombres.size() != esperados.size()) return false;
        for (String esperado : esperados) {
            if (!objeto.has(esperado)) return false;
        }
        return true;
    }
}
