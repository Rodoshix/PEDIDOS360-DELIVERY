# Contrato de Repartidores - Pedidos360

Responsable: Integrante 4

## Objetivo

Definir el contrato inicial del microservicio de repartidores de Pedidos360, estableciendo los DTO, estados y endpoints que utilizarán los demás integrantes del equipo (BFF, pedidos y frontend).

---

## DTO Repartidor (perfil)

```json
{
  "id": 1,
  "nombre": "Repartidor Uno",
  "telefono": "+56912345678",
  "vehiculo": "MOTO",
  "zona": "Norte",
  "estadoDisponibilidad": "EN_CAMINO",
  "creadoEn": "2026-09-04T12:00:00Z",
  "actualizadoEn": "2026-09-04T12:01:00Z",
  "version": 1
}
```

### Reglas de Repartidor

- `tenantId` + `entraObjectId` son únicos por repartidor (identidad externa) y no se exponen en la respuesta.
- El nombre es obligatorio (hasta 120 caracteres).
- El vehículo es obligatorio: MOTO, BICICLETA o AUTO.
- La zona y el teléfono son opcionales.

---

## DTO Asignación (pedido asignado)

```json
{
  "id": 3,
  "pedidoId": 100,
  "estado": "ASIGNADA",
  "asignadaEn": "2026-09-04T12:02:00Z",
  "nota": "Primer pedido"
}
```

### Reglas de Asignación

- `pedidoId` es estrictamente positivo y único por asignación.
- El estado es uno de los estados de asignación.
- La nota es opcional y admite hasta 500 caracteres.

---

## Vehículo

- MOTO
- BICICLETA
- AUTO

## Estados de disponibilidad

- DISPONIBLE
- OCUPADO
- EN_CAMINO
- EN_PAUSA
- DESCONECTADO
- INACTIVO
- SUSPENDIDO

## Estados de asignación

- ASIGNADA
- EN_CAMINO
- ENTREGADA
- CANCELADA

### Observaciones

- La secuencia de transiciones válidas por negocio debe acordarse con pedidos-service (Integrante 3). Este contrato acepta cualquier transición entre estados permitidos.

---

## DTO de entrada: crear/actualizar perfil

```json
{
  "nombre": "Repartidor Uno",
  "telefono": "+56912345678",
  "vehiculo": "MOTO",
  "zona": "Norte"
}
```

Reglas: `nombre` obligatorio; `vehiculo` obligatorio; `telefono` y `zona` opcionales.

## DTO de entrada: cambiar disponibilidad

```json
{
  "estado": "EN_CAMINO"
}
```

Reglas: `estado` obligatorio y de los permitidos.

## DTO de entrada: asignar pedido

```json
{
  "pedidoId": 100,
  "nota": "Primer pedido"
}
```

Reglas: `pedidoId` positivo; `nota` opcional hasta 500 caracteres.

## DTO de entrada: cambiar estado de asignación

```json
{
  "estado": "ENTREGADA"
}
```

Reglas: `estado` obligatorio y de los permitidos.

---

## Endpoints

| Método | Endpoint | Descripción |
|---|---|---|
| POST | `/repartidores` | Crea el perfil de la identidad actual. |
| GET | `/repartidores/me` | Perfil del actor actual. |
| GET | `/repartidores/{id}` | Perfil de un repartidor. |
| GET | `/repartidores?pagina=0&tamanio=20` | Listar (solo ADMIN). |
| PUT | `/repartidores/{id}` | Actualizar perfil. |
| PUT | `/repartidores/{id}/disponibilidad` | Cambiar disponibilidad. |
| DELETE | `/repartidores/{id}` | Pasar a INACTIVO. |
| GET | `/repartidores/{id}/asignaciones` | Listar asignaciones. |
| POST | `/repartidores/{id}/asignaciones` | Asignar un pedido. |
| PUT | `/repartidores/{id}/asignaciones/{pedidoId}/estado` | Cambiar estado de asignación. |

---

## Dependencias

Este contrato será utilizado por:

- BFF: para exponer y proteger posteriormente los endpoints ante el frontend y API Gateway.
- Pedidos: para vincular la asignación por `pedidoId` y coordinar el flujo de estados.
- Frontend: para mostrar el perfil, disponibilidad y asignaciones del repartidor.

Microservicio asociado:

- `repartidores-service`

---

## Criterios de aceptación

- [x] DTO de Repartidor (perfil) definido.
- [x] DTO de Asignación (pedido asignado) definido.
- [x] Estados de disponibilidad y asignación definidos.
- [x] Vehículos permitidos definidos.
- [x] Reglas de negocio documentadas.
- [x] Endpoints de consulta y actualización documentados.
- [x] Relación con el pedido mediante `pedidoId` definida (coordinación con Integrante 3).
