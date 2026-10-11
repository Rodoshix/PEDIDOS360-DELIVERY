# Cierre técnico EP2 — entrega v0.8.0 en preparación

Corte 11-10-2026. No tag/release ni merge ejecutado. La validación local de integración no certifica aceptación AWS. [Índice portable y hashes](../evidence/v0.8.0/README.md) contiene hechos sanitizados y procedencia de los originales conservados en custodia privada.

## Matriz frente a requisitos académicos

«Acreditado» significa evidencia técnica conservada, no aprobación docente ni nota asignada. La pauta de encargo y presentación fue revisada desde los originales privados; no se redistribuyen PDFs. Encargo SHA-256 `53eef199c2a19d6ee2dad8a6355c89013d84519ec979cffd4044ca8504b3ae0c`; presentación `ee37641722b4d5dda832e2e6adf029cf8eb2d4f5316aa4fc5885af637fe19a41`. La [adenda de seis dominios](ADENDA-RABBITMQ-6-SERVICIOS.md) documenta la adaptación docente de mensajería; no acredita una excepción Angular/React.

| Criterio | Estado | Evidencia / límite |
|---|---|---|
| Nombres centralizados; beans Queue/Exchange/Binding; separación de configuración y negocio | Acreditado código/local | messaging-core, topología y pruebas versionadas; no equivalen a HA cloud |
| Consumidores funcionales en seis dominios | Parcial AWS | Consultas Usuarios/Restaurantes/Productos/Pagos y evento consumido en Pedidos; Carrito pendiente AWS |
| ACK/NACK, retry, DLQ e idempotencia | Parcial AWS | ACKs reales en recorridos acreditados; fallos/reentrega locales. No prueba deliberada de segundo POST Pago AWS |
| Cuatro consultas request/reply | Acreditado AWS | UUID/correlación y RESPONDIDA_ACK, retryCount=0; Productos/Pagos HTTP 200 explícito; Usuarios/Restaurantes sin captura directa del status |
| Pago→Pedido: outbox, publicación, consumo y estado persistido | Acreditado AWS | Pago #3 EFECTIVO/PENDIENTE; pedido #4 CONFIRMADO v1; una outbox PUBLISHED, attempts=1; mismo messageId y ACK; sin nuevos duplicados/DLQ |
| Pago→Pedido: respuesta CLIENTE satisfactoria | Parcial | POST devolvió 502, causa no acreditada. GET de recuperación dieron 200; no sustituyen el éxito del POST |
| Pedido→Carrito: receipt EMPTIED, ACK y vaciado persistido | Pendiente AWS / acreditado local | Productor, consumidor, snapshot/versiones/idempotencia y guard 409 implementados; imagen candidata no desplegada |
| RabbitAdmin REST/servicio/validaciones | Parcial | 61 pruebas locales. DELETE queue devuelve 403 por política fija de seguridad: no presentar CRUD completo ni eliminación segura como implementados |
| Dos nodos, tres queues/DLQ y direct/topic | Local; aceptación cloud completa no acreditada en este paquete | Clúster demo local. Metadatos/plan AWS no prueban ensayo ni HA; dos nodos en un host tampoco |
| IDaaS/JWT, tenant/propietario y RDS cloud | Acreditado en recorridos históricos | Contratos/aislamiento autenticados y recuperación TLS/RDS read-only; no nuevo preflight |
| Frontend/backend/API Manager cloud y requisito frontend | Parcial | React real desplegado anteriormente. Pauta menciona Angular; no se encontró autorización docente de React en el material revisado |
| Regresión conjunta de #113–#117 | Acreditado local focalizado | 90 pruebas, cero fallos/omisiones, core/BFF/Carrito empaquetados; no suite completa del producto ni aceptación AWS |
| GitHub/AVA/capturas/presentación | Preparado; entrega y revisión docente pendientes | Evidencias portables/hashes. Capturas UI anteriores son fixtures locales, no backend E2E AWS; preparar enlaces AVA y exposición de 5–10 min |

## Resultados comprobados

Pago→Pedido messageId `81249c6a-f545-45ab-bc6a-9e8eee20c567`: publicación `07:34:23.344009585Z`, ACK `07:34:23.470506237Z`; transición persistida y una outbox. Pago #3 sigue EFECTIVO/PENDIENTE y pedido #4 CONFIRMADO. Registros protegidos pedido #3/pago #2 conservados. No repetir POST; no se realizó comprobación deliberada de idempotencia mediante segundo POST.

Último cierre HTTP conservado: ocho aplicaciones y RabbitMQ saludables, recuperación espontánea después de Academy, TLS/JWT y API Manager en mantenimiento. Outbox/receipts de Carrito sin trabajo y outbox histórica de pago PUBLISHED. Este informe no certifica salud AWS actual.

## Dos incidentes distintos sin causa acreditada

El POST de pago respondió 502 aunque el evento completó sus efectos. No hay correlación suficiente para atribuir timeout, upstream u otra causa. HEAD / del segundo bootstrap respondió 500 a las 22:04:15 UTC; métricas agregadas y ausencia de logs no atribuyen su causa. No se demostró relación causal con StopInstances de Academy. No se cambian timeouts, IAM o código por hipótesis. El bootstrap no preparó nueva sesión/huella CLIENTE.

## Controles de Pedido→Carrito y riesgo residual

#115 bloquea DELETE HTTP completo con 409 en modo RabbitMQ antes de mutar; conserva HTTP normal e ítems. #117 registra messageId validado, resultado y ACK_SENT solo después de basicAck sin excepción; demuestra llamada exitosa del cliente, no recepción confirmada por RabbitMQ. Contadores null siguen desconocidos. #116 gate usa identidad JWT validada y falla cerrado cuando se habilita; probado localmente, no desplegado.

La variante React RabbitMQ no impide por sí sola que checkout antiguo cree pedidos. El guard 409 evita vaciado incompatible, pero no bloquea creación de pedidos desde clientes antiguos. Sin gate/admisión y preparación de huella verificables no declarar exclusividad ni ventana lista. No existe evento AWS Pedido→Carrito aceptado.

## Integración y bloqueos restantes

Las tres líneas operativas de #113 ya están versionadas. El fallo limpio de #115 por BFF classifier fue reproducido y corregido únicamente en test-scope mediante perfil optativo; prueba BFF movida sin cambios, sin Java productivo/Dockerfile modificados. #117 fue rebasado sobre #115 actualizado, sin cambios al delta ACK. [Procedimiento y heads](INTEGRACION-V080.md).

Se puede entregar un alcance parcial honesto cuando se autoricen revisión e integración. Siguen pendientes aprobación/merge/tag/release, decisión docente React/Angular, aceptación AWS Pedido→Carrito, respuesta pública Pago completamente satisfactoria y cobertura académica completa Admin/clúster. No introducir cambios de contratos o infraestructura para resolverlos dentro de este cierre.

Exposición sugerida: «Acreditamos cuatro consultas RabbitMQ y el evento Pago→Pedido en AWS con ACK y persistencia. El POST de pago respondió 502 sin causa; recuperamos lectura con 200. Pedido→Carrito y sus controles están probados localmente, con candidata publicada, pero pendientes AWS. Entregamos el alcance comprobado y sus límites, conservando HTTP compatible.»
