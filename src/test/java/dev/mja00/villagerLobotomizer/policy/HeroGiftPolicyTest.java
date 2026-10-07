package dev.mja00.villagerLobotomizer.policy;

import dev.mja00.villagerLobotomizer.policy.HeroGiftPolicy.GiftClock;
import dev.mja00.villagerLobotomizer.policy.HeroGiftPolicy.Step;
import org.bukkit.loot.LootTables;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HeroGiftPolicyTest {

    private static final long SCAN = HeroGiftPolicy.SCAN_INTERVAL_TICKS;

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

        assertEquals(Step.WAIT, HeroGiftPolicy.onSighting(clock, 5000L, true));
        assertEquals(300L, clock.remainingTicks());
    }

    @Test
    void eachSightingCreditsAtMostOneScan() {
        GiftClock clock = new GiftClock(300L);
        HeroGiftPolicy.onSighting(clock, 1000L, true);

        HeroGiftPolicy.onSighting(clock, 1020L, true);
        assertEquals(280L, clock.remainingTicks());

        HeroGiftPolicy.onSighting(clock, 1060L, true);
        assertEquals(260L, clock.remainingTicks(), "a hidden scan in between is not credited");

        HeroGiftPolicy.onSighting(clock, 1070L, true);
        assertEquals(250L, clock.remainingTicks(), "a second hero's offset scan credits only the time between");

        HeroGiftPolicy.onSighting(clock, 900_000L, true);
        assertEquals(230L, clock.remainingTicks(), "a stale last-seen tick credits one scan");

        HeroGiftPolicy.onSighting(clock, 1_000L, true);
        assertEquals(230L, clock.remainingTicks(), "a tick from another world's clock credits nothing");
    }

    @Test
    void readyVillagerFacesTheHeroThenGivesAfterTheHeadTurn() {
        GiftClock clock = new GiftClock(SCAN);
        HeroGiftPolicy.onSighting(clock, 1000L, true);

        assertEquals(Step.FACE, HeroGiftPolicy.onSighting(clock, 1000L + SCAN, true), "cooldown just ran out");
        assertEquals(0L, clock.remainingTicks());
        assertEquals(Step.GIVE, HeroGiftPolicy.onSighting(clock, 1000L + SCAN + HeroGiftPolicy.HEAD_TURN_TICKS, true));
    }

    @Test
    void readyGiftWaitsForTheHeroToComeInRange() {
        GiftClock clock = new GiftClock(0L);
        assertEquals(Step.FACE, HeroGiftPolicy.onSighting(clock, 1000L, false));
        assertEquals(Step.FACE, HeroGiftPolicy.onSighting(clock, 1020L, false), "hero in view but too far");
        assertEquals(Step.FACE, HeroGiftPolicy.onSighting(clock, 1040L, false));

        assertEquals(Step.GIVE, HeroGiftPolicy.onSighting(clock, 1060L, true), "already facing the hero");
    }

    @Test
    void losingSightOfTheHeroRestartsTheHeadTurn() {
        GiftClock clock = new GiftClock(0L);
        HeroGiftPolicy.onSighting(clock, 1000L, true);

        assertEquals(Step.FACE, HeroGiftPolicy.onSighting(clock, 1060L, true), "the hero was out of view in between");
        assertEquals(Step.GIVE, HeroGiftPolicy.onSighting(clock, 1080L, true));
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
    void giftTableFollowsVanilla() {
        assertEquals(LootTables.LIBRARIAN_GIFT, HeroGiftPolicy.giftTable(false, "librarian"));
        assertEquals(LootTables.WEAPONSMITH_GIFT, HeroGiftPolicy.giftTable(false, "weaponsmith"));
        assertEquals(LootTables.BABY_VILLAGER_GIFT, HeroGiftPolicy.giftTable(true, "librarian"), "babies use the baby table");
        assertEquals(LootTables.UNEMPLOYED_GIFT, HeroGiftPolicy.giftTable(false, "none"));
        assertEquals(LootTables.UNEMPLOYED_GIFT, HeroGiftPolicy.giftTable(false, "nitwit"));
    }
}
