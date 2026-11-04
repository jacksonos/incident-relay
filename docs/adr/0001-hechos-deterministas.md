# ADR 0001 — Hechos deterministas e IA opcional

Estado: aceptado para el MVP local.

## Contexto
Incident Replay reconstruye un incidente desde logs exportados. Debe funcionar sin claves, sin persistencia y sin presentar inferencias como evidencia.

## Decisión
- Monolito Spring Boot con Java 21; frontend React + Vite + Axios en una fase posterior.
- Parser y reporte en memoria, sin servicios externos. Una línea válida representa un evento; línea original y número de origen se conservan.
- ISO 8601 con offset explícito, niveles DEBUG/INFO/WARN/ERROR, servicio sin espacios y mensaje no vacío. UTC para ordenar y mostrar; desempate por línea de origen.
- UTF-8 estricto, máximo 1 MiB y 10 000 líneas no vacías. LF/CRLF; líneas multilínea no reconocidas se reportan como inválidas.
- Repetidos: agrupar por mensaje, colapsar espacios y comparar sin distinguir mayúsculas con Locale.ROOT. No eliminar números ni IDs; el servicio no participa en la clave.
- IDs `event-<línea>` estables dentro de un análisis, incluso con duplicados.
- Markdown generado con el conjunto completo; hechos separados de impacto, hipótesis y acciones pendientes. Escapar contenido no confiable al exportar.
- IA opcional posterior: acción explícita, claves solo en servidor, salida validada contra eventos existentes. Correlación no implica causalidad.

## Consecuencias
No hay recuperación tras recargar ni soporte de formatos arbitrarios. El rango temporal solo describe los logs disponibles, no la duración confirmada del incidente. La privacidad depende también de la infraestructura y del proveedor elegido; no se promete anonimización.
