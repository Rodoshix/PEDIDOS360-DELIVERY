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

## 4. Estado del pago (propuesta)

`enum EstadoPago`: `PENDIENTE, APROBADO, RECHAZADO`. Método: `TARJETA`, `EFECTIVO`. Pago **simulado**, sin datos bancarios reales.

- **Idempotencia:** registrar/confirmar un pago debe ser idempotente para evitar doble confirmación.
- Una transacción JPA local **no** hace atómica una llamada HTTP a otro servicio → definir recuperación.

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

### PagoRequest / PagoResponse (propuesta para pagos-service)

```json
{ "pedidoId": 500, "monto": 13980, "metodo": "TARJETA" }
```

```json
{
  "pagoId": 100, "pedidoId": 500, "usuarioId": 10,
  "monto": 13980, "metodo": "TARJETA", "estado": "APROBADO",
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

### Pagos (previstos)

| Método y ruta | Éxito | Errores |
|---|---|---|
| `POST /pagos` | 201 | 400, 401, 404, 409 (duplicado) |
| `GET /pagos/{id}` | 200 | 401, 404 |
| `GET /pagos/pedido/{pedidoId}` | 200 | 401, 404 |

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
