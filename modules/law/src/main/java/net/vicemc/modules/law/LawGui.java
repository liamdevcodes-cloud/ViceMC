package net.vicemc.modules.law;

import net.vicemc.api.ViceModuleContext;
import net.vicemc.api.service.GUIService;
import net.vicemc.api.util.GuiKit;
import net.vicemc.api.util.ItemBuilder;
import net.vicemc.api.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The law and order menu: police tools (arrest, release, bodycam), lawyer
 * duties (pending reviews, hire, release/proceed), the court docket with a
 * guided fine/days panel, and the written law book - all in the dark modern
 * menu style.
 */
public final class LawGui {

    private static final int PER_PAGE = 27;

    private final ViceModuleContext ctx;
    private final LawModule module;
    private final Map<UUID, Draft> drafts = new ConcurrentHashMap<>();

    public LawGui(ViceModuleContext ctx, LawModule module) {
        this.ctx = ctx;
        this.module = module;
    }

    public void openDashboard(Player player) {
        boolean police = player.hasPermission("vicemc.police");
        boolean lawyer = player.hasPermission("vicemc.lawyer");
        boolean judge = player.hasPermission("vicemc.law.court");
        boolean arrested = module.arrests().isArrested(player.getUniqueId());

        String role = police ? "&cPolice" : lawyer ? "&eLawyer" : judge ? "&6Judge" : "&7Citizen";
        Material roleMat = police ? Material.IRON_SWORD : lawyer ? Material.BOOKSHELF
                : judge ? Material.ANVIL : Material.PLAYER_HEAD;

        var builder = ctx.gui().builder(GuiKit.title("Law and order"), 6);
        GuiKit.frame(builder);
        builder.item(GuiKit.HOW_TO, GuiKit.icon(Material.BOOK, "&6How it works",
                "&7Police keep bodycam evidence for every",
                "&7interaction. Arrests go to the waiting",
                "&7room, lawyers review footage, and judges",
                "&7rule on cases from the law book."), GuiKit.NONE);
        builder.item(GuiKit.STATUS, ItemBuilder.of(roleMat)
                .name("&6" + (police ? "Police officer" : lawyer ? "Lawyer" : judge ? "Judge" : "Citizen"))
                .lore("&7Role: " + role,
                        arrested ? "&cStatus: ARRESTED" : "&aStatus: Free",
                        "&7In custody: &f" + module.arrests().all().size(),
                        "&7Open cases: &f" + module.courts().open().size(),
                        "&7Pending reviews: &f" + module.pendingList().size())
                .build(), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());

        builder.item(GuiKit.ACTION_1, GuiKit.icon(Material.WRITTEN_BOOK, "&6Law book",
                "&7Browse the written laws and their",
                "&7fines and sentences."), (p, c) -> openLawbook(p, 1));

        if (police) {
            builder.item(GuiKit.ACTION_2, GuiKit.icon(Material.REDSTONE_TORCH, "&cArrest a player",
                    "&7Pick a suspect and a charge."), (p, c) -> openArrestPicker(p, 1));
            builder.item(GuiKit.ACTION_3, GuiKit.icon(Material.LIME_DYE, "&aRelease a prisoner",
                    "&7Review who is in custody."), (p, c) -> openPrisoners(p, 1));
            builder.item(GuiKit.ACTION_4, GuiKit.icon(Material.SPYGLASS, "&eBodycam footage",
                    "&7Review your recorded interactions."), (p, c) -> openBodycam(p, 1));
            builder.item(GuiKit.ACTION_5, GuiKit.icon(Material.ANVIL, "&6Court cases",
                    "&7The open court docket."), (p, c) -> openCourt(p, 1));
        } else if (lawyer) {
            builder.item(GuiKit.ACTION_2, GuiKit.icon(Material.CLOCK, "&ePending reviews",
                    "&7Defendants waiting for review."), (p, c) -> openPending(p, 1));
            if (judge) {
                builder.item(GuiKit.ACTION_3, GuiKit.icon(Material.ANVIL, "&6Court cases",
                        "&7The open court docket."), (p, c) -> openCourt(p, 1));
            }
        } else if (judge) {
            builder.item(GuiKit.ACTION_2, GuiKit.icon(Material.ANVIL, "&6Court cases",
                    "&7The open court docket."), (p, c) -> openCourt(p, 1));
        } else if (arrested) {
            builder.item(GuiKit.ACTION_2, GuiKit.icon(Material.NAME_TAG, "&eRequest a lawyer",
                    "&7You are in custody. A lawyer will",
                    "&7review your bodycam evidence."), (p, c) -> {
                module.requestLawyer(p);
                p.closeInventory();
            });
        }

        builder.open(player);
    }

