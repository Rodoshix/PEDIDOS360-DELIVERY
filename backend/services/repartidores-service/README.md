# Repartidores Service

Base del servicio de repartidores. Requiere Java 21 y Maven 3.9.15 (incluido mediante Maven Wrapper).

Desde esta carpeta, en PowerShell:

```powershell
.\mvnw.cmd verify
.\mvnw.cmd spring-boot:run
```

En otra terminal:

```powershell
Invoke-RestMethod http://localhost:8087/actuator/health
```

Debe devolver `status: UP`. Por defecto escucha solo en `127.0.0.1:8087`;
`SERVER_PORT` y `SERVER_ADDRESS` permiten cambiar esa configuración.

Esta primera etapa no contiene endpoints de repartidores, persistencia ni autenticación.
La base PostgreSQL/AWS RDS y la identidad de desarrollo se incorporarán en los siguientes pasos del Issue #12.
Antes de exponer operaciones de repartidores fuera del entorno local debe completarse su control de acceso.

Las descargas iniciales de Maven requieren conexión a Internet.
