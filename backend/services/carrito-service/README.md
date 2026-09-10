# Carrito Service

Integrante 1, issue #19. Java 21, Spring Boot 4.1.1 y Maven Wrapper 3.9.15, como Usuarios.

## Estado del cuarto bloque

Base ejecutable, salud pública y seguridad cerrada por defecto. Incluye el modelo
Carrito/LineaCarrito, repositorio JPA, migración Flyway, PostgreSQL local y endpoints de
consulta, agregado, cantidad, eliminación y vaciado. **Solo se habilitan mediante el
modo local explícito**, con identidad y catálogo ficticios. No conecta Azure, BFF,
Usuarios, Catálogo real, Pedidos ni AWS.

## Ejecución local

Desde la raíz del repositorio en PowerShell, con JDK 21 y Docker Desktop (motor Linux):

```powershell
Set-Location backend/services/carrito-service
java -version
docker info --format '{{.OSType}}'
if (-not (Test-Path .env.local)) {
  Copy-Item .env.example .env.local
}
# Completar DB_PASSWORD en .env.local antes de iniciar Compose.
.\mvnw.cmd verify
docker compose --env-file .env.local up -d --wait
.\mvnw.cmd spring-boot:run
```

La primera ejecución puede descargar Maven, dependencias e imágenes Docker. Conservar
`.env.local` si ya existe; no se versiona. Si proviene del primer bloque, agregar las
variables `DB_*` de `.env.example`. Las pruebas usan una base temporal independiente:
no leen `.env.local`, no requieren iniciar Compose y no omiten pruebas si falta Docker.

`SERVER_PORT` vale 8084 por defecto y `SERVER_ADDRESS` vale 127.0.0.1. La base local usa
el puerto 5440 y un volumen exclusivo del Compose `pedidos360-carrito-local`. En otra terminal:

```powershell
Invoke-RestMethod http://127.0.0.1:8084/actuator/health
```

Debe devolver `status: UP`; ahora comprueba también PostgreSQL, **no la integración
con otros servicios**. Sin modo local, `/carrito` responde 401. Enviar Bearer, usuario
o roles en cabeceras no habilita acceso. No hay login
por contraseña, cookies, CORS habilitado ni redirección a Microsoft en este servicio.

Para detener la base conservando los datos: `docker compose --env-file .env.local down`.
No usar `down -v` si se quieren conservar los datos. Las credenciales de PostgreSQL
se inicializan al crear el volumen; editar `.env.local` después no cambia la contraseña
de una base existente. No se modifica ni detiene el Compose de otros servicios.

### Variables de persistencia

| Variable | Uso local |
| --- | --- |
| `DB_URL` | `jdbc:postgresql://localhost:5440/pedidos360_carrito` |
| `DB_USERNAME` | `pedidos360_carrito` |
| `DB_PASSWORD` | Obligatoria para Compose, sin valor incorporado al código. |
| `DB_NAME` | `pedidos360_carrito`, usado por Compose. |
| `DB_PORT` | `5440`, puerto publicado solo en loopback; coordinar cualquier cambio con `DB_URL`. |

### Modelo y persistencia

- `carritos`: identidad `(tenant_id, entra_object_id)` única e inmutable desde JPA,
  restaurante opcional cuando está vacío, moneda CLP, auditoría UTC y versión optimista.
  Se usa la identidad externa como en Usuarios: no se consulta ese servicio ni se
  inventa un ID numérico de usuario. Su asociación para Pedidos se resolverá al integrar.
- `lineas_carrito`: producto único por carrito, nombre/precio de referencia y cantidad.
  La única clave foránea apunta a su propio carrito, nunca a una base ajena.
- Límites locales del modelo: 50 productos diferentes, 1–99 unidades por producto,
  nombre de hasta 200 caracteres y precio entre 0 y 1.000.000.000 CLP. Añadir otra vez
  un producto suma cantidades y actualiza nombre/precio de referencia para toda su línea;
  cambiar solo cantidad conserva ese precio. El adaptador de catálogo se añadirá después.
- Total y subtotales calculados, no persistidos como copias susceptibles de divergir.
  Se usan enteros `long` y aritmética exacta. No hay descuentos, envío ni reserva de stock.
- Solo el agregado Carrito modifica las líneas. `revision_contenido` hace que una
  modificación exclusivamente en una línea también actualice la raíz y su `@Version`.
  Una copia obsoleta no debe sobrescribir otra; las operaciones HTTP usan transacciones
  y convierten el conflicto en respuesta 409 sin reintento automático. `version` informa
  la versión guardada; no es un requisito enviado por el cliente ni una precondición
  If-Match. Las peticiones que llegan secuencialmente se aplican en ese orden.