    // --- Law book ---------------------------------------------------------

    private void openLawbook(Player player, int page) {
        List<LawEntry> laws = lawEntries();
        int pages = GuiKit.Pages.pages(laws.size(), PER_PAGE);
        int safe = Math.min(page, pages);
        var builder = ctx.gui().builder(GuiKit.title("Law book"), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the law menu"), (p, c) -> openDashboard(p));
        builder.item(GuiKit.STATUS, GuiKit.icon(Material.WRITTEN_BOOK, "&6The ViceMC Law Book",
                "&7" + laws.size() + " written law(s).",
                "&7Each lists its fine and jail sentence."), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());
        List<LawEntry> slice = GuiKit.Pages.slice(laws, safe, PER_PAGE);
        for (int i = 0; i < slice.size(); i++) {
            LawEntry law = slice.get(i);
            Material mat = law.days > 0 ? Material.RED_DYE : law.fine > 0 ? Material.YELLOW_DYE : Material.GRAY_DYE;
            builder.item(GuiKit.GRID_FIRST + i, ItemBuilder.of(mat)
                    .name("&f" + law.name)
                    .lore("&7Fine: &f" + GuiKit.fmt(law.fine),
                            "&7Jail: &f" + law.days + " day(s)")
                    .build(), GuiKit.NONE);
        }
        addPaging(builder, player, safe, pages, (p, next) -> openLawbook(p, next));
        builder.open(player);
    }

    // --- Arrest wizard ----------------------------------------------------

    private void openArrestPicker(Player player, int page) {
        List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
        online.removeIf(p -> p.getUniqueId().equals(player.getUniqueId()));
        int pages = GuiKit.Pages.pages(online.size(), PER_PAGE);
        int safe = Math.min(page, pages);
        var builder = ctx.gui().builder(GuiKit.title("Pick a suspect"), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the law menu"), (p, c) -> openDashboard(p));
        builder.item(GuiKit.STATUS, GuiKit.icon(Material.REDSTONE_TORCH, "&cArrest a player",
                "&7" + online.size() + " player(s) online."), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());
        List<Player> slice = GuiKit.Pages.slice(online, safe, PER_PAGE);
        for (int i = 0; i < slice.size(); i++) {
            Player target = slice.get(i);
            builder.item(GuiKit.GRID_FIRST + i, GuiKit.playerHead(target.getName(),
                    "&e" + target.getName(),
                    "&7Balance: &f" + GuiKit.fmt(ctx.economy().balance(target.getUniqueId())),
                    "&cClick to arrest."), (p, c) -> {
                drafts.computeIfAbsent(p.getUniqueId(), k -> new Draft()).target = target.getUniqueId();
                openChargePicker(p, 1);
            });
        }
        addPaging(builder, player, safe, pages, (p, next) -> openArrestPicker(p, next));
        builder.open(player);
    }

    private void openChargePicker(Player player, int page) {
        List<LawEntry> laws = lawEntries();
        int pages = GuiKit.Pages.pages(laws.size(), PER_PAGE);
        int safe = Math.min(page, pages);
        var builder = ctx.gui().builder(GuiKit.title("Pick a charge"), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the suspect picker"), (p, c) -> openArrestPicker(p, 1));
        builder.item(GuiKit.STATUS, GuiKit.icon(Material.REDSTONE_TORCH, "&cCharge",
                "&7Suspect: &f" + nameOf(draftOf(player).target),
                "&7Pick the charge from the law book."), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());
        List<LawEntry> slice = GuiKit.Pages.slice(laws, safe, PER_PAGE);
        for (int i = 0; i < slice.size(); i++) {
            LawEntry law = slice.get(i);
            builder.item(GuiKit.GRID_FIRST + i, ItemBuilder.of(Material.NAME_TAG)
                    .name("&f" + law.name)
                    .lore("&7Fine: &f" + GuiKit.fmt(law.fine),
                            "&7Jail: &f" + law.days + " day(s)",
                            "&cClick to arrest with this charge.")
                    .build(), (p, c) -> {
                drafts.computeIfAbsent(p.getUniqueId(), k -> new Draft()).charge = law.name;
                openArrestConfirm(p);
            });
        }
        addPaging(builder, player, safe, pages, (p, next) -> openChargePicker(p, next));
        builder.open(player);
    }

    private void openArrestConfirm(Player player) {
        Draft draft = draftOf(player);
        var builder = ctx.gui().builder(GuiKit.title("Confirm arrest"), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the charge picker"), (p, c) -> openChargePicker(p, 1));
        builder.item(GuiKit.STATUS, ItemBuilder.of(Material.REDSTONE_TORCH)
                .name("&6Arrest " + nameOf(draft.target))
                .lore("&7Charge: &f" + draft.charge,
                        "&7Bodycam footage is recorded",
                        "&7and the suspect is detained.")
                .build(), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());
        builder.item(GuiKit.ACTION_3, GuiKit.icon(Material.RED_WOOL, "&cConfirm arrest",
                "&7Detain " + nameOf(draft.target) + " for &f" + draft.charge + "&7."),
                (p, c) -> {
                    module.arrestPlayer(p, draft.target, draft.charge);
                    drafts.remove(p.getUniqueId());
                    openDashboard(p);
                });
        builder.item(GuiKit.ACTION_5, GuiKit.icon(Material.BARRIER, "&7Cancel",
                "&7Return to the charge picker."), (p, c) -> openChargePicker(p, 1));
        builder.open(player);
    }

    // --- Release ----------------------------------------------------------

    private void openPrisoners(Player player, int page) {
        List<ArrestRecord> records = new ArrayList<>(module.arrests().all());
        int pages = GuiKit.Pages.pages(records.size(), PER_PAGE);
        int safe = Math.min(page, pages);
        var builder = ctx.gui().builder(GuiKit.title("In custody"), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the law menu"), (p, c) -> openDashboard(p));
        builder.item(GuiKit.STATUS, GuiKit.icon(Material.IRON_BARS, "&6In custody",
                "&7" + records.size() + " prisoner(s).",
                "&7Click a prisoner to release them."), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());
        List<ArrestRecord> slice = GuiKit.Pages.slice(records, safe, PER_PAGE);
        for (int i = 0; i < slice.size(); i++) {
            ArrestRecord record = slice.get(i);
            long remaining = record.releaseAt > 0 ? Math.max(0, record.releaseAt - System.currentTimeMillis()) : 0;
            String timeLeft = record.releaseAt > 0 ? formatDuration(remaining) : "awaiting lawyer review";
            builder.item(GuiKit.GRID_FIRST + i, GuiKit.playerHead(nameOf(record.player),
                    "&e" + nameOf(record.player),
                    "&7Charge: &f" + record.charge,
                    "&7Detained: &8" + new SimpleDateFormat("HH:mm dd-MM").format(new Date(record.jailedAt)),
                    "&7Time left: &f" + timeLeft,
                    "&aClick to release."), (p, c) -> {
                module.releasePlayer(p, record.player);
                openPrisoners(p, 1);
            });
        }
        addPaging(builder, player, safe, pages, (p, next) -> openPrisoners(p, next));
        builder.open(player);
    }

    // --- Bodycam ----------------------------------------------------------

    private void openBodycam(Player player, int page) {
        int hours = module.lawConfig().getInt("bodycam.retention-hours", 48);
        List<BodycamEntry> entries = new ArrayList<>(module.bodycam().forOfficer(player.getUniqueId(), hours));
        int pages = GuiKit.Pages.pages(entries.size(), PER_PAGE);
        int safe = Math.min(page, pages);
        var builder = ctx.gui().builder(GuiKit.title("Bodycam footage"), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the law menu"), (p, c) -> openDashboard(p));
        builder.item(GuiKit.STATUS, GuiKit.icon(Material.SPYGLASS, "&eYour bodycam",
                "&7" + entries.size() + " recording(s) in the last " + hours + "h."), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());
        if (entries.isEmpty()) {
            builder.item(GuiKit.GRID_FIRST, GuiKit.icon(Material.GRAY_DYE, "&7No footage",
                    "&7Recordings appear here after", "&7police interactions."), GuiKit.NONE);
        }
        List<BodycamEntry> slice = GuiKit.Pages.slice(entries, safe, PER_PAGE);
        for (int i = 0; i < slice.size(); i++) {
            BodycamEntry entry = slice.get(i);
            builder.item(GuiKit.GRID_FIRST + i, ItemBuilder.of(Material.MUSIC_DISC_CAT)
                    .name("&e" + entry.action + (entry.charge.isEmpty() ? "" : " &7- &6" + entry.charge))
                    .lore("&7Target: &f" + nameOf(entry.target),
                            "&8" + new SimpleDateFormat("HH:mm dd-MM").format(new Date(entry.timestamp)))
                    .build(), GuiKit.NONE);
        }
        addPaging(builder, player, safe, pages, (p, next) -> openBodycam(p, next));
        builder.open(player);
    }

    // --- Lawyer: pending reviews -----------------------------------------

    private void openPending(Player player, int page) {
        List<UUID> pending = new ArrayList<>();
        for (String raw : module.pendingList()) {
            pending.add(UUID.fromString(raw));
        }
        int pages = GuiKit.Pages.pages(pending.size(), PER_PAGE);
        int safe = Math.min(page, pages);
        var builder = ctx.gui().builder(GuiKit.title("Pending reviews"), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the law menu"), (p, c) -> openDashboard(p));
        builder.item(GuiKit.STATUS, GuiKit.icon(Material.CLOCK, "&ePending reviews",
                "&7" + pending.size() + " defendant(s) waiting.",
                "&7Click a defendant to review."), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());
        List<UUID> slice = GuiKit.Pages.slice(pending, safe, PER_PAGE);
        for (int i = 0; i < slice.size(); i++) {
            UUID defendant = slice.get(i);
            String charge = module.arrests().recordOf(defendant).map(r -> r.charge).orElse("unknown");
            builder.item(GuiKit.GRID_FIRST + i, GuiKit.playerHead(nameOf(defendant),
                    "&e" + nameOf(defendant),
                    "&7Charge: &f" + charge,
                    "&aClick to review."), (p, c) -> openReview(p, defendant));
        }
        addPaging(builder, player, safe, pages, (p, next) -> openPending(p, next));
        builder.open(player);
    }

    private void openReview(Player player, UUID defendant) {
        double fee = module.privateFee(player.getUniqueId());
        boolean hired = fee <= 0 || ctx.storage().getModuleData("law",
                "hired:" + defendant + ":" + player.getUniqueId()).isPresent();
        int evidence = module.bodycam().forTarget(defendant).size();
        String charge = module.arrests().recordOf(defendant).map(r -> r.charge).orElse("unknown");

        var builder = ctx.gui().builder(GuiKit.title("Review case"), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the pending list"), (p, c) -> openPending(p, 1));
        builder.item(GuiKit.STATUS, ItemBuilder.of(Material.CLOCK)
                .name("&6Review " + nameOf(defendant))
                .lore("&7Charge: &f" + charge,
                        "&7Evidence entries: &f" + evidence,
                        "&7You are a " + (fee <= 0 ? "&astate lawyer" : "&eprivate lawyer") + ".")
                .build(), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());

        if (fee > 0 && !hired) {
            builder.item(GuiKit.ACTION_3, GuiKit.icon(Material.EMERALD, "&aHire (private)",
                    "&7Charge " + nameOf(defendant) + " &f" + GuiKit.fmt(fee),
                    "&7Click to be hired for this case."), (p, c) -> {
                if (module.hireLawyer(p, defendant)) {
                    openReview(p, defendant);
                }
            });
        } else {
            builder.item(GuiKit.ACTION_2, GuiKit.icon(Material.GREEN_WOOL, "&aRelease",
                    "&7No clear bodycam evidence -",
                    "&7release the suspect."), (p, c) -> {
                module.reviewCase(p, defendant, "RELEASE");
                openPending(p, 1);
            });
            builder.item(GuiKit.ACTION_4, GuiKit.icon(Material.RED_WOOL, "&cProceed",
                    "&7Evidence supports the arrest -",
                    "&7processing continues."), (p, c) -> {
                module.reviewCase(p, defendant, "PROCEED");
                openPending(p, 1);
            });
        }
        builder.item(GuiKit.ACTION_5, GuiKit.icon(Material.BOOK, "&6Evidence",
                "&7" + evidence + " bodycam entr(y/ies)",
                "&7involving this defendant."), GuiKit.NONE);
        builder.open(player);
    }

    // --- Court ------------------------------------------------------------

    private void openCourt(Player player, int page) {
        List<LegalCase> cases = new ArrayList<>(module.courts().open());
        int pages = GuiKit.Pages.pages(cases.size(), PER_PAGE);
        int safe = Math.min(page, pages);
        var builder = ctx.gui().builder(GuiKit.title("Court docket"), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the law menu"), (p, c) -> openDashboard(p));
        builder.item(GuiKit.STATUS, GuiKit.icon(Material.ANVIL, "&6Open cases",
                "&7" + cases.size() + " case(s) awaiting a ruling.",
                "&7Click a case to rule on it."), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());
        List<LegalCase> slice = GuiKit.Pages.slice(cases, safe, PER_PAGE);
        for (int i = 0; i < slice.size(); i++) {
            LegalCase legalCase = slice.get(i);
            builder.item(GuiKit.GRID_FIRST + i, ItemBuilder.of(Material.PAPER)
                    .name("&6Case #" + legalCase.id)
                    .lore("&7Defendant: &f" + nameOf(legalCase.defendant),
                            "&7Charge: &f" + legalCase.charge,
                            "&7Filed by: &f" + nameOf(legalCase.officer),
                            "&aClick to rule.").build(), (p, c) -> openCaseDetail(p, legalCase.id));
        }
        addPaging(builder, player, safe, pages, (p, next) -> openCourt(p, next));
        builder.open(player);
    }

    private void openCaseDetail(Player player, int caseId) {
        LegalCase legalCase = module.courts().byId(caseId).orElse(null);
        if (legalCase == null || !legalCase.status.equals("COURT")) {
            ctx.notifications().warn(player, "That case is no longer open.");
            openCourt(player, 1);
            return;
        }
        Draft draft = drafts.computeIfAbsent(player.getUniqueId(), k -> new Draft());
        int evidence = module.bodycam().forTarget(legalCase.defendant).size();

        var builder = ctx.gui().builder(GuiKit.title("Case #" + caseId), 6);
        GuiKit.frame(builder);
        builder.item(0, GuiKit.back("the court docket"), (p, c) -> openCourt(p, 1));
        builder.item(GuiKit.STATUS, ItemBuilder.of(Material.PAPER)
                .name("&6Case #" + legalCase.id)
                .lore("&7Defendant: &f" + nameOf(legalCase.defendant),
                        "&7Charge: &f" + legalCase.charge,
                        "&7Filed by: &f" + nameOf(legalCase.officer),
                        "&7Evidence: &f" + evidence)
                .build(), GuiKit.NONE);
        builder.item(GuiKit.CLOSE, GuiKit.close(), (p, c) -> p.closeInventory());

        builder.item(GuiKit.ACTION_1, ItemBuilder.of(Material.GOLD_INGOT)
                .name("&6Fine: &f" + GuiKit.fmt(draft.fine))
                .lore("&7Left: &f+1,000", "&7Right: &f-1,000",
                        "&7Shift-left: &f+10,000", "&7Shift-right: &f-10,000")
                .build(), (p, c) -> {
            long step = c.isShiftClick() ? 10_000 : 1_000;
            long delta = (c == ClickType.RIGHT || c == ClickType.SHIFT_RIGHT) ? -step : step;
            draft.fine = Math.max(0, Math.min(1_000_000, draft.fine + delta));
            openCaseDetail(p, caseId);
        });
        builder.item(GuiKit.ACTION_2, ItemBuilder.of(Material.CLOCK)
                .name("&eJail days: &f" + draft.days)
                .lore("&7Left: &f+1", "&7Right: &f-1",
                        "&7Shift-left: &f+5", "&7Shift-right: &f-5")
                .build(), (p, c) -> {
            int step = c.isShiftClick() ? 5 : 1;
            int delta = (c == ClickType.RIGHT || c == ClickType.SHIFT_RIGHT) ? -step : step;
            draft.days = Math.max(0, Math.min(90, draft.days + delta));
            openCaseDetail(p, caseId);
        });
        builder.item(GuiKit.ACTION_3, GuiKit.icon(Material.GREEN_WOOL, "&aRule INNOCENT",
                "&7Release the defendant."), (p, c) -> {
            if (module.ruleCase(p, caseId, "INNOCENT", 0, 0)) {
                p.sendMessage(Text.color("&aVerdict INNOCENT - defendant released."));
            }
            drafts.remove(p.getUniqueId());
            openCourt(p, 1);
        });
        builder.item(GuiKit.ACTION_4, GuiKit.icon(Material.RED_WOOL, "&cRule GUILTY",
                "&7Fine: &f" + GuiKit.fmt(draft.fine),
                "&7Jail: &f" + draft.days + " day(s)"), (p, c) -> {
            if (module.ruleCase(p, caseId, "GUILTY", draft.fine, draft.days)) {
                p.sendMessage(Text.color("&aVerdict GUILTY: fine " + GuiKit.fmt(draft.fine)
                        + ", " + draft.days + " days."));
            }
            drafts.remove(p.getUniqueId());
            openCourt(p, 1);
        });
        builder.open(player);
    }

    // --- Helpers ----------------------------------------------------------

    private List<LawEntry> lawEntries() {
        List<LawEntry> result = new ArrayList<>();
        ConfigurationSection book = module.lawConfig().getSection("lawbook");
        if (book == null) {
            return result;
        }
        for (String id : book.getKeys(false)) {
            LawEntry law = new LawEntry();
            law.name = module.lawConfig().getString("lawbook." + id + ".name", id);
            law.fine = module.lawConfig().getDouble("lawbook." + id + ".fine", 0);
            law.days = module.lawConfig().getInt("lawbook." + id + ".days", 0);
            result.add(law);
        }
        return result;
    }

    private Draft draftOf(Player player) {
        return drafts.computeIfAbsent(player.getUniqueId(), k -> new Draft());
    }

    private void addPaging(GUIService.GuiBuilder builder, Player player, int page, int pages, PagingAction action) {
        if (pages <= 1) {
            return;
        }
        builder.item(GuiKit.PAGE_PREV, page > 1 ? GuiKit.prevPage(page) : GuiKit.pageGap(),
                (p, c) -> {
                    if (page > 1) {
                        action.accept(p, page - 1);
                    }
                });
        builder.item(GuiKit.PAGE_INDICATOR, GuiKit.pageIndicator(page, pages), GuiKit.NONE);
        builder.item(GuiKit.PAGE_NEXT, page < pages ? GuiKit.nextPage(page) : GuiKit.pageGap(),
                (p, c) -> {
                    if (page < pages) {
                        action.accept(p, page + 1);
                    }
                });
    }

    @FunctionalInterface
    private interface PagingAction {
        void accept(Player player, int page);
    }

    private String nameOf(UUID uuid) {
        if (uuid == null) {
            return "unknown";
        }
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) {
            return online.getName();
        }
        String name = Bukkit.getOfflinePlayer(uuid).getName();
        return name == null ? uuid.toString().substring(0, 8) : name;
    }

    private String formatDuration(long millis) {
        long days = millis / 86400_000L;
        long hours = (millis % 86400_000L) / 3600_000L;
        if (days > 0) {
            return days + "d " + hours + "h";
        }
        long minutes = (millis % 3600_000L) / 60_000L;
        return hours + "h " + minutes + "m";
    }

    private static final class Draft {
        UUID target;
        String charge;
        double fine;
        int days;
    }

    private static final class LawEntry {
        String name;
        double fine;
        int days;
    }
}
