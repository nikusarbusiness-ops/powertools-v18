package de.powersmp.powertools;

import de.coreeco.CoreEco;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.block.Action;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Block;
import org.bukkit.util.Vector;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.block.data.Levelled;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class PowerTools extends JavaPlugin implements CommandExecutor, TabCompleter, Listener {
    private static final long TOOL_LIFETIME_MS = 5L * 24L * 60L * 60L * 1000L;
    private static final long CLEANUP_INTERVAL_TICKS = 20L;

    private NamespacedKey toolKey;
    private NamespacedKey createdAtKey;
    private NamespacedKey bucketFluidKey;
    private NamespacedKey bucketChargesKey;
    private CoreEco powerEco;
    private boolean processingSpecialBreak;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        toolKey = new NamespacedKey(this, "powertool_type");
        createdAtKey = new NamespacedKey(this, "powertool_created_at");
        bucketFluidKey = new NamespacedKey(this, "bucket_fluid");
        bucketChargesKey = new NamespacedKey(this, "bucket_charges");
        if (getCommand("powertools") == null) {
            getLogger().severe("Der Befehl 'powertools' fehlt in plugin.yml!");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getCommand("powertools").setExecutor(this);
        getCommand("powertools").setTabCompleter(this);
        getServer().getPluginManager().registerEvents(this, this);
        if (Bukkit.getPluginManager().getPlugin("PowerEco") instanceof CoreEco eco) {
            powerEco = eco;
        } else {
            getLogger().warning("PowerEco wurde nicht gefunden. Sell Axe ist deaktiviert.");
        }
        getServer().getScheduler().runTaskTimer(this, this::updateToolTimersAndRemoveExpired,
                CLEANUP_INTERVAL_TICKS, CLEANUP_INTERVAL_TICKS);
        getServer().getScheduler().runTask(this, this::updateToolTimersAndRemoveExpired);
        getLogger().info("PowerTools 1.3.4 aktiviert. Markierte Tools laufen nach 5 Tagen ab.");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("powertools.admin")) {
                sender.sendMessage(color("&cDazu hast du keine Berechtigung."));
                return true;
            }
            reloadConfig();
            sender.sendMessage(color("&aPowerTools-Konfiguration neu geladen."));
            return true;
        }
        if (args.length < 3 || !args[0].equalsIgnoreCase("give")) {
            sender.sendMessage(color("&eVerwendung: &6/pt give <Spieler> <drill|axe|shovel|bucket|sellaxe> [Anzahl]"));
            sender.sendMessage(color("&eOder: &6/pt reload"));
            return true;
        }
        if (!sender.hasPermission("powertools.admin")) {
            sender.sendMessage(color("&cDazu hast du keine Berechtigung."));
            return true;
        }
        Player target = getServer().getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(color("&cDieser Spieler ist nicht online."));
            return true;
        }
        String type = args[2].toLowerCase(Locale.ROOT);
        int amount = 1;
        if (args.length >= 4) {
            try { amount = Integer.parseInt(args[3]); }
            catch (NumberFormatException ex) {
                sender.sendMessage(color("&cDie Anzahl muss eine Zahl sein."));
                return true;
            }
        }
        if (amount < 1 || amount > 64) {
            sender.sendMessage(color("&cDie Anzahl muss zwischen 1 und 64 liegen."));
            return true;
        }
        ItemStack item = createTool(type);
        if (item == null) {
            sender.sendMessage(color("&cUnbekanntes oder deaktiviertes Tool. Nutze drill, axe, shovel, bucket oder sellaxe."));
            return true;
        }
        item.setAmount(amount);
        Map<Integer, ItemStack> leftovers = target.getInventory().addItem(item);
        leftovers.values().forEach(leftover -> target.getWorld().dropItemNaturally(target.getLocation(), leftover));
        sender.sendMessage(color("&aDu hast &e" + amount + "x " + displayName(type) + " &aan &e" + target.getName() + " &avergeben."));
        if (!sender.equals(target)) target.sendMessage(color("&aDu hast " + amount + "x " + displayName(type) + " &aerhalten. &7(Ablauf nach 5 Tagen)"));
        return true;
    }

    private ItemStack createTool(String type) {
        Material material;
        String name;
        switch (type) {
            case "drill" -> { material = Material.NETHERITE_PICKAXE; name = "&e&l⚡ Blitz Drill"; }
            case "axe" -> { material = Material.NETHERITE_AXE; name = "&e&l⚡ Blitz Axt"; }
            case "shovel" -> { material = Material.NETHERITE_SHOVEL; name = "&e&l⚡ Blitz Schaufel"; }
            case "bucket" -> {
                if (!getConfig().getBoolean("tools.bucket.enabled", true)) return null;
                material = Material.BUCKET; name = "&e&l⚡ Blitz Eimer";
            }
            case "sellaxe" -> { material = Material.NETHERITE_AXE; name = "&e&l⚡ Blitz Sell Axe"; }
            default -> { return null; }
        }
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;
        meta.setDisplayName(color(name));
        List<String> lore = switch (type) {
            case "drill" -> List.of(
                    "&8&m----------------------", "&e&l⚡ BLITZ-TECHNOLOGIE", "&fPräzisionsbohrer der PowerSMP-Serie.",
                    "&7Baut ein komplettes &e3x3-Feld &7ab.", "&7Für Stein, Granit, Diorit, Andesit,", "&7Tiefenschiefer und weitere Gesteine.",
                    "&8&m----------------------", "&e&l⚡ Verbleibend: 5 Tage 0 Stunden 0 Minuten");
            case "axe" -> List.of(
                    "&8&m----------------------", "&e&l⚡ BLITZ-TIMBER", "&fDie Kraft des Blitzes im Holz.", "&7Fällt verbundene Baumstämme", "&7mit einem einzigen Schlag.",
                    "&8&m----------------------", "&e&l⚡ Verbleibend: 5 Tage 0 Stunden 0 Minuten");
            case "shovel" -> List.of(
                    "&8&m----------------------", "&e&l⚡ BLITZ-BUDDLER", "&fMehr Fläche, weniger Arbeit.", "&7Gräbt ein komplettes &e3x3-Feld &7ab.",
                    "&7Für Erde, Gras, Sand und Kies.", "&8&m----------------------", "&e&l⚡ Verbleibend: 5 Tage 0 Stunden 0 Minuten");
            case "bucket" -> List.of(
                    "&8&m----------------------", "&e&l⚡ BLITZ-MULTI-EIMER",
                    "&fEin Eimer für große Bauprojekte.", "&7Speichert bis zu &e21 Ladungen&7.",
                    "&7Wahlweise Wasser oder Lava.",
                    "&7Rechtsklick: aufnehmen oder platzieren.",
                    "&7Inhalt: &f0&7/&f21 &8| &7Typ: &fLeer",
                    "&8&m----------------------", "&e&l⚡ Verbleibend: 5 Tage 0 Stunden 0 Minuten");
            case "sellaxe" -> List.of(
                    "&8&m----------------------", "&e&l⚡ BLITZ-SELL-AXE", "&fVerkaufen mit einem einzigen Klick.", "&7Verkauft wertvolle Items", "&7direkt aus einer Kiste.",
                    "&7Nicht verkaufbare Items bleiben liegen.", "&7Die Kiste bleibt stehen.", "&8&m----------------------", "&e&l⚡ Verbleibend: 5 Tage 0 Stunden 0 Minuten");
            default -> List.of("&b&lPowerSMP", "&cAblauf nach 5 Tagen");
        };
        meta.setLore(lore.stream().map(this::color).toList());
        meta.getPersistentDataContainer().set(toolKey, PersistentDataType.STRING, type);
        long createdAt = System.currentTimeMillis();
        meta.getPersistentDataContainer().set(createdAtKey, PersistentDataType.LONG, createdAt);
        updateExpiryLore(meta, createdAt, createdAt);
        if (type.equals("bucket")) {
            meta.getPersistentDataContainer().set(bucketChargesKey, PersistentDataType.INTEGER, 0);
            meta.setMaxStackSize(1);
        }
        item.setItemMeta(meta);

        // Maximal sinnvolle Verzauberungen. Fortune und Silk Touch werden nicht kombiniert.
        if (!type.equals("bucket")) {
            item.addUnsafeEnchantment(Enchantment.EFFICIENCY, type.equals("drill") ? 5 : 10);
            item.addUnsafeEnchantment(Enchantment.UNBREAKING, 10);
            item.addUnsafeEnchantment(Enchantment.MENDING, 1);
            if (type.equals("drill") || type.equals("shovel")) item.addUnsafeEnchantment(Enchantment.FORTUNE, 10);
            if (type.equals("axe") || type.equals("sellaxe")) item.addUnsafeEnchantment(Enchantment.SHARPNESS, 10);
        } else {
            item.addUnsafeEnchantment(Enchantment.UNBREAKING, 10);
        }
        return item;
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlitzBucketUse(PlayerInteractEvent event) {
        if (event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack hand = event.getItem();
        if (!isTool(hand, "bucket")) return;
        Block clicked = event.getClickedBlock();
        if (clicked == null) return;

        event.setCancelled(true);
        ItemMeta meta = hand.getItemMeta();
        if (meta == null) return;
        var pdc = meta.getPersistentDataContainer();
        int charges = pdc.getOrDefault(bucketChargesKey, PersistentDataType.INTEGER, 0);
        String fluid = pdc.get(bucketFluidKey, PersistentDataType.STRING);

        Material clickedType = clicked.getType();
        String sourceFluid = isSourceFluid(clicked) ? (clickedType == Material.WATER ? "WATER" : "LAVA") : null;

        // First preference: collect a source block if the bucket is empty or stores the same fluid.
        if (sourceFluid != null) {
            if (fluid != null && !fluid.equals(sourceFluid)) {
                event.getPlayer().sendMessage(color("&cDer Blitz Eimer enthält bereits " + fluidName(fluid) + ". Erst vollständig leeren!"));
                return;
            }
            if (charges >= 21) {
                event.getPlayer().sendMessage(color("&eDer Blitz Eimer ist voll (21/21)."));
                return;
            }
            clicked.setType(Material.AIR, false);
            fluid = sourceFluid;
            charges++;
            pdc.set(bucketFluidKey, PersistentDataType.STRING, fluid);
            pdc.set(bucketChargesKey, PersistentDataType.INTEGER, charges);
            updateBucketLore(meta, fluid, charges);
            hand.setItemMeta(meta);
            event.getPlayer().getInventory().setItemInMainHand(hand);
            event.getPlayer().sendMessage(color("&a+1 " + fluidName(fluid) + " &7(&f" + charges + "&7/21)"));
            event.getPlayer().playSound(event.getPlayer().getLocation(), org.bukkit.Sound.ITEM_BUCKET_FILL, 0.8f, 1.2f);
            return;
        }

        // Otherwise place one stored source block against the clicked block face.
        if (charges <= 0 || fluid == null) {
            event.getPlayer().sendMessage(color("&cDer Blitz Eimer ist leer. Rechtsklicke auf eine Wasser- oder Lavaquelle."));
            return;
        }
        Block target = clicked.getRelative(event.getBlockFace());
        if (!target.isPassable()) {
            event.getPlayer().sendMessage(color("&cHier kannst du nichts platzieren."));
            return;
        }
        // Replaceable blocks (for example grass or flowers) may be replaced by the fluid.
        target.setType(fluid.equals("WATER") ? Material.WATER : Material.LAVA, false);
        charges--;
        if (charges == 0) {
            pdc.remove(bucketFluidKey);
            fluid = null;
        }
        pdc.set(bucketChargesKey, PersistentDataType.INTEGER, charges);
        updateBucketLore(meta, fluid, charges);
        hand.setItemMeta(meta);
        event.getPlayer().getInventory().setItemInMainHand(hand);
        event.getPlayer().sendMessage(color("&aPlatziert! &7Noch &f" + charges + "&7/21 Ladungen."));
        event.getPlayer().playSound(event.getPlayer().getLocation(), org.bukkit.Sound.ITEM_BUCKET_EMPTY, 0.8f, 1.2f);
    }

    private boolean isSourceFluid(Block block) {
        Material type = block.getType();
        if (type != Material.WATER && type != Material.LAVA) return false;
        if (block.getBlockData() instanceof Levelled levelled) return levelled.getLevel() == 0;
        return true;
    }

    private String fluidName(String fluid) {
        return "LAVA".equals(fluid) ? "&6Lava" : "&bWasser";
    }

    private void updateBucketLore(ItemMeta meta, String fluid, int charges) {
        String kind = fluid == null ? "&fLeer" : fluidName(fluid);
        meta.setLore(List.of(
                color("&8&m----------------------"),
                color("&9&l⚡ BLITZ-MULTI-EIMER"),
                color("&7Speichert bis zu &b21 Ladungen&7."),
                color("&7Wahlweise Wasser oder Lava."),
                color("&7Rechtsklick: aufnehmen oder platzieren."),
                color("&7Inhalt: &f" + charges + "&7/21 &8| &7Typ: " + kind),
                color("&8&m----------------------")
        ));
        Long createdAt = meta.getPersistentDataContainer().get(createdAtKey, PersistentDataType.LONG);
        if (createdAt != null) updateExpiryLore(meta, createdAt, System.currentTimeMillis());
    }

    @EventHandler(ignoreCancelled = true)
    public void onSpecialToolBreak(BlockBreakEvent event) {
        if (processingSpecialBreak) return;
        ItemStack tool = event.getPlayer().getInventory().getItemInMainHand();
        String type = getToolType(tool);
        if (type == null || type.equals("bucket") || type.equals("sellaxe")) return;

        Block center = event.getBlock();
        if (type.equals("drill") && isStoneFamily(center.getType())) {
            breakThreeByThree(center, event.getPlayer(), tool, true);
        } else if (type.equals("shovel") && isDiggableFamily(center.getType())) {
            breakThreeByThree(center, event.getPlayer(), tool, false);
        } else if (type.equals("axe") && isLog(center.getType())) {
            breakConnectedLogs(center, event.getPlayer(), tool);
        }
    }

    private void breakThreeByThree(Block center, Player player, ItemStack tool, boolean stoneMode) {
        Vector direction = player.getLocation().getDirection();
        // 3x3 plane perpendicular to the direction the player is facing.
        if (Math.abs(direction.getY()) > Math.abs(direction.getX()) && Math.abs(direction.getY()) > Math.abs(direction.getZ())) {
            for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++)
                if (x != 0 || z != 0) breakExtra(center.getRelative(x, 0, z), player, tool, stoneMode);
        } else if (Math.abs(direction.getX()) > Math.abs(direction.getZ())) {
            for (int y = -1; y <= 1; y++) for (int z = -1; z <= 1; z++)
                if (y != 0 || z != 0) breakExtra(center.getRelative(0, y, z), player, tool, stoneMode);
        } else {
            for (int x = -1; x <= 1; x++) for (int y = -1; y <= 1; y++)
                if (x != 0 || y != 0) breakExtra(center.getRelative(x, y, 0), player, tool, stoneMode);
        }
    }

    private void breakExtra(Block block, Player player, ItemStack tool, boolean stoneMode) {
        if (block.getType().isAir() || !(stoneMode ? isStoneFamily(block.getType()) : isDiggableFamily(block.getType()))) return;
        BlockBreakEvent extra = new BlockBreakEvent(block, player);
        processingSpecialBreak = true;
        try {
            Bukkit.getPluginManager().callEvent(extra);
            if (!extra.isCancelled()) block.breakNaturally(tool);
        } finally {
            processingSpecialBreak = false;
        }
    }

    private void breakConnectedLogs(Block start, Player player, ItemStack tool) {
        ArrayDeque<Block> queue = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        queue.add(start);
        visited.add(blockKey(start));
        int broken = 0;
        while (!queue.isEmpty() && broken < 64) {
            Block current = queue.removeFirst();
            if (!isLog(current.getType())) continue;
            if (current != start) {
                BlockBreakEvent extra = new BlockBreakEvent(current, player);
                processingSpecialBreak = true;
                try {
                    Bukkit.getPluginManager().callEvent(extra);
                    if (extra.isCancelled()) continue;
                    current.breakNaturally(tool);
                } finally {
                    processingSpecialBreak = false;
                }
            }
            broken++;
            for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dy == 0 && dz == 0) continue;
                Block next = current.getRelative(dx, dy, dz);
                String key = blockKey(next);
                if (visited.add(key) && isLog(next.getType())) queue.addLast(next);
            }
        }
    }

    private String blockKey(Block b) { return b.getWorld().getUID() + ":" + b.getX() + ":" + b.getY() + ":" + b.getZ(); }

    private boolean isLog(Material m) {
        String n = m.name();
        return (n.endsWith("_LOG") || n.endsWith("_WOOD") || n.endsWith("_STEM") || n.endsWith("_HYPHAE"))
                && !n.contains("STRIPPED");
    }

    private boolean isStoneFamily(Material m) {
        String n = m.name();
        // Erze gehören ebenfalls zum Drill-Bereich (inkl. Tiefenschiefer-Varianten).
        if (n.endsWith("_ORE") || n.equals("ANCIENT_DEBRIS")) return true;
        return n.equals("STONE") || n.equals("COBBLESTONE") || n.equals("MOSSY_COBBLESTONE")
                || n.equals("STONE_BRICKS") || n.equals("CRACKED_STONE_BRICKS") || n.equals("MOSSY_STONE_BRICKS")
                || n.equals("CHISELED_STONE_BRICKS") || n.equals("BRICKS") || n.equals("END_STONE")
                || n.equals("END_STONE_BRICKS") || n.equals("PRISMARINE") || n.equals("DARK_PRISMARINE")
                || n.equals("PRISMARINE_BRICKS") || n.equals("OBSIDIAN") || n.equals("CRYING_OBSIDIAN")
                || n.equals("CALCITE") || n.equals("TUFF") || n.startsWith("TUFF_")
                || n.equals("SMOOTH_STONE") || n.equals("DRIPSTONE_BLOCK")
                || n.contains("SANDSTONE") || n.contains("RED_SANDSTONE") || n.contains("QUARTZ_BLOCK")
                || n.contains("GRANITE") || n.contains("DIORITE") || n.contains("ANDESITE")
                || n.contains("DEEPSLATE") || n.contains("BLACKSTONE") || n.contains("BASALT")
                || n.contains("NETHERRACK") || n.contains("NETHER_BRICK") || n.equals("MAGMA_BLOCK");
    }

    private boolean isDiggableFamily(Material m) {
        String n = m.name();
        return n.equals("SAND") || n.equals("RED_SAND") || n.equals("GRAVEL") || n.equals("DIRT")
                || n.equals("COARSE_DIRT") || n.equals("ROOTED_DIRT") || n.equals("GRASS_BLOCK")
                || n.equals("PODZOL") || n.equals("MYCELIUM") || n.equals("CLAY")
                || n.equals("MUD") || n.equals("PACKED_MUD") || n.equals("SOUL_SAND") || n.equals("SOUL_SOIL");
    }

    private String getToolType(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(toolKey, PersistentDataType.STRING);
    }

    @EventHandler(ignoreCancelled = true)
    public void onSellAxeChestBreak(BlockBreakEvent event) {
        if (powerEco == null) return;
        ItemStack hand = event.getPlayer().getInventory().getItemInMainHand();
        if (!isTool(hand, "sellaxe")) return;
        Material type = event.getBlock().getType();
        if (type != Material.CHEST && type != Material.TRAPPED_CHEST) return;
        BlockState state = event.getBlock().getState();
        if (!(state instanceof Container container)) return;

        // Sell only items with a configured PowerEco sell price. Unpriced items stay in the chest.
        Inventory inventory = container.getInventory();
        double total = 0.0;
        int soldStacks = 0;
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack == null || stack.getType().isAir()) continue;
            double each = powerEco.shop().sellEach(stack.getType(), 1.0);
            if (each <= 0.0) continue;
            total += each * stack.getAmount();
            inventory.setItem(slot, null);
            soldStacks++;
        }
        if (soldStacks == 0) {
            event.setCancelled(true); // Die Truhe bleibt auch bei leerem/nicht verkaufbarem Inhalt stehen.
            event.getPlayer().sendMessage(color("&cIn dieser Truhe befinden sich keine verkaufbaren Items."));
            return;
        }
        event.setCancelled(true); // Truhe und Block bleiben erhalten.
        powerEco.eco().deposit(event.getPlayer().getUniqueId(), total);
        event.getPlayer().sendMessage(color("&aSell Axe: &e" + soldStacks + " Stapel &averkauft für &6" + powerEco.eco().format(total) + "&a."));
        event.getPlayer().playSound(event.getPlayer().getLocation(), org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.2f);
    }

    private boolean isTool(ItemStack item, String type) {
        if (item == null || !item.hasItemMeta()) return false;
        String stored = item.getItemMeta().getPersistentDataContainer().get(toolKey, PersistentDataType.STRING);
        return type.equals(stored);
    }

    // Die Ablaufzeit basiert auf dem Erstellungszeitpunkt (Unix-Zeit) und läuft daher
    // auch offline weiter. Beim Join werden Spielerinventar und Endertruhe sofort geprüft.
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        long now = System.currentTimeMillis();
        int removed = updateInventoryTimersAndRemoveExpired(event.getPlayer().getInventory(), now);
        removed += updateInventoryTimersAndRemoveExpired(event.getPlayer().getEnderChest(), now);
        if (removed > 0) {
            getLogger().info(removed + " abgelaufene PowerTools-Item(s) beim Join entfernt.");
        }
    }

    // Items in entladenen Chunks werden erst beim erneuten Laden erreichbar.
    // Dann wird ihre absolute Ablaufzeit geprüft und abgelaufene Items werden entfernt.
    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        long now = System.currentTimeMillis();
        int removed = 0;

        for (org.bukkit.entity.Entity entity : event.getChunk().getEntities()) {
            if (!(entity instanceof Item droppedItem)) continue;
            ItemStack stack = droppedItem.getItemStack();
            if (isExpired(stack, now)) {
                droppedItem.remove();
                removed++;
            } else if (updateItemTimer(stack, now)) {
                droppedItem.setItemStack(stack);
            }
        }

        removed += updateChunkContainerTimers(event.getChunk(), now);
        if (removed > 0) {
            getLogger().info(removed + " abgelaufene PowerTools-Item(s) in geladenem Chunk entfernt.");
        }
    }

    private int updateChunkContainerTimers(org.bukkit.Chunk chunk, long now) {
        int removed = 0;
        for (BlockState state : chunk.getTileEntities()) {
            if (!(state instanceof Container container)) continue;
            removed += updateInventoryTimersAndRemoveExpired(container.getInventory(), now);
        }
        return removed;
    }

    private int updateInventoryTimersAndRemoveExpired(Inventory inventory, long now) {
        int removed = 0;
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack item = inventory.getItem(slot);
            if (isExpired(item, now)) {
                inventory.setItem(slot, null);
                removed++;
            } else if (item != null && updateItemTimer(item, now)) {
                inventory.setItem(slot, item);
            }
        }
        return removed;
    }

    private void updateToolTimersAndRemoveExpired() {
        long now = System.currentTimeMillis();
        int removed = 0;

        // Online-Spieler: Inventar und Endertruhe werden anhand des absoluten
        // Erstellungszeitpunkts geprüft. Die Frist läuft auch offline weiter;
        // offline gespeicherte Items werden direkt beim nächsten Join entfernt.
        for (Player player : getServer().getOnlinePlayers()) {
            removed += updateInventoryTimersAndRemoveExpired(player.getInventory(), now);
            removed += updateInventoryTimersAndRemoveExpired(player.getEnderChest(), now);
        }

        // Nur bereits geladene Chunks prüfen. Dadurch werden keine Chunks
        // versehentlich geladen. Container-Inhalte laufen trotzdem in Echtzeit ab.
        for (World world : getServer().getWorlds()) {
            for (org.bukkit.Chunk chunk : world.getLoadedChunks()) {
                removed += updateChunkContainerTimers(chunk, now);

                for (org.bukkit.entity.Entity entity : chunk.getEntities()) {
                    if (!(entity instanceof Item droppedItem)) continue;
                    ItemStack stack = droppedItem.getItemStack();
                    if (isExpired(stack, now)) {
                        droppedItem.remove();
                        removed++;
                    } else if (updateItemTimer(stack, now)) {
                        droppedItem.setItemStack(stack);
                    }
                }
            }
        }

        if (removed > 0) getLogger().info(removed + " abgelaufene PowerTools-Item(s) entfernt.");
    }

    private int updateInventoryTimers(Inventory inventory, long now) {
        int removed = 0;
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack item = inventory.getItem(slot);
            if (isExpired(item, now)) {
                inventory.setItem(slot, null);
                removed++;
            } else if (item != null && updateItemTimer(item, now)) {
                inventory.setItem(slot, item);
            }
        }
        return removed;
    }

    private boolean updateItemTimer(ItemStack item, long now) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.getPersistentDataContainer().has(toolKey, PersistentDataType.STRING)) return false;
        Long createdAt = meta.getPersistentDataContainer().get(createdAtKey, PersistentDataType.LONG);
        if (createdAt == null) return false;
        updateExpiryLore(meta, createdAt, now);
        item.setItemMeta(meta);
        return true;
    }

    private void updateExpiryLore(ItemMeta meta, long createdAt, long now) {
        List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
        String timerLine = color("&e&l⚡ Verbleibend: " + remainingTime(createdAt + TOOL_LIFETIME_MS - now));
        boolean replaced = false;
        for (int i = 0; i < lore.size(); i++) {
            if (ChatColor.stripColor(lore.get(i)).toLowerCase(Locale.ROOT).contains("verbleibend")
                    || ChatColor.stripColor(lore.get(i)).toLowerCase(Locale.ROOT).contains("ablauf")) {
                lore.set(i, timerLine);
                replaced = true;
            }
        }
        if (!replaced) lore.add(timerLine);
        meta.setLore(lore);
    }

    private String remainingTime(long millis) {
        // Nur Tage, Stunden und Minuten anzeigen.
        // Abrunden sorgt dafür, dass die Anzeige niemals wieder nach oben springt.
        long totalMinutes = Math.max(0L, millis / 60_000L);
        long days = totalMinutes / (24L * 60L);
        long hours = (totalMinutes % (24L * 60L)) / 60L;
        long minutes = totalMinutes % 60L;
        return days + " Tage " + hours + " Stunden " + minutes + " Minuten";
    }

    private boolean isExpired(ItemStack item, long now) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.getPersistentDataContainer().has(toolKey, PersistentDataType.STRING)) return false;
        Long createdAt = meta.getPersistentDataContainer().get(createdAtKey, PersistentDataType.LONG);
        return createdAt != null && now - createdAt >= TOOL_LIFETIME_MS;
    }

    private String displayName(String type) {
        return switch (type) {
            case "drill" -> "Blitz Drill";
            case "axe" -> "Blitz Axt";
            case "shovel" -> "Blitz Schaufel";
            case "bucket" -> "Blitz Eimer";
            case "sellaxe" -> "Blitz Sell Axe";
            default -> type;
        };
    }

    private String color(String input) { return ChatColor.translateAlternateColorCodes('&', input); }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return startsWith(args[0], List.of("give", "reload"));
        if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            return getServer().getOnlinePlayers().stream().map(Player::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("give"))
            return startsWith(args[2], List.of("drill", "axe", "shovel", "bucket", "sellaxe"));
        return List.of();
    }

    private List<String> startsWith(String input, List<String> options) {
        String lower = input.toLowerCase(Locale.ROOT);
        return options.stream().filter(option -> option.startsWith(lower)).toList();
    }
}
