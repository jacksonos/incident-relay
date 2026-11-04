# Frontend

React + Vite + Axios; requiere Node.js 20.19+ o 22.12+. Desde este directorio:

```sh
npm ci
npm run dev
npm run build
npx playwright install chromium
npm run test:e2e
```

En Windows usar `npm.cmd` y `npx.cmd` si PowerShell bloquea scripts. Primero generar el JAR con `mvnw.cmd -f backend/pom.xml package` desde la raíz. Para uso interactivo iniciar el JAR por separado; Vite escucha en 127.0.0.1:5173 y redirige `/api` a 127.0.0.1:8080. Las pruebas arrancan ambos servidores en esos puertos y fallan si ya están ocupados. Los ejemplos importan directamente los fixtures de la raíz. No hay persistencia ni IA.

Las E2E usan Chromium. El flujo principal analiza la fixture mediante la API real y comprueba la descarga completa. Los casos de frontera del texto aíslan el validador del navegador con respuestas simuladas; las dos inserciones de 10 000 líneas usan setter nativo y evento `input` porque `fill` agotó tiempo también en un textarea sin React. No equivalen a verificar un pegado nativo masivo en hardware real. Capturas e informes se generan en `test-results/` y no se versionan.
