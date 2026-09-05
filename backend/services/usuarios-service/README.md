# Usuarios Service

Servicio de perfiles internos de Pedidos360. Java 21, Spring Boot 4.1.1 y Maven Wrapper 3.9.15.

## Estado

Implementados: entidad Usuario, repositorio JPA, migración Flyway y endpoints de perfiles con validaciones.
Se incluye identidad simulada local; Entra ID y la validación real de tokens siguen pendientes.
La base final estará en AWS RDS. El PostgreSQL de Docker es exclusivamente para desarrollo local.

## Antes de empezar

Necesitas JDK 21 y Docker Desktop con el motor Linux iniciado. Maven se descarga con el
Wrapper del repositorio; no hace falta instalarlo por separado. La primera ejecución
requiere internet para descargar Maven, dependencias e imagen de PostgreSQL.

En la terminal PowerShell de VS Code, desde la raíz del repositorio:

```powershell
Set-Location backend/services/usuarios-service
java -version
docker info --format '{{.OSType}}'
```

Comprueba Java 21 y una respuesta `linux`. Los comandos siguientes se ejecutan desde
`backend/services/usuarios-service`, no desde la raíz. Puertos predeterminados: API 8081
y PostgreSQL 5433, ambos locales.

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
if (-not (Test-Path .env.local)) {
  Copy-Item .env.example .env.local
}
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
| `LOCAL_IDENTITY_ENABLED` | `false` por defecto; `true` habilita identidad simulada solo con perfil `local` y escucha loopback. |
| `LOCAL_TENANT_ID` | UUID ficticio del directorio; obligatorio en modo simulado. |
| `LOCAL_OBJECT_ID` | UUID ficticio del usuario; obligatorio en modo simulado. |
| `LOCAL_ROLES` | `CLIENTE` por defecto; admite `CLIENTE`, `ADMIN` o ambos separados por coma. Nunca se asigna desde el cuerpo HTTP. |

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

Si ya arrancaste sin el perfil local, detén ese proceso con `Ctrl+C` antes de volver a
iniciarlo. Mantén esta terminal abierta y usa otra para las peticiones HTTP.

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
| GET | `/usuarios/me` | 200 con el perfil actual; 404 si todavía no existe. |
| GET | `/usuarios/{id}` | 200; propietario o ADMIN del mismo directorio. |
| GET | `/usuarios?pagina=0&tamanio=20` | 200; solo ADMIN, paginado desde 0, tamaño entre 1 y 100, ordenado por ID ascendente. Incluye perfiles inactivos del directorio. |
| PUT | `/usuarios/{id}` | 200 con el perfil actualizado; reemplaza datos editables; propietario o ADMIN del mismo directorio. |
| DELETE | `/usuarios/{id}` | Desactiva el perfil; propietario o ADMIN del mismo directorio; 204. |

POST y PUT aceptan únicamente `nombre`, `apellido`, `email` y `telefono` (opcional).
PUT reemplaza esos campos; omitir teléfono lo borra. Se rechazan campos desconocidos,
incluidos identidad, roles, ID interno y estado. Nombre y apellido son obligatorios (hasta 100 caracteres);
email es obligatorio y debe tener formato válido (hasta 254); teléfono admite hasta 30.

### Recorrido manual: crear, consultar y editar

Con identidad local habilitada y `LOCAL_ROLES=CLIENTE`, en otra terminal PowerShell:

