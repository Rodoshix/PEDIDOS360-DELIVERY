# Pedidos360 Delivery

Plataforma web cloud-native para gestionar pedidos y delivery de comida. Este repositorio contiene el frontend React + Vite, el BFF Spring Boot, ocho microservicios, documentación, pruebas e infraestructura del proyecto.

## Arquitectura base

```text
React + Vite
      |
Microsoft Entra ID / MSAL
      |
AWS API Gateway
      |
BFF - Spring Boot
      |
      +-- usuarios-service
      +-- restaurantes-service
      +-- productos-service
      +-- carrito-service
      +-- pedidos-service
      +-- pagos-service
      +-- repartidores-service
      +-- seguimiento-service
      |
PostgreSQL / Amazon RDS
```

El BFF es un componente adicional y no se cuenta dentro de los ocho microservicios funcionales.

## Estructura

```text
PEDIDOS360-DELIVERY/
|-- .github/                 Plantillas de issues, PR y CI
|-- backend/
|   |-- bff/                 Seguridad JWT y encaminamiento
|   `-- services/            Ocho microservicios Spring Boot
|-- frontend/                Aplicación React + Vite
|-- infrastructure/          Docker y configuración AWS
|-- docs/                    Documentación que se agregará cuando sea necesaria
|-- scripts/                 Automatización de desarrollo y despliegue
`-- tests/                   Pruebas E2E, seguridad y colecciones API
```

## Responsabilidades

| Plan | Propiedad principal |
|---|---|
| Integrante 1 | React base, MSAL, Usuarios y Carrito |
| Integrante 2 | Restaurantes y Menú/Productos |
| Integrante 3 | Pedidos, Pagos y convenciones de persistencia |
| Integrante 4 | Repartidores, Seguimiento, BFF y base AWS |
| Rol adicional | Integración, QA, revisión y release |

Cada integrante implementa también las pantallas React, pruebas, documentación y Docker de su propio dominio.

## Flujo Git

- `main`: solo versiones demostrables.
- `develop`: integración diaria.
- `feature/iN-issue-descripcion`: nueva funcionalidad.
- `fix/iN-issue-descripcion`: corrección.
- `test/iN-issue-descripcion`: pruebas.
- `docs/iN-issue-descripcion`: documentación.
- `release/ep1`: estabilización antes de entregar.

No se realizan cambios directos en `main` o `develop`. Todo cambio debe llegar mediante pull request revisado.

Consulta [CONTRIBUTING.md](CONTRIBUTING.md) antes de crear una rama.

## Seguridad

- No subir archivos `.env`, secretos, tokens ni credenciales AWS/Azure.
- Mantener valores de ejemplo únicamente en `.env.example`.
- Rotar inmediatamente cualquier secreto publicado accidentalmente.
- Validar en el BFF firma, issuer, audience, expiración, roles y scopes del JWT.

## Estado del repositorio

Esta confirmación inicial contiene únicamente la estructura compartida. Cada componente debe generarse e implementarse en su rama correspondiente.
