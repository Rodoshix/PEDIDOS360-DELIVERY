# Inventario único #69

Fuente de verdad: [`infrastructure/rabbitmq/platform_control.py`](../../../rabbitmq/platform_control.py).
El adaptador AWS importa `inventory()`, `accounts()`, preflight, provisioning y
verificación del mismo archivo; solo cambia transporte a HTTPS con CA y secretos
por archivo. `export_inventory.py` imprime inventario público y templates de
permisos sin leer contraseñas. No mantener JSON paralelos editados manualmente.

Desde `infrastructure/aws/rabbitmq/`:

```sh
python3 -B scripts/export_inventory.py
```

Conteo: **21 queues quorum durables, 7 exchanges direct personalizados, 20 bindings
custom y 21 policies**; 12 usuarios / 13 entradas de permisos, dos vhosts.
6 funcionales + 8 retries + 6 DLQ + 1 respuesta BFF. Exchanges de Pedidos conservados;
otros `p360.commands`, `p360.queries`, `p360.retry`, `p360.dlx`. Sin `p360.events`.

Pedidos retry TTL 5/30/120 segundos en argumentos. Cinco retries simples tienen
TTL por policy (`RABBITMQ_SIMPLE_RETRY_MS=1000`, configurable 100..30000).
No agregar argumentos TTL distintos desde los futuros servicios: conservar
declaraciones compatibles. Main Pedidos delivery-limit=5; retries/DLQ=-1 en policies.
Dead-letter at-least-once y overflow reject-publish donde define #69. BFF response
TTL 60 s / max-length 1000 / delivery-limit=5, policy exacta independiente.

Aplicaciones sin permisos configure; bootstrap opera infraestructura. RabbitAdmin
demo aislado en sandbox. `amq.default` no limita routing key con permisos de
exchange: #77 debe validar `replyTo`; #70 deberá comprobarlo en integración.
Replay de consultas expiradas no es automático; permisos replay actuales cubren
commands, no queries. Se conservan estas decisiones, no se amplía #77–#82 aquí.

Las colas sandbox usadas por tests/probes **no cuentan** como topología oficial.
No se pueden convertir colas classic existentes por redeclaración: preflight
bloquea antes de mutar. No eliminar/purgar para hacer coincidir inventario.
