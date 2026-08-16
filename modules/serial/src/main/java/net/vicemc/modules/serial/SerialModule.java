package net.vicemc.modules.serial;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.vicemc.api.ViceModule;
import net.vicemc.api.ViceModuleContext;
import net.vicemc.api.service.CommandContext;
import net.vicemc.api.service.CommandSpec;
import net.vicemc.api.util.YamlConfig;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Limited-run serialized items. Staff open a console GUI with /serial, deposit
 * a custom piece, pick a copy count and create the run, then hand copies out
 * from a distribution chest. Copies in game are final; runs and copies can be
 * voided to invalidate (and optionally remove) every matching item.
 */
public final class SerialModule implements ViceModule {

    private ViceModuleContext ctx;
    private YamlConfig config;
    private SerialManager manager;
    private SerialGui gui;
    private ScheduledTask ticker;

    @Override
    public String id() {
        return "serial";
    }

    @Override
    public String displayName() {
        return "Vice Serial";
    }

    @Override
    public String version() {
        return "1.0.0";
    }

    @Override
    public void onEnable(ViceModuleContext context) {
        this.ctx = context;
        this.config = ctx.yaml("serial.yml");
        this.manager = new SerialManager(ctx, config);
        this.gui = new SerialGui(manager, ctx.plugin());
        Bukkit.getPluginManager().registerEvents(gui, ctx.plugin());
        registerCommands();
        scheduleTicker();
        ctx.logger().info("Serial module ready.");
    }

    @Override
    public void onDisable() {
        if (ticker != null) {
            cancelQuietly(ticker);
            ticker = null;
        }
        if (gui != null) {
            gui.closeAll();
        }
    }

    private void registerCommands() {
        ctx.commands().register(ctx.plugin(), CommandSpec.builder()
                .name("serial")
                .aliases("limited")
                .permission("vicemc.serial.admin")
                .description("Limited-run serialized items issued through chest GUIs")
                .executes(this::serial)
                .tabulates((c, a) -> a.size() <= 1 ? List.of("debug", "reload", "scan") : List.of())
                .build());
    }

    private void serial(CommandContext c) {
        if (!c.isPlayer()) {
            c.error("This is a player command - run it in game to open the serial GUI.");
            return;
        }
        String sub = c.arg(0);
        if (sub.isEmpty()) {
            gui.openConsole(c.player());
            return;
        }
        switch (sub) {
            case "debug" -> debug(c);
            case "reload" -> reload(c);
            case "scan" -> scan(c);
            default -> c.msg("&6/serial [debug|reload|scan]");
        }
    }

    private void debug(CommandContext c) {
        List<SerialRun> all = manager.all();
        int total = 0;
        int claimed = 0;
        int voided = 0;
        for (SerialRun run : all) {
            total += run.copies.size();
            for (SerialCopy copy : run.copies.values()) {
                if (copy.voided) {
                    voided++;
                } else if (copy.claimed) {
                    claimed++;
                }
            }
        }
        c.msg("&6Serial system debug:");
        c.msg("  &7Runs: &f" + all.size() + " &7- copies: &f" + total
                + " &7(claimed &f" + claimed + "&7, in chest &f" + (total - claimed - voided)
                + "&7, voided &f" + voided + "&7)");
        int[] totals = {0, 0};
        sweepOnlinePlayers(c, totals, (player, acc) -> {
            acc[0] += manager.validatePlayer(player);
            acc[1] += manager.scanPlayer(player);
        }, acc -> {
            c.msg("  &7Lore validated/restamped on &f" + acc[0] + "&7 item(s) online.");
            c.msg("  &7Voided items removed from online inventories: &f" + acc[1]);
        });
    }

    private void reload(CommandContext c) {
        config.reload();
        c.msg("&aSerial config reloaded.");
    }

    private void scan(CommandContext c) {
        int[] removed = {0};
        sweepOnlinePlayers(c, removed, (player, acc) -> acc[0] += manager.scanPlayer(player), acc -> {
            if (acc[0] > 0) {
                c.msg("&cRemoved &f" + acc[0] + "&c voided serialized item(s) from online inventories.");
            } else {
                c.msg("&aScan complete - no voided serialized items found online.");
            }
        });
    }

    /**
     * Runs {@code perPlayer} for every online player on that player's own region
     * thread, then runs {@code after} back on the issuing player's thread once all
     * sweeps have finished.
     */
    private void sweepOnlinePlayers(CommandContext c, int[] totals,
            BiConsumer<Player, int[]> perPlayer, Consumer<int[]> after) {
        Player issuer = c.player();
        List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
        if (players.isEmpty()) {
            after.accept(totals);
            return;
        }
        AtomicInteger pending = new AtomicInteger(players.size());
        Runnable done = () -> issuer.getScheduler().run(ctx.plugin(),
                task -> after.accept(totals), null);
        Runnable tick = () -> {
            if (pending.decrementAndGet() == 0) {
                done.run();
            }
        };
        for (Player player : players) {
            player.getScheduler().run(ctx.plugin(),
                    task -> {
                        perPlayer.accept(player, totals);
                        tick.run();
                    }, tick);
        }
    }

    private void scheduleTicker() {
        long interval = Math.max(1L, config.getInt("ticker-seconds", 5)) * 20L;
        ticker = Bukkit.getGlobalRegionScheduler().runAtFixedRate(ctx.plugin(),
                task -> {
                    for (Player player : Bukkit.getOnlinePlayers()) {
                        player.getScheduler().run(ctx.plugin(),
                                pTask -> manager.validatePlayer(player), null);
                    }
                    manager.checkDuplicates();
                }, interval, interval);
    }

    private static void cancelQuietly(ScheduledTask task) {
        try {
            task.cancel();
        } catch (IllegalStateException ignored) {
        }
    }
}
