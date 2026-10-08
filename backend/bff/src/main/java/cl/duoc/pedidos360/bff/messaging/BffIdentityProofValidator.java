package cl.duoc.pedidos360.bff.messaging;

import cl.duoc.pedidos360.messaging.identity.*;
import cl.duoc.pedidos360.messaging.relay.QueryTimeoutException;
import cl.duoc.pedidos360.messaging.relay.QueryUnavailableException;
import tools.jackson.databind.JsonNode;

/** Validación posterior a correlación, sin otro listener y sin exponer el JWS al cliente HTTP. */
public final class BffIdentityProofValidator {
    private final IdentityProofVerifier verifier;
    public BffIdentityProofValidator(IdentityProofVerifier verifier) { this.verifier = verifier; }
    public record VerifiedProfile(JsonNode perfil, IdentityProof prueba) {
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
            budget.requireRemaining(verifier.usableUntil(proof));
            var profile = (tools.jackson.databind.node.ObjectNode) payload.deepCopy();
            profile.remove("pruebaIdentidad");
            return new VerifiedProfile(profile, proof);
        } catch (IdentityProofException invalid) {
            if (invalid.reason() == IdentityProofException.Reason.VENCIDA)
                throw new QueryTimeoutException("vigencia de la prueba de identidad agotada");
            throw new QueryUnavailableException("respuesta de Usuarios contiene prueba de identidad inválida", null);
        }
    }
}
