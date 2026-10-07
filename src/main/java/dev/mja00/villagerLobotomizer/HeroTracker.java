package dev.mja00.villagerLobotomizer;

import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffectType;
import org.jetbrains.annotations.NotNull;

import dev.mja00.villagerLobotomizer.utils.SentryTaskWrapper;

/**
 * Tracks online players who may have Hero of the Village, so the per-villager gift check costs
 * nothing while there are none. Entries are only a pre-filter: callers re-check the effect on the
 * player's own thread, so a stale entry can never cause a gift.
 */
public class HeroTracker implements Listener {

    private final Plugin plugin;
    private final Set<UUID> heroes = Collections.newSetFromMap(new ConcurrentHashMap<>());

    public HeroTracker(@NotNull Plugin plugin) {
        this.plugin = plugin;
    }

    /** Picks up heroes already online, e.g. after a plugin reload. */
    public void scanOnlinePlayers() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            // A player's effects belong to its region thread on Folia.
            player.getScheduler().run(this.plugin, SentryTaskWrapper.wrap(task -> refresh(player)), null);
        }
    }

    public boolean isEmpty() {
        return this.heroes.isEmpty();
    }

    /** A live view; iterate it rather than copying. */
    public @NotNull Set<UUID> candidates() {
        return Collections.unmodifiableSet(this.heroes);
    }

    void refresh(@NotNull Player player) {
        if (player.isOnline() && player.hasPotionEffect(PotionEffectType.HERO_OF_THE_VILLAGE)) {
            this.heroes.add(player.getUniqueId());
        } else {
            this.heroes.remove(player.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPotionEffect(EntityPotionEffectEvent event) {
        if (!(event.getEntity() instanceof Player player)
                || event.getModifiedType() != PotionEffectType.HERO_OF_THE_VILLAGE) {
            return;
        }
        if (event.getNewEffect() != null) {
            this.heroes.add(player.getUniqueId());
        } else {
            this.heroes.remove(player.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        refresh(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        this.heroes.remove(event.getPlayer().getUniqueId());
    }
}
