# BFF — issue #34

Rama `feature/i5-bff-seguridad`. Java 21 y Spring Boot 4.1.1.

## Estado real

Bloque 1: arranque sin base de datos, salud pública y denegación del resto.
JWT, relay a Usuarios y CORS **todavía no implementados**. No acredita login
extremo a extremo. No habilitar identidad simulada en este componente.

Desde `backend/services/usuarios-service`, reutilizar el wrapper versionado:

```powershell
.\mvnw.cmd -f ../../bff/pom.xml verify
.\mvnw.cmd -f ../../bff/pom.xml spring-boot:run
```

Escucha local predeterminada: `127.0.0.1:8080`. Variables opcionales:
`SERVER_ADDRESS`, `SERVER_PORT`. No carga `.env.local` ni necesita secretos.

## Bloques siguientes

1. JWT en BFF y Usuarios: firma, emisor de tenant único, audiencia de API,
   tiempos, `tid`/`oid`; autorización `access_as_user` y roles CLIENTE/ADMIN.
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
