package net.vicemc.modules.law;

import net.vicemc.api.ViceModule;
import net.vicemc.api.ViceModuleContext;
import net.vicemc.api.service.CommandContext;
import net.vicemc.api.service.CommandSpec;
import net.vicemc.api.util.Text;
import net.vicemc.api.util.YamlConfig;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The legal gameplay loop: mandatory bodycam evidence, arrests, lawyer
 * requests with a waiting room, and a formal court system backed by the
 * written law book.
 */
public final class LawModule implements ViceModule {

    private static final String PENDING_PREFIX = "pending:";

    private ViceModuleContext ctx;
    private YamlConfig config;
    private BodycamManager bodycam;
    private ArrestManager arrests;
    private CourtManager courts;
    private LawGui gui;
    private ScheduledTask arrestTask;

    @Override
    public String id() {
        return "law";
    }

    @Override
    public String displayName() {
        return "Vice Law";
    }

    @Override
    public String version() {
        return "1.0.0";
    }

    @Override
    public void onEnable(ViceModuleContext context) {
        this.ctx = context;
        this.config = ctx.yaml("law.yml");
        this.bodycam = new BodycamManager(ctx, config);
        this.arrests = new ArrestManager(ctx, config);
        this.courts = new CourtManager(ctx);
        this.gui = new LawGui(ctx, this);

        arrestTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(ctx.plugin(),
                task -> arrests.tick(), 200L, 600L);

        registerCommands();
        ctx.logger().info("Law module ready.");
    }

    @Override
    public void onDisable() {
        if (arrestTask != null) {
            cancelQuietly(arrestTask);
            arrestTask = null;
        }
    }

    private void registerCommands() {
        ctx.commands().register(ctx.plugin(), CommandSpec.builder()
                .name("arrest")
                .description("Arrest a player (police)")
                .executes(this::arrest)
                .tabulates((c, a) -> a.size() <= 1 ? playerNames() : chargeNames())
                .build());

        ctx.commands().register(ctx.plugin(), CommandSpec.builder()
                .name("release")
                .description("Release a player (police)")
                .executes(this::release)
                .tabulates((c, a) -> playerNames())
                .build());

        ctx.commands().register(ctx.plugin(), CommandSpec.builder()
                .name("bodycam")
                .description("Review bodycam footage (police)")
                .executes(this::bodycam)
                .tabulates((c, a) -> playerNames())
                .build());

        ctx.commands().register(ctx.plugin(), CommandSpec.builder()
                .name("lawyer")
                .description("Request a lawyer or review cases")
                .executes(this::lawyer)
                .tabulates((c, a) -> a.size() <= 1
                        ? List.of("request", "price", "hire", "pending", "review")
                        : playerNames())
                .build());

        ctx.commands().register(ctx.plugin(), CommandSpec.builder()
                .name("court")
                .description("Court cases and rulings")
                .executes(this::court)
                .tabulates((c, a) -> a.size() <= 1 ? List.of("file", "case", "rule") : playerNames())
                .build());

        ctx.commands().register(ctx.plugin(), CommandSpec.builder()
                .name("lawbook")
                .description("View the written law book")
                .executes(this::lawbook)
                .build());

        ctx.commands().register(ctx.plugin(), CommandSpec.builder()
                .name("law")
                .description("Law and order menu")
                .executes(c -> {
                    if (!c.isPlayer()) {
                        c.error("This is a player command - run it in game to open the law menu.");
                        return;
                    }
                    gui.openDashboard(c.player());
                })
                .build());
    }

    // --- /arrest & /release ----------------------------------------------

    private void arrest(CommandContext c) {
        if (!requirePolice(c)) {
            return;
        }
        if (c.size() < 2) {
            c.usage("/arrest <player> <charge>");
            return;
        }
        UUID target = c.uuidArg(0);
        if (target == null) {
            c.error("Player not found.");
            return;
        }
        String charge = c.arg(1, "unspecified");
        bodycam.record(c.player().getUniqueId(), target, "arrest", charge);
        arrests.arrest(target, c.player().getUniqueId(), charge, 0);
        c.msg("&aArrested " + c.arg(0) + " for &f" + charge + "&a. Bodycam footage recorded.");
        Player suspect = Bukkit.getPlayer(target);
        if (suspect != null) {
            ctx.notifications().warn(suspect, "You were arrested for " + charge
                    + ". Request a lawyer with &e/lawyer request&c.");
        }
    }

