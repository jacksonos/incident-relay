# Handoff

## Autorización
Fases 0–2 completadas. El usuario aprobó ejecutar únicamente fase 2 (API HTTP) en esta ejecución. Fases 3–5 bloqueadas hasta nueva autorización.

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

## Límites y siguiente paso
- Detenido tras fase 2. Siguiente: fase 3, interfaz sin IA, únicamente con autorización. No hay interfaz, IA, contenedor ni despliegue.
- Sin medidor de cobertura de líneas/ramas; la cobertura declarada es de requisitos, no un porcentaje de código medido.
- El wrapper de Windows fue ejecutado; el script Unix no se probó en Linux/macOS.
- Advertencia de Mockito/Byte Buddy por carga dinámica de agente en Java 21; no hay errores de prueba. Revisar configuración del agente si se actualiza el JDK.
- El JAR compiló y se probó la API con servidor embebido, pero no se realizó una ejecución independiente del JAR ni una revisión visual (aún no hay UI).
- No se ejecutó auditoría de vulnerabilidades de dependencias ni captura automatizada de logs; el código no registra explícitamente entradas. Spring Boot actual: 3.5.16. No exponer fuera del entorno local de confianza sin evaluar seguridad e infraestructura.

## Repositorio
Repositorio: https://github.com/jacksonos/incident-relay.git. Remoto vacío comprobado e inicializado Git local en `main`; `origin` vinculado. Autor local: `jacksonos <92521092+jacksonos@users.noreply.github.com>`, confirmado por el usuario. Cuenta corporativa no usada para publicar. Publicación inicial de fases 0–2 solicitada; registrar resultado tras el push.

Verificación previa al commit: Luna low volvió a ejecutar `clean verify`: 48 pruebas, 0 fallos/errores/omitidas, exit code 0. Los archivos locales de la extensión `.github/modernize/java-upgrade/` están ignorados por su propio `.gitignore` y se dejaron intactos.
