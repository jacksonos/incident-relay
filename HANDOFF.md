# Handoff

## Autorización
Fases 0–4 completadas. El usuario autorizó publicar con la cuenta personal `jacksonos` y continuar con fase 4 (IA opcional), verificada con proveedor simulado. Fase 5 bloqueada hasta nueva autorización.

Autorización adicional completada: mejorar la interfaz con `design-taste-frontend`, como refinamiento conservador de fase 3 sobre `feat/deterministic-ui`. No autoriza fase 4 ni cambios de contrato/backend. Flujo, evidencia y privacidad preservados; resultados del refinamiento registrados abajo.

## Fase 0
Completada. Monorepo, instrucciones, plan, ADR, Java 21 / Spring Boot 3.5.7 y Maven wrapper 3.9.11.

Comprobaciones ejecutadas el 2026-11-03:
- Validación PowerShell de los 11 archivos/directorios de fundación: sin faltantes.
- `.\mvnw.cmd -B -ntp -f backend/pom.xml test`: BUILD SUCCESS; 1 prueba, 0 fallos, 0 errores, 0 omitidas.
- La primera generación del wrapper falló por el tratamiento de `-Dmaven=3.9.11` en PowerShell; se corrigió entrecomillando el argumento y se volvió a ejecutar correctamente.
- Advertencia del runtime de pruebas: Mockito carga dinámicamente un agente en Java 21; no impide las pruebas.

## Fase 1
Completada tras superar las comprobaciones de fase 0.

Entregado:
- Dos fixtures ficticios: incidente (11 eventos, una línea rechazada y una vacía) y actividad normal (tres eventos).
- `LogParser`: texto/bytes UTF-8 estrictos, límites de 1 MiB / 10 000 líneas no vacías, formato explícito, rechazo de fechas imposibles, evidencia original e IDs por línea.
- Orden UTC estable por línea; resumen completo, conteos por nivel, grupos repetidos y advertencias de análisis parcial. Resultados inmutables.
- `PostmortemGenerator`: Markdown completo sin IA, evidencia, modo, UTC, impacto desconocido, hipótesis/acciones pendientes, limitaciones y escape de contenido no confiable.
- Pruebas de todos los requisitos de sección 9 puntos 1–4, con matriz en `docs/pruebas-fase-1.md`.

Verificación real el 2026-11-03 (Windows, JDK Temurin 21.0.12.1):

```text
.\mvnw.cmd -B -ntp -f backend/pom.xml clean verify

IncidentReplayApplicationTest: 1 prueba
LogParserTest: 36 pruebas
PostmortemGeneratorTest: 4 pruebas
Tests run: 41, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
Total time: 7.207 s
```

La ejecución anterior de `test` también pasó con 41 pruebas. Se recompiló desde cero y se generó `backend/target/incident-replay-0.1.0-SNAPSHOT.jar`. Informes: `backend/target/surefire-reports/`.

## Fase 2
Completada, autorizada por el usuario. Orquestación ejecutada: Terra medium implementó; Luna low verificó; Sol high revisó límites HTTP y errores seguros.

Baseline previo a implementación ejecutado por Luna low: `.\mvnw.cmd -B -ntp -f backend/pom.xml test`, exit code 0, BUILD SUCCESS; 41 pruebas, 0 fallos, 0 errores y 0 omitidas. Continúan las advertencias conocidas de Mockito/Byte Buddy.

Entregado:
- Spring Web; Spring Boot actualizado de 3.5.7 a 3.5.16 tras consultar metadatos de Maven (no equivale a auditoría de vulnerabilidades).
- `GET /api/health` y `POST /api/analyze`, JSON UTF-8 con `text` string, respuesta con `analysis` completo y `postmortemMarkdown` determinista.
- Errores de aplicación seguros 400/413. Rechazo de coerción de tipos, claves duplicadas, JSON malformado o adicional, charset incompatible y UTF-8 inválido.
- Límites: texto de 1 MiB y 10 000 líneas no vacías; envelope JSON separado de 6 292 480 bytes, acotado durante lectura incluso en chunked sin Content-Length. Se permite el sobrecoste de escapes JSON.
- Lectura fallida normalizada a 400 por el controlador. Tomcat puede rechazar framing HTTP inválido con su propio 400; no se promete ese envelope de aplicación en errores de transporte. README aclara la distinción.
- Escucha local por defecto: `127.0.0.1`; override explícito documentado, no hay despliegue público.
- Pruebas de contrato completo, fixtures parciales, límites exactos/excesos, charset, JSON/Unicode inválidos, chunked y errores de lectura.