- Quitar/vaciar elimina líneas huérfanas y libera el restaurante, sin borrar el carrito
  ni su identidad. Hibernate usa `ddl-auto: validate`; Flyway crea el esquema `carrito`
  y aplica `V1__crear_carritos.sql`. Tras compartir una migración, crear una nueva versión
  para cambios posteriores. Flyway tiene limpieza deshabilitada.
- PostgreSQL refuerza unicidad, referencias, cantidades y precios. El límite de líneas
  y la coherencia del restaurante son invariantes del agregado, no validaciones de
  integración con un catálogo real. Auditoría a precisión de microsegundos para que
  las respuestas de escritura y consulta coincidan con lo almacenado en PostgreSQL.

### Pruebas

`mvnw.cmd verify` ejecuta pruebas de modelo y de persistencia contra PostgreSQL 17
temporal mediante Testcontainers, más pruebas HTTP con puerto aleatorio de loopback.
Cubren migración, cascadas, identidad única, búsqueda por directorio/propietario,
límites, totales, versiones obsoletas y rollback de raíz y líneas. Las HTTP verifican
el recorrido local completo, JSON inválido, campos prohibidos, límites, mezcla de
restaurantes y aislamiento. También comprueban salud, rechazo de credenciales
ficticias fuera del modo local y bloqueo de diagnóstico. Las pruebas de configuración
rechazan perfiles, interfaces o identidades inválidos. No necesitan cuentas Azure ni
datos reales y no borran la base permanente de Compose.

La suite ampliada incluye dos transacciones simultáneas contra PostgreSQL: crear el
mismo carrito y modificar la misma versión. Comprueba un guardado y un conflicto, sin
líneas parciales de la operación rechazada. Usa barreras y tiempos máximos, no esperas
arbitrarias. Los fallos provocados de unicidad/configuración pueden producir avisos
esperados en los logs; revisar el resumen final de pruebas para distinguirlos de fallos.

Las pruebas del catálogo sustituyen únicamente su adaptador por respuestas controladas:
datos inválidos, indisponibilidad, cambio de restaurante, precio actualizado y excepción
interna. Comprueban que no se altere el carrito ni se filtren mensajes del adaptador,
y que quitar/vaciar siga funcionando sin consultar el catálogo. Esto no prueba una
conexión real con Productos.

## Contrato HTTP local

Estos endpoints están implementados para pruebas locales. **No representan todavía
un acuerdo de integración del equipo**. Se revisarán antes de conectar otros servicios.

| Método | Ruta | Comportamiento |
| --- | --- | --- |
| GET | `/carrito` | 200; carrito de la identidad actual. Si no existe, devuelve vacío sin crear un registro. |
| POST | `/carrito/items` | 200; agregar `productoId` y `cantidad`, o sumar a la línea existente. Devuelve el carrito. |
| PUT | `/carrito/items/{productoId}` | 200; reemplazar `cantidad` de una línea existente. Devuelve el carrito. |
| DELETE | `/carrito/items/{productoId}` | 204; quitar una línea existente. No consulta catálogo para permitir retirar productos dados de baja. |
| DELETE | `/carrito` | 204; vaciar el carrito propio. Repetir o no tener carrito no es un error. |

Reglas:

- Un carrito por propietario y directorio. La identidad se resuelve en servidor, nunca
  desde el cuerpo ni cabeceras libres. No se crean claves foráneas a bases ajenas.
- El cliente solo indica producto y cantidad; no puede fijar propietario, roles,
  restaurante, precio, subtotal ni total. Se rechazan campos desconocidos.
- Cantidad positiva; quitar se hace con DELETE, no enviando cero. Los DTO respetan
  los límites locales del modelo descritos arriba; no se truncan cantidades decimales.
- Catálogo mediante un componente sustituible del servidor. Datos ficticios solo en
  pruebas o modo local explícito y loopback; nunca un precio fijo como fallback en
  producción. Productos inexistentes o no disponibles no modifican el carrito.
- Propuesta MVP: un restaurante por carrito; rechazar mezclar restaurantes sin vaciar
  automáticamente el carrito existente. Revisar esta regla con el equipo antes de integrar.
- Para el modelo local, CLP en pesos enteros (6990 representa $6.990), tomando como
  referencia el código actual de Pedidos. No usar float/double ni multiplicar por 100.
  La moneda y conversión final deben acordarse al integrar con Catálogo/Pedidos.
- Subtotales y total calculados en servidor con protección de desbordamiento. Son
  importes del carrito de prueba, no una cotización definitiva ni una reserva de stock.
- Persistencia PostgreSQL, esquema `carrito`, migraciones Flyway, auditoría UTC y
  control de concurrencia. Las operaciones deben ser atómicas.
