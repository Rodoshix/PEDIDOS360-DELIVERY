# BFF — issue #34

Rama `feature/i5-bff-seguridad`. Java 21 y Spring Boot 4.1.1.

## Estado real

Bloques 1 y 2: arranque sin base de datos, salud pública y validación JWT opt-in.
Relay a Usuarios y CORS **todavía no implementados**. No acredita login
extremo a extremo ni se han cambiado registros Entra. No habilitar identidad simulada.

Desde `backend/services/usuarios-service`, reutilizar el wrapper versionado:

```powershell
.\mvnw.cmd -f ../../bff/pom.xml verify
.\mvnw.cmd -f ../../bff/pom.xml spring-boot:run
```

Escucha local predeterminada: `127.0.0.1:8080`. Variables opcionales:
`SERVER_ADDRESS`, `SERVER_PORT`. No carga `.env.local` ni necesita secretos.

## Seguridad JWT (bloque 2)

Configurar en el entorno de ambos procesos, BFF y Usuarios:

```text
ENTRA_ENABLED=true
ENTRA_TENANT_ID=<UUID del directorio>
ENTRA_API_CLIENT_ID=<UUID de pedidos360-api, sin api://>
ENTRA_FRONTEND_CLIENT_ID=<UUID de pedidos360-frontend>
```

El ejemplo está en `.env.example`; BFF no carga ese archivo automáticamente.
Los IDs son públicos. No se necesita client secret ni un token en configuración.
Sin ENTRA_ENABLED, las rutas permanecen cerradas. En Usuarios no puede combinarse
con LOCAL_IDENTITY_ENABLED. Su modo local anterior sigue disponible para regresión.

Este corte acepta **solo access tokens v2**. Antes de probar manualmente, comprobar
que la API emite v2 (`api.requestedAccessTokenVersion=2` en su registro); usar
MSAL o un endpoint v2 no garantiza la versión emitida por la API. Esa comprobación
y cualquier cambio de Entra se harán explícitamente con el usuario.

Se valida firma RS256 con claves de Microsoft, issuer específico, tid, oid UUID,
audiencia exacta de la API, azp del frontend, ver=2.0, exp y nbf (tolerancia de reloj
del validador de Spring). Scope access_as_user y rol CLIENTE o ADMIN son obligatorios.
Usuarios obtiene la identidad del JWT validado y conserva los permisos de propietario.
No se aceptan claims X-User/X-Roles enviados como cabeceras. BFF y Usuarios representan
la misma audiencia lógica; si se separan audiencias habrá que revisar el flujo.

Las pruebas usan RSA efímero y un decoder local con los validadores de producción.
No contactan Entra ni acreditan descarga/rotación real de sus claves. La prueba HTTP
del BFF usa un controlador de prueba; el relay todavía no existe.

## Bloques siguientes

1. Completar prueba manual real de JWT tras la configuración de Entra.
2. Relay explícito de rutas de Usuarios, manteniendo métodos/estados del contrato.
   Upstream fijo; reenviar únicamente el Bearer validado y cabeceras necesarias.
   No confiar en X-User/X-Roles ni seguir redirecciones hacia otros destinos.
3. CORS por lista de orígenes, timeouts, errores sanitizados y pruebas completas.

BFF y Usuarios representarán la misma API lógica (audiencia existente).
Usuarios validará también el token y conservará la identidad `(tid, oid)`.
No usar el client ID del frontend como audiencia. Verificar versión del token
emitido antes de configurar issuer/audience; no asumir que usar MSAL implica v2.
No cambiar registros Entra automáticamente ni guardar tokens en issues/logs.

Referencias para la implementación:
- https://learn.microsoft.com/en-us/entra/identity-platform/claims-validation
- https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html

AWS, Docker del BFF y adaptación de Mi cuenta van en ramas posteriores del plan.
