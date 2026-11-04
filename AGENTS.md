# Incident Replay

## Alcance y reglas
- Leer `PLAN.md`, `HANDOFF.md` y `docs/adr/0001-hechos-deterministas.md` antes de cambiar código.
- Trabajar solo en la fase autorizada. No avanzar sin ejecutar sus pruebas y actualizar `HANDOFF.md` con resultados reales.
- Java 21, Spring Boot y Maven en `backend/`; React + Vite + Axios en `frontend/` cuando se autorice su fase.
- Sin base de datos, autenticación ni servicios externos por defecto. No registrar logs de entrada ni guardar secretos.
- Hechos y conteos deterministas. Las hipótesis no son causas demostradas. Exportar siempre el análisis completo.
- Mantener UTC, líneas originales, límites de 1 MiB UTF-8 y 10 000 líneas no vacías.
- Probar cambios con `sh mvnw -f backend/pom.xml test` (Windows: `.\mvnw.cmd -f backend/pom.xml test`).
- No afirmar pruebas, integración, interfaz ni despliegues que no se hayan ejecutado.
