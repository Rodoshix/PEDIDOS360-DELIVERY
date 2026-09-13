# Integración catálogo y carrito — issue #44

## Bloque 1: contrato y seguridad

El BFF expone lecturas autenticadas de Restaurantes y Productos y operaciones
del carrito propio. Requiere JWT Entra v2 de la API, tenant y cliente frontend
configurados, scope access_as_user y rol CLIENTE o ADMIN. Sin Entra habilitado,
estas rutas permanecen cerradas.

Carrito valida nuevamente firma, issuer, audiencia, tenant, oid, azp y vigencia
del token. Su propietario sigue siendo la pareja tid + oid, ya persistida en
su modelo. No se transforma en el ID numérico del perfil de Usuarios: ambos
servicios identifican a la misma persona por esa pareja, sin depender de datos
de contacto. Este bloque no impone existencia/estado del perfil de Usuarios
como requisito adicional para el carrito. ADMIN tampoco obtiene acceso al
carrito de otra persona. No hay rutas que acepten un propietario desde la UI.

| BFF | Destino |
| --- | --- |
| GET /restaurantes y /restaurantes/{id} | Restaurantes, misma ruta |
| GET /productos y /productos/{id} | Productos, misma ruta |
| GET /productos/restaurante/{id}[/disponibles] | Productos, misma ruta |
| GET /restaurantes/{id}/productos | Productos: /productos/restaurante/{id} |
| GET, DELETE /carrito | Carrito, misma ruta |
| POST /carrito/items | Carrito, productoId y cantidad |
| PUT, DELETE /carrito/items/{productoId} | Carrito; PUT recibe cantidad |

El alias resuelve la diferencia entre el documento inicial de catálogo y las
rutas implementadas por Productos. No se publican escrituras administrativas
del catálogo en este bloque. Cantidades: enteros de 1 a 99; IDs: enteros positivos.
Precio y propietario nunca se toman del cuerpo del navegador.

El BFF reenvía el bearer solo a Carrito. No reenvía cookies ni cabeceras de
identidad aportadas por el navegador. Catálogo no requiere ese token para las
lecturas actuales. Los servicios de catálogo deben permanecer privados/loopback:
su acceso directo no queda protegido por añadir estas rutas al BFF.

## Configuración local

- BFF: RESTAURANTES_SERVICE_URL (http://127.0.0.1:8082),
  PRODUCTOS_SERVICE_URL (http://127.0.0.1:8083), CARRITO_SERVICE_URL
  (http://127.0.0.1:8084).
- Carrito: ENTRA_ENABLED=true, LOCAL_IDENTITY_ENABLED=false, ENTRA_TENANT_ID,
  ENTRA_API_CLIENT_ID y ENTRA_FRONTEND_CLIENT_ID como en BFF/Usuarios.
- Carrito: CATALOGO_HTTP_ENABLED=true y PRODUCTOS_SERVICE_URL. Timeout mediante
  CATALOGO_TIMEOUT_MS (5000 ms por defecto, rango 100–30000).
- Configurar DB_URL, DB_USERNAME y DB_PASSWORD para una base de prueba propia.

Los destinos deben ser orígenes HTTPS sin ruta ni credenciales; HTTP se permite
solo en loopback para pruebas locales. No hay redirecciones ni reintentos
automáticos. Los errores upstream se sanitizan. CORS del BFF conserva la lista
explícita de orígenes y se aplica también a las nuevas rutas.

Carrito consulta el producto real al agregar/cambiar cantidad. Respeta las reglas
existentes de disponibilidad, un restaurante por carrito y precio guardado al
cambiar cantidad. Los importes CLP deben representar enteros exactos (6990.00
es válido; 6990.50 no se trunca). Identidad local y catálogo HTTP no pueden
coexistir; Entra y la identidad local tampoco.

## Validación y límites

Tests BFF: rutas, alias, cuerpos inválidos, JWT/scope/rol, CORS, filtrado de
cabeceras, errores sanitizados y rechazo de redirecciones.
Tests Carrito: validadores JWT con claves efímeras, peticiones HTTP autenticadas,
aislamiento entre dos usuarios (uno ADMIN), PostgreSQL de Testcontainers y
catálogo HTTP controlado, sin crear registros ante errores/precios inválidos.
Las claves de prueba no son credenciales de Microsoft.

## Bloque 2: interfaz real

/carrito ahora monta RealCartPanel por identidad de sesión. Reutiliza el cliente
HTTP autenticado existente y consulta GET /carrito; un 404 o respuesta inválida
no se interpreta como vacío. El catálogo dentro de la misma pantalla permite
seleccionar un restaurante, consultar sus productos y agregar una unidad.
Se reutilizan CartSummary y CartQuantityForm con etiquetas de datos reales.
Quitar/vaciar requiere confirmación; tras DELETE 204 se consulta el estado real.

Después de una escritura fallida o incierta, el adaptador bloquea nuevas
escrituras hasta una lectura correcta. No repite DELETE si falla la consulta
posterior. La pantalla ofrece actualización explícita y los controles de
Microsoft cuando se requiere interacción. Las consultas de catálogo se cancelan
al cambiar selección/desmontar; cambiar de cuenta reinicia toda la pantalla.
No se guarda estado propio en localStorage ni se usa catálogo demo como respaldo.
Los paneles demo siguen en la herramienta preview:cart, fuera del bundle real.

Verificación del bloque 2: 195 tests frontend, lint y build. Se cubren DTO
inválidos, asociación de productos al restaurante, proyección de campos,
errores HTTP, cancelación y reconciliación de escrituras. El build conserva
una advertencia de tamaño del chunk principal (aproximadamente 525 kB).

## Evidencia manual del bloque 3 (2026-09-12)

Navegador integrado con sesión Entra real; frontend 5173, BFF 8080,
Restaurantes 8082, Productos 8083 y Carrito 8084. Los tres servicios de comercio
usaron sus esquemas en una base PostgreSQL temporal independiente de Mi cuenta,
con datos iniciales de las migraciones. No se extrajeron tokens del navegador.

1. Consultar carrito vacío y cargar restaurantes mediante el BFF.
2. Elegir Burger 360 y agregar Hamburguesa Clásica: una unidad, CLP 6990.
3. Cambiar a dos unidades: CLP 13980; recargar y verificar que permanecen.
4. Confirmar en PostgreSQL producto 1, cantidad 2 y subtotal 13980.
5. Abrir eliminación, cancelar y luego confirmar: carrito vacío.
6. Agregar Papas Fritas, confirmar Vaciar carrito y recargar: total cero.
7. PostgreSQL confirmó cero líneas restantes tras el vaciado.

Los servicios Productos/Restaurantes necesitaron una compilación limpia:
target/classes conservaba un application.properties antiguo que tenía prioridad
sobre application.yml. No se cambió configuración fuente ni se borraron bases.
El contenedor pedidos360-comercio-prueba usa almacenamiento temporal y no
representa durabilidad tras eliminarlo. La base de Mi cuenta quedó intacta.

Revisión final: 195 pruebas frontend, lint limpio y build correcto; suites BFF
y Carrito verificadas por separado. El PR debe revisarse y mergearse antes de
cerrar el issue #44.
Este bloque no incluye AWS ni publica servicios fuera de la máquina local.
