package net.vicemc.modules.guns;

import net.vicemc.api.ViceModuleContext;
import net.vicemc.api.service.GUIService;
import net.vicemc.api.util.GuiKit;
import net.vicemc.api.util.ItemBuilder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * The admin arsenal: create guns from any held item's texture, tune every
 * stat, publish recipes, spawn serialised guns and hand out ammo.
 */
public final class GunsGui {

    private static final int PER_PAGE = 27;

    private final GunsModule module;
    private final ViceModuleContext ctx;
    private final Map<UUID, GunDefinition> drafts = new ConcurrentHashMap<>();

    public GunsGui(GunsModule module) {
        this.module = module;
        this.ctx = module.context();
    }

    // --- Main admin screen ------------------------------------------------

    public void openAdmin(Player player) {
        var builder = ctx.gui().builder(GuiKit.title("&fGuns Arsenal"), 6);
        GuiKit.frame(builder);
        builder.item(GuiKit.HOW_TO, GuiKit.icon(Material.BOOK, "&fHow guns work",
                "&7Right-click: shoot",
                "&7Shift + right-click: aim",
                "&7Q: reload",
                "&7Each gun has a unique serial,",
                "&7durability and magazine."),
                GuiKit.NONE);
        builder.item(GuiKit.STATUS, GuiKit.icon(Material.CROSSBOW, "&6Firearms Arsenal",
                "&7Saved guns: &f" + module.guns().defs().size(),
                "&7Create, tune and spawn guns",
                "&7from any held item."),
                GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());
        builder.item(GuiKit.ACTION_1, GuiKit.cta(Material.EMERALD, "&aNew gun",
                "&7Create a new gun from scratch.",
                "&7You give it an id, name, type",
                "&7and a texture (any held item)."),
                (p, c) -> askId(p));
        builder.item(GuiKit.ACTION_2, GuiKit.icon(Material.CHEST, "&6Gun library",
                "&7Browse saved guns and",
                "&7spawn them with serials."),
                (p, c) -> openLibrary(p, 1));
        builder.item(GuiKit.ACTION_3, GuiKit.icon(Material.IRON_NUGGET, "&aGive ammo",
                "&7Hand out ammunition for",
                "&7any ammo type."),
                (p, c) -> openAmmoPicker(p));
        builder.item(GuiKit.ACTION_4, GuiKit.icon(Material.ANVIL, "&aRepair held gun",
                "&7Restores the gun you are",
                "&7holding to full durability."),
                (p, c) -> {
                    module.repairHeld(p);
                    openAdmin(p);
                });
        builder.open(player);
    }

    // --- New gun wizard ---------------------------------------------------

    private void askId(Player player) {
        module.prompts().prompt(player,
                "&6New gun &7- type an id (lowercase, no spaces, e.g. &fglock17&7), or &ccancel&7.",
                (p, input) -> {
                    if (input == null || input.isBlank() || input.equalsIgnoreCase("cancel")) {
                        ctx.notifications().msg(p, "&7New gun cancelled.");
                        openAdmin(p);
                        return;
                    }
                    String id = input.trim().toLowerCase();
                    if (!id.matches("[a-z0-9_-]+")) {
                        ctx.notifications().warn(p, "&cUse only lowercase letters, numbers, dash or underscore.");
                        askId(p);
                        return;
                    }
                    if (module.guns().exists(id)) {
                        ctx.notifications().warn(p, "&cA gun with id '&f" + id + "&c' already exists.");
                        askId(p);
                        return;
                    }
                    GunDefinition draft = new GunDefinition();
                    draft.id = id;
                    drafts.put(p.getUniqueId(), draft);
                    askName(p);
                });
    }

    private void askName(Player player) {
        module.prompts().prompt(player,
                "&6New gun &7- type the display name (e.g. &fGlock 17&7), or &ccancel&7.",
                (p, input) -> {
                    GunDefinition draft = drafts.get(p.getUniqueId());
                    if (draft == null) {
                        openAdmin(p);
                        return;
                    }
                    if (input == null || input.isBlank() || input.equalsIgnoreCase("cancel")) {
                        drafts.remove(p.getUniqueId());
                        ctx.notifications().msg(p, "&7New gun cancelled.");
                        openAdmin(p);
                        return;
                    }
                    draft.name = input.trim();
                    openTypePicker(p);
                });
    }

