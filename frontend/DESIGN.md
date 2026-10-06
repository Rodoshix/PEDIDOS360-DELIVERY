# Sistema visual de Pedidos360 — alcance de esta rama

Experiencia operativa para clientes. Administración queda condicionada a los permisos y a la integración funcional pendiente de I2; no se añade navegación ficticia.

## Dirección

Superficies neutras claras, naranja de marca accesible, iconos Lucide y tipografía Geist variable alojada en el propio build. Sidebar en escritorio y drawer modal bajo 1024 px. La navegación conserva las URLs actuales. Inicio orienta a acciones existentes; Perfil y Carrito separan datos, avisos y acciones.

## Tokens

La fuente de verdad es `src/styles/tokens.css`: colores semánticos, escala de espacios 4/8/12/16/24/32/48, texto 12/14/16/20/24/30, radios 6/10/14 y alturas de control 40/44. Usar estos tokens; no inventar colores por vista. Estados expresados con texto además de color.

## Componentes

- `Button`: variantes primary/secondary/ghost/danger, tamaños normal/small/icon, loading. Por defecto type=button. Las acciones de formulario declaran type=submit.
- `Card`: superficie semántica configurable con `as`.
- `Badge`: tonos neutral/success/warning/danger.
- `Input`, `Select`, `Field`: componentes controlados por el padre. Field vincula label, hint y error; no valida ni llama a servicios.
- `Table`: tabla semántica con región desplazable; declarar caption y scope en los encabezados.
- `Dialog`: primitive Radix, título y descripción obligatorios. Cierra con Escape, devuelve foco al trigger y contiene el foco mientras está abierto.
- `Alert`, `EmptyState`, `Skeleton`: presentación de estados; no gestionan operaciones.
- `LoadingState`: etiqueta accesible y skeletons decorativos; no cambia peticiones.
- `Toast`: confirmación persistente y descartable. No reemplaza errores críticos ni realiza reintentos.
- `Navigation`, `Brand`: enlaces actuales compartidos entre sidebar y drawer.

## Compatibilidad

`system.css` carga Tailwind sin Preflight para no cambiar el reset de las vistas pendientes. El puente `.workspace` adapta tipografía, controles y contenedores; no reemplaza handlers ni validadores. Las páginas conservan su CSS hasta su fase correspondiente.

No se añade una segunda biblioteca UI, estado global, formularios ni animación. Se utiliza directamente el primitive de dialog necesario; no se incorpora el preset completo de shadcn ni su CLI en esta fase.

## Accesibilidad y revisión

Mantener labels, aria-describedby, avisos, disabled, guardas de doble envío y recuperación de errores. No mover errores críticos a toasts. Controles táctiles de 44 px, foco visible y reducción de movimiento. El colapso del sidebar no desmonta las páginas ni resetea sus controllers.

El banco `npm run preview:profile` permite revisar layout y sesión ficticia sin MSAL ni backend; los errores de consulta de ese banco son esperados. `npm run preview:ui` añade respuestas locales ficticias para Perfil y Carrito: escenarios normal, vacío, error y carga lenta; permite verificar presentación, validación, confirmaciones y notificaciones usando los controllers/adapters reales. El middleware y los tokens ficticios solo existen en tools, no entran al bundle y nunca son un fallback del producto. No demuestra autenticación ni compras reales contra AWS. Los tests actuales siguen siendo la referencia de regresión funcional.

## Fases y límites de este PR

- Fase 1: sistema visual y layout.
- Fase 2: Inicio, accesos a rutas existentes y texto ajustado al alcance.
- Fase 3: Perfil y presentación del acceso; MSAL, consentimientos y acciones de sesión no cambian.
- Fase 5: Carrito propio, resumen, cantidades y confirmación de eliminación.
- Fase 7: cargas, errores, vacíos, confirmaciones y toasts en las superficies anteriores.
- Fase 8: responsive, teclado, foco, contrastes y pulido de estas superficies.

**Fase 4 / I2 (#50): administración implementada en `feature/i2-50-admin-restaurantes-productos-v2`.** Listados, detalle, formularios y confirmaciones reutilizan el sistema visual. Rutas `/admin/restaurantes` y `/admin/productos`, visibles solo después de comprobar permiso con BFF; detalle en dialog y filtro por restaurante. Validación real desplegada pendiente antes de cerrar #50. Ver `docs/administracion-catalogo.md`.

**Fase 6 pendiente: Pedidos/Pagos.** No modificar lógica ni reorganizar estas pantallas antes del trabajo de RabbitMQ de Entrega 2. Retomar después del merge en otra rama nueva dedicada.

Las fases 7 y 8 se aplican también a las nuevas superficies administrativas de Fase 4. El catálogo de clientes conserva su flujo actual y Fase 6 sigue fuera de esta rama. El issue general #60 permanece abierto; no usar palabras de autocierre en el PR.
