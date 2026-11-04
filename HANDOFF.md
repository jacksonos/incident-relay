# Handoff

## Autorización
Fases 0–3 completadas. El usuario autorizó publicar lo pendiente con la cuenta personal `jacksonos`, continuar fase 3 y resolver su bloqueo manteniendo el reparto. Fases 4–5 bloqueadas hasta nueva autorización.

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

## Límites y siguiente paso
- Detenido tras fase 3. Siguiente: fase 4 (IA opcional), solo tras nueva autorización. No hay contenedor ni despliegue.
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
