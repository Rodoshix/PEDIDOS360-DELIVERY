# Misión Integrante 1 — Núcleo RabbitMQ

## Objetivo

Implementar el núcleo funcional del golden path RabbitMQ:

```text
Pago persistido
→ Transactional Outbox
→ Publisher
→ RabbitMQ
→ Consumer en Pedidos
→ confirmación idempotente
```

## Rama sugerida

```text
feature/ep2-rabbit-core
```

## Responsabilidades

- Implementar transactional outbox en `pagos-service`.
- Persistir Pago + Outbox en la misma transacción.
- Implementar dispatcher de outbox.
- Implementar publisher con:
  - `mandatory=true`;
  - publisher confirms;
  - publisher returns;
  - messageId estable.
- Implementar consumer principal en `pedidos-service`.
- Reutilizar `PedidoService.confirmarPorPago`.
- Mantener la semántica actual:
  - tarjeta aprobada confirma;
  - efectivo pendiente también confirma.
- Preparar el corte para dejar de usar confirmación HTTP automática.
- Mantener compatibilidad temporal del endpoint interno si todavía se necesita.
- Coordinar contrato y topología con Integrante 3 y 4.

## Puede modificar

Principalmente:

```text
backend/services/pagos-service/
backend/services/pedidos-service/
docs/ep2/
```

## No debe modificar sin coordinación

- RabbitAdmin del Integrante 2.
- Infraestructura/plataforma del Integrante 4.
- Fase visual completa de Pedidos/Pagos.
- Contratos compartidos ya aprobados sin registrar decisión.

## Criterios de aceptación

- [ ] Pago y outbox se guardan atómicamente.
- [ ] Reintento HTTP con misma Idempotency-Key no duplica comando lógico.
- [ ] Dispatcher recupera outbox pendiente después de reinicio.
- [ ] Publisher usa confirms + returns.
- [ ] Mensaje cumple contrato V1.
- [ ] Consumer procesa `CREADO → CONFIRMADO`.
- [ ] Pedido ya confirmado/posterior no retrocede.
- [ ] Pedido cancelado/inexistente se clasifica como definitivo.
- [ ] No existe doble coordinación automática HTTP + RabbitMQ para pagos nuevos.
- [ ] Tests unitarios/integración correspondientes pasan.
- [ ] Documentación del núcleo actualizada.

## Implementación de #65/#66

Consultar [NUCLEO-RABBITMQ.md](../NUCLEO-RABBITMQ.md) para clases, configuración temporal, recuperación, pruebas y puntos de integración con #68/#70. Integrado por PR #76; el corte definitivo sigue pendiente.

## Evidencia mínima

- test Pago + Outbox en una transacción;
- publicación real contra RabbitMQ de prueba;
- consumo real;
- redelivery seguro;
- logs correlacionados por `messageId`.


## Regla de trabajo

Este documento define el alcance de la misión.

Antes de modificar código:

1. Revisar `docs/ep2/ARQUITECTURA-RABBITMQ.md`.
2. Revisar `docs/ep2/CONTRATO-MENSAJERIA.md`.
3. Inspeccionar el código actual.
4. Revisar el issue asignado.
5. No asumir que documentación antigua refleja el estado actual.

Si la misión requiere cambiar un contrato compartido o un componente asignado a otro integrante, detenerse y comunicarlo al Integrante 1/5.

## Para asistentes de IA

- No ampliar alcance automáticamente.
- No refactorizar código ajeno a la misión.
- No cambiar contratos compartidos.
- No sustituir decisiones de arquitectura.
- No agregar dependencias sin justificar.
- No hacer merge automático.
- No cerrar issues automáticamente.
- No versionar secretos.
- Antes de finalizar, reportar:
  - archivos modificados;
  - tests ejecutados;
  - riesgos;
  - pendientes;
  - evidencias.

## Ampliación docente posterior aprobada

El núcleo #65/#66 está integrado por PR #76. Coordinar nuevos contratos, autorización y vinculación Pedido–Carrito según adenda; las implementaciones adicionales van en issues separados con responsable por confirmar. No ampliar retrospectivamente #65/#66.

Fuente: [adenda](../ADENDA-RABBITMQ-6-SERVICIOS.md) y [plan](../PLAN-ACTIVIDADES.md). Esta actualización no implementa ni crea ramas de trabajo.

### Trazabilidad de trabajos adicionales

Base común #77; Usuarios #78; Restaurantes #79; Productos #80; Pagos query #81; Carrito/Pedidos #82. Responsables de ejecución nuevos por confirmar; coordinación I1/I5. #68 sigue separado; #69 plataforma; #70 integración.