    private void release(CommandContext c) {
        if (!requirePolice(c)) {
            return;
        }
        if (c.size() < 1) {
            c.usage("/release <player>");
            return;
        }
        UUID target = c.uuidArg(0);
        if (target == null) {
            c.error("Player not found.");
            return;
        }
        bodycam.record(c.player().getUniqueId(), target, "release", "");
        arrests.release(target);
        c.msg("&aReleased " + c.arg(0) + ".");
    }

    private void bodycam(CommandContext c) {
        if (!requirePolice(c)) {
            return;
        }
        int hours = config.getInt("bodycam.retention-hours", 48);
        List<BodycamEntry> entries;
        if (c.size() > 0 && !c.arg(0).matches("\\d+")) {
            UUID target = c.uuidArg(0);
            if (target == null) {
                c.error("Player not found.");
                return;
            }
            entries = bodycam.forTarget(target);
            c.msg("&6Bodycam footage involving &f" + c.arg(0) + "&6:");
        } else {
            if (c.size() > 0) {
                hours = c.argInt(0, hours);
            }
            entries = bodycam.forOfficer(c.player().getUniqueId(), hours);
            c.msg("&6Your bodycam footage (last " + hours + "h):");
        }
        if (entries.isEmpty()) {
            c.msg("  &7No footage found.");
            return;
        }
        for (BodycamEntry e : entries) {
            c.msg("  &8[" + new SimpleDateFormat("HH:mm dd-MM").format(new Date(e.timestamp))
                    + "] &f" + nameOf(e.target) + "&7 " + e.action
                    + (e.charge.isEmpty() ? "" : " (&6" + e.charge + "&7)"));
        }
    }

    // --- /lawyer ----------------------------------------------------------

    private void lawyer(CommandContext c) {
        switch (c.arg(0)) {
            case "request" -> request(c);
            case "price" -> price(c);
            case "hire" -> hire(c);
            case "pending" -> pending(c);
            case "review" -> review(c);
            default -> c.msg("&6/lawyer request | price <amount> | hire <player> | pending | review <player> <RELEASE|PROCEED>");
        }
    }

    private void request(CommandContext c) {
        if (!c.isPlayer()) {
            return;
        }
        Player player = c.player();
        if (!arrests.isArrested(player.getUniqueId())) {
            c.error("Only arrested players can request a lawyer.");
            return;
        }
        setPending(player.getUniqueId());
        arrests.toWaitingRoom(player.getUniqueId());
        c.msg("&aA lawyer has been notified. You are held in the waiting room until your case is reviewed.");
        ctx.notifications().broadcast("vicemc.lawyer",
                "&eA player requested a lawyer: &f" + player.getName()
                        + "&e. Review with &f/lawyer review <player>&e.");
    }

    private void price(CommandContext c) {
        if (!c.isPlayer()) {
            return;
        }
        if (c.size() < 2) {
            c.usage("/lawyer price <amount>  (0 = free state lawyer)");
            return;
        }
        double fee = Math.max(0, c.argDouble(1, 0));
        ctx.storage().setModuleData("law", "fee:" + c.player().getUniqueId(), String.valueOf(fee));
        c.msg(fee > 0
                ? "&aYou now charge &f" + Text.moneyPlain(fee) + "&a per private case."
                : "&aYou are now a free state lawyer, paid per case by the government.");
    }

    private void hire(CommandContext c) {
        if (!c.isPlayer()) {
            return;
        }
        if (!c.player().hasPermission("vicemc.lawyer")) {
            c.error("You are not a lawyer.");
            return;
        }
        if (c.size() < 2) {
            c.usage("/lawyer hire <defendant>");
            return;
        }
        UUID defendant = c.uuidArg(1);
        if (defendant == null || !isPending(defendant)) {
            c.error("That player has no pending review.");
            return;
        }
        double fee = privateFee(c.player().getUniqueId());
        if (fee <= 0) {
            c.error("You are a state lawyer - no fee is charged. Review with /lawyer review.");
            return;
        }
        var result = ctx.economy().transfer(defendant, c.player().getUniqueId(), fee, "private lawyer fee");
        if (!result.success()) {
            c.error("The defendant cannot afford your fee: " + result.detail());
            return;
        }
        ctx.storage().setModuleData("law", "hired:" + defendant + ":" + c.player().getUniqueId(), String.valueOf(fee));
        c.msg("&aYou were hired for " + Text.moneyPlain(fee) + ". Review with /lawyer review.");
    }

