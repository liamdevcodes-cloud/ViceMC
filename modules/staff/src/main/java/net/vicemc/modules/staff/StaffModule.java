package net.vicemc.modules.staff;

import com.google.gson.reflect.TypeToken;
import net.vicemc.api.ViceModule;
import net.vicemc.api.ViceModuleContext;
import net.vicemc.api.service.CommandContext;
import net.vicemc.api.service.CommandSpec;
import net.vicemc.api.util.Json;
import net.vicemc.api.util.Text;
import net.vicemc.api.util.YamlConfig;
import net.vicemc.core.ViceCore;
import net.vicemc.modules.business.BusinessModule;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Vice Staff - a moderator tool that can inspect every module's raw data,
 * economy totals and player details, run guarded actions (money, licenses,
 * teleports, kick/ban/mute/freeze/spectate/vanish, utility commands) and
 * review the audit trail. Access is tiered: view = read-only, act = guarded
 * actions (logged), senior = high-level actions (money, licenses, OP-only),
 * audit = read the log.
 */
public final class StaffModule implements ViceModule {

    public static final String PERM_VIEW = "vicemc.staff.view";
    public static final String PERM_ACT = "vicemc.staff.act";
    public static final String PERM_SENIOR = "vicemc.staff.senior";
    public static final String PERM_AUDIT = "vicemc.staff.audit";
    public static final String PERM_SC = "vicemc.staff.staffchat";

    private static final long MINUTE = 60_000L;
    private static final long HOUR = 60 * MINUTE;
    private static final long DAY = 24 * HOUR;

    private static final TypeToken<List<AuditEntry>> AUDIT_LIST = new TypeToken<List<AuditEntry>>() {
    };
    private static final TypeToken<List<PunishmentRecord>> PUNISHMENT_LIST = new TypeToken<List<PunishmentRecord>>() {
    };

    private ViceModuleContext ctx;
    private YamlConfig config;
    private StaffGui gui;
    private StaffPrompts prompts;
    private BusinessModule business;
    private final Map<UUID, long[]> actionWindow = new ConcurrentHashMap<>();
    private final Set<UUID> staffSet = ConcurrentHashMap.newKeySet();
    private final Set<UUID> frozen = ConcurrentHashMap.newKeySet();
    private final Set<UUID> vanished = ConcurrentHashMap.newKeySet();
    private final Set<UUID> staffChatToggle = ConcurrentHashMap.newKeySet();
    private final Map<UUID, SpectateSession> spectateSessions = new ConcurrentHashMap<>();

    @Override
    public String id() {
        return "staff";
    }

    @Override
    public String displayName() {
        return "Vice Staff";
    }

    @Override
    public String version() {
        return "1.1.1";
    }

    @Override
    public void onEnable(ViceModuleContext context) {
        this.ctx = context;
        this.config = ctx.yaml("staff.yml");
        this.business = (BusinessModule) ViceCore.get().getModuleRegistry().module("business").orElse(null);
        this.prompts = new StaffPrompts(this);
        Bukkit.getPluginManager().registerEvents(prompts, ctx.plugin());
        Bukkit.getPluginManager().registerEvents(new StaffListener(this), ctx.plugin());
        this.gui = new StaffGui(ctx, this, prompts);

        ctx.commands().register(ctx.plugin(), CommandSpec.builder()
                .name("staff")
                .aliases("staffpanel")
                .permission(PERM_VIEW)
                .description("ViceMC staff panel: inspect every module and run guarded actions")
                .executes(this::staff)
                .tabulates((c, a) -> {
                    if (a.size() <= 1) {
                        return List.of("audit", "money", "data", "players", "regions",
                                "punishments", "chat", "vanish");
                    }
                    if (("money".equals(a.get(0)) || "punishments".equals(a.get(0))) && a.size() == 2) {
                        return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
                    }
                    return List.of();
                })
                .build());

        ctx.commands().register(ctx.plugin(), CommandSpec.builder()
                .name("sc")
                .aliases("staffchat")
                .permission(PERM_SC)
                .description("Send a message only staff can see")
                .executes(c -> {
                    if (!c.isPlayer()) {
                        c.error("Only players can use this.");
                        return;
                    }
                    if (!hasAct(c.player())) {
                        c.error("You need " + PERM_ACT + " to use staff chat.");
                        return;
                    }
                    String message = String.join(" ", c.args());
                    if (message.isBlank()) {
                        c.usage("/sc <message>");
                        return;
                    }
                    staffChat(c.player(), message);
                })
                .build());

        ctx.logger().info("Staff module ready.");
    }

