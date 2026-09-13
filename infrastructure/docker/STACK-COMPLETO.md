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

## Bloques siguientes (pendientes)

Evidencia local (2026-09-13): ocho imágenes construidas; `Test-LocalTls` pasó
confianza positiva/negativa y `Test-Stack` terminó con `STACK_OK` y código 0.
Los 14 contenedores estuvieron saludables. Se verificaron proxy HTTPS al BFF,
catálogo HTTPS interno y rechazo 401 de pedidos sin token. Los recursos
temporales se retiraron; las bases previas no se modificaron.

1. Preparar configuración real y registrar la URI SPA local para Docker.
2. Recorrido de navegador sobre Docker: catálogo, carrito, pedido, pago simulado.
3. Registrar evidencias, revisión final y PR. El stack manual existente se conserva.

La administración pendiente del issue #50 y los casos reales pendientes del #48
no se consideran terminados por construir estas imágenes.
