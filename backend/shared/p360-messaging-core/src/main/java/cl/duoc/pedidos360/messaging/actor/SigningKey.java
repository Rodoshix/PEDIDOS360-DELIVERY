package cl.duoc.pedidos360.messaging.actor;

import java.util.UUID;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.KeyUse;

/** Clave EC P-256 confiable. Los consumers reciben exclusivamente su parte pública. */
public record SigningKey(UUID keyId, ECKey material) {
    public SigningKey {
        if (keyId == null || material == null || material.getKeyRevocation() != null || !Curve.P_256.equals(material.getCurve())
                || !keyId.toString().equals(material.getKeyID())
                || (material.getAlgorithm() != null && !JWSAlgorithm.ES256.equals(material.getAlgorithm()))
                || (material.getKeyUse() != null && !KeyUse.SIGNATURE.equals(material.getKeyUse()))
                || (material.getKeyOperations() != null && !material.getKeyOperations().isEmpty()))
            throw new IllegalArgumentException("clave de actor debe ser EC P-256 con kid UUID y uso ES256");
        try { material.toECPublicKey(); }
        catch (Exception invalid) { throw new IllegalArgumentException("clave pública EC inválida"); }
    }

    public SigningKey publica() { return new SigningKey(keyId, material.toPublicJWK()); }

    @Override public String toString() { return "SigningKey[kid=" + keyId + "]"; }
}
