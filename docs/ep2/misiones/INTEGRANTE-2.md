# Misión Integrante 2 — RabbitAdmin

## Objetivo

Implementar el microservicio administrador de RabbitMQ requerido por EP2, aislado de la topología de negocio.

## Rama sugerida

```text
feature/ep2-rabbit-admin
```

## Arquitectura obligatoria

```text
RabbitAdminController
→ DTO + Bean Validation
→ RabbitAdminService
→ Spring AMQP RabbitAdmin
```

## Vhost

Solo:

```text
pedidos360-admin-demo
```

## Endpoints mínimos

```text
PUT    /admin/rabbit/queues/{name}
DELETE /admin/rabbit/queues/{name}
PUT    /admin/rabbit/exchanges/{name}
DELETE /admin/rabbit/exchanges/{name}
POST   /admin/rabbit/bindings
DELETE /admin/rabbit/bindings/{bindingId}
GET    /admin/rabbit/queues/{name}
```

## Seguridad

Reutilizar Entra:

- JWT válido;
- `access_as_user`;
- rol `ADMIN`.

## Restricciones

- Prefijo obligatorio `p360.demo.`.
- No permitir `amq.*`.
- No administrar exchange por defecto.
- No administrar topología principal.
- No recibir vhost/broker URL/credenciales desde cliente.
- No permitir argumentos AMQP arbitrarios.
- No CRUD de usuarios/policies desde la API.
- No purge arbitrario.
- Eliminación solo con condiciones seguras.

## Puede modificar

```text
backend/services/rabbit-admin-service/
docs/ep2/
```

y archivos mínimos de build/compose necesarios para su servicio, coordinados con I4/I5.

## Criterios de aceptación

- [ ] Crear queue válida.
- [ ] Rechazar nombre vacío/inválido.
- [ ] Crear exchange válido.
- [ ] Crear binding.
- [ ] Consultar queue.
- [ ] Eliminar recursos sandbox.
- [ ] 401 sin autenticación.
- [ ] 403 para CLIENTE.
- [ ] 409 para operación protegida/incompatible.
- [ ] 503 si broker no disponible.
- [ ] Controller no usa directamente RabbitAdmin.
- [ ] Tests cubren validación y autorización.
- [ ] Topología principal no puede alterarse.

## Evidencia mínima

- captura/log de CRUD válido;
- ejemplo de 400;
- ejemplo de 403;
- intento bloqueado sobre topología principal;
- tests automatizados.


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