    private void openTypePicker(Player player) {
        var builder = ctx.gui().builder(GuiKit.title("Choose gun type"), 3);
        for (int slot = 0; slot < 27; slot++) {
            builder.item(slot, GuiKit.bar(GuiKit.FILL), GuiKit.NONE);
        }
        builder.item(4, GuiKit.icon(Material.CROSSBOW, "&6Choose gun type",
                "&7Defaults load per type; you",
                "&7can tune everything next."),
                GuiKit.NONE);
        GunType[] types = GunType.values();
        for (int i = 0; i < types.length; i++) {
            GunType type = types[i];
            int slot = 10 + i;
            builder.item(slot, GuiKit.icon(type.icon(), "&6" + type.display(),
                    "&7Damage: &f" + type.damage(),
                    "&7Magazine: &f" + type.magSize(),
                    "&7Fire rate: &f" + type.fireRate() + "/s",
                    "&7Range: &f" + type.range(),
                    "&7Durability: &f" + type.durability(),
                    "&7Ammo: &f" + type.ammo().display()),
                    (p, c) -> {
                        GunDefinition draft = drafts.get(p.getUniqueId());
                        if (draft == null) {
                            openAdmin(p);
                            return;
                        }
                        draft.applyType(type);
                        ctx.notifications().msg(p, "&a" + type.display() + " defaults applied.");
                        openEdit(p);
                    });
        }
        builder.item(22, GuiKit.close(), (p, c) -> p.closeInventory());
        builder.open(player);
    }

    // --- Edit screen ------------------------------------------------------

    public void openEdit(Player player) {
        GunDefinition draft = drafts.get(player.getUniqueId());
        if (draft == null) {
            openAdmin(player);
            return;
        }
        boolean saved = module.guns().exists(draft.id);
        var builder = ctx.gui().builder(GuiKit.title("&fEdit " + draft.name), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back(saved ? "the gun" : "the arsenal"), (p, c) -> {
            drafts.remove(p.getUniqueId());
            if (saved) {
                module.guns().def(draft.id).ifPresent(d -> openDefinition(p, d));
            } else {
                openAdmin(p);
            }
        });
        builder.item(GuiKit.STATUS, preview(draft), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());
        builder.item(GuiKit.ACTION_1, GuiKit.icon(Material.ITEM_FRAME, "&6Set skin",
                "&7Hold the custom item you want",
                "&7as the gun texture and click.",
                "&7Current: &f" + draft.bukkitMaterial().name()
                        + (draft.modelData > 0 ? " &8(#" + draft.modelData + ")" : "")),
                (p, c) -> setSkin(p));
        builder.item(GuiKit.ACTION_2, GuiKit.cta(Material.EMERALD, "&aSave & publish gun",
                "&7Saves the gun to the arsenal",
                "&7so you can spawn it with",
                "&7a fresh serial."),
                (p, c) -> publish(p));

        addStat(builder, player, 0, Material.PAPER, "&fName", "&7" + draft.name, "name");
        addStat(builder, player, 1, draft.gunType().icon(), "&fType", "&7" + draft.gunType().display(), "type");
        addStat(builder, player, 2, Material.IRON_SWORD, "&fDamage", "&7" + draft.damage + " per shot", "damage");
        addStat(builder, player, 3, Material.DIAMOND, "&fMagazine", "&7" + draft.magSize + " rounds", "mag");
        addStat(builder, player, 4, Material.BLAZE_POWDER, "&fFire rate", "&7" + draft.fireRate + " shots/s", "fire");
        addStat(builder, player, 5, Material.COMPASS, "&fRange", "&7" + draft.range + " blocks", "range");
        addStat(builder, player, 6, Material.ANVIL, "&fDurability", "&7" + draft.durability + " shots", "durability");
        addStat(builder, player, 7, Material.SNOWBALL, "&fPellets", "&7" + draft.pellets + " per shot", "pellets");
        addStat(builder, player, 8, Material.CLOCK, "&fReload", "&7" + draft.reloadSeconds + "s", "reload");
        addStat(builder, player, 9, Material.ARROW, "&fSpread", "&7" + draft.spread + " deg", "spread");
        addSoundStat(builder, draft, false);
        addSoundStat(builder, draft, true);
        builder.open(player);
    }