- Errores: 400 por entrada inválida, 401 sin identidad, 403 por acceso prohibido,
  404 por producto o línea inexistentes, 409 por producto no disponible, mezcla de
  restaurantes o concurrencia; 415 por cuerpo que no sea JSON. Si no existe adaptador
  de catálogo, falla su consulta o devuelve datos inválidos, la capa de servicio falla
  con 503; nunca sustituye datos reales por un precio ficticio automáticamente. Un
  producto no encontrado conserva el 404, pero no se propaga el mensaje interno del
  adaptador. No hay reintentos ni se adjunta la excepción original a la respuesta.

POST admite solo `productoId` y `cantidad`; PUT, solo `cantidad`. No hay ruta para
consultar un carrito ajeno por ID, ni siquiera para ADMIN. La respuesta contiene
`id`, `restauranteId`, `moneda`, `total`, `version`, `actualizadoEn` e `items`.
Cada ítem contiene `productoId`, `nombre`, `precioUnitario`, `cantidad` y `subtotal`;
se ordenan por producto. Un carrito aún no creado tiene ID, restaurante, versión y
fecha nulos, total cero e ítems vacíos. Un carrito vaciado conserva su ID y versión.
No se exponen UUID de identidad, tokens, SQL ni entidades JPA. Los errores controlados
usan `application/problem+json`. La creación simultánea de dos carritos del mismo
propietario devuelve 409 a la operación en conflicto, sin reintentar el POST.

## Probar operaciones sin integración

Con PostgreSQL local iniciado, completar estas variables de `.env.example` en
`.env.local` y cambiar `LOCAL_IDENTITY_ENABLED=true`:

| Variable | Uso |
| --- | --- |
| `LOCAL_IDENTITY_ENABLED` | `false` por defecto; habilita identidad y catálogo ficticios juntos. |
| `LOCAL_TENANT_ID` | UUID ficticio del directorio, configurado en servidor. |
| `LOCAL_OBJECT_ID` | UUID ficticio del usuario, configurado en servidor. |
| `LOCAL_ROLES` | `CLIENTE` por defecto; admite CLIENTE/ADMIN. Ambos solo acceden a su propio carrito. |

Arrancar desde la carpeta del servicio (detener antes cualquier instancia en 8084):

```powershell
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=local"
```

La aplicación rechaza el arranque con modo local habilitado si falta la identidad,
hay roles desconocidos, otro perfil además de `local` o una escucha distinta de
`127.0.0.1`/`::1`. No habilitar este modo para despliegue ni exponerlo mediante un
proxy o túnel. Cualquier proceso local puede operar como la identidad configurada:
**esto no es autenticación real**. No se aceptan identidades/roles del cuerpo o de
cabeceras HTTP; cambiar de identidad exige reiniciar el servicio. Sin habilitación,
ni la identidad ni el catálogo ficticio se cargan.

Productos disponibles exclusivamente en este modo, con precios ficticios CLP:

| Producto | Restaurante | Precio | Disponible |
| --- | --- | --- | --- |
| 101, hamburguesa de prueba | 20 | 6990 | Sí |
| 102, bebida de prueba | 20 | 1500 | Sí |
| 103, producto no disponible | 20 | 3000 | No |
| 201, pizza de prueba | 21 | 8990 | Sí |

En otra terminal PowerShell:

```powershell
$carritoUrl = 'http://127.0.0.1:8084/carrito'
Invoke-RestMethod $carritoUrl
Invoke-RestMethod -Method Post -Uri "$carritoUrl/items" -ContentType 'application/json' -Body '{"productoId":101,"cantidad":2}'
Invoke-RestMethod -Method Put -Uri "$carritoUrl/items/101" -ContentType 'application/json' -Body '{"cantidad":1}'
Invoke-RestMethod -Method Delete -Uri "$carritoUrl/items/101"
Invoke-RestMethod -Method Delete -Uri $carritoUrl
```

Repetir POST suma cantidades. No hace falta iniciar sesión en React ni enviar un token
para este recorrido local aislado. Si falta el perfil/flag, un 401 es el comportamiento
esperado. Al terminar las pruebas, volver a `LOCAL_IDENTITY_ENABLED=false` y reiniciar.

## Entregas de esta rama

1. Base del servicio y contrato local (publicado en `82d6063`).
2. Modelo, migraciones y pruebas PostgreSQL (publicado en `d3edbcd`).
3. Identidad/catálogo locales explícitos, operaciones y validaciones (preparado localmente).
4. Pruebas ampliadas de aislamiento, concurrencia y errores (este bloque).
5. Documentación final y recorrido local reproducible.

Las pantallas React, el Dockerfile de despliegue y la integración real se trabajan
aparte. No hay checkout ni creación de pedidos dentro de este servicio.
