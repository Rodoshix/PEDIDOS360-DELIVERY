# Contrato de Catálogo - Pedidos360

Responsable: Integrante 2

## Objetivo

Definir el contrato inicial de los microservicios de Restaurantes y Productos de Pedidos360, estableciendo los DTO, reglas y endpoints que utilizarán los demás integrantes del equipo.

---

## DTO Restaurante

```json
{
  "id": 20,
  "nombre": "Burger 360",
  "descripcion": "Hamburguesas y comida rápida",
  "direccion": "Av. Principal 123",
  "estado": "ABIERTO"
}
```

### Estados permitidos

- ABIERTO
- CERRADO
- INACTIVO

### Reglas de Restaurante

- El nombre no puede estar vacío.
- El restaurante debe tener un identificador único.
- El estado debe ser ABIERTO, CERRADO o INACTIVO.

---

## DTO Producto

```json
{
  "id": 101,
  "restauranteId": 20,
  "nombre": "Hamburguesa clásica",
  "descripcion": "Carne, queso y vegetales",
  "precio": 6990,
  "categoria": "HAMBURGUESAS",
  "disponible": true
}
```

### Reglas de Producto

- El nombre no puede estar vacío.
- El precio no puede ser negativo.
- Cada producto debe pertenecer a un restaurante mediante `restauranteId`.
- Un producto puede estar disponible o no disponible.

---

## Endpoints de Restaurantes

| Método | Endpoint | Descripción |
|---|---|---|
| GET | `/restaurantes` | Obtener todos los restaurantes |
| GET | `/restaurantes/{id}` | Obtener restaurante por ID |
| POST | `/restaurantes` | Crear restaurante |
| PUT | `/restaurantes/{id}` | Actualizar restaurante |
| DELETE | `/restaurantes/{id}` | Eliminar o desactivar restaurante |

---

## Endpoints de Productos

| Método | Endpoint | Descripción |
|---|---|---|
| GET | `/productos` | Obtener todos los productos |
| GET | `/productos/{id}` | Obtener producto por ID |
| GET | `/restaurantes/{id}/productos` | Obtener productos de un restaurante |
| POST | `/productos` | Crear producto |
| PUT | `/productos/{id}` | Actualizar producto |
| DELETE | `/productos/{id}` | Eliminar o desactivar producto |

---

## Dependencias

Este contrato será utilizado por:

- Frontend: para mostrar restaurantes y productos.
- Carrito: para utilizar la información del producto.
- Pedidos: para almacenar la referencia del producto y su precio.
- API Gateway / BFF: para exponer y proteger posteriormente los endpoints.

Microservicios asociados:

- `restaurantes-service`
- `productos-service`

---

## Criterios de aceptación

- [x] DTO de Restaurante definido.
- [x] DTO de Producto definido.
- [x] Estados de Restaurante definidos.
- [x] Reglas principales documentadas.
- [x] Endpoints de Restaurantes documentados.
- [x] Endpoints de Productos documentados.
- [x] Relación Producto-Restaurante definida mediante `restauranteId`.