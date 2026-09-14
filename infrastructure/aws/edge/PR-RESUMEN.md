## Resumen

Integra el despliegue AWS Academy hacia `develop`. Relacionado con #55.

- Puente Lambda privado, rutas HTTP API y authorizer JWT Entra.
- Compose opt-in con puertos ligados a IP privada; frontend sin proxy de API.
- Validacion de configuracion y pruebas del handler, TLS y material privado.
- Evidencia de ocho contenedores saludables, migraciones RDS y recorrido
  navegador: pedido #1, pago simulado aprobado y confirmacion del pedido.
- Registro honesto del reinicio, agotamiento de creditos CPU y cambio manual
  a Unlimited (posible costo adicional), sin modificar secretos en Git.

## Validacion

`node --test infrastructure/aws/compose.test.mjs infrastructure/aws/deployment.test.mjs infrastructure/aws/edge/index.test.mjs infrastructure/aws/transfer-worker.test.mjs`

`git diff --check`

## Pendientes / limites de cierre

- No cerrar #55 todavia: aislamiento con dos cuentas y rechazo 403 en AWS
  pendientes. Prueba publica explicita de bearer invalido pendiente.
- Ajustes frontend de perfil faltante y textos NO incluidos.
- Healthchecks y arranque automatico requieren robustecimiento; el usuario
  reporto recuperacion en el ultimo ciclo, no se garantiza tiempo fijo.
- Backup/rotacion y presupuesto requieren seguimiento operativo.
- Imagenes ECR desplegadas conservan SHA 5d4601905c870efa2e2ca9b3e850d6ef262f4a02;
  este commit agrega infraestructura/documentacion, no reconstruye aplicaciones.

Sin merge automatico ni cierre de issue; revisar criterios antes de aprobar.
