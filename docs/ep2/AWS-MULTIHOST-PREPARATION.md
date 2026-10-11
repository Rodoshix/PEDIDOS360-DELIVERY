# Preparación multihost EP2 — revisión previa, sin despliegue

Base revisada: develop `3d2913c06ecc31c6029792cf837250773d10aa8b`.
Distribución propuesta aceptada: A = frontend, BFF, Usuarios, Pedidos y RabbitMQ;
B = Restaurantes, Productos, Carrito y Pagos. RDS permanece compartido.
Referencias: #71, #70 y #72. Este trabajo no completa su corte operativo.

## VERIFICADO

El 2026-10-10 se consultaron exclusivamente STS, EC2 describe-instances,
describe-security-groups y SSM describe-instance-information, con perfil
`pedidos360-lab` y región explícita `us-east-1`. Ambos hosts están RUNNING,
son t3.large en la misma VPC y SSM informa Online / Ubuntu 24.04.
No se enviaron comandos SSM ni se inspeccionaron secretos, mensajes o datos.
No se modificó AWS. No se versionan identificadores de cuenta o credenciales.

El SG de A conserva entrada 8080/8443 desde el SG Lambda, salida 80/443 y
5432 hacia el SG RDS. B tiene cero reglas inbound y solo salida TCP 443
a 0.0.0.0/0. RDS admite 5432 desde el SG de A, todavía no desde B.
Por tanto, la comunicación multihost propuesta está actualmente bloqueada.

### Dependencias reales

| Origen | Destino y protocolo existente | Cambio al dividir |
|---|---|---|
| Navegador / entrada actual | Frontend y API Gateway/Lambda → BFF HTTPS | Sin cambios de rutas, MSAL ni entrada pública |
| BFF A | Usuarios HTTPS 8081, Pedidos HTTPS 8085 | DNS Docker local |
| BFF A | Restaurantes 8082, Productos 8083, Carrito 8084, Pagos 8086, HTTPS | Nombres de servicio resueltos a IP privada B |
| Pedidos A | Usuarios HTTPS 8081, Productos HTTPS 8083 | Usuarios local; Productos remoto B |
| Carrito B | Productos HTTPS 8083 | Docker local B |
| Pagos B | Usuarios HTTPS 8081, Pedidos HTTPS 8085 | Remotos A; token delegado/worker existente |
| Cada microservicio | Su base y usuario RDS, PostgreSQL 5432 sslmode=verify-full | B requiere autorización de red RDS; pools max 5 conservados |
| Java / MSAL | Entra HTTPS 443 | Mantener validación JWT, tenant, scopes y roles |
| Clientes RabbitMQ A | Broker A AMQPS 5671 | Alias local p360-rabbitmq conservado |
| Clientes RabbitMQ B, futuro | Broker A AMQPS 5671 | Alias remoto p360-rabbitmq; no está habilitado por esta preparación |

Fuentes: `infrastructure/aws/compose.yml`, `compose.ep2-prepared.yml`,
`rabbitmq/compose.rabbitmq.yml` y configuraciones de los servicios.
Restaurantes/Productos no reciben claves de firma privadas. BFF es el único
emisor ActorContext; Usuarios conserva la clave independiente IdentityProof.
No se altera la semántica de tenant/usuario local, CLIENTE/ADMIN ni el 404
uniforme para recursos externos o históricos no autorizados.

## IMPLEMENTADO EN RAMA

| Archivo | Finalidad |
|---|---|
| infrastructure/aws/compose.host-a.yml | Frontend, BFF, Usuarios y Pedidos; proyecto pedidos360-aws |
| infrastructure/aws/compose.host-b.yml | Los cuatro servicios B; proyecto pedidos360-aws-b |
| infrastructure/aws/compose.broker-host-a.yml | Variante futura del broker existente, mismo nodo y EBS, AMQPS privado |
| infrastructure/aws/multihost.py | Generación determinista y comprobación frente a los Compose canónicos |
| infrastructure/aws/test_multihost.py | Regresiones estáticas sin arranque de servicios |
| infrastructure/aws/multihost.env.example | Variables públicas adicionales, direcciones sintéticas y rutas sin secretos |
| docs/ep2/AWS-MULTIHOST-PREPARATION-tests.json | Resultados y hashes reproducibles |

Los .yml generados usan JSON, subconjunto válido de YAML. Se derivan de
compose.yml + compose.ep2-prepared.yml; no son overlays que deban apilarse
sobre el Compose monohost. Cada archivo se utiliza solo en su host.
`multihost.py check` detecta divergencias frente a las fuentes.
Solo se eliminan dependencias depends_on remotas, se filtran secretos/redes,
se agregan aliases privados, bindings y shutdown de 90 s. Healthchecks,
imágenes, parámetros de autenticación y límites originales se conservan.
Frontend conserva el healthcheck de su imagen, sin inventar uno en Compose.