    // --- Facade -----------------------------------------------------------

    public ViceModuleContext context() {
        return ctx;
    }

    public YamlConfig staffConfig() {
        return config;
    }

    public BusinessModule businessModule() {
        return business;
    }

    public boolean hasView(Player player) {
        return player.hasPermission(PERM_VIEW);
    }

    public boolean hasAct(Player player) {
        return player.hasPermission(PERM_ACT);
    }

    public boolean hasSenior(Player player) {
        return player.hasPermission(PERM_SENIOR);
    }

    public boolean hasAudit(Player player) {
        return player.hasPermission(PERM_AUDIT);
    }

    // --- Anti-abuse -------------------------------------------------------

    /**
     * Whether a player counts as staff. Uses the in-memory roster first so
     * offline staff are still protected, then falls back to live permission
     * checks.
     */
    public boolean isStaff(UUID uuid) {
        if (staffSet.contains(uuid)) {
            return true;
        }
        Player player = Bukkit.getPlayer(uuid);
        if (player == null) {
            return false;
        }
        return player.hasPermission(PERM_VIEW) || player.hasPermission(PERM_ACT)
                || player.hasPermission(PERM_SENIOR) || player.hasPermission(PERM_AUDIT);
    }

    public void trackStaff(Player player) {
        if (isStaff(player.getUniqueId())) {
            staffSet.add(player.getUniqueId());
        }
    }

    public void untrackStaff(Player player) {
        staffSet.remove(player.getUniqueId());
    }

    /**
     * Rate limit: at most N act-tier actions per minute per staff member.
     */
    public boolean rateLimited(Player actor) {
        long now = System.currentTimeMillis();
        long[] window = actionWindow.computeIfAbsent(actor.getUniqueId(), k -> new long[]{now, 0});
        int limit = Math.max(1, config.getInt("actions-per-minute", 10));
        if (now - window[0] > 60_000) {
            window[0] = now;
            window[1] = 0;
        }
        window[1]++;
        return window[1] > limit;
    }

    /**
     * Central moderation guard. Returns an error message, or null if the
     * actor may act on the target. Self and other-staff are always blocked;
     * the rate limiter is consulted after those checks.
     */
    public String guardTarget(Player actor, UUID targetUuid) {
        if (targetUuid.equals(actor.getUniqueId())) {
            return "You cannot do that to yourself.";
        }
        if (isStaff(targetUuid)) {
            return "You cannot do that to another staff member.";
        }
        if (rateLimited(actor)) {
            return "Too many actions this minute. Please wait.";
        }
        return null;
    }

    // --- Audit log --------------------------------------------------------

    public void log(Player actor, String action, String target, String detail) {
        List<AuditEntry> list = auditEntries();
        list.add(new AuditEntry(System.currentTimeMillis(), actor.getName(), action,
                target == null ? "" : target, detail == null ? "" : detail));
        int keep = Math.max(1, config.getInt("audit-keep", 1000));
        while (list.size() > keep) {
            list.remove(0);
        }
        ctx.storage().setModuleData("staff", "audit", Json.toJson(list));
    }

    public List<AuditEntry> auditEntries() {
        List<AuditEntry> out = new ArrayList<>();
        ctx.storage().getModuleData("staff", "audit").ifPresent(json -> {
            List<AuditEntry> loaded = Json.fromJson(json, AUDIT_LIST.getType());
            if (loaded != null) {
                out.addAll(loaded);
            }
        });
        return out;
    }

    // --- Punishments ------------------------------------------------------

    public List<PunishmentRecord> punishments(String key) {
        List<PunishmentRecord> out = new ArrayList<>();
        ctx.storage().getModuleData("staff", key).ifPresent(json -> {
            List<PunishmentRecord> loaded = Json.fromJson(json, PUNISHMENT_LIST.getType());
            if (loaded != null) {
                out.addAll(loaded);
            }
        });
        return out;
    }

    private void savePunishments(String key, List<PunishmentRecord> list) {
        long now = System.currentTimeMillis();
        list.removeIf(p -> !p.active(now));
        int keep = Math.max(1, config.getInt("punish-keep", 500));
        while (list.size() > keep) {
            list.remove(0);
        }
        ctx.storage().setModuleData("staff", key, Json.toJson(list));
    }

