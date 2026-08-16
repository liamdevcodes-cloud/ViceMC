package net.vicemc.modules.lifestyle;

import net.vicemc.api.ViceModule;
import net.vicemc.api.ViceModuleContext;
import net.vicemc.api.service.CommandContext;
import net.vicemc.api.service.CommandSpec;
import net.vicemc.api.util.YamlConfig;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Sleep, thirst, energy drinks, bottle deposits and fitness. Custom foods
 * and drinks live in lifestyle.yml and can be edited in-game by admins.
 */
public final class LifestyleModule implements ViceModule {

    private ViceModuleContext ctx;
    private YamlConfig config;
    private LifestyleItems items;
    private LifestyleManager manager;
    private LifestyleGui gui;

    @Override
    public String id() {
        return "lifestyle";
    }

    @Override
    public String displayName() {
        return "Vice Lifestyle";
    }

    @Override
    public String version() {
        return "1.0.0";
    }

    @Override
    public void onEnable(ViceModuleContext context) {
        this.ctx = context;
        this.config = ctx.yaml("lifestyle.yml");
        this.items = new LifestyleItems(ctx, config);
        this.manager = new LifestyleManager(ctx, config, items);
        this.gui = new LifestyleGui(ctx, this);

        Bukkit.getPluginManager().registerEvents(manager, ctx.plugin());
        manager.start();

        registerCommands();
        ctx.logger().info("Lifestyle module ready.");
    }

    @Override
    public void onDisable() {
        if (manager != null) {
            manager.stop();
            manager.saveAll();
        }
    }

    private void registerCommands() {
        ctx.commands().register(ctx.plugin(), CommandSpec.builder()
                .name("lifestyle")
                .aliases("ls")
                .description("Your lifestyle status, catalogue and bottle deposit")
                .executes(this::lifestyle)
                .tabulates((c, a) -> {
                    if (a.size() <= 1) {
                        return List.of("deposit", "info", "reload", "give", "name");
                    }
                    if (a.get(0).equals("give") && a.size() == 2) {
                        return itemIds();
                    }
                    if (a.get(0).equals("give") && a.size() == 3) {
                        return playerNames();
                    }
                    if (a.get(0).equals("name") && a.size() == 2) {
                        return itemIds();
                    }
                    return List.of();
                })
                .build());
    }

    private void lifestyle(CommandContext c) {
        switch (c.arg(0)) {
            case "deposit" -> deposit(c);
            case "info" -> info(c);
            case "reload" -> reload(c);
            case "give" -> give(c);
            case "name" -> name(c);
            default -> {
                if (c.isPlayer()) {
                    gui.openDashboard(c.player());
                } else {
                    c.msg("&6/lifestyle deposit | info | give <item> [player] [amount] | name <id> <name> | reload");
                }
            }
        }
    }

    private void deposit(CommandContext c) {
        if (!c.isPlayer()) {
            return;
        }
        gui.openDashboard(c.player());
    }

    private void info(CommandContext c) {
        if (!c.isPlayer()) {
            return;
        }
        PlayerState state = manager.getOrLoad(c.player().getUniqueId());
        c.msg("&6Your lifestyle:"
                + "  &7Rest: &f" + manager.pct(state.rest) + "%"
                + " &7Thirst: &f" + manager.pct(state.thirst) + "%"
                + " &7Fitness: &f" + manager.pct(state.fitness) + "%"
                + " &7Speed: &fx" + String.format("%.2f", state.speedFactor(config))
                + "  &7Empty bottles: &f" + manager.countEmptyBottles(c.player()));
    }

    private void reload(CommandContext c) {
        if (!c.isPlayer() || !c.player().hasPermission("vicemc.lifestyle.admin")) {
            c.error("You are not authorized.");
            return;
        }
        config.reload();
        items.reload();
        c.msg("&aLifestyle config reloaded.");
    }

    private void give(CommandContext c) {
        if (!c.isPlayer()) {
            return;
        }
        if (!c.player().hasPermission("vicemc.lifestyle.admin")) {
            c.error("You are not authorized.");
            return;
        }
        if (c.size() < 2) {
            c.usage("/lifestyle give <item> [player] [amount]");
            return;
        }
        LifestyleItem item = items.byId(c.arg(1)).orElse(null);
        if (item == null) {
            c.error("Unknown item. See the catalogue with /lifestyle admin.");
            return;
        }
        Player target = c.playerArg(2);
        if (target == null) {
            target = c.player();
        }
        int amount = c.argInt(3, 1);
        amount = Math.max(1, Math.min(64, amount));
        ItemStack stack = items.build(item, amount);
        var left = target.getInventory().addItem(stack);
        if (!left.isEmpty()) {
            target.getWorld().dropItemNaturally(target.getLocation(), left.values().iterator().next());
        }
        c.msg("&aGave &f" + amount + "x " + item.name + "&a to &f" + target.getName() + "&a.");
    }

    private void name(CommandContext c) {
        if (!c.isPlayer()) {
            return;
        }
        if (!c.player().hasPermission("vicemc.lifestyle.admin")) {
            c.error("You are not authorized.");
            return;
        }
        if (c.size() < 3) {
            c.usage("/lifestyle name <id> <new name>");
            return;
        }
        LifestyleItem item = items.byId(c.arg(1)).orElse(null);
        if (item == null) {
            c.error("Unknown item.");
            return;
        }
        String newName = joinArgs(c, 2, c.size() - 1);
        items.renameItem(item.id, newName);
        c.msg("&aRenamed item to &f" + newName + "&a.");
    }

    private String joinArgs(CommandContext c, int from, int to) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i <= to; i++) {
            if (i > from) {
                sb.append(' ');
            }
            sb.append(c.arg(i));
        }
        return sb.toString();
    }

    // --- GUI facade -------------------------------------------------------

    public ViceModuleContext context() {
        return ctx;
    }

    public YamlConfig config() {
        return config;
    }

    public LifestyleItems items() {
        return items;
    }

    public LifestyleManager manager() {
        return manager;
    }

    public LifestyleGui gui() {
        return gui;
    }

    public double balance(Player player) {
        return ctx.economy().balance(player.getUniqueId());
    }

    private List<String> itemIds() {
        return items.all().stream().map(i -> i.id).toList();
    }

    private List<String> playerNames() {
        return new ArrayList<>(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList());
    }
}
