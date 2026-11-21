package lat.bastion.lobbypvp;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
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
    private File snapshotsFile;
    private YamlConfiguration snapshots;
    private static final Pattern HEX = Pattern.compile("&#([a-fA-F0-9]{6})");

    @Override public void onEnable() {
        saveDefaultConfig();
        swordKey = new NamespacedKey(this, "pvp_sword");
        snapshotsFile = new File(getDataFolder(), "armor-backups.yml");
        snapshots = YamlConfiguration.loadConfiguration(snapshotsFile);
        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("lobbypvp")).setExecutor((sender, command, label, args) -> {
            if (args.length == 1 && args[0].equalsIgnoreCase("off") && sender instanceof Player player) {
                deactivate(player, true); return true;
            }
            if (!sender.hasPermission("bastionlobbypvp.admin")) { sender.sendMessage(message("no-permission")); return true; }
            if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
                for (Player p : Bukkit.getOnlinePlayers()) deactivate(p, false);
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
            if (args.length == 1) return (sender.hasPermission("bastionlobbypvp.admin") ? List.of("give", "reload", "off") : List.of("off")).stream().filter(s -> s.startsWith(args[0].toLowerCase())).toList();
            if (args.length == 2 && args[0].equalsIgnoreCase("give") && sender.hasPermission("bastionlobbypvp.admin")) return Bukkit.getOnlinePlayers().stream().map(Player::getName).filter(s -> s.toLowerCase().startsWith(args[1].toLowerCase())).toList();
            return List.of();
        });
        for (Player p : Bukkit.getOnlinePlayers()) recover(p);
    }

    @Override public void onDisable() {
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
    private boolean allowed(Player p) { return getConfig().getStringList("worlds").contains(p.getWorld().getName()); }
    private boolean pvpSword(ItemStack item) {
        return item != null && item.hasItemMeta() && item.getItemMeta().getPersistentDataContainer().has(swordKey, PersistentDataType.BYTE);
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
    private void storeSnapshots() {
        try { snapshots.save(snapshotsFile); } catch (IOException ex) { getLogger().severe("No se pudo guardar armor-backups.yml: " + ex.getMessage()); }
    }
    private void snapshot(Player p) {
        String key = p.getUniqueId().toString();
        ItemStack[] armor = p.getInventory().getArmorContents();
        for (int i=0;i<4;i++) snapshots.set(key + ".slot" + i, armor[i]);
        snapshots.set(key + ".stored", true);
        storeSnapshots();
    }
    private void restore(Player p) {
        String key = p.getUniqueId().toString();
        if (!snapshots.getBoolean(key + ".stored")) return;
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
    private ItemStack armorPiece(String configPath, Material fallback) {
        Material mat = Material.matchMaterial(getConfig().getString("armor." + configPath, fallback.name()));
        ItemStack item = new ItemStack(mat == null ? fallback : mat);
        int protection = Math.max(0, Math.min(4, getConfig().getInt("armor.protection-level", 0)));
        if (protection > 0) item.addUnsafeEnchantment(Enchantment.PROTECTION, protection);
        return item;
    }
    private void activate(Player p) {
        if (active.contains(p.getUniqueId()) || !allowed(p) || !p.hasPermission("bastionlobbypvp.use")) return;
        snapshot(p);
        p.getInventory().setArmorContents(new ItemStack[]{
            armorPiece("boots", Material.NETHERITE_BOOTS), armorPiece("leggings", Material.NETHERITE_LEGGINGS),
            armorPiece("chestplate", Material.NETHERITE_CHESTPLATE), armorPiece("helmet", Material.NETHERITE_HELMET)
        });
        active.add(p.getUniqueId()); p.sendMessage(message("enabled"));
    }
    private void deactivate(Player p, boolean announce) {
        boolean wasActive = active.remove(p.getUniqueId());
        if (wasActive) restore(p);
        if (wasActive && announce) p.sendMessage(message("disabled"));
    }

    @EventHandler(ignoreCancelled = false) public void rightClick(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || !pvpSword(e.getItem())) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        if (!allowed(p)) { p.sendMessage(message("only-world")); return; }
        if (!p.hasPermission("bastionlobbypvp.use")) { p.sendMessage(message("no-permission")); return; }
        if (active.contains(p.getUniqueId())) deactivate(p, true); else activate(p);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void held(PlayerItemHeldEvent e) {
        if (active.contains(e.getPlayer().getUniqueId())) deactivate(e.getPlayer(), true);
    }
    @EventHandler public void swap(PlayerSwapHandItemsEvent e) {
        if (pvpSword(e.getMainHandItem()) || pvpSword(e.getOffHandItem())) e.setCancelled(true);
        if (active.contains(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }
    @EventHandler public void drop(PlayerDropItemEvent e) {
        if (pvpSword(e.getItemDrop().getItemStack())) e.setCancelled(true);
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
    @EventHandler public void quit(PlayerQuitEvent e) { deactivate(e.getPlayer(), false); }
    @EventHandler public void join(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTask(this, () -> {
            if (!p.isOnline()) return;
            recover(p);
            if (allowed(p) && getConfig().getBoolean("sword.give-on-join", false)) giveSword(p);
        });
    }
    @EventHandler public void world(PlayerChangedWorldEvent e) {
        deactivate(e.getPlayer(), false);
        if (allowed(e.getPlayer()) && getConfig().getBoolean("sword.give-on-world-enter", false)) giveSword(e.getPlayer());
    }
    @EventHandler public void gameMode(PlayerGameModeChangeEvent e) { deactivate(e.getPlayer(), false); }
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
            if (getConfig().getBoolean("combat.stop-pvp-when-dying", true)) deactivate(p, true);
        }
    }
    @EventHandler public void death(PlayerDeathEvent e) { if (active.contains(e.getPlayer().getUniqueId())) {
        e.setKeepInventory(true); e.getDrops().clear();
        deactivate(e.getPlayer(), false);
    }}
}
