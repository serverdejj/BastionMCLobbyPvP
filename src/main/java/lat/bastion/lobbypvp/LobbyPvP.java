package lat.bastion.lobbypvp;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LobbyPvP extends JavaPlugin implements Listener {
    private final Set<UUID> active = new HashSet<>();
    private NamespacedKey swordKey;
    private final Map<UUID, org.bukkit.scheduler.BukkitTask> disableTimers = new HashMap<>();
    private final Map<UUID, org.bukkit.scheduler.BukkitTask> activationTimers = new HashMap<>();
    private File snapshotsFile;
    private YamlConfiguration snapshots;
    private File pvpSpawnFile;
    private YamlConfiguration pvpSpawn;
    private final Set<UUID> pendingRespawns = new HashSet<>();
    private static final Pattern HEX = Pattern.compile("&#([a-fA-F0-9]{6})");

    @Override public void onEnable() {
        saveDefaultConfig();
        swordKey = new NamespacedKey(this, "pvp_sword");
        snapshotsFile = new File(getDataFolder(), "armor-backups.yml");
        snapshots = YamlConfiguration.loadConfiguration(snapshotsFile);
        pvpSpawnFile = new File(getDataFolder(), "pvp-spawn.yml");
        pvpSpawn = YamlConfiguration.loadConfiguration(pvpSpawnFile);
        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("lobbypvp")).setExecutor((sender, command, label, args) -> {
            if (args.length == 1 && args[0].equalsIgnoreCase("setspawn")) {
                if (!sender.hasPermission("bastionlobbypvp.admin")) { sender.sendMessage(message("no-permission")); return true; }
                if (!(sender instanceof Player player)) { sender.sendMessage("§cUsa /pvp setspawn dentro del lobby."); return true; }
                if (!allowed(player)) { sender.sendMessage(message("only-world")); return true; }
                Location location = player.getLocation();
                pvpSpawn.set("spawn", location);
                try {
                    pvpSpawn.save(pvpSpawnFile);
                    sender.sendMessage(message("spawn-set"));
                } catch (IOException ex) {
                    getLogger().severe("No se pudo guardar pvp-spawn.yml: " + ex.getMessage());
                    sender.sendMessage(message("spawn-save-failed"));
                }
                return true;
            }
            if (args.length == 1 && args[0].equalsIgnoreCase("off") && sender instanceof Player player) {
                if (active.contains(player.getUniqueId())) startCountdown(player);
                return true;
            }
            if (!sender.hasPermission("bastionlobbypvp.admin")) { sender.sendMessage(message("no-permission")); return true; }
            if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
                for (Player p : Bukkit.getOnlinePlayers()) { cancelActivation(p, true); deactivate(p, false); }
                reloadConfig(); sender.sendMessage(message("reloaded")); return true;
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) { sender.sendMessage("§cJugador desconectado."); return true; }
                if (giveSword(target)) sender.sendMessage(message("given").replace("%player%", target.getName()));
                else sender.sendMessage(message("no-room"));
                return true;
            }
            sender.sendMessage(message("usage")); return true;
        });
        Objects.requireNonNull(getCommand("lobbypvp")).setTabCompleter((sender, command, label, args) -> {
            if (args.length == 1) return (sender.hasPermission("bastionlobbypvp.admin") ? List.of("give", "reload", "off", "setspawn") : List.of("off")).stream().filter(s -> s.startsWith(args[0].toLowerCase())).toList();
            if (args.length == 2 && args[0].equalsIgnoreCase("give") && sender.hasPermission("bastionlobbypvp.admin")) return Bukkit.getOnlinePlayers().stream().map(Player::getName).filter(s -> s.toLowerCase().startsWith(args[1].toLowerCase())).toList();
            return List.of();
        });
        for (Player p : Bukkit.getOnlinePlayers()) { recover(p); makeDeluxeSwordUnbreakable(p); }
    }

    @Override public void onDisable() {
        for (org.bukkit.scheduler.BukkitTask task : activationTimers.values()) task.cancel();
        activationTimers.clear();
        for (org.bukkit.scheduler.BukkitTask task : disableTimers.values()) task.cancel();
        disableTimers.clear();
        for (Player p : Bukkit.getOnlinePlayers()) deactivate(p, false);
        storeSnapshots();
    }

    private String color(String raw) {
        Matcher m = HEX.matcher(raw == null ? "" : raw);
        StringBuffer result = new StringBuffer();
        while (m.find()) {
            StringBuilder hex = new StringBuilder("§x");
            for (char c : m.group(1).toCharArray()) hex.append('§').append(c);
            m.appendReplacement(result, Matcher.quoteReplacement(hex.toString()));
        }
        m.appendTail(result);
        return ChatColor.translateAlternateColorCodes('&', result.toString());
    }
    private String message(String path) { return color(getConfig().getString("messages.prefix", "") + getConfig().getString("messages." + path, path)); }
    private boolean allowed(Player p) { return getConfig().getStringList("worlds").stream().anyMatch(w -> w.equalsIgnoreCase(p.getWorld().getName())); }
    private boolean pvpSword(ItemStack item) {
        return item != null && item.hasItemMeta() && item.getItemMeta().getPersistentDataContainer().has(swordKey, PersistentDataType.BYTE);
    }
    // Reconocer la espada de DeluxeHub sin depender de un PDC que DeluxeHub no añade.
    // Material, slot y nombre formateado deben coincidir para evitar falsos positivos.
    private boolean deluxeHubSword(Player p, ItemStack item) {
        if (!getConfig().getBoolean("deluxehub.enabled", true) || item == null || item.getType().isAir() || !item.hasItemMeta()) return false;
        Material material = Material.matchMaterial(getConfig().getString("deluxehub.material", "DIAMOND_SWORD"));
        if (material == null || item.getType() != material) return false;
        if (p.getInventory().getHeldItemSlot() != getConfig().getInt("deluxehub.slot", 4)) return false;
        String expected = color(getConfig().getString("deluxehub.display-name", ""));
        return !expected.isEmpty() && item.getItemMeta().hasDisplayName() && expected.equals(item.getItemMeta().getDisplayName());
    }
    private boolean activationSword(Player p, ItemStack item) {
        return pvpSword(item) || deluxeHubSword(p, item);
    }
    private ItemStack newSword() {
        Material mat = Material.matchMaterial(getConfig().getString("sword.material", "NETHERITE_SWORD"));
        ItemStack item = new ItemStack(mat == null ? Material.NETHERITE_SWORD : mat);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(color(getConfig().getString("sword.name", "&cPvP")));
        meta.setLore(getConfig().getStringList("sword.lore").stream().map(this::color).toList());
        meta.setUnbreakable(getConfig().getBoolean("sword.unbreakable", true));
        meta.getPersistentDataContainer().set(swordKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }
    private boolean giveSword(Player p) {
        for (ItemStack i : p.getInventory().getContents()) if (pvpSword(i)) return true;
        int slot = Math.max(0, Math.min(8, getConfig().getInt("sword.slot", 4)));
        ItemStack existing = p.getInventory().getItem(slot);
        if (existing != null && !existing.getType().isAir()) return false;
        p.getInventory().setItem(slot, newSword()); return true;
    }
    private boolean storeSnapshots() {
        try { snapshots.save(snapshotsFile); return true; }
        catch (IOException ex) { getLogger().severe("No se pudo guardar armor-backups.yml: " + ex.getMessage()); return false; }
    }
    // Guardar TODO antes de vaciar el inventario. Persistente incluso si el servidor se cierra inesperadamente.
    private boolean snapshot(Player p) {
        String key = p.getUniqueId().toString();
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < 4; i++) snapshots.set(key + ".slot" + i, inv.getArmorContents()[i]);
        ItemStack[] storage = inv.getStorageContents();
        // La espada DeluxeHub debe continuar siendo irrompible cuando se restaura el inventario.
        int swordSlot = Math.max(0, Math.min(8, getConfig().getInt("deluxehub.slot", 4)));
        if (swordSlot < storage.length && deluxeHubSword(p, storage[swordSlot])) {
            storage[swordSlot] = unbreakableCopy(storage[swordSlot]);
        }
        for (int i = 0; i < storage.length; i++) snapshots.set(key + ".inventory." + i, storage[i]);
        snapshots.set(key + ".inventory-size", storage.length);
        snapshots.set(key + ".offhand", inv.getItemInOffHand());
        snapshots.set(key + ".full", true);
        snapshots.set(key + ".stored", true);
        if (storeSnapshots()) return true;
        snapshots.set(key, null);
        return false;
    }
    private void restore(Player p) {
        String key = p.getUniqueId().toString();
        if (!snapshots.getBoolean(key + ".stored")) return;
        if (snapshots.getBoolean(key + ".full")) {
            int len = Math.min(p.getInventory().getStorageContents().length, snapshots.getInt(key + ".inventory-size", 36));
            ItemStack[] storage = new ItemStack[p.getInventory().getStorageContents().length];
            for (int i = 0; i < len; i++) storage[i] = snapshots.getItemStack(key + ".inventory." + i);
            p.getInventory().setStorageContents(storage);
            ItemStack offhand = snapshots.getItemStack(key + ".offhand");
            p.getInventory().setItemInOffHand(offhand);
        }
        ItemStack[] armor = new ItemStack[4];
        for (int i=0;i<4;i++) armor[i] = snapshots.getItemStack(key + ".slot" + i);
        p.getInventory().setArmorContents(armor);
        snapshots.set(key, null);
        storeSnapshots();
    }
    private void recover(Player p) {
        if (snapshots.getBoolean(p.getUniqueId() + ".stored")) {
            restore(p); p.sendMessage(message("recovered"));
        }
    }
    private ItemStack unbreakableCopy(ItemStack original) {
        if (original == null || original.getType().isAir()) return original;
        ItemStack copy = original.clone();
        ItemMeta meta = copy.getItemMeta();
        if (meta != null) { meta.setUnbreakable(true); copy.setItemMeta(meta); }
        return copy;
    }
    private void makeDeluxeSwordUnbreakable(Player p) {
        if (!allowed(p) || !getConfig().getBoolean("sword.unbreakable", true)) return;
        int slot = Math.max(0, Math.min(8, getConfig().getInt("deluxehub.slot", 4)));
        ItemStack item = p.getInventory().getItem(slot);
        if (deluxeHubSword(p, item)) p.getInventory().setItem(slot, unbreakableCopy(item));
    }
    private ItemStack armorPiece(String configPath, Material fallback) {
        Material mat = Material.matchMaterial(getConfig().getString("armor." + configPath, fallback.name()));
        ItemStack item = new ItemStack(mat == null ? fallback : mat);
        int protection = Math.max(0, Math.min(4, getConfig().getInt("armor.protection-level", 0)));
        if (protection > 0) item.addUnsafeEnchantment(Enchantment.PROTECTION, protection);
        return item;
    }
    // Cuenta previa: el PvP NO se activa ni reemplaza el inventario hasta terminar los 3 segundos.
    private void startActivation(Player p) {
        UUID id = p.getUniqueId();
        if (active.contains(id) || activationTimers.containsKey(id)) return;
        int seconds = Math.max(1, Math.min(30, getConfig().getInt("activation-countdown.seconds", 3)));
        final int[] remaining = {seconds};
        showActivation(p, remaining[0]);
        org.bukkit.scheduler.BukkitTask task = Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (!p.isOnline() || !allowed(p) || !p.hasPermission("bastionlobbypvp.use")
                    || !activationSword(p, p.getInventory().getItemInMainHand())) {
                cancelActivation(p, true);
                return;
            }
            remaining[0]--;
            if (remaining[0] <= 0) {
                cancelActivation(p, false);
                activate(p);
                return;
            }
            showActivation(p, remaining[0]);
        }, 20L, 20L);
        activationTimers.put(id, task);
    }
    private void showActivation(Player p, int remaining) {
        p.sendTitle(color(getConfig().getString("activation-countdown.title", "&a&lActivando PVP")),
            color(getConfig().getString("activation-countdown.subtitle", "&e%seconds%").replace("%seconds%", String.valueOf(remaining))),
            0, 25, 0);
    }
    private void cancelActivation(Player p, boolean clearTitle) {
        org.bukkit.scheduler.BukkitTask task = activationTimers.remove(p.getUniqueId());
        if (task != null) {
            task.cancel();
            if (clearTitle && p.isOnline()) p.resetTitle();
        }
    }
    private void activate(Player p) {
        if (active.contains(p.getUniqueId()) || !allowed(p) || !p.hasPermission("bastionlobbypvp.use")) return;
        if (!snapshot(p)) { p.sendMessage(message("backup-failed")); return; }
        ItemStack sword = p.getInventory().getItemInMainHand().clone();
        if (getConfig().getBoolean("sword.unbreakable", true)) sword = unbreakableCopy(sword);
        p.getInventory().setStorageContents(new ItemStack[p.getInventory().getStorageContents().length]);
        p.getInventory().setItemInOffHand(null);
        p.getInventory().setItem(p.getInventory().getHeldItemSlot(), sword);
        p.getInventory().setArmorContents(new ItemStack[]{
            armorPiece("boots", Material.NETHERITE_BOOTS), armorPiece("leggings", Material.NETHERITE_LEGGINGS),
            armorPiece("chestplate", Material.NETHERITE_CHESTPLATE), armorPiece("helmet", Material.NETHERITE_HELMET)
        });
        active.add(p.getUniqueId()); p.sendMessage(message("enabled"));
    }
    private void cancelCountdown(Player p) {
        org.bukkit.scheduler.BukkitTask task = disableTimers.remove(p.getUniqueId());
        if (task != null) task.cancel();
    }
    private void healFully(Player p) {
        if (!p.isOnline() || p.isDead()) return;
        var maxHealth = p.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealth != null) p.setHealth(maxHealth.getValue());
        p.setFireTicks(0);
    }
    private Location pvpRespawnLocation(Player player) {
        Location saved = pvpSpawn.getLocation("spawn");
        if (saved != null && saved.getWorld() != null && Bukkit.getWorld(saved.getWorld().getUID()) != null) return saved.clone();
        // Si no hay /pvp setspawn, usar el spawn del mundo del lobby, nunca Survival.
        return player.getWorld().getSpawnLocation().add(0.5, 0, 0.5);
    }
    private void returnToPvpSpawn(Player p) {
        if (!p.isOnline() || !getConfig().getBoolean("combat.return-to-spawn-on-defeat", true)) return;
        Location destination = pvpRespawnLocation(p);
        // Los nametags TextDisplay montados son pasajeros y pueden impedir el teleport.
        for (Entity passenger : new ArrayList<>(p.getPassengers())) p.removePassenger(passenger);
        if (!p.teleport(destination)) p.sendMessage(message("spawn-teleport-failed"));
    }
    private void deactivate(Player p, boolean announce) {
        cancelCountdown(p);
        boolean wasActive = active.remove(p.getUniqueId());
        if (wasActive || snapshots.getBoolean(p.getUniqueId() + ".stored")) restore(p);
        if (wasActive && announce) {
            if (getConfig().getBoolean("combat.heal-on-disable", true)) healFully(p);
            p.sendTitle(color(getConfig().getString("countdown.finished-title", "&aPvP DESACTIVADO")),
                color(getConfig().getString("countdown.finished-subtitle", "&7Objetos restaurados")), 0, 25, 10);
            p.sendMessage(message("disabled"));
        }
    }
    private void startCountdown(Player p) {
        UUID id = p.getUniqueId();
        if (!active.contains(id) || disableTimers.containsKey(id)) return;
        int seconds = Math.max(1, Math.min(60, getConfig().getInt("countdown.seconds", 5)));
        // El jugador continúa en PvP durante la cuenta regresiva.
        final int[] remaining = { seconds };
        showCountdown(p, remaining[0]);
        org.bukkit.scheduler.BukkitTask task = Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (!p.isOnline() || !active.contains(id) || !allowed(p)) { deactivate(p, false); return; }
            remaining[0]--;
            if (remaining[0] <= 0) { deactivate(p, true); return; }
            showCountdown(p, remaining[0]);
        }, 20L, 20L);
        disableTimers.put(id, task);
    }
    private void showCountdown(Player p, int remaining) {
        p.sendTitle(color(getConfig().getString("countdown.title", "&c&lDesactivando PVP")),
            color(getConfig().getString("countdown.subtitle", "&e%seconds%").replace("%seconds%", String.valueOf(remaining))),
            0, 25, 0);
    }
    @EventHandler(ignoreCancelled = false) public void rightClick(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || !activationSword(e.getPlayer(), e.getItem())) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        if (!allowed(p)) { p.sendMessage(message("only-world")); return; }
        if (!p.hasPermission("bastionlobbypvp.use")) { p.sendMessage(message("no-permission")); return; }
        if (active.contains(p.getUniqueId())) startCountdown(p); else startActivation(p);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void held(PlayerItemHeldEvent e) {
        if (e.getPreviousSlot() != e.getNewSlot()) {
            cancelActivation(e.getPlayer(), true);
            if (active.contains(e.getPlayer().getUniqueId())) startCountdown(e.getPlayer());
        }
    }
    @EventHandler public void swordWear(org.bukkit.event.player.PlayerItemDamageEvent e) {
        if (activationSword(e.getPlayer(), e.getItem())) e.setCancelled(true);
    }
    @EventHandler public void swap(PlayerSwapHandItemsEvent e) {
        if (pvpSword(e.getMainHandItem()) || pvpSword(e.getOffHandItem()) || deluxeHubSword(e.getPlayer(), e.getMainHandItem())) e.setCancelled(true);
        if (active.contains(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }
    @EventHandler public void drop(PlayerDropItemEvent e) {
        if (pvpSword(e.getItemDrop().getItemStack()) || deluxeHubSword(e.getPlayer(), e.getItemDrop().getItemStack())) e.setCancelled(true);
        if (active.contains(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }
    @EventHandler public void inventory(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (active.contains(p.getUniqueId()) && (e.getRawSlot() >= 0 && e.getRawSlot() < e.getView().getTopInventory().getSize() || e.getClickedInventory() == p.getInventory())) { e.setCancelled(true); return; }
        if (pvpSword(e.getCurrentItem()) || pvpSword(e.getCursor()) || (e.getHotbarButton() >= 0 && pvpSword(p.getInventory().getItem(e.getHotbarButton())))) e.setCancelled(true);
    }
    @EventHandler public void drag(InventoryDragEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (active.contains(p.getUniqueId()) || pvpSword(e.getOldCursor())) e.setCancelled(true);
    }
    @EventHandler public void quit(PlayerQuitEvent e) { cancelActivation(e.getPlayer(), false); deactivate(e.getPlayer(), false); }
    @EventHandler public void join(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTask(this, () -> {
            if (!p.isOnline()) return;
            recover(p);
            if (allowed(p) && getConfig().getBoolean("sword.give-on-join", false)) giveSword(p);
            Bukkit.getScheduler().runTaskLater(this, () -> { if (p.isOnline()) makeDeluxeSwordUnbreakable(p); }, 20L);
        });
    }
    @EventHandler public void world(PlayerChangedWorldEvent e) {
        cancelActivation(e.getPlayer(), true);
        deactivate(e.getPlayer(), false);
        if (allowed(e.getPlayer()) && getConfig().getBoolean("sword.give-on-world-enter", false)) giveSword(e.getPlayer());
        Bukkit.getScheduler().runTaskLater(this, () -> { if (e.getPlayer().isOnline()) makeDeluxeSwordUnbreakable(e.getPlayer()); }, 2L);
    }
    @EventHandler public void gameMode(PlayerGameModeChangeEvent e) { cancelActivation(e.getPlayer(), true); deactivate(e.getPlayer(), false); }
    @EventHandler(priority = EventPriority.HIGHEST) public void pvp(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim) || !allowed(victim)) return;
        Player attacker = null;
        if (e.getDamager() instanceof Player p) attacker = p;
        else if (e.getDamager() instanceof Projectile projectile && projectile.getShooter() instanceof Player p) attacker = p;
        if (attacker == null) return;
        if (!allowed(attacker) || !active.contains(attacker.getUniqueId()) || !active.contains(victim.getUniqueId())) {
            e.setCancelled(true); return;
        }
        e.setDamage(e.getDamage() * Math.max(0, getConfig().getDouble("combat.damage-multiplier", 1.0)));
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) public void preventDeath(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p) || !active.contains(p.getUniqueId()) || !getConfig().getBoolean("combat.prevent-player-death", true)) return;
        if (e.getFinalDamage() >= p.getHealth()) {
            e.setCancelled(true);
            double maxHealth = Objects.requireNonNull(p.getAttribute(Attribute.MAX_HEALTH)).getValue();
            p.setHealth(maxHealth);
            p.setFireTicks(0);
            if (getConfig().getBoolean("combat.stop-pvp-when-dying", true)) {
                deactivate(p, false);
                p.setNoDamageTicks(40);
                Bukkit.getScheduler().runTask(this, () -> {
                    if (!p.isOnline()) return;
                    returnToPvpSpawn(p);
                    healFully(p);
                    p.sendTitle(color(getConfig().getString("combat.defeat-title", "&c&lHAS SIDO DERROTADO")),
                        color(getConfig().getString("combat.defeat-subtitle", "&eRegresaste al spawn del PvP")), 10, 50, 10);
                    p.sendMessage(message("defeated"));
                });
            }
        }
    }
    // Respaldo por si otro plugin permite una muerte real mientras se tiene PvP activo.
    @EventHandler public void death(PlayerDeathEvent e) {
        if (!active.contains(e.getPlayer().getUniqueId())) return;
        pendingRespawns.add(e.getPlayer().getUniqueId());
        e.setKeepInventory(true);
        e.getDrops().clear();
        deactivate(e.getPlayer(), false);
    }
    @EventHandler(priority = EventPriority.HIGHEST) public void respawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        if (!pendingRespawns.remove(p.getUniqueId())) return;
        if (getConfig().getBoolean("combat.return-to-spawn-on-defeat", true)) e.setRespawnLocation(pvpRespawnLocation(p));
        Bukkit.getScheduler().runTask(this, () -> {
            if (!p.isOnline()) return;
            healFully(p);
            p.sendMessage(message("defeated"));
        });
    }
}