Resultado final independiente de Luna low:

```text
.\mvnw.cmd -B -ntp -f backend/pom.xml clean verify

ApiControllerTest: 7 pruebas
IncidentReplayApplicationTest: 1 prueba
LogParserTest: 36 pruebas
PostmortemGeneratorTest: 4 pruebas
Tests run: 48, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS (exit code 0)
```

JAR generado: `backend/target/incident-replay-0.1.0-SNAPSHOT.jar`. Informes: `backend/target/surefire-reports/`. Los tests HTTP usan servidor embebido real en puerto aleatorio.

Revisión independiente Sol high: se reforzaron contrato completo, charset y framing HTTP. Último pendiente documental sobre el envelope de errores de transporte corregido en README. No se detectaron otros defectos confirmados; esto no garantiza ausencia de bugs.

Incidencias durante implementación: un fallo inicial de compilación por conversión de fixtures byte[]/String, corregido; un test de framing esperaba el error del controlador pero Tomcat devuelve su propio 400, se ajustó para comprobar status seguro y ausencia de contenido/stacktrace, y se probó por separado IOException del controlador. No se midió consumo de tokens ni ahorro; no se cambió el reparto de modelos/esfuerzos.

## Fase 3
Completada y verificada el 2026-11-04. Rama: `feat/deterministic-ui`. Orquestación ejecutada sin cambios: Terra medium implementó, Luna low verificó y Sol medium revisó. Fase 4 no autorizada.

Entregado:
- React + Vite + Axios y TypeScript, CSS nativo; ejemplos importados de los fixtures originales sin duplicarlos.
- Fuente explícita texto/archivo/ejemplo; no hay envío automático al elegir fuente o archivo. UTF-8 estricto, extensión, vacío y límites de 1 MiB / 10 000 líneas no vacías en navegador, además de validación backend.
- Resumen global, niveles, rango UTC, repetidos, advertencias/líneas rechazadas; filtros y búsqueda no alteran totales ni descarga.
- Detalle con evidencia original, línea y timestamps; borrador seguro como texto y descarga Markdown completa tras acción explícita.
- Cancelación/invalidation de solicitudes y lecturas obsoletas, mensajes seguros de carga/error/sin coincidencias. Sin almacenamiento persistente ni IA. Backend sin cambios.
- Vite y pruebas en loopback, proxy /api sin CORS abierto. Comandos en frontend/README.md; requiere JAR construido y puertos 8080/5173 libres para E2E.

Verificación final independiente de Luna low:

```text
frontend: npm.cmd run build — exit code 0
frontend: npm.cmd run test:e2e — 9 passed, 0 failed, exit code 0
raíz: .\mvnw.cmd -B -ntp -f backend/pom.xml clean verify
48 pruebas backend, 0 fallos, 0 errores, 0 omitidas; BUILD SUCCESS, exit code 0
```

Cobertura E2E: flujo contra API real y descarga exacta; archivos inválidos/normal y no autoenvío; errores 400/413/red y cancelación; teclado/móvil; lectura de archivo obsoleta; cuatro fronteras de bytes/líneas del texto con conteos de petición y payload. En los tests de aceptación exacta, un 400 simulado prueba que el validador frontend permite el envío, no un parsing exitoso. El flujo principal sí utiliza el backend real.

Historial del bloqueo resuelto: al reforzar límites hubo dos intentos fallidos con timeout de `fill` al reemplazar texto grande. Se avisó al usuario y se detuvo el ciclo; este autorizó continuar sin cambios de reparto. Aislar casos resolvió ambos tests multibyte, pero la inserción de 10 000 líneas seguía agotando tiempo. Un experimento acotado reprodujo timeout de 5 008 ms en textarea nativo sin React; setter nativo + evento input y validación en la app completaron en 151 ms. Solo las dos fronteras de líneas usan esa ruta documentada de evento real, sin modificar producción ni aumentar el timeout general. Queda sin comprobar la interacción nativa de inserción masiva; no se afirma que esté calibrada en hardware real.

