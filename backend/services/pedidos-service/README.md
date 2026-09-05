# Pedidos Service

Servicio de pedidos de Pedidos360. Java 21, Spring Boot 4.1.1 y Maven Wrapper 3.9.15.

## Estado

Implementados: entidad Pedido + LineaPedido, repositorios JPA, migración Flyway para PostgreSQL,
máquina de estados de pedido, endpoints de creación/consulta/cambio de estado y manejo de errores.
La interacción con el catálogo (precios/restaurantes) está simulada con mocks; la identidad real
proviene de la capa de seguridad (I4/BFF).

Reglas principales:
- El pedido y sus líneas persisten en una transacción y guardan una **copia del precio** aplicado a la compra.
- La máquina de estados solo permite transiciones válidas (ver `EstadoPedido`); una transición inválida devuelve 400/409.
- El total se calcula en el backend a partir de los ítems; no se confía en un precio o total enviado por el cliente.
- El `usuarioId` se resuelve desde la identidad autenticada, no desde el cuerpo de la petición.

## Base local

Requiere Docker Desktop con el motor Linux funcionando.

Desde esta carpeta, en PowerShell:

```powershell
Copy-Item .env.example .env.local
```

Hacer la copia solo si no existe `.env.local`. Editar `DB_PASSWORD` antes de iniciar la base.

```powershell
docker compose --env-file .env.local up -d --wait
.\mvnw.cmd spring-boot:run
```

Spring carga `.env.local` desde el directorio de ejecución. El puerto local de PostgreSQL es 5435; si se cambia `DB_PORT`, actualizar también `DB_URL`.

## Configuración

| Variable | Uso |
| --- | --- |
| `DB_URL` | URL JDBC de PostgreSQL; local: `jdbc:postgresql://localhost:5435/pedidos360_pedidos`. |
| `DB_USERNAME` | Usuario PostgreSQL. |
| `DB_PASSWORD` | Contraseña, sin valor incorporado al código. |
| `DB_NAME`, `DB_PORT` | Nombre de base y puerto publicado por el Compose local. |
| `SERVER_PORT` | Puerto HTTP; 8085 por defecto. |
| `SERVER_ADDRESS` | Dirección de escucha; 127.0.0.1 por defecto. |

En AWS se configurarán `DB_URL`, `DB_USERNAME` y `DB_PASSWORD` para RDS desde el entorno de despliegue. No usar el Compose local para desplegar RDS ni subir credenciales.

Flyway administra el esquema `pedidos` y su historial de migraciones. Hibernate usa `ddl-auto: validate`: valida el modelo, no modifica tablas automáticamente.

## Salud

```powershell
Invoke-RestMethod http://localhost:8085/actuator/health
```

Debe devolver `status: UP`. La salud depende también de la base de datos.

## Seguridad

La validación del token la hace el BFF (Integrante 4). Este servicio aplica autorización sobre los
pedidos (pertenencia del recurso) usando la identidad confiable que llega de la capa de seguridad.
La identidad simulada (`pedidos.identidad-local`) está **deshabilitada por defecto** y solo aplica en
el perfil `local` con loopback, para desarrollo.