    private void pending(CommandContext c) {
        if (!c.isPlayer() || !c.player().hasPermission("vicemc.lawyer")) {
            c.error("You are not a lawyer.");
            return;
        }
        List<String> defendants = pendingList();
        if (defendants.isEmpty()) {
            c.msg("&7No pending reviews.");
            return;
        }
        c.msg("&6Pending reviews:");
        for (String raw : defendants) {
            UUID uuid = UUID.fromString(raw);
            c.msg("  &7- &f" + nameOf(uuid));
        }
    }

    private void review(CommandContext c) {
        if (!c.isPlayer()) {
            return;
        }
        Player lawyer = c.player();
        if (!lawyer.hasPermission("vicemc.lawyer")) {
            c.error("You are not a lawyer.");
            return;
        }
        if (c.size() < 3) {
            c.usage("/lawyer review <player> <RELEASE|PROCEED>");
            return;
        }
        UUID defendant = c.uuidArg(1);
        if (defendant == null || !isPending(defendant)) {
            c.error("That player has no pending review.");
            return;
        }
        String verdict = c.arg(2).toUpperCase();
        if (!verdict.equals("RELEASE") && !verdict.equals("PROCEED")) {
            c.error("Verdict must be RELEASE or PROCEED.");
            return;
        }

        double fee = privateFee(lawyer.getUniqueId());
        if (fee > 0 && !ctx.storage().getModuleData("law", "hired:" + defendant + ":" + lawyer.getUniqueId()).isPresent()) {
            c.error("You are a private lawyer - the defendant must hire you first (/lawyer hire).");
            return;
        }

        List<BodycamEntry> evidence = bodycam.forTarget(defendant);
        if (verdict.equals("PROCEED") && evidence.isEmpty()) {
            c.error("No bodycam evidence within the retention window. You must release this suspect.");
            return;
        }

        removePending(defendant);
        boolean released = verdict.equals("RELEASE");
        if (released) {
            arrests.release(defendant);
            ctx.notifications().broadcast("&6" + nameOf(defendant)
                    + " was released - no clear bodycam evidence established identity.");
        } else {
            Player suspect = Bukkit.getPlayer(defendant);
            if (suspect != null) {
                ctx.notifications().msg(suspect, "&cThe lawyer reviewed the footage and found enough evidence. Processing proceeds.");
            }
        }

        if (fee <= 0) {
            ctx.economy().deposit(lawyer.getUniqueId(),
                    config.getDouble("state-lawyer-pay", 5000), "state lawyer case");
            c.msg("&aState lawyer fee paid: " + Text.moneyPlain(config.getDouble("state-lawyer-pay", 5000)) + ".");
        }
        ctx.events().publish("law.review", Map.of(
                "type", "review",
                "defendant", defendant.toString(),
                "lawyer", lawyer.getName(),
                "verdict", verdict,
                "evidence", evidence.size()));
        c.msg("&aReview complete: &f" + verdict + "&a.");
    }

    // --- /court -----------------------------------------------------------

    private void court(CommandContext c) {
        switch (c.arg(0)) {
            case "file" -> fileCase(c);
            case "case" -> viewCase(c);
            case "rule" -> rule(c);
            default -> c.msg("&6/court file <player> <charge> | case <id> | rule <id> <GUILTY|INNOCENT> [fine] [days]");
        }
    }

    private void fileCase(CommandContext c) {
        if (!requirePolice(c)) {
            return;
        }
        if (c.size() < 3) {
            c.usage("/court file <player> <charge>");
            return;
        }
        UUID defendant = c.uuidArg(1);
        if (defendant == null || !arrests.isArrested(defendant)) {
            c.error("Player not found or not arrested.");
            return;
        }
        String charge = c.arg(2, "unspecified");
        LegalCase legalCase = courts.file(defendant, c.player().getUniqueId(), charge);
        bodycam.record(c.player().getUniqueId(), defendant, "court filing", charge);
        c.msg("&aCase #" + legalCase.id + " filed against " + c.arg(1) + " for &f" + charge + "&a.");
    }

