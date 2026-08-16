package net.vicemc.modules.business;

import com.google.gson.reflect.TypeToken;
import net.vicemc.api.ViceModuleContext;
import net.vicemc.api.util.Json;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Registry of businesses, business licenses and business bans. The government
 * module drives licensing through these methods.
 */
public final class BusinessManager {

    private static final TypeToken<List<Business>> BUSINESS_TYPE = new TypeToken<List<Business>>() {
    };

    private final ViceModuleContext ctx;
    private final List<Business> businesses = new CopyOnWriteArrayList<>();

    public BusinessManager(ViceModuleContext ctx) {
        this.ctx = ctx;
        ctx.storage().getModuleData("business", "businesses").ifPresent(json -> {
            List<Business> loaded = Json.fromJson(json, BUSINESS_TYPE.getType());
            if (loaded != null) {
                businesses.addAll(loaded);
            }
        });
    }

    public Business create(BusinessType type, String name, UUID owner) {
        Business business = new Business();
        business.id = nextId();
        business.type = type.name();
        business.name = name;
        business.owner = owner;
        business.licensed = hasLicense(owner, type.name());
        business.createdAt = System.currentTimeMillis();
        businesses.add(business);
        save();
        return business;
    }

    public void save() {
        ctx.storage().setModuleData("business", "businesses", Json.toJson(businesses));
    }

    private int nextId() {
        return businesses.stream().mapToInt(b -> b.id).max().orElse(0) + 1;
    }

    public Optional<Business> byId(int id) {
        return businesses.stream().filter(b -> b.id == id).findFirst();
    }

    public List<Business> byOwner(UUID owner) {
        return businesses.stream().filter(b -> b.owner.equals(owner)).toList();
    }

    public List<Business> all() {
        return businesses;
    }

    public boolean isOwnerOrEmployee(UUID uuid, Business business) {
        return business.owner.equals(uuid) || business.employees.contains(uuid);
    }

    public Business ownedOfType(UUID uuid, String type) {
        return businesses.stream()
                .filter(b -> b.type.equalsIgnoreCase(type) && b.owner.equals(uuid))
                .findFirst()
                .orElse(null);
    }

    public Business ownedOrEmployedOfType(UUID uuid, String type) {
        return businesses.stream()
                .filter(b -> b.type.equalsIgnoreCase(type) && isOwnerOrEmployee(uuid, b))
                .findFirst()
                .orElse(null);
    }

    // --- Licenses ---------------------------------------------------------

    public Set<String> licenses(UUID player) {
        Set<String> result = new HashSet<>();
        ctx.storage().getModuleData("business", "license:" + player).ifPresent(json -> {
            List<String> loaded = Json.fromJson(json, new TypeToken<List<String>>() {
            }.getType());
            if (loaded != null) {
                result.addAll(loaded);
            }
        });
        return result;
    }

    public boolean hasLicense(UUID player, String type) {
        return licenses(player).contains(type);
    }

    public void grantLicense(UUID player, String type) {
        Set<String> set = new HashSet<>(licenses(player));
        set.add(type.toUpperCase());
        ctx.storage().setModuleData("business", "license:" + player, Json.toJson(new ArrayList<>(set)));
        syncLicenseFlag(player, type);
    }

    public void revokeLicense(UUID player, String type) {
        Set<String> set = new HashSet<>(licenses(player));
        set.remove(type.toUpperCase());
        ctx.storage().setModuleData("business", "license:" + player, Json.toJson(new ArrayList<>(set)));
        syncLicenseFlag(player, type);
    }

    private void syncLicenseFlag(UUID player, String type) {
        boolean has = hasLicense(player, type);
        businesses.stream()
                .filter(b -> b.owner.equals(player) && b.type.equalsIgnoreCase(type))
                .forEach(b -> b.licensed = has);
        save();
    }

    // --- Business bans ----------------------------------------------------

    public boolean isBusinessBanned(UUID player) {
        return ctx.storage().getModuleData("business", "ban:" + player).isPresent();
    }

    public String banReason(UUID player) {
        return ctx.storage().getModuleData("business", "ban:" + player).orElse("");
    }

    public void banFromBusiness(UUID player, String reason) {
        ctx.storage().setModuleData("business", "ban:" + player, reason == null ? "abuse" : reason);
    }

    public void pardonBusiness(UUID player) {
        ctx.storage().removeModuleData("business", "ban:" + player);
    }
}
