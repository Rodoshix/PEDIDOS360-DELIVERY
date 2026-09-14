-- Scope de la clave de idempotencia por identidad (evita fuga entre usuarios).
ALTER TABLE pagos DROP CONSTRAINT uk_pagos_idempotencia;
ALTER TABLE pagos ADD CONSTRAINT uk_pagos_idempotencia_usuario UNIQUE (usuario_id, clave_idempotencia);

-- Estado de coordinación con Pedidos (recuperable): false = confirmación pendiente.
ALTER TABLE pagos ADD COLUMN pedido_confirmado BOOLEAN NOT NULL DEFAULT FALSE;

-- Invariancia: un solo pago activo (PENDIENTE/APROBADO) por pedido, garantizado en PostgreSQL.
CREATE UNIQUE INDEX uk_pagos_pedido_activo ON pagos (pedido_id)
    WHERE estado IN ('PENDIENTE', 'APROBADO');
