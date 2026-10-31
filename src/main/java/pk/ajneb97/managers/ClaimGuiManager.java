package pk.ajneb97.managers;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import pk.ajneb97.PlayerKits2;
import pk.ajneb97.model.Kit;
import pk.ajneb97.model.internal.GiveKitInstructions;
import pk.ajneb97.model.internal.KitVariable;
import pk.ajneb97.model.internal.PlayerKitsMessageResult;
import pk.ajneb97.model.inventory.InventoryPlayer;
import pk.ajneb97.model.item.KitItem;
import pk.ajneb97.utils.ItemUtils;
import pk.ajneb97.utils.MiniMessageUtils;
import pk.ajneb97.utils.PlayerUtils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Claim GUI mode: instead of putting the kit items directly into the player inventory,
 * the items are shown in a GUI ("Odbierz przedmioty") where the player takes them manually.
 * The cooldown starts only when every item has been taken.
 * Items that were not taken stay "pending" (also after a restart) until the player takes them.
 */
public class ClaimGuiManager {

    public static final String BUTTON_TAG = "playerkits_claim";

    private final PlayerKits2 plugin;
    private final Map<UUID, Map<String, List<ItemStack>>> pending = new HashMap<>();
    private final Map<UUID, ClaimSession> sessions = new HashMap<>();
    private File pendingFile;
    private FileConfiguration defaultConfig;