    private void addPunishment(String key, PunishmentRecord record) {
        List<PunishmentRecord> list = punishments(key);
        list.removeIf(p -> p.uuid().equals(record.uuid()));
        list.add(record);
        savePunishments(key, list);
    }

    private void removePunishment(String key, UUID uuid) {
        List<PunishmentRecord> list = punishments(key);
        list.removeIf(p -> p.uuid().equals(uuid.toString()));
        savePunishments(key, list);
    }

    public Optional<PunishmentRecord> activeBan(UUID uuid) {
        long now = System.currentTimeMillis();
        return punishments("bans").stream()
                .filter(p -> p.uuid().equals(uuid.toString()))
                .filter(p -> p.active(now))
                .findFirst();
    }

    public Optional<MuteData> muteData(UUID uuid) {
        long now = System.currentTimeMillis();
        return punishments("mutes").stream()
                .filter(p -> p.uuid().equals(uuid.toString()))
                .filter(p -> p.active(now))
                .findFirst()
                .map(p -> new MuteData(p.expires(), p.reason()));
    }

    /** All currently active bans and mutes, newest first. */
    public List<PunishmentRecord> activePunishments() {
        long now = System.currentTimeMillis();
        List<PunishmentRecord> out = new ArrayList<>();
        for (String key : List.of("bans", "mutes")) {
            out.addAll(punishments(key).stream().filter(p -> p.active(now)).toList());
        }
        out.sort((a, b) -> Long.compare(b.time(), a.time()));
        return out;
    }

    /** Every stored ban and mute of one player, newest first. */
    public List<PunishmentRecord> punishmentsFor(UUID uuid) {
        List<PunishmentRecord> out = new ArrayList<>();
        for (String key : List.of("bans", "mutes")) {
            out.addAll(punishments(key).stream().filter(p -> p.uuid().equals(uuid.toString())).toList());
        }
        out.sort((a, b) -> Long.compare(b.time(), a.time()));
        return out;
    }

    public static String durationLabel(long millis) {
        if (millis <= 0) {
            return "Permanent";
        }
        long minutes = millis / MINUTE;
        if (minutes < 60) {
            return minutes + "m";
        }
        long hours = minutes / 60;
        if (hours < 24) {
            return hours + "h " + (minutes % 60) + "m";
        }
        long days = hours / 24;
        if (days < 30) {
            return days + "d " + (hours % 24) + "h";
        }
        return (days / 30) + "mo";
    }

    // --- Moderation actions ----------------------------------------------

    public boolean kickPlayer(Player actor, Player target, String reason) {
        String err = guardTarget(actor, target.getUniqueId());
        if (err != null) {
            ctx.notifications().warn(actor, err);
            return false;
        }
        target.kick(Text.color("&cYou were kicked from ViceMC.\n&7Reason: &f" + reason
                + "\n&7By: &f" + actor.getName()));
        log(actor, "kick", target.getName(), reason);
        ctx.notifications().msg(actor, "&cKicked &f" + target.getName() + "&c.");
        return true;
    }

    public boolean banPlayer(Player actor, Player target, String reason, long durationMillis) {
        String err = guardTarget(actor, target.getUniqueId());
        if (err != null) {
            ctx.notifications().warn(actor, err);
            return false;
        }
        addPunishment("bans", new PunishmentRecord(target.getUniqueId().toString(), target.getName(),
                "ban", reason, actor.getName(), System.currentTimeMillis(), durationMillis));
        target.kick(Text.color("&cYou are banned from ViceMC.\n&7Reason: &f" + reason
                + "\n&7Expires: &f" + durationLabel(durationMillis)
                + "\n&7By: &f" + actor.getName()));
        log(actor, "ban", target.getName(), durationLabel(durationMillis) + " - " + reason);
        ctx.notifications().msg(actor, "&cBanned &f" + target.getName() + "&c.");
        return true;
    }

    public boolean unban(Player actor, UUID targetUuid, String targetName) {
        if (rateLimited(actor)) {
            ctx.notifications().warn(actor, "Too many actions this minute. Please wait.");
            return false;
        }
        removePunishment("bans", targetUuid);
        log(actor, "unban", targetName, "");
        ctx.notifications().msg(actor, "&aUnbanned &f" + targetName + "&a.");
        return true;
    }

