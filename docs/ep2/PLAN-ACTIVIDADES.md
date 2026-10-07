# Plan de Actividades — Pedidos360 EP2

> Este documento es la copia versionada del plan. GitHub Project debe reflejar el estado vivo.

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
| [#65](https://github.com/Rodoshix/PEDIDOS360-DELIVERY/issues/65) | Transactional Outbox y Publisher en Pagos | Integrante 1 | #64 | Ready |
| [#66](https://github.com/Rodoshix/PEDIDOS360-DELIVERY/issues/66) | Consumer principal de confirmación en Pedidos | Integrante 1 | #64 | Ready |
| [#67](https://github.com/Rodoshix/PEDIDOS360-DELIVERY/issues/67) | RabbitAdmin | Integrante 2 | #64 / #69 | Ready |
| [#68](https://github.com/Rodoshix/PEDIDOS360-DELIVERY/issues/68) | ACK/NACK, Retry, DLX/DLQ y pruebas de fallos | Integrante 3 | #66 / #69 | Ready |
| [#69](https://github.com/Rodoshix/PEDIDOS360-DELIVERY/issues/69) | Plataforma RabbitMQ | Integrante 4 | #64 | Ready |
| [#70](https://github.com/Rodoshix/PEDIDOS360-DELIVERY/issues/70) | Integración y corte HTTP → RabbitMQ | Integrante 5 | #65 / #66 / #68 / #69 | Backlog |
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

- actualizar GitHub Project;
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

- Los números de la tabla corresponden a issues reales; todos se crean OPEN y sin assignee por ausencia de mapeo GitHub confirmado.
- Se conservan los estados iniciales previstos. Done en #64 corresponde al diseño aprobado; la integración de su documentación y el cierre del issue siguen pendientes de revisión/merge.
- Ready expresa el estado inicial planificado; deben satisfacerse las dependencias antes de implementar o integrar la pieza correspondiente.
- #73 permanece condicionado a la confirmación explícita del requisito de cluster; no autoriza desplegarlo.
- No se crean ramas de integrantes en esta incorporación. Los nombres en misiones/issues son recomendaciones futuras.
- GitHub Project no pudo consultarse/sincronizarse con la credencial disponible: falta read:project; una actualización requerirá permiso de escritura project. No se afirma que su estado vivo coincida con esta tabla.