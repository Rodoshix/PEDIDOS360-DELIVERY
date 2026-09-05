# Usuarios Service

Servicio de perfiles internos de Pedidos360. Java 21, Spring Boot 4.1.1 y Maven Wrapper 3.9.15.

## Estado

Implementados: entidad Usuario, repositorio JPA, migración Flyway y endpoints de perfiles con validaciones.
Se incluye identidad simulada local; Entra ID y la validación real de tokens siguen pendientes.
La base final estará en AWS RDS. El PostgreSQL de Docker es exclusivamente para desarrollo local.

## Modelo

- ID interno numérico: referencia que usarán Carrito y Pedidos.
- `tenantId` y `entraObjectId`: UUID del directorio y de la identidad externa. La pareja es única y no se modifica al editar el perfil.
- Nombre, apellido, email, teléfono opcional y estado activo.
- Fechas de creación/actualización en UTC y versión para detectar actualizaciones concurrentes.
- La desactivación conserva el registro y las referencias de otros módulos.
- No guarda contraseñas. Los roles de acceso se resolverán desde la identidad validada, no desde campos editables del perfil.
- El email se normaliza, pero no identifica de forma única al usuario: puede cambiar o coincidir entre directorios.

En pruebas se usan UUID ficticios; esto no equivale a una sesión autenticada.

## Base local

Requiere Docker Desktop con el motor Linux funcionando.

Desde esta carpeta, en PowerShell:

```powershell
Copy-Item .env.example .env.local
```

Hacer la copia solo si no existe `.env.local`. Editar `DB_PASSWORD` antes de iniciar la base. El archivo queda ignorado por Git y contiene valores de desarrollo, sin comillas.

```powershell
docker compose --env-file .env.local up -d --wait
.\mvnw.cmd spring-boot:run
```

Spring carga `.env.local` desde el directorio de ejecución. Docker Compose usa el mismo archivo mediante `--env-file`. El puerto local de PostgreSQL es 5433; si se cambia `DB_PORT`, actualizar también `DB_URL`.

La base conserva sus datos en un volumen. Para detenerla conservando los datos:

```powershell
docker compose --env-file .env.local down
```

Las variables iniciales de usuario y contraseña de PostgreSQL se aplican al crear el volumen por primera vez; editar el archivo después no cambia las credenciales de una base existente.

## Configuración

| Variable | Uso |
| --- | --- |
| `DB_URL` | URL JDBC de PostgreSQL; local: `jdbc:postgresql://localhost:5433/pedidos360_usuarios`. |
| `DB_USERNAME` | Usuario PostgreSQL. |
| `DB_PASSWORD` | Contraseña, sin valor incorporado al código. |
| `DB_NAME`, `DB_PORT` | Nombre de base y puerto publicado por el Compose local. |
| `SERVER_PORT` | Puerto HTTP; 8081 por defecto. |
| `SERVER_ADDRESS` | Dirección de escucha; 127.0.0.1 por defecto. |

En AWS se configurarán `DB_URL`, `DB_USERNAME` y `DB_PASSWORD` para RDS desde el entorno de despliegue, con TLS y acceso de red autorizado. No usar el Compose local para desplegar RDS ni subir credenciales.

Flyway administra el esquema `usuarios` y su historial de migraciones. Hibernate usa `ddl-auto: validate`: valida el modelo, no modifica tablas automáticamente. La cuenta de migración necesita permisos para crear el esquema y sus tablas; debe coordinarse con el responsable de RDS. Después de aplicar una migración compartida, agregar otra versión en vez de modificar la anterior.

## Probar los endpoints sin Entra ID

En `.env.local`, agregar las variables `LOCAL_*` de `.env.example` y cambiar
`LOCAL_IDENTITY_ENABLED=true`. Los UUID de ejemplo son ficticios. `LOCAL_ROLES=CLIENTE`
permite probar un usuario normal; `ADMIN` habilita operaciones administrativas sobre el mismo directorio.

Con PostgreSQL local iniciado, arrancar desde esta carpeta:

```powershell
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=local"
```

