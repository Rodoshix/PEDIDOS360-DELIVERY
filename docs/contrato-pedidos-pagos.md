# Contrato — Pedidos y Pagos

> **Responsable:** Integrante 3.
> **Revisores/consumidores:** I1 (carrito/identidad), I2 (catálogo), I4 (logística/BFF/identidad interna), integrador.
> **Estado:** `pedidos-service` implementado y en `develop`; `pagos-service` pendiente.

## 1. Alcance

Define request/response, estados, transiciones y errores de **Pedidos** y **Pagos**. No define catálogo (I2), identidad/MSAL/BFF (I1/I4) ni reparto (I4).

## 2. Convenciones generales

- **Identidad:** los endpoints leen la identidad autenticada que provee la capa de seguridad (BFF/I4). Un `usuarioId` enviado por el cliente **no** se considera identidad confiable.
- **Precio:** no se acepta precio/total desde el cliente; se resuelve en backend al crear el pedido y se guarda una **copia del precio aplicado en la compra**.
- **Moneda:** `CLP` (*pendiente acordar* unidad: valor entero vs céntimos).
- **Autorización:** además del rol, se comprueba la **pertenencia** del recurso a la identidad autenticada.

## 3. Estado del pedido

`enum EstadoPedido`: `CREADO, CONFIRMADO, PREPARANDO, LISTO, EN_REPARTO, ENTREGADO, CANCELADO`.

Estados terminales: `ENTREGADO`, `CANCELADO`.

### 3.1 Matriz de transiciones (implementada)

| Desde | Hacia permitidos |
|---|---|
| CREADO | CONFIRMADO, CANCELADO |
| CONFIRMADO | PREPARANDO, CANCELADO |
| PREPARANDO | LISTO, CANCELADO |
| LISTO | EN_REPARTO |
| EN_REPARTO | ENTREGADO |
| ENTREGADO | — |
| CANCELADO | — |

**NO permitido:** `CREADO → ENTREGADO` (salto directo); modificar un pedido `CANCELADO` o `ENTREGADO`.

- Una transición inválida devuelve **409** (`TransicionInvalidaException`).
- *Pendiente de acuerdo:* cuándo el pago aprobado confirma el pedido; flujo `EFECTIVO`; correspondencia `EN_REPARTO` (Pedidos) ↔ `EN_CAMINO` (Repartidores, I4).

## 4. Estado del pago (implementado)

`enum EstadoPago`: `PENDIENTE, APROBADO, RECHAZADO`. Método: `TARJETA`, `EFECTIVO`. Pago **simulado**, sin datos bancarios reales.

- **Idempotencia con alcance por identidad:** la `Idempotency-Key` se interpreta por usuario. Un reintento idéntico devuelve el mismo pago; la misma clave con otro pedido o método responde **409**.
- **Un pago activo por pedido:** garantizado en PostgreSQL con índice único parcial sobre `pedido_id` para `PENDIENTE`/`APROBADO`; el conflicto se traduce a **409**.
- **Autorización:** registrar/consultar exige pertenencia del pedido a la identidad (o rol `ADMIN`); aprobar un cobro exige permiso explícito (`REPARTIDOR` o `ADMIN`).
- **Coordinación recuperable:** el pago se persiste antes de confirmar el pedido. Si la confirmación falla o se pierde la respuesta, el pago queda con `pedido_confirmado=false` y un proceso de reconciliación lo reintenta de forma idempotente.
- **Confirmación verificada:** un 400/409 de Pedidos no se interpreta automáticamente como éxito; se consulta el estado real del pedido y solo se acepta si ya está `CONFIRMADO` o en un estado posterior. Un pedido `CANCELADO` deja la coordinación pendiente, no confirmada.

## 5. DTOs (implementados en pedidos)

### CrearPedidoRequest

```json
{
  "restauranteId": 20,
  "direccionEntrega": "Av. Ejemplo 123",
  "items": [{ "productoId": 101, "cantidad": 2 }]
}
```

> El `usuarioId` **no** se envía: se deriva de la identidad autenticada.
> `productoId` referencia `Producto.id` de I2 — **no renombrar** el DTO de catálogo. El precio se resuelve en backend (hoy mock).

### PedidoResponse

```json
{
  "pedidoId": 500,
  "usuarioId": 10,
  "restauranteId": 20,
  "direccionEntrega": "Av. Ejemplo 123",
  "estado": "CREADO",
  "total": 13980,
  "moneda": "CLP",
  "fechaCreacion": "2026-09-04T12:00:00Z",
  "lineas": [
    { "lineaId": 1, "productoId": 101, "cantidad": 2, "precioUnitario": 6990, "subtotal": 13980 }
  ]
}
```

### CambiarEstadoRequest

```json
{ "estado": "PREPARANDO" }
```

### PagoRequest / PagoResponse (implementados en pagos-service)

`POST /pagos` con cabecera opcional `Idempotency-Key` (si falta, se genera una). El `usuarioId` no se envía: se deriva de la identidad.

```json
{ "pedidoId": 500, "metodo": "TARJETA" }
```

```json
{
  "pagoId": 100, "pedidoId": 500, "usuarioId": 10,
  "monto": 13980, "moneda": "CLP", "metodo": "TARJETA", "estado": "APROBADO",
  "fecha": "2026-09-04T12:05:00Z"
}
```

## 6. Endpoints

### Pedidos (implementados)

| Método y ruta | Éxito | Errores |
|---|---|---|
| `POST /pedidos` | 201 | 400, 401, 403 |
| `GET /pedidos` | 200 | 401 |
| `GET /pedidos/{id}` | 200 | 401, 404 |
| `GET /usuarios/{id}/pedidos` | 200 | 401, 404 |
| `PUT /pedidos/{id}/estado` | 200 | 400, 401, 404, 409 |

### Pagos (implementados)

| Método y ruta | Éxito | Errores |
|---|---|---|
| `POST /pagos` | 201 | 400, 401, 403, 404, 409 |
| `GET /pagos/{id}` | 200 | 401, 403, 404 |
| `GET /pagos/pedido/{pedidoId}` | 200 | 401, 403, 404 |
| `PUT /pagos/{id}/aprobar` | 200 | 401, 403, 404, 409 |

- **403** en pagos: sin pertenencia del pedido a la identidad, o sin permiso para aprobar cobros.
- **409** en `POST /pagos`: pago activo duplicado o reutilización de `Idempotency-Key` para otra operación.

## 7. Estructura de error

Se usa `application/problem+json` (RFC 7807, `ProblemDetail`).

- **400** validación de datos.
- **401** sin identidad / token inválido (validación central en BFF/I4).
- **403** sin permiso sobre el recurso.
- **404** recurso inexistente.
- **409** conflicto (transición de estado inválida).

## 8. Entregable a I4 (pedido `LISTO`)

```json
{
  "pedidoId": 500,
  "usuarioId": 10,
  "estado": "LISTO",
  "direccionEntrega": "Av. Ejemplo 123",
  "total": 13980
}
```

## 9. Decisiones pendientes (no bloquean el desarrollo con mocks)

1. Moneda: unidad (valor entero vs céntimos) y redondeo.
2. ¿Un restaurante por pedido como regla del MVP?
3. Flujo `EFECTIVO` vs `TARJETA` (¿cuándo se confirma el pedido?).
4. Estados de pago (¿`ANULADO`/`REEMBOLSADO`?) y reintentos permitidos.
5. Correspondencia `EN_REPARTO` ↔ `EN_CAMINO` con I4.
6. Propagación de identidad BFF → servicios (con I4).
7. Origen del precio real (integración con catálogo de I2).
