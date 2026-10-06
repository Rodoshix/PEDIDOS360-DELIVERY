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

| Criterio #50 | Estado |
|---|---|
| Pantalla de Restaurantes | Implementada |
| Listar Restaurantes | Implementado; validación real pendiente |
| Crear Restaurante | Implementado; validación real pendiente |
| Editar Restaurante | Implementado; validación real pendiente |
| Desactivar Restaurante | Implementado; validación real pendiente |
| Pantalla de Productos | Implementada |
| Listar Productos | Implementado; validación real pendiente |
| Productos por restaurante | Implementado; validación real pendiente |
| Crear Producto | Implementado; validación real pendiente |
| Editar Producto | Implementado; validación real pendiente |
| Cambiar disponibilidad | Implementado; validación real pendiente |
| Campos obligatorios | Validación automática local |
| Precio válido | Validación automática local |
| Carga/vacío/error | Implementados y probados localmente |
| Compilación frontend | `npm run build` correcto |

**No cerrar #50 todavía.** Falta ejecutar el recorrido desplegado con tokens reales de Entra y los microservicios/RDS actuales. No se despliega ni se hace merge automáticamente.

Validación 2026-10-06: lint limpio, 223 tests frontend y 58 tests BFF pasan. Build correcto; administración cargada en un chunk diferido (3.99 kB gzip), principal 175.58 kB gzip frente a 172.95 kB de develop (+2.63 kB). Sin nuevas dependencias. Revisión visual local: creación/edición/desactivación, detalle, disponibilidad, filtro vacío, rechazo CLIENTE, dialogs y confirmación de descarte; anchos 1440, 390 y 320, sin desbordamiento horizontal de página. Error upstream controlado y consola sin excepciones en la revisión final. Se conserva la advertencia previa de chunk principal >500 kB. Fixtures excluidos del build por el test de producción.

## Prueba real antes del cierre

1. Desplegar imágenes de esta rama para BFF/frontend con el procedimiento del entorno vigente, conservando Entra y TLS. No ampliar Terraform Entrega 1.
2. Con cuenta que tenga ADMIN en la API, abrir ambas rutas y comprobar el permiso (204). Con CLIENTE comprobar ausencia de navegación y 403 en accesos/escrituras; sin sesión comprobar 401.
3. Crear un restaurante de prueba, consultar su detalle, recargar la página y verificar persistencia, editarlo y comprobar el cambio.
4. Crear un producto asociado a ese restaurante, consultar detalle/listado/filtro, editar precio y categoría y recargar.
5. Cambiar disponibilidad en ambos sentidos; verificar la consulta `.../disponibles` y el catálogo de clientes.
6. Desactivar el restaurante: verificar INACTIVO y conservación de datos. No borrar registros ajenos.
7. Verificar campos inválidos, expiración/consentimiento de token, fallos de consulta y escritura incierta sin reenvío automático.
8. Probar escritorio/móvil y el flujo de catálogo/carrito existente. Registrar IDs de datos de prueba y resultados sin guardar tokens ni secretos.

Solo después de esta evidencia y resolución de cualquier fallo puede recomendarse cerrar #50.