Todas las aplicaciones mantienen HTTP, relay DISABLED, IdentityProof false,
readiness false y declaración dinámica de topología false. Las variables
de activación en el shell no prevalecen sobre esos valores fijos.
No hay nuevo consumer, worker, tabla, migración ni cambio productivo.
El reconciliador HTTP ya existente de Pagos permanece habilitado.

### Secretos y material por host

A necesita exclusivamente sus contraseñas DB, TLS de BFF/Usuarios/Pedidos,
credenciales propias de mensajería, claves ActorContext privada del BFF y
IdentityProof privada de Usuarios, y los públicos que consumen esos servicios.
El broker A mantiene su cookie, bootstrap y clave TLS en el EBS/configuración
actuales. No se copian ni se sustituyen durante esta tarea.

B necesita sus cuatro contraseñas DB, certificados servidor de esos cuatro
servicios, contraseña de keystore, truststores públicos y secreto OAuth del
worker Pagos ya existente. Recibe públicos ActorContext/IdentityProof solo
según referencias de cada servicio. Los consumidores y publisher Pagos
mantienen cuentas independientes. B no recibe claves de firma privadas,
cookie Erlang, bootstrap, CA privada ni datos/volumen RabbitMQ.
Los archivos se suministrarán por canales autorizados fuera de GitHub.

Los accounts RabbitMQ existentes son por servicio y propósito, no una prueba
de partición por tenant. La autorización tenant continúa en los validadores
autenticados existentes. No compartir credenciales entre despliegues de
tenants; no inventar una garantía de aislamiento del broker por tenant.

### Nombres TLS y redes Docker

Se preservan URLs https://productos:8083, https://pedidos:8085, etc.
extra_hosts enlaza únicamente nombres remotos con HOST_A_PRIVATE_IP o
HOST_B_PRIVATE_IP. El certificado debe contener el SAN DNS del nombre
utilizado, y la CA debe existir en el truststore. No se cambia la URL a IP
ni se desactiva verificación. La IP no acredita identidad ni permisos.
Un cambio de dirección exige actualizar el artefacto público y revisar TLS.

Las redes Docker services siguen internal; las aplicaciones conservan su
red egress y frontend usa edge, para los puertos privados publicados.
El broker futuro conserva la red externa pedidos360-aws_services, el proyecto
pedidos360-ep2-broker, nodo rabbit@p360-rabbitmq y guard de filesystem/cookie.
Agrega una bridge broker-access para el binding privado 5671. No publica
Management, AMQP sin TLS ni Erlang. Esta bridge permite egress del broker
sujeto al SG del host: es un cambio propuesto que requiere revisión operativa.
No aplicar el archivo del broker durante la migración HTTP; RabbitMQ queda
funcionando en A, sin traslado ni copia del volumen.

## PROPUESTO — matriz mínima SG, pendiente de autorización

Cada fila requiere salida en origen e ingreso en destino. Usar referencias
de SG, no CIDR público ni confiar en la IP como autorización de aplicación.
SG son stateful: no abrir rangos efímeros de retorno.

| Origen | Destino | TCP | Justificación y momento |
|---|---|---|---|
| SG A | SG B | 8082 | BFF → Restaurantes HTTPS |
| SG A | SG B | 8083 | BFF/Pedidos → Productos HTTPS |
| SG A | SG B | 8084 | BFF → Carrito HTTPS |
| SG A | SG B | 8086 | BFF → Pagos HTTPS |
| SG B | SG A | 8081 | Pagos → Usuarios HTTPS |
| SG B | SG A | 8085 | Pagos → Pedidos, proyección interna y confirmación autenticadas |
| SG B | SG RDS | 5432 | Cuatro usuarios DB existentes, verify-full |
| SG B | SG A | 5671 | Solo futura activación EP2 autorizada; no necesario para migración HTTP |
| A/B | Salida actual autorizada | 443 | Entra, SSM, ECR y demás dependencias HTTPS |
| SG Lambda actual | SG A | 8080/8443 | Conservar entrada existente, sin modificación desde este PR |

Bindings: A frontend8080, BFF8443→contenedor8080, Usuarios8081,
Pedidos8085; B Restaurantes8082, Productos8083, Carrito8084, Pagos8086.
Son IP privadas explícitas, nunca 0.0.0.0. La privacidad efectiva exige además
SG, rutas y pruebas desde orígenes prohibidos; binding privado por sí solo
no demuestra inaccesibilidad desde Internet en EC2 con IPv4 pública.
No abrir 22, 4369, 5672, 15671, 15672 o 25672. RabbitAdmin no está incluido
en estos modelos ni se habilita DELETE; su perfil privado sigue fuera del corte.

