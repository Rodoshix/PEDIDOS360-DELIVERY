# Dockerizar componentes 1 — Issue #25

Rama `feature/i1-25-docker`. Solo frontend, Usuarios y Carrito. No BFF, integración
HTTP/JWT real, AWS ni componentes del integrante 5. El Compose general de esta carpeta
y los Compose locales de PostgreSQL se conservan intactos.

## Bloque 1 publicado

Frontend publicado en `e9a4fa5`; build multietapa y Nginx sin root. Consultar
[instrucciones del frontend](../../frontend/README.md).

## Bloque 2: imágenes de Usuarios y Carrito

Cada servicio usa su carpeta como contexto de build y tiene `.dockerignore` de lista
permitida. Solo ingresan `pom.xml`, Wrapper y fuentes de `src/main`; quedan fuera
`.env.local`, claves, `target`, pruebas y configuración personal de Maven. La imagen
final contiene JRE 21 y el JAR, sin Maven/JDK, fuentes ni configuración de base local.

Bases [Eclipse Temurin](https://hub.docker.com/_/eclipse-temurin) fijadas por digest.
El builder usa JDK 21 y Maven Wrapper 3.9.15 del repositorio; el runtime usa JRE 21 y
UID/GID `10001`. Actualizar bases deliberadamente y volver a probar al mantenerlas.
Si cambia la versión del artefacto en `pom.xml`, ajustar el nombre del JAR en `COPY`.

### Primero verificar, después construir

Desde la raíz del repositorio, PowerShell 7, con JDK 21 y Docker Desktop Linux:

```powershell
Push-Location backend/services/usuarios-service
try {
  .\mvnw.cmd -B -ntp verify
  if ($LASTEXITCODE -ne 0) { throw 'Fallaron pruebas de Usuarios' }
} finally { Pop-Location }
Push-Location backend/services/carrito-service
try {
  .\mvnw.cmd -B -ntp verify
  if ($LASTEXITCODE -ne 0) { throw 'Fallaron pruebas de Carrito' }
} finally { Pop-Location }

docker build -t pedidos360-usuarios:i1-25-smoke backend/services/usuarios-service
if ($LASTEXITCODE -ne 0) { throw 'Falló el build de Usuarios' }
docker build -t pedidos360-carrito:i1-25-smoke backend/services/carrito-service
if ($LASTEXITCODE -ne 0) { throw 'Falló el build de Carrito' }
```

En Linux usar `sh mvnw -B -ntp verify` desde cada servicio. Las pruebas usan
Testcontainers/PostgreSQL y no leen `.env.local`. No montar el socket de Docker en
BuildKit ni copiar las credenciales del host para hacerlas correr dentro del build.

Los Dockerfiles usan `-Dmaven.test.skip=true` **solo para empaquetar**: no ejecutan ni
compilan pruebas en el builder. Un build exitoso no sustituye el `verify` anterior.
Este comportamiento está definido por [Maven Surefire](https://maven.apache.org/surefire/maven-surefire-plugin/test-mojo.html).
El cache de Maven se comparte con bloqueo entre builds; no contiene `.env.local`.

### Configuración de ejecución

| Variable | Usuarios | Carrito |
| --- | --- | --- |
| `SERVER_PORT` | `8081` | `8084` |
| `SERVER_ADDRESS` | `0.0.0.0` dentro del contenedor | `0.0.0.0` dentro del contenedor |
| `LOCAL_IDENTITY_ENABLED` | `false` | `false` |
| `DB_URL` | JDBC hacia PostgreSQL de Usuarios | JDBC hacia PostgreSQL de Carrito |
| `DB_USERNAME`, `DB_PASSWORD` | Requeridos al ejecutar | Requeridos al ejecutar |

No hay contraseñas incorporadas a las imágenes. Dentro del contenedor, `localhost`
no es la base del host: usar DNS de la red Docker, por ejemplo
`jdbc:postgresql://usuarios-db:5432/pedidos360_usuarios`. La configuración concreta
con volúmenes persistentes se preparará en el bloque de Compose.

La escucha en `0.0.0.0` permite recibir tráfico dentro de Docker, no es una autorización.
Publicar puertos **solo en 127.0.0.1** para este entorno local. El healthcheck consulta
`/actuator/health` y respeta `SERVER_PORT`; su estado `UP` incluye conexión a PostgreSQL,
pero no acredita integración con Microsoft ni con otros servicios.

Se conserva la protección: identidad simulada apagada, endpoints privados responden
401; enviar un Bearer ficticio o cabeceras de rol no concede acceso. Esta imagen no
implementa validación JWT. No habilitar la identidad simulada en entornos expuestos.

### Prueba de humo aislada

Con las dos imágenes anteriores construidas:

```powershell
./infrastructure/docker/Test-I1ServiceImages.ps1
```

El script crea una red exclusiva, dos PostgreSQL 17 con datos en `tmpfs` y dos APIs,
con nombres y etiquetas aleatorios. No publica PostgreSQL; publica APIs en puertos
aleatorios de loopback. Usa contraseñas sintéticas en memoria, no imprime valores ni
lee archivos de entorno. No toca bases, servicios ni volúmenes existentes.

Comprueba health `UP` sin detalles, 401 sin identidad/con Bearer inválido, UID 10001,
ausencia de herramientas de build y `.env.local`, migraciones Flyway y reinicio de la
API conservando el historial de migraciones de su base de prueba. Las APIs corren
con raíz de solo lectura, `/tmp` temporal, sin capabilities y con `no-new-privileges`.

En `finally`, el script verifica etiquetas y elimina **solo** contenedores/red creados
en esa ejecución. Los datos sintéticos en RAM desaparecen, las imágenes se conservan.
Si se interrumpe forzosamente PowerShell, revisar los recursos con etiqueta
`pedidos360.smoke` antes de retirar un recurso; no usar limpieza global de Docker.

Esta prueba no demuestra persistencia después de recrear PostgreSQL ni un flujo
funcional entre servicios. Eso no se infiere del health. Los volúmenes persistentes,
dependencias y arranque conjunto corresponden al siguiente bloque de Compose.

## Resultado del bloque 2

- `mvnw verify`: Usuarios **27 pruebas**, Carrito **52 pruebas**; cero fallos, errores
  u omisiones. Usaron PostgreSQL temporal independiente de las bases del desarrollador.
- Ambas imágenes construidas desde fuentes, sin copiar el JAR del host.
- Script de humo completo aprobado en ambas imágenes: salud, protección, usuario,
  Flyway y reinicio. Los grupos `liveness`/`readiness` del health son nombres públicos;
  no se permiten detalles ni componentes internos en la comprobación.
- Contenedores y red de humo retirados; imágenes conservadas. Ningún volumen existente
  fue modificado o eliminado. El Compose general y los Compose locales no cambiaron.

Bloque 2 completado para publicación. El frontend permanece publicado en `e9a4fa5`.
Siguiente: Compose propio del Integrante 1, con volúmenes persistentes separados,
variables y dependencias de arranque. La integración HTTP/BFF sigue fuera del issue.
