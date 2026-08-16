package net.vicemc.modules.discord;

import net.vicemc.api.ViceModule;
import net.vicemc.api.ViceModuleContext;
import net.vicemc.api.util.Json;
import net.vicemc.api.util.YamlConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

/**
 * Discord bridge. Installs the core NotificationService webhook sender and
 * forwards module events (elections, lawyer reviews, finance, heists,
 * government, RMT flags) to the configured channel webhooks.
 */
public final class DiscordModule implements ViceModule {

    private ViceModuleContext ctx;
    private YamlConfig config;

    @Override
    public String id() {
        return "discord";
    }

    @Override
    public String displayName() {
        return "Vice Discord";
    }

    @Override
    public String version() {
        return "1.0.0";
    }

    @Override
    public void onEnable(ViceModuleContext context) {
        this.ctx = context;
        this.config = ctx.yaml("discord.yml");

        if (!config.getBoolean("enabled", true)) {
            ctx.logger().info("Discord bridge disabled in config.");
            return;
        }

        ctx.notifications().setDiscordSender(this::send);

        ctx.events().subscribe("gangs.election", this::onElection);
        ctx.events().subscribe("law.review", this::onLawyer);
        ctx.events().subscribe("law.court", this::onCourt);
        ctx.events().subscribe("finance", this::onFinance);
        ctx.events().subscribe("government", this::onGovernment);
        ctx.events().subscribe("heist", this::onHeist);
        ctx.events().subscribe("heist.escaped", this::onHeistEscaped);
        ctx.events().subscribe("blooddiamond.rmt", this::onRmt);

        ctx.logger().info("Discord bridge ready.");
    }

    @Override
    public void onDisable() {
        if (config != null && config.getBoolean("enabled", true)) {
            ctx.notifications().setDiscordSender(null);
        }
    }

    // --- Event handlers ---------------------------------------------------

    private void onElection(Map<String, Object> data) {
        String type = str(data, "type");
        switch (type) {
            case "open" -> post("elections", ":stop_sign: Weekly gang election is now open! Use **/vote**.");
            case "result" -> post("elections", ":trophy: " + str(data, "gang")
                    + " elected a new leader!");
            case "join" -> post("elections", ":bust_in_silhouette: " + str(data, "player")
                    + " joined " + str(data, "gang") + ".");
            default -> {
            }
        }
    }

    private void onLawyer(Map<String, Object> data) {
        post("lawyer", ":scales: Lawyer review: **" + str(data, "defendant")
                + "** verdict **" + str(data, "verdict") + "** by " + str(data, "lawyer")
                + " (" + data.getOrDefault("evidence", 0) + " bodycam entries).");
    }

    private void onCourt(Map<String, Object> data) {
        post("lawyer", ":judge: Case #" + data.getOrDefault("caseId", "?")
                + " ruled **" + str(data, "verdict") + "** for " + str(data, "defendant")
                + " - fine " + money(num(data, "fine")) + ", " + data.getOrDefault("days", 0) + " days.");
    }

    private void onFinance(Map<String, Object> data) {
        if ("loan-default".equals(str(data, "type"))) {
            post("finance", ":warning: Loan #" + data.getOrDefault("loanId", "?")
                    + " defaulted. Borrower " + str(data, "borrower")
                    + " owes " + money(num(data, "amount")) + ". Escalating to garnishment.");
        }
    }

    private void onGovernment(Map<String, Object> data) {
        switch (str(data, "type")) {
            case "subsidy" -> post("government", ":office: Government subsidy granted to business "
                    + str(data, "business") + " worth " + money(num(data, "amount")) + ".");
            case "seizure" -> post("government", ":office: Asset seizure: " + money(num(data, "amount"))
                    + " taken from " + str(data, "target") + " for unpaid debts.");
            case "businessban" -> post("government", ":no_entry_sign: " + str(data, "target")
                    + " banned from starting businesses: " + str(data, "reason"));
            default -> {
            }
        }
    }

    private void onHeist(Map<String, Object> data) {
        switch (str(data, "type")) {
            case "start" -> post("heists", ":rotating_light: A heist is in progress at "
                    + str(data, "site") + " (police response " + data.getOrDefault("risk", "?") + "/3).");
            case "success" -> post("heists", ":moneybag: Heist at " + str(data, "site")
                    + " succeeded! Crew share: " + money(num(data, "payout")) + " each.");
            case "end" -> post("heists", ":rotating_light: Heist at " + str(data, "site")
                    + " ended: **" + str(data, "phase") + "**. Lethal force authorized.");
            default -> {
            }
        }
    }

    private void onHeistEscaped(Map<String, Object> data) {
        boolean masked = Boolean.TRUE.equals(data.get("masked"));
        post("heists", ":island: A crew escaped to the island from " + str(data, "site")
                + " (" + data.getOrDefault("members", "?") + " members). "
                + (masked ? "They were masked - identity unknown, no arrest possible."
                : "Identities were visible and remain arrestable."));
    }

    private void onRmt(Map<String, Object> data) {
        post("government", ":rotating_light: **RMT FLAG**: " + str(data, "target")
                + " - " + str(data, "reason") + ". This is a bannable offense.");
    }

    // --- Webhook delivery -------------------------------------------------

    private void post(String channel, String message) {
        String webhook = config.getString("webhooks." + channel, "");
        if (webhook.isBlank()) {
            return;
        }
        send(webhook, message);
    }

    private void send(String webhook, String message) {
        if (webhook == null || webhook.isBlank() || message == null || message.isBlank()) {
            return;
        }
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder(URI.create(webhook))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"content\":" + Json.toJson(message) + "}"))
                .build();
        client.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                .exceptionally(ex -> null);
    }

    // --- Helpers ----------------------------------------------------------

    private String str(Map<String, Object> data, String key) {
        Object value = data.get(key);
        return value == null ? "?" : String.valueOf(value);
    }

    private double num(Map<String, Object> data, String key) {
        Object value = data.get(key);
        return value instanceof Number n ? n.doubleValue() : 0;
    }

    private String money(double value) {
        return "$" + String.format("%,.2f", value);
    }
}
