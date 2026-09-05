# Contrato de Seguimiento - Pedidos360

Responsable: Integrante 4

## Objetivo

Definir el contrato inicial del microservicio de seguimiento de Pedidos360, estableciendo los DTO, estados y endpoints que utilizarán los demás integrantes del equipo (BFF, pedidos y frontend).

---

## DTO Seguimiento (estado actual)

```json
{
  "id": 1,
  "pedidoId": 100,
  "estadoActual": "EN_CAMINO",
  "creadoEn": "2026-09-04T12:00:00Z",
  "actualizadoEn": "2026-09-04T12:01:00Z",
  "version": 1
}
```

### Reglas de Seguimiento

- `pedidoId` es estrictamente positivo y único por pedido.
- El estado actual es siempre uno de los estados permitidos.
- La desactivación no existe: un seguimiento se conserva y solo cambia de estado.

---

## DTO Evento (historial)

```json
{
  "id": 2,
  "estado": "EN_CAMINO",
  "ocurridoEn": "2026-09-04T12:01:00Z",
  "nota": "Repartidor en ruta"
}
```

### Reglas de Evento

- Cada cambio de estado genera un evento con la fecha de ocurrencia (UTC).
- La nota es opcional y admite hasta 500 caracteres.
- El historial se devuelve ordenado por fecha ascendente.

---

## DTO Historial

```json
{
  "pedidoId": 100,
  "estadoActual": "EN_CAMINO",
  "eventos": [
    { "id": 1, "estado": "RECIBIDO", "ocurridoEn": "2026-09-04T12:00:00Z", "nota": "Seguimiento iniciado." },
    { "id": 2, "estado": "EN_CAMINO", "ocurridoEn": "2026-09-04T12:01:00Z", "nota": "Repartidor en ruta" }
  ]
}
```

---

## Estados permitidos

- RECIBIDO
- EN_PREPARACION
- LISTO
- EN_CAMINO
- ENTREGADO
- CANCELADO
- RECHAZADO
- DEVOLUCION

### Observaciones

- La secuencia de transiciones válidas por negocio debe acordarse con pedidos-service (Integrante 3). Este contrato acepta cualquier transición entre estados permitidos.

---

## DTO de entrada: iniciar seguimiento

```json
{
  "pedidoId": 100,
  "estadoInicial": "RECIBIDO"
}
```

Reglas: `pedidoId` positivo; `estadoInicial` uno de los estados permitidos.

## DTO de entrada: cambiar estado

```json
{
  "estado": "EN_CAMINO",
  "nota": "Repartidor en ruta"
}
```

Reglas: `estado` obligatorio y de los permitidos; `nota` opcional hasta 500 caracteres.

---

## Endpoints

| Método | Endpoint | Descripción |
|---|---|---|
| GET | `/seguimientos?pagina=0&tamanio=20` | Listar seguimientos (paginado). Solo ADMIN. |
| GET | `/seguimientos/{pedidoId}` | Estado actual de un pedido. |
| GET | `/seguimientos/{pedidoId}/historial` | Estado actual e historial de un pedido. |
| POST | `/seguimientos` | Iniciar el seguimiento de un pedido. |
| PUT | `/seguimientos/{pedidoId}/estado` | Cambiar el estado y registrar el evento. |

---

## Dependencias

Este contrato será utilizado por:

- BFF: para exponer y proteger posteriormente los endpoints ante el frontend y API Gateway.
- Pedidos: para vincular el seguimiento por `pedidoId` y coordinar el flujo de estados.
- Frontend: para mostrar el estado del pedido y su historial al cliente.

Microservicio asociado:

- `seguimiento-service`

---

## Criterios de aceptación

- [x] DTO de Seguimiento (estado actual) definido.
- [x] DTO de Evento (historial) definido.
- [x] Estados permitidos definidos.
- [x] Reglas de negocio documentadas.
- [x] Endpoints de consulta y actualización documentados.
- [x] Relación con el pedido mediante `pedidoId` definida (coordinación con Integrante 3).
