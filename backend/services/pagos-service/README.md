# Pagos Service

Servicio de pagos simulados de Pedidos360. Java 21, Spring Boot 4.1.1 y Maven Wrapper 3.9.15.

## Estado

Implementados: entidad Pago + EstadoPago/MetodoPago, repositorio JPA, migraciones Flyway para PostgreSQL,
registro de pago simulado con **idempotencia**, autorización por propietario/rol, garantía de **un pago
activo por pedido** (índice único parcial), coordinación **recuperable** con Pedidos y consultas.

Reglas principales:
- **Pago simulado**: no se almacenan datos bancarios reales.
- **Idempotencia con alcance por identidad**: la `Idempotency-Key` se interpreta por usuario; un reintento
  idéntico devuelve el mismo pago y la misma clave con otra operación (pedido o método) responde 409.
- **Un pago activo por pedido**: garantizado en PostgreSQL con un índice único parcial sobre
  `pedido_id` para estados `PENDIENTE`/`APROBADO`; el conflicto se traduce a 409.
- **Autorización**: registrar/consultar exige pertenencia del pedido a la identidad (o rol `ADMIN`);
  aprobar un cobro exige el permiso explícito `REPARTIDOR` o `ADMIN`.
- **Coordinación recuperable**: el pago se persiste primero (commit local) y luego se confirma el pedido.
  Si la confirmación falla o se pierde la respuesta, el pago queda con `pedido_confirmado=false` y la
  reconciliación lo reintenta de forma idempotente.
- **Confirmación verificada (no optimista)**: un rechazo de Pedidos (400/409) **no** se trata automáticamente
  como éxito: se consulta el estado real del pedido y solo se acepta si ya está confirmado o en un estado
  posterior. Un pedido `CANCELADO` u otro estado no confirmado deja la coordinación **pendiente**, no confirmada.
- El **monto** se toma del pedido; no se confía en un monto enviado por el cliente.
- El **usuarioId** se resuelve desde la identidad autenticada, no desde el cuerpo.

## Flujo de pago

- **TARJETA**: se resuelve al registrar (APROBADO/RECHAZADO). Si se aprueba, se confirma el pedido.
- **EFECTIVO**: queda **PENDIENTE** ("por cobrar") y el pedido se confirma igual para preparación/despacho;
  pasa a **APROBADO** al entregar (cobro aprobado por `REPARTIDOR` o `ADMIN`).

## Reconciliación

Si la confirmación del pedido falla o se pierde su respuesta, el pago queda persistido con
`pedido_confirmado=false`. Un planificador reintenta periódicamente (`pagos.reconciliacion.enabled`,
`pagos.reconciliacion.intervalo-ms`, por defecto cada 30 s) confirmando los pendientes de forma idempotente,
sin duplicar pagos.

## Base local

Requiere Docker Desktop con el motor Linux funcionando.

```powershell
Copy-Item .env.example .env.local
docker compose --env-file .env.local up -d --wait
.\mvnw.cmd spring-boot:run
```

El puerto local de PostgreSQL es 5437; si se cambia `DB_PORT`, actualizar también `DB_URL`.

## Configuración

| Variable | Uso |
| --- | --- |
| `DB_URL` | URL JDBC de PostgreSQL; local: `jdbc:postgresql://localhost:5437/pedidos360_pagos`. |
| `DB_USERNAME` / `DB_PASSWORD` | Credenciales PostgreSQL (sin valor en el código). |
| `DB_NAME` / `DB_PORT` | Nombre de base y puerto del Compose local. |
| `SERVER_PORT` | Puerto HTTP; 8086 por defecto. |
| `PEDIDOS_SERVICE_URL` | URL interna de pedidos-service; 8085 por defecto. |

En AWS se configurarán `DB_URL`, `DB_USERNAME` y `DB_PASSWORD` para RDS desde el entorno de despliegue.

Flyway administra el esquema `pagos`; Hibernate usa `ddl-auto: validate`.

## Docker

El `Dockerfile` construye la imagen de despliegue (Java 21, usuario no root, healthcheck en `/actuator/health`). El `compose.yml` de esta carpeta levanta **solo PostgreSQL local** para desarrollo; no es el despliegue completo.

```powershell
docker build -t pedidos360-pagos:local .

docker run --rm -p 127.0.0.1:8086:8086 `
  -e DB_URL="jdbc:postgresql://host.docker.internal:5437/pedidos360_pagos" `
  -e DB_USERNAME=pedidos360_pagos -e DB_PASSWORD=... `
  -e PEDIDOS_SERVICE_URL="http://host.docker.internal:8085" `
  pedidos360-pagos:local
```

Notas:
- La imagen **no** incluye base de datos; en AWS se apunta a RDS con `DB_URL`, `DB_USERNAME` y `DB_PASSWORD`.
- `LOCAL_IDENTITY_ENABLED=false` en la imagen.
- La reconciliación Pagos → Pedidos requerirá **autenticación de servicio** (acuerdo con I1/I5); hoy la llamada interna no lleva credenciales de servicio.
- El build no ejecuta Testcontainers; verificar antes con `./mvnw verify`.

## Salud

```powershell
Invoke-RestMethod http://localhost:8086/actuator/health
```

## Seguridad

La validación del token la hace el BFF (I4). Este servicio aplica autorización sobre los pagos
(pertenencia del recurso). La identidad simulada (`pagos.identidad-local`) está **deshabilitada por
defecto** y solo aplica en el perfil `local` con loopback.
