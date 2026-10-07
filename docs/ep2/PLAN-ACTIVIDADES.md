# Plan de Actividades — Pedidos360 EP2

> El seguimiento oficial de EP2 se realiza mediante GitHub Issues, Pull Requests, este plan versionado y ramas de trabajo por integrante cuando corresponda.

## Estados sugeridos

```text
Backlog
Ready
In Progress
Review
Testing
Done
```

## Actividades

| Issue | Actividad | Responsable | Dependencias | Estado inicial |
|---|---|---|---|---|
| [#64](https://github.com/Rodoshix/PEDIDOS360-DELIVERY/issues/64) | Arquitectura RabbitMQ y contrato de mensajería | Integrante 1 | — | Done |
| [#65](https://github.com/Rodoshix/PEDIDOS360-DELIVERY/issues/65) | Transactional Outbox y Publisher en Pagos | Integrante 1 | #64 | Done |
| [#66](https://github.com/Rodoshix/PEDIDOS360-DELIVERY/issues/66) | Consumer principal de confirmación en Pedidos | Integrante 1 | #64 | Done |
| [#67](https://github.com/Rodoshix/PEDIDOS360-DELIVERY/issues/67) | RabbitAdmin | Integrante 2 | #64 / #69 | Ready |
| [#68](https://github.com/Rodoshix/PEDIDOS360-DELIVERY/issues/68) | ACK/NACK, Retry, DLX/DLQ y pruebas de fallos | Integrante 3 | #66; policies de plataforma coordinadas con #69 | Done — PR #84 integrado |
| [#69](https://github.com/Rodoshix/PEDIDOS360-DELIVERY/issues/69) | Plataforma RabbitMQ | Integrante 4 | #64 / adenda PR #83 / núcleo PR #84; coordinar #77 | Review — plataforma local en esta rama, sin merge |
| [#70](https://github.com/Rodoshix/PEDIDOS360-DELIVERY/issues/70) | Integración y corte HTTP → RabbitMQ | Integrante 5 | #65 / #66 / #68 / #69 / #77–#82 | Backlog |
| [#71](https://github.com/Rodoshix/PEDIDOS360-DELIVERY/issues/71) | Integración AWS | Integrante 5 | #70 | Backlog |
| [#72](https://github.com/Rodoshix/PEDIDOS360-DELIVERY/issues/72) | Pruebas E2E y regresión de Entrega 1 | Integrante 3 + Integrante 5 | #70 / #71 | Backlog |
| [#73](https://github.com/Rodoshix/PEDIDOS360-DELIVERY/issues/73) | Cluster demostrativo, solo si se confirma como requisito | Integrante 4 | Confirmación explícita del requisito / #69 | Backlog |
| [#74](https://github.com/Rodoshix/PEDIDOS360-DELIVERY/issues/74) | Defensa técnica y evidencias finales | Todos; integrador: Integrante 5 | #67 / #72 / #73 (solo si se confirma obligatorio) | Backlog |


## Hitos internos propuestos

Las fechas deben ajustarse al calendario real del grupo.

### Hito A — Arquitectura cerrada
- topología;
- contrato;
- ownership;
- issues/ramas.

### Hito B — Desarrollo paralelo
- I1: outbox/publisher/consumer;
- I2: RabbitAdmin;
- I3: reliability/tests;
- I4: broker/plataforma.

### Hito C — Integración local
- merge controlado;
- RabbitMQ real;
- Testcontainers;
- happy path;
- fallos.

### Hito D — AWS
- medir recursos;
- desplegar broker;
- secretos;
- persistencia;
- validación E2E.

### Hito E — Entrega
- regresión;
- evidencias;
- defensa;
- plan actualizado;
- revisión contra pauta.

## Actualización del plan

Al inicio de cada clase hasta la fecha indicada por el docente:

- actualizar el seguimiento en GitHub Issues y Pull Requests;
- actualizar este archivo si corresponde;
- conservar evidencia/captura del estado;
- no marcar `Done` sin PR integrado y pruebas.

## Definition of Done de una actividad

- [ ] Implementación.
- [ ] Tests.
- [ ] Documentación.
- [ ] Evidencia.
- [ ] PR.
- [ ] Review.
- [ ] Integración comprobada.

## Trazabilidad de esta incorporación

- Los números de la tabla corresponden a issues reales; nuevos issues OPEN sin assignee por ausencia de mapeo confirmado.
- #64 CLOSED/COMPLETED: documentación integrada por PR #75; #65/#66 CLOSED/COMPLETED: núcleo integrado por PR #76 (22f5c4d). Los estados Ready/Backlog restantes son planificación, no implementación comprobada.
- Ready expresa el estado inicial planificado; deben satisfacerse las dependencias antes de implementar o integrar la pieza correspondiente.
- #73 permanece condicionado a la confirmación explícita del requisito de cluster; no autoriza desplegarlo.
- No se crean ramas de integrantes en esta incorporación. Los nombres en misiones/issues son recomendaciones futuras.

## Ampliación posterior: seis microservicios

Fuente: [adenda aprobada](ADENDA-RABBITMQ-6-SERVICIOS.md). Objetivo 21 queues/7 exchanges; sin implementación en este PR.

| Issue | Actividad | Responsable | Dependencias | Estado inicial |
|---|---|---|---|---|
| [#77](https://github.com/Rodoshix/PEDIDOS360-DELIVERY/issues/77) | EP2-12 — Base común request/reply y reliability simple | Ejecución por confirmar; coordinación I1/I5 | Documentación aprobada en este PR; coordinación con #69 para pruebas/plataforma. No depende de consumers posteriores. | Backlog |
| [#78](https://github.com/Rodoshix/PEDIDOS360-DELIVERY/issues/78) | EP2-13 — Usuarios RabbitMQ: consulta de perfil actual | Ejecución por confirmar; coordinación I1/I5 | #77; #69; documentación aprobada. | Backlog |
| [#79](https://github.com/Rodoshix/PEDIDOS360-DELIVERY/issues/79) | EP2-14 — Restaurantes RabbitMQ: consulta de listado | Ejecución por confirmar; coordinación I1/I5 | #77; #69; documentación aprobada. | Backlog |
| [#80](https://github.com/Rodoshix/PEDIDOS360-DELIVERY/issues/80) | EP2-15 — Productos RabbitMQ: disponibles por restaurante | Ejecución por confirmar; coordinación I1/I5 | #77; #69; documentación aprobada. | Backlog |
| [#81](https://github.com/Rodoshix/PEDIDOS360-DELIVERY/issues/81) | EP2-16 — Pagos RabbitMQ query: consulta por ID | Ejecución por confirmar; coordinación I1/I5 | #77; #69; #65 completado; documentación aprobada. | Backlog |
| [#82](https://github.com/Rodoshix/PEDIDOS360-DELIVERY/issues/82) | EP2-17 — Carrito RabbitMQ y coordinación desde Pedidos | Ejecución por confirmar; coordinación I1/I5 | #77 para base de mensajería/handoff simple; #69 plataforma suficiente; contrato/vinculación aprobados. Outbox de Pedidos es entregable de este issue, no dependencia de sí mismo. | Backlog |

#68 conserva reliability avanzada de Pedidos. #69 plataforma ampliada; #70 cortes graduales. #77 entrega base simple separada. No reasignar misiones anteriores sin confirmar responsable.

Estado 7 de octubre de 2026: #68 CLOSED/COMPLETED, PR #84 integrado en ff40c7d;
#69 integrado mediante PR #85 (d96e247), con plataforma local y evidencia en
[PLATAFORMA-RABBITMQ.md](PLATAFORMA-RABBITMQ.md). #77–#82 conservan alcance y
dependencias; #70 no fue iniciado ni se cambiaron flags del stack. AWS final en #71.

Nota de avance: #77 está implementado y en revisión mediante PR #86 (rama `feature/ep2-rabbit-request-reply`). La base común vive en `backend/shared/p360-messaging-core` y su diseño implementado está en [REQUEST-REPLY-RABBITMQ.md](REQUEST-REPLY-RABBITMQ.md). #78 a #81 siguen pendientes: deben aportar su procesador de dominio, `QueryInvoker` y su prueba extremo a extremo. La declaración operativa de la topología pertenece a #69: #77 no declara argumentos de TTL/DLX que ya son propiedad de las policies de plataforma.

Orden: adenda integrada → #77 y #69 coordinados → #78/#79/#80/#81; #68 en paralelo. #82 requiere contrato/vinculación revisados, base #77 y plataforma suficiente #69; su outbox es entregable propio. #70 depende de todas las piezas para cierre completo. Las ramas de los issues son sugerencias futuras; no creadas aquí. Seguimiento mediante Issues, PRs y este plan.
