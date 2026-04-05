package de.mcbesser.farmworld;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.Orientable;
import org.bukkit.block.data.type.Door;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerExpChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.world.PortalCreateEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.CompassMeta;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.io.File;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.UUID;

public class FarmWorldPlugin extends JavaPlugin implements Listener, CommandExecutor {

    private static final DateTimeFormatter RESET_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
    private static final int MIN_ENTRY_LEVELS = 5;
    private static final int SECONDS_PER_LEVEL = 60;
    private static final int RETURN_COUNTDOWN_SECONDS = 5;
    private static final double FARM_BORDER_DIAMETER = 5000.0;
    private static final int SAFE_BORDER_MARGIN = 16;
    private static final int SPAWNER_SAFE_RADIUS = 12;

    private final List<ActivePortal> activePortals = new ArrayList<>();
    private final List<PendingActivePortal> pendingActivePortals = new ArrayList<>();
    private final Map<PortalLocation, PortalType> interiorPortalTypes = new HashMap<>();
    private final Map<PortalType, LocalDateTime> nextResets = new EnumMap<>(PortalType.class);
    private final Map<UUID, FarmSession> sessions = new HashMap<>();
    private final Map<UUID, ReturnCountdown> returnCountdowns = new HashMap<>();
    private final Map<UUID, Map<PortalType, Location>> farmAnchors = new HashMap<>();
    private final Map<UUID, Map<PortalType, Location>> farmShelters = new HashMap<>();
    private final Map<UUID, Location> lastPortalReturns = new HashMap<>();
    private final Map<FarmZoneKey, FarmZone> farmZones = new HashMap<>();
    private final Map<UUID, FarmZoneKey> playerZonePresence = new HashMap<>();
    private final Set<UUID> pendingFarmEntries = new HashSet<>();

    private BukkitTask portalMonitorTask;
    private BukkitTask resetMonitorTask;
    private BukkitTask sessionTickTask;
    private BukkitTask zoneParticleTask;

    private ZoneId zoneId;
    private boolean resetRunning;
    private NamespacedKey compassKey;
    private File claimsFile;

    @Override
    public void onEnable() {
        this.zoneId = ZoneId.systemDefault();
        this.compassKey = new NamespacedKey(this, "farm_return_compass");
        this.claimsFile = new File(getDataFolder(), "claims.yml");

        Bukkit.getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("farmreset"), "Command farmreset fehlt in plugin.yml").setExecutor(this);

        LocalDateTime now = LocalDateTime.now(zoneId);
        for (PortalType portalType : PortalType.values()) {
            nextResets.put(portalType, computeNextReset(portalType, now));
        }

        loadClaimsFromDisk();
        Bukkit.getScheduler().runTask(this, this::restorePendingActivePortals);

        portalMonitorTask = Bukkit.getScheduler().runTaskTimer(this, this::monitorPortals, 20L, 20L);
        resetMonitorTask = Bukkit.getScheduler().runTaskTimer(this, this::monitorResets, 20L, 20L * 60L);
        sessionTickTask = Bukkit.getScheduler().runTaskTimer(this, this::tickSessions, 20L, 20L);
        zoneParticleTask = Bukkit.getScheduler().runTaskTimer(this, this::renderZoneParticles, 20L, 20L);

