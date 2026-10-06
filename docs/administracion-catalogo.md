# Administración real de catálogo — I2 #50 / Fase 4

## Arquitectura y alcance

Frontend → BFF → Restaurantes/Productos. Se conservan los contratos y persistencia de los microservicios. No se modifican Pedidos/Pagos ni se incorpora RabbitMQ. No se integra el commit histórico cc7506b: se rescatan la URL `/admin/restaurantes`, los campos y la intención de listado/alta/edición/desactivación. Se sustituyen datos ficticios, estado local como persistencia, validación incompleta y CSS paralelo. La rama histórica se conserva.

Rutas frontend: `/admin/restaurantes` y `/admin/productos`. Detalle y edición se abren en dialogs; Productos permite consultar todos o filtrar por restaurante. No se añaden rutas de detalle innecesarias ni se cambian las rutas de clientes.

## Operaciones BFF

| Método | Ruta | Resultado esperado | Destino |
|---|---|---|---|
| GET | `/restaurantes/admin/acceso` | 204 | Solo comprueba autorización, sin upstream |
| POST | `/restaurantes` | 201 | Restaurantes, misma ruta |
| PUT | `/restaurantes/{id}` | 200 | Restaurantes, misma ruta |
| DELETE | `/restaurantes/{id}` | 204 | Desactivación, conserva datos |
| POST | `/productos` | 201 | Productos, misma ruta |
| PUT | `/productos/{id}` | 200 | Productos, misma ruta |
| PATCH | `/productos/{id}/disponibilidad?disponible=true\|false` | 200 | Productos, mismo contrato de query |

Se conservan todas las lecturas de catálogo existentes. La ruta de permiso queda bajo `/restaurantes` porque Lambda edge permite ese prefijo; no se modifica la infraestructura ni su allowlist. CORS admite PATCH desde los mismos orígenes configurados, sin cookies.

## Seguridad: decisión y límites

Cada escritura requiere JWT Entra válido para la API, tenant/frontend esperados, scope `access_as_user` y rol `ADMIN`. La política se evalúa antes del controlador y antes de las reglas generales CLIENTE/ADMIN del catálogo. `CLIENTE` recibe 403 sin tocar upstream; ausencia/token inválido produce 401. Headers `X-Roles` o campos del cuerpo no conceden permisos.

La navegación y el guard frontend consultan el permiso al BFF con el access token existente; no infieren roles del nombre, del ID token ni de datos locales. El guard es presentación: cada escritura vuelve a validarse en servidor. Cambio/cierre de cuenta desmonta el controller y aborta peticiones.

Restaurantes y Productos actualmente no validan JWT. Se mantiene la frontera de autorización en el BFF y el aislamiento existente: servicios del compose AWS sin puertos publicados, red interna y TLS del servidor; en desarrollo escuchan en loopback. No se agregan cabeceras de identidad ni un supuesto secreto interno. El BFF no reenvía credenciales al catálogo que no las consume.

**Límite:** la red interna y TLS de servidor no autentican al llamador. Un proceso con acceso a esa red puede invocar escrituras directamente. Esta fase es segura en la frontera de acceso externo existente; no ofrece defensa frente a un servicio interno comprometido. Si se requiere ese modelo de amenaza o se publican servicios, será necesario revalidar JWT/ADMIN o implementar autenticación de servicio en los microservicios. No exponer sus puertos ni saltarse el BFF para integrar la UI. Esta decisión evita habilitar un mecanismo JWT nuevo sin configuración y pruebas correspondientes.

## Validación y recuperación

Allowlist de campos e IDs positivos. Restaurantes: nombre/estado obligatorios; dirección y descripción opcionales, conforme al DTO actual (el prototipo exigía dirección). Productos: restaurante, nombre, categoría, precio y disponibilidad; precio entre 0.01 y 99999999.99 y hasta dos decimales conforme a `NUMERIC(10,2)`. Se conserva `disponible` como query booleana en PATCH.

