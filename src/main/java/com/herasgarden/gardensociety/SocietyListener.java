package com.herasgarden.gardensociety;

import com.herasgarden.gardencore.api.ui.GardenMessages;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;

import java.sql.SQLException;

public final class SocietyListener implements Listener {
    private final SocietyService society;

    public SocietyListener(SocietyService society) {
        this.society = society;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof Villager villager)) return;
        try {
            SocietyService.Resident resident = society.resident(villager.getUniqueId()).orElse(null);
            if (resident == null) return;
            event.setCancelled(true);
            if (event.getPlayer().isSneaking()) {
                GardenMessages.send(event.getPlayer(), society.card(resident));
            } else {
                GardenMessages.send(event.getPlayer(), resident.name() + " is a Society resident. Shift-right-click to view their character card.");
            }
        } catch (SQLException ignored) {
        }
    }

    @EventHandler
    public void onDeath(EntityDeathEvent event) {
        if (event.getEntity() instanceof Villager villager) society.removeResident(villager.getUniqueId());
    }

    @EventHandler
    public void onBreed(EntityBreedEvent event) {
        if (!(event.getEntity() instanceof Villager child)) return;
        if (!(event.getMother() instanceof Villager mother)) return;
        if (!(event.getFather() instanceof Villager father)) return;
        society.registerBirth(child, mother, father);
    }
}