Revisión final Sol medium: sin defectos funcionales confirmados; verificó los refuerzos de concurrencia/límites y CSS completo. Orquestador inspeccionó capturas reales desktop y móvil de Playwright: legibles, jerarquía sobria y sin overflow global observado. Los tests comprueban anchuras 320 y 375 px; esto no sustituye una prueba en teléfono real. Capturas ignoradas en frontend/test-results/.

Auditoría npm ejecutada por el orquestador: `npm.cmd audit --json` informó 0 vulnerabilidades (incluidas dependencias de desarrollo) en este momento; no garantiza ausencia de vulnerabilidades futuras ni sustituye auditoría de Maven.

Publicado en `origin/feat/deterministic-ui`: `add905c` — `feat(ui): add deterministic incident analysis workspace`. Autor y committer personales `jacksonos`; autenticación de push verificada como `jacksonos`, sin cambiar la cuenta activa global. `git diff --cached --check` sin errores tras quitar líneas vacías finales del CSS (sin cambio funcional). No se publicaron código con pruebas fallidas ni artefactos de build/pruebas. No se hizo merge a main ni se abrió PR; la rama queda disponible para revisión.

## Refinamiento visual de fase 3
Completado el 2026-11-04 a petición del usuario con `design-taste-frontend`. Se aplicaron solo principios pertinentes a una herramienta de análisis, no patrones de landing ni imágenes/marketing decorativos. Variación 3/10, movimiento 2/10, densidad 6/10. Sin dependencias nuevas ni recursos externos.

Cambios:
- Conservados marca, aviso de privacidad, etiquetas y orden de campos, backend, evidencia y descarga completa.
- CSS consolidado con variables semánticas; paleta azul/neutra existente y tema automático según `prefers-color-scheme`, coherente en todas las superficies.
- Resumen con jerarquía numérica, fechas UTC legibles en móvil, estado inicial accionable, controles de al menos 44 px y foco visible.
- Cronología y detalle relacionados lateralmente en escritorio y apilados en móvil; texto largo contenido sin overflow global. Detalle con `:has()` en navegadores modernos; no se verificaron navegadores antiguos.
- Se preservan los nueve E2E y se añaden dos casos claro/oscuro: fondos computados distintos, foco con estilo/anchura/contraste, muestras de contraste de CTA/etiqueta, estados de carga/error/advertencia/sin coincidencias, detalle por teclado y descarga idéntica.

Orquestación ejecutada: Luna low auditó archivos; Terra medium implementó; Sol medium revisó y pidió cerrar falsos positivos de temas/foco, corregidos sin defectos funcionales nuevos confirmados. Orquestador inspeccionó capturas reales claro/oscuro/móvil y ajustó la distribución de fechas en pantallas pequeñas.

Verificación independiente de Luna low:
- `npm.cmd run build`: exit code 0, 88 módulos transformados.
- `npm.cmd run test:e2e -- --config playwright.verify.config.ts`: 11 aprobadas, 0 fallidas, exit code 0. Se repitieron build y 11 E2E tras el último ajuste CSS móvil, también correctos.
- `.\mvnw.cmd -B -ntp -f backend/pom.xml clean verify`: 48 pruebas, 0 fallos/errores/omitidas, exit code 0. Backend no cambió después de esa verificación.

El puerto 5173 tenía un Vite existente del usuario; no se detuvo. La primera ejecución de Terra reutilizó ese servicio con configuración temporal; no se usó como evidencia final. Las ejecuciones finales usaron instancias propias en frontend 5174 y backend 8080, con `reuseExistingServer:false`. La configuración temporal solo cambiaba el puerto frontend/baseURL respecto a la normal; se eliminó antes del commit. Para repetir normalmente: puertos 5173/8080 libres, JAR construido y `npm.cmd run test:e2e` en frontend.

