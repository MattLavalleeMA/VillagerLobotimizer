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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives {@link LobotomizeStorage#giveHeroGiftIfDue} directly: the periodic check that calls it
 * reaches the trapped-villager geometry, which MockBukkit cannot evaluate.
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

    private Long storedTick() {
        return villager.getPersistentDataContainer().get(giftKey, PersistentDataType.LONG);
    }

    private List<Item> droppedItems() {
        return world.getEntitiesByClass(Item.class).stream().toList();
    }

    @Test
    void firstSightSchedulesTheFirstGiftWithoutGiving() {
        plugin.getStorage().giveHeroGiftIfDue(villager);

        assertEquals(600L, storedTick());
        assertTrue(rolledTables.isEmpty());
        assertTrue(droppedItems().isEmpty());
    }

    @Test
    void dueGiftIsDroppedAtTheHerosFeetAndRescheduled() {
        villager.getPersistentDataContainer().set(giftKey, PersistentDataType.LONG, 0L);

        plugin.getStorage().giveHeroGiftIfDue(villager);

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
        villager.getPersistentDataContainer().set(giftKey, PersistentDataType.LONG, 150L);
        world.setGameTime(1000L);
        plugin.getStorage().giveHeroGiftIfDue(villager);
        assertEquals(150L, storedTick(), "the hero just came into view, so no time has accrued");

        world.setGameTime(1150L);
        plugin.getStorage().giveHeroGiftIfDue(villager);

        assertEquals(List.of(LootTables.LIBRARIAN_GIFT), rolledTables);
    }

    @Test
    void returningHeroDoesNotGetAnInstantGift() {
        // Vanilla only counts the cooldown down while a hero is in view, so time away must not count.
        villager.getPersistentDataContainer().set(giftKey, PersistentDataType.LONG, 150L);
        world.setGameTime(1000L);
        plugin.getStorage().giveHeroGiftIfDue(villager);

        heroVisible = false;
        world.setGameTime(1100L);
        plugin.getStorage().giveHeroGiftIfDue(villager);

        heroVisible = true;
        world.setGameTime(50_000L);
        plugin.getStorage().giveHeroGiftIfDue(villager);

        assertTrue(rolledTables.isEmpty());
        assertEquals(150L, storedTick());
    }

    @Test
    void noGiftWithoutTheHeroEffect() {
        hero.removePotionEffect(PotionEffectType.HERO_OF_THE_VILLAGE);
        plugin.getHeroTracker().refresh(hero);
        villager.getPersistentDataContainer().set(giftKey, PersistentDataType.LONG, 0L);

        plugin.getStorage().giveHeroGiftIfDue(villager);

        assertTrue(rolledTables.isEmpty());
        assertTrue(droppedItems().isEmpty());
    }

    @Test
    void staleTrackerEntryNeverGivesAGift() {
        // The tracker still lists the player, but the effect is gone: the live re-check must win.
        hero.removePotionEffect(PotionEffectType.HERO_OF_THE_VILLAGE);
        villager.getPersistentDataContainer().set(giftKey, PersistentDataType.LONG, 0L);

        plugin.getStorage().giveHeroGiftIfDue(villager);

        assertTrue(rolledTables.isEmpty());
    }

    @Test
    void noGiftToAHeroOutOfRange() {
        hero.teleport(new Location(world, 14, 64, 8));
        plugin.getStorage().giveHeroGiftIfDue(villager);

        assertNull(storedTick(), "a hero 6 blocks away is never seen, so nothing is scheduled");
    }

    @Test
    void noGiftToAHeroTheVillagerCannotSee() {
        heroVisible = false;
        villager.getPersistentDataContainer().set(giftKey, PersistentDataType.LONG, 0L);

        plugin.getStorage().giveHeroGiftIfDue(villager);

        assertTrue(rolledTables.isEmpty());
    }

    @Test
    void noGiftToASpectator() {
        hero.setGameMode(GameMode.SPECTATOR);
        plugin.getStorage().giveHeroGiftIfDue(villager);

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

        plugin.getStorage().giveHeroGiftIfDue(villager);

        assertTrue(rolledTables.isEmpty());
        assertEquals("disabled in config", plugin.getStorage().describeHeroGift(villager));
    }

    @Test
    void wakingTheVillagerDropsItsGiftCooldown() {
        plugin.getStorage().giveHeroGiftIfDue(villager);
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
}
