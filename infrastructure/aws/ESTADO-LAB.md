# Estado verificado del laboratorio — 2026-09-13

Este registro describe comprobaciones realizadas; no implica que la aplicacion
este desplegada. No contiene passwords, tokens ni huellas de secretos.

## EC2 y RDS

- EC2 `pedidos360-app`: activa, Ubuntu 24.04, Systems Manager online.
- RDS `pedidos360-db`: disponible, PostgreSQL, `db.t3.micro`, Single-AZ,
  20 GiB gp2, acceso publico desactivado (consultado por API).
- Seis bases y roles propios: usuarios, restaurantes, productos, carrito,
  pedidos y pagos, con prefijo `pedidos360_`.
- Usuarios habilitados con passwords aleatorias independientes generadas en EC2;
  permisos generales PUBLIC retirados de las seis bases.
- La cuenta maestra no se usa como credencial de las aplicaciones.

## Material privado

- Directorio definitivo: `/opt/pedidos360/private` en EC2, no en Git.
- Worker reutilizado desde el archivo local ignorado de Pagos, enviado mediante
  RSA-OAEP sobre SSM; no se incluyo el valor original en los parametros de SSM.
- Certificados internos de siete servicios y truststore generados y validados
  mediante el preparador; archivos DB conservados sin cambios.
- Bundle regional RDS descargado por HTTPS desde
  `https://truststore.pki.rds.amazonaws.com/us-east-1/us-east-1-bundle.pem`.
  Sus tres certificados CA pasaron validacion de tipo y vigencia.
- 24 archivos en `tls/` y `secrets/`: UID/GID 10001, modo 0400.
  Directorios privados root con acceso restringido (0700).
- Se comparo el contenido de todos los secretos antes y despues del ajuste de
  permisos, sin imprimir valores ni huellas: no cambiaron.
- La clave y el directorio temporal de transferencia fueron eliminados tras el
  resultado satisfactorio; el secreto definitivo del worker se conserva.

## Pruebas reales de conectividad

Se ejecutaron desde EC2 mediante psycopg2, leyendo los archivos privados:

- **6/6** conexiones a la base propia, identidad correcta y TLS activo,
  usando `sslmode=verify-full` y el bundle regional definitivo.
- **30/30** intentos cruzados (cada usuario contra las otras cinco bases)
  rechazados por falta de permiso de conexion.
- Estas pruebas no cambiaron datos ni permisos de RDS.

Referencia operativa SSM del bloque CA/permisos/conexiones:
`dc25bf14-606b-4bc6-a443-3e78756fcd44` (Success).

## Pendiente antes de considerar el despliegue terminado

- Backup seguro del material privado y procedimiento de rotacion/recuperacion.
- Validar el worker contra Entra (la transferencia no valida su vigencia).
- Imagenes trazables en ECR, configuracion real del despliegue y preflight completo.
- Lectura de los montajes por Docker, handshake entre servicios y migraciones Flyway.
- Salud de los ocho contenedores y persistencia despues de reinicio.
- HTTPS publico/API Gateway, frontend/redirects Entra y recorrido con dos cuentas.
- Presupuesto y apagado/limpieza del laboratorio. RDS y almacenamiento pueden
  seguir consumiendo presupuesto aunque no se este usando la aplicacion.

No ejecutar de nuevo `prepare` ni `complete` sobre este material terminado.
No eliminar o regenerar los archivos DB: sus valores ya estan configurados en RDS.
