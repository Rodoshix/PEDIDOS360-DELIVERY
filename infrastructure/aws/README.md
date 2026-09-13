# Despliegue AWS Academy — issue #55

## Estado de este bloque

Preparacion de configuracion, NO despliegue cloud validado. EC2 con Docker y
Compose fue comprobada manualmente con hello-world. RDS, entrada HTTPS publica,
API Gateway, publicacion de imagenes y recorrido real siguen pendientes.

No ejecutar `up` aun. Este Compose requiere bases, credenciales y certificados
preparados; no los crea. No modifica el Compose local ni sus volumenes.

## Arquitectura y limites

- Ocho contenedores: frontend, BFF y seis servicios. Sin PostgreSQL local.
- Una instancia RDS PostgreSQL prevista, Single-AZ, bases y usuarios separados.
  Limitar ingreso 5432 al security group de EC2; RDS no publico.
- JDBC usa el endpoint real de RDS, `sslmode=verify-full` y CA publica montada.
- HTTPS entre servicios conserva certificados por nombre DNS y truststore.
- Frontend escucha solo en 127.0.0.1:8080; BFF HTTPS solo 127.0.0.1:8443.
  Son puntos locales para configurar/probar la futura entrada, no URLs publicas.
  No abrir estos puertos en el security group para saltarse API Gateway.
- El frontend usa su configuracion de API Gateway incorporada al compilar;
  no monta el proxy /api del stack local.
- Red interna para comunicacion entre servicios y red egress para RDS/Entra.
  `internal: true` no autentica las peticiones ni reemplaza controles AWS.
- 512 MiB por JVM, 128 MiB frontend; pool maximo 5 conexiones por servicio.
  La suma de limites Java es 3.5 GiB: no es una reserva ni prueba de capacidad.
  Probar arranque escalonado y carga antes de aceptar EC2 de 4 GiB.
- Logs json-file: 10 MiB x 3 por contenedor; restart unless-stopped.
- Conserva Entra, scopes/roles y worker Pagos -> Pedidos. Sin identidad local.
- Fuera de alcance: Repartidores y Seguimiento. Cognito no confirmado.

## Configuracion y archivos privados

Copiar `.env.example` como `.env.deploy`, completar valores publicos.
Nunca ejecutar `source` sobre archivos de origen no confiable.
`IMAGE_REGISTRY` no lleva https ni barra final; `IMAGE_TAG` debe ser el SHA
del commit construido. Los cambios de URL frontend requieren recompilar su imagen.
La URL y el prefijo definitivos de API Gateway se acordaran antes de publicar.

Preparar, sin reutilizar contrasenas de desarrollo:

- `secrets/tls_password`, `secrets/worker_secret`.
- `secrets/<servicio>_db_password` para usuarios, restaurantes, productos,
  carrito, pedidos y pagos.
- `tls/<servicio>.p12` para BFF y los seis servicios, con SAN del nombre
  Docker correspondiente y localhost para health checks.
- `tls/truststore.p12`: certificados publicos de confianza para servicios
  internos y autoridades publicas de Entra. Password changeit identifica este
  almacen publico, NO debe usarse para las claves privadas.
- `tls/rds-ca.pem`: bundle oficial de CA de RDS obtenido por HTTPS:
  https://truststore.pki.rds.amazonaws.com/us-east-1/us-east-1-bundle.pem

El bundle RDS verifica el certificado y hostname, no usar sslmode=require ni
desactivar verificacion. Referencia:
https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/ssl-certificate-rotation-postgresql.html

Los archivos configtree se montan con nombres Spring; no pasar passwords por
argumentos, variables VITE, Dockerfile, imagenes ni Git. Compose local usa bind
mounts para secretos: sus permisos Linux deben permitir lectura al UID 10001.
Mantener los directorios privados restringidos al administrador; planificar
entrega, permisos y rotacion antes del arranque. No imprimir su contenido.

Bases y usuarios esperados: `pedidos360_<servicio>` para cada uno de los seis
servicios. Crear propietarios, aislamiento de CONNECT y esquemas necesarios
antes de Flyway. La cuenta administradora de RDS no se usa en las aplicaciones.

## Validacion sin crear recursos

Desde la raiz del repositorio, con Node y Docker Compose disponibles:

```sh
node --test infrastructure/aws/compose.test.mjs
docker compose --env-file infrastructure/aws/.env.deploy -f infrastructure/aws/compose.yml config --quiet
```

La prueba usa identificadores ficticios; no lee secretos reales ni contacta AWS.
Validar sintaxis no garantiza que archivos montados, bases, certificados o
credenciales existan ni que la aplicacion arranque.

## Construccion y publicacion (solo despues de definir URLs y registry)

Usar Docker local, nunca compilar los servicios en la EC2 limitada. Ejecutar las
pruebas de los componentes antes: los Dockerfiles Java omiten tests en build.
Estos comandos son manuales; este bloque NO hace push ni crea repositorios ECR.

```sh
docker compose --env-file infrastructure/aws/.env.deploy -f infrastructure/aws/compose.yml -f infrastructure/aws/compose.build.yml build
docker compose --env-file infrastructure/aws/.env.deploy -f infrastructure/aws/compose.yml -f infrastructure/aws/compose.build.yml push
```

Crear/verificar los ocho repositorios del registry y autenticar el cliente por
el mecanismo oficial antes del push. No pasar claves AWS como build args.
Construccion forzada linux/amd64, compatible con la EC2 seleccionada.
Configurar tags inmutables en el registry cuando este disponible y registrar
digests publicados para rollback. No sobrescribir el tag de una entrega.

En EC2, despues de transferir configuracion y archivos privados de forma segura:

```sh
sudo docker compose --env-file .env.deploy -f compose.yml pull
sudo node deployment.mjs up .env.deploy
sudo docker compose --env-file .env.deploy -f compose.yml ps
```

Mantener los archivos relativos al Compose. No transferir .git, caches ni
.env locales. No incluir compose.build.yml en el procedimiento de ejecucion EC2.

## Siguientes bloques obligatorios

1. Ejecutar preflight y generacion segura de TLS de despliegue (ver PREPARACION.md).
2. RDS, cuentas separadas, TLS y prueba de migraciones/persistencia.
3. Definir entrada publica HTTPS y API Gateway compatible con permisos/coste,
   sin atajos HTTP para tokens ni bypass publico del BFF. Entra SPA/CORS.
4. Publicar imagenes, desplegar y probar ambas cuentas, 401/403 y pago simulado.
5. Evidencias, rollback, inventario y costes. No cerrar #55 por validar YAML.

## Presupuesto del laboratorio

Reutilizar EC2/VPC y roles LabRole/LabInstanceProfile en us-east-1. No provisionar
NAT Gateway, balanceador u otros servicios sin justificar coste/permisos.
El laboratorio no garantiza capa gratuita. Revisar saldo (puede retrasarse 8-12h).
Detener EC2/RDS cuando no se usen; EBS sigue costando, RDS puede seguir encendido
al finalizar la sesion y puede arrancar automaticamente tras siete dias parado.
Al reanudar el laboratorio puede iniciar EC2 detenidas y cambiar sus IP publicas.
No terminar instancias ni eliminar volumenes como sustituto de un respaldo.
