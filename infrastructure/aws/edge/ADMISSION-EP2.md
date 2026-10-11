# Admisión temporal Pedido→Carrito — candidato sin aplicar

El handler existente mantiene JWT de API Gateway y revalidación BFF. La fuente
conservada de Lambda coincide con index.mjs base f6a9540 al normalizar CRLF/LF;
no se consultó ni modificó AWS en esta implementación. Se usa únicamente
requestContext.authorizer.jwt.claims para tid, oid, roles y scp, y
requestContext.http.sourceIp para IP. No se decodifica un JWT sin verificar ni
se usan headers de identidad o X-Forwarded-For para autorizar.

## Configuración propuesta, nunca aplicada

Por defecto todas las variables siguientes están ausentes: comportamiento
original. EP2_ADMISSION_ENABLED también admite el literal `false` cuando NO hay
selectores restantes. Cualquier otro literal, nombre desconocido que empiece
por EP2_ADMISSION_, selector incompleto/inválido o selector sobrante estando
desactivado impide inicializar el handler: respuesta genérica 503, sin upstream.

Para activar, configurar atómicamente los siete nombres:

| Nombre | Valor esperado |
|---|---|
| EP2_ADMISSION_ENABLED | `true` literal |
| EP2_ADMISSION_TENANT_ID | UUID canónico minúsculo del tenant ya autorizado |
| EP2_ADMISSION_OBJECT_ID | UUID canónico minúsculo del CLIENTE académico |
| EP2_ADMISSION_SOURCE_IP | IP exacta IPv4/IPv6 observada en contexto AWS; no CIDR |
| EP2_ADMISSION_TOKEN_SHA256 | SHA-256 hexadecimal minúsculo del bearer aprobado, sin prefijo Bearer |
| EP2_ADMISSION_EXPIRES_AT | UTC `YYYY-MM-DDTHH:mm:ssZ`, futura y como máximo 30 min desde inicialización |
| EP2_ADMISSION_ROUTES | JSON de 1–16 cadenas únicas `METODO /ruta/exacta` |

No incluir valores reales en Git, logs, screenshots, PR ni comandos de consola.
La huella debe aprovisionarse mediante un mecanismo privado revisado, calculado
en memoria sobre una credencial ya validada; no se implementa captura automática,
registro de tokens ni ruta de enrolamiento. Ese mecanismo aún debe concretarse
y autorizarse antes de usar el gate en AWS. No solicitar/copiar tokens al usuario.

Solo se admite roles=[CLIENTE] (array o JSON array serializado por Gateway), scp
con access_as_user y coincidencia exacta de tenant/oid/IP/huella. El BFF sigue
validando JWT, roles, scope, tenant y propietario. Otra credencial de la misma
cuenta se rechaza. Renovación del token o cambio IP exige detener y revisar:
nunca relajar controles ni reintentar un POST para recuperar incertidumbre.

La huella identifica el bearer, **no una pestaña física**: si dos procesos
comparten exactamente esa credencial y la misma IP, no son distinguibles.
No afirmar exclusividad absoluta de navegador. Mantener instalación única del
panel, control del CLIENTE académico y prohibición de otros operadores de esa
credencial. Si se exige distinguirlos criptográficamente, este gate no satisface
esa exigencia; sería necesario otro protocolo, fuera de alcance.

## Rutas y denegación

El techo de rutas está fijo en código: GET usuarios/me, restaurantes,
productos/restaurante/{id} (también /disponibles), carrito y pedidos/me o
pedidos/{id}; POST carrito/items
y pedidos; PUT/DELETE carrito/items/{id}; DELETE carrito completo solo para
verificar el 409. IDs positivos. **El techo no autoriza automáticamente**:
solo las entradas exactas de EP2_ADMISSION_ROUTES pasan. No admite comodines,
queries, trailing slash, Pagos, administración, rutas internas ni otros métodos.
No usar todas las entradas del techo por comodidad; revisar el mínimo de la
secuencia del panel. Un GET del pedido recién creado necesita su ID exacto:
preparar previamente cómo incorporar esa ruta bajo mantenimiento sin repetir
el POST; no se habilita un patrón abierto para resolverlo.

Recursos estáticos públicos explícitos: GET/HEAD `/`, `/index.html` y archivo
simple `/assets/<nombre>.<js|css|woff2|png|svg|jpg|webp>`. Permiten cargar MSAL/React,
no operaciones protegidas. Nunca llevan Authorization/cookies/query al frontend.
Otros caminos estáticos quedan rechazados, incluso rutas SPA al recargar.
La lista impide usar el frontend como proxy alternativo /api. API sin contexto
JWT/bearer: 401; identidad/ruta/IP/huella no permitida: 403; vencimiento o reloj
inválido: 503. El plazo pertenece al gate, no cambia expiración JWT ni timeouts.

## Activación/reversión a revisar antes de una futura autorización

1. Mantener DisableExecuteApiEndpoint=true y mantenimiento efectivo. Conservar
   ZIP/certificado/configuración originales y hashes; comprobar entradas públicas
   alternativas, permisos Lambda (solo Gateway) y ausencia de acceso directo BFF.
2. Tras revisión independiente, desplegar un ZIP candidato de index.mjs y el
   certificado **público** BFF. No hay imagen Docker edge ni dependencias nuevas.
3. Aplicar selectores completos en una sola actualización privada; verificar
   versión/configuración efectiva y denegación de controles negativos antes de
   autorizar apertura. Las pruebas locales no acreditan restricciones reales de
   IAM/SG/custom domains. No quitar mantenimiento por un health 200 aislado.
4. Abrir únicamente después de esos gates, con captura persistente y secuencia
   única del panel. Expiración falla cerrada también para estáticos. No hay
   persistencia ni contador de POST en Lambda; no garantiza una única creación
   desde dos clientes que compartan bearer. No modificar el procedimiento de
   una sola escritura ni su control de operaciones inciertas.
5. Cerrar API Manager primero y verificar mantenimiento. Preservar evidencia.
   Eliminar los seis selectores y establecer enabled=false (o retirar los siete)
   **atómicamente**; verificar comportamiento HTTP original bajo mantenimiento.
   Alternativa: restaurar ZIP/configuración exactos previos. Mantener tráfico
   cerrado durante propagación; no desactivar el gate con API abierta.

No hay acciones AWS automatizadas ni scripts de activación añadidos. Aplicar,
abrir, revertir y ensayar live requieren autorización posterior.

## Pruebas y procedencia

`node --test infrastructure/aws/edge/index.test.mjs infrastructure/aws/edge/admission.test.mjs`

16 pruebas locales: 4 regresiones originales y 12 de admisión, con casos
negativos para tenant/owner/roles/scope/sesión/IP manipulados, autenticación
ausente, métodos/rutas alternativas, config inválida/typos, plazo, reloj,
upstream y preservación de 409. Transport simulado; no Entra/Gateway real.

Base develop f6a9540; cambio independiente de #113/#114/#115, sin modificar
overlays, IAM, authorizer, certificados, BFF o React. No se construyó ZIP ni
imagen. Futuro empaquetado, solo tras autorización: ZIP con index.mjs revisado
y bff.crt público validado, hash del ZIP y de la fuente; ninguna credencial.

Contrato de contexto confiable:
[AWS JWT authorizer](https://docs.aws.amazon.com/apigateway/latest/developerguide/http-api-jwt-authorizer.html)
y [payload Lambda 2.0](https://docs.aws.amazon.com/apigateway/latest/developerguide/http-api-develop-integrations-lambda.html).