    private void viewCase(CommandContext c) {
        if (!c.isPlayer() || !c.player().hasPermission("vicemc.law.court")) {
            c.error("You are not authorized to access the courts.");
            return;
        }
        if (c.size() < 2) {
            c.usage("/court case <id>");
            return;
        }
        LegalCase legalCase = courts.byId(c.argInt(1, -1)).orElse(null);
        if (legalCase == null) {
            c.error("Case not found.");
            return;
        }
        c.msg("&6Case #" + legalCase.id + " - &f" + nameOf(legalCase.defendant)
                + "&7 charged with &6" + legalCase.charge);
        c.msg("  &7Filed by: &f" + nameOf(legalCase.officer)
                + " &7Status: &f" + legalCase.status
                + (legalCase.verdict != null ? " &7Verdict: &f" + legalCase.verdict : ""));
        c.msg("  &7Bodycam evidence entries: &f" + bodycam.forTarget(legalCase.defendant).size());
    }

    private void rule(CommandContext c) {
        if (!c.isPlayer() || !c.player().hasPermission("vicemc.law.court")) {
            c.error("Only a judge can rule on cases.");
            return;
        }
        if (c.size() < 3) {
            c.usage("/court rule <id> <GUILTY|INNOCENT> [fine] [days]");
            return;
        }
        LegalCase legalCase = courts.byId(c.argInt(1, -1)).orElse(null);
        if (legalCase == null || !legalCase.status.equals("COURT")) {
            c.error("Case not found or already ruled.");
            return;
        }
        String verdict = c.arg(2).toUpperCase();
        if (!verdict.equals("GUILTY") && !verdict.equals("INNOCENT")) {
            c.error("Verdict must be GUILTY or INNOCENT.");
            return;
        }
        double fine = verdict.equals("GUILTY") ? c.argDouble(3, 0) : 0;
        int days = verdict.equals("GUILTY") ? c.argInt(4, 0) : 0;

        if (verdict.equals("GUILTY")) {
            double balance = ctx.economy().balance(legalCase.defendant);
            if (fine > 0) {
                if (balance >= fine) {
                    ctx.economy().withdraw(legalCase.defendant, fine, "court fine case #" + legalCase.id);
                } else {
                    double shortfall = fine - balance;
                    if (balance > 0) {
                        ctx.economy().withdraw(legalCase.defendant, balance, "court fine case #" + legalCase.id);
                    }
                    ctx.storage().setModuleData("law", "debt:" + legalCase.defendant, String.valueOf(shortfall));
                    c.msg("&cThe defendant could not pay the full fine; the remainder ("
                            + Text.moneyPlain(shortfall) + ") is recorded as debt and subject to garnishment.");
                }
            }
            if (days > 0) {
                arrests.arrest(legalCase.defendant, legalCase.officer, legalCase.charge, days);
            }
            c.msg("&aVerdict GUILTY: fine " + Text.moneyPlain(fine) + ", " + days + " days.");
        } else {
            arrests.release(legalCase.defendant);
            c.msg("&aVerdict INNOCENT - " + nameOf(legalCase.defendant) + " released.");
        }
        legalCase.status = "RULED";
        legalCase.verdict = verdict;
        legalCase.fine = fine;
        legalCase.jailDays = days;
        courts.save();
        ctx.events().publish("law.court", Map.of(
                "type", "ruling",
                "caseId", legalCase.id,
                "defendant", legalCase.defendant.toString(),
                "verdict", verdict,
                "fine", fine,
                "days", days));
    }

    // --- /lawbook ---------------------------------------------------------

    private void lawbook(CommandContext c) {
        ConfigurationSection book = config.getSection("lawbook");
        if (book == null) {
            c.msg("&7The law book is empty.");
            return;
        }
        c.msg("&6\u2696 The ViceMC Law Book");
        for (String id : book.getKeys(false)) {
            String name = config.getString("lawbook." + id + ".name", id);
            double fine = config.getDouble("lawbook." + id + ".fine", 0);
            int days = config.getInt("lawbook." + id + ".days", 0);
            c.msg("  &f" + Text.color(name) + " &7- fine &f" + Text.moneyPlain(fine)
                    + "&7 / &f" + days + "&7 days");
        }
    }

    // --- Pending (waiting room) ------------------------------------------

