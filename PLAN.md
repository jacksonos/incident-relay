# Plan de entrega — seis fases

Cada fase termina ejecutando sus comprobaciones y registrando comando, resultado y pendientes en `HANDOFF.md`. Un fallo bloquea la siguiente fase. Fases 0–2 completadas y verificadas; fases 3–5 requieren nueva autorización.

## Fase 0 — Fundación
- Monorepo `backend/`, `frontend/`, `fixtures/`, `docs/`.
- Maven wrapper, Spring Boot con Java 21, instrucciones, ADR y este plan.
- Comprobación: arranque del contexto Spring mediante prueba automatizada y validación de estructura.

## Fase 1 — Núcleo determinista
- Dos fixtures ficticios; parser UTF-8 estricto, validación, orden estable, resumen y grupos.
- Postmortem Markdown sin IA con evidencia, advertencias y campos desconocidos.
- Pruebas de todos los requisitos de los puntos 1–4 de la sección 9 del contexto: parsing, orden, conteos/límites y reporte.

## Fase 2 — API
- `/api/analyze`, `/api/health`, validación HTTP, errores seguros 400/413.
- Pruebas HTTP de contratos y límites; preservar pruebas de fases anteriores.

## Fase 3 — Interfaz sin IA
- React + Vite + Axios, entrada explícita, resumen, filtros, detalle y descarga.
- Estados accesibles y responsive. Verificar flujo completo con pruebas reproducibles.

## Fase 4 — IA opcional
- Adaptador de servidor configurable, consentimiento explícito, contenido acotado, timeout y validación de referencias.
- Proveedor simulado: falta de clave, fallos, timeout, JSON incorrecto y referencias inexistentes.

## Fase 5 — Entrega
- Contenedor, documentación operativa y privacidad, diagrama, guion de demo y descripción de portafolio.
- Suite completa, build frontend/backend y revisión del flujo; informar límites sin desplegar públicamente.
