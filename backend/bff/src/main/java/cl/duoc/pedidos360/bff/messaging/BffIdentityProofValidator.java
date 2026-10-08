package cl.duoc.pedidos360.bff.messaging;

import cl.duoc.pedidos360.messaging.identity.*;
import cl.duoc.pedidos360.messaging.relay.QueryTimeoutException;
import cl.duoc.pedidos360.messaging.relay.QueryUnavailableException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/** Validación posterior a correlación, sin otro listener y sin exponer el JWS al cliente HTTP. */
public final class BffIdentityProofValidator {
    private final IdentityProofVerifier verifier;
    private static final List<String> HTTP_FIELDS = List.of("id", "nombre", "apellido", "email", "telefono",
            "activo", "creadoEn", "actualizadoEn");
    private static final Set<String> RESPONSE_FIELDS = Set.of("id", "nombre", "apellido", "email", "telefono",
            "activo", "creadoEn", "actualizadoEn", "pruebaIdentidad");
    public BffIdentityProofValidator(IdentityProofVerifier verifier) { this.verifier = verifier; }
    public record VerifiedProfile(JsonNode perfil, IdentityProof prueba, Instant usableUntil) {
        @Override public String toString() { return "VerifiedProfile[redacted]"; }
    }
    public VerifiedProfile validate(JsonNode payload, RequestPlan plan, QueryOperationBudget budget) {
        budget.requireRemaining(plan.envelope().expiresAt());
        try {
            if (payload == null || !payload.isObject() || !payload.path("pruebaIdentidad").isString())
                throw new IdentityProofException(IdentityProofException.Reason.FORMATO);
            var proof = verifier.verify(payload.path("pruebaIdentidad").stringValue(), budget.tenant(), budget.oid(),
                    plan.envelope().messageId(), budget.originalDeadline());
            if (!plan.envelope().expiresAt().equals(budget.originalDeadline()) || !payload.path("id").isIntegralNumber()
                    || !payload.path("id").canConvertToLong() || payload.path("id").longValue() != proof.usuarioId()
                    || !payload.path("activo").isBoolean() || !payload.path("activo").booleanValue())
                throw new IdentityProofException(IdentityProofException.Reason.VINCULO);
            Instant usableUntil = verifier.usableUntil(proof);
            budget.requireRemaining(usableUntil);
            var profile = project(payload);
            var result = new VerifiedProfile(profile, proof, usableUntil);
            budget.requireRemaining(budget.originalDeadline());
            budget.requireRemaining(usableUntil);
            return result;
        } catch (IdentityProofException invalid) {
            if (invalid.reason() == IdentityProofException.Reason.VENCIDA) {
                budget.invalidate();
                throw new QueryTimeoutException("vigencia de la prueba de identidad agotada");
            }
            throw new QueryUnavailableException("respuesta de Usuarios contiene prueba de identidad inválida", null);
        }
    }
    /** El DTO persistido tiene ocho campos; solo telefono admite null. No se propagan extensiones. */
    private static ObjectNode project(JsonNode payload) {
        var names = new java.util.HashSet<String>();
        payload.properties().forEach(e -> names.add(e.getKey()));
        if (!names.equals(RESPONSE_FIELDS)) throw malformedProfile();
        for (String field : List.of("nombre", "apellido", "email"))
            if (!payload.path(field).isString()) throw malformedProfile();
        if (!payload.path("telefono").isString() && !payload.path("telefono").isNull()) throw malformedProfile();
        for (String field : List.of("creadoEn", "actualizadoEn")) {
            if (!payload.path(field).isString()) throw malformedProfile();
            try {
                String value = payload.path(field).stringValue();
                if (!value.endsWith("Z")) throw malformedProfile();
                Instant.parse(value);
            } catch (java.time.format.DateTimeParseException invalid) { throw malformedProfile(); }
        }
        var profile = new ObjectNode(JsonNodeFactory.instance);
        for (String field : HTTP_FIELDS) profile.set(field, payload.get(field).deepCopy());
        return profile;
    }
    private static IdentityProofException malformedProfile() {
        return new IdentityProofException(IdentityProofException.Reason.FORMATO);
    }
}
