package net.vicemc.modules.staff;

/**
 * A single entry in the staff audit trail. Every act-tier action is recorded
 * so moderators can review who did what, when and to whom.
 */
public record AuditEntry(long time, String actor, String action, String target, String detail) {
}
