package dev.mja00.villagerLobotomizer.policy;

import org.bukkit.loot.LootTables;

import java.util.Map;
import java.util.Random;

/**
 * Pure rules for Hero of the Village gifts from lobotomized villagers, mirroring vanilla's gift
 * behavior, which a villager with its AI off can no longer run itself.
 */
public final class HeroGiftPolicy {

    /** Vanilla villagers see players within their 16-block follow range, and count the cooldown down then. */
    public static final double VIEW_RANGE = 16.0;
    public static final double VIEW_RANGE_SQUARED = VIEW_RANGE * VIEW_RANGE;
    /** Vanilla only hands a gift over once the villager's block is closer than 5 blocks to the hero's. */
    public static final int THROW_RANGE_SQUARED = 5 * 5;
    /** Vanilla's player sensor refreshes what a villager can see every 20 ticks. */
    public static final long SCAN_INTERVAL_TICKS = 20L;
    /** Vanilla's delay before a villager's first gift. */
    public static final long FIRST_GIFT_DELAY_TICKS = 600L;
    public static final long MIN_COOLDOWN_TICKS = 600L;
    public static final long MAX_COOLDOWN_TICKS = 6600L;

    private static final Map<String, LootTables> PROFESSION_GIFTS = Map.ofEntries(
            Map.entry("armorer", LootTables.ARMORER_GIFT),
            Map.entry("butcher", LootTables.BUTCHER_GIFT),
            Map.entry("cartographer", LootTables.CARTOGRAPHER_GIFT),
            Map.entry("cleric", LootTables.CLERIC_GIFT),
            Map.entry("farmer", LootTables.FARMER_GIFT),
            Map.entry("fisherman", LootTables.FISHERMAN_GIFT),
            Map.entry("fletcher", LootTables.FLETCHER_GIFT),
            Map.entry("leatherworker", LootTables.LEATHERWORKER_GIFT),
            Map.entry("librarian", LootTables.LIBRARIAN_GIFT),
            Map.entry("mason", LootTables.MASON_GIFT),
            Map.entry("shepherd", LootTables.SHEPHERD_GIFT),
            Map.entry("toolsmith", LootTables.TOOLSMITH_GIFT),
            Map.entry("weaponsmith", LootTables.WEAPONSMITH_GIFT));

    public enum Timing {
        /** No cooldown yet: start one at {@link #FIRST_GIFT_DELAY_TICKS}. */
        SCHEDULE_FIRST,
        /** Cooldown still running. */
        WAIT,
        /** Cooldown elapsed: give a gift and start a new cooldown. */
        GIVE
    }

    private HeroGiftPolicy() {
    }

    /**
     * Whether a hero counts as in view, before the (costlier) line-of-sight check. Like vanilla this is
     * far wider than the gift range: every villager watching a hero runs its cooldown down.
     */
    public static boolean isCandidate(double distanceSquared, boolean hasHeroEffect, boolean spectator) {
        return hasHeroEffect && !spectator && distanceSquared < VIEW_RANGE_SQUARED;
    }

    /** Vanilla's gift range, measured between block positions. */
    public static boolean withinThrowingDistance(int dx, int dy, int dz) {
        return (long) dx * dx + (long) dy * dy + (long) dz * dz < THROW_RANGE_SQUARED;
    }

    /**
     * Like vanilla, the cooldown only runs while a hero is in view: a villager a hero walks past
     * again later still has to watch them for the rest of its cooldown before gifting.
     *
     * @param remainingTicks the stored cooldown after {@link #countDown}, or {@code null} if none
     */
    public static Timing timing(Long remainingTicks) {
        if (remainingTicks == null) {
            return Timing.SCHEDULE_FIRST;
        }
        return remainingTicks <= 0 ? Timing.GIVE : Timing.WAIT;
    }

    /**
     * Counts the cooldown down for a sighting. Vanilla's sensor remembers a seen hero for one scan, so
     * each sighting is worth at most one scan interval: a hero seen on every other scan runs the
     * cooldown at half speed, and a failed check needs no record. Several heroes scanning the same
     * villager share its last-seen tick, so between them they can never credit more than real time.
     *
     * @param lastSeenTick   the game tick of the previous sighting, or {@code null} if there was none
     * @param maxCreditTicks the most one sighting may credit, i.e. the scan interval; also bounds a
     *                       stale tick or one from another world's clock
     */
    public static long countDown(long remainingTicks, Long lastSeenTick, long now, long maxCreditTicks) {
        long remaining = Math.min(remainingTicks, MAX_COOLDOWN_TICKS);
        if (lastSeenTick == null) {
            return remaining;
        }
        long elapsed = Math.clamp(now - lastSeenTick, 0L, maxCreditTicks);
        return Math.max(0L, remaining - elapsed);
    }

    public static long firstGiftCooldown() {
        return FIRST_GIFT_DELAY_TICKS;
    }

    /** Vanilla picks the next cooldown uniformly between 600 and 6600 ticks. */
    public static long nextGiftCooldown(Random random) {
        return MIN_COOLDOWN_TICKS + random.nextLong(MAX_COOLDOWN_TICKS - MIN_COOLDOWN_TICKS + 1);
    }

    /**
     * Vanilla's gift table: babies use the baby table, known professions their own table, and
     * everyone else (unemployed, nitwit) the unemployed table.
     *
     * @param professionPath the profession key's path, e.g. {@code "librarian"}
     */
    public static LootTables giftTable(boolean baby, String professionPath) {
        if (baby) {
            return LootTables.BABY_VILLAGER_GIFT;
        }
        return PROFESSION_GIFTS.getOrDefault(professionPath, LootTables.UNEMPLOYED_GIFT);
    }
}
