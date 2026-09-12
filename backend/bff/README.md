# BFF — issue #34

Rama `feature/i5-bff-seguridad`. Java 21 y Spring Boot 4.1.1.

## Estado real

Bloques 1 a 3: arranque sin base de datos, salud pública, JWT opt-in,
relay explícito a Usuarios y CORS por lista de orígenes. No acredita login
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
usa el controlador real del BFF y un servidor HTTP temporal como upstream. No equivale
a ejecutar BFF y Usuarios juntos con PostgreSQL: ese recorrido sigue pendiente.

## Relay y CORS (bloque 3)

USUARIOS_SERVICE_URL es un origen fijo sin ruta ni barra final: por defecto
http://127.0.0.1:8081. Solo HTTPS o HTTP loopback; para la red privada de Docker/AWS
habrá que acordar explícitamente la configuración de transporte en su rama.
BFF_UPSTREAM_TIMEOUT_MS acepta 100–30000 ms (por defecto 5000), incluido el cuerpo.
No se siguen redirecciones ni se implementan reintentos de escrituras.

Rutas soportadas, sin prefijo /api:

| Método | Ruta |
|---|---|
| GET | /usuarios?pagina=0&tamanio=20 |
| GET | /usuarios/me |
| GET, PUT, DELETE | /usuarios/{id} |
| POST | /usuarios |

El frontend debe usar el origen del BFF como VITE_API_BASE_URL. Se conserva el JSON
de éxito y Location relativo al crear; solo se reenvían Authorization validado,
Accept y Content-Type. Cookies, X-User-Id, X-Roles y otras cabeceras entrantes
no se reenvían. Usuarios valida otra vez el JWT y aplica autorización de dominio.
Los errores 400/401/403/404/409/429 conservan estado, pero su detalle se sanitiza;
fallos del upstream se traducen a 502 y tiempo excedido a 504. Respuestas sin caché.

BFF_CORS_ORIGINS es una lista separada por comas de orígenes exactos (sin barra final).
Predeterminados: http://localhost:5173 y http://localhost:5180. Vacío deshabilita
acceso cross-origin. No se permiten comodines, cookies ni credenciales CORS.
Preflight admite Authorization/Content-Type/Accept y GET/POST/PUT/DELETE/OPTIONS.
CORS no sustituye la validación JWT ni restringe clientes que no sean navegadores.

## Bloques siguientes

1. Completar prueba manual real de JWT tras la configuración de Entra.
2. Verificación conjunta BFF + Usuarios + PostgreSQL y regresión final.

BFF y Usuarios representarán la misma API lógica (audiencia existente).
Usuarios validará también el token y conservará la identidad `(tid, oid)`.
No usar el client ID del frontend como audiencia. Verificar versión del token
emitido antes de configurar issuer/audience; no asumir que usar MSAL implica v2.
No cambiar registros Entra automáticamente ni guardar tokens en issues/logs.

Referencias para la implementación:
- https://learn.microsoft.com/en-us/entra/identity-platform/claims-validation
- https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html

AWS, Docker del BFF y adaptación de Mi cuenta van en ramas posteriores del plan.