## PROPUESTO — instalación, transición y rollback

La secuencia original genérica queda reemplazada por
[AWS-MULTIHOST-TRANSITION.md](AWS-MULTIHOST-TRANSITION.md). Detalla comandos
por etapa, traslado separado de catálogo/carrito/Pagos, puertas de avance,
recuperación y matriz de imágenes/Flyway/roles. No se ejecutó en AWS.

El ensayo local demuestra que up con el mismo proyecto puede recrear los
servicios incluidos y conservar huérfanos ACTIVOS. Por eso se prohíben
--remove-orphans, COMPOSE_REMOVE_ORPHANS=true, down y cambios de proyecto A.
Pagos A se detiene y verifica al comienzo de la ventana, antes de arrancar
cualquier copia B. Los contenedores retirados se conservan detenidos; los
servicios recreados requieren imágenes y modelo previo para rollback.

El broker permanece fuera de todos los comandos de corte HTTP. La variante
compose.broker-host-a.yml es exclusivamente futura: no incluirla ni aplicar
su puerto o bridge durante el traslado. Las imágenes realmente desplegadas,
los digests candidatos y los checksums/owners/grants reales RDS siguen sin
acreditar. No arrancar un runtime candidato con migraciones pendientes.

## Pruebas y evidencia

Ejecutadas localmente: 12 regresiones de configuración, un caso integrado
de transición Compose con sleepers sintéticos y 20 existentes
de preparación AWS. Estas últimas incluyen keytool real con certificados y
secretos sintéticos temporales. Docker Compose config analiza modelos. Solo el ensayo de transición arranca
contenedores sintéticos en proyectos aleatorios; no aplicaciones, listeners,
PostgreSQL o RabbitMQ. Ninguna es una prueba
E2E AWS, de carga multihost, partición de red o Entra live. No se reejecutaron
suites de negocio/frontend porque no se modificó código ni contratos.

Reproducción desde raíz (Docker Compose y Python; Node/JDK para suite previa):

```text
python -B infrastructure/aws/multihost.py check
python -B -m unittest discover -s infrastructure/aws -p test_multihost.py -v
python -B -m unittest discover -s infrastructure/aws -p test_multihost_transition.py -v
node --test infrastructure/aws/deployment.test.mjs infrastructure/aws/compose.test.mjs infrastructure/aws/transfer-worker.test.mjs infrastructure/aws/ep2-prepared.test.mjs
git diff --check
```

Para validación de una configuración pública completa, combinar .env.example
y multihost.env.example en un archivo ignorado. Exportar sus variables públicas
en un shell controlado y ejecutar `python -B infrastructure/aws/multihost.py validate`.
Solo valida direcciones RFC1918 distintas y sintaxis Compose. No certifica
existencia de EBS/certificados/secretos, conectividad, imagen o permisos AWS.
No usar deployment.mjs up: sigue destinado al modelo monohost y puede crear
servicios fuera de esta partición.

Pendientes antes de despliegue: TLS válido por todos los nombres y rechazo de
CA/SAN erróneos; URLs internas no accesibles públicamente; JWT inválido,
CLIENTE/ADMIN, recurso externo 404 y tenant; checkout HTTP sin doble POST,
lecturas y confirmación Pago→Pedido; health real, RDS verify-full y roles;
ausencia de jobs duplicados; fallos/reinicio y rollback ensayado. Para futura
activación EP2: IdentityProof/deadlines entre hosts con sincronización de reloj,
confirms/returns, retry/DLX, permisos mínimos y 21/7/20/21 sin cambios.

## NO COMPROBADO / RIESGOS / decisiones pendientes

No se inspeccionó estado de Docker o material TLS del host durante esta tarea.
No se demostró carga/capacidad, rendimiento RDS, CPU Unlimited, backups,
restauración, rutas/NACL, permisos Academy para cambios, instalación Docker B
ni controles DB operativos. Los prerequisitos de PR #94 siguen pendientes de
acreditación en AWS. Dos t3.large distribuyen recursos, no ofrecen HA automática.
Las correlaciones BFF siguen locales a una única instancia A. No hay terminalidad
durable global de consultas; mantener sincronización temporal y garantías
acotadas ya aprobadas. GHSA-6588-rqcr-59pw operativo sigue pendiente.

Requieren autorización adicional: Docker B, reglas SG/RDS, emisión/distribución
TLS/secretos, cualquier migración pendiente, ventana y traslado de servicios,
prueba de recuperación y posterior activación EP2/#70. La bridge/puerto del
broker es propuesta separada, no permiso para modificar RabbitMQ existente.

**READY FOR INDEPENDENT MULTIHOST REVIEW**: artefactos y revisión estática
preparados. No significa listo para desplegar ni requisitos operativos resueltos.
