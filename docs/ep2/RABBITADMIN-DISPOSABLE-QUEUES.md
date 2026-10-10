# RabbitAdmin EP2 — DELETE acotado para demostración

La protección anterior de colas durables sigue vigente. No se resuelve el defecto
de `queue.delete(if-empty,if-unused)` para una cola que ya tiene deliveries sin ACK.
No se confía en una lectura de métricas ni se cierran conexiones de otros clientes.

Se incorpora una modalidad explícita al PUT existente:

```json
{"durable":false,"disposable":true}
```

Solo nombres `p360.demo.disposable.*`, dentro del vhost fijo
`pedidos360-admin-demo`. `durable:true` sin `disposable` conserva su contrato.
El cliente no puede elegir vhost, broker, credenciales, argumentos, exclusividad
o bypass. DELETE mantiene JWT Entra válido, scope `access_as_user` y rol ADMIN.
Se conservan 400/401/403/409/503 y respuestas sanitizadas.

Una conexión AMQP dedicada por cola tiene propiedad exclusiva impuesta por el
broker. El servicio nunca hace `basic.get` ni registra consumers en esa conexión.
Otras conexiones no pueden entregar mensajes de esa cola. Por ello no puede haber
un subconjunto invisible pendiente de ACK; el `if-empty` del DELETE actúa sobre
todos sus mensajes. Se exige `if-unused` y se espera la respuesta del broker antes
de informar 204. Un 406 por mensajes deja abierta la conexión propietaria, conserva
la cola y sus mensajes y reemplaza únicamente el canal de control cerrado.
Bindings se declaran/eliminan a través de ese canal propietario.

Se limita a 16 colas/conexiones por proceso. Otro proceso o un nombre sin recepción
propietaria devuelve 409; no adopta recursos históricos ni recrea automáticamente
propiedad después de una desconexión. La recuperación automática nativa del
cliente está deshabilitada; Spring conserva su conexión independiente para las
operaciones anteriores. No cambian permisos, servidores ni topología operativa.

**Limitación importante:** una cola exclusiva no durable es desechable. Si cierra
su conexión, se detiene el servicio o cae el broker, desaparece, incluso con
mensajes sintéticos. No es almacenamiento durable ni apta para negocio. Esta
modalidad no acredita DELETE general seguro de queues durables; ese alcance sigue
diferido. Una publicación que llegue después del DELETE puede quedar sin ruta:
el publisher debe usar confirms/mandatory según su contrato. No se ofrece purge.
[Semántica oficial de colas exclusivas](https://www.rabbitmq.com/docs/queues#exclusive-queues).

## Reproducción local

Desde la raíz: `mvn -f backend/services/rabbit-admin-service/pom.xml test`.
El banco usa HTTP real, JWT RSA firmado de fixture, validadores reales y RabbitMQ
4.1.8 Testcontainers con usuario/vhost sandbox propios. Entra live no se ejecuta.
Regresiones verifican DELETE vacío, permisos, entrada explícita/no durable,
recurso durable que no puede adoptarse, basic.get/consumer externos rechazados,
mensaje posterior al GET que impide DELETE con 409 y permanece almacenado,
y creación/eliminación de bindings con direct/topic/fanout.
Las pruebas previas de mensajes sin ACK y cola con consumer continúan comprobando
403 en colas normales. Se alinean las dependencias de empaquetado con las versiones
ya aprobadas en los otros servicios, sin actualizar RabbitMQ Server.
