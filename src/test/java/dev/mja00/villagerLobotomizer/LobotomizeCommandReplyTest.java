package dev.mja00.villagerLobotomizer;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.entity.Villager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MockBukkit runs every region on one thread, so only the same-region and console paths of
 * {@link LobotomizeCommand#reply} can be exercised here; the cross-region hop is not observable.
 */
class LobotomizeCommandReplyTest extends MockBukkitTestBase {

    private VillagerLobotomizer plugin;
    private WorldMock world;

    @BeforeEach
    void loadPlugin() {
        plugin = MockBukkit.load(VillagerLobotomizer.class);
        world = server.addSimpleWorld("test");
    }

    @Test
    void reportIncludesHeroGiftStatus() {
        Villager villager = world.spawn(new Location(world, 8, 64, 8), Villager.class);

        String report = PlainTextComponentSerializer.plainText()
                .serialize(new LobotomizeCommand(plugin).buildVillagerDetails(villager));

        assertTrue(report.contains("Hero gift: no hero seen yet"), report);
    }

    @Test
    void replyReachesAPlayerInTheCurrentRegion() {
        PlayerMock player = server.addPlayer();

        new LobotomizeCommand(plugin).reply(player, Component.text("hello"));
        server.getScheduler().performTicks(1);

        assertEquals("hello", player.nextMessage());
    }

    @Test
    void replyToTheConsoleIsDirect() {
        assertDoesNotThrow(() -> new LobotomizeCommand(plugin).reply(server.getConsoleSender(), Component.text("hello")));
    }
}
