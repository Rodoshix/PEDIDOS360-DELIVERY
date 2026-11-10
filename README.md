# Pedidos360 Delivery — preparación v0.8.0

Aplicación React + Vite/MSAL, BFF y servicios Java 21/Spring Boot con PostgreSQL y mensajería RabbitMQ. v0.8.0 es la entrega en preparación: no hay tag/release publicado ni aceptación EP2 completa.

## Arquitectura y estado real

```text
React / Microsoft Entra ID
  → API Gateway (JWT para API)
  → Lambda Edge → BFF
  → Usuarios, Restaurantes, Productos, Carrito, Pedidos, Pagos
  → PostgreSQL/RDS y RabbitMQ
```

Repartidores y Seguimiento completan los ocho servicios funcionales del repositorio; el despliegue académico Host A tiene ocho aplicaciones (React, BFF y seis servicios), además del broker. RabbitAdmin es administración adicional, no otro dominio del conteo docente.

El estado operativo recuperado es **HTTP compatible**, con API Manager en mantenimiento. Las ventanas AWS acreditaron cuatro consultas RabbitMQ y el evento Pago→Pedido con publicación, ACK y persistencia. El POST de pago respondió 502 sin causa acreditada; sus GET de recuperación dieron 200. Pedido→Carrito está implementado y validado localmente, con candidata publicada, **pendiente de aceptación funcional AWS**. Edge de admisión y ACK_SENT están preparados; no se han desplegado.

Consultar [cierre EP2](docs/ep2/CIERRE-V080.md), [matriz y evidencias sanitizadas](docs/evidence/v0.8.0/README.md) y [procedimiento de integración](docs/ep2/INTEGRACION-V080.md). Los resultados AWS históricos no equivalen a salud cloud actual ni a autorización para activar flujos.

## Estructura y ejecución

- `frontend/`: React, Vite y MSAL.
- `backend/bff/`, `backend/services/`: BFF y servicios; `backend/shared/p360-messaging-core/`: contratos y reliability.
- `infrastructure/docker/`: entornos locales; `infrastructure/aws/`: configuración y procedimientos cloud.
- `docs/ep2/`: contratos, topología, aceptación y límites; `docs/evidence/`: resultados sanitizados.

Java 21, Maven, Node y Docker local son los prerrequisitos. Usar [dockerización local](infrastructure/docker/README-i1.md) y [componentes 1](infrastructure/docker/i1/README.md) según el escenario; son entornos locales, no procedimientos para recrear AWS. Instalar messaging-core antes de módulos dependientes. Para verificar el guard contra el BFF real usar el perfil explícito descrito en [TEST-BFF.md](backend/services/carrito-service/TEST-BFF.md), disponible después de integrar #115.

Para AWS consultar [guía actual](infrastructure/aws/README.md): imágenes inmutables, esquema migrado y credenciales runtime limitadas. No ejecutar migraciones, activar RabbitMQ ni desplegar por seguir este README; requieren una ventana autorizada independiente.

## Seguridad y entrega

JWT conserva firma, issuer, audience, vigencia, scopes/roles; servicios mantienen tenant y propietario. RabbitMQ usa identidad de aplicación y controles de contrato; no sustituye la autenticación HTTP del CLIENTE. Nunca versionar bearer, huellas de sesiones reales, credenciales, claves privadas ni logs crudos.

La pauta original menciona Angular; el frontend real es React. No se encontró evidencia de excepción docente en el material revisado: declarar la diferencia y obtener la decisión académica, sin inventar autorización ni reescribir el frontend automáticamente.

`main` y `develop` solo se actualizan mediante PR revisado y autorización expresa. #113–#117 permanecen Draft; #117 depende de #115. Una prueba verde no autoriza merge o despliegue. Ver [CONTRIBUTING.md](CONTRIBUTING.md).
