# AWS — estado y límites de operación EP2

Actualización documental 11-10-2026; sin comprobación AWS fresca. El último cierre conservado acredita ocho aplicaciones HTTP y RabbitMQ saludables, RDS compatible, TLS/JWT y API Manager en mantenimiento. La recuperación tras AWS Academy fue espontánea; no se reiniciaron servicios para corregirla.

## Arquitectura vigente

Host A: React, BFF y Usuarios/Restaurantes/Productos/Carrito/Pedidos/Pagos; PostgreSQL en RDS con bases separadas, `sslmode=verify-full`, CA y hostname verificados. TLS interno y Entra permanecen activos. Repartidores/Seguimiento no forman parte de esas ocho aplicaciones.

La entrada pasa por HTTP API → Lambda Edge → frontend/BFF. `compose.yml` base publica loopback; `compose.edge.yml` configura los binds privados de Host A. No confundir esa configuración con puertos públicos ni permitir acceso alternativo que eluda JWT/Edge. Host B y el clúster de demostración son independientes; su aceptación completa no se deduce de metadatos o del broker A.

## Configuraciones y modos

| Archivo | Función | Condición |
|---|---|---|
| `compose.yml`, `compose.edge.yml` | Aplicaciones y entrada privada | Mantener pins e identidad aprobados |
| `compose.ep2-prepared.yml` | TLS/secretos RabbitMQ; consultas y coordinaciones HTTP por defecto | No activa flujos |
| `compose.ep2-runtime-roles.yml` | Carrito/Pedidos/Pagos con roles runtime limitados y Flyway desactivado | Requiere esquema ya migrado y roles verificados |
| `compose.ep2-query-candidate.yml` | Cuatro consultas RabbitMQ | Ventana independiente; ENTRA_TENANT_ID obligatorio en catálogos |
| `compose.ep2-pago-candidate.yml` | Pago→Pedido RabbitMQ | PAGOS_WORKER_ENABLED=false y PEDIDOS_INTERNO_ENABLED=false; no fallback HTTP interno |
| `compose.ep2-carrito-candidate.yml` | Pedido→Carrito y variante React | No desplegado/aceptado AWS; requiere guard 409, gate y evidencia |

La configuración base HTTP permanece intacta. Las tres líneas operativas de #113 se versionan para reproducir las ventanas anteriores; añadir un overlay a un comando no constituye autorización para ejecutarlo.

## Imágenes y procedencia

Usar digest aprobado por componente, no `latest` ni asumir que una candidata está desplegada. La candidata Carrito #115+#117 publicada tiene digest `sha256:166afeeb0a43c4635f0fa404d74176394707b4a3a11c5e03667ea366722b11b7`: f6a9540 más los dos Java aprobados, POM base. No es una imagen del árbol integrado v0.8.0. ZIP Edge #116 preparado sin desplegar; SHA-256 `7dd7b2da966f6464ae70a4a1e8f3dff21fb30a1c294f3c66ba3a427f94821f86`.

`build-minimal-local.py` es histórico: fija f6a9540 y rechaza develop modificado. No usarlo para atribuir una nueva imagen al release final. Construir desde el SHA final revisado, instalar core desde el mismo checkout y registrar manifiesto/digest requiere autorización posterior; no se publican imágenes en esta fase.

## Recuperación compatible, sin ejecutarla

Antes de cualquier futura recreación: mantenimiento efectivo, evidencia privada persistente por containerId, snapshots y comprobación de outbox/leases/receipts/ready/unacked/retry. No recuperar HTTP mientras quede trabajo incierto. Preservar eventos, mensajes, outbox y DLQ; no purgar/reentregar ni repetir POST para resolver incertidumbre.

Volver solo a imágenes EP2 compatibles con el esquema migrado, overlays HTTP y roles runtime aprobados. No retirar el overlay de roles ni reactivar migraciones de runtime; el Compose inicial #55 no es un downgrade seguro. Verificar ocho aplicaciones, TLS/JWT y mantenimiento efectivo al cerrar. Orden de una futura activación Pedido→Carrito: consumidor Carrito antes de productor Pedidos, exclusivamente con autorización y gate validado.

No versionar `.env`, archivos de huella real, bearer, secretos, certificados privados ni logs crudos. Los ejemplos de Compose se validan localmente con valores sintéticos y sin conectar a AWS/RDS.

## Evidencia y límites

[Índice portable](../../docs/evidence/v0.8.0/README.md) y [cierre](../../docs/ep2/CIERRE-V080.md). Pago→Pedido acredita el evento, pero su POST público respondió 502 sin causa. HEAD / del segundo bootstrap respondió 500 sin causa acreditada; es otro incidente. No existe nueva sesión/huella de bootstrap ni aceptación AWS Pedido→Carrito. No cambiar IAM, timeouts o código por hipótesis.

[Preparación inicial #55](HTTP-BASE-55-HISTORICO.md) se conserva como histórica; para contratos y plataforma ver [documentación EP2](../../docs/ep2/README.md). Este documento no autoriza despliegues, aperturas o activaciones.
