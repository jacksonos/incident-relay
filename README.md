# Incident Replay

Convierte logs ficticios de un incidente en una cronología y un borrador de postmortem verificable. Proyecto en construcción por fases: consultar `HANDOFF.md` y `PLAN.md`.

Repositorio de destino indicado por el usuario: https://github.com/jacksonos/incident-relay.git (el producto se llama Incident Replay).

## Requisitos y pruebas
- JDK 21; acceso a Internet para descargar Maven y dependencias en la primera ejecución.
- Windows: `.\mvnw.cmd -f backend/pom.xml test`
- Linux/macOS: `sh mvnw -f backend/pom.xml test`
- Build: sustituir `test` por `package`.

La interfaz está en `frontend/`; ver [instrucciones de ejecución y pruebas](frontend/README.md). No hay persistencia. El análisis inicial es determinista; la IA opcional está desactivada por defecto y requiere consentimiento. Para iniciar la API: `java -jar backend/target/incident-replay-0.1.0-SNAPSHOT.jar` después del build. Por defecto escucha solo en `127.0.0.1`. Es posible sobrescribirlo explícitamente con `--server.address=<dirección>`, pero no debe exponerse sin evaluar seguridad e infraestructura. Solo usar datos ficticios en un entorno local de confianza.

## API HTTP
- `GET /api/health`: `200 {"status":"ok"}`.
- `POST /api/analyze`: `Content-Type: application/json`, cuerpo UTF-8 `{"text":"2026-10-15T10:00:00Z INFO app - Ready"}`. `text` debe ser cadena; las claves duplicadas se rechazan.
- `200`: `{"analysis": {...}, "postmortemMarkdown": "..."}`. `analysis` incluye `events` (con `id`, `lineNumber`, timestamps, nivel, servicio, mensaje y `rawLine`), `invalidLineNumbers`, `blankLineCount`, `warnings`, `summary` (conteos y rango UTC) y `groups` (mensaje normalizado y IDs). El Markdown cubre todo el análisis, sin filtros.
- `400 {"error":"Entrada inválida."}` para JSON, UTF-8 o texto inválido, vacío o sin eventos; `413 {"error":"Límite excedido."}` para límites superados. No se incluye la entrada en esos errores.
- Ese formato de error corresponde a la validación de aplicación. Un framing HTTP inválido (por ejemplo, chunks malformados) puede ser rechazado directamente por Tomcat con un 400 cuyo cuerpo no sigue ese contrato; no asumir un envelope JSON uniforme para errores de transporte.
- El texto decodificado admite hasta 1 048 576 bytes UTF-8 y 10 000 líneas no vacías. El cuerpo JSON completo tiene límite independiente de 6 292 480 bytes (6 × 1 MiB + 1024), comprobado durante la lectura incluso sin `Content-Length`; permite escapes JSON para un texto de hasta 1 MiB, pero no espacios o campos adicionales ilimitados. No hay endpoint separado de exportación.

### IA opcional
- `GET /api/insights/config` devuelve solo `{"available":true}` o `{"available":false}`, sin consultar al proveedor.
- `POST /api/insights`: JSON UTF-8 con `consent: true`, `totalEvents` entero entre 1 y 10 000 y `events` (1–100). Cada evento tiene exactamente `id`, `lineNumber`, `timestampUtc`, `level`, `service`, `message`, ordenado por instante UTC y línea; `id` es `event-<lineNumber>`. `totalEvents` no puede ser menor que el número seleccionado. No enviar `rawLine` ni `timestampOriginal`.
- El array de eventos serializado como JSON compacto no puede superar 65 536 bytes UTF-8; el envelope completo no puede superar 81 920 bytes, incluidos cuerpos chunked. No se truncan mensajes. El servidor valida la forma pero no puede acreditar que los eventos procedan de `/api/analyze`.
- `200` devuelve `mode: "assisted"`, `hypotheses` (cada una con `statement`, `evidenceEventIds`, `uncertainties`, `nextChecks`), `openQuestions`, `warnings` y `coverage: {selectedEvents,totalEvents}`. Se descartan hipótesis con referencias vacías, inexistentes o duplicadas; una referencia válida no demuestra causalidad.
- Errores de aplicación con `{"error":"..."}`: 400 entrada inválida, 413 límite, 503 no disponible, 502 error o salida inválida del proveedor, 504 timeout. Si está deshabilitado se devuelve 503 antes de leer o validar el cuerpo. Errores de transporte o tipo de contenido pueden ser gestionados por Tomcat/Spring fuera de este formato.

Variables de entorno del servidor: `IR_AI_ENABLED` (por defecto `false`), `IR_AI_API_KEY` y `IR_AI_MODEL` (vacíos por defecto), `IR_AI_ENDPOINT` (por defecto `https://api.openai.com/v1/chat/completions`). El endpoint debe ser compatible con OpenAI Chat Completions y usar HTTPS sin userinfo, query ni fragmento. `.env.example` es una referencia: Spring no carga automáticamente ese archivo; exportar variables en el entorno del proceso. Nunca guardar claves reales en el repositorio. Ver [ADR 0002](docs/adr/0002-ia-opcional.md).

## Núcleo disponible
- `backend/src/main/java/dev/incidentreplay/LogParser.java`: `parse(String)` y `parse(byte[])`; devuelve eventos, resumen global, rechazos, advertencias y grupos repetidos inmutables.
- `backend/src/main/java/dev/incidentreplay/PostmortemGenerator.java`: `generate(Analysis)` produce un borrador Markdown del análisis completo, sin IA ni llamadas externas.
- `fixtures/incident.log` y `fixtures/normal.log`: datos de demostración consumidos por las pruebas.
- `docs/pruebas-fase-1.md`: correspondencia entre requisitos y pruebas.

El reporte conserva evidencia como texto escapado para que mensajes con HTML o Markdown no se interpreten como contenido activo. No determina causas ni impacto. El análisis determinista no requiere claves.

## Formato
`2026-10-15T10:02:14Z INFO payments - Request latency increased`

Una línea por evento, UTF-8, LF/CRLF, timestamp ISO 8601 con `Z` o desplazamiento, nivel DEBUG/INFO/WARN/ERROR, servicio sin espacios y mensaje no vacío. Máximo 1 MiB y 10 000 líneas no vacías. Líneas vacías se cuentan aparte; las inválidas se reportan por número. Sin eventos válidos, la entrada se rechaza.

## Datos y límites
Solo usar ejemplos ficticios; no introducir secretos ni datos personales. El núcleo no almacena ni registra el contenido de entrada; la interfaz lo mantiene en memoria durante la sesión y lo envía a la API local al analizar. Al solicitar IA se envían los eventos seleccionados al proveedor configurado, que puede retenerlos. No se ha probado un proveedor real ni se afirma despliegue público.
