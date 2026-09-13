# Stack completo I5 — issue #53

Rama: `feature/i5-docker-stack-completo`, base `develop`.
No incluye Seguimiento ni Repartidores y no despliega recursos AWS.

## Bloque 1: imágenes

Se reutilizan las imágenes de Frontend, Usuarios, Carrito, Pedidos y Pagos.
Se agregan Dockerfiles y contextos restrictivos para BFF, Restaurantes y Productos.
Cada imagen nueva usa Java 21 con digest fijo, build multi-stage, usuario no root
y healthcheck. Los archivos `.env`, llaves y salidas `target` no entran al contexto.
Los wrappers Maven agregados reutilizan la versión de los servicios existentes.

Desde la raíz del repositorio:

```powershell
docker build -t pedidos360-bff:i5-stack backend/bff
docker build -t pedidos360-restaurantes:i5-stack backend/services/restaurantes-service
docker build -t pedidos360-productos:i5-stack backend/services/productos-service
./infrastructure/docker/Test-I5Images.ps1
```

El smoke test crea únicamente contenedores con prefijo aleatorio `i5-images-`,
una red interna sin puertos publicados y PostgreSQL en tmpfs. Al terminar elimina
esos recursos temporales, incluidos sus datos de prueba. No borra imágenes,
volúmenes o contenedores existentes. Las imágenes quedan para reutilizarse.
No usa credenciales reales de Entra. Comprueba arranque/salud, no el flujo autenticado.
Los tests de dominio se ejecutan fuera del build; no se monta el socket Docker.

## Bloque 2: Compose y transporte TLS

El Compose completo está en `stack/compose.yml`. Publica únicamente el frontend
en loopback (5180 por defecto). Nginx envía `/api/` al BFF por HTTPS con certificado
verificado. Los clientes Java conservan sus reglas existentes de HTTPS, destinos
fijos, JWT y bloqueo de redirecciones. No se agregaron excepciones para HTTP.

Cada API monta solo su clave privada, el truststore público y los secretos que
necesita. Las seis bases no publican puertos y tienen redes/volúmenes separados.
Las APIs que validan Entra tienen salida de red para las claves públicas y tokens.
Restaurantes y Productos quedan accesibles solo dentro de las redes Docker.
Esto no implementa todavía la autorización administrativa pendiente del #50.

### Preparación local

1. Completar `stack/.env.local` siguiendo `stack/.env.example` con IDs reales,
   contraseñas diferentes para cada base y el secreto vigente del worker.
   El archivo está ignorado por Git. Formato simple `VARIABLE=valor`, sin comillas.
   Si ya existen `frontend/.env.local` y el `.env.worker.local` de Pagos,
   `./infrastructure/docker/stack/Initialize-LocalEnvironment.ps1` reutiliza esos
   IDs y el secreto, valida su coherencia y genera contraseñas aleatorias para
   las nuevas bases/TLS. No imprime valores ni sobrescribe un archivo existente.
2. Registrar `http://localhost:5180` como URI SPA en Entra (o el puerto elegido).
   Los valores VITE son públicos y se incorporan al construir; reconstruir si cambian.
3. Ejecutar desde la raíz:

```powershell
./infrastructure/docker/stack/Invoke-Stack.ps1 config
./infrastructure/docker/stack/Invoke-Stack.ps1 build
./infrastructure/docker/stack/Invoke-Stack.ps1 up
./infrastructure/docker/stack/Invoke-Stack.ps1 ps
# Parar conservando las bases:
./infrastructure/docker/stack/Invoke-Stack.ps1 down
```

El primer `up` genera certificados locales de 30 días mediante keytool/JDK 21.
También prepara archivos de secretos en `stack/secrets`, ignorados por Git,
para montarlos en contenedores de solo lectura. Son archivos locales sin cifrado:
no compartirlos ni incluirlos en respaldos públicos. No se incorporan a imágenes.
Si un secreto existente difiere del configurado, el script se detiene y exige
una rotación explícita; no reemplaza credenciales silenciosamente.
No instala confianza en Windows ni modifica el navegador. Java conserva además
las autoridades públicas del JDK para Entra. El navegador usa HTTP loopback y la
comunicación entre servicios utiliza HTTPS. Para AWS se requiere HTTPS público
y una gestión de certificados/secretos adecuada al despliegue, no este bootstrap.

