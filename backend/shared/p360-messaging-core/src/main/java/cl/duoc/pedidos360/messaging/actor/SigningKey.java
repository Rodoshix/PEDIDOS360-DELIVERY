package cl.duoc.pedidos360.messaging.actor;

/**
 * Material de firma de una clave simetrica identificada.
 *
 * <p>El material nunca proviene de Git: se inyecta por variable de entorno o gestor de secretos.
 * El identificador permite rotar claves y rechazar sobres firmados con una clave retirada.
 */
public record SigningKey(java.util.UUID keyId, byte[] material) {
    public SigningKey {
        if (keyId == null) throw new IllegalArgumentException("keyId requerido");
        if (material == null || material.length < 32)
            throw new IllegalArgumentException("el material de firma debe tener al menos 32 bytes");
        material = material.clone();
    }

    @Override
    public byte[] material() {
        return material.clone();
    }
}