Un envío simultáneo no genera escrituras duplicadas. No hay reintentos automáticos de escrituras. Tras un fallo se mantiene el formulario y se exige consultar antes de otra operación: un timeout no demuestra que el cambio no se aplicó. La selección de restaurante se limita a registros obtenidos del backend; la existencia referencial tampoco se verifica hoy en el microservicio de Productos, lo que permanece como límite del contrato existente.

## Evidencia local y aceptación

Pruebas HTTP del BFF con JWT firmados de prueba: ADMIN, CLIENTE, scope incorrecto, 401, cuerpo inválido, métodos/rutas/cuerpos, PATCH, CORS y errores upstream sin secretos/reintentos. Se conservan pruebas de lectura y de Pedidos/Pagos. El upstream de estas pruebas es un stub: no demuestra persistencia real en RDS.

Pruebas frontend: adapters, validaciones, carga/vacío/error, concurrencia, filtro antiguo, abortado, navegación por permiso y formulario bloqueado. Banco visual loopback `npm run preview:ui`: cuenta A ADMIN ficticia, B CLIENTE ficticia. Las respuestas del banco solo existen en tools y quedan fuera del build; no reemplazan el backend en producción.

### Validación manual local con Entra real — 2026-10-06

El usuario confirmó el recorrido sobre el stack Docker real de esta rama en `http://localhost:5180`, con dos sesiones separadas: ADMIN en incógnito y CLIENTE en navegador normal. No se utilizaron mocks ni el banco visual en este recorrido.

- ADMIN: acceso a administración, creación/edición/desactivación de restaurante, creación/edición/cambio de disponibilidad de producto y persistencia después de refrescar, todos correctos.
- CLIENTE: acceso/operaciones administrativas restringidos y escrituras rechazadas según lo esperado.
- El usuario no detectó errores funcionales durante la prueba. No se registraron tokens ni secretos.

Esta evidencia acredita la validación funcional manual local reportada por el usuario. No incluye captura individual de códigos HTTP, inspección de claims ni observación de tráfico que demuestre ausencia de llamadas upstream en cada rechazo. Tampoco sustituye la prueba desplegada en AWS. Listados, filtro por restaurante y detalle tienen pruebas locales automatizadas/visuales; no fueron enumerados individualmente en el informe manual final.

### Validación desplegada con Entra real + AWS real — 2026-10-06

Frontend y BFF actualizados desde `a95cecb8dc16cf61fcaf0428ec090a609e477381`, tag inmutable del mismo SHA. Origen: `https://l7wtit9zmj.execute-api.us-east-1.amazonaws.com`.

- Frontend: `sha256:46c0a569d51937242600e98c59f66e8e5c5b90e9dfd5fea45ac3b9674a6ed832`.
- BFF: `sha256:2f6ebb2391ebe3c82ea9531d98bca2acfdbb0aa8cf497a4826dbed544fe13c9a`.
- Auditoría técnica del despliegue: ambos healthy, BFF UP con TLS verificado, frontend público 200, API sin token 401, catálogos internos Restaurantes/Productos 200 y SELECT 1 en ambas RDS con TLS verify-full. Sin errores críticos en logs. Los otros seis servicios conservaron IDs y tiempos de arranque. Overrides nuevo/rollback validados; imágenes previas conservadas. SSM de despliegue: `fa17c342-8821-4c34-8ff3-b72bad3d159c` (Success).
- El usuario confirmó personalmente ADMIN: acceso, crear/editar/desactivar restaurante, crear/editar producto, cambiar disponibilidad y persistencia tras refrescar, todo OK.
- El usuario confirmó CLIENTE: catálogo normal, administración restringida y escrituras administrativas rechazadas, todo OK. Sin errores funcionales detectados.

