package pk.ajneb97.listeners;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import pk.ajneb97.PlayerKits2;
import pk.ajneb97.managers.ClaimGuiManager;

/**
 * Rules of the "Odbierz przedmioty" GUI: the player can only TAKE items out of it.
 * Nothing can be put inside.
 */
public class ClaimGuiListener implements Listener {

    private final PlayerKits2 plugin;

    public ClaimGuiListener(PlayerKits2 plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }
        Player player = (Player) event.getWhoClicked();
        ClaimGuiManager manager = plugin.getClaimGuiManager();
        if (manager == null) {
            return;
        }
        final ClaimGuiManager.ClaimSession session = manager.getSession(player);
        if (session == null || session.inventory == null) {
            return;
        }
        Inventory top = event.getInventory();
        if (!session.inventory.equals(top)) {
            return;
        }

        Inventory clicked = event.getClickedInventory();
        boolean clickedTop = clicked != null && clicked.equals(top);
        InventoryAction action = event.getAction();

        if (clickedTop) {
            switch (action) {
                case PICKUP_ALL:
                case PICKUP_HALF:
                case PICKUP_SOME:
                case PICKUP_ONE:
                case MOVE_TO_OTHER_INVENTORY:
                case DROP_ALL_SLOT:
                case DROP_ONE_SLOT:
                    break;
                case HOTBAR_SWAP:
                case HOTBAR_MOVE_AND_READD:
                    if (!hotbarTargetEmpty(player, event)) {
                        event.setCancelled(true);
                        return;
                    }
                    break;
                default:
                    event.setCancelled(true);
                    return;
            }
        } else {
            // Click on the player's own inventory (or outside the window).
            // Shift-click would push items into the GUI, double click would pull items from it.
            if (action == InventoryAction.MOVE_TO_OTHER_INVENTORY || action == InventoryAction.COLLECT_TO_CURSOR) {
                event.setCancelled(true);
                return;
            }
        }

        Bukkit.getScheduler().runTask(plugin, new Runnable() {
            @Override
            public void run() {
                plugin.getClaimGuiManager().afterClick(session);
            }
        });
    }

    private boolean hotbarTargetEmpty(Player player, InventoryClickEvent event) {
        int button = event.getHotbarButton();
        ItemStack target;
        if (button >= 0) {
            target = player.getInventory().getItem(button);
        } else {
            target = player.getInventory().getItemInOffHand();
        }
        return target == null || target.getType().equals(Material.AIR);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }
        ClaimGuiManager manager = plugin.getClaimGuiManager();
        if (manager == null) {
            return;
        }
        ClaimGuiManager.ClaimSession session = manager.getSession((Player) event.getWhoClicked());
        if (session == null || session.inventory == null) {
            return;
        }
        Inventory top = event.getInventory();
        if (!session.inventory.equals(top)) {
            return;
        }
        for (int rawSlot : event.getRawSlots()) {
            if (rawSlot < top.getSize()) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player)) {
            return;
        }
        ClaimGuiManager manager = plugin.getClaimGuiManager();
        if (manager == null) {
            return;
        }
        manager.handleClose((Player) event.getPlayer(), event.getInventory());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        ClaimGuiManager manager = plugin.getClaimGuiManager();
        if (manager == null) {
            return;
        }
        manager.handleQuit(event.getPlayer());
    }
}
