# Rollback de infraestructura #71

1. Confirmar que aplicaciones conservan HTTP, platform-ready=false, relay DISABLED
   y declare-topology=false. No usar este procedimiento como rollback del futuro
   corte #70 sin su runbook específico.
2. Desde el paquete ejecutar `python3 -B scripts/tools.py rollback`: Compose STOP
   únicamente del servicio rabbitmq del proyecto EP2. No toca Entrega 1.
3. Confirmar broker detenido y health/API HTTP Entrega 1 recuperados; usar sesión
   Entra válida para regresión autenticada. No manipular tokens desde el chat/logs.
4. Preservar EBS, mount, cookie, TLS y contraseñas, container y red backend externa.
   No DELETE, purge, `down -v`, formateo, desmontaje automático ni borrado de datos.
5. Registrar causa y testigos privados fuera de Git. Corregirla antes de reiniciar:
   preflight → start → verify. Si filesystem/cookie cambió, detenerse e inspeccionar.

Rollback no elimina recursos AWS ni deshace topología: conservarlos evita perder
mensajes. Este paquete no altera API Gateway, Lambda, RDS, Entra ni certificados de
Entrega 1. Reinicio del host y recuperación del EBS necesitan ventana propia.
