# Fixtures

Solo datos ficticios. Se usan directamente como recursos de pruebas para evitar divergencia entre demo y validación.

- `incident.log`: 11 eventos válidos, una línea vacía y una traza inválida (línea 7). Entrada desordenada, cuatro niveles, UTC y offsets, empate entre líneas 6 y 8; tres grupos de dos mensajes repetidos. Secuencia de latencia, errores de conexión y 503 sin demostrar causalidad.
- `normal.log`: tres eventos sin WARN/ERROR, rechazos ni repetidos.

Un salto de línea al final termina la última línea; no cuenta como otra línea vacía. Los mensajes repetidos colapsan espacios y comparan sin distinguir mayúsculas, sin sustituir números/IDs ni separar por servicio.