    public void setPending(UUID uuid) {
        ctx.storage().setModuleData("law", PENDING_PREFIX + uuid, "waiting");
    }

    public boolean isPending(UUID uuid) {
        return ctx.storage().getModuleData("law", PENDING_PREFIX + uuid).isPresent();
    }

    public void removePending(UUID uuid) {
        ctx.storage().removeModuleData("law", PENDING_PREFIX + uuid);
    }

    public List<String> pendingList() {
        List<String> result = new ArrayList<>();
        for (String key : ctx.storage().moduleDataAll("law").keySet()) {
            if (key.startsWith(PENDING_PREFIX)) {
                result.add(key.substring(PENDING_PREFIX.length()));
            }
        }
        return result;
    }

    public double privateFee(UUID lawyer) {
        return ctx.storage().getModuleData("law", "fee:" + lawyer)
                .map(Double::parseDouble).orElse(0.0);
    }

    // --- Public facade used by the GUI -----------------------------------

    public ViceModuleContext context() {
        return ctx;
    }

    public YamlConfig lawConfig() {
        return config;
    }

    public BodycamManager bodycam() {
        return bodycam;
    }

    public ArrestManager arrests() {
        return arrests;
    }

    public CourtManager courts() {
        return courts;
    }

    public void requestLawyer(Player player) {
        if (!arrests.isArrested(player.getUniqueId())) {
            player.sendMessage(Text.color("&cOnly arrested players can request a lawyer."));
            return;
        }
        setPending(player.getUniqueId());
        arrests.toWaitingRoom(player.getUniqueId());
        player.sendMessage(Text.color("&aA lawyer has been notified. You are held in the waiting room until your case is reviewed."));
        ctx.notifications().broadcast("vicemc.lawyer",
                "&eA player requested a lawyer: &f" + player.getName()
                        + "&e. Review with &f/lawyer review <player>&e.");
    }

    public void arrestPlayer(Player officer, UUID target, String charge) {
        bodycam.record(officer.getUniqueId(), target, "arrest", charge);
        arrests.arrest(target, officer.getUniqueId(), charge, 0);
        Player suspect = Bukkit.getPlayer(target);
        if (suspect != null) {
            ctx.notifications().warn(suspect, "You were arrested for " + charge
                    + ". Request a lawyer with &e/lawyer request&c.");
        }
    }

    public void releasePlayer(Player officer, UUID target) {
        bodycam.record(officer.getUniqueId(), target, "release", "");
        arrests.release(target);
    }

    public void fileCase(Player officer, UUID defendant, String charge) {
        courts.file(defendant, officer.getUniqueId(), charge);
        bodycam.record(officer.getUniqueId(), defendant, "court filing", charge);
    }

    public boolean hireLawyer(Player lawyer, UUID defendant) {
        if (!isPending(defendant)) {
            lawyer.sendMessage(Text.color("&cThat player has no pending review."));
            return false;
        }
        double fee = privateFee(lawyer.getUniqueId());
        if (fee <= 0) {
            lawyer.sendMessage(Text.color("&cYou are a state lawyer - no fee is charged. Review with /lawyer review."));
            return false;
        }
        var result = ctx.economy().transfer(defendant, lawyer.getUniqueId(), fee, "private lawyer fee");
        if (!result.success()) {
            lawyer.sendMessage(Text.color("&cThe defendant cannot afford your fee: " + result.detail()));
            return false;
        }
        ctx.storage().setModuleData("law", "hired:" + defendant + ":" + lawyer.getUniqueId(), String.valueOf(fee));
        lawyer.sendMessage(Text.color("&aYou were hired for " + Text.moneyPlain(fee) + ". Review with /lawyer review."));
        return true;
    }

