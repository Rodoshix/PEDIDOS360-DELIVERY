# Puente privado para AWS Academy

API Gateway HTTPS -> Lambda dentro de VPC -> EC2 privada. La web estatica usa
HTTP 8080 sin tokens/cookies; la API usa HTTPS 8443 con CA y hostname `bff`
verificados. API Gateway y BFF validan JWT Entra. No hay acceso directo publico
al BFF ni a los seis servicios. Ver estado comprobado en `../ESTADO-LAB.md`.

`index.mjs` es el handler sin dependencias. Su ZIP contiene solo ese archivo y
el certificado **publico** `bff.crt`. Nunca incluir P12, worker ni passwords DB.
Limites: peticion 1 MiB, respuesta 4 MiB, upstream 20 s, sin reintentos
automaticos de pagos. Rutas limitadas al contrato actual de los seis servicios.

`lab.mjs` es una herramienta operativa para los IDs exactos del laboratorio,
no un provisionador generico ni idempotente. Usa el perfil `pedidos360-lab` y
SSM; los pulls ECR usan el rol de EC2 y un login Docker temporal eliminado al
terminar. No ejecutar todas las acciones de nuevo: ante un fallo, inspeccionar
el ID SSM antes de reintentar. La creacion de Lambda se reanudo una vez tras
fallar el empaquetador PowerShell; se usa ahora `jar --no-manifest` del JDK.

Comprobaciones de lectura (sin imprimir secretos):

```sh
node infrastructure/aws/edge/lab.mjs inspect-ec2
node infrastructure/aws/edge/lab.mjs verify-ec2
node infrastructure/aws/edge/lab.mjs probe-lambda
node infrastructure/aws/edge/lab.mjs audit-databases
node --test infrastructure/aws/edge/index.test.mjs
```

La auditoria solicita un token al endpoint oficial de Entra, solo en memoria;
no crea pedidos ni pagos. La prueba Lambda usa un bearer deliberadamente
invalido y exige rechazo 401 del BFF tras handshake TLS.

Operacion pendiente: backup/rotacion de secretos, renovacion de certificados
de 30 dias (actualizar tambien el certificado publico empaquetado en Lambda),
pruebas con dos cuentas y presupuesto/limpieza. El dominio de API Gateway no
requiere compra; los recursos AWS SI pueden consumir presupuesto. Los limites
de solicitudes no son un tope de gasto. No modificar LabRole compartido.

Referencias: [VPC Lambda](https://docs.aws.amazon.com/lambda/latest/dg/configuration-vpc.html)
y [JWT HTTP API](https://docs.aws.amazon.com/apigateway/latest/developerguide/http-api-jwt-authorizer.html).
