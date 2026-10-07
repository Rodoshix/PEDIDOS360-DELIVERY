# Plataforma local EP2 (#69)

Stack separado; no modifica el stack Docker actual ni `.local-infra`.

Runbook, inventario, policies, permisos, pruebas y límites:
[PLATAFORMA-RABBITMQ.md](../../docs/ep2/PLATAFORMA-RABBITMQ.md).

PowerShell 7, Python 3.11+, Docker Compose v2, Java 21 para la prueba de aplicación:

```powershell
./New-LocalEnvironment.ps1
./Initialize-Platform.ps1
$env:EP2_PLATFORM_TESTS='1' # Solo broker dedicado, vacío y sin consumers funcionales
./.venv/Scripts/python.exe test_platform.py
./Test-Application.ps1
```

No se eliminan ni purgan colas. Las pruebas consumen/ACK exclusivamente mensajes
de prueba; nunca ejecutarlas sobre datos compartidos. Conservan el volumen.
El sandbox conserva `demo.platform` / `demo.platform.q` como evidencia independiente.
