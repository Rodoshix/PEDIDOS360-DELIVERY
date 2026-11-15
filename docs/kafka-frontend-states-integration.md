# Estados integrados de observaciones Kafka

Fecha: 13/11/2026. Base develop: `3cf2229b09a39ecc5f1cc01b7526bc186062db7f`.
Cambios exclusivamente locales posteriores a esa base, sin commit ni publicación.

## Brecha y método

Las pruebas anteriores de `kafkaObservationsUi.test.js` sustituyen hooks y cliente;
las de `apiClient.test.js` comprueban el transporte por separado. La aceptación AWS
ya acredita los dos recorridos positivos, persistencia, BFF/API y consumo por React.
No se repiten esas pruebas AWS ni se atribuyen nuevos resultados a ellas.

El nuevo fixture monta **KafkaObservations sin modificar en React y DOM reales**.
Ejecuta los hooks, validación `observations`, Axios e interceptores de
`createApiClient` reales. Solo el módulo singleton del cliente se sustituye por una
instancia de la misma fábrica con transporte API controlado y autenticación sintética;
no se usa MSAL/Entra real ni se extraen tokens. Los scripts pertenecen al fixture,
no crean una funcionalidad ni ruta de la aplicación productiva.

La cadena comprobada es React → fábrica HTTP real → respuestas API simuladas →
validador → renderizado React/DOM. **No es React → BFF → servicios reales**.

## Reproducción local

Desde `frontend`, con las dependencias del lockfile:

```powershell
node tools/kafka-observations-integration.mjs
```

Abrir `http://127.0.0.1:5187/__test-kafka-observations` en un navegador local.
La página ejecuta los escenarios y muestra un JSON final PASS/FAIL. El servidor
se limita a loopback y no pertenece al build normal. Cerrar con Ctrl+C.
No ejecutar este fixture contra AWS: su transporte simulado no permite escrituras.

Regresión focalizada ejecutada:

```powershell
node --test tests/kafkaObservations.test.js tests/kafkaObservationsUi.test.js tests/apiClient.test.js
npx --no-install oxlint tools/kafka-observations-integration.mjs tests/fixtures/kafkaObservations.integration.jsx
```

## Resultados observados

| Verificación | Restaurante / Productos | Producto / Carrito |
|---|---|---|
| Carga inicial visible y botón deshabilitado mientras espera API | PASS local | PASS local |
| Error API 503, sin filtrar detalles y sin reintento automático | PASS local | PASS local |
| Recuperación mediante acción explícita de actualizar | PASS local | PASS local |
| Estado desconocido explícito, sin inventar valor o fecha | PASS local | PASS local |
| Observación conocida y fecha exacta del contrato | PASS local | PASS local |
| Actualización oculta observación anterior durante la espera | PASS local | PASS local |
| Respuesta nueva reemplaza valor y timestamp observado | PASS local | PASS local |
| Respuesta tardía cancelada no reaparece al deshabilitar | PASS local | PASS local |
| Agregado carrito intacto; solo cinco GET sintéticos por escenario | PASS local | PASS local |

Dos escenarios integrados de navegador PASS; 18 pruebas Node relacionadas PASS,
0 fallos y 0 omisiones. Lint focalizado finalizó correctamente. No son veinte
pruebas de una misma suite: se distinguen el fixture navegador y la selección Node.

La frescura implementada es la fecha informativa `observadoEn` y el reemplazo de
la observación al actualizar/cambiar de ámbito. No hay un umbral TTL ni una promesa
de actualidad de negocio; el texto conserva que no confirma disponibilidad de compra.
No se implementó una política de caducidad nueva para satisfacer la prueba.

## Límites y KP-73

Los estados frontend faltantes quedan acreditados en integración local con API simulada,
complementando la aceptación AWS positiva existente. No demuestra estados de error/carga
originados por BFF y servicios reales ni reemplaza silenciosamente el compromiso vigente
de una demostración conjunta con todas esas capas. KP-73 debe permanecer abierta mientras
se conserve esa exigencia: falta la sesión integrada correspondiente o una decisión
expresa de aceptar esta evidencia local junto a la aceptación AWS ya existente.

No se modificaron componentes productivos, backend, configuración, datos o infraestructura.
No se ejecutaron nuevas pruebas AWS, carga ni inyección de fallos. El fixture no usa
ninguno de los veinte archivos temporales de diagnóstico del checkout original.
