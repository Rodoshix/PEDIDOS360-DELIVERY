INSERT INTO productos (
    restaurante_id,
    nombre,
    descripcion,
    precio,
    categoria,
    disponible
) VALUES
    (1, 'Hamburguesa Clásica', 'Carne, queso, lechuga y tomate', 6990.00, 'HAMBURGUESAS', TRUE),
    (1, 'Hamburguesa Doble', 'Doble carne, queso y salsa especial', 8990.00, 'HAMBURGUESAS', TRUE),
    (1, 'Papas Fritas', 'Porción de papas fritas crujientes', 2990.00, 'ACOMPAÑAMIENTOS', TRUE),

    (2, 'Pizza Pepperoni', 'Pizza con queso mozzarella y pepperoni', 9990.00, 'PIZZAS', TRUE),
    (2, 'Pizza Vegetariana', 'Pizza con vegetales y queso mozzarella', 9490.00, 'PIZZAS', TRUE),

    (3, 'Sushi Salmón', 'Roll de salmón y palta', 6490.00, 'SUSHI', TRUE),
    (3, 'Sushi Tempura', 'Roll tempura con salsa especial', 6990.00, 'SUSHI', FALSE),

    (4, 'Menú Casero', 'Plato del día acompañado de ensalada', 5990.00, 'PLATOS', TRUE),

    (5, 'Ensalada César', 'Lechuga, pollo, queso y aderezo César', 5490.00, 'ENSALADAS', TRUE);