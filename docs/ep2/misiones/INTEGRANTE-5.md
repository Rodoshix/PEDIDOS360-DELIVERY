# Misión Integrante 5 — Integración, AWS y Release

## Objetivo

Integrar las piezas de EP2, desplegarlas de forma segura en AWS Academy y certificar que no rompen la Entrega 1.

## Rama sugerida

La integración se realiza desde ramas/PR aprobados hacia:

```text
develop
```

No desarrollar features grandes directamente en `develop`.

## Responsabilidades

- Revisar PR de Integrantes 1–4.
- Resolver conflictos.
- Confirmar compatibilidad de contratos.
- Ejecutar suite completa.
- Coordinar corte HTTP → RabbitMQ.
- Verificar pendientes históricos antes del corte.
- Medir EC2:
  - CPU/créditos;
  - RAM;
  - disco;
  - I/O;
  - uso por contenedor.
- Integrar RabbitMQ al stack AWS existente.
- Mantener infraestructura Entrega 1.
- Configurar secretos/credenciales fuera de Git.
- Validar persistencia.
- Validar acceso privado a Management/RabbitAdmin.
- Ejecutar E2E.
- Mantener rollback.
- Auditar contra pauta.
- Preparar release/evidencias finales.

## Restricciones

No:
- destruir infraestructura anterior;
- borrar RDS;
- ejecutar cambios irreversibles sin rollback;
- exponer RabbitMQ/Management a Internet;
- mezclar Fase 6 visual completa con EP2;
- aceptar PR sin tests/evidencia.

## Checklist de integración local

- [ ] Pagos compila/tests.
- [ ] Pedidos compila/tests.
- [ ] RabbitAdmin compila/tests.
- [ ] RabbitMQ platform levanta.
- [ ] Golden path funciona.
- [ ] Retry/DLQ funciona.
- [ ] Entrega 1 sigue funcionando.
- [ ] Frontend/BFF sin regresiones.

## Checklist AWS

- [ ] Recursos EC2 medidos.
- [ ] Broker agregado sin destruir stack.
- [ ] volumen persistente.
- [ ] secretos fuera de repositorio.
- [ ] puertos no públicos.
- [ ] producer conecta.
- [ ] consumer conecta.
- [ ] Management accesible solo por túnel/privado.
- [ ] tarjeta confirma pedido.
- [ ] efectivo pendiente confirma pedido.
- [ ] broker caído → outbox recupera.
- [ ] consumer caído → queue conserva.
- [ ] error definitivo → DLQ.
- [ ] reinicio broker conserva mensajes.
- [ ] regresión Perfil/Catálogo/Carrito/Pedidos/Pagos OK.

## Evidencia final

Consolidar:

- arquitectura;
- topología;
- contrato;
- pruebas;
- capturas;
- logs correlacionados;
- estado AWS;
- evidencia de DLQ;
- evidencia RabbitAdmin;
- plan de actividades;
- matriz de pauta.

## Cierre

Solo después de integración estable:
- recomendar merge/release;
- marcar issues Done;
- preparar defensa técnica.


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