    public boolean reviewCase(Player lawyer, UUID defendant, String verdict) {
        if (!isPending(defendant)) {
            lawyer.sendMessage(Text.color("&cThat player has no pending review."));
            return false;
        }
        if (!verdict.equals("RELEASE") && !verdict.equals("PROCEED")) {
            lawyer.sendMessage(Text.color("&cVerdict must be RELEASE or PROCEED."));
            return false;
        }
        double fee = privateFee(lawyer.getUniqueId());
        if (fee > 0 && !ctx.storage().getModuleData("law", "hired:" + defendant + ":" + lawyer.getUniqueId()).isPresent()) {
            lawyer.sendMessage(Text.color("&cYou are a private lawyer - the defendant must hire you first (/lawyer hire)."));
            return false;
        }
        List<BodycamEntry> evidence = bodycam.forTarget(defendant);
        if (verdict.equals("PROCEED") && evidence.isEmpty()) {
            lawyer.sendMessage(Text.color("&cNo bodycam evidence within the retention window. You must release this suspect."));
            return false;
        }

        removePending(defendant);
        if (verdict.equals("RELEASE")) {
            arrests.release(defendant);
            ctx.notifications().broadcast("&6" + nameOf(defendant)
                    + " was released - no clear bodycam evidence established identity.");
        } else {
            Player suspect = Bukkit.getPlayer(defendant);
            if (suspect != null) {
                ctx.notifications().msg(suspect, "&cThe lawyer reviewed the footage and found enough evidence. Processing proceeds.");
            }
        }
        if (fee <= 0) {
            ctx.economy().deposit(lawyer.getUniqueId(),
                    config.getDouble("state-lawyer-pay", 5000), "state lawyer case");
        }
        ctx.events().publish("law.review", Map.of(
                "type", "review",
                "defendant", defendant.toString(),
                "lawyer", lawyer.getName(),
                "verdict", verdict,
                "evidence", evidence.size()));
        return true;
    }

    public boolean ruleCase(Player judge, int caseId, String verdict, double fine, int days) {
        if (!judge.hasPermission("vicemc.law.court")) {
            judge.sendMessage(Text.color("&cOnly a judge can rule on cases."));
            return false;
        }
        LegalCase legalCase = courts.byId(caseId).orElse(null);
        if (legalCase == null || !legalCase.status.equals("COURT")) {
            judge.sendMessage(Text.color("&cCase not found or already ruled."));
            return false;
        }
        if (!verdict.equals("GUILTY") && !verdict.equals("INNOCENT")) {
            judge.sendMessage(Text.color("&cVerdict must be GUILTY or INNOCENT."));
            return false;
        }

        if (verdict.equals("GUILTY")) {
            double balance = ctx.economy().balance(legalCase.defendant);
            if (fine > 0) {
                if (balance >= fine) {
                    ctx.economy().withdraw(legalCase.defendant, fine, "court fine case #" + legalCase.id);
                } else {
                    double shortfall = fine - balance;
                    if (balance > 0) {
                        ctx.economy().withdraw(legalCase.defendant, balance, "court fine case #" + legalCase.id);
                    }
                    ctx.storage().setModuleData("law", "debt:" + legalCase.defendant, String.valueOf(shortfall));
                    judge.sendMessage(Text.color("&cThe defendant could not pay the full fine; the remainder ("
                            + Text.moneyPlain(shortfall) + ") is recorded as debt and subject to garnishment."));
                }
            }
            if (days > 0) {
                arrests.arrest(legalCase.defendant, legalCase.officer, legalCase.charge, days);
            }
        } else {
            arrests.release(legalCase.defendant);
        }
        legalCase.status = "RULED";
        legalCase.verdict = verdict;
        legalCase.fine = fine;
        legalCase.jailDays = days;
        courts.save();
        ctx.events().publish("law.court", Map.of(
                "type", "ruling",
                "caseId", legalCase.id,
                "defendant", legalCase.defendant.toString(),
                "verdict", verdict,
                "fine", fine,
                "days", days));
        return true;
    }

    // --- Helpers ----------------------------------------------------------

    private boolean requirePolice(CommandContext c) {
        if (!c.isPlayer()) {
            c.error("Only players can use this command.");
            return false;
        }
        if (!c.player().hasPermission("vicemc.police")) {
            c.error("You are not authorized to do that.");
            return false;
        }
        return true;
    }

    private List<String> chargeNames() {
        ConfigurationSection book = config.getSection("lawbook");
        return book == null ? List.of() : new ArrayList<>(book.getKeys(false));
    }

    private List<String> playerNames() {
        return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
    }

    private String nameOf(UUID uuid) {
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) {
            return online.getName();
        }
        String name = Bukkit.getOfflinePlayer(uuid).getName();
        return name == null ? uuid.toString().substring(0, 8) : name;
    }

    private static void cancelQuietly(ScheduledTask task) {
        try {
            task.cancel();
        } catch (IllegalStateException ignored) {
        }
    }
}