Capturas reales ignoradas en `frontend/test-results/`: `app-tema-light-evidencia-larga-y-foco/light-desktop.png`, `app-tema-dark-evidencia-larga-y-foco/dark-desktop.png`, `app-móvil-y-navegación-por-teclado/mobile.png`. Se inspeccionó el ajuste final móvil y ambos temas. Muestras de contraste y tests no sustituyen auditoría completa; no se ejecutó Lighthouse ni se midieron Core Web Vitals, no hubo pruebas en teléfono real/Safari/Firefox. No se afirma cumplimiento total de la checklist de marketing, que no aplica a esta superficie de producto.

## Fase 4
Completada y verificada el 2026-11-04 tras aprobación del frontend. Rama `feat/optional-ai-insights` basada en `d542b33`, confirmado sincronizado con `origin/feat/deterministic-ui` antes de empezar. No merge a main ni despliegue.

Orquestación ejecutada: Sol high definió contrato y revisó seguridad; Terra medium implementó por unidades (transporte, validación, endpoints, interfaz); Luna low verificó independientemente. El primer encargo amplio no produjo código y solo verificó baseline de 48 tests; se dividió en unidades acotadas sin cambiar reparto ni esfuerzo.

Entregado:
- `GET /api/insights/config` devuelve solo disponibilidad sin llamadas externas ni secretos. IA desactivada por defecto; variables servidor `IR_AI_ENABLED`, `IR_AI_API_KEY`, `IR_AI_MODEL`, `IR_AI_ENDPOINT`. Configuración inválida no aborta el arranque; endpoint HTTPS en producción. Excepción HTTP loopback solo en constructor interno de pruebas.
- `POST /api/insights` requiere consentimiento verdadero y selección normalizada: hasta 100 eventos, seis campos permitidos, IDs/líneas únicos coherentes, orden cronológico, UTC, tipos y Unicode válidos. Sin rawLine/timestampOriginal. El servidor stateless valida forma y coherencia, no autenticidad del origen.
- Selección acotada a 65 536 bytes de la cadena JSON compacta codificada en UTF-8; envelope HTTP separado de 81 920 bytes, limitado durante lectura incluso chunked. No truncar mensajes ni rebajar silenciosamente la selección.
- Transporte Java estándar sin nuevas dependencias: conexión hasta 3 s, plazo total de 15 s incluida lectura, respuesta hasta 128 KiB mientras llega, sin redirects ni reintentos. Cancelación observada en servidor fake tanto por exceso chunked como timeout durante goteo.
- Esquema de salida acotado y estricto; toda hipótesis con referencias vacías, duplicadas o inexistentes se descarta entera y se advierte. Referencias existentes no prueban causalidad ni exactitud. Errores seguros 400/413/503/502/504; sin cuerpo externo ni claves en mensajes.
- UI con disponibilidad, selección explícita de primeros 100 eventos del conjunto completo independiente de filtros, explicación de campos/cobertura/retención y consentimiento desmarcado. Sin envío automático; excesos bloquean solicitud sin alterar hechos.
- Cancelación de solicitudes obsoletas y reinicio de consentimiento al cambiar entrada/análisis. Fallos conservan exactamente la exportación determinista. Éxito muestra hipótesis/preguntas y citas con línea, modo asistido y anexo Markdown escapado; vista previa y descarga usan el mismo documento.
- `.env.example` contiene valores vacíos y flag false, no se carga automáticamente. Configuración, límites y privacidad documentados en README, frontend/README y ADR 0002. Sin API real ni credenciales reales.

Revisión y correcciones cerradas:
- Clave sintética CR/LF deshabilita disponibilidad y devuelve 503 sin llamada; límites HTTP de 100/101 eventos cubiertos.
- Se alinearon vista previa/descarga asistidas e indicador global de modo. Se añadieron casos config pendiente/fallida/deshabilitada, frontera exacta de selección, cancelación IA y fallback determinista.
- Sol detectó discrepancia para emojis: Jackson al escribir byte[] podía contar pares surrogate como escapes, a diferencia del navegador/envío String. Se confirmó RED con 6 000 emojis y se corrigió para medir la misma cadena JSON UTF-8 del envío. Regresiones HTTP: 6 000 emojis, pares escapados, 65 536 bytes aceptados, 65 540 rechazados sin llamada adicional y límite independiente del envelope. Revisión focal final: P2 resuelto, sin nuevos defectos confirmados.
- Hubo fallos iniciales de pruebas por fecha inválida no convertida a error seguro y selectores E2E ambiguos al añadir textos asistidos, corregidos y seguidos de ejecuciones completas aprobadas. No se cambió modelo, esfuerzo ni reparto.

