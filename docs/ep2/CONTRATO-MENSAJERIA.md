# Contrato de Mensajería — Pedidos360 EP2

## Comando principal

Nombre lógico:

```text
ConfirmarPedidoPorPago
```

Versión:

```text
1
```

Routing key:

```text
pedido.confirmar.v1
```

## Payload

```json
{
  "messageId": "7cbd68bf-bcf7-49dd-b1e2-8e15a7a0a681",
  "type": "ConfirmarPedidoPorPago",
  "version": 1,
  "occurredAt": "2026-10-07T01:30:00Z",
  "pedidoId": 500,
  "pagoId": 100
}
```

## Reglas

| Campo | Regla |
|---|---|
| `messageId` | UUID estable para publicación y retries |
| `type` | Exactamente `ConfirmarPedidoPorPago` |
| `version` | Entero, inicialmente `1` |
| `occurredAt` | Timestamp UTC de creación |
| `pedidoId` | Entero positivo |
| `pagoId` | Entero positivo |

## Propiedades AMQP

- `message_id`: mismo valor que `messageId`.
- `content_type`: `application/json`.
- mensaje persistente.
- `app_id`: `pagos-service`.
- header `retry-count`: inicia en `0`.
- replay manual puede agregar metadatos de operación sin cambiar `messageId`.

## Datos que NO deben incluirse

No incluir:

- JWT;
- Bearer token;
- usuario;
- dirección;
- lista de productos;
- monto;
- moneda;
- método de pago;
- secretos;
- credenciales;
- estado completo del pago.

El producer ya determinó la elegibilidad antes de publicar.

## Producer

Responsable:

```text
pagos-service
```

La intención se crea cuando el pago persistido queda:

```text
PENDIENTE
o
APROBADO
```

Esto preserva la lógica actual de efectivo y tarjeta.

## Consumer

Responsable:

```text
pedidos-service
```

El consumer no necesita consultar Pagos para ejecutar la confirmación.

## Compatibilidad

Cambiar cualquiera de estos campos o semánticas requiere:

1. propuesta explícita;
2. revisión del Integrante 1/5;
3. actualización de contrato;
4. adaptación coordinada de producer + consumer;
5. pruebas de compatibilidad.

Una IA o integrante no debe agregar campos “por conveniencia” sin coordinación.

## Contratos adicionales de la ampliación aprobada

El payload estricto ConfirmarPedidoPorPago V1 y sus exclusiones anteriores permanecen intactos. Las siguientes son fronteras nuevas independientes; consultar [adenda](ADENDA-RABBITMQ-6-SERVICIOS.md) para destinos y reglas operativas.

| Type nuevo, version=1 | Parámetros de negocio mínimos | Routing key |
|---|---|---|
| ConsultarUsuarioActual | Sin ID de perfil; actor verificable determina identidad/tenant | usuario.consultar-actual.v1 |
| ListarRestaurantes | Sin parámetros de negocio adicionales | restaurante.listar.v1 |
| ListarProductosDisponibles | restauranteId positivo | producto.listar-disponibles.v1 |
| ConsultarPago | pagoId positivo; actor verificable para pertenencia | pago.consultar.v1 |

Envelope de consultas: messageId UUID estable, type, version, occurredAt UTC, expiresAt UTC y parámetros de la tabla. correlationId/replyTo son propiedades AMQP, junto con message_id coincidente, content_type, app_id y retry-count. Timeout inicial 5 s; un retry corto no renueva expiresAt. Respuesta correlacionada con resultado o error equivalente al HTTP; ACK solo tras confirm positivo sin return. Errores HTTP esperados generan respuesta; inválidos/técnicos agotados/vencidos se diagnostican y van a DLQ. La forma JSON exacta de respuesta y del contexto firmado se especificará/revisará en el issue de base antes de consumers.

Contexto de actor verificable separado del comando de pagos: preservar tenant, identidad, roles/pertenencia y perfil activo; no transportar JWT original ni confiar en IDs/roles sin autenticidad. Restringir replyTo. Nunca reutilizar estos campos en ConfirmarPedidoPorPago V1.

### VaciarCarritoPorPedido V1

Campos: messageId UUID estable, type=VaciarCarritoPorPedido, version=1, occurredAt UTC, pedidoId positivo, carritoId positivo, expectedCarritoVersion no negativa y referencia verificable de propietario/tenant. No productos, precios, JWT ni secretos. La serialización de la referencia y obtención verificada del snapshot se documentan en el issue de Carrito antes de implementación.

Pedido + outbox se guardan atómicamente. Deduplicación persistente y verificación concurrente de versión: coincidente → vaciar/commit/ACK; duplicado → ACK; versión posterior → omitir/registrar/ACK; vínculo inválido → DLQ; temporal → un retry corto. No usar vaciar() dependiente de identidad HTTP como consumer directo. No cambiar contratos HTTP existentes sin revisión explícita durante #70.
