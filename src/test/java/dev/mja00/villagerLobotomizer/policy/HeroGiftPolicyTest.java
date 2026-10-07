package dev.mja00.villagerLobotomizer.policy;

import dev.mja00.villagerLobotomizer.policy.HeroGiftPolicy.GiftClock;
import dev.mja00.villagerLobotomizer.policy.HeroGiftPolicy.Hit;
import dev.mja00.villagerLobotomizer.policy.HeroGiftPolicy.SegmentCollider;
import dev.mja00.villagerLobotomizer.policy.HeroGiftPolicy.Step;
import org.bukkit.loot.LootTables;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Random;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HeroGiftPolicyTest {

    private static final long SCAN = HeroGiftPolicy.SCAN_INTERVAL_TICKS;
    private static final UUID HERO = UUID.randomUUID();
    private static final double NEAR = 4.0;

    @Test
    void candidateNeedsEffectViewRangeAndNonSpectator() {
        assertTrue(HeroGiftPolicy.isCandidate(15.9 * 15.9, true, false), "vanilla villagers see 16 blocks");
        assertFalse(HeroGiftPolicy.isCandidate(16.0 * 16.0, true, false));
        assertFalse(HeroGiftPolicy.isCandidate(1.0, false, false), "no hero effect");
        assertFalse(HeroGiftPolicy.isCandidate(1.0, true, true), "spectators are never gifted");
    }

    @Test
    void throwingDistanceIsUnderFiveBlocks() {
        assertTrue(HeroGiftPolicy.withinThrowingDistance(4, 0, 0));
        assertTrue(HeroGiftPolicy.withinThrowingDistance(3, 1, 3));
        assertFalse(HeroGiftPolicy.withinThrowingDistance(5, 0, 0), "vanilla range is strictly under 5 blocks");
        assertFalse(HeroGiftPolicy.withinThrowingDistance(3, 0, 4));
    }

    @Test
    void aNewClockStartsAtVanillasFirstGiftDelay() {
        assertEquals(600L, new GiftClock().remainingTicks());
    }

    @Test
    void firstGiftsAreSpreadOverSeveralScans() {
        Random random = new Random(7);
        Set<Long> readyOnScan = new HashSet<>();
        for (int i = 0; i < 1_000; i++) {
            long delay = HeroGiftPolicy.firstGiftDelay(random);
            assertTrue(delay >= 600L && delay <= 600L + HeroGiftPolicy.FIRST_GIFT_JITTER_TICKS, "delay " + delay);
            readyOnScan.add((delay + SCAN - 1) / SCAN);
        }
        assertTrue(readyOnScan.size() > 1, "villagers seen together must not all throw on the same scan");
    }

    @Test
    void firstSightingCreditsNothing() {
        GiftClock clock = new GiftClock(300L);

        assertEquals(Step.WAIT, HeroGiftPolicy.onSighting(clock, 5000L, HERO, NEAR, true));
        assertEquals(300L, clock.remainingTicks());
    }

    @Test
    void eachSightingCreditsAtMostOneScan() {
        GiftClock clock = new GiftClock(300L);
        HeroGiftPolicy.onSighting(clock, 1000L, HERO, NEAR, true);

        HeroGiftPolicy.onSighting(clock, 1020L, HERO, NEAR, true);
        assertEquals(280L, clock.remainingTicks());

        HeroGiftPolicy.onSighting(clock, 1060L, HERO, NEAR, true);
        assertEquals(260L, clock.remainingTicks(), "a hidden scan in between is not credited");

        HeroGiftPolicy.onSighting(clock, 1070L, HERO, NEAR, true);
        assertEquals(250L, clock.remainingTicks(), "a second hero's offset scan credits only the time between");

        HeroGiftPolicy.onSighting(clock, 900_000L, HERO, NEAR, true);
        assertEquals(230L, clock.remainingTicks(), "a stale last-seen tick credits one scan");

        HeroGiftPolicy.onSighting(clock, 1_000L, HERO, NEAR, true);
        assertEquals(230L, clock.remainingTicks(), "a tick from another world's clock credits nothing");
    }

    @Test
    void readyVillagerFacesTheHeroThenGivesAfterTheHeadTurn() {
        GiftClock clock = new GiftClock(SCAN);
        HeroGiftPolicy.onSighting(clock, 1000L, HERO, NEAR, true);

        assertEquals(Step.FACE, HeroGiftPolicy.onSighting(clock, 1000L + SCAN, HERO, NEAR, true), "cooldown just ran out");
        assertEquals(0L, clock.remainingTicks());
        assertEquals(Step.GIVE, HeroGiftPolicy.onSighting(clock, 1000L + SCAN + HeroGiftPolicy.HEAD_TURN_TICKS, HERO, NEAR, true));
    }

    @Test
    void readyGiftWaitsForTheHeroToComeInRange() {
        GiftClock clock = new GiftClock(0L);
        assertEquals(Step.FACE, HeroGiftPolicy.onSighting(clock, 1000L, HERO, NEAR, false));
        assertEquals(Step.FACE, HeroGiftPolicy.onSighting(clock, 1020L, HERO, NEAR, false), "hero in view but too far");
        assertEquals(Step.FACE, HeroGiftPolicy.onSighting(clock, 1040L, HERO, NEAR, false));

        assertEquals(Step.GIVE, HeroGiftPolicy.onSighting(clock, 1060L, HERO, NEAR, true), "already facing the hero");
    }

    @Test
    void losingSightOfTheHeroRestartsTheHeadTurn() {
        GiftClock clock = new GiftClock(0L);
        HeroGiftPolicy.onSighting(clock, 1000L, HERO, NEAR, true);

        assertEquals(Step.FACE, HeroGiftPolicy.onSighting(clock, 1060L, HERO, NEAR, true), "the hero was out of view in between");
        assertEquals(Step.GIVE, HeroGiftPolicy.onSighting(clock, 1080L, HERO, NEAR, true));
    }

    @Test
    void nextCooldownStaysWithinVanillaBounds() {
        Random random = new Random(42);
        for (int i = 0; i < 10_000; i++) {
            GiftClock clock = new GiftClock(0L);
            HeroGiftPolicy.startNextCooldown(clock, random);
            long cooldown = clock.remainingTicks();
            assertTrue(cooldown >= 600L && cooldown <= 6600L, "cooldown " + cooldown);
        }
    }

    @Test
    void aCloserHeroTakesOverTheHandOverAndRestartsTheHeadTurn() {
        UUID far = UUID.randomUUID();
        UUID near = UUID.randomUUID();
        GiftClock clock = new GiftClock(0L);
        assertEquals(Step.FACE, HeroGiftPolicy.onSighting(clock, 1000L, far, 100.0, false));
        assertEquals(Step.FACE, HeroGiftPolicy.onSighting(clock, 1020L, far, 100.0, false));

        assertEquals(Step.FACE, HeroGiftPolicy.onSighting(clock, 1020L, near, 4.0, true),
                "turning to the new hero, not throwing on the old hero's head turn");
        assertEquals(Step.WAIT, HeroGiftPolicy.onSighting(clock, 1030L, far, 100.0, false),
                "the farther hero does not take the villager back");
        assertEquals(Step.GIVE, HeroGiftPolicy.onSighting(clock, 1040L, near, 4.0, true));
    }

    @Test
    void equallyCloseHeroesDoNotTakeTheHandOverFromEachOther() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        GiftClock clock = new GiftClock(0L);
        HeroGiftPolicy.onSighting(clock, 1000L, first, 4.0, true);

        assertEquals(Step.WAIT, HeroGiftPolicy.onSighting(clock, 1010L, second, 4.0, true));
        assertEquals(Step.GIVE, HeroGiftPolicy.onSighting(clock, 1020L, first, 4.0, true));
    }

    @Test
    void aHeroWhoLeftViewLosesTheHandOver() {
        UUID gone = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        GiftClock clock = new GiftClock(0L);
        HeroGiftPolicy.onSighting(clock, 1000L, gone, 4.0, true);

        assertEquals(Step.FACE, HeroGiftPolicy.onSighting(clock, 1100L, other, 9.0, true));
        assertEquals(Step.GIVE, HeroGiftPolicy.onSighting(clock, 1120L, other, 9.0, true));
    }

    // Villager standing on the block at (0, 64, 0), throwing east as vanilla does: from 0.3 below its
    // eyes, at 0.3 blocks per tick toward the hero's feet on the same level.
    private static final Vector VILLAGER = new Vector(0.5, 64.0, 0.5);
    private static final Vector THROW_FROM = new Vector(0.5, 64.0 + 1.62 - HeroGiftPolicy.THROW_HEIGHT_BELOW_EYES, 0.5);
    private static final Vector EAST = new Vector(HeroGiftPolicy.THROW_SPEED, 0.0, 0.0);
    private static final double[] FLOOR = {-8, 63, -8, 8, 64, 8};

    @Test
    void throwOverAnOpenFloorLandsOutsideTheCell() {
        assertTrue(HeroGiftPolicy.throwEscapes(THROW_FROM, EAST, VILLAGER, boxes(FLOOR)));
    }

    @Test
    void throwThatClearsALowCounterLandsOnTopOfIt() {
        double[] counter = {1, 64, -1, 2, 65, 2};
        assertTrue(HeroGiftPolicy.throwEscapes(THROW_FROM, EAST, VILLAGER, boxes(FLOOR, counter)));
    }

    @Test
    void throwIntoALowBarrierFurtherOutIsBlocked() {
        // The item leaves level and falls: 1.5 blocks out it is below this counter's top, though a
        // straight ray from the same spot to the torso of a hero 4 blocks away would clear it.
        double[] counter = {2, 64, -1, 3, 65, 2};
        assertFalse(HeroGiftPolicy.throwEscapes(THROW_FROM, EAST, VILLAGER, boxes(FLOOR, counter)));
    }

    @Test
    void throwIntoACellWallIsBlocked() {
        double[] wall = {1, 64, -1, 2, 66, 2};
        assertFalse(HeroGiftPolicy.throwEscapes(THROW_FROM, EAST, VILLAGER, boxes(FLOOR, wall)));
    }

    @Test
    void aGiftLandingInTheVillagersOwnBlockIsBlocked() {
        assertFalse(HeroGiftPolicy.throwEscapes(THROW_FROM, new Vector(), VILLAGER, boxes(FLOOR)),
                "a hero standing in the villager's spot gets nothing thrown; the gift drops at their feet");
    }

    @Test
    void aThrowThatNeverLandsHasClearedTheCell() {
        assertTrue(HeroGiftPolicy.throwEscapes(THROW_FROM, EAST, VILLAGER, (from, to) -> null));
    }

    /** Axis-aligned boxes {minX, minY, minZ, maxX, maxY, maxZ}, hit with the slab method. */
    private static SegmentCollider boxes(double[]... boxes) {
        List<double[]> list = List.of(boxes);
        return (from, to) -> {
            double[] p = {from.getX(), from.getY(), from.getZ()};
            double[] d = {to.getX() - p[0], to.getY() - p[1], to.getZ() - p[2]};
            Hit best = null;
            double bestT = Double.MAX_VALUE;
            for (double[] box : list) {
                double enter = 0.0;
                double exit = 1.0;
                int enterAxis = -1;
                boolean miss = false;
                for (int axis = 0; axis < 3 && !miss; axis++) {
                    double min = box[axis];
                    double max = box[axis + 3];
                    if (Math.abs(d[axis]) < 1.0E-12) {
                        miss = p[axis] < min || p[axis] > max;
                        continue;
                    }
                    double t1 = (min - p[axis]) / d[axis];
                    double t2 = (max - p[axis]) / d[axis];
                    double near = Math.min(t1, t2);
                    double far = Math.max(t1, t2);
                    if (near > enter) {
                        enter = near;
                        enterAxis = axis;
                    }
                    exit = Math.min(exit, far);
                    miss = enter > exit;
                }
                if (miss || enterAxis < 0 || enter >= bestT) {
                    continue;
                }
                double[] normal = new double[3];
                normal[enterAxis] = d[enterAxis] > 0 ? -1.0 : 1.0;
                bestT = enter;
                best = new Hit(new Vector(p[0] + d[0] * enter, p[1] + d[1] * enter, p[2] + d[2] * enter),
                        new Vector(normal[0], normal[1], normal[2]));
            }
            return best;
        };
    }

    @Test
    void giftTableFollowsVanilla() {
        assertEquals(LootTables.LIBRARIAN_GIFT, HeroGiftPolicy.giftTable(false, "librarian"));
        assertEquals(LootTables.WEAPONSMITH_GIFT, HeroGiftPolicy.giftTable(false, "weaponsmith"));
        assertEquals(LootTables.BABY_VILLAGER_GIFT, HeroGiftPolicy.giftTable(true, "librarian"), "babies use the baby table");
        assertEquals(LootTables.UNEMPLOYED_GIFT, HeroGiftPolicy.giftTable(false, "none"));
        assertEquals(LootTables.UNEMPLOYED_GIFT, HeroGiftPolicy.giftTable(false, "nitwit"));
    }
}
