# ADR 0002 — Análisis asistido opcional

Estado: decisión de implementación de fase 4; sin prueba con proveedor real.

La IA está desactivada por defecto. Habilitarla requiere configuración del servidor y consentimiento explícito por solicitud. La configuración y la clave nunca se devuelven al navegador. Se acepta únicamente un endpoint compatible con OpenAI Chat Completions, HTTPS sin credenciales, consulta ni fragmento; la excepción HTTP loopback existe solo en el constructor interno de pruebas. `.env.example` es solo referencia y no se carga automáticamente.

El cliente selecciona hasta 100 eventos cronológicos y envía únicamente ID, línea, timestamp UTC, nivel, servicio y mensaje. El servidor valida la selección pero, al ser stateless, no puede acreditar que proceda de `/api/analyze`. La cadena JSON compacta del array, codificada en UTF-8, admite 65 536 bytes y el envelope 81 920 bytes; no se truncan mensajes. El transporte tiene conexión de hasta 3 segundos, plazo total de 15 segundos y respuesta limitada a 128 KiB, sin redirecciones ni reintentos.

El proveedor recibe los eventos seleccionados como datos no confiables, no como instrucciones. La respuesta estructurada se valida y se descarta toda hipótesis con referencias vacías, inexistentes o duplicadas. Una referencia existente no demuestra apoyo causal ni exactitud. Las hipótesis y verificaciones requieren revisión humana; el análisis y Markdown deterministas no dependen de IA.

El servidor no guarda deliberadamente los datos ni registra entradas. El proveedor o la infraestructura pueden retenerlos; no se garantiza privacidad ni eliminación de inyección de instrucciones. No introducir secretos ni datos personales. No se probó un proveedor real ni se afirma auditoría o despliegue.
