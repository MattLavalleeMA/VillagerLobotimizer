package dev.mja00.villagerLobotomizer.policy;

import org.bukkit.loot.LootTables;

import java.util.Map;
import java.util.Random;

/**
 * Pure rules for Hero of the Village gifts from lobotomized villagers, mirroring vanilla's gift
 * behavior ({@code GiveGiftToHero}), which a villager with its AI off can no longer run itself.
 */
public final class HeroGiftPolicy {

    /** Vanilla villagers see players within their 16-block follow range, and count the cooldown down then. */
    public static final double VIEW_RANGE = 16.0;
    public static final double VIEW_RANGE_SQUARED = VIEW_RANGE * VIEW_RANGE;
    /** Vanilla only hands a gift over once the villager's block is closer than 5 blocks to the hero's. */
    public static final int THROW_RANGE_SQUARED = 5 * 5;
    /** Vanilla's player sensor refreshes what a villager can see every 20 ticks. */
    public static final long SCAN_INTERVAL_TICKS = 20L;
    /** Vanilla waits this long after turning toward the hero before throwing, so the head can finish turning. */
    public static final long HEAD_TURN_TICKS = 20L;
    /** Vanilla's gift timer, which lives in the villager's brain and so restarts at this on every load. */
    public static final long FIRST_GIFT_DELAY_TICKS = 600L;
    public static final long MIN_COOLDOWN_TICKS = 600L;
    public static final long MAX_COOLDOWN_TICKS = 6600L;
    /** Vanilla throws from this far below the villager's eyes... */
    public static final double THROW_HEIGHT_BELOW_EYES = 0.3;
    /** ...at this speed, in blocks per tick, toward the hero's feet. */
    public static final double THROW_SPEED = 0.3;

    private static final long NEVER = Long.MIN_VALUE;

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

    /** What a villager does on a scan that sees a hero. */
    public enum Step {
        /** Cooldown still running. */
        WAIT,
        /** Gift ready: turn toward the hero, as vanilla does before throwing. */
        FACE,
        /** Facing the hero, which is in range: give the gift, then {@link #startNextCooldown}. */
        GIVE
    }

    /**
     * One villager's gift timer. Like vanilla's it is not saved: a villager starts at
     * {@link #FIRST_GIFT_DELAY_TICKS} whenever it loads. Only touched on the villager's own thread.
     */
    public static final class GiftClock {
        private long remainingTicks;
        private long lastSeenTick = NEVER;
        private long facingSinceTick = NEVER;

        public GiftClock() {
            this(FIRST_GIFT_DELAY_TICKS);
        }

        public GiftClock(long remainingTicks) {
            this.remainingTicks = remainingTicks;
        }

        public long remainingTicks() {
            return this.remainingTicks;
        }
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
     * Advances a villager's clock for a scan that sees a hero.
     * <p>
     * Vanilla's sensor remembers a seen hero for one scan, so each sighting runs the cooldown down by
     * at most one scan interval: a hero seen on every other scan counts at half speed, and a failed
     * check needs no record. Heroes scanning the same villager share its clock, so between them they
     * never credit more than real time. Once the cooldown is done the villager faces the hero, and
     * throws {@link #HEAD_TURN_TICKS} later if the hero is in range. Unlike vanilla, whose villager
     * walks over and gives up after a few seconds, a trapped villager keeps the gift until a hero
     * comes close.
     */
    public static Step onSighting(GiftClock clock, long now, boolean inThrowRange) {
        long sinceLastSeen = clock.lastSeenTick == NEVER ? -1 : now - clock.lastSeenTick;
        boolean continuous = sinceLastSeen >= 0 && sinceLastSeen <= SCAN_INTERVAL_TICKS;
        clock.lastSeenTick = now;
        if (clock.remainingTicks > 0) {
            long credit = sinceLastSeen < 0 ? 0 : Math.min(sinceLastSeen, SCAN_INTERVAL_TICKS);
            clock.remainingTicks = Math.max(0L, clock.remainingTicks - credit);
            if (clock.remainingTicks > 0) {
                return Step.WAIT;
            }
            clock.facingSinceTick = now;
            return Step.FACE;
        }
        if (!continuous || clock.facingSinceTick == NEVER || now < clock.facingSinceTick) {
            // The hero left view, so the villager has to turn toward them again.
            clock.facingSinceTick = now;
            return Step.FACE;
        }
        return inThrowRange && now - clock.facingSinceTick >= HEAD_TURN_TICKS ? Step.GIVE : Step.FACE;
    }

    /** Vanilla picks the next cooldown uniformly between 600 and 6600 ticks. */
    public static void startNextCooldown(GiftClock clock, Random random) {
        clock.remainingTicks = MIN_COOLDOWN_TICKS + random.nextLong(MAX_COOLDOWN_TICKS - MIN_COOLDOWN_TICKS + 1);
        clock.facingSinceTick = NEVER;
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
