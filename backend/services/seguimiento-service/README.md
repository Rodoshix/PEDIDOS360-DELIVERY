# Seguimiento Service

Servicio de seguimiento de pedidos de Pedidos360. Java 21, Spring Boot 4.1.1 y Maven Wrapper 3.9.15.

## Estado

Implementados: entidades `Seguimiento` (estado actual) y `SeguimientoEvento` (historial), repositorios JPA y migración Flyway para PostgreSQL.
Todavía no hay endpoints de seguimiento ni autenticación; se incorporan en el siguiente bloque.
La base final estará en AWS RDS. El PostgreSQL de Docker es exclusivamente para desarrollo local.

## Modelo

- `Seguimiento`: una entrada por pedido (`pedidoId` único) con su estado actual.
- `EstadoSeguimiento`: enum con RECIBIDO, EN_PREPARACION, LISTO, EN_CAMINO, ENTREGADO, CANCELADO, RECHAZADO y DEVOLUCION.
- `SeguimientoEvento`: historial de cambios de estado con fecha de ocurrencia y nota opcional.
- Fechas en UTC y versión en `Seguimiento` para detectar actualizaciones concurrentes.
- No guarda datos sensibles del pedido; solo la referencia `pedidoId` y el estado.

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

Spring carga `.env.local` desde el directorio de ejecución. Docker Compose usa el mismo archivo mediante `--env-file`. El puerto local de PostgreSQL es 5434; si se cambia `DB_PORT`, actualizar también `DB_URL`.

La base conserva sus datos en un volumen. Para detenerla conservando los datos:

```powershell
docker compose --env-file .env.local down
```

Las variables iniciales de usuario y contraseña de PostgreSQL se aplican al crear el volumen por primera vez; editar el archivo después no cambia las credenciales de una base existente.

## Configuración

| Variable | Uso |
| --- | --- |
| `DB_URL` | URL JDBC de PostgreSQL; local: `jdbc:postgresql://localhost:5434/pedidos360_seguimiento`. |
| `DB_USERNAME` | Usuario PostgreSQL. |
| `DB_PASSWORD` | Contraseña, sin valor incorporado al código. |
| `DB_NAME`, `DB_PORT` | Nombre de base y puerto publicado por el Compose local. |
| `SERVER_PORT` | Puerto HTTP; 8088 por defecto. |
| `SERVER_ADDRESS` | Dirección de escucha; 127.0.0.1 por defecto. |

En AWS se configurarán `DB_URL`, `DB_USERNAME` y `DB_PASSWORD` para RDS desde el entorno de despliegue, con TLS y acceso de red autorizado. No usar el Compose local para desplegar RDS ni subir credenciales.

Flyway administra las migraciones. Hibernate usa `ddl-auto: validate`: valida el modelo, no modifica tablas automáticamente. La cuenta de migración necesita permisos para crear las tablas; debe coordinarse con el responsable de RDS. Después de aplicar una migración compartida, agregar otra versión en vez de modificar la anterior.

## Pruebas

Con Docker Desktop funcionando:

```powershell
.\mvnw.cmd verify
```

Las pruebas levantan PostgreSQL 17 temporal mediante Testcontainers, ejecutan la migración y verifican persistencia, unicidad por pedido, historial y salud HTTP. Usan una base aislada, no RDS ni el volumen de desarrollo. Los contenedores de prueba se eliminan al terminar.

Para comprobar únicamente la compilación:

```powershell
.\mvnw.cmd test-compile
```

Esto no acredita que la persistencia funcione.

## Salud

Con la base disponible y el servicio iniciado:

```powershell
Invoke-RestMethod http://localhost:8088/actuator/health
```

Debe devolver `status: UP`. La salud ahora depende también de la base de datos.
Antes de exponer operaciones de seguimiento fuera del entorno local debe completarse su control de acceso.

Seguimiento: Issue #7; rama `feature/i4-7-seguimiento-service`.