    private void addStat(GUIService.GuiBuilder builder, Player player, int index, Material material,
                         String name, String value, String field) {
        int slot = GuiKit.GRID_FIRST + index;
        builder.item(slot, GuiKit.icon(material, name, value, "&7Click to change."),
                (p, c) -> askStat(p, field));
    }

    private void addSoundStat(GUIService.GuiBuilder builder, GunDefinition draft, boolean reload) {
        String value = reload ? draft.reloadSound : draft.sound;
        String sound = value == null || value.isBlank() ? "default" : value;
        int slot = GuiKit.GRID_FIRST + (reload ? 11 : 10);
        String label = reload ? "&6Reload sound" : "&6Fire sound";
        builder.item(slot, GuiKit.icon(Material.NOTE_BLOCK, label,
                        "&7Current: &f" + sound, "&7Click to choose a custom .ogg sound."),
                (p, c) -> openSoundPicker(p, 1, reload));
    }

    private void openSoundPicker(Player player, int page, boolean reload) {
        GunDefinition draft = drafts.get(player.getUniqueId());
        if (draft == null) {
            openAdmin(player);
            return;
        }
        List<String> sounds = new ArrayList<>();
        sounds.add("default");
        sounds.addAll(module.customSounds());
        int pages = GuiKit.Pages.pages(sounds.size(), PER_PAGE);
        int safePage = Math.max(1, Math.min(page, pages));
        String current = reload ? draft.reloadSound : draft.sound;
        current = current == null || current.isBlank() ? "default" : current;
        String label = reload ? "&6Reload sound" : "&6Fire sound";
        var builder = ctx.gui().builder(GuiKit.title(reload ? "&fChoose reload sound" : "&fChoose fire sound"), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the gun"), (p, c) -> openEdit(p));
        builder.item(GuiKit.STATUS, GuiKit.icon(Material.NOTE_BLOCK, label,
                "&7Current: &f" + current),
                GuiKit.NONE);
        List<String> visible = GuiKit.Pages.slice(sounds, safePage, PER_PAGE);
        for (int i = 0; i < visible.size(); i++) {
            String sound = visible.get(i);
            boolean selected = sound.equals(current);
            builder.item(GuiKit.GRID_FIRST + i,
                    GuiKit.icon(selected ? Material.MUSIC_DISC_13 : Material.NOTE_BLOCK,
                            "&f" + sound, selected ? "&aSelected" : "&7Click to select."),
                    (p, c) -> {
                        if (reload) {
                            draft.reloadSound = sound.equals("default") ? "" : sound;
                        } else {
                            draft.sound = sound.equals("default") ? "" : sound;
                        }
                        ctx.notifications().msg(p, "&a" + (reload ? "Reload" : "Fire")
                                + " sound set to &f" + sound + "&a.");
                        openEdit(p);
                    });
        }
        addPaging(builder, player, safePage, pages, (p, next) -> openSoundPicker(p, next, reload));
        builder.open(player);
    }

    private void setSkin(Player player) {
        GunDefinition draft = drafts.get(player.getUniqueId());
        if (draft == null) {
            openAdmin(player);
            return;
        }
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held == null || held.getType().isAir()) {
            ctx.notifications().warn(player, "&cHold the custom item you want as the gun texture, then click again.");
            openEdit(player);
            return;
        }
        draft.material = held.getType().name();
        var heldMeta = held.getItemMeta();
        draft.modelData = heldMeta != null && heldMeta.hasCustomModelData() ? heldMeta.getCustomModelData() : 0;
        ctx.notifications().msg(player, "&aSkin set to &f" + held.getType().name()
                + (draft.modelData > 0 ? " &8(#" + draft.modelData + ")" : "") + "&a.");
        openEdit(player);
    }

