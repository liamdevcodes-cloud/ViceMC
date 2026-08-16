package net.vicemc.modules.lifestyle;

import net.vicemc.api.ViceModuleContext;
import net.vicemc.api.util.GuiKit;
import net.vicemc.api.util.ItemBuilder;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Lifestyle chest GUI: shows every stat at a glance - health, food,
 * saturation, rest (sleep), thirst, fitness, walk speed, empty bottles and
 * your balance - with a one-click bottle deposit.
 */
public final class LifestyleGui {

    private final ViceModuleContext ctx;
    private final LifestyleModule module;

    public LifestyleGui(ViceModuleContext ctx, LifestyleModule module) {
        this.ctx = ctx;
        this.module = module;
    }

    public void openDashboard(Player player) {
        PlayerState state = module.manager().getOrLoad(player.getUniqueId());
        var builder = ctx.gui().builder(GuiKit.title("Lifestyle"), 6);
        GuiKit.frame(builder);
        builder.item(GuiKit.HOW_TO, GuiKit.icon(Material.BOOK, "&3How it works",
                "&7Sleep when you feel tired and",
                "&7drink water when thirsty. Food",
                "&7shapes your fitness and your",
                "&7walking speed. Empty bottles",
                "&7from energy drinks are refundable."), GuiKit.NONE);
        builder.item(GuiKit.STATUS, overviewItem(player, state), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());

        builder.item(GuiKit.ACTION_1, ItemBuilder.of(Material.RED_DYE)
                .name("&cHealth")
                .lore("&7Current: &f" + one(player.getHealth()) + " &7/ " + one(player.getMaxHealth()),
                        "&7Hunger damage and dehydration",
                        "&7eat away at your health.")
                .build(), GuiKit.NONE);
        builder.item(GuiKit.ACTION_2, ItemBuilder.of(Material.COOKED_BEEF)
                .name("&6Food")
                .lore("&7Food bars: &f" + player.getFoodLevel() + " &7/ 20",
                        "&7Eat food to restore hunger.")
                .build(), GuiKit.NONE);
        builder.item(GuiKit.ACTION_3, ItemBuilder.of(Material.CHICKEN)
                .name("&7Saturation")
                .lore("&7Saturation: &f" + one(player.getSaturation()),
                        "&7Food padding that delays",
                        "&7your hunger from dropping.")
                .build(), GuiKit.NONE);

        int bottles = module.manager().countEmptyBottles(player);
        double refund = module.config().getDouble("bottle.refund", 25) * bottles;
        builder.item(GuiKit.ACTION_4, ItemBuilder.of(Material.GLASS_BOTTLE)
                .name("&6Bottle deposit")
                .lore("&7Empty bottles: &f" + bottles,
                        "&7Refund: &6" + GuiKit.fmt(refund),
                        "",
                        "&aClick to turn them in for money.",
                        "&7Statiegeld - just like in Europe.")
                .build(), (p, c) -> {
            module.manager().turnInBottles(p);
            openDashboard(p);
        });

        builder.item(GuiKit.ACTION_5, ItemBuilder.of(Material.RED_BED)
                .name("&eRest & sleep")
                .lore("&7Rest: &f" + pct(state.rest) + "%",
                        "&71 minute in bed = 1 hour of sleep.",
                        "&7Energy drinks refill rest instantly,",
                        "&7so you can skip sleeping.")
                .build(), GuiKit.NONE);

        builder.item(18, ItemBuilder.of(Material.IRON_BOOTS)
                .name("&fWalk speed")
                .lore("&7Speed: &fx" + String.format("%.2f", state.speedFactor(module.config())),
                        "&7Fitness sets your speed:",
                        "&f  0.75x&7 at fitness 0,",
                        "&f  1.5x&7 at fitness 100.")
                .build(), GuiKit.NONE);
        builder.item(19, ItemBuilder.of(Material.GOLD_INGOT)
                .name("&6Balance")
                .lore("&7You have &6" + GuiKit.fmt(module.balance(player)) + "&7.",
                        "&7Empty bottles turn in for cash,",
                        "&7slept-in rest keeps you fast.")
                .build(), GuiKit.NONE);

        builder.open(player);
    }

    private ItemStack overviewItem(Player player, PlayerState state) {
        return ItemBuilder.of(Material.CLOCK)
                .name("&6Your lifestyle")
                .lore("&7Rest:    " + bar(state.rest, module.config().getDouble("rest.warn-below", 25)),
                        "&7Thirst:  " + bar(state.thirst, module.config().getDouble("thirst.warn-below", 25)),
                        "&7Fitness: " + bar(state.fitness, module.config().getDouble("fitness.warn-below", 25)),
                        "",
                        "&7Speed: &fx" + String.format("%.2f", state.speedFactor(module.config())))
                .build();
    }

    private String bar(double pct, double warnBelow) {
        int filled = (int) Math.round(pct / 10.0);
        String color = pct >= 60 ? "&a" : pct >= warnBelow ? "&e" : "&c";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            sb.append(i < filled ? color + "\u2588" : "&7\u2591");
        }
        return sb.toString();
    }

    private String pct(double value) {
        return String.valueOf((int) Math.round(value));
    }

    private String one(double value) {
        return value == Math.floor(value) ? String.valueOf((long) value) : String.format("%.1f", value);
    }
}
