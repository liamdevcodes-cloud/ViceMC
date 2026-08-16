package net.vicemc.modules.cosmetics;

import net.vicemc.api.ViceModule;
import net.vicemc.api.ViceModuleContext;
import net.vicemc.api.service.CommandContext;
import net.vicemc.api.service.CommandSpec;
import net.vicemc.api.util.Text;
import net.vicemc.api.util.YamlConfig;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

import java.util.List;

/**
 * Collectible armor cosmetics as plain inventory items with the no-drop rule
 * and the Blood Diamond Mine exception. There is no /cosmetics GUI - gear is
 * handed out in-game and lives in the player's inventory or in their house.
 */
public final class CosmeticsModule implements ViceModule, Listener {

    private ViceModuleContext ctx;
    private YamlConfig config;
    private CosmeticManager cosmetics;

    @Override
    public String id() {
        return "cosmetics";
    }

    @Override
    public String displayName() {
        return "Vice Cosmetics";
    }

    @Override
    public String version() {
        return "1.0.0";
    }

    @Override
    public void onEnable(ViceModuleContext context) {
        this.ctx = context;
        this.config = ctx.yaml("cosmetics.yml");
        this.cosmetics = new CosmeticManager(ctx, config);
        Bukkit.getPluginManager().registerEvents(this, ctx.plugin());

        ctx.commands().register(ctx.plugin(), CommandSpec.builder()
                .name("cosmetics")
                .description("Collectible cosmetic armor")
                .executes(this::cosmetics)
                .tabulates((c, a) -> a.size() <= 1
                        ? List.of("list", "give")
                        : a.get(0).equals("give") ? setIds() : playerNames())
                .build());

        ctx.logger().info("Cosmetics module ready.");
    }

    private void cosmetics(CommandContext c) {
        switch (c.arg(0)) {
            case "list" -> list(c);
            case "give" -> give(c);
            default -> c.msg("&6/cosmetics list | give <set> [player]");
        }
    }

    private void list(CommandContext c) {
        c.msg("&6Cosmetic sets:");
        for (CosmeticSet set : cosmetics.sets()) {
            c.msg("  &f" + set.id + " &7- " + Text.color(set.name)
                    + (set.mask ? " &7(&8mask&7)" : ""));
        }
    }

    private void give(CommandContext c) {
        if (!c.isPlayer()) {
            return;
        }
        if (c.size() < 2) {
            c.usage("/cosmetics give <set> [player]");
            return;
        }
        CosmeticSet set = cosmetics.set(c.arg(1));
        if (set == null) {
            c.error("Unknown set. Valid: " + String.join(", ", setIds()));
            return;
        }
        Player target = c.size() > 2 ? c.playerArg(2) : c.player();
        if (target == null) {
            c.error("Player not found.");
            return;
        }
        if (!target.equals(c.player()) && !c.player().hasPermission("vicemc.cosmetics.give")) {
            c.error("You are not allowed to give cosmetics to others.");
            return;
        }
        cosmetics.giveSet(target, set);
        c.msg("&aGave " + Text.color(set.name) + "&a to " + target.getName() + ".");
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        cosmetics.protectOnDeath(event.getEntity(), event.getDrops(), event.getKeepInventory());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        cosmetics.restoreOnRespawn(event.getPlayer());
    }

    private List<String> setIds() {
        return cosmetics.sets().stream().map(s -> s.id).toList();
    }

    private List<String> playerNames() {
        return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
    }
}
