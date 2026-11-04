# Verificación de fase 1

Ejecutar desde la raíz: `.\mvnw.cmd -B -ntp -f backend/pom.xml clean verify` en Windows, o `sh mvnw -B -ntp -f backend/pom.xml clean verify` en Linux/macOS.

## Cobertura de requisitos de sección 9, puntos 1–4

| Punto | Requisitos | Pruebas |
| --- | --- | --- |
| 1 | Niveles, mensaje y evidencia originales, offsets, UTF-8 | `parsesEverySupportedLevelAndPreservesEvidence` (4 casos), `validatesBytesAndUnicodeStrictly` |
| 1 | LF/CRLF y numeración física | `supportsLineEndingsAndCountsPhysicalBlankLines` (2 casos) |
| 1 | Fechas imposibles, formato incompleto, nivel/servicio/mensaje incorrecto y trazas multilínea | `rejectsMalformedLinesWithoutInventingEventsOrDates` (17 casos) |
| 2 | Orden por instante real, empates con offsets, IDs únicos de duplicados y repetibilidad | `ordersByInstantThenSourceLineEvenWithOffsetsAndDuplicates`, `incidentFixtureHasExactCountsStableTiesAndThreeRepeatedGroups` |
| 3 | Resumen, niveles con cero, blancos, rechazos, grupos, conservación de IDs y números, Locale.ROOT | `incidentFixtureHasExactCountsStableTiesAndThreeRepeatedGroups`, `normalFixtureHasNoErrorsOrWarningsAndCollectionsAreImmutable`, `normalizesOnlyWhitespaceAndCaseWithoutDependingOnSystemLocale` |
| 3 | Entrada vacía/nula, sin eventos válidos, límites exactos y exceso de bytes/líneas, tamaño multibyte, blancos fuera del límite de no vacías | `rejectsMissingOrBlankInput` (4 casos), `rejectsAllInvalidInputWithoutExposingItsContents`, `validatesBytesAndUnicodeStrictly`, `acceptsExactlyByteLimitAndRejectsOneByteMoreForBytesAndText`, `countsUtf8BytesNotJavaCharacters`, `acceptsExactlyLineLimitAndRejectsOneMoreIncludingInvalidLines` |
| 4 | Reporte completo, UTC, modo, conteos, orden, IDs/líneas, evidencia, advertencias y determinismo | `exportsAllFactsWithOriginEvidenceUtcAndPartialDataWarning` |
| 4 | Causa e impacto no inventados, hipótesis/acciones pendientes, limitaciones, estados normales | `doesNotInventCauseImpactOrActionsAlreadyTaken` |
| 4 | Contenido no confiable escapado y rechazo de análisis vacío | `keepsLogContentAsTextRatherThanActiveHtmlOrMarkdown`, `rejectsMissingOrEmptyAnalysis` |

`IncidentReplayApplicationTest.contextLoads` también comprueba la integración de parser y generador como beans de Spring, sin servidor HTTP.

Se cubren todos los requisitos enumerados de los puntos 1–4. Esto no equivale a afirmar 100 % de cobertura de líneas o ramas: no se ha instalado un medidor de cobertura. No hay pruebas de API, UI ni proveedor IA porque sus fases no están autorizadas.

## Resultados
Consultar `HANDOFF.md` para el último comando y resultado real. Maven guarda informes XML y texto en `backend/target/surefire-reports/`; `target/` no se versiona.