No sobrescribir certificados o cambiar contraseñas de bases ya inicializadas:
las credenciales de PostgreSQL no se rotan por cambiar el archivo de entorno.
Antes de renovar TLS, detener el stack y conservar una copia recuperable de
`stack/tls`; generar el directorio nuevo con la misma configuración y arrancar.
Los scripts no borran ese directorio ni los volúmenes persistentes.

### Verificación aislada

```powershell
./infrastructure/docker/stack/Test-LocalTls.ps1
./infrastructure/docker/stack/Test-Stack.ps1
```

Las pruebas generan credenciales ficticias, certificados y proyectos temporales.
`Test-LocalTls` verifica aceptación de confianza explícita y rechazo sin ella.
`Test-Stack` verifica servicios, salud del BFF a través del proxy TLS, consulta
interna del catálogo con una sonda Java y 401 sin token en el puerto 5188.
No prueba el catálogo a través del BFF autenticado: eso requiere Entra real.
Al terminar elimina únicamente su proyecto temporal, sus volúmenes y
certificados. No certifica el login Entra ni un pago real del stack.
Las imágenes de la prueba construidas con IDs ficticios deben reconstruirse
mediante `Invoke-Stack.ps1 build` antes de probar con los IDs reales.

## Evidencias y pendientes

Evidencia local (2026-09-13): ocho imágenes construidas; `Test-LocalTls` pasó
confianza positiva/negativa y `Test-Stack` terminó con `STACK_OK` y código 0.
Los 14 contenedores estuvieron saludables. Se verificaron proxy HTTPS al BFF,
catálogo HTTPS interno y rechazo 401 de pedidos sin token. Los recursos
temporales se retiraron; las bases previas no se modificaron.

Recorrido autenticado (2026-09-13, `http://localhost:5180`, navegador integrado):

- Inicio de sesión real con Microsoft Entra completado por el usuario.
- Consulta y creación de perfil ficticio `Prueba Docker` mediante BFF/Usuarios.
  Los datos de contacto son ficticios; el perfil queda asociado a la sesión de prueba.
- Catálogo de restaurantes y productos consultado mediante BFF; Burger 360,
  Hamburguesa Clásica por $6.990, una unidad agregada al carrito.
- Pedido #1 creado con dirección ficticia; total $6.990 y carrito vaciado.
- Pago simulado con tarjeta #1 aprobado; pedido #1 en estado Confirmado.
  El stack mantiene activado el worker Entra y las conexiones internas HTTPS.
- Volver a Pago muestra el pago #1 aprobado, sin ofrecer otro registro.
- Los 14 contenedores siguen saludables. No se usó dinero real ni se cambiaron
  las bases del entorno manual. Los datos de prueba se conservan en este Compose.

Prueba con segunda cuenta invitada, rol Cliente (2026-09-13):

- Sesión distinta confirmada en el navegador; no aparece el perfil anterior.
- Perfil ficticio independiente creado. Consulta SQL de solo lectura confirma
  que se conservan ambos perfiles: #1 Prueba Docker y #2 Prueba Segunda cuenta.
- Historial propio vacío. Acceso directo al pedido #1 rechazado con HTTP 403.
- Pago del pedido #1 inicialmente devolvía 502 sin exponer datos. Se corrigió
  `PedidosRestClient.obtener` para conservar el 403 de Pedidos con mensaje
  controlado. Tras reconstruir y reemplazar solo Pagos, la segunda cuenta recibe
  HTTP 403 en `/api/pagos/pedido/1` y la UI muestra falta de permiso.
- Suite Pagos: 69 pruebas, 0 fallos, 0 errores, 1 omitida (Entra live opcional).
  Nuevas regresiones: conservar 403, 404 como ausencia y 500 como 502.
- Carrito de la segunda cuenta vacío. No demuestra por sí solo aislamiento de
  carritos con contenido, porque el carrito anterior también estaba vacío.

Reinicio con persistencia (2026-09-13): `Invoke-Stack.ps1 down` y `up`, conservando
los seis volúmenes. Los hashes SHA-256 de los dumps de datos de las seis bases
coinciden antes y después (se excluyen únicamente las líneas aleatorias de
restricción de pg_dump). Se conservan perfiles, catálogo, carritos, pedido y pago.
No se cubrió recuperación de una operación interrumpida en mitad de un pago.

La administración pendiente del issue #50 y los casos reales pendientes del #48
no se consideran terminados por construir estas imágenes.