    public boolean mutePlayer(Player actor, Player target, String reason, long durationMillis) {
        String err = guardTarget(actor, target.getUniqueId());
        if (err != null) {
            ctx.notifications().warn(actor, err);
            return false;
        }
        addPunishment("mutes", new PunishmentRecord(target.getUniqueId().toString(), target.getName(),
                "mute", reason, actor.getName(), System.currentTimeMillis(), durationMillis));
        ctx.notifications().warn(target, "&cYou have been muted&7 (" + durationLabel(durationMillis)
                + "). Reason: &f" + reason);
        log(actor, "mute", target.getName(), durationLabel(durationMillis) + " - " + reason);
        ctx.notifications().msg(actor, "&cMuted &f" + target.getName() + "&c.");
        return true;
    }

    public boolean unmute(Player actor, UUID targetUuid, String targetName) {
        if (rateLimited(actor)) {
            ctx.notifications().warn(actor, "Too many actions this minute. Please wait.");
            return false;
        }
        removePunishment("mutes", targetUuid);
        log(actor, "unmute", targetName, "");
        ctx.notifications().msg(actor, "&aUnmuted &f" + targetName + "&a.");
        return true;
    }

    // --- Freeze / vanish / spectate --------------------------------------

    public boolean isFrozen(UUID uuid) {
        return frozen.contains(uuid);
    }

    public void unfreeze(UUID uuid) {
        frozen.remove(uuid);
    }

    public boolean toggleFreeze(Player actor, Player target) {
        String err = guardTarget(actor, target.getUniqueId());
        if (err != null) {
            ctx.notifications().warn(actor, err);
            return false;
        }
        if (frozen.remove(target.getUniqueId())) {
            ctx.notifications().msg(actor, "&aUnfroze &f" + target.getName() + "&a.");
            ctx.notifications().action(target, "&aYou have been unfrozen.");
            log(actor, "unfreeze", target.getName(), "");
        } else {
            frozen.add(target.getUniqueId());
            ctx.notifications().msg(actor, "&cFroze &f" + target.getName() + "&c.");
            ctx.notifications().action(target, "&cYou have been frozen by staff.");
            log(actor, "freeze", target.getName(), "");
        }
        return true;
    }

    public boolean isVanished(UUID uuid) {
        return vanished.contains(uuid);
    }

