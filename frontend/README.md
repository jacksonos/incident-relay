# Frontend

React + Vite + Axios; requiere Node.js 20.19+ o 22.12+. Desde este directorio:

```sh
npm ci
npm run dev
npm run build
npx playwright install chromium
npm run test:e2e
```

En Windows usar `npm.cmd` y `npx.cmd` si PowerShell bloquea scripts. Primero generar el JAR con `mvnw.cmd -f backend/pom.xml package` desde la raíz. Para uso interactivo iniciar el JAR por separado; Vite escucha en 127.0.0.1:5173 y redirige `/api` a 127.0.0.1:8080. Las pruebas arrancan ambos servidores en esos puertos y fallan si ya están ocupados. Los ejemplos importan directamente los fixtures de la raíz. No hay persistencia.

El análisis inicial es determinista. La IA es opcional, está desactivada por defecto y requiere configuración del servidor y consentimiento por solicitud. Se transmiten hasta los primeros 100 eventos completos, sin truncar, y el proveedor puede retenerlos. La previsualización coincide con el Markdown descargado; las hipótesis no demuestran causas. Variables y política en el [README principal](../README.md); `.env.example` no se carga automáticamente. La integración utiliza Chat Completions compatible con OpenAI y no se ha probado con un proveedor real.

Las E2E usan Chromium. El flujo principal analiza la fixture mediante la API real y comprueba la descarga completa. Los casos de frontera del texto aíslan el validador del navegador con respuestas simuladas; las dos inserciones de 10 000 líneas usan setter nativo y evento `input` porque `fill` agotó tiempo también en un textarea sin React. No equivalen a verificar un pegado nativo masivo en hardware real. Capturas e informes se generan en `test-results/` y no se versionan.

La interfaz adapta el tema claro/oscuro a la preferencia del sistema, sin guardar preferencias ni usar recursos externos. En escritorio, el detalle seleccionado aparece junto a la cronología; en móvil se apila debajo. Las pruebas incluyen ambos temas, muestras de contraste y foco, estados de carga/error/sin coincidencias, descarga íntegra y ausencia de overflow a 320/375/768/1280 px. Son comprobaciones parciales, no una certificación completa de accesibilidad ni de rendimiento.
