package com.herasgarden.gardensociety;

import com.herasgarden.gardencore.api.GardenPlatform;
import com.herasgarden.gardencore.api.land.GardenTerritoryDirectory;
import com.herasgarden.gardencore.api.land.PropertyAddress;
import com.herasgarden.gardencore.api.land.PropertyDirectory;
import com.herasgarden.gardencore.api.land.PropertyMailbox;
import com.herasgarden.gardencore.api.land.TerritorySummary;
import com.herasgarden.gardencore.api.membership.TerritoryMembershipProvider;
import com.herasgarden.gardentrade.api.BusinessDirectory;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class SocietyService {
    private static final String[] NAMES = {
            "Aster","Basil","Clover","Dahlia","Fern","Flora","Hazel","Iris","Juniper","Laurel",
            "Marigold","Olive","Poppy","Rose","Sage","Violet","Willow","Yarrow","Zinnia"
    };

    private final JavaPlugin plugin;
    private final GardenPlatform platform;
    private final GardenTerritoryDirectory territories;
    private final TerritoryMembershipProvider memberships;
    private final PropertyDirectory properties;
    private final BusinessDirectory businesses;

    public SocietyService(
            JavaPlugin plugin,
            GardenPlatform platform,
            GardenTerritoryDirectory territories,
            TerritoryMembershipProvider memberships,
            PropertyDirectory properties,
            BusinessDirectory businesses
    ) {
        this.plugin = plugin;
        this.platform = platform;
        this.territories = territories;
        this.memberships = memberships;
        this.properties = properties;
        this.businesses = businesses;
    }

    public void setHousingEligibility(Player actor, UUID propertyId, boolean eligible) throws SQLException {
        PropertyAddress property = properties.find(propertyId)
                .orElseThrow(() -> new IllegalArgumentException("That property does not exist."));
        ClaimInfo claim = claim(property.claimId())
                .orElseThrow(() -> new IllegalArgumentException("The property claim is missing."));
        if (!isNpcHousingType(claim)) {
            throw new IllegalArgumentException("Only Homes and Apartment units can be NPC-eligible housing.");
        }
        UUID territoryId = territoryForClaim(property.claimId())
                .orElseThrow(() -> new IllegalArgumentException("That property is not inside a Territory."));
        if (!territories.canManage(actor, territoryId) && !actor.hasPermission("gardensociety.admin")) {
            throw new IllegalArgumentException("You must manage that Territory to change Society housing.");
        }
        long now = System.currentTimeMillis();
        try (Connection c = platform.storage().connection()) {
            int changed;
            try (PreparedStatement u = c.prepareStatement(
                    "UPDATE gs_housing SET npc_eligible = ?, updated_by = ?, updated_at = ? WHERE property_uuid = ?")) {
                u.setInt(1, eligible ? 1 : 0);
                u.setString(2, actor.getUniqueId().toString());
                u.setLong(3, now);
                u.setString(4, propertyId.toString());
                changed = u.executeUpdate();
            }
            if (changed == 0) {
                try (PreparedStatement i = c.prepareStatement(
                        "INSERT INTO gs_housing (property_uuid, npc_eligible, updated_by, updated_at) VALUES (?, ?, ?, ?)")) {
                    i.setString(1, propertyId.toString());
                    i.setInt(2, eligible ? 1 : 0);
                    i.setString(3, actor.getUniqueId().toString());
                    i.setLong(4, now);
                    i.executeUpdate();
                }
            }
        }
    }

    public Resident adopt(Player actor, Villager villager, String territoryName) throws SQLException {
        if (resident(villager.getUniqueId()).isPresent()) {
            throw new IllegalArgumentException("That villager is already a Society resident.");
        }
        TerritorySummary territory = territories.findByName(territoryName)
                .orElseThrow(() -> new IllegalArgumentException("That Territory does not exist."));
        if (!territories.canManage(actor, territory.claimId()) && !actor.hasPermission("gardensociety.admin")) {
            throw new IllegalArgumentException("You must manage that Territory to adopt a resident.");
        }
        List<Housing> homes = vacantHomes(territory.claimId());
        if (homes.isEmpty()) {
            throw new IllegalArgumentException("That Territory has no vacant NPC-eligible Home or Apartment.");
        }
        BusinessDirectory.Vacancy vacancy = vacancies(territory.claimId()).stream().findFirst().orElse(null);
        return makeResident(villager, territory, homes.getFirst(), vacancy, null, null, false);
    }

    public ImmigrationResult tryImmigrate(TerritorySummary territory) throws SQLException {
        List<Housing> homes = vacantHomes(territory.claimId());
        List<BusinessDirectory.Vacancy> vacancies = vacancies(territory.claimId());
        if (homes.size() < 3) {
            return new ImmigrationResult(false, "Needs at least 3 vacant NPC-eligible Homes/Apartments; currently " + homes.size() + ".");
        }
        if (vacancies.size() < 2) {
            return new ImmigrationResult(false, "Needs at least 2 available Territory job positions; currently " + vacancies.size() + ".");
        }

        Housing home = homes.getFirst();
        BusinessDirectory.Vacancy vacancy = vacancies.getFirst();
        PropertyMailbox mailbox = home.mailbox();
        World world = Bukkit.getWorld(mailbox.worldId());
        if (world == null) return new ImmigrationResult(false, "The selected home's world is not loaded.");

        Location spawn = new Location(world, mailbox.x() + 0.5, mailbox.y() + 1.0, mailbox.z() + 0.5);
        Villager villager = world.spawn(spawn, Villager.class);
        try {
            Resident resident = makeResident(villager, territory, home, vacancy, null, null, true);
            return new ImmigrationResult(true, resident.name() + " moved into " + territory.name()
                    + " at " + home.property().display() + " and took the " + vacancy.positionTitle() + " position.");
        } catch (Exception exception) {
            villager.remove();
            if (exception instanceof SQLException sql) throw sql;
            if (exception instanceof RuntimeException runtime) throw runtime;
            throw new SQLException("Society immigration failed.", exception);
        }
    }

    public void immigrationTick() {
        for (TerritorySummary territory : territories.list()) {
            try {
                ImmigrationResult result = tryImmigrate(territory);
                if (result.moved()) plugin.getLogger().info(result.message());
            } catch (Exception exception) {
                plugin.getLogger().warning("Society immigration check failed for " + territory.name() + ": " + exception.getMessage());
            }
        }
    }

    public void registerBirth(Villager child, Villager parent1, Villager parent2) {
        try {
            Resident a = resident(parent1.getUniqueId()).orElse(null);
            Resident b = resident(parent2.getUniqueId()).orElse(null);
            if (a == null || b == null || !a.territoryClaimId().equals(b.territoryClaimId())) return;
            if (resident(child.getUniqueId()).isPresent()) return;
            TerritorySummary territory = territories.findByClaim(a.territoryClaimId()).orElse(null);
            if (territory == null) return;
            makeResident(child, territory, null, null, a.id(), b.id(), false);
        } catch (Exception exception) {
            plugin.getLogger().warning("Could not register Society birth: " + exception.getMessage());
        }
    }

    public void removeResident(UUID villagerId) {
        try {
            Resident resident = resident(villagerId).orElse(null);
            if (resident == null) return;
            if (resident.positionId() != null) businesses.vacate(resident.positionId(), villagerId);
            memberships.clearMembership(villagerId);
            try (Connection c = platform.storage().connection();
                 PreparedStatement s = c.prepareStatement("DELETE FROM gs_residents WHERE villager_uuid = ?")) {
                s.setString(1, villagerId.toString());
                s.executeUpdate();
            }
        } catch (SQLException exception) {
            plugin.getLogger().warning("Could not clean up Society resident " + villagerId + ": " + exception.getMessage());
        }
    }

    public Optional<Resident> resident(UUID villagerId) throws SQLException {
        try (Connection c = platform.storage().connection();
             PreparedStatement s = c.prepareStatement("SELECT * FROM gs_residents WHERE villager_uuid = ?")) {
            s.setString(1, villagerId.toString());
            try (ResultSet r = s.executeQuery()) {
                return r.next() ? Optional.of(readResident(r)) : Optional.empty();
            }
        }
    }

    public int population(String territoryName) {
        TerritorySummary territory = territories.findByName(territoryName)
                .orElseThrow(() -> new IllegalArgumentException("That Territory does not exist."));
        return memberships.members(territory.claimId()).size();
    }

    public String card(Resident resident) throws SQLException {
        String territory = territories.findByClaim(resident.territoryClaimId()).map(TerritorySummary::name).orElse("Unknown");
        String home = resident.homePropertyId() == null ? "Family household"
                : properties.find(resident.homePropertyId()).map(PropertyAddress::display).orElse("Unknown home");
        String occupation = resident.occupationLabel() == null ? "Unemployed" : resident.occupationLabel();
        return resident.name() + " | Territory: " + territory + " | Home: " + home + " | Occupation: " + occupation;
    }

    public void restoreVisibleResidents() {
        for (World world : Bukkit.getWorlds()) {
            for (Villager villager : world.getEntitiesByClass(Villager.class)) {
                try {
                    resident(villager.getUniqueId()).ifPresent(r -> applyIdentity(villager, r.name()));
                } catch (SQLException ignored) {
                }
            }
        }
    }

    private Resident makeResident(
            Villager villager,
            TerritorySummary territory,
            Housing home,
            BusinessDirectory.Vacancy vacancy,
            UUID parent1,
            UUID parent2,
            boolean immigration
    ) throws SQLException {
        String name = existingOrGeneratedName(villager);
        UUID positionId = vacancy == null ? null : vacancy.positionId();
        String occupation = vacancy == null ? null : vacancy.positionTitle() + " @ " + vacancy.businessName();

        memberships.setMembership(villager.getUniqueId(), territory.claimId());
        boolean hired = false;
        try {
            if (vacancy != null) {
                hired = businesses.hire(vacancy.positionId(), villager.getUniqueId(), name);
                if (!hired) throw new IllegalArgumentException("The selected job was filled before the resident could take it.");
            }
            long now = System.currentTimeMillis();
            try (Connection c = platform.storage().connection();
                 PreparedStatement s = c.prepareStatement(
                         "INSERT INTO gs_residents "
                                 + "(villager_uuid, resident_name, territory_claim_uuid, home_property_uuid, position_uuid, occupation_label, "
                                 + "parent1_uuid, parent2_uuid, born_at, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                s.setString(1, villager.getUniqueId().toString());
                s.setString(2, name);
                s.setString(3, territory.claimId().toString());
                s.setString(4, home == null ? null : home.property().propertyId().toString());
                s.setString(5, positionId == null ? null : positionId.toString());
                s.setString(6, occupation);
                s.setString(7, parent1 == null ? null : parent1.toString());
                s.setString(8, parent2 == null ? null : parent2.toString());
                if (parent1 == null && parent2 == null) s.setObject(9, null); else s.setLong(9, now);
                s.setLong(10, now);
                s.executeUpdate();
            }
        } catch (Exception exception) {
            if (hired && positionId != null) {
                try { businesses.vacate(positionId, villager.getUniqueId()); } catch (SQLException ignored) {}
            }
            try { memberships.clearMembership(villager.getUniqueId()); } catch (SQLException ignored) {}
            if (exception instanceof SQLException sql) throw sql;
            if (exception instanceof RuntimeException runtime) throw runtime;
            throw new SQLException("Society resident creation failed.", exception);
        }

        applyIdentity(villager, name);
        Resident resident = resident(villager.getUniqueId()).orElseThrow();
        if (immigration && home != null && positionId != null) logImmigration(territory.claimId(), resident.id(), home.property().propertyId(), positionId);
        return resident;
    }

    private void logImmigration(UUID territoryId, UUID villagerId, UUID propertyId, UUID positionId) {
        try (Connection c = platform.storage().connection();
             PreparedStatement s = c.prepareStatement(
                     "INSERT INTO gs_immigration_log (immigration_uuid, territory_claim_uuid, villager_uuid, home_property_uuid, position_uuid, created_at) VALUES (?, ?, ?, ?, ?, ?)")) {
            s.setString(1, UUID.randomUUID().toString());
            s.setString(2, territoryId.toString());
            s.setString(3, villagerId.toString());
            s.setString(4, propertyId.toString());
            s.setString(5, positionId.toString());
            s.setLong(6, System.currentTimeMillis());
            s.executeUpdate();
        } catch (SQLException exception) {
            plugin.getLogger().warning("Resident immigrated but immigration audit logging failed: " + exception.getMessage());
        }
    }

    private List<Housing> vacantHomes(UUID territoryId) throws SQLException {
        List<Housing> homes = new ArrayList<>();
        try (Connection c = platform.storage().connection();
             PreparedStatement s = c.prepareStatement("SELECT property_uuid FROM gs_housing WHERE npc_eligible = 1 ORDER BY updated_at ASC");
             ResultSet r = s.executeQuery()) {
            while (r.next()) {
                UUID propertyId = UUID.fromString(r.getString("property_uuid"));
                if (occupied(propertyId)) continue;
                PropertyAddress property = properties.find(propertyId).orElse(null);
                if (property == null) continue;
                if (!territoryForClaim(property.claimId()).filter(territoryId::equals).isPresent()) continue;
                ClaimInfo info = claim(property.claimId()).orElse(null);
                if (info == null || !isNpcHousingType(info)) continue;
                PropertyMailbox mailbox = properties.mailbox(propertyId).orElse(null);
                if (mailbox == null) continue;
                homes.add(new Housing(property, mailbox));
            }
        }
        return List.copyOf(homes);
    }

    private List<BusinessDirectory.Vacancy> vacancies(UUID territoryId) throws SQLException {
        return businesses.vacancies().stream()
                .filter(v -> territoryId.equals(v.territoryClaimId()))
                .toList();
    }

    private boolean occupied(UUID propertyId) throws SQLException {
        try (Connection c = platform.storage().connection();
             PreparedStatement s = c.prepareStatement("SELECT 1 FROM gs_residents WHERE home_property_uuid = ? LIMIT 1")) {
            s.setString(1, propertyId.toString());
            try (ResultSet r = s.executeQuery()) { return r.next(); }
        }
    }

    private Optional<UUID> territoryForClaim(UUID claimId) throws SQLException {
        UUID current = claimId;
        for (int i = 0; i < 32 && current != null; i++) {
            ClaimInfo info = claim(current).orElse(null);
            if (info == null) return Optional.empty();
            if ("TERRITORY".equalsIgnoreCase(info.type())) return Optional.of(current);
            current = info.parentId();
        }
        return Optional.empty();
    }

    private Optional<ClaimInfo> claim(UUID claimId) throws SQLException {
        try (Connection c = platform.storage().connection();
             PreparedStatement s = c.prepareStatement("SELECT claim_type, claim_tag, parent_uuid FROM gc_claims WHERE claim_uuid = ?")) {
            s.setString(1, claimId.toString());
            try (ResultSet r = s.executeQuery()) {
                if (!r.next()) return Optional.empty();
                String parent = r.getString("parent_uuid");
                return Optional.of(new ClaimInfo(r.getString("claim_type"), r.getString("claim_tag"),
                        parent == null ? null : UUID.fromString(parent)));
            }
        }
    }

    private boolean isNpcHousingType(ClaimInfo claim) {
        return "HOME".equalsIgnoreCase(claim.type())
                || ("UNIT".equalsIgnoreCase(claim.type()) && "APARTMENT".equalsIgnoreCase(claim.tag()));
    }

    private String existingOrGeneratedName(Villager villager) {
        String current = villager.getCustomName();
        if (current != null && !current.isBlank()) return current.trim();
        return NAMES[Math.floorMod(villager.getUniqueId().hashCode(), NAMES.length)];
    }

    private void applyIdentity(Villager villager, String name) {
        villager.customName(Component.text(name));
        villager.setCustomNameVisible(true);
    }

    private Resident readResident(ResultSet r) throws SQLException {
        String home = r.getString("home_property_uuid");
        String pos = r.getString("position_uuid");
        String p1 = r.getString("parent1_uuid");
        String p2 = r.getString("parent2_uuid");
        Object born = r.getObject("born_at");
        return new Resident(
                UUID.fromString(r.getString("villager_uuid")),
                r.getString("resident_name"),
                UUID.fromString(r.getString("territory_claim_uuid")),
                home == null ? null : UUID.fromString(home),
                pos == null ? null : UUID.fromString(pos),
                r.getString("occupation_label"),
                p1 == null ? null : UUID.fromString(p1),
                p2 == null ? null : UUID.fromString(p2),
                born == null ? null : r.getLong("born_at"),
                r.getLong("created_at")
        );
    }

    private record ClaimInfo(String type, String tag, UUID parentId) {}
    private record Housing(PropertyAddress property, PropertyMailbox mailbox) {}

    public record Resident(UUID id, String name, UUID territoryClaimId, UUID homePropertyId, UUID positionId,
                           String occupationLabel, UUID parent1Id, UUID parent2Id, Long bornAt, long createdAt) {}
    public record ImmigrationResult(boolean moved, String message) {}
}
