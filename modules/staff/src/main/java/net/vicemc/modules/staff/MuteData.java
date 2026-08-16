package net.vicemc.modules.staff;

/**
 * The active mute of a player as handed to the runtime listener. mutedUntil
 * is 0 for a permanent mute, otherwise the epoch millis the mute ends.
 */
public record MuteData(long mutedUntil, String reason) {

    public boolean active(long now) {
        return mutedUntil == 0 || mutedUntil > now;
    }

    public String remainingLabel(long now) {
        if (mutedUntil == 0) {
            return "permanently";
        }
        return StaffModule.durationLabel(mutedUntil - now);
    }
}