    public ClaimGuiManager(PlayerKits2 plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ config helpers

    private FileConfiguration getMainConfig() {
        return plugin.getConfigsManager().getMainConfigManager().getConfig();
    }

    private FileConfiguration getDefaultConfig() {
        if (defaultConfig == null) {
            InputStream in = plugin.getResource("config.yml");
            if (in != null) {
                defaultConfig = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
            } else {
                defaultConfig = new YamlConfiguration();
            }
        }
        return defaultConfig;
    }

    /** Uses the server config when it has the path, otherwise the default config embedded in the jar. */
    private FileConfiguration getSource(String path) {
        FileConfiguration main = getMainConfig();
        if (main != null && main.contains(path)) {
            return main;
        }
        return getDefaultConfig();
    }

    public boolean isEnabled() {
        FileConfiguration main = getMainConfig();
        if (main == null) {
            return false;
        }
        return main.getBoolean("claim_in_gui", true);
    }

    public int getButtonSlot() {
        return getSource("claim_gui.button_slot").getInt("claim_gui.button_slot", 43);
    }

    private String getString(String path, String def) {
        return getSource(path).getString(path, def);
    }

    // ------------------------------------------------------------------ pending storage

    public void load() {
        pendingFile = new File(plugin.getDataFolder(), "pending_claims.yml");
        pending.clear();
        if (!pendingFile.exists()) {
            return;
        }
        FileConfiguration cfg = YamlConfiguration.loadConfiguration(pendingFile);
        ConfigurationSection players = cfg.getConfigurationSection("players");
        if (players == null) {
            return;
        }
        for (String uuidString : players.getKeys(false)) {
            UUID uuid;
            try {
                uuid = UUID.fromString(uuidString);
            } catch (IllegalArgumentException e) {
                continue;
            }
            ConfigurationSection kits = players.getConfigurationSection(uuidString);
            if (kits == null) {
                continue;
            }
            Map<String, List<ItemStack>> map = new HashMap<>();
            for (String kitName : kits.getKeys(false)) {
                List<ItemStack> list = new ArrayList<>();
                List<?> raw = kits.getList(kitName);
                if (raw != null) {
                    for (Object o : raw) {
                        if (o instanceof ItemStack) {
                            list.add((ItemStack) o);
                        }
                    }
                }
                if (!list.isEmpty()) {
                    map.put(kitName, list);
                }
            }
            if (!map.isEmpty()) {
                pending.put(uuid, map);
            }
        }
    }

    public void saveFile() {
        if (pendingFile == null) {
            return;
        }
        YamlConfiguration cfg = new YamlConfiguration();
        for (Map.Entry<UUID, Map<String, List<ItemStack>>> entry : pending.entrySet()) {
            for (Map.Entry<String, List<ItemStack>> kitEntry : entry.getValue().entrySet()) {
                if (kitEntry.getValue().isEmpty()) {
                    continue;
                }
                cfg.set("players." + entry.getKey().toString() + "." + kitEntry.getKey(), kitEntry.getValue());
            }
        }
        try {
            cfg.save(pendingFile);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public boolean hasPending(Player player, String kitName) {
        Map<String, List<ItemStack>> map = pending.get(player.getUniqueId());
        if (map == null) {
            return false;
        }
        List<ItemStack> list = map.get(kitName);
        return list != null && !list.isEmpty();
    }

    private List<ItemStack> getPending(UUID uuid, String kitName) {
        Map<String, List<ItemStack>> map = pending.get(uuid);
        if (map == null) {
            return null;
        }
        return map.get(kitName);
    }

    private void setPending(UUID uuid, String kitName, List<ItemStack> items) {
        Map<String, List<ItemStack>> map = pending.get(uuid);
        if (map == null) {
            map = new HashMap<>();
            pending.put(uuid, map);
        }
        map.put(kitName, items);
    }

    private void removePending(UUID uuid, String kitName) {
        Map<String, List<ItemStack>> map = pending.get(uuid);
        if (map == null) {
            return;
        }
        map.remove(kitName);
        if (map.isEmpty()) {
            pending.remove(uuid);
        }
    }

    /** Called when the player claims the kit: creates the list of items waiting to be taken. */
    public void createPending(Player player, Kit kit) {
        List<ItemStack> list = new ArrayList<>();
        KitItemManager kitItemManager = plugin.getKitItemManager();
        for (KitItem kitItem : kit.getItems()) {
            ItemStack item = kitItemManager.createItemFromKitItem(kitItem, player, kit);
            if (item != null && !item.getType().equals(Material.AIR)) {
                list.add(item);
            }
        }
        setPending(player.getUniqueId(), kit.getName(), list);
        saveFile();
    }

    // ------------------------------------------------------------------ claim button

    public static class ButtonState {
        public final String status;
        public final String time;

        public ButtonState(String status, String time) {
            this.status = status;
            this.time = time;
        }
    }

    public ButtonState getButtonState(Player player, Kit kit) {
        PlayerDataManager playerDataManager = plugin.getPlayerDataManager();
        if (hasPending(player, kit.getName())) {
            return new ButtonState("pending", "");
        }
        if (!kit.playerHasPermission(player)) {
            return new ButtonState("no_permission", "");
        }
        if (kit.isOneTime() && !PlayerUtils.isPlayerKitsAdmin(player) && !PlayerUtils.hasOneTimeBypassPermission(player)
                && playerDataManager.isKitOneTime(player, kit.getName())) {
            return new ButtonState("one_time", "");
        }
        if (kit.getCooldown() != 0 && !PlayerUtils.isPlayerKitsAdmin(player) && !PlayerUtils.hasCooldownBypassPermission(player)) {
            long playerCooldown = playerDataManager.getKitCooldown(player, kit.getName());
            String time = playerDataManager.getKitCooldownString(playerCooldown);
            if (!time.isEmpty()) {
                return new ButtonState("cooldown", time);
            }
        }
        return new ButtonState("ready", "");
    }

    public ItemStack createButton(Player player, Kit kit) {
        ButtonState state = getButtonState(player, kit);
        ArrayList<KitVariable> variables = new ArrayList<>();
        variables.add(new KitVariable("%kit_name%", kit.getName()));
        variables.add(new KitVariable("%time%", state.time));

        String path = "claim_gui.buttons." + state.status;
        KitItemManager kitItemManager = plugin.getKitItemManager();
        KitItem kitItem = kitItemManager.getKitItemFromConfig(getSource(path), path);
        kitItemManager.replaceVariables(kitItem, variables, player);
        ItemStack item = kitItemManager.createItemFromKitItem(kitItem, player, kit);
        return ItemUtils.setTagStringItem(plugin, item, BUTTON_TAG, kit.getName());
    }

    /** The player clicked the claim button on the kit preview. */
    public void clickClaimButton(InventoryPlayer inventoryPlayer, String kitName) {
        Player player = inventoryPlayer.getPlayer();
        MessagesManager msgManager = plugin.getMessagesManager();

        if (hasPending(player, kitName)) {
            openPickup(player, kitName, inventoryPlayer.getPreviousInventoryName());
            return;
        }

        GiveKitInstructions instructions = new GiveKitInstructions();
        instructions.setIntoGui(true);
        PlayerKitsMessageResult result = plugin.getKitsManager().giveKit(player, kitName, instructions);
        if (result.isError()) {
            msgManager.sendMessage(player, result.getMessage(), true);
            return;
        }
        if (result.isProceedToBuy()) {
            inventoryPlayer.setSavedPreviousInventoryName(inventoryPlayer.getPreviousInventoryName());
            inventoryPlayer.setPreviousInventoryName("preview_inventory");
            inventoryPlayer.setInventoryName("buy_requirements_inventory");
            inventoryPlayer.setKitName(kitName);
            plugin.getInventoryManager().openInventory(inventoryPlayer);
            return;
        }
        openPickup(player, kitName, inventoryPlayer.getPreviousInventoryName());
    }

    /** Used by /kit claim <kit> and /kit <kit>. */
    public void claimFromCommand(Player player, String kitName) {
        MessagesManager msgManager = plugin.getMessagesManager();
        if (hasPending(player, kitName)) {
            openPickup(player, kitName, "main_inventory");
            return;
        }
        GiveKitInstructions instructions = new GiveKitInstructions();
        instructions.setIntoGui(true);
        PlayerKitsMessageResult result = plugin.getKitsManager().giveKit(player, kitName, instructions);
        if (result.isError()) {
            msgManager.sendMessage(player, result.getMessage(), true);
            return;
        }
        if (result.isProceedToBuy()) {
            InventoryPlayer inventoryPlayer = new InventoryPlayer(player, "buy_requirements_inventory");
            inventoryPlayer.setKitName(kitName);
            inventoryPlayer.setPreviousInventoryName("main_inventory");
            plugin.getInventoryManager().openInventory(inventoryPlayer);
            return;
        }
        openPickup(player, kitName, "main_inventory");
    }

    // ------------------------------------------------------------------ pickup GUI

    public ClaimSession getSession(Player player) {
        return sessions.get(player.getUniqueId());
    }

    public void openPickup(Player player, String kitName, String previousInventoryName) {
        UUID uuid = player.getUniqueId();
        List<ItemStack> list = getPending(uuid, kitName);
        if (list == null) {
            return;
        }
        if (list.isEmpty()) {
            ClaimSession empty = new ClaimSession(player, kitName, null, new ArrayList<ItemStack>(), previousInventoryName);
            complete(empty, true);
            return;
        }

        int batch = Math.min(54, list.size());
        int size = ((batch + 8) / 9) * 9;
        String title = getString("claim_gui.title", "&8Odbierz przedmioty");
        Inventory inv;
        if (plugin.getConfigsManager().getMainConfigManager().isUseMiniMessage()) {
            inv = MiniMessageUtils.createInventory(size, title);
        } else {
            inv = Bukkit.createInventory(null, size, MessagesManager.getLegacyColoredMessage(title));
        }
        for (int i = 0; i < batch; i++) {
            inv.setItem(i, list.get(i));
        }
        List<ItemStack> overflow = new ArrayList<>(list.subList(batch, list.size()));

        ClaimSession session = new ClaimSession(player, kitName, inv, overflow, previousInventoryName);
        sessions.put(uuid, session);
        player.openInventory(inv);
    }

    private List<ItemStack> readInventory(Inventory inv) {
        List<ItemStack> remaining = new ArrayList<>();
        if (inv == null) {
            return remaining;
        }
        for (ItemStack item : inv.getContents()) {
            if (item != null && !item.getType().equals(Material.AIR)) {
                remaining.add(item.clone());
            }
        }
        return remaining;
    }

    /** Runs one tick after every click inside the pickup GUI. */
    public void afterClick(ClaimSession session) {
        if (session.finished || session.rolling) {
            return;
        }
        UUID uuid = session.player.getUniqueId();
        if (sessions.get(uuid) != session) {
            return;
        }
        List<ItemStack> remaining = readInventory(session.inventory);
        List<ItemStack> all = new ArrayList<>(remaining);
        all.addAll(session.overflow);
        setPending(uuid, session.kitName, all);

        if (remaining.isEmpty()) {
            if (session.overflow.isEmpty()) {
                complete(session, true);
            } else {
                // More than 54 items: show the next batch
                session.rolling = true;
                sessions.remove(uuid);
                openPickup(session.player, session.kitName, session.previousInventoryName);
            }
        }
    }

    public void handleClose(Player player, Inventory closed) {
        UUID uuid = player.getUniqueId();
        ClaimSession session = sessions.get(uuid);
        if (session == null || session.inventory == null || !session.inventory.equals(closed)) {
            return;
        }
        sessions.remove(uuid);
        if (session.finished || session.rolling) {
            return;
        }
        List<ItemStack> remaining = readInventory(session.inventory);
        List<ItemStack> all = new ArrayList<>(remaining);
        all.addAll(session.overflow);
        if (all.isEmpty()) {
            complete(session, false);
        } else {
            setPending(uuid, session.kitName, all);
            saveFile();
        }
    }

    public void handleQuit(Player player) {
        ClaimSession session = sessions.get(player.getUniqueId());
        if (session != null) {
            handleClose(player, session.inventory);
        }
    }

    /** Called from onDisable: stores what is left in every open pickup GUI. */
    public void onDisable() {
        List<ClaimSession> open = new ArrayList<>(sessions.values());
        for (ClaimSession session : open) {
            if (session.inventory != null && !session.finished && !session.rolling) {
                List<ItemStack> all = readInventory(session.inventory);
                all.addAll(session.overflow);
                if (all.isEmpty()) {
                    removePending(session.player.getUniqueId(), session.kitName);
                } else {
                    setPending(session.player.getUniqueId(), session.kitName, all);
                }
            }
        }
        sessions.clear();
        saveFile();
    }

    private void complete(ClaimSession session, boolean reopenPreview) {
        session.finished = true;
        Player player = session.player;
        UUID uuid = player.getUniqueId();
        sessions.remove(uuid);
        removePending(uuid, session.kitName);
        saveFile();

        Kit kit = plugin.getKitsManager().getKitByName(session.kitName);
        if (kit != null) {
            // The cooldown starts now, when everything was taken
            if (kit.getCooldown() != 0 && !PlayerUtils.isPlayerKitsAdmin(player) && !PlayerUtils.hasCooldownBypassPermission(player)) {
                long millisMax = System.currentTimeMillis() + (kit.getCooldown() * 1000L);
                plugin.getPlayerDataManager().setKitCooldown(player, kit.getName(), millisMax);
            }
        }

        List<String> actions = getSource("claim_gui.complete_actions").getStringList("claim_gui.complete_actions");
        for (String action : actions) {
            try {
                plugin.getKitsManager().executeAction(player, action);
            } catch (Exception e) {
                Bukkit.getConsoleSender().sendMessage(PlayerKits2.prefix + MessagesManager.getLegacyColoredMessage(
                        "&cInvalid action on claim_gui.complete_actions: &7" + action));
            }
        }

        if (reopenPreview && kit != null && plugin.getInventoryManager().getInventory("preview_inventory") != null) {
            InventoryPlayer inventoryPlayer = new InventoryPlayer(player, "preview_inventory");
            inventoryPlayer.setKitName(session.kitName);
            inventoryPlayer.setPreviousInventoryName(
                    session.previousInventoryName != null ? session.previousInventoryName : "main_inventory");
            plugin.getInventoryManager().openInventory(inventoryPlayer);
        }
    }

    // ------------------------------------------------------------------ session

    public static class ClaimSession {
        public final Player player;
        public final String kitName;
        public final Inventory inventory;
        public final List<ItemStack> overflow;
        public final String previousInventoryName;
        public boolean finished = false;
        public boolean rolling = false;

        public ClaimSession(Player player, String kitName, Inventory inventory, List<ItemStack> overflow, String previousInventoryName) {
            this.player = player;
            this.kitName = kitName;
            this.inventory = inventory;
            this.overflow = overflow;
            this.previousInventoryName = previousInventoryName;
        }
    }
}
