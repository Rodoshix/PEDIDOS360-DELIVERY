# Evidencias portables v0.8.0 — 11-10-2026

Paquete sanitizado, sin logs crudos ni configuración privada. [Procedencia](PROCEDENCIA.json) registra identificador relativo de custodia y SHA-256 de cada original; [hashes del paquete](HASHES.json) permite comprobar los archivos distribuidos. Los originales externos permanecen privados: este paquete exporta únicamente hechos seleccionados, no sustituye una traza completa. No contiene bearer, huellas de sesiones reales, credenciales, payloads personales ni snapshots de clientes.

## AWS histórico — no son pruebas nuevas

| Archivo | Evidencia / límite |
|---|---|
| [Usuarios y Restaurantes](aws-usuarios-restaurantes.json) | UUID, correlación, RESPONDIDA_ACK y retryCount=0; sin captura directa de status HTTP |
| [Productos](aws-productos.json) | GET exacto /disponibles, HTTP 200, contrato y ACK |
| [Pagos consulta](aws-pagos-consulta.json) | GET pago #2, HTTP 200, EFECTIVO/PENDIENTE y ACKs |
| [Pago→Pedido](aws-pago-pedido.json) | Evento/persistencia acreditados; POST público 502, aceptación CLIENTE parcial |
| [GET recuperación](aws-recuperacion-get.json) | Pedido #4 y pago #3, HTTP 200 y contratos |
| [Cierre HTTP](aws-cierre-http.json) | Último estado conservado: salud, TLS, datos y mantenimiento; no salud cloud fresca |
| [HEAD 500](aws-head500.json) | Causa no acreditada, métricas faltantes desconocidas; incidente distinto del 502 |

## Local y pendiente

| Archivo | Evidencia / límite |
|---|---|
| [Candidata Carrito](local-carrito-candidata.json) | Arranque y bytecode local; no despliegue/aceptación AWS |
| [Integración de cinco PR](local-integracion.json) | 90 pruebas aprobadas; 59 Maven + 16 Edge + 15 Compose, cero fallos/omisiones; build limpio reproducido y corregido |
| [Compose efectivo](local-compose.json) | HTTP migrado y candidatos separados con fixtures; sin despliegue |
| [RabbitAdmin](../../ep2/RABBITADMIN-67-tests.json) | 61 pruebas históricas locales; DELETE queue deshabilitado, requisito incompleto |
| [Clúster demo](../../ep2/CLUSTER-DEMO-73-tests.json) | Pruebas locales; no prueba de HA ni aceptación cloud integral |

[Reproducción local](reproduce-local.ps1), [cierre/matriz](../../ep2/CIERRE-V080.md) y [integración sin ejecutar](../../ep2/INTEGRACION-V080.md). Las capturas anteriores de UI son fixtures locales y no evidencian AWS: [declaración](../60-client-commerce/browser-review.json).

Para comprobar hashes, desde la raíz:

```sh
python docs/evidence/v0.8.0/verify-evidence.py
```

La pauta menciona Angular. No se encontró autorización docente de React en el material revisado; declarar esta diferencia hasta aportar decisión verificable. Pedido→Carrito no está aceptado AWS. Ningún resultado local CAPTURED, build o test autoriza apertura, merge o release.
