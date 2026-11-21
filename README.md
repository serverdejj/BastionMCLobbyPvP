# BastionMCLobbyPvP 1.0.0

Plugin para **BASTION MC** | Purpur 1.21.11 | Java 21.

## Funcionamiento
- `/lobbypvp give <jugador>` entrega una espada especial en la casilla configurada (si está libre). El plugin NO reemplaza automáticamente los ítems de tu lobby existente. Para usar esa misma espada visual en lugar de la del selector, añade la espada de este plugin a la barra del lobby con tu sistema de ítems.
- Con la espada en la mano, clic derecho activa armadura Netherite temporal y habilita PvP únicamente contra jugadores que también hayan activado el modo.
- Cambiar de casilla, desconectarse, salir de `spawn` o cambiar de modo de juego desactiva y restaura armadura previa.
- Al recibir daño letal con el modo activado, se cancela la muerte, se recupera la salud y se desactiva el PvP.
- Las armaduras anteriores se guardan en `armor-backups.yml` para restaurarlas incluso tras una caída del servidor. No borres ese archivo.
- El ítem especial está marcado con PersistentDataContainer y no puede soltarse ni moverse por clics normales.

## Instalación
Compila con GitHub Actions (`.github/workflows/build.yml`), descarga el artifact JAR e instálalo **únicamente en el servidor de lobby**, no en Velocity. Prueba con dos usuarios sin OP antes de producción.

## Comandos
`/lobbypvp give Jugador` (OP), `/lobbypvp reload` (OP), `/lobbypvp off` (todos).

## Notas
- Si otro plugin cancela el daño en `spawn` (WorldGuard, plugin de lobby), debes autorizar PvP en la zona o integrar la excepción; este plugin no debe deshacer una cancelación de protección ajena.
- Si tu lobby usa un plugin que repone o bloquea la espada existente, desactiva esa restricción solo para el ítem de BastionMCLobbyPvP.
- Evita poner armadura valiosa real en el lobby; realiza copia de seguridad antes de instalar.
- Colores `&` y `&#RRGGBB` para nombre, lore y mensajes; permite degradados construidos carácter por carácter.
