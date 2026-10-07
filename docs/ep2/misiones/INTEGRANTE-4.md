# Misión Integrante 4 — Plataforma RabbitMQ

## Objetivo

Preparar la plataforma RabbitMQ que usarán los demás integrantes, primero localmente y luego lista para integración AWS.

## Rama sugerida

```text
feature/ep2-rabbitmq-platform
```

## Responsabilidades

### Broker
- RabbitMQ con Management.
- Persistencia mediante volumen.
- Healthcheck.
- Nombre de nodo estable.
- Configuración reproducible.

### Vhosts

```text
pedidos360
pedidos360-admin-demo
```

### Usuarios/permisos

Preparar usuarios separados, como mínimo conceptualmente:

- producer;
- consumer;
- bootstrap;
- admin sandbox.

Permisos mínimos por vhost y prefijo.

### Seguridad
- No exponer AMQP/Management públicamente en AWS.
- Secretos fuera de Git.
- Preparar acceso privado/túnel.
- TLS cuando corresponda al entorno.

### Persistencia
Demostrar:
- reinicio broker;
- conservación de mensajes persistentes;
- conservación de queues necesarias.

### Recursos
- documentar consumo aproximado de RAM/CPU;
- establecer límites coherentes;
- preparar recomendaciones para EC2.

### Cluster
Investigar/preparar perfil demostrativo solo si se confirma como requisito.

Si se implementa demo:
- 3 nodos RabbitMQ;
- formación de cluster;
- queues replicadas/quorum cuando aplique;
- demostrar caída de un proceso/nodo;
- dejar claro que 3 contenedores en una EC2 no protegen frente a caída del host.

## Puede modificar

```text
infrastructure/docker/
infrastructure/rabbitmq/ (si se crea)
docs/ep2/
```

Cambios AWS finales deben pasar por Integrante 5.

## No puede cambiar unilateralmente

- nombres de topología;
- routing keys;
- contrato del mensaje;
- lógica de Pagos/Pedidos;
- código RabbitAdmin;
- seguridad Entra.

## Criterios de aceptación

- [ ] RabbitMQ levanta reproduciblemente.
- [ ] Management funciona local/privado.
- [ ] vhost `pedidos360`.
- [ ] vhost `pedidos360-admin-demo`.
- [ ] usuarios/permisos separados.
- [ ] healthcheck correcto.
- [ ] volumen persistente.
- [ ] mensajes persisten tras reinicio.
- [ ] secretos fuera de Git.
- [ ] topología puede ser declarada por aplicaciones.
- [ ] procedimiento de túnel/Management documentado.
- [ ] consumo de recursos medido/documentado.
- [ ] cluster demo preparado solo si corresponde.

## Evidencia mínima

- `docker compose ps`;
- health;
- Management;
- vhosts;
- permisos;
- reinicio con mensaje persistente;
- consumo de recursos.


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

#69 prepara el inventario aprobado de 21 queues/7 exchanges, bindings, TTL, DLQ, policies, permisos, persistencia, health y recursos. Preservar exchanges de Pedidos y revisar compatibilidad de tipos/argumentos antes de declarar. No activar cluster ni AWS desde esta ampliación.

Fuente: [adenda](../ADENDA-RABBITMQ-6-SERVICIOS.md) y [plan](../PLAN-ACTIVIDADES.md). Esta actualización no implementa ni crea ramas de trabajo.

### Trazabilidad de trabajos adicionales

Base común #77; Usuarios #78; Restaurantes #79; Productos #80; Pagos query #81; Carrito/Pedidos #82. Responsables de ejecución nuevos por confirmar; coordinación I1/I5. #68 sigue separado; #69 plataforma; #70 integración.
