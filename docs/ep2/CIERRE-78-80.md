# Cierre documental de #78 y #80

## Decisión del responsable del proyecto — 8 de octubre de 2026

El responsable del proyecto decidió sustituir, exclusivamente para el cierre de
#78 y #80, la exigencia interna de revisión I1/I5 y coordinación I3/I4 por la
auditoría técnica realizada con Codex. Estas reglas son de organización del
equipo, no requisitos académicos ni aprobaciones externas.

Esta decisión no afirma que I1, I5, I3 o I4 aprobaron los cambios. La autorización
proviene directamente del responsable. No modifica requisitos de otros issues.

## Base y resultado de auditoría

- develop auditado: `cd35d310731367d08bd31a324f053dfc32b617c6`, sincronizado y limpio.
- [PR #88](https://github.com/Rodoshix/PEDIDOS360-DELIVERY/pull/88): MERGED;
  SHA `0303a17d176ffd2ae61c73dd37bdce45189b05af`, implementación de #80.
- [PR #89](https://github.com/Rodoshix/PEDIDOS360-DELIVERY/pull/89): MERGED;
  SHA `cd35d310731367d08bd31a324f053dfc32b617c6`, implementación de #78.
- #78 y #80: OPEN. La auditoría no identificó blockers funcionales.
- Los pendientes formales de revisión interna quedan sustituidos por esta decisión.
  Los estados documentales anteriores al merge y la codificación del README de
  Usuarios se sanean en este PR exclusivamente documental.

## Evidencia técnica conservada

| Componente | Resultado revisado en reportes existentes |
|---|---|
| Usuarios | 73 pruebas; 25 RabbitMQ/PostgreSQL reales; sin fallos, errores u omisiones |
| Productos | 44 pruebas; 22 RabbitMQ/PostgreSQL, 6 HTTP/CRUD, 13 processor, 3 configuración |
| Adaptador BFF | 10 pruebas aprobadas, incluido timeout |
| Plataforma #69 | 13 pruebas aprobadas; 21 queues, 7 exchanges, 20 bindings, 21 policies |
| Aplicación #69 | PLATFORM APPLICATION CHECK PASSED |
| Compose Usuarios | Build real sin caché aprobado; log conservado |
| Compose Productos | Aprobado según PR/documentación; log bruto no localizado en la auditoría |

La auditoría comprobó código actual y reportes existentes; no volvió a ejecutar
las suites largas ni los builds. Este saneamiento documental tampoco los repite.
Los fallos DB inyectados con spies no acreditan desconexión real de PostgreSQL;
las consultas exitosas y los tests indicados usan DB/broker reales.

Detalle: [Usuarios](../../backend/services/usuarios-service/EP2-13-EVIDENCIAS.md)
y [Productos](PRODUCTOS-RABBITMQ.md).

## Responsabilidades independientes

- #78/#80: consumers, lógica de consulta equivalente a HTTP, autorización,
  ACK manual/prefetch 1, retry corto/DLQ/recovery, configuración desactivada,
  build y compatibilidad de permisos, pruebas y evidencia del servicio.
- #70: registro de operaciones concretas y corte BFF, activación por flujo,
  orquestación, presupuesto compartido cuando corresponda y rollback.
- #71: AWS, TLS/secretos, despliegue y permisos aplicados al broker AWS.
- #72: E2E integrales, fallos en entorno integrado y regresión de entrega completa.

El cierre de #78/#80 no certifica ni cierra #70/#71/#72. HTTP sigue oficial;
relay DISABLED por defecto. El permiso del DLX compartido mantiene la limitación
por exchange documentada, sin aislamiento de routing keys. No se modifica su
configuración en este PR.

## Secuencia de cierre pendiente de autorización

1. Revisar y autorizar este PR documental; no hacer merge automático.
2. Una vez autorizado, mergear el PR hacia develop.
3. Comentar #78 con PR #89, pruebas y auditoría; comentar #80 con PR #88,
   pruebas y auditoría. En ambos dejar explícitas las responsabilidades independientes
   de #70/#71/#72 y la decisión del responsable, sin aprobaciones ficticias.
4. Cerrar #78 y #80 únicamente después del merge documental.

Este documento no ejecuta esos pasos. Ambos issues siguen OPEN y el agente se
detiene antes del merge y del cierre para autorización del responsable.
No se inicia #79/#81, no se activa #70 ni se despliega/provisiona AWS.