Esta es evidencia manual del recorrido real desplegado, complementada por comprobaciones técnicas y pruebas automatizadas. No se afirma captura de tokens/claims, códigos individuales de rechazos ni trazas upstream del recorrido humano. Listados y filtro por restaurante se acreditan con las pruebas locales existentes; el informe AWS no los enumera como acciones administrativas independientes.

| Criterio #50 | Estado |
|---|---|
| Pantalla de Restaurantes | Acreditado: acceso ADMIN real local/AWS |
| Listar Restaurantes | Acreditado: pruebas locales; no enumerado individualmente en informe manual AWS |
| Crear Restaurante | Acreditado: validación manual local y AWS con Entra real |
| Editar Restaurante | Acreditado: validación manual local y AWS con Entra real |
| Desactivar Restaurante | Acreditado: validación manual local y AWS con Entra real |
| Pantalla de Productos | Acreditado: operaciones ADMIN reales local/AWS |
| Listar Productos | Acreditado: pruebas locales; no enumerado individualmente en informe manual AWS |
| Productos por restaurante | Acreditado: pruebas locales; no enumerado individualmente en informe manual AWS |
| Crear Producto | Acreditado: validación manual local y AWS con Entra real |
| Editar Producto | Acreditado: validación manual local y AWS con Entra real |
| Cambiar disponibilidad | Acreditado: validación manual local y AWS con Entra real |
| Campos obligatorios | Acreditado: validación automática local |
| Precio válido | Acreditado: validación automática local |
| Carga/vacío/error | Acreditado: pruebas locales de estados |
| Compilación frontend | Acreditado: build local y Docker correctos |

**Los 15 criterios de #50 quedan acreditados con la evidencia combinada.** Validación local y AWS con Entra real completadas. Se recomienda revisar y hacer merge del PR #62 y luego cerrar #50 como completed. La revisión visual del catálogo cliente y Fase 6 permanecen fuera del alcance. No se realiza merge ni cierre automático; #60 permanece abierto.

Validación 2026-10-06: lint limpio, 223 tests frontend y 58 tests BFF pasan. Build correcto; administración cargada en un chunk diferido (3.99 kB gzip), principal 175.58 kB gzip frente a 172.95 kB de develop (+2.63 kB). Sin nuevas dependencias. Revisión visual local: creación/edición/desactivación, detalle, disponibilidad, filtro vacío, rechazo CLIENTE, dialogs y confirmación de descarte; anchos 1440, 390 y 320, sin desbordamiento horizontal de página. Error upstream controlado y consola sin excepciones en la revisión final. Se conserva la advertencia previa de chunk principal >500 kB. Fixtures excluidos del build por el test de producción.

## Procedimiento para repetir la validación AWS

1. Desplegar imágenes de esta rama para BFF/frontend con el procedimiento del entorno vigente, conservando Entra y TLS. No ampliar Terraform Entrega 1.
2. Con cuenta que tenga ADMIN en la API, abrir ambas rutas y comprobar el permiso (204). Con CLIENTE comprobar ausencia de navegación y 403 en accesos/escrituras; sin sesión comprobar 401.
3. Crear un restaurante de prueba, consultar su detalle, recargar la página y verificar persistencia, editarlo y comprobar el cambio.
4. Crear un producto asociado a ese restaurante, consultar detalle/listado/filtro, editar precio y categoría y recargar.
5. Cambiar disponibilidad en ambos sentidos; verificar la consulta `.../disponibles` y el catálogo de clientes.
6. Desactivar el restaurante: verificar INACTIVO y conservación de datos. No borrar registros ajenos.
7. Verificar campos inválidos, expiración/consentimiento de token, fallos de consulta y escritura incierta sin reenvío automático.
8. Probar escritorio/móvil y el flujo de catálogo/carrito existente. Registrar IDs de datos de prueba y resultados sin guardar tokens ni secretos.

La validación funcional AWS anterior fue completada sin fallos reportados. Este procedimiento se conserva para repetir las comprobaciones; no implica que todas sus variantes negativas se ejecutaron manualmente en AWS.