```powershell
$usuariosUrl = 'http://127.0.0.1:8081'
$perfil = @{
  nombre = 'Ana'
  apellido = 'Pérez'
  email = 'ana@example.test'
  telefono = '+56912345678'
} | ConvertTo-Json

$creado = Invoke-RestMethod -Method Post -Uri "$usuariosUrl/usuarios" -ContentType 'application/json; charset=utf-8' -Body ([System.Text.Encoding]::UTF8.GetBytes($perfil))
$usuarioId = $creado.id
Invoke-RestMethod "$usuariosUrl/usuarios/me"
Invoke-RestMethod "$usuariosUrl/usuarios/$usuarioId"

$edicion = @{
  nombre = 'Ana María'
  apellido = 'Pérez'
  email = 'ana.actualizada@example.test'
} | ConvertTo-Json
Invoke-RestMethod -Method Put -Uri "$usuariosUrl/usuarios/$usuarioId" -ContentType 'application/json; charset=utf-8' -Body ([System.Text.Encoding]::UTF8.GetBytes($edicion))
```

El POST devuelve 201 y un encabezado `Location: /usuarios/{id}`; las consultas y el PUT
devuelven 200. El PUT de ejemplo elimina el teléfono al omitirlo. Se envía UTF-8 explícito
para conservar los acentos también en Windows PowerShell.

Si ya ejecutaste el POST con esa identidad, obtendrás 409. No es necesario crear otra vez
el perfil: recupera `$usuarioId` con `(Invoke-RestMethod "$usuariosUrl/usuarios/me").id`
y continúa con las consultas y edición. Si estaba desactivado, usa otra identidad ficticia
para un nuevo recorrido; no se permite reactivarlo desde esta API.

La respuesta expone ID interno, datos del perfil, estado y fechas; no expone identificadores de Entra.
Ejemplo ilustrativo (el ID y las fechas se generan en el servidor):

```json
{
  "id": 1,
  "nombre": "Ana María",
  "apellido": "Pérez",
  "email": "ana.actualizada@example.test",
  "telefono": null,
  "activo": true,
  "creadoEn": "2026-09-04T12:00:00Z",
  "actualizadoEn": "2026-09-04T12:01:00Z"
}
```

La lista devuelve `contenido`, `pagina`, `tamanio`, `totalElementos` y `totalPaginas`.
Nombre, apellido y teléfono se recortan en los extremos; el email también pasa a minúsculas.
Teléfono vacío o compuesto solo de espacios se guarda como `null`.

### Probar ADMIN y desactivación

Para probar el listado, detén el servicio, cambia `LOCAL_ROLES=ADMIN` en `.env.local`
y vuelve a iniciarlo con el perfil `local`. Mantén los mismos UUID para usar el mismo perfil.

```powershell
Invoke-RestMethod "$usuariosUrl/usuarios?pagina=0&tamanio=20"
```

Con `CLIENTE`, esa petición devuelve 403. ADMIN solo ve su directorio; no puede crear
perfiles en nombre de otras identidades mediante POST.

La siguiente operación es opcional y desactiva el perfil creado en el recorrido. Hazla
al final: no hay endpoint para reactivarlo.

```powershell
Invoke-RestMethod -Method Delete -Uri "$usuariosUrl/usuarios/$usuarioId"
```

Devuelve 204, sin cuerpo. Las siguientes operaciones de esa identidad devolverán 403,
incluso si tiene rol ADMIN. Para seguir probando, cambia `LOCAL_OBJECT_ID` por otro UUID
ficticio y reinicia. Si usas ADMIN con esa otra identidad activa o sin perfil, puedes
consultar el registro desactivado o repetir DELETE sobre él (204).

### Respuestas de error

Los errores de validación y negocio usan `application/problem+json`, con `status`, `detail`
e `instance`; los errores de campos añaden `errores`.

```json
{
  "title": "Bad Request",
  "status": 400,
  "detail": "Revisa los campos enviados.",
  "instance": "/usuarios/1",
  "errores": { "nombre": "El nombre es obligatorio." }
}
```