El modo simulado requiere únicamente el perfil `local` y `SERVER_ADDRESS=127.0.0.1`
(o `::1`). Si se intenta habilitar con otro perfil o escuchando en todas las interfaces,
la aplicación se niega a arrancar.

Toda petición local usa la identidad configurada en el servidor. No se necesita un token
ni se toman identidades o roles de headers HTTP. Cualquier proceso local puede usar esa
identidad de prueba: no es autenticación real. Cambiar UUID o roles exige reiniciar el servicio.
No hay cookies de sesión, login por contraseña ni acceso habilitado desde otros orígenes mediante CORS.

Sin habilitación explícita, `/usuarios` y sus subrutas responden 401; solo la salud queda pública.
Enviar un Bearer token no habilita el acceso hasta implementar la validación de Entra.

| Método | Ruta | Comportamiento |
| --- | --- | --- |
| POST | `/usuarios` | Crea el perfil de la identidad actual; 201 con Location. Duplicado: 409. |
| GET | `/usuarios/me` | Perfil actual; 404 si todavía no existe. |
| GET | `/usuarios/{id}` | Propietario o ADMIN del mismo directorio. |
| GET | `/usuarios?pagina=0&tamanio=20` | Solo ADMIN; paginado, máximo 100 por página. |
| PUT | `/usuarios/{id}` | Reemplaza datos editables; propietario o ADMIN del mismo directorio. |
| DELETE | `/usuarios/{id}` | Desactiva el perfil; propietario o ADMIN del mismo directorio; 204. |

POST y PUT aceptan únicamente `nombre`, `apellido`, `email` y `telefono` (opcional).
PUT reemplaza esos campos; omitir teléfono lo borra. Se rechazan campos desconocidos,
incluidos identidad, roles, ID interno y estado. Nombre y apellido son obligatorios (hasta 100 caracteres);
email es obligatorio y debe tener formato válido (hasta 254); teléfono admite hasta 30.

Ejemplo en otra terminal PowerShell:

```powershell
$perfil = @{
  nombre = 'Ana'
  apellido = 'Pérez'
  email = 'ana@example.test'
  telefono = '+56912345678'
} | ConvertTo-Json

Invoke-RestMethod -Method Post -Uri http://localhost:8081/usuarios -ContentType 'application/json; charset=utf-8' -Body $perfil
Invoke-RestMethod http://localhost:8081/usuarios/me
```

La respuesta expone ID interno, datos del perfil, estado y fechas; no expone identificadores de Entra.
La lista devuelve `contenido`, `pagina`, `tamanio`, `totalElementos` y `totalPaginas`.
Los errores de validación y negocio usan `application/problem+json`, con `status`, `detail`
e `instance`; los errores de campos añaden `errores`.

Un CLIENTE recibe 403 al consultar o modificar IDs ajenos, sin confirmar su existencia.
ADMIN recibe 404 para IDs inexistentes o de otro directorio. Un perfil desactivado ya no puede
operar ni volver a registrarse; no se edita ni se reactiva desde PUT. ADMIN puede consultar
perfiles inactivos y repetir su desactivación.

## Pruebas

Con Docker Desktop funcionando:

```powershell
.\mvnw.cmd verify
```

Las pruebas levantan PostgreSQL 17 temporal mediante Testcontainers y ejecutan la migración.
Verifican persistencia, identidad única, separación entre directorios, CRUD HTTP, validaciones,
permisos de CLIENTE/ADMIN, bloqueo por defecto y restricciones del modo local.
Usan una base aislada y no leen `.env.local`: no acceden a RDS ni al volumen de desarrollo.
Los contenedores de prueba se eliminan al terminar.

No se omiten automáticamente las pruebas cuando falta Docker. Para comprobar únicamente la compilación:

```powershell
.\mvnw.cmd test-compile
```

Esto no acredita que la persistencia funcione.

## Salud

Con la base disponible y el servicio iniciado:

```powershell
Invoke-RestMethod http://localhost:8081/actuator/health
```

Debe devolver `status: UP`. La salud ahora depende también de la base de datos.
Antes de exponer operaciones de usuarios fuera del entorno local debe completarse su control de acceso.

Seguimiento: Issue #6; rama `feature/i1-6-usuarios-service`.
