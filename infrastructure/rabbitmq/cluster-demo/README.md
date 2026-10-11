# #73 — Clúster RabbitMQ local de dos nodos

Base: `e34af8df9ab29a65553404c544750403504cb47f` (merge #105).
La autorización actual exige **dos nodos** y sustituye la referencia antigua a
tres nodos del issue. No se modificó el issue ni se incorporaron PDF académicos.
Perfil sintético independiente; no conectado a servicios PEDIDOS360, AWS o #70.

## Arquitectura

```text
Python local -- AMQPS / HTTPS verificados -- rabbit-demo-1 / rabbit-demo-2
                                              |               |
                                         volumen node1   volumen node2
                                              +--- Raft -------+
```

RabbitMQ 4.1.8 por el mismo digest aprobado en #69/#71, dos identidades estables
`rabbit@rabbit-demo-1` y `rabbit@rabbit-demo-2`. Descubrimiento `classic_config`
con nodo 1 como seed. No hay join/reset manual en el arranque. Ambos procesos
arrancan juntos, especialmente al recuperar datos persistidos: exigir que uno
esté healthy antes de arrancar el otro puede impedir el rejoin completo.
El provisionador rechaza membresía incompleta y no declara colas hasta comprobar
los dos nodos. Comprueba membresía con Management y `cluster_status` de ambos.

Proyecto fijo `p360-cluster73-demo`, dos volúmenes Docker propios, red de pares
`p360-cluster73-demo` interna y puente `p360-cluster73-demo-access` exclusivo para
sondas locales. Docker 29 no publica puertos desde una red únicamente interna.
Ninguna red se comparte con stacks existentes. El puente de acceso permite
salida de red: **no es una garantía de aislamiento físico del host ni de ausencia
de egress**. Docker/root y quien controla estas redes son fronteras privilegiadas.
Los clientes del paquete solo aceptan destinos locales fijos; los contextos Docker
remotos y `DOCKER_HOST` están bloqueados.

| Nodo | AMQPS loopback | Management HTTPS loopback |
|---|---|---|
| rabbit-demo-1 | 127.0.0.1:5783 | 127.0.0.1:15783 |
| rabbit-demo-2 | 127.0.0.1:5784 | 127.0.0.1:15784 |

No publicar en `0.0.0.0`, no exponer 5672/15672, EPMD o distribución Erlang.
AMQP y Management usan TLS con CA/hostname verificados, sin bypass. No se exige
mTLS. **La distribución Erlang no usa TLS**, únicamente cookie propia sobre las
redes Docker demo del mismo host; esto se limita a datos sintéticos y no acredita
seguridad de comunicaciones interhost en AWS. Cookie equivale a una credencial
privilegiada de nodo: no compartirla con otros entornos.

## Topología y permisos

Fuente única: `topology.json`, vhost `demo73`, tres exchanges durables:
`demo73.direct`, `demo73.topic`, `demo73.dlx`. No usa nombres operativos `p360.*`.

| Cola principal | Binding principal | DLQ / clave de fallo |
|---|---|---|
| demo73.orders.q | direct: order.created | demo73.orders.dlq / order.failed |
| demo73.payments.q | direct: payment.created | demo73.payments.dlq / payment.failed |
| demo73.events.q | topic: demo.*.created y audit.# | demo73.events.dlq / event.failed |

Las **seis** colas son quorum, durables, con initial-group-size 2; se verifican
members/online y Raft `quorum_status`. Las tres políticas DLX usan
`at-least-once` y `reject-publish`; no se pierde intencionadamente el mensaje por
usar dead lettering at-most-once. Hay siete bindings explícitos, más los bindings
implícitos al exchange default. La topología principal 21/7/20/21 no se modifica.

- `demo73-admin`: administrador del clúster sintético para provisioning/verificación.
- `demo73-publisher`: configure/read denegados; write solo direct/topic demo.
- `demo73-consumer`: configure/write denegados; read solo las seis colas demo.

Son cuentas nuevas con contraseñas independientes, sin tags impersonator para
aplicaciones. No existe guest ni vhost operativo. Los brokers reciben solo password
Admin, cookie, marcador y certificado/clave servidor/CA pública; no reciben clave
privada CA ni passwords de clientes. El bootstrap compara cookie persistida,
conserva volúmenes y copia TLS con propietario rabbitmq y permisos 0600.

## Uso local reproducible

Python 3.11+, OpenSSL, Docker Compose v2, contexto Docker local y pika 1.3.2.
Preflight exige Docker con al menos 4 GiB asignados, dos CPU y disco local libre
>=6 GiB. Esto es un umbral de preparación, no una garantía de MemAvailable ni
rendimiento del host: revisar carga de otras aplicaciones antes del arranque.
Cada broker limita memoria a 768 MiB, CPU a 1, PIDs a 256 y schedulers Erlang a 2.
Health comprueba ping, aplicación running y alarmas; membresía se valida aparte.

Desde la raíz del repositorio, en PowerShell:

```powershell
$demo='infrastructure/rabbitmq/cluster-demo'
python -m venv "$demo/.local/venv"
& "$demo/.local/venv/Scripts/python.exe" -m pip install -r "$demo/requirements.txt"
$env:DEMO73_OPENSSL='C:/Program Files/Git/usr/bin/openssl.exe' # o openssl en PATH
python -B "$demo/demo.py" init
python -B "$demo/demo.py" preflight
python -B "$demo/demo.py" start
python -B "$demo/demo.py" provision
python -B "$demo/demo.py" verify
python -B "$demo/demo.py" resources
python -B -m unittest discover -s $demo -p test_config.py -v
New-Item -ItemType Directory -Force "$demo/evidence" | Out-Null
$env:DEMO73_REAL_TESTS='1'
& "$demo/.local/venv/Scripts/python.exe" -B -m unittest discover -s $demo -p test_cluster.py -v
python -B "$demo/demo.py" stop
```

En Linux usar `bin/python` del venv. `init` crea solo archivos ignorados bajo
`.local/private`, passwords/cookie aleatorios y CA/certificado local de 14 días.
No sobrescribe un fixture existente. Configuración incompleta, secretos reutilizados,
certificado/clave incompatible, volúmenes/redes con etiquetas ajenas o destinos
remotos bloquean operaciones. No imprime secretos. No reutilizar este material
en AWS. Si vence o queda incompleto, requiere mantenimiento local explícito;
no hay renovación silenciosa ni borrado automático de datos.

Management es autenticado: importar la CA pública local en el cliente antes de
usar `https://localhost:15783`; no saltar advertencias ni usar `curl -k`. Los scripts
leen credentials desde archivos privados, nunca argumentos de shell ni URLs.
CA/key de prueba y la cookie no se versionan; `.gitattributes` conserva scripts LF.

## Fallos, recuperación y datos

Con dos miembros, mayoría = **dos**. Caer un proceso puede impedir operar las
quorum queues. `pause_minority` también puede pausar el proceso superviviente.
La prueba real confirma un mensaje, ejecuta SIGKILL del nodo 2, comprueba que
no hay lectura funcional y recupera nodo/mayoría antes de consumir y ACKearlo.
El probe de lectura corre en un proceso acotado: si se bloquea se termina sin
ACK ni publish. No se interpreta incertidumbre como pérdida o éxito.

El reinicio completo detiene ambos y los arranca juntos con sus volúmenes
existentes; recupera mensajes confirmados de las tres colas. Nunca ejecutar reset,
force_boot o forget_cluster_node para simular éxito. `stop` conserva todo.

El cliente cierra una entrega sin ACK y demuestra redelivery, luego un ACK y
ausencia de una segunda entrega en esa prueba. **No es exactly-once global ni
dedupe de negocio**; RabbitMQ puede redeliver. Un mismo host implica fallo común
de energía/disco/Docker; no existe alta disponibilidad de host ni continuidad
demostrada con un miembro perdido. No se probaron particiones reales de red.

Durante desarrollo se hizo un reset **explícito de datos sintéticos vacíos** para
validar el arranque final limpio. Se comprobaron nombres y etiquetas exactos de
`p360-cluster73-demo_node1/node2`, ausencia de mensajes/consumers y aislamiento;
se retiró solo ese proyecto y esos dos volúmenes. El reset no es comando del
helper ni parte de recuperación normal. No usar `down -v` ni limpieza global.
Para repetir una reinicialización se requiere volver a verificar esas condiciones
y actuar exclusivamente sobre los nombres demo; los volúmenes ajenos se rechazan.

## Evidencia, límites de seguridad y nube

Resultados y hashes: [CLUSTER-DEMO-73-tests.json](../../../docs/ep2/CLUSTER-DEMO-73-tests.json).
Logs completos locales en `evidence/`, ignorados. Los hashes no hacen recuperable
un log ausente; se incluyen comandos para volver a producirlo. No se reejecutaron
suites de negocio o frontend, cuyas fuentes no cambian.

Ejecución final: **11 pruebas con broker real correctas, 5 pruebas offline de
configuración correctas, ninguna omitida**. Una comprobación focalizada adicional
de membresía/permisos pasó; está incluida en los 11 casos, no suma cobertura nueva.
Se verificaron el arranque limpio final, membresía por ambos CLI, tres exchanges,
siete bindings, seis réplicas quorum de dos miembros, políticas y ACL exactas.

Entorno medido: Docker Desktop 29.4.3, 16 CPU, ~15.15 GiB asignados al engine.
La muestra inmediatamente posterior a la suite registró 157.1 / 162.8 MiB de
memoria de contenedor y ~100% de una CPU por nodo, sobre límite 768 MiB / 1 CPU.
Sin alarmas de memoria/disco en la muestra. Otra muestra previa de reposo marcó
87.18 / 84.77 MiB y 0.92% / 0.46% CPU. Son muestras, **no picos máximos ni un
benchmark**; los CLI/health checks y reinicios consumen CPU. No extrapolar costo
ni rendimiento a EC2 sin medir carga/capacidad real.

Fallos de preparación registrados y corregidos: marcador con CRLF Windows;
descubrimiento que esperaba un peer aún no arrancado; dependencia healthy que
impedía rejoin simultáneo; publicación de puertos en red internal-only; tipo string
de mem_limit en Compose JSON; URL incorrecta del harness para leer permisos.
No se debilitaron pruebas de quorum, mensajes, ACL o TLS para obtener el resultado.

La sonda TLS negocia el encabezado SASL de AMQP 1.0: **no está deshabilitado**.
Los clientes sintéticos usan AMQP 0-9-1 y no usan metadata `user_id` como
autoridad. [GHSA-6588-rqcr-59pw](https://github.com/rabbitmq/rabbitmq-server/security/advisories/GHSA-6588-rqcr-59pw)
describe falsificación de user-ID mediante propiedades posteriores al body de
AMQP 1.0, incluso al convertir a 0-9-1. Sus metadatos listan 4.3.0–antes de 4.3.5,
pero el texto no establece un rango completo. No se declara 4.1.8 inmune; no se
ejecutó el exploit y la negociación no prueba mitigación. En esta demo no existe
un consumidor que autorice negocio con esa metadata. Una decisión de versión o
restricción operativa de protocolos requiere evaluación/aprobación fuera de esta
demostración; no se actualizó el digest ni la autenticación de producción.

La nube queda pendiente: autorización, capacidad real, red/firewall, TLS de
distribución interhost, certificados operativos y evaluación de versión/protocolos.
Este Compose y sus claves son locales, no un despliegue AWS listo para activar.
No cambia RabbitAdmin DELETE 403, permisos operativos, flags, contratos, outbox,
frontend/backend, #70, #71 ni el estado de ningún issue.
