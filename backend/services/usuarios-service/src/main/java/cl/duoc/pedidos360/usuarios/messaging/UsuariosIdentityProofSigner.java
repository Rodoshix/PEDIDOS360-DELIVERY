package cl.duoc.pedidos360.usuarios.messaging;

import cl.duoc.pedidos360.messaging.actor.ActorContext;
import cl.duoc.pedidos360.messaging.actor.SigningKey;
import cl.duoc.pedidos360.messaging.envelope.RequestEnvelope;
import cl.duoc.pedidos360.messaging.identity.*;
import cl.duoc.pedidos360.usuarios.service.PerfilActualVerificado;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.crypto.ECDSASigner;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.Duration;
import java.util.UUID;

/** La capacidad de firma vive únicamente en Usuarios, con una privada diferente de ActorContext. */
public final class UsuariosIdentityProofSigner {
    private final SigningKey key;
    private final Clock clock;
    private final IdentityProofVerifier verifier;
    public UsuariosIdentityProofSigner(SigningKey key, IdentityProofKeys publicKeys, Clock clock, Duration margin) {
        if (key == null || !key.material().isPrivate()
                || !key.material().toPublicJWK().equals(publicKeys.get(key.keyId()).material()))
            throw new IllegalStateException("privada de Usuarios no coincide con pública confiable");
        this.key = key; this.clock = clock;
        this.verifier = new IdentityProofVerifier(publicKeys, clock, margin);
        try {
            var probe = new com.nimbusds.jose.JWSObject(new JWSHeader(JWSAlgorithm.ES256), new com.nimbusds.jose.Payload("configuration-check"));
            probe.sign(new ECDSASigner(key.material()));
            if (!probe.verify(new com.nimbusds.jose.crypto.ECDSAVerifier(key.material().toPublicJWK()))) throw new IllegalArgumentException();
        } catch (Exception invalid) { throw new IllegalStateException("privada de Usuarios incoherente"); }
    }
    public String emitir(ActorContext actor, RequestEnvelope request, PerfilActualVerificado perfil) {
        if (!perfil.tenantId().equals(actor.tenantId()) || !perfil.entraObjectId().equals(actor.sujetoId())
                || !perfil.perfil().activo() || perfil.perfil().id() == null || perfil.perfil().id() < 1)
            throw new IdentityProofException(IdentityProofException.Reason.VINCULO);
        Instant now = IdentityProofCodec.millis(clock.instant());
        Instant deadline = IdentityProofCodec.millis(request.expiresAt());
        if (!request.expiresAt().equals(deadline)) throw new IdentityProofException(IdentityProofException.Reason.TIEMPO_INCOHERENTE);
        Instant observed = IdentityProofCodec.millis(perfil.perfilVerificadoEn());
        Instant expiry = observed.plusSeconds(4).isBefore(deadline) ? observed.plusSeconds(4) : deadline;
        if (!expiry.isAfter(now)) throw new IdentityProofException(IdentityProofException.Reason.VENCIDA);
        var proof = new IdentityProof(actor.tenantId(), actor.sujetoId(), perfil.perfil().id(), observed,
                now, expiry, deadline, request.messageId(), UUID.randomUUID());
        String input = IdentityProofCodec.header(key.keyId()) + "." + IdentityProofCodec.payload(proof);
        try {
            var header = new JWSHeader.Builder(JWSAlgorithm.ES256).type(new JOSEObjectType(IdentityProof.TYPE)).keyID(key.keyId().toString()).build();
            String jws = input + "." + new ECDSASigner(key.material()).sign(header, input.getBytes(StandardCharsets.US_ASCII));
            verifier.verify(jws, actor.tenantId(), actor.sujetoId(), request.messageId(), deadline);
            return jws;
        } catch (com.nimbusds.jose.JOSEException invalid) { throw new IdentityProofException(IdentityProofException.Reason.FIRMA); }
    }
    @Override public String toString() { return "UsuariosIdentityProofSigner[kid=" + key.keyId() + "]"; }
}
