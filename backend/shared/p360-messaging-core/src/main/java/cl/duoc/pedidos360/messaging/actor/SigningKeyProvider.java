package cl.duoc.pedidos360.messaging.actor;

import java.util.Optional;

/**
 * Entrega el material de firma vigente.
 *
 * <p>La implementacion concreta decide de donde viene el secreto (variable de entorno, gestor de
 * secretos o KMS). La base request/reply no fija el mecanismo de distribucion de claves: solo exige
 * que exista una clave identificada y que su ausencia impida firmar o verificar.
 */
public interface SigningKeyProvider {

    /** Clave vigente usada para firmar. Vacio significa que el emisor no puede firmar. */
    Optional<SigningKey> claveParaFirmar();

    /** Material de la clave indicada. Vacio significa clave desconocida o retirada. */
    Optional<SigningKey> clavePorId(java.util.UUID keyId);
}
