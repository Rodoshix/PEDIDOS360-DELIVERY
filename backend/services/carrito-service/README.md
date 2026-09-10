# Carrito Service

Integrante 1, issue #19. Java 21, Spring Boot 4.1.1 y Maven Wrapper 3.9.15, como Usuarios.

## Estado del primer bloque

Base ejecutable, salud pública y seguridad cerrada por defecto. **Todavía no implementa
las operaciones del carrito, persistencia ni identidad local.** No conecta Azure, BFF,
Usuarios, Catálogo, Pedidos ni AWS. Las dependencias de PostgreSQL/Flyway y su Compose
se incorporarán junto al modelo en el siguiente bloque; no se requiere Docker todavía.

## Ejecución local

Desde la raíz del repositorio en PowerShell, con JDK 21 instalado:

```powershell
Set-Location backend/services/carrito-service
java -version
if (-not (Test-Path .env.local)) {
  Copy-Item .env.example .env.local
}
.\mvnw.cmd verify
.\mvnw.cmd spring-boot:run
```

La primera ejecución puede descargar Maven y dependencias. Conservar `.env.local` si ya
existe; no se versiona. `SERVER_PORT` vale 8084 por defecto y `SERVER_ADDRESS` vale
127.0.0.1. No se necesita contraseña ni secreto de cliente. En otra terminal:

```powershell
Invoke-RestMethod http://127.0.0.1:8084/actuator/health
```

Debe devolver `status: UP`. En este bloque solo comprueba el proceso, **no una base ni
integración real**. `/carrito` y cualquier ruta distinta de salud responden 401 sin
sesión. Enviar Bearer, usuario o roles en cabeceras no habilita acceso. No hay login
por contraseña, cookies, CORS habilitado ni redirección a Microsoft en este servicio.

Las pruebas arrancan el servidor en un puerto aleatorio de loopback y comprueban salud,
rechazo de operaciones anónimas y credenciales ficticias, ausencia de cookies y de
redirecciones, y bloqueo de endpoints de diagnóstico. No requieren servicios externos.

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
- Cantidad positiva; quitar se hace con DELETE, no enviando cero. Definir y probar
  límites de cantidad y líneas al implementar las operaciones.
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

1. Base del servicio y contrato local (este bloque).
2. Modelo, migraciones y pruebas PostgreSQL.
3. Identidad/catálogo locales explícitos, operaciones y validaciones.
4. Pruebas ampliadas de aislamiento, concurrencia y errores.
5. Documentación final y recorrido local reproducible.

Las pantallas React, el Dockerfile de despliegue y la integración real se trabajan
aparte. No hay checkout ni creación de pedidos dentro de este servicio.
