package cl.duoc.pedidos360.messaging.actor;

import java.util.Optional;

/** Clave privada de emisión solo BFF y públicas locales confiables para verificar. */
public interface SigningKeyProvider {

    /** Clave vigente usada para firmar. Vacio significa que el emisor no puede firmar. */
    Optional<SigningKey> claveParaFirmar();

    /** Clave pública indicada. Vacio significa clave desconocida o retirada; nunca material privado. */
    Optional<SigningKey> clavePorId(java.util.UUID keyId);
}
