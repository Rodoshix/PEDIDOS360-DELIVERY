# Pagos Service

Servicio de pagos simulados de Pedidos360. Java 21, Spring Boot 4.1.1 y Maven Wrapper 3.9.15.

## Estado

Implementados: entidad Pago + EstadoPago/MetodoPago, repositorio JPA, migración Flyway para PostgreSQL,
registro de pago simulado con **idempotencia**, consultas y coordinación con Pedidos.

Reglas principales:
- **Pago simulado**: no se almacenan datos bancarios reales.
- **Idempotencia**: una misma `claveIdempotencia` no genera un segundo pago.
- **Un pago activo por pedido**: si el pedido ya tiene un pago pendiente o aprobado, se responde 409.
- El **monto** se toma del pedido; no se confía en un monto enviado por el cliente.
- El **usuarioId** se resuelve desde la identidad autenticada, no desde el cuerpo.

## Flujo de pago

- **TARJETA**: se resuelve al registrar (APROBADO/RECHAZADO). Si se aprueba, se confirma el pedido.
- **EFECTIVO**: queda **PENDIENTE** ("por cobrar") y el pedido se confirma igual para preparación/despacho;
  pasa a **APROBADO** al entregar.

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

## Salud

```powershell
Invoke-RestMethod http://localhost:8086/actuator/health
```

## Seguridad

La validación del token la hace el BFF (I4). Este servicio aplica autorización sobre los pagos
(pertenencia del recurso). La identidad simulada (`pagos.identidad-local`) está **deshabilitada por
defecto** y solo aplica en el perfil `local` con loopback.