    private void publish(Player player) {
        GunDefinition draft = drafts.get(player.getUniqueId());
        if (draft == null) {
            openAdmin(player);
            return;
        }
        clamp(draft);
        if (draft.id.isEmpty() || draft.name.isEmpty()) {
            ctx.notifications().warn(player, "&cGive the gun an id and a name first.");
            openEdit(player);
            return;
        }
        module.guns().save(draft);
        drafts.remove(player.getUniqueId());
        ctx.notifications().msg(player, "&aSaved &f" + draft.name + "&a to the arsenal.");
        openDefinition(player, draft);
    }

    private void clamp(GunDefinition d) {
        d.damage = Math.max(0.5, Math.min(100, d.damage));
        d.magSize = Math.max(1, Math.min(200, d.magSize));
        d.fireRate = Math.max(0.5, Math.min(30, d.fireRate));
        d.range = Math.max(5, Math.min(500, d.range));
        d.durability = Math.max(1, Math.min(10000, d.durability));
        d.pellets = Math.max(1, Math.min(20, d.pellets));
        d.reloadSeconds = Math.max(0.5, Math.min(10, d.reloadSeconds));
        d.spread = Math.max(0, Math.min(30, d.spread));
    }

    private void askStat(Player player, String field) {
        GunDefinition draft = drafts.get(player.getUniqueId());
        if (draft == null) {
            openAdmin(player);
            return;
        }
        switch (field) {
            case "name" -> askText(player, field,
                    "&6Name &7- type the display name, or &ccancel&7.");
            case "type" -> openTypePicker(player);
            case "damage" -> askNumber(player, field,
                    "&6Damage per shot (0.5-100). Current: &f" + draft.damage + "&6.", 0.5, 100, false);
            case "mag" -> askNumber(player, field,
                    "&6Magazine size (1-200). Current: &f" + draft.magSize + "&6.", 1, 200, true);
            case "fire" -> askNumber(player, field,
                    "&6Shots per second (0.5-30). Current: &f" + draft.fireRate + "&6.", 0.5, 30, false);
            case "range" -> askNumber(player, field,
                    "&6Max range in blocks (5-500). Current: &f" + draft.range + "&6.", 5, 500, true);
            case "durability" -> askNumber(player, field,
                    "&6Durability (1-10000). Current: &f" + draft.durability + "&6.", 1, 10000, true);
            case "pellets" -> askNumber(player, field,
                    "&6Pellets per shot (1-20). Current: &f" + draft.pellets + "&6.", 1, 20, true);
            case "reload" -> askNumber(player, field,
                    "&6Reload seconds (0.5-10). Current: &f" + draft.reloadSeconds + "&6.", 0.5, 10, false);
            case "spread" -> askNumber(player, field,
                    "&6Spread in degrees (0-30). Current: &f" + draft.spread + "&6.", 0, 30, false);
            default -> openEdit(player);
        }
    }

    private void askText(Player player, String field, String message) {
        module.prompts().prompt(player, message, (p, input) -> {
            GunDefinition draft = drafts.get(p.getUniqueId());
            if (draft == null) {
                openAdmin(p);
                return;
            }
            if (input == null || input.isBlank() || input.equalsIgnoreCase("cancel")) {
                openEdit(p);
                return;
            }
            if (field.equals("name")) {
                draft.name = input.trim();
            }
            openEdit(p);
        });
    }

    private void askNumber(Player player, String field, String message, double min, double max, boolean whole) {
        module.prompts().prompt(player, message + " Or type &ccancel&7.", (p, input) -> {
            GunDefinition draft = drafts.get(p.getUniqueId());
            if (draft == null) {
                openAdmin(p);
                return;
            }
            if (input == null || input.isBlank() || input.equalsIgnoreCase("cancel")) {
                openEdit(p);
                return;
            }
            double value;
            try {
                value = Double.parseDouble(input.trim());
            } catch (NumberFormatException ex) {
                ctx.notifications().warn(p, "&cThat is not a number.");
                askStat(p, field);
                return;
            }
            value = Math.max(min, Math.min(max, value));
            if (whole) {
                long rounded = Math.round(value);
                value = rounded;
            }
            switch (field) {
                case "damage" -> draft.damage = value;
                case "mag" -> draft.magSize = (int) value;
                case "fire" -> draft.fireRate = value;
                case "range" -> draft.range = (int) value;
                case "durability" -> draft.durability = (int) value;
                case "pellets" -> draft.pellets = (int) value;
                case "reload" -> draft.reloadSeconds = value;
                case "spread" -> draft.spread = value;
                default -> {
                }
            }
            openEdit(p);
        });
    }

