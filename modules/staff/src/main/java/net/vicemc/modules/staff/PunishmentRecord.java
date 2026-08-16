package net.vicemc.modules.staff;

/**
 * A persistent ban or mute. type is "ban" or "mute". expires is 0 for
 * permanent punishments, otherwise the epoch millis the punishment ends.
 * A punishment is active while its type exists and has not expired yet.
 */
public record PunishmentRecord(String uuid, String name, String type, String reason,
                               String actor, long time, long expires) {

    public boolean isBan() {
        return "ban".equals(type);
    }

    public boolean isMute() {
        return "mute".equals(type);
    }

    public boolean active(long now) {
        return expires == 0 || expires > now;
    }

    public String durationLabel(long now) {
        if (expires == 0) {
            return "Permanent";
        }
        return StaffModule.durationLabel(expires - now);
    }
}