    public void applyVanish(Player vanishedPlayer) {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer.equals(vanishedPlayer)) {
                continue;
            }
            if (isStaff(viewer.getUniqueId())) {
                viewer.showPlayer(vanishedPlayer);
            } else {
                viewer.hidePlayer(vanishedPlayer);
            }
        }
    }

    public boolean toggleVanish(Player actor) {
        if (rateLimited(actor)) {
            ctx.notifications().warn(actor, "Too many actions this minute. Please wait.");
            return false;
        }
        if (vanished.remove(actor.getUniqueId())) {
            applyVanish(actor);
            ctx.notifications().msg(actor, "&aYou are now visible.");
            log(actor, "vanish-off", actor.getName(), "");
        } else {
            vanished.add(actor.getUniqueId());
            applyVanish(actor);
            ctx.notifications().msg(actor, "&dVanish on - you are hidden from players.");
            log(actor, "vanish-on", actor.getName(), "");
        }
        return true;
    }

    public boolean toggleSpectate(Player actor, Player target) {
        UUID actorId = actor.getUniqueId();
        if (spectateSessions.containsKey(actorId)) {
            unspectate(actorId);
            return true;
        }
        String err = guardTarget(actor, target.getUniqueId());
        if (err != null) {
            ctx.notifications().warn(actor, err);
            return false;
        }
        spectateSessions.put(actorId, new SpectateSession(target, actor.getGameMode()));
        actor.setGameMode(GameMode.SPECTATOR);
        actor.setSpectatorTarget(target);
        ctx.notifications().msg(actor, "&dNow spectating &f" + target.getName()
                + "&d. Click again to stop.");
        log(actor, "spectate", target.getName(), "");
        return true;
    }

    public void unspectate(UUID actorId) {
        SpectateSession session = spectateSessions.remove(actorId);
        if (session == null) {
            return;
        }
        Player actor = Bukkit.getPlayer(actorId);
        if (actor != null) {
            if (session.previous() != null) {
                actor.setGameMode(session.previous());
            }
            actor.setSpectatorTarget(null);
            ctx.notifications().msg(actor, "&aStopped spectating.");
        }
    }

    public void cancelSpectateOf(UUID targetUuid) {
        List<UUID> spectators = new ArrayList<>();
        for (Map.Entry<UUID, SpectateSession> e : spectateSessions.entrySet()) {
            if (e.getValue().target().getUniqueId().equals(targetUuid)) {
                spectators.add(e.getKey());
            }
        }
        for (UUID spectator : spectators) {
            unspectate(spectator);
        }
    }

    // --- Utility actions --------------------------------------------------

    public boolean healPlayer(Player actor, Player target) {
        String err = guardTarget(actor, target.getUniqueId());
        if (err != null) {
            ctx.notifications().warn(actor, err);
            return false;
        }
        target.setHealth(target.getMaxHealth());
        target.setFoodLevel(20);
        target.setSaturation(20f);
        ctx.notifications().msg(actor, "&aHealed &f" + target.getName() + "&a.");
        ctx.notifications().action(target, "&aYou have been healed by staff.");
        log(actor, "heal", target.getName(), "");
        return true;
    }

    public boolean clearInventory(Player actor, Player target) {
        String err = guardTarget(actor, target.getUniqueId());
        if (err != null) {
            ctx.notifications().warn(actor, err);
            return false;
        }
        target.getInventory().clear();
        target.getInventory().setArmorContents(null);
        ctx.notifications().msg(actor, "&aCleared the inventory of &f" + target.getName() + "&a.");
        ctx.notifications().action(target, "&cYour inventory was cleared by staff.");
        log(actor, "clear-inventory", target.getName(), "");
        return true;
    }

    public boolean setGameMode(Player actor, Player target, GameMode mode) {
        String err = guardTarget(actor, target.getUniqueId());
        if (err != null) {
            ctx.notifications().warn(actor, err);
            return false;
        }
        target.setGameMode(mode);
        ctx.notifications().msg(actor, "&aSet &f" + target.getName() + "&a to &f"
                + mode.name().toLowerCase() + "&a.");
        ctx.notifications().action(target, "&aYour gamemode was set to &f" + mode.name().toLowerCase() + "&a.");
        log(actor, "gamemode", target.getName(), mode.name().toLowerCase());
        return true;
    }

    // --- Staff chat -------------------------------------------------------

    public boolean staffChatOn(UUID uuid) {
        return staffChatToggle.contains(uuid);
    }

    public void toggleStaffChat(Player actor) {
        if (staffChatToggle.remove(actor.getUniqueId())) {
            ctx.notifications().msg(actor, "&7Staff chat off. Your messages go to normal chat.");
        } else {
            staffChatToggle.add(actor.getUniqueId());
            ctx.notifications().msg(actor, "&bStaff chat on. Your messages go only to staff.");
        }
    }

    public void staffChat(Player actor, String message) {
        String line = "&8[&bStaff&8] &f" + actor.getName() + "&7: &f" + message;
        ctx.notifications().broadcast(PERM_SC, line);
    }

    // --- /staff -----------------------------------------------------------

    private void staff(CommandContext c) {
        if (!c.isPlayer()) {
            c.error("Only players can use this.");
            return;
        }
        Player p = c.player();
        switch (c.arg(0)) {
            case "audit" -> {
                if (!hasAudit(p)) {
                    c.error("You need " + PERM_AUDIT + " to read the audit log.");
                    return;
                }
                gui.openAudit(p, 1);
            }
            case "money" -> {
                if (c.size() >= 2) {
                    Player target = c.playerArg(1);
                    if (target != null) {
                        gui.openPlayerEconomy(p, target, 1);
                        return;
                    }
                }
                gui.openEconomy(p, 1);
            }
            case "data" -> gui.openModules(p, 1);
            case "players" -> gui.openPlayers(p, 1);
            case "regions" -> gui.openRegions(p, 1);
            case "punishments" -> {
                if (!hasAct(p)) {
                    c.error("You need " + PERM_ACT + " for moderation.");
                    return;
                }
                if (c.size() >= 2) {
                    Player target = c.playerArg(1);
                    if (target != null) {
                        gui.openPlayerPunishments(p, target);
                        return;
                    }
                    c.error("Player not found: " + c.arg(1));
                    return;
                }
                gui.openPunishments(p, 1);
            }
            case "chat" -> {
                if (!hasAct(p)) {
                    c.error("You need " + PERM_ACT + " to use staff chat.");
                    return;
                }
                toggleStaffChat(p);
            }
            case "vanish" -> {
                if (!hasAct(p)) {
                    c.error("You need " + PERM_ACT + " to vanish.");
                    return;
                }
                toggleVanish(p);
            }
            default -> gui.openMain(p);
        }
    }

    private record SpectateSession(Player target, GameMode previous) {
    }
}
