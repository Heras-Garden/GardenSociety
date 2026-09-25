package com.herasgarden.gardensociety;

import com.herasgarden.gardencore.api.GardenPlatform;
import com.herasgarden.gardencore.api.claim.ClaimDirectoryService;
import com.herasgarden.gardencore.api.claim.ClaimTransferPolicy;
import com.herasgarden.gardencore.api.land.GardenTerritoryDirectory;
import com.herasgarden.gardencore.api.land.PropertyDirectory;
import com.herasgarden.gardencore.api.land.PropertyManagementService;
import com.herasgarden.gardencore.api.membership.TerritoryMembershipProvider;
import com.herasgarden.gardentrade.api.BusinessDirectory;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;

public final class GardenSociety extends JavaPlugin {
    @Override
    public void onEnable() {
        saveDefaultConfig();

        GardenPlatform platform = service(GardenPlatform.class);
        GardenTerritoryDirectory territories = service(GardenTerritoryDirectory.class);
        ClaimDirectoryService claims = service(ClaimDirectoryService.class);
        TerritoryMembershipProvider memberships = service(TerritoryMembershipProvider.class);
        PropertyDirectory properties = service(PropertyDirectory.class);
        PropertyManagementService propertyManagement = service(PropertyManagementService.class);
        ClaimTransferPolicy transferPolicy = service(ClaimTransferPolicy.class);
        BusinessDirectory businesses = service(BusinessDirectory.class);
        if (platform == null || territories == null || claims == null || memberships == null
                || properties == null || propertyManagement == null || businesses == null) {
            getLogger().severe("GardenSociety requires active GardenCore, GardenLands, GardenCivics, and GardenTrade services.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        try {
            SocietySchema.ensure(platform.storage());
        } catch (SQLException exception) {
            getLogger().severe("GardenSociety could not prepare storage: " + exception.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        SocietyService society = new SocietyService(
                this,
                platform,
                territories,
                claims,
                memberships,
                properties,
                propertyManagement,
                transferPolicy,
                businesses,
                Math.max(0L, getConfig().getLong("housing.max-purchase-price", 500L)),
                Math.max(0L, getConfig().getLong("economy.starting-obols", 500L))
        );
        SocietyCommand command = new SocietyCommand(society, territories);
        PluginCommand root = getCommand("society");
        if (root != null) {
            root.setExecutor(command);
            root.setTabCompleter(command);
        }
        getServer().getPluginManager().registerEvents(new SocietyListener(society), this);
        getServer().getScheduler().runTask(this, society::restoreVisibleResidents);

        long minutes = Math.max(1L, getConfig().getLong("immigration.interval-minutes", 10L));
        if (getConfig().getBoolean("immigration.enabled", true)) {
            long ticks = minutes * 60L * 20L;
            getServer().getScheduler().runTaskTimer(this, society::immigrationTick, ticks, ticks);
        }

        getLogger().info("GardenSociety enabled. Territory residents, housing-gated immigration, jobs, family births, and character cards are active.");
    }

    private <T> T service(Class<T> type) {
        RegisteredServiceProvider<T> registration = getServer().getServicesManager().getRegistration(type);
        return registration == null ? null : registration.getProvider();
    }
}
