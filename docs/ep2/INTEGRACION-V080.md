# Integración v0.8.0 — procedimiento preparado, no ejecutado

Actualización 11-10-2026. Todos los PR permanecen Draft. No modifica develop/main ni autoriza merges, tags, release, AWS o despliegues.

## Heads revisables

| PR | Head | Dependencia |
|---|---|---|
| #113 | 303eae818c5dc008296b91e48d3fe6c6e5654a83 | develop f6a9540; agrega solo tres líneas operativas |
| #114 | 5a5e1cdfdeddac7bc622b0337ec0632b3fee4426 | develop f6a9540; sin cambios |
| #115 | 8eb68d93156e2dfe5464de3ed8813105216781f3 | develop f6a9540; fix únicamente POM/test source/documentación |
| #117 | bfce5aaa39e5d3dbf52ff2d17892fb778eaf066f | rama #115 actualizada; delta ACK intacto |
| #116 | a459a204258d73999bbb7967c5f4e957a0694e22 | develop f6a9540; sin cambios |

Develop remoto/local consultado: f6a9540e5db9890a7716184b917c718d35a21de6. Main remoto: 4373e613d02b2e8c8a9820d7b1080c8b9171e8f1. Main local estaba atrasado: no usar su SHA inicial como base. Refrescar referencias antes de cualquier integración posterior; un nuevo head requiere revisar su delta.

## Validación local realizada

Árbol temporal limpio creado desde develop mediante aplicación de los deltas exactos, sin commits de integración ni merge. #117 se aplicó respecto de su base #115. Árbol fuente validado antes de documentación: df20dd7c06685038c8d22c14d46d21af135ec5a3. Maven local inicialmente vacío: core 18 pruebas IdentityProofDeadlineLoggingTests, BFF instalado con classifier classes y tests omitidos explícitamente; Carrito 41 pruebas (servicio, BFF/directo, ACK y consumidor). Edge 16 y Compose 15: total 90, sin fallos u omisiones en esas suites. No se ejecutó suite integral del producto.

La primera reproducción Docker linux/amd64 de #115 falló: `Could not find artifact cl.duoc.pedidos360:bff:jar:classes:0.0.1-SNAPSHOT`, pese a maven.test.skip=true. Se usó cache BuildKit nuevo y contexto Docker original + messaging-core. Después del fix, mismo Dockerfile y otro cache nuevo: build exitoso, usuario 10001:10001. Es una imagen de validación local #115, no un nuevo artefacto publicado ni imagen conjunta de release.

Corrección: dependencia BFF y una fuente de tests optativas bajo `bff-http-integration`; prueba real movida sin cambios, SHA-256 `3a98d2420107a656b0db48f21d7c3aedc2503d0ae795f08ea0914b32bd7e8cd1`. El build de producción no incluye BFF/test. El arnés agrega solo src/bff-http-test/java, nunca todo ../../bff/src/main/java. [Instrucciones del perfil](../../backend/services/carrito-service/TEST-BFF.md).

[Reproducción PowerShell portable](../evidence/v0.8.0/reproduce-local.ps1) ejecuta las mismas suites en un repositorio Maven nuevo. Compose efectivo se comprobó con variables sintéticas y .env.example, sin leer secretos ni conectar a servicios remotos. HTTP migrado permanece HTTP; candidatos Pago y consultas se validaron por separado. Ningún resultado local cambia el estado operativo.

Para reproducir el build Docker desde la raíz y evitar un cache precargado, generar una copia temporal del Dockerfile sustituyendo únicamente el identificador del cache `pedidos360-maven` por uno nuevo; conservar el original intacto:

```powershell
$dockerTestDir = Join-Path ([IO.Path]::GetTempPath()) ('p360-docker-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $dockerTestDir | Out-Null
$dockerTestFile = Join-Path $dockerTestDir 'Dockerfile'
(Get-Content backend/services/carrito-service/Dockerfile -Raw).Replace('id=pedidos360-maven,', ('id=p360-clean-' + [guid]::NewGuid().ToString('N') + ',')) | Set-Content $dockerTestFile -Encoding utf8
docker buildx build --platform linux/amd64 --no-cache --progress plain --load --tag pedidos360-carrito:v080-local-validation --build-context messaging-core=backend/shared/p360-messaging-core --file $dockerTestFile backend/services/carrito-service
```

Este comando es local y no publica imágenes. Requiere Docker local; no usar credenciales AWS ni contextos privados.

## Orden posterior, sujeto a autorización expresa

1. Revisar nuevos deltas #113/#115 y rebase #117; conservar Draft hasta decisión.
2. Autorizar e integrar #113 → #114 → #115. Preferir merges que preserven ancestros para los PR apilados.
3. Tras #115, cambiar base de #117 a develop y verificar que solo muestra listener, test ACK y sus dos documentos. Si #115 se squash-mergea, trasladar únicamente el delta ACK a la nueva base y revisar el SHA resultante.
4. Integrar #117 → #116 → PR documental. El PR documental parte de #113 para no mezclar cambios Java; tras integrar #113, cambiar su base a develop y comprobar que solo contiene docs/evidencia.
5. Comprobar el árbol final y la validez de referencias del paquete. Si aparece diferencia productiva respecto del árbol ensayado, ejecutar las pruebas afectadas antes de continuar.
6. Abrir PR develop → main desde referencias remotas frescas; revisar manifiesto, diff y alcance parcial explícito.
7. Solo con autorización posterior: merge a main, registrar SHA final, crear tag anotado v0.8.0 sobre ese SHA y publicar release con notas de pendientes. Código integrado no autoriza despliegue, flags ni apertura.

Mantener imágenes anteriores y evidencias; no atribuir candidatas basadas en f6a9540 al nuevo árbol final. No usar build-minimal-local.py (histórico, base fija) como constructor del release. Ningún paso de esta fase ejecuta tag/release o recupera bootstrap.
