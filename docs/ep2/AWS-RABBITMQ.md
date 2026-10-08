# Integración AWS #71 — infraestructura validada y artefactos versionados

Ampliación operativa de la plataforma #69, posterior a sus pruebas locales.
Se versiona el paquete público en [infrastructure/aws/rabbitmq](../../infrastructure/aws/rabbitmq/README.md).
No cambia contratos ni Java/React, no activa #70 y no sustituye Entrega 1.
#71 permanece abierto hasta auditoría/integración del PR; no afirmar pruebas
funcionales E2E de RabbitMQ mientras el corte permanece desactivado.

## Resultado aprobado de las ventanas AWS anteriores

RabbitMQ **4.1.8 healthy**, TLS/AMQPS correctos, sin puertos AMQP/Management públicos.
EBS dedicado persistente PASS; 21 queues oficiales quorum / 7 exchanges custom /
20 bindings / 21 policies. Inventario y permisos siguen la fuente única de #69.
EBS 10 GiB gp3 cifrado, misma AZ, DeleteOnTermination=false, ext4 y fstab UUID.
Guard de mount/identidad antes de arranque evita fallback al root. Datos en
`/opt/pedidos360/rabbitmq-data`, privados en `/opt/pedidos360/private-ep2/`.

No se publican IDs operativos, UUID reales, cuentas, credenciales, certificados,
testigos ni logs privados. Estas cifras son el resumen sanitizado de las ventanas
ya aprobadas por el usuario; **no una nueva medición AWS en este PR**.

## Capacidad

| Métrica | Baseline | Con RabbitMQ |
|---|---:|---:|
| CPU promedio | 54,82 % | 60,87 % |
| CPU máximo por intervalo | 60,43 % | 68,71 % |
| RAM utilizada | 2,387 GiB | 2,678 GiB |
| RAM disponible mínima | 5,067 GiB | 4,755 GiB |

Clasificación **CAPACIDAD JUSTA**: CPU credits agotados durante observación. No es
prueba bajo carga completa ni autoriza escalar consumers sin volver a medir.
Broker: **768 MiB provisional**, cpu_shares=512 (peso relativo, no reserva/cuota),
`+S 2:2`, PIDs=256, watermark=460 MiB, disk_free_limit=2 GiB.

## Reboot controlado: PASS CON OBSERVACIONES

| Hito después del arranque del host | Tiempo observado aproximado |
|---|---:|
| Mount EBS | 3,4 s |
| Docker | 8,8 s |
| RabbitMQ | 11,1 s |
| BFF | 93 s |
| Servicios Java | 156–167 s |

Mensaje persistente sobrevivió reboot; mount precedió Docker/RabbitMQ. Sin restart
loops ni OOM. Entrega 1 recuperó: hubo fallo HTTP transitorio inicial de consulta
de perfil y luego PASS al repetir. Disponibilidad depende del **health real** del
stack, no de EC2 RUNNING ni SSM. Este resultado de reboot no equivale a E2E
Pago → Pedido por RabbitMQ.

## Reproducibilidad y operación

[Runbook](../../infrastructure/aws/rabbitmq/README.md): pre-deploy EBS/mount/UID/TLS/
secretos/red/flags → start solo broker → preflight Management HTTPS → provisioning
aditivo → verify → measure. Stop/rollback conserva EBS y mensajes.
[Inventario y templates](../../infrastructure/aws/rabbitmq/topology/README.md)
importados desde #69; sin JSON paralelos. Policies permanecen en la plataforma,
no en consumidores nuevos. Sandbox probe quorum separado de las 21 queues oficiales.

Privados únicamente en archivos externos a Git. Clave CA no se instala en broker.
TLS verifica CA/SAN sin bypass; Management adaptado a HTTPS rechaza redirects,
proxies y operaciones destructivas. Un fallo inicial de permisos de configuración
pública se corrigió antes de validar AWS: archivos públicos legibles por RabbitMQ,
privados con permisos mínimos. El test de restart usa `up --no-deps --wait`, sin
depender de soporte de `compose start --wait`.

Guardas preservadas:

```dotenv
PEDIDOS360_COORDINATION_MODE=HTTP
PEDIDOS360_RELIABILITY_PLATFORM_READY=false
PEDIDOS360_RELAY_MODE=DISABLED
PEDIDOS360_DECLARE_TOPOLOGY=false
```

No se hace deploy, reboot ni llamada AWS nueva al versionar este paquete.

## Validación del paquete y límites

Pruebas locales: diez guardas offline; suite real TLS que reutiliza 13 probes de
#69 y añade CA/SAN/listeners/HTTPS (17 casos); persistencia de mensaje quorum en
sandbox tras stop/up. Sintaxis shell/Python, Compose y regresión de herramientas
AWS Entrega 1. Resultados exactos y commit se registran en el PR de #71.
Fixtures con secretos locales desechables, testigos y mediciones crudas ignorados.

Validación de versionado: 10/10 guardas y 13/13 probes de plataforma PASS. Las
cuatro pruebas TLS pasan tras corregir dos aserciones que inicialmente esperaban
AMQPConnectionError cuando pika propaga SSLCertVerificationError; se repitieron
los cuatro casos TLS (4/4), sin cambiar la configuración del broker. Probe sandbox
quorum PASS tras stop/up, helper Docker con inventario montado PASS (preflight,
reprovisionamiento y verify), regresión AWS Entrega 1 15/15 PASS. Se comprobó que
bootstrap rechaza filesystem ID incorrecto antes de iniciar RabbitMQ. Esta
validación fue local; las cifras AWS/reboot anteriores no se volvieron a medir.

Pendiente **#70**: conectar servicios con CA/cuentas correctas, flags por fases,
rollback de coordinación, no doble ejecución y E2E tarjeta/efectivo, fallos reales
de consumer/broker y DLQ funcional. **#72** completa regresión/E2E. El body histórico
de #71 vincula pruebas funcionales a #70; este PR cubre la infraestructura previa,
no las presenta como realizadas. No cerrar #71 manualmente en esta tarea.

Riesgos: un solo nodo (sin HA), créditos CPU agotados, margen provisional de RAM,
crecimiento de disco/backlog, vigencia TLS/rotación, recuperación lenta Java,
ventana AWS Academy y pérdida de acceso al detener lab. Mantener DeleteOnTermination
false y procedimientos de respaldo propios: persistencia no reemplaza backups.
