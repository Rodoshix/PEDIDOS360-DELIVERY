# Prueba conjunta BFF → Usuarios → PostgreSQL

Issue #34, validación previa a Entra real. Requiere Java 21 y Docker Linux activo.
Desde `backend/services/usuarios-service`:

```powershell
.\mvnw.cmd -f ../../integration-tests/pom.xml verify
```

Este módulo compila las fuentes reales de BFF y Usuarios como fuentes de prueba.
No duplica implementación ni requiere instalar sus JAR. No genera una aplicación
desplegable (el JAR vacío del módulo de pruebas no es un entregable de producción).

Levanta dos contextos Spring independientes con HTTP en loopback y puertos
temporales, más PostgreSQL 17 desechable. Usa la migración real de Usuarios.
No lee archivos `.env.local`; configuración aislada pasada a cada contexto.
El BFF no tiene conexión a base de datos; todas las operaciones viajan por HTTP.

Los únicos reemplazos de seguridad son decoders de prueba con la misma clave RSA
efímera, usando los validadores de producción de cada servicio. Los tokens no se
imprimen ni guardan. No contacta Entra ni verifica sus claves remotas/rotación.

Tres escenarios ordenados componen un único recorrido:

1. Perfil inicialmente ausente, creación 201, duplicado 409, modificación,
   reinicio de Usuarios sobre la misma PostgreSQL y lectura de datos persistidos.
2. 401 sin token o con audiencia incorrecta en ambas capas; 403 sin scope,
   frente a perfil ajeno y listado de cliente; listado ADMIN permitido.
3. Baja lógica 204, acceso posterior denegado y respuesta 502 si Usuarios cae.

Comprueba además Location y CORS en la creación. La suite del BFF cubre preflight,
origen rechazado, sanitización de errores, redirecciones y timeout.

Los contextos y el contenedor se cierran al finalizar. Sus datos son ficticios,
desechables y regenerables; no se usan las bases locales del desarrollador.

Resultado inicial: 3 pruebas aprobadas, sin fallos, errores ni omitidas.
La consulta manual con token real de Entra se verificó por separado el 2026-09-12;
véase la evidencia en `../bff/README.md`. Este módulo sigue usando claves efímeras.
Pendiente: validar los artefactos/configuración de despliegue y el CRUD completo
desde la interfaz real, que pertenecen a las siguientes ramas.
