# Pedidos360 Delivery â€” preparaciÃ³n v0.8.0

AplicaciÃ³n React + Vite/MSAL, BFF y servicios Java 21/Spring Boot con PostgreSQL y mensajerÃ­a RabbitMQ. v0.8.0 es la entrega en preparaciÃ³n: no hay tag/release publicado ni aceptaciÃ³n EP2 completa.

## Arquitectura y estado real

```text
React / Microsoft Entra ID
  â†’ API Gateway (JWT para API)
  â†’ Lambda Edge â†’ BFF
  â†’ Usuarios, Restaurantes, Productos, Carrito, Pedidos, Pagos
  â†’ PostgreSQL/RDS y RabbitMQ
```

Repartidores y Seguimiento completan los ocho servicios funcionales del repositorio; el despliegue acadÃ©mico Host A tiene ocho aplicaciones (React, BFF y seis servicios), ademÃ¡s del broker. RabbitAdmin es administraciÃ³n adicional, no otro dominio del conteo docente.

El estado operativo recuperado es **HTTP compatible**, con API Manager en mantenimiento. Las ventanas AWS acreditaron cuatro consultas RabbitMQ y el evento Pagoâ†’Pedido con publicaciÃ³n, ACK y persistencia. El POST de pago respondiÃ³ 502 sin causa acreditada; sus GET de recuperaciÃ³n dieron 200. Pedidoâ†’Carrito estÃ¡ implementado y validado localmente, con candidata publicada, **pendiente de aceptaciÃ³n funcional AWS**. Edge de admisiÃ³n y ACK_SENT estÃ¡n preparados; no se han desplegado.

Consultar [cierre EP2](docs/ep2/CIERRE-V080.md), [matriz y evidencias sanitizadas](docs/evidence/v0.8.0/README.md) y [procedimiento de integraciÃ³n](docs/ep2/INTEGRACION-V080.md). Los resultados AWS histÃ³ricos no equivalen a salud cloud actual ni a autorizaciÃ³n para activar flujos.

## Estructura y ejecuciÃ³n

- `frontend/`: React, Vite y MSAL.
- `backend/bff/`, `backend/services/`: BFF y servicios; `backend/shared/p360-messaging-core/`: contratos y reliability.
- `infrastructure/docker/`: entornos locales; `infrastructure/aws/`: configuraciÃ³n y procedimientos cloud.
- `docs/ep2/`: contratos, topologÃ­a, aceptaciÃ³n y lÃ­mites; `docs/evidence/`: resultados sanitizados.

Java 21, Maven, Node y Docker local son los prerrequisitos. Usar [dockerización local](infrastructure/docker/README-i1.md) y [componentes 1](infrastructure/docker/i1/README.md) segÃºn el escenario; son entornos locales, no procedimientos para recrear AWS. Instalar messaging-core antes de mÃ³dulos dependientes. Para verificar el guard contra el BFF real usar el perfil explÃ­cito descrito en [TEST-BFF.md](backend/services/carrito-service/TEST-BFF.md), disponible despuÃ©s de integrar #115.

Para AWS consultar [guÃ­a actual](infrastructure/aws/README.md): imÃ¡genes inmutables, esquema migrado y credenciales runtime limitadas. No ejecutar migraciones, activar RabbitMQ ni desplegar por seguir este README; requieren una ventana autorizada independiente.

## Seguridad y entrega

JWT conserva firma, issuer, audience, vigencia, scopes/roles; servicios mantienen tenant y propietario. RabbitMQ usa identidad de aplicaciÃ³n y controles de contrato; no sustituye la autenticaciÃ³n HTTP del CLIENTE. Nunca versionar bearer, huellas de sesiones reales, credenciales, claves privadas ni logs crudos.

La pauta original menciona Angular; el frontend real es React. No se encontrÃ³ evidencia de excepciÃ³n docente en el material revisado: declarar la diferencia y obtener la decisiÃ³n acadÃ©mica, sin inventar autorizaciÃ³n ni reescribir el frontend automÃ¡ticamente.

`main` y `develop` solo se actualizan mediante PR revisado y autorizaciÃ³n expresa. #113â€“#117 permanecen Draft; #117 depende de #115. Una prueba verde no autoriza merge o despliegue. Ver [CONTRIBUTING.md](CONTRIBUTING.md).