        getLogger().info("FarmWorld-Portalplugin aktiviert.");
        logNextResets();
    }

    @Override
    public void onDisable() {
        if (portalMonitorTask != null) {
            portalMonitorTask.cancel();
        }
        if (resetMonitorTask != null) {
            resetMonitorTask.cancel();
        }
        if (sessionTickTask != null) {
            sessionTickTask.cancel();
        }
        if (zoneParticleTask != null) {
            zoneParticleTask.cancel();
        }

        for (UUID uuid : new ArrayList<>(sessions.keySet())) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                endSession(player, true, "Plugin deaktiviert.");
            }
        }

        saveClaimsToDisk();

        activePortals.clear();
        pendingActivePortals.clear();
        interiorPortalTypes.clear();
        sessions.clear();
        returnCountdowns.clear();
        farmAnchors.clear();
        farmShelters.clear();
        lastPortalReturns.clear();
        farmZones.clear();
        playerZonePresence.clear();
    }
    @EventHandler
    public void onPortalIgnite(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        Player player = event.getPlayer();
        ItemStack item = event.getItem();
        Block clicked = event.getClickedBlock();

        if (isFarmWorld(player.getWorld().getName())
                && clicked != null
                && item != null
                && item.getType() == Material.ENDER_EYE
                && clicked.getType() == Material.END_PORTAL_FRAME) {
            event.setCancelled(true);
            player.sendMessage(color("&cVanilla-Endportale sind in FarmWorlden deaktiviert."));
            return;
        }

        if (item == null || (item.getType() != Material.FLINT_AND_STEEL && item.getType() != Material.FIRE_CHARGE)) {
            return;
        }

        if (clicked == null) {
            return;
        }

        Block igniteTarget = clicked.getRelative(event.getBlockFace());
        if (!isIgnitableInterior(igniteTarget.getType())) {
            return;
        }

        PortalShape shape = findPortalShape(igniteTarget);
        if (shape == null || isAlreadyTracked(shape)) {
            return;
        }

        ActivePortal portal = activatePortal(shape);
        activePortals.add(portal);
        saveClaimsToDisk();

        consumeIgnitionItem(event.getPlayer(), item);

        event.setCancelled(true);
        event.getPlayer().sendMessage(color("&aPortal aktiviert: " + shape.portalType.displayName + "."));
    }

    @EventHandler
    public void onPortalCreate(PortalCreateEvent event) {
        if (event.getBlocks().isEmpty()) {
            return;
        }

        if (!isFarmWorld(event.getBlocks().get(0).getWorld().getName())) {
            return;
        }

        boolean createsNetherPortal = event.getBlocks().stream()
                .anyMatch(blockState -> blockState.getType() == Material.NETHER_PORTAL);
        if (!createsNetherPortal) {
            return;
        }

        event.setCancelled(true);
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        restorePendingActivePortals();
    }

    @EventHandler
    public void onPlayerPortal(PlayerPortalEvent event) {
        Player player = event.getPlayer();

        if (isFarmWorld(player.getWorld().getName())
                && event.getCause() == PlayerTeleportEvent.TeleportCause.END_PORTAL) {
            event.setCancelled(true);
            player.sendMessage(color("&cVanilla-Endportale sind in FarmWorlden deaktiviert."));
            return;
        }

        if (event.getCause() != PlayerTeleportEvent.TeleportCause.NETHER_PORTAL) {
            return;
        }
        PortalType typeFromPortal = findPortalTypeAt(event.getFrom());
        if (typeFromPortal == null) {
            typeFromPortal = findPortalTypeNear(player.getLocation(), 1);
        }

        if (typeFromPortal != null) {
            event.setCancelled(true);

            if (isFarmWorld(player.getWorld().getName())) {
                endSession(player, true, "&eDu wurdest zu deinem Portal zurückgebracht.");
                return;
            }

            if (!isUnlimitedFarmMode(player) && player.getLevel() < MIN_ENTRY_LEVELS) {
                player.sendMessage(color("&cDu brauchst mindestens " + MIN_ENTRY_LEVELS + " Level, um die FarmWorld zu betreten."));
                return;
            }

            int freeSlot = player.getInventory().firstEmpty();
            if (freeSlot < 0) {
                player.sendMessage(color("&cDu brauchst mindestens 1 freien Inventar-Slot für den Rückkehr-Kompass."));
                return;
            }

            World targetWorld = getOrCreateWorld(typeFromPortal);
            if (!pendingFarmEntries.add(player.getUniqueId())) {
                player.sendMessage(color("&eFarmWorld-Eintritt wird bereits vorbereitet."));
                return;
            }

            player.sendMessage(color("&7FarmWorld-Eintritt wird vorbereitet..."));
            prepareFarmEntry(player, typeFromPortal, freeSlot, targetWorld);
            return;
        }

        if (pendingFarmEntries.contains(player.getUniqueId())) {
            event.setCancelled(true);
            player.sendMessage(color("&eFarmWorld-Eintritt lÃ¤uft bereits."));
            return;
        }

        if (isFarmWorld(player.getWorld().getName())) {
            event.setCancelled(true);
            player.sendMessage(color("&cVanilla-Netherportale sind in FarmWorlden deaktiviert."));
            return;
        }

        if (isFarmWorld(player.getWorld().getName())) {
            event.setCancelled(true);
            endSession(player, true, "&eDu wurdest zu deinem Portal zurückgebracht.");
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(this, () -> {
            if (isFarmWorld(player.getWorld().getName()) && !sessions.containsKey(player.getUniqueId())) {
                rescueStuckPlayer(player);
            }
        });
    }

    private void prepareFarmEntry(Player player, PortalType type, int preferredFreeSlot, World targetWorld) {
        UUID playerId = player.getUniqueId();
        resolveFarmEntryLocationAsync(player, type, targetWorld, targetLocation -> {
            pendingFarmEntries.remove(playerId);

            if (!player.isOnline()) {
                return;
            }
            if (targetLocation == null || targetLocation.getWorld() == null) {
                player.sendMessage(color("&cKonnte keinen sicheren Eintrittspunkt in der FarmWorld finden."));
                return;
            }

            int freeSlot = preferredFreeSlot;
            ItemStack currentItem = player.getInventory().getItem(freeSlot);
            if (currentItem != null && currentItem.getType() != Material.AIR) {
                freeSlot = player.getInventory().firstEmpty();
            }
            if (freeSlot < 0) {
                player.sendMessage(color("&cDu brauchst mindestens 1 freien Inventar-Slot fÃ¼r den RÃ¼ckkehr-Kompass."));
                return;
            }

            if (!startSession(player, type, freeSlot, targetLocation)) {
                player.sendMessage(color("&cKonnte FarmWorld-Eintritt nicht starten."));
            }
        });
    }

    @EventHandler
    public void onCompassUse(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR
                && action != Action.RIGHT_CLICK_BLOCK
                && action != Action.LEFT_CLICK_AIR
                && action != Action.LEFT_CLICK_BLOCK) {
            return;
        }

        Player player = event.getPlayer();
        FarmSession session = sessions.get(player.getUniqueId());
        if (session == null) {
            return;
        }

        if (!isReturnCompass(event.getItem())) {
            return;
        }

        event.setCancelled(true);
        if (player.isSneaking() && (action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK)) {
            deleteClaimWithCompass(player, session.portalType);
            return;
        }
        if (player.isSneaking() && action == Action.RIGHT_CLICK_BLOCK && event.getClickedBlock() != null) {
            claimWithCompass(player, session.portalType, event.getClickedBlock().getLocation().clone().add(0.5, 1.0, 0.5));
            return;
        }

        if (isUnlimitedFarmMode(player)) {
            endSession(player, true, "&aCreative/Spectator: sofortige Rückkehr.");
            return;
        }

        if (returnCountdowns.containsKey(player.getUniqueId())) {
            player.sendMessage(color("&eRückkehr-Countdown läuft bereits."));
            return;
        }

        returnCountdowns.put(player.getUniqueId(), new ReturnCountdown(RETURN_COUNTDOWN_SECONDS));
        player.sendMessage(color("&eBleib " + RETURN_COUNTDOWN_SECONDS + " Sekunden still stehen, um zurückzureisen."));
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        FarmZone zone = findZoneAt(event.getBlock().getLocation());
        if (zone == null) {
            return;
        }
        if (!canUseZone(event.getPlayer().getUniqueId(), zone)) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(color("&cIn diesem Claim darfst du nichts abbauen."));
        }
    }

    @EventHandler
    public void onProtectedInteract(PlayerInteractEvent event) {
        if (event.getClickedBlock() == null) {
            return;
        }
        FarmZone zone = findZoneAt(event.getClickedBlock().getLocation());
        if (zone == null) {
            return;
        }
        if (!canUseZone(event.getPlayer().getUniqueId(), zone)) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(color("&cIn diesem Claim darfst du nicht interagieren."));
        }
    }

    @EventHandler
    public void onCompassShare(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof Player target)) {
            return;
        }

        Player owner = event.getPlayer();
        if (!owner.isSneaking()) {
            return;
        }
        if (!isReturnCompass(owner.getInventory().getItemInMainHand())) {
            return;
        }

        FarmZone zone = findZoneAt(owner.getLocation());
        if (zone == null || !zone.owner.equals(owner.getUniqueId())) {
            return;
        }

        zone.allowedPlayers.add(target.getUniqueId());
        owner.sendMessage(color("&a" + target.getName() + " hat jetzt Zugriff auf deinen Claim."));
        target.sendMessage(color("&aDu hast Zugriff auf den Claim von " + owner.getName() + "."));
        saveClaimsToDisk();
        event.setCancelled(true);
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) {
            return;
        }

        if (from.getChunk().getX() != to.getChunk().getX() || from.getChunk().getZ() != to.getChunk().getZ()) {
            handleZonePresenceChange(player, to);
        }

        ReturnCountdown countdown = returnCountdowns.get(player.getUniqueId());
        if (countdown == null) {
            return;
        }

        boolean movedBlock = from.getBlockX() != to.getBlockX()
                || from.getBlockY() != to.getBlockY()
                || from.getBlockZ() != to.getBlockZ();

        if (movedBlock) {
            returnCountdowns.remove(player.getUniqueId());
            player.sendMessage(color("&cRückkehr abgebrochen, da du dich bewegt hast."));
        }
    }

    @EventHandler
    public void onPlayerExpChange(PlayerExpChangeEvent event) {
        Player player = event.getPlayer();
        FarmSession session = sessions.get(player.getUniqueId());
        if (session == null) {
            return;
        }

        int amount = event.getAmount();
        if (amount <= 0) {
            return;
        }

        event.setAmount(0);
        session.xpBuffer += amount;

        int addedLevels = 0;
        while (session.xpBuffer >= xpToNextLevel(session.virtualLevel)) {
            session.xpBuffer -= xpToNextLevel(session.virtualLevel);
            session.virtualLevel++;
            addedLevels++;
        }

        if (addedLevels > 0) {
            session.remainingSeconds += addedLevels * SECONDS_PER_LEVEL;
            changePlayerLevelsAccurately(player, addedLevels);
            player.sendMessage(color("&a+" + addedLevels + " Minute(n) Farmzeit durch EXP."));
        }
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        ItemStack dropped = event.getItemDrop().getItemStack();
        if (isReturnCompass(dropped)) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(color("&cDieses Item kann nicht gedroppt werden."));
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (!sessions.containsKey(player.getUniqueId())) {
            return;
        }

        ItemStack current = event.getCurrentItem();
        ItemStack cursor = event.getCursor();

        if (isReturnCompass(current)) {
            if (event.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY
                    || event.getClickedInventory() != player.getInventory()
                    || event.getView().getTopInventory().getType() != InventoryType.CRAFTING) {
                event.setCancelled(true);
                return;
            }
        }

        if (isReturnCompass(cursor) && event.getClickedInventory() != player.getInventory()) {
            event.setCancelled(true);
            return;
        }

        if (event.getHotbarButton() >= 0) {
            ItemStack hotbar = player.getInventory().getItem(event.getHotbarButton());
            if (isReturnCompass(hotbar)) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (!sessions.containsKey(player.getUniqueId())) {
            return;
        }
        if (!isReturnCompass(event.getOldCursor())) {
            return;
        }

        int topSize = event.getView().getTopInventory().getSize();
        for (int rawSlot : event.getRawSlots()) {
            if (rawSlot < topSize && event.getView().getTopInventory().getType() != InventoryType.CRAFTING) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("farmreset")) {
            return false;
        }

        sender.sendMessage(color("&eNächste Farm-Resets (" + zoneId + "):"));
        LocalDateTime nearest = null;
        PortalType nearestType = null;

        for (PortalType type : PortalType.values()) {
            LocalDateTime next = nextResets.get(type);
            if (next == null) {
                next = computeNextReset(type, LocalDateTime.now(zoneId));
                nextResets.put(type, next);
            }

            sender.sendMessage(color("&7- &f" + type.displayName + "&7: &a" + RESET_FORMAT.format(next)));
            if (nearest == null || next.isBefore(nearest)) {
                nearest = next;
                nearestType = type;
            }
        }

        if (nearest != null && nearestType != null) {
            sender.sendMessage(color("&7Nächster globaler Reset: &f" + nearestType.displayName + " &7am &a" + RESET_FORMAT.format(nearest)));
        }

        return true;
    }

    private boolean startSession(Player player, PortalType type, int freeSlot, Location targetLocation) {
        if (sessions.containsKey(player.getUniqueId())) {
            endSession(player, false, null);
        }

        boolean creativeEntry = isUnlimitedFarmMode(player);
        int bankedLevels = creativeEntry ? 0 : player.getLevel();
        if (!creativeEntry && bankedLevels < MIN_ENTRY_LEVELS) {
            return false;
        }

        ItemStack compass = createReturnCompass();
        Location anchor = getAnchorLocation(player.getUniqueId(), type);
        if (anchor != null) {
            applyAnchorToCompassItem(compass, anchor);
        }
        PlayerInventory inventory = player.getInventory();
        inventory.setItem(freeSlot, compass);

        BossBar bar = Bukkit.createBossBar("Farmzeit", BarColor.GREEN, BarStyle.SOLID);
        bar.addPlayer(player);

        Location returnLoc = player.getLocation().clone();
        lastPortalReturns.put(player.getUniqueId(), returnLoc.clone());
        saveClaimsToDisk();

        int initialSeconds = creativeEntry ? Integer.MAX_VALUE : bankedLevels * SECONDS_PER_LEVEL;
        FarmSession session = new FarmSession(type, returnLoc, initialSeconds, freeSlot, bar, creativeEntry, bankedLevels);
        sessions.put(player.getUniqueId(), session);

        boolean teleported = player.teleport(targetLocation, PlayerTeleportEvent.TeleportCause.PLUGIN);
        if (!teleported) {
            sessions.remove(player.getUniqueId());
            bar.removeAll();
            inventory.setItem(freeSlot, null);
            return false;
        }

        if (!creativeEntry) {
            chargeStartedMinute(player, session);
        }

        updateBossBar(session, creativeEntry);
        if (creativeEntry) {
            player.sendMessage(color("&aDu bist in der " + type.displayName + ". Zeitbudget: unbegrenzt (Creative/Spectator)."));
        } else {
            player.sendMessage(color("&aDu bist in der " + type.displayName + ". Zeitbudget: " + formatSeconds(session.remainingSeconds)));
        }
        return true;
    }

    private void tickSessions() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            FarmSession session = sessions.get(player.getUniqueId());
            if (session == null) {
                if (isFarmWorld(player.getWorld().getName())) {
                    rescueStuckPlayer(player);
                }
                continue;
            }

            if (!isFarmWorld(player.getWorld().getName())) {
                endSession(player, false, null);
                continue;
            }

            ensureCompassPresent(player, session);
            boolean unlimitedTime = session.unlimitedTime || isUnlimitedFarmMode(player);
            if (unlimitedTime) {
                session.unlimitedTime = true;
            }

            ReturnCountdown countdown = returnCountdowns.get(player.getUniqueId());
            if (countdown != null) {
                if (isUnlimitedFarmMode(player)) {
                    returnCountdowns.remove(player.getUniqueId());
                    endSession(player, true, "&aCreative/Spectator: sofortige Rückkehr.");
                    continue;
                }
                countdown.secondsLeft--;
                if (countdown.secondsLeft <= 0) {
                    returnCountdowns.remove(player.getUniqueId());
                    endSession(player, true, "&aDu wurdest erfolgreich zurückteleportiert.");
                    continue;
                }
                player.sendActionBar(color("&eRückkehr in " + countdown.secondsLeft + "s - bitte still stehen"));
            }

            if (!unlimitedTime) {
                session.remainingSeconds--;
                if (session.remainingSeconds > 0 && session.remainingSeconds % SECONDS_PER_LEVEL == 0) {
                    chargeStartedMinute(player, session);
                }
            }

            if (!unlimitedTime && !session.warnedThirty && session.remainingSeconds == 30) {
                session.warnedThirty = true;
                player.sendMessage(color("&6Noch 30 Sekunden Farmzeit."));
            }

            if (!unlimitedTime && session.remainingSeconds <= 10 && session.remainingSeconds > 0) {
                player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 40, 0, false, false, false));
                player.sendMessage(color("&cNoch " + session.remainingSeconds + " Sekunden bis zur Rückkehr."));
            }

            updateBossBar(session, unlimitedTime);

            if (!unlimitedTime && session.remainingSeconds <= 0) {
                endSession(player, true, "&cFarmzeit abgelaufen. Du wurdest zurückteleportiert.");
            }
        }
    }

    private void rescueStuckPlayer(Player player) {
        player.removePotionEffect(PotionEffectType.BLINDNESS);
        Location target = lastPortalReturns.get(player.getUniqueId());
        if (target == null || target.getWorld() == null || isFarmWorld(target.getWorld().getName())) {
            target = getMainWorld().getSpawnLocation().clone().add(0.5, 0.0, 0.5);
        } else {
            target = findSafeReturnLocationNear(target);
        }
        player.teleport(target, PlayerTeleportEvent.TeleportCause.PLUGIN);
        player.sendMessage(color("&eDu warst ohne aktive Sitzung in einer FarmWorld und wurdest zum letzten Portal zurückteleportiert."));
    }

    private void endSession(Player player, boolean teleportBack, String message) {
        UUID uuid = player.getUniqueId();
        FarmSession session = sessions.remove(uuid);
        returnCountdowns.remove(uuid);
        playerZonePresence.remove(uuid);

        if (session == null) {
            if (teleportBack && isFarmWorld(player.getWorld().getName())) {
                player.teleport(getMainWorld().getSpawnLocation().clone().add(0.5, 0.0, 0.5), PlayerTeleportEvent.TeleportCause.PLUGIN);
            }
            return;
        }

        removeReturnCompass(player);
        session.bossBar.removeAll();

        player.removePotionEffect(PotionEffectType.BLINDNESS);

        if (teleportBack) {
            Location target = session.returnLocation;
            if (target.getWorld() == null || isFarmWorld(target.getWorld().getName())) {
                target = getMainWorld().getSpawnLocation().clone().add(0.5, 0.0, 0.5);
            }
            if (isUnlimitedFarmMode(player)) {
                target = findSafeReturnLocationNear(target);
            }
            player.teleport(target, PlayerTeleportEvent.TeleportCause.PLUGIN);
        }

        if (message != null && !message.isEmpty()) {
            player.sendMessage(color(message));
        }
    }

    private void ensureCompassPresent(Player player, FarmSession session) {
        PlayerInventory inv = player.getInventory();

        int foundCount = 0;
        int keepSlot = -1;
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack item = inv.getItem(i);
            if (!isReturnCompass(item)) {
                continue;
            }
            foundCount++;
            if (keepSlot < 0) {
                keepSlot = i;
                normalizeReturnCompass(item, player.getUniqueId(), session.portalType);
                inv.setItem(i, item);
            } else {
                inv.setItem(i, null);
            }
        }

        if (foundCount > 0) {
            return;
        }

        // Beim Verschieben liegt der Kompass kurz auf dem Cursor.
        ItemStack cursor = player.getOpenInventory().getCursor();
        if (isReturnCompass(cursor)) {
            return;
        }
        ItemStack replacement = createReturnCompass();
        Location anchor = getAnchorLocation(player.getUniqueId(), session.portalType);
        if (anchor != null) {
            applyAnchorToCompassItem(replacement, anchor);
        }
        inv.setItem(session.compassSlot, replacement);
    }

    private void normalizeReturnCompass(ItemStack item, UUID owner, PortalType type) {
        if (item == null || item.getType() != Material.COMPASS) {
            return;
        }
        ItemMeta rawMeta = item.getItemMeta();
        if (!(rawMeta instanceof CompassMeta compassMeta)) {
            return;
        }
        applyReturnCompassMeta(compassMeta);
        Location anchor = getAnchorLocation(owner, type);
        if (anchor != null && anchor.getWorld() != null) {
            try {
                compassMeta.setLodestone(anchor.clone());
                compassMeta.setLodestoneTracked(false);
            } catch (IllegalArgumentException ignored) {
                compassMeta.setLodestone(null);
                compassMeta.setLodestoneTracked(true);
            }
        }
        item.setItemMeta(compassMeta);
    }

    private ItemStack createReturnCompass() {
        ItemStack compass = new ItemStack(Material.COMPASS);
        ItemMeta meta = compass.getItemMeta();
        if (meta instanceof CompassMeta compassMeta) {
            applyReturnCompassMeta(compassMeta);
            compass.setItemMeta(compassMeta);
        }
        return compass;
    }

    private void applyReturnCompassMeta(CompassMeta compassMeta) {
        compassMeta.setDisplayName(color("&eRückkehr-Kompass"));
        compassMeta.setLore(Arrays.asList(
                color("&7Funktionen:"),
                color("&f- Rechtsklick: &7Rückkehr-Countdown starten"),
                color("&f- Shift + Rechtsklick Block: &7Claim setzen"),
                color("&f- Shift + Linksklick: &7Claim löschen"),
                color("&f- Shift + Rechtsklick Spieler: &7Zugriff teilen")
        ));
        compassMeta.getPersistentDataContainer().set(compassKey, PersistentDataType.BYTE, (byte) 1);
    }

    private void applyAnchorToReturnCompass(Player player, Location anchor) {
        PlayerInventory inv = player.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack item = inv.getItem(i);
            if (!isReturnCompass(item)) {
                continue;
            }
            applyAnchorToCompassItem(item, anchor);
            inv.setItem(i, item);
        }
    }

    private void applyAnchorToCompassItem(ItemStack compass, Location anchor) {
        if (compass == null || compass.getType() != Material.COMPASS) {
            return;
        }
        if (anchor == null || anchor.getWorld() == null) {
            return;
        }
        ItemMeta meta = compass.getItemMeta();
        if (!(meta instanceof CompassMeta compassMeta)) {
            return;
        }
        try {
            compassMeta.setLodestone(anchor.clone());
            compassMeta.setLodestoneTracked(false);
        } catch (IllegalArgumentException ignored) {
            return;
        }
        compass.setItemMeta(compassMeta);
    }

    private void claimWithCompass(Player player, PortalType type, Location requestedAnchor) {
        if (!type.worldName.equals(player.getWorld().getName())) {
            player.sendMessage(color("&cDu kannst hier keinen Claim für diese Farm setzen."));
            return;
        }

        Location anchor = requestedAnchor;
        if (!isSafeLocation(anchor)) {
            Location safe = findSafeNear(player.getWorld(), anchor, 12);
            if (safe == null) {
                player.sendMessage(color("&cHier kann kein sicherer Claim-Punkt gesetzt werden."));
                return;
            }
            anchor = safe;
        }

        int chunkX = anchor.getBlockX() >> 4;
        int chunkZ = anchor.getBlockZ() >> 4;
        FarmZone overlap = findZoneByChunk(player.getWorld().getName(), chunkX, chunkZ);
        if (overlap != null && !overlap.owner.equals(player.getUniqueId())) {
            player.sendMessage(color("&cHier liegt bereits eine fremde Claim-Zone."));
            return;
        }

        FarmZoneKey zoneKey = new FarmZoneKey(player.getUniqueId(), type);
        farmZones.remove(zoneKey);
        FarmZone zone = new FarmZone(zoneKey, player.getName(), player.getWorld().getName(), chunkX, chunkZ);
        farmZones.put(zoneKey, zone);

        clearShelterLocation(player.getUniqueId(), type);
        farmAnchors.computeIfAbsent(player.getUniqueId(), ignored -> new EnumMap<>(PortalType.class)).put(type, anchor);
        applyAnchorToReturnCompass(player, anchor);
        player.sendMessage(color("&aClaim für " + type.displayName + " gesetzt. Kompass zeigt jetzt auf den Claim."));
        saveClaimsToDisk();
    }

    private void deleteClaimWithCompass(Player player, PortalType type) {
        UUID playerId = player.getUniqueId();
        boolean removed = false;

        Map<PortalType, Location> perPlayer = farmAnchors.get(playerId);
        if (perPlayer != null) {
            removed = perPlayer.remove(type) != null;
            if (perPlayer.isEmpty()) {
                farmAnchors.remove(playerId);
            }
        }

        FarmZoneKey key = new FarmZoneKey(playerId, type);
        if (farmZones.remove(key) != null) {
            removed = true;
        }
        if (clearShelterLocation(playerId, type)) {
            removed = true;
        }

        clearAnchorFromReturnCompass(player);

        if (removed) {
            player.sendMessage(color("&eClaim für " + type.displayName + " gelöscht."));
        } else {
            player.sendMessage(color("&eDu hast aktuell keinen Claim für " + type.displayName + "."));
        }
        saveClaimsToDisk();
    }

    private Location getAnchorLocation(UUID owner, PortalType type) {
        Map<PortalType, Location> perPlayer = farmAnchors.get(owner);
        if (perPlayer == null) {
            return null;
        }
        Location loc = perPlayer.get(type);
        if (loc == null) {
            return null;
        }
        Location clone = loc.clone();
        if (clone.getWorld() == null) {
            World world = Bukkit.getWorld(type.worldName);
            if (world != null) {
                clone.setWorld(world);
            }
        }
        return clone;
    }

    private void setShelterLocation(UUID owner, PortalType type, Location shelter) {
        farmShelters.computeIfAbsent(owner, ignored -> new EnumMap<>(PortalType.class)).put(type, shelter.clone());
        saveClaimsToDisk();
    }

    private boolean clearShelterLocation(UUID owner, PortalType type) {
        Map<PortalType, Location> perPlayer = farmShelters.get(owner);
        if (perPlayer == null) {
            return false;
        }
        boolean removed = perPlayer.remove(type) != null;
        if (perPlayer.isEmpty()) {
            farmShelters.remove(owner);
        }
        return removed;
    }

    private Location getShelterLocation(UUID owner, PortalType type, World world) {
        Map<PortalType, Location> perPlayer = farmShelters.get(owner);
        if (perPlayer == null) {
            return null;
        }
        Location loc = perPlayer.get(type);
        if (loc == null) {
            return null;
        }
        Location clone = loc.clone();
        if (clone.getWorld() == null || !world.getName().equals(clone.getWorld().getName())) {
            clone.setWorld(world);
        }
        return clone;
    }

    private void clearAnchorFromReturnCompass(Player player) {
        PlayerInventory inv = player.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack item = inv.getItem(i);
            if (!isReturnCompass(item)) {
                continue;
            }
            ItemMeta rawMeta = item.getItemMeta();
            if (!(rawMeta instanceof CompassMeta compassMeta)) {
                continue;
            }
            compassMeta.setLodestone(null);
            compassMeta.setLodestoneTracked(true);
            item.setItemMeta(compassMeta);
            inv.setItem(i, item);
        }
    }

    private boolean isReturnCompass(ItemStack item) {
        if (item == null || item.getType() != Material.COMPASS || !item.hasItemMeta()) {
            return false;
        }
        Byte marker = item.getItemMeta().getPersistentDataContainer().get(compassKey, PersistentDataType.BYTE);
        return marker != null && marker == (byte) 1;
    }

    private void removeReturnCompass(Player player) {
        PlayerInventory inv = player.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            if (isReturnCompass(inv.getItem(i))) {
                inv.setItem(i, null);
            }
        }
    }

    private int xpToNextLevel(int level) {
        if (level >= 30) {
            return 112 + (level - 30) * 9;
        }
        if (level >= 15) {
            return 37 + (level - 15) * 5;
        }
        return 7 + (level * 2);
    }

    private void chargeStartedMinute(Player player, FarmSession session) {
        int currentLevel = Math.max(0, player.getLevel());
        if (currentLevel > 0) {
            changePlayerLevelsAccurately(player, -1);
        }
    }

    private void changePlayerLevelsAccurately(Player player, int levelDelta) {
        if (levelDelta == 0) {
            return;
        }

        int currentLevel = Math.max(0, player.getLevel());
        float currentProgress = Math.max(0f, Math.min(1f, player.getExp()));
        int targetLevel = Math.max(0, currentLevel + levelDelta);
        int targetTotalExperience = experienceAtLevelStart(targetLevel)
                + Math.round(currentProgress * xpToNextLevel(targetLevel));
        setAccurateTotalExperience(player, targetTotalExperience);
    }

    private int experienceAtLevelStart(int level) {
        if (level <= 0) {
            return 0;
        }
        if (level <= 16) {
            return level * level + (6 * level);
        }
        if (level <= 31) {
            return (int) Math.round((2.5 * level * level) - (40.5 * level) + 360);
        }
        return (int) Math.round((4.5 * level * level) - (162.5 * level) + 2220);
    }

    private void setAccurateTotalExperience(Player player, int totalExperience) {
        player.setExp(0f);
        player.setLevel(0);
        player.setTotalExperience(0);
        if (totalExperience > 0) {
            player.giveExp(totalExperience);
        }
    }

    private String formatSeconds(int total) {
        int clamped = Math.max(0, total);
        int min = clamped / 60;
        int sec = clamped % 60;
        return String.format("%02d:%02d", min, sec);
    }

    private String color(String input) {
        if (input == null) {
            return "";
        }
        return ChatColor.translateAlternateColorCodes('&', input);
    }

    private boolean isUnlimitedFarmMode(Player player) {
        GameMode mode = player.getGameMode();
        return mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR;
    }

    private void loadClaimsFromDisk() {
        farmAnchors.clear();
        farmShelters.clear();
        lastPortalReturns.clear();
        farmZones.clear();
        activePortals.clear();
        pendingActivePortals.clear();
        interiorPortalTypes.clear();

        if (!claimsFile.exists()) {
            return;
        }

        FileConfiguration config = YamlConfiguration.loadConfiguration(claimsFile);
        ConfigurationSection players = config.getConfigurationSection("players");
        ConfigurationSection shelters = config.getConfigurationSection("shelters");
        ConfigurationSection returns = config.getConfigurationSection("returns");
        ConfigurationSection activePortalSection = config.getConfigurationSection("active-portals");
        if (activePortalSection != null) {
            for (String portalId : activePortalSection.getKeys(false)) {
                String base = "active-portals." + portalId;
                String typeName = config.getString(base + ".type");
                if (typeName == null || typeName.isBlank()) {
                    continue;
                }

                PortalType portalType;
                try {
                    portalType = PortalType.valueOf(typeName);
                } catch (IllegalArgumentException ex) {
                    continue;
                }

                List<String> frameSerialized = config.getStringList(base + ".frame");
                List<String> interiorSerialized = config.getStringList(base + ".interior");
                if (interiorSerialized.isEmpty()) {
                    continue;
                }

                PendingActivePortal pending = new PendingActivePortal(portalType, frameSerialized, interiorSerialized);
                if (!tryRestorePendingActivePortal(pending)) {
                    pendingActivePortals.add(pending);
                }
            }
        }

        if (returns != null) {
            for (String uuidString : returns.getKeys(false)) {
                UUID ownerId;
                try {
                    ownerId = UUID.fromString(uuidString);
                } catch (IllegalArgumentException ex) {
                    continue;
                }
                String base = "returns." + uuidString;
                if (!config.contains(base + ".x") || !config.contains(base + ".y") || !config.contains(base + ".z")) {
                    continue;
                }
                String worldName = config.getString(base + ".world", getMainWorld().getName());
                World world = Bukkit.getWorld(worldName);
                if (world == null) {
                    world = getMainWorld();
                }
                lastPortalReturns.put(ownerId, new Location(
                        world,
                        config.getDouble(base + ".x"),
                        config.getDouble(base + ".y"),
                        config.getDouble(base + ".z")
                ));
            }
        }

        if (shelters != null) {
            for (String uuidString : shelters.getKeys(false)) {
                UUID ownerId;
                try {
                    ownerId = UUID.fromString(uuidString);
                } catch (IllegalArgumentException ex) {
                    continue;
                }

                ConfigurationSection shelterTypes = shelters.getConfigurationSection(uuidString);
                if (shelterTypes == null) {
                    continue;
                }

                for (String portalName : shelterTypes.getKeys(false)) {
                    PortalType type;
                    try {
                        type = PortalType.valueOf(portalName);
                    } catch (IllegalArgumentException ex) {
                        continue;
                    }

                    String base = "shelters." + uuidString + "." + portalName;
                    if (!config.contains(base + ".x") || !config.contains(base + ".y") || !config.contains(base + ".z")) {
                        continue;
                    }

                    String worldName = config.getString(base + ".world", type.worldName);
                    World world = Bukkit.getWorld(worldName);
                    if (world == null) {
                        world = Bukkit.getWorld(type.worldName);
                    }

                    Location shelter = new Location(
                            world,
                            config.getDouble(base + ".x"),
                            config.getDouble(base + ".y"),
                            config.getDouble(base + ".z")
                    );
                    farmShelters.computeIfAbsent(ownerId, ignored -> new EnumMap<>(PortalType.class)).put(type, shelter);
                }
            }
        }

        if (players == null) {
            return;
        }

        for (String uuidString : players.getKeys(false)) {
            UUID ownerId;
            try {
                ownerId = UUID.fromString(uuidString);
            } catch (IllegalArgumentException ex) {
                continue;
            }

            ConfigurationSection portals = players.getConfigurationSection(uuidString);
            if (portals == null) {
                continue;
            }

            for (String portalName : portals.getKeys(false)) {
                PortalType type;
                try {
                    type = PortalType.valueOf(portalName);
                } catch (IllegalArgumentException ex) {
                    continue;
                }

                String base = "players." + uuidString + "." + portalName;
                if (!config.contains(base + ".x") || !config.contains(base + ".y") || !config.contains(base + ".z")) {
                    continue;
                }

                String worldName = config.getString(base + ".world", type.worldName);
                World world = Bukkit.getWorld(worldName);
                if (world == null) {
                    world = Bukkit.getWorld(type.worldName);
                }

                double x = config.getDouble(base + ".x");
                double y = config.getDouble(base + ".y");
                double z = config.getDouble(base + ".z");
                Location anchor = new Location(world, x, y, z);
                farmAnchors.computeIfAbsent(ownerId, ignored -> new EnumMap<>(PortalType.class)).put(type, anchor);

                int chunkX = ((int) Math.floor(x)) >> 4;
                int chunkZ = ((int) Math.floor(z)) >> 4;
                String ownerName = Bukkit.getOfflinePlayer(ownerId).getName();
                if (ownerName == null || ownerName.isBlank()) {
                    ownerName = ownerId.toString().substring(0, 8);
                }

                FarmZoneKey zoneKey = new FarmZoneKey(ownerId, type);
                FarmZone zone = new FarmZone(zoneKey, ownerName, type.worldName, chunkX, chunkZ);
                List<String> allowed = config.getStringList(base + ".allowed");
                for (String allowedId : allowed) {
                    try {
                        zone.allowedPlayers.add(UUID.fromString(allowedId));
                    } catch (IllegalArgumentException ignored) {
                    }
                }
                farmZones.put(zoneKey, zone);
            }
        }
    }

    private void saveClaimsToDisk() {
        if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
            getLogger().warning("Konnte Plugin-Ordner zum Speichern von Claims nicht erstellen.");
            return;
        }

        FileConfiguration config = new YamlConfiguration();
        int portalIndex = 0;
        for (ActivePortal portal : activePortals) {
            String base = "active-portals." + portalIndex;
            config.set(base + ".type", portal.portalType.name());

            List<String> frameSerialized = new ArrayList<>();
            for (PortalLocation frame : portal.frameBlocks) {
                String encoded = serializePortalLocation(frame);
                if (encoded != null) {
                    frameSerialized.add(encoded);
                }
            }
            config.set(base + ".frame", frameSerialized);

            List<String> interiorSerialized = new ArrayList<>();
            for (PortalLocation interior : portal.interiorBlocks) {
                String encoded = serializePortalLocation(interior);
                if (encoded != null) {
                    interiorSerialized.add(encoded);
                }
            }
            config.set(base + ".interior", interiorSerialized);
            portalIndex++;
        }

        for (Map.Entry<UUID, Location> returnEntry : lastPortalReturns.entrySet()) {
            UUID ownerId = returnEntry.getKey();
            Location loc = returnEntry.getValue();
            if (loc == null) {
                continue;
            }
            String base = "returns." + ownerId;
            String worldName = loc.getWorld() != null ? loc.getWorld().getName() : getMainWorld().getName();
            config.set(base + ".world", worldName);
            config.set(base + ".x", loc.getX());
            config.set(base + ".y", loc.getY());
            config.set(base + ".z", loc.getZ());
        }

        for (Map.Entry<UUID, Map<PortalType, Location>> playerEntry : farmAnchors.entrySet()) {
            UUID ownerId = playerEntry.getKey();
            for (Map.Entry<PortalType, Location> claimEntry : playerEntry.getValue().entrySet()) {
                PortalType type = claimEntry.getKey();
                Location anchor = claimEntry.getValue();
                if (anchor == null) {
                    continue;
                }

                String base = "players." + ownerId + "." + type.name();
                String worldName = anchor.getWorld() != null ? anchor.getWorld().getName() : type.worldName;
                config.set(base + ".world", worldName);
                config.set(base + ".x", anchor.getX());
                config.set(base + ".y", anchor.getY());
                config.set(base + ".z", anchor.getZ());

                FarmZone zone = farmZones.get(new FarmZoneKey(ownerId, type));
                if (zone != null) {
                    List<String> allowed = new ArrayList<>();
                    for (UUID allowedId : zone.allowedPlayers) {
                        if (!allowedId.equals(ownerId)) {
                            allowed.add(allowedId.toString());
                        }
                    }
                    config.set(base + ".allowed", allowed);
                }
            }
        }

        for (Map.Entry<UUID, Map<PortalType, Location>> playerEntry : farmShelters.entrySet()) {
            UUID ownerId = playerEntry.getKey();
            for (Map.Entry<PortalType, Location> shelterEntry : playerEntry.getValue().entrySet()) {
                PortalType type = shelterEntry.getKey();
                Location shelter = shelterEntry.getValue();
                if (shelter == null) {
                    continue;
                }

                String base = "shelters." + ownerId + "." + type.name();
                String worldName = shelter.getWorld() != null ? shelter.getWorld().getName() : type.worldName;
                config.set(base + ".world", worldName);
                config.set(base + ".x", shelter.getX());
                config.set(base + ".y", shelter.getY());
                config.set(base + ".z", shelter.getZ());
            }
        }

        try {
            config.save(claimsFile);
        } catch (IOException ex) {
            getLogger().severe("Konnte Claims nicht speichern: " + ex.getMessage());
        }
    }

    private void updateBossBar(FarmSession session, boolean unlimited) {
        if (unlimited) {
            session.bossBar.setTitle(color("&aFarmzeit: &fUnbegrenzt (Creative/Spectator)"));
            session.bossBar.setProgress(1.0);
            session.bossBar.setColor(BarColor.GREEN);
            return;
        }

        session.bossBar.setTitle(color("&aFarmzeit: &f" + formatSeconds(session.remainingSeconds)));
        double progress = Math.min(1.0, Math.max(0.0, session.remainingSeconds / 300.0));
        session.bossBar.setProgress(progress);
        if (session.remainingSeconds <= 30) {
            session.bossBar.setColor(BarColor.RED);
        } else if (session.remainingSeconds <= 120) {
            session.bossBar.setColor(BarColor.YELLOW);
        } else {
            session.bossBar.setColor(BarColor.GREEN);
        }
    }

    private void monitorPortals() {
        restorePendingActivePortals();

        List<ActivePortal> toRemove = new ArrayList<>();
        for (ActivePortal portal : activePortals) {
            if (isPortalInteriorActive(portal)) {
                continue;
            }

            if (isPortalFrameIntact(portal)) {
                restoreTrackedPortalInterior(portal);
                continue;
            }

            try {
                revertPortal(portal);
            } catch (IllegalStateException ignored) {
                // If the world is temporarily unavailable, keep the portal tracked and retry later.
                continue;
            }
            toRemove.add(portal);
        }
        activePortals.removeAll(toRemove);
        if (!toRemove.isEmpty()) {
            saveClaimsToDisk();
        }
    }

    private boolean isPortalInteriorActive(ActivePortal portal) {
        try {
            for (PortalLocation location : portal.interiorBlocks) {
                if (location.toBlock().getType() != Material.NETHER_PORTAL) {
                    return false;
                }
            }
            return true;
        } catch (IllegalStateException ex) {
            return false;
        }
    }

    private boolean isPortalFrameIntact(ActivePortal portal) {
        try {
            for (PortalLocation location : portal.frameBlocks) {
                if (location.toBlock().getType() != portal.portalType.frameMaterial) {
                    return false;
                }
            }
            return true;
        } catch (IllegalStateException ex) {
            return false;
        }
    }

    private void restoreTrackedPortalInterior(ActivePortal portal) {
        for (PortalLocation interior : portal.interiorBlocks) {
            Block block = interior.toBlock();
            if (block.getType() != Material.NETHER_PORTAL) {
                block.setType(Material.NETHER_PORTAL, false);
            }
            interiorPortalTypes.put(interior, portal.portalType);
        }
    }

    private String serializePortalLocation(PortalLocation location) {
        World world = Bukkit.getWorld(location.world);
        if (world == null) {
            return null;
        }
        return world.getName() + "," + location.x + "," + location.y + "," + location.z;
    }

    private PortalLocation deserializePortalLocation(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String[] parts = raw.split(",");
        if (parts.length != 4) {
            return null;
        }

        World world = Bukkit.getWorld(parts[0]);
        if (world == null) {
            return null;
        }

        try {
            int x = Integer.parseInt(parts[1]);
            int y = Integer.parseInt(parts[2]);
            int z = Integer.parseInt(parts[3]);
            return new PortalLocation(world.getUID(), x, y, z);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private void restorePendingActivePortals() {
        if (pendingActivePortals.isEmpty()) {
            return;
        }

        List<PendingActivePortal> restored = new ArrayList<>();
        for (PendingActivePortal pending : pendingActivePortals) {
            if (tryRestorePendingActivePortal(pending)) {
                restored.add(pending);
            }
        }
        pendingActivePortals.removeAll(restored);
    }

    private boolean tryRestorePendingActivePortal(PendingActivePortal pending) {
        Set<PortalLocation> frameBlocks = new HashSet<>();
        for (String rawFrame : pending.frameSerialized) {
            PortalLocation parsed = deserializePortalLocation(rawFrame);
            if (parsed == null) {
                return false;
            }
            frameBlocks.add(parsed);
        }

        Set<PortalLocation> interiorBlocks = new HashSet<>();
        for (String rawInterior : pending.interiorSerialized) {
            PortalLocation parsed = deserializePortalLocation(rawInterior);
            if (parsed == null) {
                return false;
            }
            interiorBlocks.add(parsed);
        }
        if (interiorBlocks.isEmpty()) {
            return false;
        }

        for (PortalLocation interior : interiorBlocks) {
            Block block = interior.toBlock();
            if (block.getType() != Material.NETHER_PORTAL) {
                block.setType(Material.NETHER_PORTAL, false);
            }
        }

        registerActivePortal(frameBlocks, interiorBlocks, pending.portalType);
        return true;
    }

    private void registerActivePortal(Set<PortalLocation> frameBlocks, Set<PortalLocation> interiorBlocks, PortalType portalType) {
        ActivePortal loaded = new ActivePortal(frameBlocks, interiorBlocks, portalType);
        activePortals.add(loaded);
        for (PortalLocation interior : interiorBlocks) {
            interiorPortalTypes.put(interior, portalType);
        }
    }

    private void monitorResets() {
        if (resetRunning) {
            return;
        }

        LocalDateTime now = LocalDateTime.now(zoneId).withSecond(0).withNano(0);
        for (PortalType type : PortalType.values()) {
            LocalDateTime next = nextResets.get(type);
            if (next == null) {
                next = computeNextReset(type, now);
                nextResets.put(type, next);
            }
            if (!now.isBefore(next)) {
                runFarmReset(type, now);
            }
        }
    }

    private void runFarmReset(PortalType type, LocalDateTime now) {
        resetRunning = true;
        try {
            String worldName = type.worldName;
            World world = Bukkit.getWorld(worldName);
            if (world != null) {
                for (Player player : new ArrayList<>(world.getPlayers())) {
                    endSession(player, true, "&eFarmWorld wurde zurückgesetzt.");
                }

                if (!Bukkit.unloadWorld(world, false)) {
                    getLogger().warning("FarmWorld konnte nicht entladen werden: " + worldName);
                    return;
                }
            }

            deleteWorldFolder(worldName);
            createWorld(type);
            for (Map<PortalType, Location> perPlayer : farmAnchors.values()) {
                perPlayer.remove(type);
            }
            for (Map<PortalType, Location> perPlayer : farmShelters.values()) {
                perPlayer.remove(type);
            }
            farmZones.entrySet().removeIf(entry -> entry.getKey().portalType == type);
            playerZonePresence.clear();
            saveClaimsToDisk();

            LocalDateTime newNext = computeNextReset(type, now.plusMinutes(1));
            nextResets.put(type, newNext);

            Bukkit.broadcastMessage(color("&6[FarmWorld] &e" + type.displayName + " wurde zurückgesetzt."));
            getLogger().info(type.displayName + " reset abgeschlossen. Nächster Reset: " + RESET_FORMAT.format(newNext));
        } finally {
            resetRunning = false;
        }
    }

    private void deleteWorldFolder(String worldName) {
        Path worldPath = Bukkit.getWorldContainer().toPath().resolve(worldName);
        if (!Files.exists(worldPath)) {
            return;
        }

        try {
            Files.walkFileTree(worldPath, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    if (exc != null) {
                        throw exc;
                    }
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ex) {
            throw new IllegalStateException("Konnte Weltordner nicht löschen: " + worldName, ex);
        }
    }

    private ActivePortal activatePortal(PortalShape shape) {
        for (PortalLocation interior : shape.interiorBlocks) {
            Block block = interior.toBlock();
            BlockData data = Bukkit.createBlockData(Material.NETHER_PORTAL);

            if (data instanceof Orientable orientable) {
                orientable.setAxis(shape.axis == Axis.X ? org.bukkit.Axis.X : org.bukkit.Axis.Z);
                block.setBlockData(orientable, false);
            } else {
                block.setType(Material.NETHER_PORTAL, false);
            }

            interiorPortalTypes.put(interior, shape.portalType);
        }

        return new ActivePortal(shape.frameBlocks, shape.interiorBlocks, shape.portalType);
    }

    private void revertPortal(ActivePortal portal) {
        for (PortalLocation interior : portal.interiorBlocks) {
            Block block = interior.toBlock();
            if (block.getType() == Material.NETHER_PORTAL) {
                block.setType(Material.AIR, false);
            }
            interiorPortalTypes.remove(interior);
        }

    }

    private PortalType findPortalTypeAt(Location origin) {
        PortalLocation[] checks = new PortalLocation[]{
                new PortalLocation(origin),
                new PortalLocation(origin.clone().add(0, 1, 0)),
                new PortalLocation(origin.clone().add(0, -1, 0))
        };

        for (PortalLocation location : checks) {
            PortalType type = interiorPortalTypes.get(location);
            if (type != null) {
                return type;
            }
        }

        return detectPortalTypeFromFrame(origin);
    }

    private PortalType findPortalTypeNear(Location origin, int radius) {
        World world = origin.getWorld();
        if (world == null) {
            return null;
        }
        for (int x = -radius; x <= radius; x++) {
            for (int y = -1; y <= 1; y++) {
                for (int z = -radius; z <= radius; z++) {
                    Location check = origin.clone().add(x, y, z);
                    PortalType type = interiorPortalTypes.get(new PortalLocation(check));
                    if (type != null) {
                        return type;
                    }
                }
            }
        }
        return detectPortalTypeFromFrame(origin);
    }

    private PortalType detectPortalTypeFromFrame(Location origin) {
        World world = origin.getWorld();
        if (world == null) {
            return null;
        }

        Location[] portalCandidates = new Location[]{
                origin,
                origin.clone().add(0, 1, 0),
                origin.clone().add(0, -1, 0)
        };

        for (Location candidate : portalCandidates) {
            Block portalBlock = world.getBlockAt(candidate);
            if (portalBlock.getType() != Material.NETHER_PORTAL) {
                continue;
            }

            Set<PortalType> foundTypes = new HashSet<>();
            int px = portalBlock.getX();
            int py = portalBlock.getY();
            int pz = portalBlock.getZ();

            for (int dx = -2; dx <= 2; dx++) {
                for (int dy = -3; dy <= 3; dy++) {
                    for (int dz = -2; dz <= 2; dz++) {
                        int x = px + dx;
                        int y = py + dy;
                        int z = pz + dz;
                        Material material = world.getBlockAt(x, y, z).getType();
                        PortalType type = PortalType.fromFrameMaterial(material);
                        if (type == null) {
                            continue;
                        }
                        if (!isAdjacentToPortal(world, x, y, z)) {
                            continue;
                        }
                        foundTypes.add(type);
                    }
                }
            }

            if (foundTypes.size() == 1) {
                return foundTypes.iterator().next();
            }
        }

        return null;
    }

    private boolean isAdjacentToPortal(World world, int x, int y, int z) {
        return world.getBlockAt(x + 1, y, z).getType() == Material.NETHER_PORTAL
                || world.getBlockAt(x - 1, y, z).getType() == Material.NETHER_PORTAL
                || world.getBlockAt(x, y + 1, z).getType() == Material.NETHER_PORTAL
                || world.getBlockAt(x, y - 1, z).getType() == Material.NETHER_PORTAL
                || world.getBlockAt(x, y, z + 1).getType() == Material.NETHER_PORTAL
                || world.getBlockAt(x, y, z - 1).getType() == Material.NETHER_PORTAL;
    }

    private boolean isAlreadyTracked(PortalShape shape) {
        Set<PortalLocation> currentFrame = new HashSet<>(shape.frameBlocks);
        for (ActivePortal portal : activePortals) {
            if (portal.frameBlocks.equals(currentFrame)) {
                return true;
            }
        }
        return false;
    }

    private PortalShape findPortalShape(Block igniteTarget) {
        PortalShape xShape = tryFind(igniteTarget, Axis.X);
        if (xShape != null) {
            return xShape;
        }
        return tryFind(igniteTarget, Axis.Z);
    }

    private PortalShape tryFind(Block center, Axis axis) {
        World world = center.getWorld();
        int x = center.getX();
        int y = center.getY();
        int z = center.getZ();

        int dx = axis == Axis.X ? 1 : 0;
        int dz = axis == Axis.Z ? 1 : 0;

        int left = 0;
        while (isIgnitableInterior(world.getBlockAt(x - dx * (left + 1), y, z - dz * (left + 1)).getType())) {
            left++;
            if (left > 21) {
                return null;
            }
        }

        int right = 0;
        while (isIgnitableInterior(world.getBlockAt(x + dx * (right + 1), y, z + dz * (right + 1)).getType())) {
            right++;
            if (right > 21) {
                return null;
            }
        }

        int innerWidth = left + right + 1;
        if (innerWidth < 2 || innerWidth > 21) {
            return null;
        }

        int minInnerOffset = -left;
        int maxInnerOffset = right;

        int down = 0;
        while (rowIsInterior(world, x, y - (down + 1), z, axis, minInnerOffset, maxInnerOffset)) {
            down++;
            if (down > 21) {
                return null;
            }
        }

        int up = 0;
        while (rowIsInterior(world, x, y + (up + 1), z, axis, minInnerOffset, maxInnerOffset)) {
            up++;
            if (up > 21) {
                return null;
            }
        }

        int innerHeight = down + up + 1;
        if (innerHeight < 3 || innerHeight > 21) {
            return null;
        }

        int bottomY = y - down - 1;
        int topY = y + up + 1;

        int leftFrameOffset = minInnerOffset - 1;
        int rightFrameOffset = maxInnerOffset + 1;

        PortalType portalType = null;
        Set<PortalLocation> frame = new HashSet<>();
        Set<PortalLocation> interior = new HashSet<>();

        for (int yy = bottomY; yy <= topY; yy++) {
            for (int off = leftFrameOffset; off <= rightFrameOffset; off++) {
                int bx = x + dx * off;
                int bz = z + dz * off;
                Block block = world.getBlockAt(bx, yy, bz);

                boolean border = yy == bottomY || yy == topY || off == leftFrameOffset || off == rightFrameOffset;
                if (border) {
                    PortalType blockType = PortalType.fromFrameMaterial(block.getType());
                    if (blockType == null) {
                        return null;
                    }
                    if (portalType == null) {
                        portalType = blockType;
                    } else if (portalType != blockType) {
                        return null;
                    }
                    frame.add(new PortalLocation(block.getLocation()));
                } else {
                    if (!isIgnitableInterior(block.getType())) {
                        return null;
                    }
                    interior.add(new PortalLocation(block.getLocation()));
                }
            }
        }

        if (portalType == null) {
            return null;
        }

        return new PortalShape(axis, portalType, frame, interior);
    }

    private boolean rowIsInterior(World world, int centerX, int y, int centerZ, Axis axis, int minOffset, int maxOffset) {
        int dx = axis == Axis.X ? 1 : 0;
        int dz = axis == Axis.Z ? 1 : 0;

        for (int off = minOffset; off <= maxOffset; off++) {
            Material material = world.getBlockAt(centerX + dx * off, y, centerZ + dz * off).getType();
            if (!isIgnitableInterior(material)) {
                return false;
            }
        }

        return true;
    }

    private boolean isIgnitableInterior(Material material) {
        return material == Material.AIR
                || material == Material.CAVE_AIR
                || material == Material.FIRE
                || material == Material.NETHER_PORTAL;
    }

    private void consumeIgnitionItem(Player player, ItemStack item) {
        if (isUnlimitedFarmMode(player)) {
            return;
        }

        if (item.getType() == Material.FIRE_CHARGE) {
            item.setAmount(Math.max(0, item.getAmount() - 1));
            return;
        }

        if (item.getType() == Material.FLINT_AND_STEEL) {
            ItemMeta meta = item.getItemMeta();
            if (!(meta instanceof Damageable damageable)) {
                return;
            }

            damageable.setDamage(damageable.getDamage() + 1);
            item.setItemMeta((ItemMeta) damageable);

            if (damageable.getDamage() >= item.getType().getMaxDurability()) {
                item.setAmount(0);
            }
        }
    }

    private World getOrCreateWorld(PortalType type) {
        World world = Bukkit.getWorld(type.worldName);
        if (world != null) {
            applyFarmWorldSettings(world);
            return world;
        }
        return createWorld(type);
    }

    private World createWorld(PortalType type) {
        WorldCreator creator = new WorldCreator(type.worldName).environment(type.environment);
        World world = Bukkit.createWorld(creator);
        if (world == null) {
            throw new IllegalStateException("Konnte FarmWorld nicht erstellen: " + type.worldName);
        }
        applyFarmWorldSettings(world);
        return world;
    }

    private void applyFarmWorldSettings(World world) {
        world.getWorldBorder().setCenter(0.0, 0.0);
        world.getWorldBorder().setSize(FARM_BORDER_DIAMETER);
    }

    private Location resolveFarmEntryLocation(Player player, PortalType type, World world) {
        UUID playerId = player.getUniqueId();
        Map<PortalType, Location> perPlayer = farmAnchors.get(playerId);
        Location storedAnchor = perPlayer != null ? perPlayer.get(type) : null;

        if (storedAnchor != null) {
            Location shelter = getShelterLocation(playerId, type, world);
            if (shelter != null) {
                Location insideShelter = buildSpawnShelter(shelter, type);
                if (insideShelter != null) {
                    return insideShelter;
                }
            }

            Location forWorld = storedAnchor.clone();
            if (forWorld.getWorld() == null || !world.getName().equals(forWorld.getWorld().getName())) {
                forWorld.setWorld(world);
            }
            Location safeStored = resolveClaimEntryLocation(world, forWorld);
            if (safeStored != null) {
                if (shouldBuildShelter(safeStored)) {
                    Location insideShelter = buildSpawnShelter(safeStored, type);
                    if (insideShelter != null) {
                        setShelterLocation(playerId, type, insideShelter);
                        return insideShelter;
                    }
                    return safeStored;
                }
                return safeStored;
            }

            Location insideShelter = buildSpawnShelter(forWorld, type);
            if (insideShelter != null) {
                setShelterLocation(playerId, type, insideShelter);
                return insideShelter;
            }

            return forWorld;
        }

        return findRandomShelterEntryLocation(world, playerId, type);
    }

    private Location findRandomShelterEntryLocation(World world, UUID playerId, PortalType type) {
        Location randomSafe = findRandomSafeLocation(world, playerId);
        if (randomSafe == null) {
            return null;
        }
        if (shouldBuildShelter(randomSafe)) {
            Location insideShelter = buildSpawnShelter(randomSafe, type);
            if (insideShelter != null) {
                setShelterLocation(playerId, type, insideShelter);
                return insideShelter;
            }
            return null;
        }
        return randomSafe;
    }

    private void resolveFarmEntryLocationAsync(Player player, PortalType type, World world, java.util.function.Consumer<Location> callback) {
        UUID playerId = player.getUniqueId();
        Map<PortalType, Location> perPlayer = farmAnchors.get(playerId);
        Location storedAnchor = perPlayer != null ? perPlayer.get(type) : null;

        if (storedAnchor != null) {
            Location shelter = getShelterLocation(playerId, type, world);
            if (shelter != null) {
                buildSpawnShelterAsync(shelter, type, insideShelter -> {
                    if (insideShelter != null) {
                        callback.accept(insideShelter);
                        return;
                    }
                    resolveStoredOrRandomEntryLocation(player, world, type, storedAnchor, callback);
                });
                return;
            }

            resolveStoredOrRandomEntryLocation(player, world, type, storedAnchor, callback);
            return;
        }

        resolveRandomEntryLocation(player, world, type, 0, callback);
    }

    private void resolveStoredOrRandomEntryLocation(Player player, World world, PortalType type, Location storedAnchor, java.util.function.Consumer<Location> callback) {
        UUID playerId = player.getUniqueId();
        Location forWorld = storedAnchor.clone();
        if (forWorld.getWorld() == null || !world.getName().equals(forWorld.getWorld().getName())) {
            forWorld.setWorld(world);
        }

        resolveClaimEntryLocationAsync(world, forWorld, safeStored -> {
            if (safeStored == null) {
                buildSpawnShelterAsync(forWorld, type, insideShelter -> {
                    if (insideShelter != null) {
                        setShelterLocation(playerId, type, insideShelter);
                        callback.accept(insideShelter);
                        return;
                    }
                    callback.accept(forWorld);
                });
                return;
            }
            if (!shouldBuildShelter(safeStored)) {
                callback.accept(safeStored);
                return;
            }
            buildSpawnShelterAsync(safeStored, type, insideShelter -> {
                if (insideShelter != null) {
                    setShelterLocation(playerId, type, insideShelter);
                    callback.accept(insideShelter);
                    return;
                }
                callback.accept(safeStored);
            });
        });
    }

    private Location resolveClaimEntryLocation(World world, Location storedAnchor) {
        if (isSafeLocation(storedAnchor)) {
            return storedAnchor.clone();
        }

        Location sameColumn = findSafeAtXZ(world, storedAnchor.getBlockX(), storedAnchor.getBlockZ());
        if (sameColumn != null) {
            return sameColumn;
        }

        return findSafeNear(world, storedAnchor, 24);
    }

    private void resolveClaimEntryLocationAsync(World world, Location storedAnchor, java.util.function.Consumer<Location> callback) {
        ensureChunkLoadedAsync(world, storedAnchor.getBlockX(), storedAnchor.getBlockZ(), () -> {
            if (isSafeLocation(storedAnchor)) {
                callback.accept(storedAnchor.clone());
                return;
            }

            findSafeAtXZAsync(world, storedAnchor.getBlockX(), storedAnchor.getBlockZ(), sameColumn -> {
                if (sameColumn != null) {
                    callback.accept(sameColumn);
                    return;
                }

                findSafeNearAsync(world, storedAnchor, 24, callback);
            });
        });
    }

    private void resolveRandomEntryLocation(Player player, World world, PortalType type, int searchRound, java.util.function.Consumer<Location> callback) {
        UUID playerId = player.getUniqueId();
        if (searchRound > 0) {
            notifyEntrySearchProgress(player, type, searchRound);
        }
        findRandomSafeLocationAsync(world, playerId, randomSafe -> {
            if (randomSafe == null) {
                resolveRandomEntryLocation(player, world, type, searchRound + 1, callback);
                return;
            }
            if (!shouldBuildShelter(randomSafe)) {
                callback.accept(randomSafe);
                return;
            }
            buildSpawnShelterAsync(randomSafe, type, insideShelter -> {
                if (insideShelter != null) {
                    setShelterLocation(playerId, type, insideShelter);
                    callback.accept(insideShelter);
                    return;
                }
                resolveRandomEntryLocation(player, world, type, searchRound + 1, callback);
            });
        });
    }

    private Location findRandomSafeLocation(World world, UUID playerId) {
        double radius = (FARM_BORDER_DIAMETER / 2.0) - SAFE_BORDER_MARGIN;
        for (int i = 0; i < 80; i++) {
            int x = ThreadLocalRandom.current().nextInt((int) -radius, (int) radius + 1);
            int z = ThreadLocalRandom.current().nextInt((int) -radius, (int) radius + 1);
            Location candidate = findSafeAtXZ(world, x, z);
            if (candidate != null && canSpawnAtRandom(playerId, candidate)) {
                return candidate;
            }
        }
        Location spawn = world.getSpawnLocation().clone().add(0.5, 1.0, 0.5);
        if (canSpawnAtRandom(playerId, spawn)) {
            return spawn;
        }
        Location fallback = findSafeNear(world, spawn, 128);
        return fallback != null ? fallback : world.getSpawnLocation().clone().add(0.5, 1.0, 0.5);
    }

    private void findRandomSafeLocationAsync(World world, UUID playerId, java.util.function.Consumer<Location> callback) {
        double radius = (FARM_BORDER_DIAMETER / 2.0) - SAFE_BORDER_MARGIN;
        findRandomSafeLocationAsync(world, playerId, callback, radius, 0);
    }

    private void findRandomSafeLocationAsync(World world, UUID playerId, java.util.function.Consumer<Location> callback, double radius, int attempt) {
        if (attempt >= 80) {
            callback.accept(null);
            return;
        }

        int x = ThreadLocalRandom.current().nextInt((int) -radius, (int) radius + 1);
        int z = ThreadLocalRandom.current().nextInt((int) -radius, (int) radius + 1);
        if (!isInsideFarmBorder(x, z)) {
            findRandomSafeLocationAsync(world, playerId, callback, radius, attempt + 1);
            return;
        }

        findSafeAtXZAsync(world, x, z, candidate -> {
            if (candidate != null && canSpawnAtRandom(playerId, candidate)) {
                callback.accept(candidate);
                return;
            }
            findRandomSafeLocationAsync(world, playerId, callback, radius, attempt + 1);
        });
    }

    private void notifyEntrySearchProgress(Player player, PortalType type, int searchRound) {
        if (!player.isOnline()) {
            return;
        }
        if (searchRound == 1) {
            player.sendMessage(color("&eSuche weiter nach einem sicheren Eintrittspunkt in " + type.displayName + "..."));
            return;
        }
        if (searchRound % 3 == 0) {
            player.sendMessage(color("&eNoch kein sicherer Platz gefunden. Weitere Suchrunde " + searchRound + " läuft..."));
        }
    }

    private Location findSafeNear(World world, Location center, int range) {
        if (isSafeLocation(center)) {
            return center.clone();
        }
        for (int i = 0; i < 40; i++) {
            int x = center.getBlockX() + ThreadLocalRandom.current().nextInt(-range, range + 1);
            int z = center.getBlockZ() + ThreadLocalRandom.current().nextInt(-range, range + 1);
            if (!isInsideFarmBorder(x, z)) {
                continue;
            }
            Location candidate = findSafeAtXZ(world, x, z);
            if (candidate != null) {
                return candidate;
            }
        }
        return null;
    }

    private void findSafeNearAsync(World world, Location center, int range, java.util.function.Consumer<Location> callback) {
        ensureChunkLoadedAsync(world, center.getBlockX(), center.getBlockZ(), () -> {
            if (isSafeLocation(center)) {
                callback.accept(center.clone());
                return;
            }
            findSafeNearAsync(world, center, range, 0, callback);
        });
    }

    private void findSafeNearAsync(World world, Location center, int range, int attempt, java.util.function.Consumer<Location> callback) {
        if (attempt >= 40) {
            callback.accept(null);
            return;
        }

        int x = center.getBlockX() + ThreadLocalRandom.current().nextInt(-range, range + 1);
        int z = center.getBlockZ() + ThreadLocalRandom.current().nextInt(-range, range + 1);
        if (!isInsideFarmBorder(x, z)) {
            findSafeNearAsync(world, center, range, attempt + 1, callback);
            return;
        }

        findSafeAtXZAsync(world, x, z, candidate -> {
            if (candidate != null) {
                callback.accept(candidate);
                return;
            }
            findSafeNearAsync(world, center, range, attempt + 1, callback);
        });
    }

    private Location findSafeAtXZ(World world, int x, int z) {
        int chunkX = x >> 4;
        int chunkZ = z >> 4;
        if (!world.isChunkLoaded(chunkX, chunkZ)) {
            return null;
        }
        for (int y = world.getMaxHeight() - 2; y > world.getMinHeight() + 1; y--) {
            Location loc = new Location(world, x + 0.5, y, z + 0.5);
            if (isSafeLocation(loc)) {
                return loc;
            }
        }
        return null;
    }

    private void findSafeAtXZAsync(World world, int x, int z, java.util.function.Consumer<Location> callback) {
        ensureChunkLoadedAsync(world, x, z, () -> callback.accept(findSafeAtXZ(world, x, z)));
    }

    private boolean isSafeLocation(Location location) {
        World world = location.getWorld();
        if (world == null) {
            return false;
        }

        int x = location.getBlockX();
        int y = location.getBlockY();
        int z = location.getBlockZ();
        if (!isInsideFarmBorder(x, z)) {
            return false;
        }
        if (!isChunkLoadedAtBlock(world, x, z)) {
            return false;
        }

        Block feet = world.getBlockAt(x, y, z);
        Block head = world.getBlockAt(x, y + 1, z);
        Block below = world.getBlockAt(x, y - 1, z);

        if (!feet.isPassable() || !head.isPassable()) {
            return false;
        }
        if (feet.isLiquid() || head.isLiquid()) {
            return false;
        }
        if (!below.getType().isSolid()) {
            return false;
        }
        if (!isLikelySurface(world, x, y, z)) {
            return false;
        }
        if (hasNearbySpawner(world, x, y, z, SPAWNER_SAFE_RADIUS)) {
            return false;
        }

        Material belowType = below.getType();
        if (world.getEnvironment() == World.Environment.NETHER && belowType == Material.BEDROCK) {
            return false;
        }

        return belowType != Material.LAVA
                && belowType != Material.MAGMA_BLOCK
                && belowType != Material.CAMPFIRE
                && belowType != Material.SOUL_CAMPFIRE
                && belowType != Material.FIRE
                && belowType != Material.SOUL_FIRE
                && belowType != Material.CACTUS;
    }

    private boolean isLikelySurface(World world, int x, int y, int z) {
        if (world.getEnvironment() != World.Environment.NORMAL) {
            return true;
        }
        if (!isChunkLoadedAtBlock(world, x, z)) {
            return false;
        }
        int highestSolid = world.getHighestBlockYAt(x, z);
        return (y - 1) >= (highestSolid - 1);
    }

    private boolean hasNearbySpawner(World world, int x, int y, int z, int radius) {
        int minY = Math.max(world.getMinHeight(), y - radius);
        int maxY = Math.min(world.getMaxHeight() - 1, y + radius);
        int radiusSquared = radius * radius;
        for (int by = minY; by <= maxY; by++) {
            for (int bx = x - radius; bx <= x + radius; bx++) {
                for (int bz = z - radius; bz <= z + radius; bz++) {
                    int dx = bx - x;
                    int dy = by - y;
                    int dz = bz - z;
                    if ((dx * dx) + (dy * dy) + (dz * dz) > radiusSquared) {
                        continue;
                    }
                    if (!isChunkLoadedAtBlock(world, bx, bz)) {
                        continue;
                    }
                    Block block = world.getBlockAt(bx, by, bz);
                    if (block.getState() instanceof CreatureSpawner) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean isChunkLoadedAtBlock(World world, int blockX, int blockZ) {
        return world.isChunkLoaded(blockX >> 4, blockZ >> 4);
    }

    private boolean shouldBuildShelter(Location location) {
        World world = location.getWorld();
        return world != null;
    }

    private Location buildSpawnShelter(Location location, PortalType type) {
        World world = location.getWorld();
        if (world == null) {
            return null;
        }

        ShelterPalette palette = ShelterPalette.forPortalType(type);

        int cx = location.getBlockX();
        int cz = location.getBlockZ();
        int floorY = Math.max(world.getMinHeight() + 1, location.getBlockY() - 1);
        int roofY = floorY + 4;
        int minX = cx - 2;
        int maxX = cx + 2;
        int minZ = cz - 2;
        int maxZ = cz + 2;

        if (!areChunksLoadedForArea(world, minX, maxX, minZ, maxZ)) {
            return null;
        }

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                world.getBlockAt(x, floorY, z).setType(palette.shellMaterial, false);
                world.getBlockAt(x, roofY, z).setType(palette.shellMaterial, false);
                for (int y = floorY + 1; y < roofY; y++) {
                    boolean wall = (x == minX || x == maxX || z == minZ || z == maxZ);
                    Block block = world.getBlockAt(x, y, z);
                    block.setType(wall ? palette.shellMaterial : Material.AIR, false);
                }
            }
        }

        world.getBlockAt(cx, floorY + 1, cz).setType(palette.lightMaterial, false);
        placeDoor(world, cx, floorY + 1, minZ, BlockFace.NORTH, palette.doorMaterial);
        placeDoor(world, cx, floorY + 1, maxZ, BlockFace.SOUTH, palette.doorMaterial);
        placeDoor(world, minX, floorY + 1, cz, BlockFace.WEST, palette.doorMaterial);
        placeDoor(world, maxX, floorY + 1, cz, BlockFace.EAST, palette.doorMaterial);
        return new Location(world, cx + 0.5, floorY + 1.0, cz + 0.5);
    }

    private void buildSpawnShelterAsync(Location location, PortalType type, java.util.function.Consumer<Location> callback) {
        World world = location.getWorld();
        if (world == null) {
            callback.accept(null);
            return;
        }

        int cx = location.getBlockX();
        int cz = location.getBlockZ();
        loadAreaChunksAsync(world, cx - 2, cx + 2, cz - 2, cz + 2, () ->
                callback.accept(buildSpawnShelter(location, type)));
    }

    private void ensureChunkLoadedAsync(World world, int blockX, int blockZ, Runnable callback) {
        int chunkX = blockX >> 4;
        int chunkZ = blockZ >> 4;
        if (world.isChunkLoaded(chunkX, chunkZ)) {
            Bukkit.getScheduler().runTask(this, callback);
            return;
        }
        world.getChunkAtAsync(chunkX, chunkZ, true, true)
                .whenComplete((chunk, throwable) -> Bukkit.getScheduler().runTask(this, () -> {
                    if (throwable != null) {
                        getLogger().warning("Asynchrones Chunk-Laden fehlgeschlagen bei " + world.getName()
                                + " [" + chunkX + "," + chunkZ + "]: " + throwable.getMessage());
                    }
                    callback.run();
                }));
    }

    private void loadAreaChunksAsync(World world, int minX, int maxX, int minZ, int maxZ, Runnable callback) {
        List<CompletableFuture<?>> futures = new ArrayList<>();
        for (int chunkX = minX >> 4; chunkX <= maxX >> 4; chunkX++) {
            for (int chunkZ = minZ >> 4; chunkZ <= maxZ >> 4; chunkZ++) {
                if (world.isChunkLoaded(chunkX, chunkZ)) {
                    continue;
                }
                futures.add(world.getChunkAtAsync(chunkX, chunkZ, true, true));
            }
        }

        if (futures.isEmpty()) {
            Bukkit.getScheduler().runTask(this, callback);
            return;
        }

        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                .whenComplete((ignored, throwable) -> Bukkit.getScheduler().runTask(this, () -> {
                    if (throwable != null) {
                        getLogger().warning("Asynchrones Laden eines Shelter-Bereichs fehlgeschlagen in "
                                + world.getName() + ": " + throwable.getMessage());
                    }
                    callback.run();
                }));
    }

    private boolean areChunksLoadedForArea(World world, int minX, int maxX, int minZ, int maxZ) {
        for (int chunkX = minX >> 4; chunkX <= maxX >> 4; chunkX++) {
            for (int chunkZ = minZ >> 4; chunkZ <= maxZ >> 4; chunkZ++) {
                if (!world.isChunkLoaded(chunkX, chunkZ)) {
                    return false;
                }
            }
        }
        return true;
    }

    private void placeDoor(World world, int x, int y, int z, BlockFace facing, Material doorMaterial) {
        BlockFace rotated = facing.getOppositeFace();
        Block lower = world.getBlockAt(x, y, z);
        Block upper = world.getBlockAt(x, y + 1, z);
        lower.setType(doorMaterial, false);
        upper.setType(doorMaterial, false);

        BlockData lowerData = Bukkit.createBlockData(doorMaterial);
        if (lowerData instanceof Door lowerDoor) {
            lowerDoor.setFacing(rotated);
            lowerDoor.setHalf(Bisected.Half.BOTTOM);
            lowerDoor.setOpen(false);
            lower.setBlockData(lowerDoor, false);
        }

        BlockData upperData = Bukkit.createBlockData(doorMaterial);
        if (upperData instanceof Door upperDoor) {
            upperDoor.setFacing(rotated);
            upperDoor.setHalf(Bisected.Half.TOP);
            upperDoor.setOpen(false);
            upper.setBlockData(upperDoor, false);
        }
    }

    private Location findSafeReturnLocationNear(Location center) {
        World world = center.getWorld();
        if (world == null) {
            return getMainWorld().getSpawnLocation().clone().add(0.5, 0.0, 0.5);
        }

        int baseX = center.getBlockX();
        int baseY = center.getBlockY();
        int baseZ = center.getBlockZ();

        if (isSafeReturnStand(world, baseX, baseY, baseZ)) {
            return new Location(world, baseX + 0.5, baseY, baseZ + 0.5, center.getYaw(), center.getPitch());
        }

        for (int radius = 1; radius <= 4; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
                        continue;
                    }
                    for (int dy = -1; dy <= 2; dy++) {
                        int x = baseX + dx;
                        int y = baseY + dy;
                        int z = baseZ + dz;
                        if (!isSafeReturnStand(world, x, y, z)) {
                            continue;
                        }
                        return new Location(world, x + 0.5, y, z + 0.5, center.getYaw(), center.getPitch());
                    }
                }
            }
        }

        return center.clone().add(1.5, 0.0, 0.5);
    }

    private boolean isSafeReturnStand(World world, int x, int y, int z) {
        if (y <= world.getMinHeight() + 1 || y >= world.getMaxHeight() - 2) {
            return false;
        }
        Block feet = world.getBlockAt(x, y, z);
        Block head = world.getBlockAt(x, y + 1, z);
        Block below = world.getBlockAt(x, y - 1, z);

        if (feet.getType() == Material.NETHER_PORTAL || head.getType() == Material.NETHER_PORTAL) {
            return false;
        }
        if (!feet.isPassable() || !head.isPassable()) {
            return false;
        }
        if (feet.isLiquid() || head.isLiquid()) {
            return false;
        }
        if (!below.getType().isSolid()) {
            return false;
        }

        Material belowType = below.getType();
        return belowType != Material.LAVA
                && belowType != Material.MAGMA_BLOCK
                && belowType != Material.CAMPFIRE
                && belowType != Material.SOUL_CAMPFIRE
                && belowType != Material.FIRE
                && belowType != Material.SOUL_FIRE
                && belowType != Material.CACTUS;
    }

    private boolean isInsideFarmBorder(int x, int z) {
        double half = FARM_BORDER_DIAMETER / 2.0;
        return Math.abs(x) <= half - SAFE_BORDER_MARGIN && Math.abs(z) <= half - SAFE_BORDER_MARGIN;
    }

    private boolean canSpawnAtRandom(UUID playerId, Location location) {
        FarmZone zone = findZoneAt(location);
        if (zone == null) {
            return true;
        }
        return zone.owner.equals(playerId);
    }

    private void handleZonePresenceChange(Player player, Location to) {
        FarmZone zone = findZoneAt(to);
        FarmZoneKey newKey = zone == null ? null : zone.key;
        FarmZoneKey oldKey = playerZonePresence.get(player.getUniqueId());

        if (Objects.equals(oldKey, newKey)) {
            return;
        }

        if (newKey == null) {
            playerZonePresence.remove(player.getUniqueId());
            return;
        }

        playerZonePresence.put(player.getUniqueId(), newKey);
        String ownerName = zone.ownerName;
        if (ownerName == null || ownerName.isBlank()) {
            ownerName = zone.owner.toString().substring(0, 8);
        }
        player.sendMessage(color("&eDu betrittst den Claim von " + ownerName + "."));
    }

    private void renderZoneParticles() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            FarmZone zone = findZoneAt(player.getLocation());
            if (zone == null || player.getWorld() == null) {
                continue;
            }
            renderZoneBorder(player, zone);
        }
    }

    private void renderZoneBorder(Player viewer, FarmZone zone) {
        World world = viewer.getWorld();
        if (!world.getName().equals(zone.worldName)) {
            return;
        }

        int minX = (zone.centerChunkX - FarmZone.RADIUS_CHUNKS) * 16;
        int maxX = (zone.centerChunkX + FarmZone.RADIUS_CHUNKS + 1) * 16;
        int minZ = (zone.centerChunkZ - FarmZone.RADIUS_CHUNKS) * 16;
        int maxZ = (zone.centerChunkZ + FarmZone.RADIUS_CHUNKS + 1) * 16;
        double y = viewer.getLocation().getY() + 0.2;

        for (int x = minX; x <= maxX; x += 4) {
            world.spawnParticle(Particle.END_ROD, x + 0.5, y, minZ + 0.5, 1, 0, 0, 0, 0, null, true);
            world.spawnParticle(Particle.END_ROD, x + 0.5, y, maxZ + 0.5, 1, 0, 0, 0, 0, null, true);
        }
        for (int z = minZ; z <= maxZ; z += 4) {
            world.spawnParticle(Particle.END_ROD, minX + 0.5, y, z + 0.5, 1, 0, 0, 0, 0, null, true);
            world.spawnParticle(Particle.END_ROD, maxX + 0.5, y, z + 0.5, 1, 0, 0, 0, 0, null, true);
        }
    }

    private FarmZone findZoneAt(Location location) {
        if (location == null || location.getWorld() == null) {
            return null;
        }
        return findZoneByChunk(location.getWorld().getName(), location.getBlockX() >> 4, location.getBlockZ() >> 4);
    }

    private FarmZone findZoneByChunk(String worldName, int chunkX, int chunkZ) {
        for (FarmZone zone : farmZones.values()) {
            if (!zone.worldName.equals(worldName)) {
                continue;
            }
            if (zone.containsChunk(chunkX, chunkZ)) {
                return zone;
            }
        }
        return null;
    }

    private boolean canUseZone(UUID playerId, FarmZone zone) {
        return zone.owner.equals(playerId) || zone.allowedPlayers.contains(playerId);
    }

    private boolean isFarmWorld(String worldName) {
        for (PortalType type : PortalType.values()) {
            if (type.worldName.equals(worldName)) {
                return true;
            }
        }
        return false;
    }

    private World getMainWorld() {
        return Bukkit.getWorlds().get(0);
    }

    private LocalDateTime computeNextReset(PortalType type, LocalDateTime fromExclusive) {
        if (type.resetRule == ResetRule.MONTHLY_LAST_SUNDAY_18) {
            return nextMonthlyLastSunday18(fromExclusive);
        }
        return nextQuarterlyLastSunday18(fromExclusive);
    }

    private LocalDateTime nextMonthlyLastSunday18(LocalDateTime fromExclusive) {
        YearMonth cursor = YearMonth.from(fromExclusive);
        while (true) {
            LocalDateTime candidate = lastSundayAt18(cursor);
            if (candidate.isAfter(fromExclusive)) {
                return candidate;
            }
            cursor = cursor.plusMonths(1);
        }
    }

    private LocalDateTime nextQuarterlyLastSunday18(LocalDateTime fromExclusive) {
        YearMonth cursor = YearMonth.from(fromExclusive);
        while (true) {
            if ((cursor.getMonthValue() - 1) % 3 == 0) {
                LocalDateTime candidate = lastSundayAt18(cursor);
                if (candidate.isAfter(fromExclusive)) {
                    return candidate;
                }
            }
            cursor = cursor.plusMonths(1);
        }
    }

    private LocalDateTime lastSundayAt18(YearMonth month) {
        LocalDate day = month.atDay(1).with(TemporalAdjusters.lastInMonth(DayOfWeek.SUNDAY));
        return day.atTime(18, 0);
    }

    private void logNextResets() {
        for (PortalType type : PortalType.values()) {
            LocalDateTime next = nextResets.get(type);
            getLogger().info("Nächster Reset " + type.displayName + ": " + RESET_FORMAT.format(next));
        }
    }

    private enum Axis {
        X,
        Z
    }

    private enum ResetRule {
        MONTHLY_LAST_SUNDAY_18,
        QUARTERLY_LAST_SUNDAY_18
    }

    private enum PortalType {
        OVERWORLD("Farm Oberwelt", Material.POLISHED_ANDESITE, "farm_overworld", World.Environment.NORMAL, ResetRule.MONTHLY_LAST_SUNDAY_18),
        NETHER("Farm Nether", Material.POLISHED_GRANITE, "farm_nether", World.Environment.NETHER, ResetRule.QUARTERLY_LAST_SUNDAY_18),
        END("Farm End", Material.POLISHED_DIORITE, "farm_end", World.Environment.THE_END, ResetRule.QUARTERLY_LAST_SUNDAY_18);

        private final String displayName;
        private final Material frameMaterial;
        private final String worldName;
        private final World.Environment environment;
        private final ResetRule resetRule;

        PortalType(String displayName, Material frameMaterial, String worldName, World.Environment environment, ResetRule resetRule) {
            this.displayName = displayName;
            this.frameMaterial = frameMaterial;
            this.worldName = worldName;
            this.environment = environment;
            this.resetRule = resetRule;
        }

        private static PortalType fromFrameMaterial(Material material) {
            for (PortalType type : values()) {
                if (type.frameMaterial == material) {
                    return type;
                }
            }
            return null;
        }
    }

    private static final class ShelterPalette {
        private final Material shellMaterial;
        private final Material lightMaterial;
        private final Material doorMaterial;

        private ShelterPalette(Material shellMaterial, Material lightMaterial, Material doorMaterial) {
            this.shellMaterial = shellMaterial;
            this.lightMaterial = lightMaterial;
            this.doorMaterial = doorMaterial;
        }

        private static ShelterPalette forPortalType(PortalType type) {
            if (type == PortalType.NETHER) {
                return new ShelterPalette(Material.BLACKSTONE, Material.TORCH, Material.CRIMSON_DOOR);
            }
            if (type == PortalType.END) {
                return new ShelterPalette(Material.END_STONE_BRICKS, Material.TORCH, Material.BIRCH_DOOR);
            }
            return new ShelterPalette(Material.COBBLESTONE, Material.TORCH, Material.OAK_DOOR);
        }
    }

    private static final class PortalShape {
        private final Axis axis;
        private final PortalType portalType;
        private final Set<PortalLocation> frameBlocks;
        private final Set<PortalLocation> interiorBlocks;

        private PortalShape(Axis axis, PortalType portalType, Set<PortalLocation> frameBlocks, Set<PortalLocation> interiorBlocks) {
            this.axis = axis;
            this.portalType = portalType;
            this.frameBlocks = frameBlocks;
            this.interiorBlocks = interiorBlocks;
        }
    }

    private static final class ActivePortal {
        private final Set<PortalLocation> frameBlocks;
        private final Set<PortalLocation> interiorBlocks;
        private final PortalType portalType;

        private ActivePortal(Set<PortalLocation> frameBlocks, Set<PortalLocation> interiorBlocks, PortalType portalType) {
            this.frameBlocks = new HashSet<>(frameBlocks);
            this.interiorBlocks = new HashSet<>(interiorBlocks);
            this.portalType = portalType;
        }
    }

    private static final class PendingActivePortal {
        private final PortalType portalType;
        private final List<String> frameSerialized;
        private final List<String> interiorSerialized;

        private PendingActivePortal(PortalType portalType, List<String> frameSerialized, List<String> interiorSerialized) {
            this.portalType = portalType;
            this.frameSerialized = new ArrayList<>(frameSerialized);
            this.interiorSerialized = new ArrayList<>(interiorSerialized);
        }
    }

    private static final class PortalLocation {
        private final UUID world;
        private final int x;
        private final int y;
        private final int z;

        private PortalLocation(UUID world, int x, int y, int z) {
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        private PortalLocation(Location location) {
            this.world = Objects.requireNonNull(location.getWorld()).getUID();
            this.x = location.getBlockX();
            this.y = location.getBlockY();
            this.z = location.getBlockZ();
        }

        private Block toBlock() {
            World worldObj = Bukkit.getWorld(world);
            if (worldObj == null) {
                throw new IllegalStateException("Welt nicht gefunden: " + world);
            }
            return worldObj.getBlockAt(x, y, z);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof PortalLocation that)) {
                return false;
            }
            return x == that.x && y == that.y && z == that.z && world.equals(that.world);
        }

        @Override
        public int hashCode() {
            return Objects.hash(world, x, y, z);
        }
    }

    private static final class FarmZoneKey {
        private final UUID owner;
        private final PortalType portalType;

        private FarmZoneKey(UUID owner, PortalType portalType) {
            this.owner = owner;
            this.portalType = portalType;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof FarmZoneKey that)) {
                return false;
            }
            return owner.equals(that.owner) && portalType == that.portalType;
        }

        @Override
        public int hashCode() {
            return Objects.hash(owner, portalType);
        }
    }

    private static final class FarmZone {
        private static final int RADIUS_CHUNKS = 3;

        private final FarmZoneKey key;
        private final UUID owner;
        private final String ownerName;
        private final String worldName;
        private final int centerChunkX;
        private final int centerChunkZ;
        private final Set<UUID> allowedPlayers = new HashSet<>();

        private FarmZone(FarmZoneKey key, String ownerName, String worldName, int centerChunkX, int centerChunkZ) {
            this.key = key;
            this.owner = key.owner;
            this.ownerName = ownerName;
            this.worldName = worldName;
            this.centerChunkX = centerChunkX;
            this.centerChunkZ = centerChunkZ;
            this.allowedPlayers.add(owner);
        }

        private boolean containsChunk(int chunkX, int chunkZ) {
            return Math.max(Math.abs(centerChunkX - chunkX), Math.abs(centerChunkZ - chunkZ)) <= RADIUS_CHUNKS;
        }
    }

    private static final class FarmSession {
        private final PortalType portalType;
        private final Location returnLocation;
        private int remainingSeconds;
        private final int compassSlot;
        private final BossBar bossBar;
        private boolean unlimitedTime;
        private int xpBuffer;
        private int virtualLevel;
        private boolean warnedThirty;

        private FarmSession(PortalType portalType, Location returnLocation, int remainingSeconds, int compassSlot, BossBar bossBar, boolean unlimitedTime, int startingLevel) {
            this.portalType = portalType;
            this.returnLocation = returnLocation;
            this.remainingSeconds = remainingSeconds;
            this.compassSlot = compassSlot;
            this.bossBar = bossBar;
            this.unlimitedTime = unlimitedTime;
            this.virtualLevel = startingLevel;
        }
    }

    private static final class ReturnCountdown {
        private int secondsLeft;

        private ReturnCountdown(int secondsLeft) {
            this.secondsLeft = secondsLeft;
        }
    }
}



