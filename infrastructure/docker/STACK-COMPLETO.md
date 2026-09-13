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

## Bloques siguientes

1. Resolver transporte interno: BFF, Carrito y clientes de Pedidos/Pagos rechazan
   HTTP fuera de loopback. No cambiar indiscriminadamente a permitir cualquier
   host HTTP. Definir TLS interno o una política explícita para el entorno local
   aislado; conservar validación JWT, destinos fijos y bloqueo de redirecciones.
2. Compose nuevo e independiente: servicios, bases persistentes por servicio,
   redes, dependencias saludables y publicación mínima en loopback. Conservar
   los Compose existentes como configuraciones separadas.
3. Configuración Entra y worker externa al repositorio y a las imágenes.
   Elegir puertos sin interferir con el entorno manual; registrar la URI local
   de redirección correspondiente antes del recorrido.
4. Arranque conjunto y prueba de navegador: catálogo, carrito, pedido y pago
   simulado. Documentar salud, parada, persistencia y diagnóstico.

La administración pendiente del issue #50 y los casos reales pendientes del #48
no se consideran terminados por construir estas imágenes.