    private ItemStack preview(GunDefinition draft) {
        return module.guns().gunItem(draft, "NEW-000000", draft.durability, draft.magSize);
    }

    // --- Gun library ------------------------------------------------------

    public void openLibrary(Player player, int page) {
        List<GunDefinition> defs = module.guns().defs();
        int pages = GuiKit.Pages.pages(defs.size(), PER_PAGE);
        int safe = Math.min(page, pages);
        var builder = ctx.gui().builder(GuiKit.title("&fGun Library"), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the arsenal"), (p, c) -> openAdmin(p));
        builder.item(GuiKit.STATUS, GuiKit.icon(Material.CHEST, "&6Gun library",
                "&7Saved guns: &f" + defs.size(),
                "&7Click a gun to spawn it,",
                "&7give it away or edit it."),
                GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());
        List<GunDefinition> slice = GuiKit.Pages.slice(defs, safe, PER_PAGE);
        for (int i = 0; i < slice.size(); i++) {
            GunDefinition def = slice.get(i);
            ItemStack item = module.guns().gunItem(def, "NEW-000000", def.durability, def.magSize);
            builder.item(GuiKit.GRID_FIRST + i, item, (p, c) -> openDefinition(p, def));
        }
        addPaging(builder, player, safe, pages, (p, next) -> openLibrary(p, next));
        builder.open(player);
    }

    // --- Single gun screen ------------------------------------------------

    public void openDefinition(Player player, GunDefinition def) {
        var builder = ctx.gui().builder(GuiKit.title("&f" + def.name), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the gun library"), (p, c) -> openLibrary(p, 1));
        builder.item(GuiKit.STATUS, module.guns().gunItem(def, "VIC-000000", def.durability, def.magSize),
                GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());
        builder.item(GuiKit.ACTION_1, GuiKit.cta(Material.CROSSBOW, "&aSpawn a gun",
                "&7Gives you a fresh gun with",
                "&7a brand new serial, full",
                "&7durability and magazine."),
                (p, c) -> {
                    module.giveGun(p, p, def);
                    openDefinition(p, def);
                });
        builder.item(GuiKit.ACTION_2, GuiKit.icon(Material.PLAYER_HEAD, "&6Give to player",
                "&7Spawn a serialised gun",
                "&7into another player's",
                "&7inventory."),
                (p, c) -> givePlayer(p, def, 1));
        builder.item(GuiKit.ACTION_3, GuiKit.icon(Material.WRITABLE_BOOK, "&6Edit",
                "&7Tune any stat or change",
                "&7the skin."),
                (p, c) -> {
                    drafts.put(p.getUniqueId(), def.copy());
                    openEdit(p);
                });
        builder.item(GuiKit.ACTION_4, GuiKit.icon(Material.RED_WOOL, "&cDelete",
                "&7Removes the gun from the",
                "&7arsenal. Existing guns stop",
                "&7working."),
                (p, c) -> openDeleteConfirm(p, def));
        builder.open(player);
    }

    public void givePlayer(Player player, GunDefinition def, int page) {
        List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
        int pages = GuiKit.Pages.pages(online.size(), PER_PAGE);
        int safe = Math.min(page, pages);
        var builder = ctx.gui().builder(GuiKit.title("Give " + def.name), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the gun"), (p, c) -> openDefinition(p, def));
        builder.item(GuiKit.STATUS, GuiKit.icon(Material.PLAYER_HEAD, "&6Give to player",
                "&7Who gets a fresh serialised",
                "&7" + def.name + "?"),
                GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());
        List<Player> slice = GuiKit.Pages.slice(online, safe, PER_PAGE);
        for (int i = 0; i < slice.size(); i++) {
            Player target = slice.get(i);
            builder.item(GuiKit.GRID_FIRST + i,
                    GuiKit.playerHead(target.getName(), "&f" + target.getName(),
                            "&7Click to give them a " + def.name + "."),
                    (p, c) -> {
                        module.giveGun(p, target, def);
                        openDefinition(p, def);
                    });
        }
        addPaging(builder, player, safe, pages, (p, next) -> givePlayer(p, def, next));
        builder.open(player);
    }

    // --- Ammo -------------------------------------------------------------

    public void openAmmoPicker(Player player) {
        var builder = ctx.gui().builder(GuiKit.title("&fGive Ammo"), 3);
        for (int slot = 0; slot < 27; slot++) {
            builder.item(slot, GuiKit.bar(GuiKit.FILL), GuiKit.NONE);
        }
        builder.item(4, GuiKit.icon(Material.IRON_NUGGET, "&aGive ammo",
                "&7Pick an ammo type, then",
                "&7type how many rounds."),
                GuiKit.NONE);
        AmmoType[] types = AmmoType.values();
        for (int i = 0; i < types.length; i++) {
            AmmoType type = types[i];
            int slot = 10 + i;
            builder.item(slot, module.guns().ammoItem(type).clone(),
                    (p, c) -> askAmmoAmount(p, type));
        }
        builder.item(22, GuiKit.close(), (p, c) -> p.closeInventory());
        builder.open(player);
    }

    private void askAmmoAmount(Player player, AmmoType type) {
        module.prompts().prompt(player,
                "&6Ammo &7- how many &f" + type.display() + "&7? Type a number, or &ccancel&7.",
                (p, input) -> {
                    if (input == null || input.isBlank() || input.equalsIgnoreCase("cancel")) {
                        openAmmoPicker(p);
                        return;
                    }
                    int amount;
                    try {
                        amount = Integer.parseInt(input.trim());
                    } catch (NumberFormatException ex) {
                        ctx.notifications().warn(p, "&cThat is not a number.");
                        askAmmoAmount(p, type);
                        return;
                    }
                    if (amount <= 0) {
                        ctx.notifications().warn(p, "&cAmount must be at least 1.");
                        askAmmoAmount(p, type);
                        return;
                    }
                    module.giveAmmo(p, type, Math.min(amount, 2304));
                    openAmmoPicker(p);
                });
    }

    // --- Delete -----------------------------------------------------------

    private void openDeleteConfirm(Player player, GunDefinition def) {
        var builder = ctx.gui().builder(GuiKit.title("Delete " + def.name), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the gun"), (p, c) -> openDefinition(p, def));
        builder.item(GuiKit.STATUS, GuiKit.icon(Material.RED_WOOL, "&cDelete " + def.name + "?",
                "&7This removes the gun from",
                "&7the arsenal. Existing guns",
                "&7with this recipe stop working.",
                "&7This cannot be undone."),
                GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());
        builder.item(GuiKit.ACTION_3, GuiKit.cta(Material.GREEN_WOOL, "&aConfirm delete",
                "&7Delete " + def.name + "."), (p, c) -> {
            module.guns().delete(def.id);
            drafts.remove(p.getUniqueId());
            ctx.notifications().msg(p, "&aDeleted &f" + def.name + "&a.");
            openLibrary(p, 1);
        });
        builder.item(GuiKit.ACTION_5, GuiKit.icon(Material.BARRIER, "&7Cancel",
                "&7Keep the gun."), (p, c) -> openDefinition(p, def));
        builder.open(player);
    }

    // --- Helpers ----------------------------------------------------------

    private void addPaging(GUIService.GuiBuilder builder, Player player, int page, int pages,
                           BiConsumer<Player, Integer> opener) {
        if (page > 1) {
            builder.item(GuiKit.PAGE_PREV, GuiKit.prevPage(page), (p, c) -> opener.accept(p, page - 1));
        } else {
            builder.item(GuiKit.PAGE_PREV, GuiKit.pageGap(), GuiKit.NONE);
        }
        builder.item(GuiKit.PAGE_INDICATOR, GuiKit.pageIndicator(page, pages), GuiKit.NONE);
        if (page < pages) {
            builder.item(GuiKit.PAGE_NEXT, GuiKit.nextPage(page), (p, c) -> opener.accept(p, page + 1));
        } else {
            builder.item(GuiKit.PAGE_NEXT, GuiKit.pageGap(), GuiKit.NONE);
        }
    }
}
