package dev.mja00.villagerLobotomizer.policy;

import dev.mja00.villagerLobotomizer.policy.HeroGiftPolicy.Timing;
import org.bukkit.loot.LootTables;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HeroGiftPolicyTest {

    @Test
    void candidateNeedsEffectRangeAndNonSpectator() {
        assertTrue(HeroGiftPolicy.isCandidate(4.9 * 4.9, true, false));
        assertFalse(HeroGiftPolicy.isCandidate(5.0 * 5.0, true, false), "vanilla range is strictly under 5 blocks");
        assertFalse(HeroGiftPolicy.isCandidate(1.0, false, false), "no hero effect");
        assertFalse(HeroGiftPolicy.isCandidate(1.0, true, true), "spectators are never gifted");
    }

    @Test
    void timingFollowsTheRemainingCooldown() {
        assertEquals(Timing.SCHEDULE_FIRST, HeroGiftPolicy.timing(null));
        assertEquals(Timing.WAIT, HeroGiftPolicy.timing(1L));
        assertEquals(Timing.GIVE, HeroGiftPolicy.timing(0L));
        assertEquals(Timing.GIVE, HeroGiftPolicy.timing(-40L));
        assertEquals(600L, HeroGiftPolicy.firstGiftCooldown());
    }

    @Test
    void countDownCreditsOnlyTimeWithAHeroInView() {
        assertEquals(300L, HeroGiftPolicy.countDown(300L, null, 5000L, 150L), "hero just came into view");
        assertEquals(200L, HeroGiftPolicy.countDown(300L, 4900L, 5000L, 150L));
    }

    @Test
    void countDownNeverCreditsMoreThanOneCheckInterval() {
        assertEquals(150L, HeroGiftPolicy.countDown(300L, 0L, 1_000_000L, 150L), "stale last-seen tick");
        assertEquals(300L, HeroGiftPolicy.countDown(300L, 9_000L, 1_000L, 150L),
                "a last-seen tick from another world's clock credits nothing");
    }

    @Test
    void countDownCapsACorruptCooldown() {
        assertEquals(HeroGiftPolicy.MAX_COOLDOWN_TICKS, HeroGiftPolicy.countDown(Long.MAX_VALUE, null, 0L, 150L));
    }

    @Test
    void nextCooldownStaysWithinVanillaBounds() {
        Random random = new Random(42);
        for (int i = 0; i < 10_000; i++) {
            long cooldown = HeroGiftPolicy.nextGiftCooldown(random);
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