Verificación final independiente de Luna low:

```text
raíz: .\mvnw.cmd -B -ntp -f backend/pom.xml clean verify
81 pruebas, 0 fallos, 0 errores, 0 omitidas; BUILD SUCCESS, exit code 0
frontend: npm.cmd run build
exit code 0; 88 módulos transformados
frontend: npm.cmd run test:e2e -- --config playwright.verify.config.ts
24 aprobadas, 0 fallidas, 0 omitidas; exit code 0
```

E2E con instancias propias en 5174/8080 y `reuseExistingServer:false`; el proceso existente del usuario en 5173 no se detuvo ni reutilizó. Configuración de pruebas fuerza IA desactivada y credenciales vacías; casos de UI asistida usan mocks, backend usa proveedor HTTP local simulado. Config temporal solo cambió puerto/baseURL y se eliminó antes del commit; comando normal reproducible con 5173/8080 libres: `npm.cmd run test:e2e`. JAR usado por E2E construido por `clean verify` final.

Orquestador inspeccionó captura renderizada `frontend/test-results/insights-consentimiento-ex-692f7--y-descarga-asistida-segura/ia-asistida.png`: muestra salida sintética de prueba, no resultado real de un proveedor. Artefacto ignorado, no se versiona. No se probó un proveedor real ni se garantiza eliminación de prompt injection o retención externa. La aplicación sigue siendo local de confianza, no está preparada para exposición pública sin revisión adicional.

## Límites y siguiente paso
- Detenido tras fase 4. Siguiente: fase 5 (contenedor, documentación y demo), solo tras nueva autorización. No hay despliegue.
- Sin medidor de cobertura de líneas/ramas; la cobertura declarada es de requisitos, no un porcentaje de código medido.
- El wrapper de Windows fue ejecutado; el script Unix no se probó en Linux/macOS.
- Advertencia de Mockito/Byte Buddy por carga dinámica de agente en Java 21; no hay errores de prueba. Revisar configuración del agente si se actualiza el JDK.
- El JAR se ejecuta como proceso independiente en las E2E de fase 3; se revisaron capturas reales de la UI, no hardware móvil real ni otros navegadores fuera de Chromium.
- Sin auditoría de dependencias Maven ni captura automatizada de logs; npm audit sí se ejecutó según fase 3. El código no registra explícitamente entradas. Spring Boot actual: 3.5.16. No exponer fuera del entorno local de confianza sin evaluar seguridad e infraestructura.

## Repositorio
Repositorio: https://github.com/jacksonos/incident-relay.git. Remoto originalmente vacío; Git local inicializado en `main` y `origin` vinculado. Autor local: `jacksonos <92521092+jacksonos@users.noreply.github.com>`, confirmado por el usuario. Cuenta corporativa no usada para publicar.

Publicado en `origin/main`: `a277716` — `feat(api): bootstrap deterministic incident analysis MVP` (fases 0–2). Push autenticado como `jacksonos`, sin cambiar la cuenta activa global de GitHub CLI. Rama local y remota sincronizadas al terminar la publicación. Commits futuros: Conventional Commits; no hacer force-push ni incluir artefactos, secretos o logs reales.

Fase 3 publicada en la rama `feat/deterministic-ui` con commit `add905c`; este handoff registra el resultado en un commit documental posterior, sin alterar los resultados de verificación.

Verificación previa al commit: Luna low volvió a ejecutar `clean verify`: 48 pruebas, 0 fallos/errores/omitidas, exit code 0. Los archivos locales de la extensión `.github/modernize/java-upgrade/` están ignorados por su propio `.gitignore` y se dejaron intactos.
