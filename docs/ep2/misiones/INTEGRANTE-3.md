# Misión Integrante 3 — Confiabilidad: ACK/NACK, Retry, DLQ y pruebas

## Objetivo

Implementar y validar la política de confiabilidad del golden path aprobado.

## Rama sugerida

```text
feature/ep2-rabbit-reliability
```

## Alcance

- ACK manual.
- NACK explícito.
- Retry escalonado:
  - 5 s;
  - 30 s;
  - 120 s.
- DLX.
- DLQ.
- Clasificación de errores.
- Replay manual controlado.
- Testcontainers RabbitMQ.
- Pruebas de fallos y duplicados.

## Política obligatoria

### ACK
Solo después de:
- commit exitoso;
- éxito idempotente;
- transferencia confirmada a retry.

### Error definitivo
NACK sin requeue → DLQ:
- pedido inexistente;
- pedido cancelado;
- JSON inválido;
- IDs inválidos;
- versión/tipo desconocido;
- retries agotados.

### Error transitorio
Enviar a retry correspondiente.

## Casos de prueba mínimos

- [ ] happy path tarjeta;
- [ ] happy path efectivo pendiente;
- [ ] mensaje duplicado;
- [ ] pedido ya confirmado;
- [ ] pedido en estado posterior;
- [ ] pedido cancelado;
- [ ] pedido inexistente;
- [ ] JSON inválido;
- [ ] versión inválida;
- [ ] consumer caído;
- [ ] RabbitMQ caído;
- [ ] DB Pedidos caída;
- [ ] caída después del commit antes del ACK;
- [ ] fallo permanente → DLQ;
- [ ] reinicio RabbitMQ con mensajes pendientes;
- [ ] replay manual;
- [ ] publicación retry fallida;
- [ ] redelivery repetida.

## Puede modificar

Principalmente:

```text
backend/services/pedidos-service/
tests relacionados con messaging/
docs/ep2/
```

Cambios funcionales al consumer deben coordinarse con Integrante 1.

## No puede cambiar unilateralmente

- contrato V1;
- nombres de topología;
- número de retries;
- TTL;
- semántica de estados;
- outbox;
- publisher principal.

## Evidencias

Preparar tabla:

```text
caso
resultado esperado
resultado obtenido
evidencia
```

Guardar capturas/logs apropiados y documentación de replay.

## Criterios de aceptación

- [ ] ACK/NACK demostrables.
- [ ] retry 5/30/120 funciona.
- [ ] DLQ recibe fallos definitivos o agotados.
- [ ] duplicados no duplican efecto.
- [ ] no hay loop infinito por requeue.
- [ ] replay no pierde mensaje si republicación falla.
- [ ] suite con RabbitMQ real de prueba.
- [ ] documentación completa de fallos.


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

#68 sigue exclusivamente sobre reliability avanzada de Pedidos (5/30/120, ACK/NACK, clasificación, DLQ y replay). No absorbe consumers adicionales. Reliability simple/request-reply tiene issue propio y ownership de ejecución por confirmar.

Fuente: [adenda](../ADENDA-RABBITMQ-6-SERVICIOS.md) y [plan](../PLAN-ACTIVIDADES.md). Esta actualización no implementa ni crea ramas de trabajo.

### Trazabilidad de trabajos adicionales

Base común #77; Usuarios #78; Restaurantes #79; Productos #80; Pagos query #81; Carrito/Pedidos #82. Responsables de ejecución nuevos por confirmar; coordinación I1/I5. #68 sigue separado; #69 plataforma; #70 integración.

## Entrega #68: política real y dependencias

Ver [RELIABILITY-RABBITMQ.md](../RELIABILITY-RABBITMQ.md). Sustituye el bean provisional; pruebas con RabbitMQ/PostgreSQL y herramienta Java de replay manual. Las policies/tipos compatibles quedan a #69; listener platform-ready=false y modo HTTP por defecto hasta validación/corte en #70. No incluye consumers ni base simple #77–#82.
