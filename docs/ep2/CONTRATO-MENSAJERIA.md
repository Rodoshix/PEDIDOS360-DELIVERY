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
