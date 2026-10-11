# Prueba HTTP real de Carrito y BFF

La prueba `CarritoRabbitHttpGuardTests` vive en `src/bff-http-test/java` y conserva su contenido. Arranca Carrito, BFF real, PostgreSQL y RabbitMQ locales; verifica DELETE directo y vía BFF, JWT/tenant/propietario, ítems, snapshot y reentrega. No es un mock de la ruta BFF.

El perfil `bff-http-integration` agrega únicamente esa fuente de tests y la dependencia `bff:classes` en scope test. No agrega el código fuente del BFF a Carrito ni entra en el artefacto productivo. Está desactivado por defecto para que el contexto Docker de Carrito solo necesite messaging-core.

Desde la raíz, con Java 21, Maven y Docker local:

```sh
mvn -B -ntp -Dmaven.repo.local=/ruta/m2-limpio -f backend/shared/p360-messaging-core/pom.xml -Dmaven.test.skip=true install
mvn -B -ntp -Dmaven.repo.local=/ruta/m2-limpio -f backend/bff/pom.xml -DskipTests install
mvn -B -ntp -Dmaven.repo.local=/ruta/m2-limpio -f backend/services/carrito-service/pom.xml -Pbff-http-integration -Dtest=CarritoServiceTests,CarritoRabbitHttpGuardTests verify
```

No omitir este perfil en la validación de integración del guard: el build de producción y los tests por defecto no ejecutan la prueba BFF. La preparación del classifier requiere `install`, no solo `package`, porque los módulos no forman un reactor Maven.

Para producción se conserva el Dockerfile y su build-context `messaging-core`; no activar el perfil ni instalar BFF dentro de la imagen. Un cache Maven precargado puede ocultar la dependencia incorrecta anterior: la reproducción debe usar un cache nuevo.
