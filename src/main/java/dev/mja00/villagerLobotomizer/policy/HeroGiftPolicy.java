package dev.mja00.villagerLobotomizer.policy;

import org.bukkit.loot.LootTables;

import java.util.Map;
import java.util.Random;

/**
 * Pure rules for Hero of the Village gifts from lobotomized villagers, mirroring vanilla's gift
 * behaviour, which a villager with its AI off can no longer run itself.
 */
public final class HeroGiftPolicy {

    /** Vanilla only gifts heroes closer than 5 blocks. */
    public static final double RANGE_SQUARED = 5.0 * 5.0;
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

    /** Whether a player is a gift target, before the (costlier) line-of-sight check. */
    public static boolean isCandidate(double distanceSquared, boolean hasHeroEffect, boolean spectator) {
        return hasHeroEffect && !spectator && distanceSquared < RANGE_SQUARED;
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
     * Counts the cooldown down by the time a hero has been in view since the previous check.
     *
     * @param lastSeenTick the game tick of the previous check that saw a hero, or {@code null} if the
     *                     hero was not in view then, in which case no time has accrued yet
     * @param maxStepTicks the longest a single step may credit, i.e. the check interval, so a stale
     *                     tick (or one from another world's clock) can never skip the cooldown
     */
    public static long countDown(long remainingTicks, Long lastSeenTick, long now, long maxStepTicks) {
        long remaining = Math.min(remainingTicks, MAX_COOLDOWN_TICKS);
        if (lastSeenTick == null) {
            return remaining;
        }
        long elapsed = Math.clamp(now - lastSeenTick, 0L, maxStepTicks);
        return remaining - elapsed;
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
