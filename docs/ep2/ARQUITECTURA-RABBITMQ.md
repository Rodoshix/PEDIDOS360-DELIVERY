# Arquitectura RabbitMQ — Pedidos360 EP2

## 1. Principio de diseño

RabbitMQ se incorpora para resolver un problema real ya existente: la coordinación entre el registro de un pago y la confirmación del pedido.

No se agregarán eventos artificiales solo para aumentar la cantidad de colas.

## 2. Golden Path

```text
Pagos
  │
  │ transacción PostgreSQL
  ├── guarda Pago elegible
  └── guarda comando en Outbox
          │
          ▼
PagoOutboxDispatcher
          │
          ▼
PagoConfirmacionPublisher
          │
          ▼
p360.pedidos.commands
          │ pedido.confirmar.v1
          ▼
p360.pedidos.confirmacion.q
          │
          ▼
PedidoConfirmacionConsumer
          │
          ▼
PedidoService.confirmarPorPago(pedidoId)
```

No debe existir confirmación HTTP automática paralela para pagos nuevos una vez realizado el corte al flujo RabbitMQ.

## 3. Topología principal

### Vhost

```text
pedidos360
```

### Exchanges

| Exchange | Tipo | Propósito |
|---|---|---|
| `p360.pedidos.commands` | direct | Comandos dirigidos a Pedidos |
| `p360.pedidos.retry` | direct | Etapas de retry con backoff |
| `p360.pedidos.dlx` | direct | Dead-letter de fallos definitivos o agotados |

Todos:
- durable;
- no auto-delete.

### Queues

| Queue | Binding |
|---|---|
| `p360.pedidos.confirmacion.q` | `p360.pedidos.commands` + `pedido.confirmar.v1` |
| `p360.pedidos.confirmacion.retry.5s.q` | `p360.pedidos.retry` + `pedido.confirmar.retry.5s` |
| `p360.pedidos.confirmacion.retry.30s.q` | `p360.pedidos.retry` + `pedido.confirmar.retry.30s` |
| `p360.pedidos.confirmacion.retry.120s.q` | `p360.pedidos.retry` + `pedido.confirmar.retry.120s` |
| `p360.pedidos.confirmacion.dlq` | `p360.pedidos.dlx` + `pedido.confirmar.failed` |

### Retry

```text
intento inicial
   ↓ fallo transitorio
retry 5 s
   ↓ fallo transitorio
retry 30 s
   ↓ fallo transitorio
retry 120 s
   ↓ fallo transitorio
DLQ
```

Las colas retry:
- no tienen consumer;
- usan TTL;
- al expirar vuelven a `p360.pedidos.commands`;
- routing key de retorno: `pedido.confirmar.v1`.

## 4. ACK/NACK

Modo:
```text
MANUAL
```

ACK únicamente después de:

- commit exitoso de la operación de Pedidos;
- resultado idempotente válido (pedido ya confirmado o posterior);
- transferencia confirmada a una cola de retry.

NACK sin requeue:

- pedido inexistente;
- pedido cancelado;
- mensaje inválido;
- versión/tipo desconocido;
- retries agotados.

`requeue=true` no debe utilizarse como retry normal de negocio.

## 5. Idempotencia

El consumer reutiliza:

```text
PedidoService.confirmarPorPago(pedidoId)
```

Comportamiento esperado:

- `CREADO` → `CONFIRMADO`;
- `CONFIRMADO` → éxito sin cambio;
- estados posteriores → éxito sin retroceso;
- `CANCELADO` → error definitivo;
- inexistente → error definitivo.

RabbitMQ trabaja con entrega al menos una vez. Los duplicados son esperables y deben ser seguros.

## 6. Transactional Outbox

Pago + intención de confirmación deben guardarse en la misma transacción.

Estados conceptuales:

```text
PENDING
IN_FLIGHT
PUBLISHED
BLOCKED
```

La publicación debe usar:

- publisher confirms;
- publisher returns;
- `mandatory=true`;
- `messageId` estable.

`PUBLISHED` significa recibido por el broker, no pedido confirmado.

## 7. Scheduler anterior

El scheduler HTTP existente debe dejar de confirmar pedidos directamente.

Su responsabilidad futura puede transformarse en:

```text
recuperar outbox pendiente / leases vencidos
```

No debe existir simultáneamente:

```text
HTTP automático + scheduler HTTP + RabbitMQ
```

para el mismo pago.

## 8. Configuración centralizada

Los nombres compartidos deben vivir bajo una configuración central:

```yaml
pedidos360:
  messaging:
    exchanges:
    queues:
    routing-keys:
    retries:
    dlx:
    dlq:
```

La conexión y credenciales van en:

```yaml
spring:
  rabbitmq:
```

alimentadas desde secretos/variables de entorno.

No hardcodear nombres en controllers/services/listeners.

## 9. Plataforma RabbitMQ

### Vhost negocio

```text
pedidos360
```

### Vhost RabbitAdmin sandbox

```text
pedidos360-admin-demo
```

El vhost sandbox no puede administrar/destruir la topología principal.

## 10. Cluster

El cluster aparece en la planificación propuesta, pero no como criterio literal confirmado de la pauta.

Decisión inicial:

- AWS: broker de un nodo para golden path.
- Cluster: perfil demostrativo separado si finalmente se exige.
- No desplegar 3 EC2 solo para la demo sin necesidad académica.

## 11. Decisiones que requieren aprobación del Integrante 1/5

No cambiar sin coordinación:

- nombres de exchanges;
- nombres de queues;
- routing keys;
- formato del mensaje;
- número/TTL de retries;
- semántica de ACK/NACK;
- mecanismo outbox;
- modo de corte HTTP → RabbitMQ;
- vhosts;
- contrato compartido entre Pagos y Pedidos.
