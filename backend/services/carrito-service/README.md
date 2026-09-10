# Carrito Service

Integrante 1, issue #19. Java 21, Spring Boot 4.1.1 y Maven Wrapper 3.9.15, como Usuarios.

## Estado del segundo bloque

Base ejecutable, salud pública y seguridad cerrada por defecto. Incluye el modelo
Carrito/LineaCarrito, repositorio JPA, migración Flyway y PostgreSQL local. **Todavía no
implementa endpoints de negocio, identidad local ni catálogo.** Las operaciones del
modelo se prueban directamente, no por HTTP. No conecta Azure, BFF, Usuarios, Catálogo,
Pedidos ni AWS.

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
con otros servicios**. `/carrito` y cualquier ruta distinta de salud responden 401 sin
sesión. Enviar Bearer, usuario o roles en cabeceras no habilita acceso. No hay login
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
  Una copia obsoleta no debe sobrescribir otra; las futuras operaciones HTTP deben
  usar transacciones y convertir el conflicto en respuesta 409 sin reintento automático.
- Quitar/vaciar elimina líneas huérfanas y libera el restaurante, sin borrar el carrito
  ni su identidad. Hibernate usa `ddl-auto: validate`; Flyway crea el esquema `carrito`
  y aplica `V1__crear_carritos.sql`. Tras compartir una migración, crear una nueva versión
  para cambios posteriores. Flyway tiene limpieza deshabilitada.
- PostgreSQL refuerza unicidad, referencias, cantidades y precios. El límite de líneas
  y la coherencia del restaurante son invariantes del agregado, no validaciones de
  integración con un catálogo real. Los accesos HTTP de negocio siguen bloqueados.

### Pruebas

`mvnw.cmd verify` ejecuta pruebas de modelo y de persistencia contra PostgreSQL 17
temporal mediante Testcontainers, más pruebas HTTP con puerto aleatorio de loopback.
Cubren migración, cascadas, identidad única, búsqueda por directorio/propietario,
límites, totales, versiones obsoletas y rollback de raíz y líneas. Las HTTP verifican
salud, rechazo de credenciales ficticias, ausencia de cookies/redirecciones y bloqueo
de diagnóstico. No necesitan cuentas Azure ni datos reales.

## Contrato local propuesto para los siguientes bloques

Este contrato es una propuesta del módulo, **no endpoints ya implementados ni un acuerdo
de integración del equipo**. Se revisará antes de conectar otros servicios.

| Método | Ruta | Operación prevista |
| --- | --- | --- |
| GET | `/carrito` | Obtener el carrito de la identidad actual; vacío si no tiene líneas. |
| POST | `/carrito/items` | Agregar `productoId` y `cantidad`; sumar si ya está en el carrito. |
| PUT | `/carrito/items/{productoId}` | Reemplazar la cantidad de una línea existente. |
| DELETE | `/carrito/items/{productoId}` | Quitar una línea existente. |
| DELETE | `/carrito` | Vaciar el carrito propio. |

Reglas previstas:

- Un carrito por propietario y directorio. La identidad se resuelve en servidor, nunca
  desde el cuerpo ni cabeceras libres. No se crean claves foráneas a bases ajenas.
- El cliente solo indica producto y cantidad; no puede fijar propietario, roles,
  restaurante, precio, subtotal ni total. Se rechazan campos desconocidos.
- Cantidad positiva; quitar se hace con DELETE, no enviando cero. Los futuros DTO
  deben respetar los límites locales del modelo descritos arriba.
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
- Errores previstos: 400 por entrada inválida, 401 sin identidad, 404 por producto o
  línea inexistentes y 409 por conflictos de negocio o concurrencia. Precisar los DTO
  de respuesta junto a la implementación, usando errores controlados.

## Entregas de esta rama

1. Base del servicio y contrato local (publicado en `82d6063`).
2. Modelo, migraciones y pruebas PostgreSQL (este bloque).
3. Identidad/catálogo locales explícitos, operaciones y validaciones.
4. Pruebas ampliadas de aislamiento, concurrencia y errores.
5. Documentación final y recorrido local reproducible.

Las pantallas React, el Dockerfile de despliegue y la integración real se trabajan
aparte. No hay checkout ni creación de pedidos dentro de este servicio.
