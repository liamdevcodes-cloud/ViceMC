package net.vicemc.modules.law;

import com.google.gson.reflect.TypeToken;
import net.vicemc.api.ViceModuleContext;
import net.vicemc.api.util.Json;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Court cases escalated from arrests and lawyer reviews. Judges rule based
 * on the written law book.
 */
public final class CourtManager {

    private static final TypeToken<List<LegalCase>> CASES_TYPE = new TypeToken<List<LegalCase>>() {
    };

    private final ViceModuleContext ctx;
    private final List<LegalCase> cases = new CopyOnWriteArrayList<>();

    public CourtManager(ViceModuleContext ctx) {
        this.ctx = ctx;
        ctx.storage().getModuleData("law", "cases").ifPresent(json -> {
            List<LegalCase> loaded = Json.fromJson(json, CASES_TYPE.getType());
            if (loaded != null) {
                cases.addAll(loaded);
            }
        });
    }

    public LegalCase file(UUID defendant, UUID officer, String charge) {
        LegalCase legalCase = new LegalCase();
        legalCase.id = nextId();
        legalCase.defendant = defendant;
        legalCase.officer = officer;
        legalCase.charge = charge;
        legalCase.filedAt = System.currentTimeMillis();
        cases.add(legalCase);
        save();
        return legalCase;
    }

    public void save() {
        ctx.storage().setModuleData("law", "cases", Json.toJson(cases));
    }

    private int nextId() {
        return cases.stream().mapToInt(c -> c.id).max().orElse(0) + 1;
    }

    public Optional<LegalCase> byId(int id) {
        return cases.stream().filter(c -> c.id == id).findFirst();
    }

    public List<LegalCase> all() {
        return cases;
    }

    public List<LegalCase> open() {
        return cases.stream().filter(c -> c.status.equals("COURT")).toList();
    }
}
