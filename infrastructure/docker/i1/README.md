# Compose local — Dockerizar componentes 1

Este Compose levanta **frontend, Usuarios, Carrito y dos PostgreSQL 17**, sin BFF ni
integración funcional entre componentes. Separa los volúmenes/redes del Compose general
y de los Compose locales anteriores. No migra datos de ellos.

## Configurar

Docker Desktop con contenedores Linux y al menos unos 2 GB de memoria disponibles para
el conjunto en ejecución (más margen para builds). Los límites suman aproximadamente
1,4 GB; no constituyen un dimensionamiento de producción.

Desde la raíz del repositorio, en PowerShell:

```powershell
Set-Location infrastructure/docker/i1
if (-not (Test-Path .env.local)) { Copy-Item .env.example .env.local }
# Completar .env.local: identificadores públicos de Entra y dos contraseñas locales distintas.
docker compose --env-file .env.local config --quiet
if ($LASTEXITCODE -ne 0) { throw 'Revisar configuración' }
```

No sobrescribir `.env.local` si ya existe. Está ignorado por Git y fuera de todos los
contextos de build. No reutilizar el `.env` raíz: usa otras variables y otros componentes.
No compartir la salida de `docker compose config` sin `--quiet`, porque contiene las
contraseñas interpoladas. Variables `I1_*` ya presentes en la terminal tienen prioridad
sobre el archivo; comprobarlas localmente si la configuración difiere de lo esperado.

| Variable | Uso |
| --- | --- |
| `I1_ENTRA_CLIENT_ID`, `I1_ENTRA_TENANT_ID` | UUID públicos de tu frontend/directorio |
| `I1_ENTRA_API_SCOPE` | `api://<id-api>/access_as_user`, público |
| `I1_API_BASE_URL` | Origen del BFF futuro; por defecto `http://localhost:8080` |
| `I1_FRONTEND_PORT` | `5180`, publicado en loopback |
| `I1_USUARIOS_PORT` | `8181`, publicado en loopback |
| `I1_CARRITO_PORT` | `8184`, publicado en loopback |
| `I1_USUARIOS_DB_PASSWORD` | Obligatoria, solo para PostgreSQL de Usuarios |
| `I1_CARRITO_DB_PASSWORD` | Obligatoria, solo para PostgreSQL de Carrito |

La URI SPA de compilación se deriva de `I1_FRONTEND_PORT`: `http://localhost:5180`
por defecto. Usar `localhost` al abrir el frontend y registrar ese origen como URI SPA
en Entra para un login real. Para mantener el puerto 5173, cambiar la variable **si está
libre**; no detener Vite automáticamente. No se modifican registros de Azure aquí.

Los valores Entra/URL son argumentos públicos de compilación: nunca tokens ni client
secrets. Cambiarlos requiere reconstruir el frontend. Las contraseñas solo se inyectan
al ejecutar APIs/bases, no en las imágenes ni en los argumentos de build. Docker local
permite a usuarios con acceso al daemon inspeccionar variables: no es una bóveda de secretos.

## Construir y arrancar

Antes de construir los servicios, ejecutar sus suites `mvnw verify`, según la
[guía de imágenes](../README-i1.md). Los builds Java empaquetan sin Testcontainers.

Desde esta carpeta:

```powershell
docker compose --env-file .env.local up -d --build --wait --wait-timeout 180
if ($LASTEXITCODE -ne 0) { throw 'Revisar servicios; no borrar los volúmenes para reintentar' }
docker compose --env-file .env.local ps
```

El proyecto predeterminado es `pedidos360-i1-local`. Las imágenes, contenedores, redes
y volúmenes quedan asociados a ese proyecto. No hay `container_name` global ni volúmenes
externos. Cambiar `-p` crea otro conjunto y otras bases; conservar el nombre del proyecto
si se quieren reutilizar sus datos. No usar nombres de proyectos existentes ajenos.

- Frontend: `http://localhost:5180`, `/healthz` comprueba solo el servidor estático.
- Usuarios: `http://127.0.0.1:8181/actuator/health`.
- Carrito: `http://127.0.0.1:8184/actuator/health`.
- PostgreSQL no publica ningún puerto; cada API usa el DNS de su base y su red separada.
- Cada API espera el health de su PostgreSQL. El frontend no depende de las APIs porque
  no existe aún integración HTTP. `depends_on` coordina el arranque, no garantiza
  disponibilidad continua ni reinicia automáticamente un servicio si pasa a unhealthy.

Las tres aplicaciones corren sin root, con raíz read-only, tmpfs, capabilities retiradas
y `no-new-privileges`. Las APIs conservan `LOCAL_IDENTITY_ENABLED=false`: `/usuarios/me`
y `/carrito` devuelven **401** sin identidad. Ese resultado es esperado; no habilitar una
identidad ficticia para simular que la integración funciona. El frontend conserva los
estados de integración pendiente y no contiene simuladores de desarrollo.

## Parar y conservar datos

```powershell
docker compose --env-file .env.local down
```

`down` retira contenedores y redes, **conservando los dos volúmenes**:
`pedidos360-i1-local_usuarios_pg_data` y `pedidos360-i1-local_carrito_pg_data`.
No usar `down -v`, `volume prune` ni borrar volúmenes para solucionar errores.
Al levantar de nuevo con el mismo proyecto, PostgreSQL reutiliza los datos.
Cambiar la contraseña en `.env.local` no cambia la de un volumen ya inicializado;
la rotación o migración requiere un procedimiento aparte, no borrar la base.

## Prueba reproducible independiente

Desde la raíz del repositorio, con PowerShell 7, Node y Docker:

```powershell
./infrastructure/docker/Test-I1Compose.ps1
```

No lee tu `.env.local`. Usa el ejemplo vacío más variables temporales: IDs ficticios,
contraseñas aleatorias y puertos libres. Valida cinco servicios, puertos loopback,
bases sin puertos, salud como dependencia y rechazo de contraseñas ausentes. Construye
las imágenes y exige que los cinco servicios estén healthy, verifica HTTP del frontend
y protección 401 de las APIs.

Luego inserta un marcador **sintético** en cada base de prueba, ejecuta `down` sin `-v`,
vuelve a levantar y verifica que ambos marcadores persistan. Al terminar retira solo
ese proyecto aleatorio y sus dos volúmenes, comprobando sus etiquetas de propietario.
La eliminación ocurre **después** de la prueba de persistencia y solo sobre datos
sintéticos creados por el script. Las imágenes quedan en Docker. Si se interrumpe
forzosamente, revisar los recursos de ese proyecto concreto, sin limpiar Docker globalmente.

Esto acredita funcionamiento del Compose local y persistencia, no integración BFF,
validación JWT, login real, pagos ni tareas del integrante 5. Después de este bloque
queda la revisión final/regresiones/documentación antes del PR.

Resultado verificado del bloque 3: configuración y build correctos; los cinco servicios
healthy en ambos arranques; smoke HTTP aprobado, APIs con 401 esperado y ambos marcadores
conservados tras `down`/`up`. Se retiraron los contenedores, redes y volúmenes del proyecto
sintético, sin afectar recursos existentes. Bloque 3 completado para publicación.

Referencias: [orden de arranque](https://docs.docker.com/compose/how-tos/startup-order/)
y [variables de Compose](https://docs.docker.com/compose/how-tos/environment-variables/variable-interpolation/).
