package net.vicemc.modules.heists;

import com.google.gson.reflect.TypeToken;
import net.vicemc.api.ViceModuleContext;
import net.vicemc.api.util.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Registry of mafia families and pending invites. Creating a family charges
 * the registration fee (design: requires owning a house, which is validated
 * by the caller via config).
 */
public final class FamilyManager {

    private static final TypeToken<List<Family>> FAMILIES_TYPE = new TypeToken<List<Family>>() {
    };
    private static final TypeToken<List<String>> STRINGS_TYPE = new TypeToken<List<String>>() {
    };

    private final ViceModuleContext ctx;
    private final List<Family> families = new CopyOnWriteArrayList<>();

    public FamilyManager(ViceModuleContext ctx) {
        this.ctx = ctx;
        ctx.storage().getModuleData("heists", "families").ifPresent(json -> {
            List<Family> loaded = Json.fromJson(json, FAMILIES_TYPE.getType());
            if (loaded != null) {
                families.addAll(loaded);
            }
        });
    }

    public Family create(String name, UUID owner) {
        Family family = new Family();
        family.id = nextId();
        family.name = name;
        family.owner = owner;
        family.members.add(owner);
        family.createdAt = System.currentTimeMillis();
        families.add(family);
        save();
        return family;
    }

    public void save() {
        ctx.storage().setModuleData("heists", "families", Json.toJson(families));
    }

    private int nextId() {
        return families.stream().mapToInt(f -> f.id).max().orElse(0) + 1;
    }

    public Optional<Family> byId(int id) {
        return families.stream().filter(f -> f.id == id).findFirst();
    }

    public Family byOwner(UUID uuid) {
        return families.stream().filter(f -> f.owner.equals(uuid)).findFirst().orElse(null);
    }

    public Family byMember(UUID uuid) {
        return families.stream().filter(f -> f.members.contains(uuid)).findFirst().orElse(null);
    }

    public List<Family> all() {
        return families;
    }

    public int memberCount(Family family) {
        return family.members.size();
    }

    // --- Invites ----------------------------------------------------------

    public List<UUID> invites(int familyId) {
        return ctx.storage().getModuleData("heists", "invites:" + familyId)
                .map(json -> {
                    List<String> raw = Json.fromJson(json, STRINGS_TYPE.getType());
                    return raw == null ? List.<UUID>of() : raw.stream().map(UUID::fromString).toList();
                })
                .orElse(List.of());
    }

    public void invite(int familyId, UUID uuid) {
        List<UUID> list = new ArrayList<>(invites(familyId));
        if (!list.contains(uuid)) {
            list.add(uuid);
        }
        ctx.storage().setModuleData("heists", "invites:" + familyId,
                Json.toJson(list.stream().map(UUID::toString).toList()));
    }

    public void removeInvite(int familyId, UUID uuid) {
        List<UUID> list = new ArrayList<>(invites(familyId));
        list.remove(uuid);
        ctx.storage().setModuleData("heists", "invites:" + familyId,
                Json.toJson(list.stream().map(UUID::toString).toList()));
    }

    // --- Membership -------------------------------------------------------

    public void join(int familyId, UUID uuid) {
        Family family = byId(familyId).orElse(null);
        if (family != null) {
            family.members.add(uuid);
            removeInvite(familyId, uuid);
            save();
        }
    }

    public void leave(UUID uuid) {
        Family family = byMember(uuid);
        if (family == null) {
            return;
        }
        family.members.remove(uuid);
        if (family.owner.equals(uuid)) {
            families.remove(family);
        }
        save();
    }

    public void kick(int familyId, UUID uuid) {
        Family family = byId(familyId).orElse(null);
        if (family != null) {
            family.members.remove(uuid);
            save();
        }
    }
}
