# Restaurantes Service

El HTTP existente conserva el catálogo y CRUD. EP2-14 agrega un consumer request/reply opcional, DISABLED por defecto, para `restaurante.listar.v1` con payload `{}`; incluye restaurantes INACTIVOS.

Contrato, seguridad, pruebas reales y comandos: [evidencia EP2-14](../../../docs/ep2/RESTAURANTES-RABBITMQ.md).

La activación y el corte BFF pertenecen a #70. La plataforma #69 provisiona colas/policies/permisos; la aplicación no declara topología. No activar este consumer sin configuración y coordinación operativa.
