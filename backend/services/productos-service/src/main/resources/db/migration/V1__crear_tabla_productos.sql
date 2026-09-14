CREATE TABLE productos (
    id BIGSERIAL PRIMARY KEY,
    restaurante_id BIGINT NOT NULL,
    nombre VARCHAR(120) NOT NULL,
    descripcion VARCHAR(500),
    precio NUMERIC(10,2) NOT NULL,
    categoria VARCHAR(80) NOT NULL,
    disponible BOOLEAN NOT NULL DEFAULT TRUE,

    CONSTRAINT chk_productos_precio_positivo
        CHECK (precio > 0)
);