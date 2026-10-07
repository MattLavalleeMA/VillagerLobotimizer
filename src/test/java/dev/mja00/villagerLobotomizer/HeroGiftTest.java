package dev.mja00.villagerLobotomizer;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Item;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.ItemStack;
import org.bukkit.loot.LootTables;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import dev.mja00.villagerLobotomizer.policy.HeroGiftPolicy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives {@link LobotomizeStorage#offerHeroGift} directly for the rules, and the hero's scan for the
 * wiring. The villager's own periodic check reaches the trapped-villager geometry, which MockBukkit
 * cannot evaluate, so no test here runs long enough for it to fire.
 */
class HeroGiftTest extends MockBukkitTestBase {

    private VillagerLobotomizer plugin;
    private WorldMock world;
    private NamespacedKey giftKey;
    private Villager villager;
    private PlayerMock hero;
    private final List<LootTables> rolledTables = new ArrayList<>();
    private boolean heroVisible = true;

    @BeforeEach
    void setUp() {
        plugin = MockBukkit.load(VillagerLobotomizer.class);
        world = server.addSimpleWorld("test");
        world.loadChunk(0, 0);
        giftKey = new NamespacedKey(plugin, LobotomizeStorage.HERO_GIFT_COOLDOWN_KEY);

        villager = world.spawn(new Location(world, 8, 64, 8), Villager.class);
        villager.setProfession(Villager.Profession.LIBRARIAN);
        // Lobotomized: an aware villager runs vanilla's own gift behavior.
        villager.setAware(false);

        hero = server.addPlayer();
        hero.teleport(new Location(world, 10, 64, 8));
        hero.addPotionEffect(new PotionEffect(PotionEffectType.HERO_OF_THE_VILLAGE, 6000, 0));
        plugin.getHeroTracker().refresh(hero);

        useStubLoot();
    }

    private void useStubLoot() {
        rolledTables.clear();
        plugin.getStorage().setHeroGiftLoot((v, table, random) -> {
            rolledTables.add(table);
            return List.of(new ItemStack(Material.BOOK));
        });
        plugin.getStorage().setHeroVisibility((v, player) -> heroVisible);
    }

    private void offer() {
        plugin.getStorage().offerHeroGift(villager, hero);
    }

    private Long storedTick() {
        return villager.getPersistentDataContainer().get(giftKey, PersistentDataType.LONG);
    }

    private List<Item> droppedItems() {
        return world.getEntitiesByClass(Item.class).stream().toList();
    }

    @Test
    void firstSightSchedulesTheFirstGiftWithoutGiving() {
        offer();

        assertEquals(600L, storedTick());
        assertTrue(rolledTables.isEmpty());
        assertTrue(droppedItems().isEmpty());
    }

    @Test
    void dueGiftIsDroppedAtTheHerosFeetAndRescheduled() {
        villager.getPersistentDataContainer().set(giftKey, PersistentDataType.LONG, 0L);

        offer();

        assertEquals(List.of(LootTables.LIBRARIAN_GIFT), rolledTables);
        List<Item> items = droppedItems();
        assertEquals(1, items.size());
        Item gift = items.get(0);
        assertEquals(Material.BOOK, gift.getItemStack().getType());
        assertTrue(gift.getLocation().distanceSquared(hero.getLocation()) < 1.0E-6, "dropped at the hero's feet");
        assertEquals(0.0, gift.getVelocity().lengthSquared(), 1.0E-9, "no random pop away from the hero");
        long next = storedTick();
        assertTrue(next >= 600L && next <= 6600L, "rescheduled within vanilla's cooldown");
    }

    @Test
    void cooldownRunsWhileTheHeroStaysInView() {
        villager.getPersistentDataContainer().set(giftKey, PersistentDataType.LONG, 40L);
        world.setGameTime(1000L);
        offer();
        assertEquals(40L, storedTick(), "the hero just came into view, so no time has accrued");

        world.setGameTime(1020L);
        offer();
        assertEquals(20L, storedTick());

        world.setGameTime(1040L);
        offer();

        assertEquals(List.of(LootTables.LIBRARIAN_GIFT), rolledTables);
    }

    @Test
    void returningHeroDoesNotGetAnInstantGift() {
        // Vanilla only counts the cooldown down while a hero is in view, so time away must not count:
        // only the one scan the last sighting is remembered for.
        villager.getPersistentDataContainer().set(giftKey, PersistentDataType.LONG, 150L);
        world.setGameTime(1000L);
        offer();

        heroVisible = false;
        world.setGameTime(1020L);
        offer();

        heroVisible = true;
        world.setGameTime(50_000L);
        offer();

        assertTrue(rolledTables.isEmpty());
        assertEquals(130L, storedTick());
    }

    @Test
    void aHeroSeenEveryOtherScanCountsAtHalfSpeed() {
        villager.getPersistentDataContainer().set(giftKey, PersistentDataType.LONG, 600L);
        for (long tick = 1000L; tick <= 1160L; tick += HeroGiftPolicy.SCAN_INTERVAL_TICKS) {
            heroVisible = (tick / HeroGiftPolicy.SCAN_INTERVAL_TICKS) % 2 == 0;
            world.setGameTime(tick);
            offer();
        }

        // Seen at 1000, 1040, 1080, 1120, 1160: four credited sightings of one scan each.
        assertEquals(600L - 4 * HeroGiftPolicy.SCAN_INTERVAL_TICKS, storedTick());
    }

    @Test
    void aHiddenSecondHeroDoesNotDiscardTheVisibleOnesTime() {
        PlayerMock hidden = server.addPlayer();
        hidden.teleport(new Location(world, 8, 64, 10));
        hidden.addPotionEffect(new PotionEffect(PotionEffectType.HERO_OF_THE_VILLAGE, 6000, 0));
        plugin.getStorage().setHeroVisibility((v, player) -> player != hidden);
        villager.getPersistentDataContainer().set(giftKey, PersistentDataType.LONG, 600L);

        for (long tick = 1000L; tick <= 1100L; tick += HeroGiftPolicy.SCAN_INTERVAL_TICKS) {
            world.setGameTime(tick);
            offer();
            world.setGameTime(tick + 10L);
            plugin.getStorage().offerHeroGift(villager, hidden);
        }

        assertEquals(600L - 100L, storedTick(), "every scan of the visible hero counts in full");
    }

    @Test
    void aHeroBeyondGiftRangeStillRunsTheCooldownDown() {
        hero.teleport(new Location(world, 18, 64, 8));
        villager.getPersistentDataContainer().set(giftKey, PersistentDataType.LONG, 20L);
        world.setGameTime(1000L);
        offer();
        world.setGameTime(1020L);
        offer();

        assertEquals(0L, storedTick(), "a hero 10 blocks away is in view, like vanilla's 16-block sensor");
        assertTrue(rolledTables.isEmpty(), "but too far for the gift");

        hero.teleport(new Location(world, 11, 64, 8));
        world.setGameTime(50_000L);
        offer();

        assertEquals(List.of(LootTables.LIBRARIAN_GIFT), rolledTables, "the ready gift waits for the hero");
    }

    @Test
    void awareVillagerIsLeftToVanilla() {
        villager.setAware(true);
        villager.getPersistentDataContainer().set(giftKey, PersistentDataType.LONG, 0L);

        offer();

        assertTrue(rolledTables.isEmpty());
    }

    @Test
    void noGiftWithoutTheHeroEffect() {
        hero.removePotionEffect(PotionEffectType.HERO_OF_THE_VILLAGE);
        plugin.getHeroTracker().refresh(hero);
        villager.getPersistentDataContainer().set(giftKey, PersistentDataType.LONG, 0L);

        offer();

        assertTrue(rolledTables.isEmpty());
        assertTrue(droppedItems().isEmpty());
    }

    @Test
    void staleTrackerEntryNeverGivesAGift() {
        // The tracker still lists the player, but the effect is gone: the live re-check must win.
        hero.removePotionEffect(PotionEffectType.HERO_OF_THE_VILLAGE);
        villager.getPersistentDataContainer().set(giftKey, PersistentDataType.LONG, 0L);

        offer();

        assertTrue(rolledTables.isEmpty());
    }

    @Test
    void noGiftToAHeroOutOfView() {
        hero.teleport(new Location(world, 25, 64, 8));
        offer();

        assertNull(storedTick(), "a hero 17 blocks away is never seen, so nothing is scheduled");
    }

    @Test
    void noGiftToAHeroTheVillagerCannotSee() {
        heroVisible = false;
        villager.getPersistentDataContainer().set(giftKey, PersistentDataType.LONG, 0L);

        offer();

        assertTrue(rolledTables.isEmpty());
    }

    @Test
    void noGiftToASpectator() {
        hero.setGameMode(GameMode.SPECTATOR);
        offer();

        assertNull(storedTick());
    }

    @Test
    void disabledInConfigDoesNothing() {
        plugin.getConfig().set("hero-gifts-from-lobotomized-villagers", false);
        // reloadPluginState re-reads config.yml from disk.
        plugin.saveConfig();
        plugin.reloadPluginState();
        useStubLoot();
        villager.getPersistentDataContainer().set(giftKey, PersistentDataType.LONG, 0L);

        offer();

        assertTrue(rolledTables.isEmpty());
        assertEquals("disabled in config", plugin.getStorage().describeHeroGift(villager));
    }

    @Test
    void wakingTheVillagerDropsItsGiftCooldown() {
        offer();
        assertNotNull(storedTick());

        plugin.getStorage().clearLobotomizedMarker(villager);

        assertFalse(villager.getPersistentDataContainer().has(giftKey, PersistentDataType.LONG));
    }

    @Test
    void trackerFollowsPotionEffectEvents() {
        PlayerMock other = server.addPlayer();
        other.addPotionEffect(new PotionEffect(PotionEffectType.HERO_OF_THE_VILLAGE, 600, 0));
        assertTrue(plugin.getHeroTracker().candidates().contains(other.getUniqueId()), "added on effect");

        other.removePotionEffect(PotionEffectType.HERO_OF_THE_VILLAGE);
        assertFalse(plugin.getHeroTracker().candidates().contains(other.getUniqueId()), "removed on effect loss");
    }

    @Test
    void heroScanGivesTrackedVillagersTheirGiftWithinOneScan() {
        Villager tracked = spawnTrackedLobotomized(new Location(world, 8, 64, 10));
        tracked.getPersistentDataContainer().set(giftKey, PersistentDataType.LONG, 0L);

        // Well short of the villager's own 150-tick check, which MockBukkit could not evaluate.
        server.getScheduler().performTicks(HeroGiftPolicy.SCAN_INTERVAL_TICKS + 1);

        assertEquals(List.of(LootTables.LIBRARIAN_GIFT), rolledTables);
        assertEquals(1, droppedItems().size());
    }

    @Test
    void heroScanIgnoresUntrackedVillagers() {
        villager.getPersistentDataContainer().set(giftKey, PersistentDataType.LONG, 0L);
        plugin.getStorage().removeVillager(villager);
        villager.setAware(false);

        server.getScheduler().performTicks(HeroGiftPolicy.SCAN_INTERVAL_TICKS + 1);

        assertTrue(rolledTables.isEmpty());
    }

    @Test
    void heroScanStopsWhenTheEffectEnds() {
        Villager tracked = spawnTrackedLobotomized(new Location(world, 8, 64, 10));
        hero.removePotionEffect(PotionEffectType.HERO_OF_THE_VILLAGE);
        tracked.getPersistentDataContainer().set(giftKey, PersistentDataType.LONG, 0L);

        server.getScheduler().performTicks(HeroGiftPolicy.SCAN_INTERVAL_TICKS * 2 + 1);

        assertTrue(rolledTables.isEmpty());
        assertFalse(plugin.getHeroTracker().candidates().contains(hero.getUniqueId()));
    }

    /** Tracked lobotomized via the persisted marker, so no trapped-villager geometry is evaluated. */
    private Villager spawnTrackedLobotomized(Location location) {
        NamespacedKey markerKey = new NamespacedKey(plugin, LobotomizeStorage.LOBOTOMIZED_KEY);
        Villager tracked = world.spawn(location, Villager.class, v -> {
            v.setProfession(Villager.Profession.LIBRARIAN);
            v.getPersistentDataContainer().set(markerKey, PersistentDataType.BYTE, (byte) 1);
        });
        if (!plugin.getStorage().getLobotomized().contains(tracked)) {
            plugin.getStorage().addVillager(tracked);
        }
        assertTrue(plugin.getStorage().getLobotomized().contains(tracked), "precondition: tracked lobotomized");
        assertFalse(tracked.isAware(), "precondition: AI off");
        return tracked;
    }
}
