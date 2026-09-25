package com.herasgarden.gardensociety;

import com.herasgarden.gardencore.api.land.GardenTerritoryDirectory;
import com.herasgarden.gardencore.api.land.TerritorySummary;
import com.herasgarden.gardencore.api.ui.GardenMessages;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class SocietyCommand implements CommandExecutor, TabCompleter {
    private final SocietyService society;
    private final GardenTerritoryDirectory territories;

    public SocietyCommand(SocietyService society, GardenTerritoryDirectory territories) {
        this.society = society;
        this.territories = territories;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            GardenMessages.send(sender, "Society commands must be used in-game.");
            return true;
        }
        try {
            if (args.length == 0) { help(player); return true; }
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "housing" -> housing(player, args);
                case "adopt" -> adopt(player, args);
                case "info" -> info(player);
                case "population" -> population(player, args);
                case "immigrate" -> immigrate(player, args);
                default -> help(player);
            }
        } catch (IllegalArgumentException exception) {
            GardenMessages.send(player, exception.getMessage());
        } catch (SQLException exception) {
            GardenMessages.send(player, "Society data could not update right now.");
        }
        return true;
    }

    private void housing(Player player, String[] args) throws SQLException {
        if (args.length < 3) throw new IllegalArgumentException("Use /society housing <property-uuid> <on|off>.");
        boolean enabled = switch (args[2].toLowerCase(Locale.ROOT)) {
            case "on", "true", "yes" -> true;
            case "off", "false", "no" -> false;
            default -> throw new IllegalArgumentException("Use on or off.");
        };
        society.setHousingEligibility(player, UUID.fromString(args[1]), enabled);
        GardenMessages.send(player, "NPC housing eligibility " + (enabled ? "enabled." : "disabled."));
    }

    private void adopt(Player player, String[] args) throws SQLException {
        if (args.length < 2) throw new IllegalArgumentException("Use /society adopt <territory> while looking at a villager.");
        Entity target = player.getTargetEntity(6);
        if (!(target instanceof Villager villager)) throw new IllegalArgumentException("Look directly at a villager.");
        var resident = society.adopt(player, villager, join(args, 1));
        GardenMessages.send(player, resident.name() + " is now a Society resident.");
    }

    private void info(Player player) throws SQLException {
        Entity target = player.getTargetEntity(6);
        if (!(target instanceof Villager villager)) throw new IllegalArgumentException("Look directly at a villager.");
        SocietyService.Resident resident = society.resident(villager.getUniqueId())
                .orElseThrow(() -> new IllegalArgumentException("That is a vanilla villager, not a Society resident."));
        GardenMessages.send(player, society.card(resident));
    }

    private void population(Player player, String[] args) {
        if (args.length < 2) throw new IllegalArgumentException("Use /society population <territory>.");
        String name = join(args, 1);
        GardenMessages.send(player, name + " population: " + society.population(name) + ".");
    }

    private void immigrate(Player player, String[] args) throws SQLException {
        if (args.length < 2) throw new IllegalArgumentException("Use /society immigrate <territory>.");
        TerritorySummary territory = territories.findByName(join(args, 1))
                .orElseThrow(() -> new IllegalArgumentException("That Territory does not exist."));
        if (!territories.canManage(player, territory.claimId()) && !player.hasPermission("gardensociety.admin")) {
            throw new IllegalArgumentException("You must manage that Territory to run an immigration check.");
        }
        SocietyService.ImmigrationResult result = society.tryImmigrate(territory);
        GardenMessages.send(player, result.message());
    }

    private String join(String[] args, int start) {
        return String.join(" ", Arrays.copyOfRange(args, start, args.length)).trim();
    }

    private void help(Player player) {
        GardenMessages.send(player, "/society housing <property-uuid> <on|off>, adopt <territory>, info, population <territory>, immigrate <territory>");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String p = args[0].toLowerCase(Locale.ROOT);
            return List.of("housing", "adopt", "info", "population", "immigrate").stream()
                    .filter(v -> v.startsWith(p)).toList();
        }
        if (args.length >= 2 && (args[0].equalsIgnoreCase("adopt")
                || args[0].equalsIgnoreCase("population") || args[0].equalsIgnoreCase("immigrate"))) {
            String p = join(args, 1).toLowerCase(Locale.ROOT);
            return territories.list().stream().map(TerritorySummary::name)
                    .filter(v -> v.toLowerCase(Locale.ROOT).startsWith(p)).toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("housing")) return List.of("on", "off");
        return List.of();
    }
}