| Código | Causa habitual |
| --- | --- |
| 400 | Campos inválidos, JSON roto, propiedades no admitidas, ID no positivo o paginación fuera de límites. |
| 401 | Sin identidad habilitada/validada; un Bearer token todavía no autentica. |
| 403 | CLIENTE intentando listar o acceder a otro ID, o identidad con perfil desactivado. |
| 404 | `/usuarios/me` sin perfil, o consulta de ADMIN a ID inexistente/de otro directorio. |
| 409 | Perfil duplicado, edición de un perfil inactivo por ADMIN o conflicto de escritura. |
| 415 | POST/PUT con un tipo de contenido distinto de `application/json`. |

PowerShell muestra las respuestas HTTP de error como excepciones. Revisa el cuerpo de
la respuesta para distinguir una validación esperada de un problema de conexión.
El frontend debe usar `status` y `errores`, sin depender del texto exacto de `detail`.

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

No necesitas levantar Compose ni el servicio para ejecutar `verify`. Los servidores HTTP
de prueba usan puertos aleatorios. El bloque actual contiene 27 pruebas; también cubre
JSON inválido, límites de campos, rechazo sin alterar datos, ADMIN desactivado, protección
contra versiones obsoletas en persistencia y limpieza de la identidad simulada entre peticiones.
Los resultados quedan en `target/surefire-reports/` y no se suben a Git.

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

## Si algo falla

| Síntoma | Qué revisar |
| --- | --- |
| Maven no encuentra Java o indica versión incompatible | `java -version` y `JAVA_HOME` deben apuntar al JDK 21. Abre otra terminal después de cambiar la configuración. |
| Docker no responde o las pruebas fallan al buscar un entorno Docker | Abre Docker Desktop, espera que el motor esté listo y comprueba `docker info`. No hace falta restablecerlo de fábrica. |
| Puerto 8081 o 5433 ocupado | Detén solo el proceso propio que usa el puerto, o ajusta `SERVER_PORT` / `DB_PORT` y `DB_URL`. Actualiza también la URL de los ejemplos si cambias 8081. |
| Error de contraseña o conexión a PostgreSQL | Comprueba el archivo local y `docker compose --env-file .env.local ps`. Cambiar la contraseña del archivo no modifica la almacenada en un volumen existente. No borres el volumen para resolverlo. |
| Todos los endpoints de usuarios devuelven 401 | Verifica `LOCAL_IDENTITY_ENABLED=true`, el arranque con perfil `local` y que ejecutas Maven desde la carpeta del servicio. Reinicia tras cambiar variables. |
| El modo local impide arrancar | Debe ser el único perfil activo, usar loopback, UUID válidos y roles admitidos. No lo habilites en despliegues compartidos. |
| GET `/usuarios/me` devuelve 404 | Falta crear el perfil mediante POST para la identidad local configurada. |

Para detener la aplicación usa `Ctrl+C` en su terminal. Después puedes detener PostgreSQL
con el comando `docker compose --env-file .env.local down` indicado antes, conservando sus datos.

## Integración pendiente con el equipo

- BFF: usar el ID numérico devuelto por `/usuarios/me` para las referencias de Carrito y
  Pedidos. El frontend no debe conectarse directamente a PostgreSQL ni inventar el ID.
- Identidad: integrar y probar la validación de Entra ID y el transporte de identidad
  entre BFF y servicio. Enviar `X-User-Id`, `X-Roles` o un Bearer token no resuelve ese paso.
  No exponer el modo simulado ni usarlo como seguridad del BFF.
- RDS: coordinar base, credenciales, permisos de migración, TLS y acceso de red. La
  configuración por entorno ya existe; la conexión real con AWS no está validada.
- Concurrencia: `@Version` detecta escrituras solapadas en persistencia. La API no recibe
  versión ni `If-Match`, por lo que no detecta por sí sola que un formulario quedó antiguo
  antes de iniciar una nueva petición. No asumir que ese caso está resuelto.

Esta rama no incorpora login de frontend, despliegue ni autenticación de producción.
El contrato descrito aquí es el comportamiento actual del servicio local.

Seguimiento: Issue #6; rama `feature/i1-6-usuarios-service`.
