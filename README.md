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

## Integración con DeluxeHub (1.0.1)
- En DeluxeHub el item Pvp es DIAMOND_SWORD en slot 4 y conserva el nombre original con HEX.
- En DeluxeHub `custom_join_items.items.Pvp.actions: []` para que DeluxeHub no abra su GUI.
- `world_settings.disable_player_pvp: false` para que BastionMCLobbyPvP permita daño solo entre jugadores activos.
- La sección `deluxehub` en la config del plugin valida material, slot activo y nombre formateado exacto.
- No se entrega segunda espada ni se requiere el PDC del item, pues DeluxeHub lo crea.
- Si se cambia el nombre de la espada en DeluxeHub, actualizar `deluxehub.display-name` al mismo valor.
- **Prueba en copia:** dos jugadores sin OP, cambio de casilla, muerte protegida, y otros plugins de PvP/WorldGuard que puedan cancelar daño.


## Actualización 1.0.2
- Mundo predeterminado: `void` (el lobby configurado en BASTION MC).
- Espada DeluxeHub irrompible mientras está en uso y cuando se reconoce al entrar.
- Al activar, guarda en `armor-backups.yml` el inventario completo, mano secundaria y armadura, luego oculta los objetos y deja únicamente la espada. Restaura todo al desactivar, desconectar o reiniciar. **No borres `armor-backups.yml`**.
- Al cambiar de slot o hacer clic derecho otra vez comienza una cuenta de 5 segundos; permanece vulnerable al PvP durante la cuenta. TITLE rojo y número amarillo son configurables.
- No actualizar este plugin mediante `/reload` global de Bukkit. Prueba en servidor de staging con inventario de prueba.
- No cambies `disable_player_pvp: false` de DeluxeHub sin el plugin de protección activo.

## Actualización 1.0.3: TITLE de activación
- Al hacer clic derecho en la espada válida comienza un contador TITLE **Activando PVP** verde claro con **3, 2, 1** amarillo.
- El modo PvP y la armadura temporal solo se activan al finalizar los 3 segundos; se evita alterar el inventario durante la cuenta.
- Si cambias de slot, mundo, modo de juego, te desconectas o dejas de sostener la espada, la activación se cancela.
- El contador de desactivación de 5 segundos se mantiene.
- La sección nueva `activation-countdown` en `config.yml` es configurable; **agrégala manualmente** al archivo existente en Holy Hosting (los archivos existentes no se sobrescriben automáticamente).
