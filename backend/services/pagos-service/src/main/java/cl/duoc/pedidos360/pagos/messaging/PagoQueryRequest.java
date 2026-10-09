package cl.duoc.pedidos360.pagos.messaging;

import cl.duoc.pedidos360.messaging.envelope.EnvelopeException;
import tools.jackson.databind.JsonNode;

public record PagoQueryRequest(long pagoId, String pruebaIdentidad) {
    public static PagoQueryRequest read(JsonNode payload) {
        var id = payload.get("pagoId");
        var proof = payload.get("pruebaIdentidad");
        if (!payload.isObject() || payload.size() != 2 || id == null || !id.isIntegralNumber()
                || !id.canConvertToLong() || id.longValue() <= 0 || proof == null || !proof.isString()
                || proof.stringValue().isBlank() || proof.stringValue().length() > 4096)
            throw new EnvelopeException(EnvelopeException.Reason.ESQUEMA_INVALIDO,
                    "payload exige pagoId positivo y pruebaIdentidad JWS");
        return new PagoQueryRequest(id.longValue(), proof.stringValue());
    }
    @Override public String toString() { return "PagoQueryRequest[redacted]"; }
}
