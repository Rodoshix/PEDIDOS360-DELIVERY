# Diagnóstico mínimo de IdentityProof con plazo vencido

Base: `f6a9540e5db9890a7716184b917c718d35a21de6` (`develop`).
Rama: `codex/identity-proof-deadline-observability`.
Validación local: 11 de octubre de 2026.

## Cambio autorizado

`QueryFailureHandler.gestionar()` ya clasificaba como DLQ una consulta vencida y
registraba únicamente la clase de la excepción. En esa misma rama, exclusivamente
para `Domain.USUARIOS` y `IdentityProofException`, ahora registra `reason()`.

Formato del mensaje diagnóstico:

```text
Consulta messageId=<UUID> correlationId=<UUID|DESCONOCIDO> plazo vencido: no se ejecuta ni se reintenta; IdentityProofException reason=<enum>
```

Los valores del enum son `FORMATO`, `FIRMA`, `CLAVE`, `VINCULO`,
`TIEMPO_INCOHERENTE` y `VENCIDA`. Una correlación no canónica se representa como
`DESCONOCIDO` solo en este log, para evitar contenido libre o inyección de líneas.
No se modifica la correlación del mensaje ni su tratamiento AMQP.

Se conserva el timestamp del logger existente para correlacionar con las marcas
de recepción, presupuesto y handoff ya disponibles. No se añaden lecturas del
reloj o del guard, ni se inventan tiempos E/R que esta rama no conoce. No se
registra el mensaje ni el stack trace de la excepción, JWS, firma, token, claims,
actor, payload, credenciales o identidad personal.

Las condiciones de vencimiento, validación IdentityProof/ActorContext, timeouts,
tenant, issuer, audience, vigencias y la llamada a `transferir()` permanecen
intactas. ACK/NACK, retry y recuperación ante handoff incierto no se modifican.
Los demás dominios y excepciones conservan su log anterior. No cambian
dependencias, contratos, configuración ni código propio de Usuarios.

## Pruebas ejecutadas

| Ejecución local | Casos | Fallos / errores / omisiones |
| --- | ---: | --- |
| Core: `mvn -B "-Dtest=IdentityProofDeadlineLoggingTests" test` | 18 | 0 / 0 / 0 |
| Core completo: `mvn -B install` | 286 | 0 / 0 / 0 |
| Usuarios completo, con el Core recién instalado: `mvn -B verify` | 79 | 0 / 0 / 0 |

Total de casos distintos en las suites completas: **365**. Los 18 focalizados
están incluidos en los 286 de Core; su ejecución separada no aumenta ese total.
`git diff --check` también pasa.

Las regresiones nuevas capturan el evento Logback y comprueban los seis motivos,
vencimiento explícito/envelope/guard, UUID y correlación permitidos, ausencia de
throwable y de una cadena sintética colocada en actor, payload y mensaje de
excepción. Comprueban también correlaciones malformadas, otros dominios,
otras excepciones y DLQ incierta sin retry alternativo. Son pruebas unitarias con
transporte inyectado y reloj fijo; no reproducen el rechazo de AWS.

Core incluye pruebas existentes con RabbitMQ Testcontainers. Usuarios ejecuta
25 casos de `UsuariosRabbitTests` con listener Spring real y RabbitMQ 4.1.8 / PostgreSQL
17 desechables: contratos, correlación, autorización, tenant, ACK, retry, DLQ y
recuperación. Los fallos transitorios de base de esa suite se inyectan en el
servicio; no son particiones reales de red ni fallos de RDS. La autenticación usa
JWT/JWK y perfiles de prueba locales, no sesiones Microsoft Entra live.

No se ejecutaron las suites completas de BFF u otros servicios, ni pruebas AWS
o autenticación Entra live. El alcance productivo queda condicionado a Usuarios;
Core comprueba compatibilidad del log para los otros tres dominios.

## Evidencia reproducible

Desde los respectivos directorios de módulo, ejecutar los tres comandos de la
tabla en ese orden, con Java 21, Maven y Docker local disponibles. No proporcionar
endpoints, credenciales ni variables AWS a los tests.

`IDENTITYPROOF-DEADLINE-OBSERVABILITY-tests.json` contiene conteos y SHA-256 de
fuentes, reportes Surefire locales y JAR construidos. El Core incluido en
`BOOT-INF/lib` del JAR de Usuarios coincide por SHA-256 con el Core probado.
Las fuentes de identidad, actor, consumer, publishers y guard listadas en el
manifiesto son idénticas a la base.

Los reportes originales permanecen en `target` ignorado y pueden regenerarse;
no se versionan XML completos, `system-out`, logs ni fixtures de credenciales.
Los hashes identifican esta ejecución: una nueva ejecución puede cambiar los
hashes de reportes/JAR por timestamps y metadatos.

## Límite operativo

Este cambio prepara observabilidad para una ejecución futura. No determina
retroactivamente el motivo del rechazo anterior: la causa exacta sigue sin
acreditarse. No se consultó ni modificó AWS, RDS, broker o ECR; no se desplegó la
instrumentación, reactivó RabbitMQ ni realizó merge. `develop` permanece intacto.
La revisión del PR Draft y una autorización separada de despliegue son necesarias
antes de usar esta instrumentación en AWS.
