# Pedidos360 — Evaluación Parcial 2 (EP2)

## Objetivo

Incorporar RabbitMQ al sistema existente sin romper la lógica funcional de Pedidos360.

La implementación debe demostrar mensajería desacoplada, manejo explícito de errores, ACK/NACK, retry, DLX/DLQ, administración de RabbitMQ y pruebas funcionales en el contexto de AWS + Entra.

## Golden Path aprobado

```text
Cliente
  ↓
POST /pagos
  ↓
Pagos
  ├─ persiste Pago
  ├─ persiste Outbox
  └─ responde HTTP
        ↓
Outbox Dispatcher
        ↓
RabbitMQ
        ↓
Pedidos Consumer
        ↓
PedidoService.confirmarPorPago()
```

Semántica que debe preservarse:

- `TARJETA / APROBADO` solicita confirmar el pedido.
- `EFECTIVO / PENDIENTE` también solicita confirmar el pedido.
- `CONFIRMADO` significa que el pedido puede continuar su operación.
- `CONFIRMADO` no implica necesariamente que el dinero ya fue cobrado.
- Registro de pago, autorización y consultas principales siguen por HTTP.

## Responsables

| Integrante | Responsabilidad principal |
|---|---|
| Integrante 1 | Núcleo RabbitMQ: outbox, publisher, consumer principal y contrato |
| Integrante 2 | Microservicio RabbitAdmin |
| Integrante 3 | ACK/NACK, retry, DLX/DLQ, pruebas de fallos y replay |
| Integrante 4 | Plataforma RabbitMQ: Docker, vhosts, usuarios, permisos, persistencia, health y cluster demo |
| Integrante 5 | Integración, AWS, E2E, revisión de PR, release y auditoría final |

> Integrante 1 e Integrante 5 corresponden a la misma persona, pero se mantienen como roles separados para distinguir desarrollo del núcleo e integración global.

## Documentos fuente de verdad

Antes de implementar, todos deben revisar:

1. [ARQUITECTURA-RABBITMQ.md](ARQUITECTURA-RABBITMQ.md)
2. [CONTRATO-MENSAJERIA.md](CONTRATO-MENSAJERIA.md)
3. Su archivo en `misiones/`
4. Código actual del repositorio
5. Issue asignado

Si existe contradicción entre documentación antigua y estos documentos, se debe detener el trabajo y consultar al Integrante 1/5.

## Reglas comunes

- No modificar contratos compartidos sin aprobación.
- No cambiar nombres de exchanges, queues o routing keys unilateralmente.
- No hacer merge directo a `develop`.
- No cerrar issues automáticamente.
- No ampliar el alcance por decisión propia o de una IA.
- Cada trabajo debe incluir código, tests, documentación y evidencia.
- Todo PR debe indicar pruebas ejecutadas, riesgos y pendientes.
- No incluir secretos, tokens, passwords o credenciales en Git.
- Mantener compatibilidad con Entra, AWS y funcionalidades de Entrega 1.

## Definition of Done común

Una misión solo se considera terminada cuando cumple:

- [ ] Código implementado.
- [ ] Tests correspondientes aprobados.
- [ ] Sin regresiones relevantes.
- [ ] Documentación de la pieza actualizada.
- [ ] Evidencias preparadas.
- [ ] PR abierto hacia `develop`.
- [ ] Review del Integrante 1/5.
- [ ] Sin secretos ni artefactos locales versionados.

## Organización y seguimiento

La planificación fue integrada mediante PR #75. El núcleo de #65/#66 está implementado en la rama `feature/ep2-rabbit-core`, sujeto a revisión; el corte y la política completa de fallos siguen pendientes.

- [Implementación, convivencia HTTP y evidencia del núcleo](NUCLEO-RABBITMQ.md).
- [Plan de actividades e issues reales](PLAN-ACTIVIDADES.md).
- Misiones: [Integrante 1](misiones/INTEGRANTE-1.md), [Integrante 2](misiones/INTEGRANTE-2.md), [Integrante 3](misiones/INTEGRANTE-3.md), [Integrante 4](misiones/INTEGRANTE-4.md) e [Integrante 5](misiones/INTEGRANTE-5.md).
- Las ramas restantes se crean al iniciar la misión correspondiente. El seguimiento usa Issues, PRs y el plan versionado.
