# Repartidores Service

Servicio de repartidores de Pedidos360. Java 21, Spring Boot 4.1.1 y Maven Wrapper 3.9.15.

## Estado

Implementados: entidad `Repartidor` (perfil y disponibilidad), entidad `AsignacionRepartidor` (asignaciones), repositorios JPA, migración Flyway y endpoints REST de perfiles, disponibilidad y asignaciones con validaciones.
Se incluye identidad simulada local; Entra ID y la validación real de tokens siguen pendientes.
La base final estará en AWS RDS. El PostgreSQL de Docker es exclusivamente para desarrollo local.

## Modelo

- `Repartidor`: perfil vinculado a su identidad externa (`tenantId` + `entraObjectId`, únicos).
- `EstadoDisponibilidad`: DISPONIBLE, OCUPADO, EN_CAMINO, EN_PAUSA, DESCONECTADO, INACTIVO y SUSPENDIDO.
- `Vehiculo`: MOTO, BICICLETA y AUTO.
- `AsignacionRepartidor`: asignación de un pedido a un repartidor (`pedidoId` único), con estado y fecha.
- `EstadoAsignacion`: ASIGNADA, EN_CAMINO, ENTREGADA y CANCELADA.
- Fechas en UTC y versión en `Repartidor` para detectar actualizaciones concurrentes.

## API

| Método | Ruta | Comportamiento |
| --- | --- | --- |
| POST | `/repartidores` | Crea el perfil de la identidad actual; 201 con Location. Duplicado: 409. |
| GET | `/repartidores/me` | 200 con el perfil actual; 404 si no existe. |
| GET | `/repartidores/{id}` | 200; propietario o ADMIN del mismo directorio. |
| GET | `/repartidores?pagina=0&tamanio=20` | 200; solo ADMIN, paginado, ordenado por ID ascendente. |
| PUT | `/repartidores/{id}` | 200 con el perfil actualizado; propietario o ADMIN. |
| PUT | `/repartidores/{id}/disponibilidad` | 200; cambia el estado de disponibilidad. |
| DELETE | `/repartidores/{id}` | Pasa el repartidor a INACTIVO; propietario o ADMIN; 204. |
| GET | `/repartidores/{id}/asignaciones` | 200; lista las asignaciones del repartidor. |
| POST | `/repartidores/{id}/asignaciones` | Asigna un pedido; 201; pedido duplicado: 409. |
| PUT | `/repartidores/{id}/asignaciones/{pedidoId}/estado` | 200; cambia el estado de una asignación. |

Los cuerpos se envían en JSON (`application/json`). Se rechazan campos desconocidos.

## Seguridad

- Los endpoints `/repartidores` y `/repartidores/**` requieren autenticación.
- `/actuator/health` y `/actuator/health/**` son públicos.
- La identidad local de prueba se activa solo con `repartidores.identidad-local.enabled=true`, el perfil `local` y escucha en loopback (`127.0.0.1`).
- La validación de tokens Entra ID/JWT se incorporará en un bloque posterior.

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

Spring carga `.env.local` desde el directorio de ejecución. Docker Compose usa el mismo archivo mediante `--env-file`. El puerto local de PostgreSQL es 5435; si se cambia `DB_PORT`, actualizar también `DB_URL`.

La base conserva sus datos en un volumen. Para detenerla conservando los datos:

```powershell
docker compose --env-file .env.local down
```

Las variables iniciales de usuario y contraseña de PostgreSQL se aplican al crear el volumen por primera vez; editar el archivo después no cambia las credenciales de una base existente.

Para probar la API localmente con la identidad simulada, habilitar `LOCAL_IDENTITY_ENABLED=true` en `.env.local`, usar el perfil `local` y definir `LOCAL_ROLES` (ADMIN o CLIENTE).

## Configuración

| Variable | Uso |
| --- | --- |
| `DB_URL` | URL JDBC de PostgreSQL; local: `jdbc:postgresql://localhost:5435/pedidos360_repartidores`. |
| `DB_USERNAME` | Usuario PostgreSQL. |
| `DB_PASSWORD` | Contraseña, sin valor incorporado al código. |
| `DB_NAME`, `DB_PORT` | Nombre de base y puerto publicado por el Compose local. |
| `SERVER_PORT` | Puerto HTTP; 8087 por defecto. |
| `SERVER_ADDRESS` | Dirección de escucha; 127.0.0.1 por defecto. |
| `LOCAL_IDENTITY_ENABLED` | `false` por defecto; `true` habilita identidad simulada solo con perfil `local` y loopback. |
| `LOCAL_TENANT_ID`, `LOCAL_OBJECT_ID` | Identidad simulada. |
| `LOCAL_ROLES` | `ADMIN` por defecto; admite `ADMIN`, `CLIENTE` o ambos separados por coma. |

En AWS se configurarán `DB_URL`, `DB_USERNAME` y `DB_PASSWORD` para RDS desde el entorno de despliegue, con TLS y acceso de red autorizado. No usar el Compose local para desplegar RDS ni subir credenciales.

Flyway administra las migraciones. Hibernate usa `ddl-auto: validate`: valida el modelo, no modifica tablas automáticamente. La cuenta de migración necesita permisos para crear las tablas; debe coordinarse con el responsable de RDS. Después de aplicar una migración compartida, agregar otra versión en vez de modificar la anterior.

## Pruebas

Con Docker Desktop funcionando:

```powershell
.\mvnw.cmd verify
```

Las pruebas levantan PostgreSQL 17 temporal mediante Testcontainers, ejecutan la migración y verifican persistencia, unicidad, CRUD HTTP, asignaciones, validaciones, roles CLIENTE/ADMIN y restricciones del modo local. Usan una base aislada y no leen `.env.local`. Los contenedores de prueba se eliminan al terminar.

Para comprobar únicamente la compilación:

```powershell
.\mvnw.cmd test-compile
```

Esto no acredita que la persistencia funcione.

## Salud

Con la base disponible y el servicio iniciado:

```powershell
Invoke-RestMethod http://localhost:8087/actuator/health
```

Debe devolver `status: UP`. La salud ahora depende también de la base de datos.
Antes de exponer operaciones de repartidores fuera del entorno local debe completarse su control de acceso.

Seguimiento: Issue #12; rama `feature/i4-12-repartidores-service`.
