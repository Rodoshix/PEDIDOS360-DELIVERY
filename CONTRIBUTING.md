# Guía de contribución

## 1. Antes de comenzar

1. Selecciona o crea un issue.
2. Confirma que tenga un único responsable y criterios de aceptación.
3. Actualiza `develop`.
4. Crea tu rama desde `develop`.

Ejemplo:

```bash
git switch develop
git pull origin develop
git switch -c feature/i2-18-productos-crud
```

## 2. Commits

Formato:

```text
tipo(alcance): descripción breve en imperativo
```

Tipos permitidos: `feat`, `fix`, `test`, `docs`, `refactor`, `chore` y `ci`.

Ejemplos:

```text
feat(auth): integrar inicio de sesión con MSAL
feat(pedidos): crear pedido desde el carrito
fix(bff): rechazar token con audience inválida
test(seguridad): cubrir respuestas 401 y 403
docs(api): documentar contrato de productos
```

## 3. Pull requests

Un PR debe:

- Resolver un objetivo funcional.
- Referenciar su issue.
- Compilar y arrancar sin errores.
- Incluir pruebas o pasos reproducibles.
- Documentar endpoints y variables nuevas.
- Mantener los contratos acordados.
- Estar libre de secretos y archivos generados.
- Tener al menos una aprobación de otra persona.

## 4. Integración

Orden recomendado:

1. React + MSAL + endpoint BFF protegido.
2. Restaurantes + Productos + Carrito.
3. Pedidos + Pagos.
4. Repartidores + Seguimiento.
5. API Gateway + EC2 + RDS.

Si un PR rompe `develop`, se corrige o revierte antes de integrar otro cambio.

## 5. Definición de terminado

- Código compilado.
- Criterios del issue satisfechos.
- Pruebas ejecutadas.
- Errores HTTP correctos.
- OpenAPI y README actualizados cuando corresponda.
- Variables agregadas a `.env.example` sin valores reales.
- PR revisado e integrado.
