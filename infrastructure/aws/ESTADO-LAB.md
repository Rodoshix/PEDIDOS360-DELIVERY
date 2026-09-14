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

## Imagenes y acceso publico: avance del 13 de septiembre

- Ocho imagenes publicadas en ECR `pedidos360-{bff,usuarios,restaurantes,
  productos,carrito,pedidos,pagos,frontend}`, etiqueta inmutable
  `5d4601905c870efa2e2ca9b3e850d6ef262f4a02`.
- Backend: Maven verify correcto; 304 pruebas ejecutadas sin fallos y dos
  pruebas live omitidas. Restaurantes y productos no tienen suites de pruebas.
- Frontend: lint, 208 pruebas y build correctos. Digest ECR confirmado:
  `sha256:beab104ae433855c627bcc33b1fb344f643c7fb5d64ba262d4ec99e1055c868e`.
- HTTP API desplegada: `pedidos360-public` (`65bce807i0`). URL:
  `https://65bce807i0.execute-api.us-east-1.amazonaws.com`.
  Web, JavaScript, CSS y ruta SPA `/carrito` responden 200 por HTTPS.
- Lambda `pedidos360-edge`, Node.js 22, 256 MiB, timeout 25 s, activa en la VPC.
  SG `sg-04ffbc3689a1d808e`: sin entrada y salida solo a EC2 SG en 8080/8443.
  EC2 permite esos dos puertos exclusivamente desde dicho SG; sin CIDR publico.
  Se usa LabRole existente, no un rol dedicado de minimo privilegio (limitacion
  del laboratorio). No se creo Function URL, NAT, balanceador ni CloudFront.
- Stage `$default`, throttling 5 solicitudes/s y rafaga 10. Rutas `/api` y
  `/api/{proxy+}` requieren JWT Entra con scope `access_as_user`; el BFF
  vuelve a validar el token. `$default` solo sirve estaticos GET/HEAD mediante
  el filtro de Lambda. Retencion del log Lambda: siete dias.
- Usuario registro el redirect SPA en Entra y mantuvo ambos localhost.
  Falta la prueba interactiva de inicio de sesion.
- Ocho contenedores saludables. Configuracion en EC2:
  `/opt/pedidos360/deploy-edge`, usando `compose.edge.yml` y preflight Linux.
  El frontend usa un bridge exclusivo no-internal: Docker suprimia la
  publicacion de puertos en la red internal. Sigue ligado a 172.31.91.221,
  sin miembros backend, secretos ni proxy `/api` en Nginx.
- Prueba real Lambda -> BFF: TLS verificado y bearer invalido rechazado 401.
  Desde EC2, una CA incorrecta tambien fue rechazada. No se desactivo TLS.
- Auditoria SQL de solo lectura: historiales Flyway correctos en esquemas
  usuarios (2 filas), restaurantes (3), productos (4), carrito (2), pedidos (2),
  pagos (3), todos con TLS. SSM: `a4c0ea90-e22a-4f0a-bcb9-1f88caa35e8d`.
- Entra emitio token worker para la API con rol `Pedidos.Confirmar`, sin
  registrar ni transferir el token. Esto no sustituye una prueba de pago.
- Pruebas negativas publicas: API sin token 401, `/internal/pedidos` 404,
  `/.env` 400. No se usaron cuentas reales ni se crearon pedidos de prueba.

## Prueba interactiva y recuperacion posterior

- Prueba realizada en el navegador interno con una cuenta autenticada: catalogo,
  carrito, creacion del pedido #1 por CLP 6.990 con direccion ficticia,
  vaciado automatico del carrito y pago simulado #1 aprobado. El pedido paso a
  Confirmado y aparecio en el historial. No hubo cobro real.
- El intento anterior sin perfil fue reconciliado mediante historial vacio
  despues de crear el perfil. No se repitio a ciegas ni se observo duplicado.
- Defecto pendiente de UX: falta de perfil produce mensajes de pedido no
  encontrado/resultado incierto. Los ajustes del frontend NO estan implementados.
- Tras End Lab/Start Lab, EC2 quedo detenida y fue iniciada manualmente por el
  usuario. Start Lab por si solo no garantiza la disponibilidad del despliegue.
- Hubo agotamiento de CPUCreditBalance y modo Standard: vmstat mostro steal
  69-81%, arranques Java de 276 y 308 segundos y timeouts del healthcheck de 8 s.
  El usuario habilito Unlimited aceptando posible consumo adicional. Los seis
  servicios recuperaron salud; se iniciaron Pagos/BFF pendientes con Compose.
- En un ciclo posterior los ocho contenedores arrancaron automaticamente; el
  usuario confirmo que la aplicacion volvio a funcionar despues de esperar.
  Esto es evidencia reportada por el usuario, no una auditoria SSM del reinicio.
  Docker restart no aplica la espera de dependencias de Compose. No se promete
  un tiempo fijo ni se han corregido los umbrales del healthcheck.
- La prueba del companero quedo bloqueada por 502 durante estos problemas de
  disponibilidad. No demuestra aislamiento entre dos cuentas ni fallo de roles.
- El perfil CLI local fue rechazado por la politica del laboratorio
  `voc-cancel-cred`; no se modificaron politicas para eludir esa restriccion.

## Pendiente antes de considerar el despliegue terminado

- Backup seguro del material privado y procedimiento de rotacion/recuperacion.
- Aislamiento con dos cuentas en AWS, rechazo 403 y prueba publica explicita
  con token invalido. El recorrido completo de una cuenta ya fue comprobado.
- Corregir UX sin perfil y textos de desarrollo/pago simulado; probar y publicar
  nueva imagen. No confundir esos pendientes con cambios ya desplegados.
- Robustecer arranque/healthchecks y comprobar recuperacion si cambia la IP privada.
- Presupuesto y apagado/limpieza del laboratorio. RDS y almacenamiento pueden
  seguir consumiendo presupuesto aunque no se este usando la aplicacion.

No ejecutar de nuevo `prepare` ni `complete` sobre este material terminado.
No eliminar o regenerar los archivos DB: sus valores ya estan configurados en RDS.
