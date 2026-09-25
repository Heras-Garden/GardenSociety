package com.herasgarden.gardensociety;

import com.herasgarden.gardencore.api.GardenPlatform;
import com.herasgarden.gardencore.api.claim.ClaimDirectoryService;
import com.herasgarden.gardencore.api.claim.ClaimSummary;
import com.herasgarden.gardencore.api.claim.ClaimTransferPolicy;
import com.herasgarden.gardencore.api.land.GardenTerritoryDirectory;
import com.herasgarden.gardencore.api.land.PropertyAddress;
import com.herasgarden.gardencore.api.land.PropertyDirectory;
import com.herasgarden.gardencore.api.land.PropertyMailbox;
import com.herasgarden.gardencore.api.land.PropertyManagementService;
import com.herasgarden.gardencore.api.land.PropertyPurchaseResult;
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
    private final ClaimDirectoryService claims;
    private final TerritoryMembershipProvider memberships;
    private final PropertyDirectory properties;
    private final PropertyManagementService propertyManagement;
    private final ClaimTransferPolicy transferPolicy;
    private final BusinessDirectory businesses;
    private final long maxHomePrice;
    private final long startingObols;

    public SocietyService(
            JavaPlugin plugin,
            GardenPlatform platform,
            GardenTerritoryDirectory territories,
            ClaimDirectoryService claims,
            TerritoryMembershipProvider memberships,
            PropertyDirectory properties,
            PropertyManagementService propertyManagement,
            ClaimTransferPolicy transferPolicy,
            BusinessDirectory businesses,
            long maxHomePrice,
            long startingObols
    ) {
        this.plugin = plugin;
        this.platform = platform;
        this.territories = territories;
        this.claims = claims;
        this.memberships = memberships;
        this.properties = properties;
        this.propertyManagement = propertyManagement;
        this.transferPolicy = transferPolicy;
        this.businesses = businesses;
        this.maxHomePrice = maxHomePrice;
        this.startingObols = startingObols;
    }

    public void setHousingEligibility(Player actor, UUID propertyId, boolean eligible) throws SQLException {
        PropertyAddress property = properties.find(propertyId)
                .orElseThrow(() -> new IllegalArgumentException("That property does not exist."));
        ClaimSummary claim = claims.find(property.claimId())
                .orElseThrow(() -> new IllegalArgumentException("The property claim is missing."));
        if (!isNpcHousingType(claim)) {
            throw new IllegalArgumentException("Only Homes and Apartment units can be NPC-eligible housing.");
        }
        UUID territoryId = claims.territoryAncestor(property.claimId())
                .orElseThrow(() -> new IllegalArgumentException("That property is not inside a Territory."));
        if (!territories.canManage(actor, territoryId) && !actor.hasPermission("gardensociety.admin")) {
            throw new IllegalArgumentException("You must manage that Territory to change Society housing.");
        }
        long now = System.currentTimeMillis();
        try (Connection c = platform.storage().connection()) {
            int changed;
            try (PreparedStatement u = c.prepareStatement(
                    "UPDATE gs_housing SET npc_eligible = ?, audience = 'SOCIETY', updated_by = ?, updated_at = ? WHERE property_uuid = ?")) {
                u.setInt(1, eligible ? 1 : 0);
                u.setString(2, actor.getUniqueId().toString());
                u.setLong(3, now);
                u.setString(4, propertyId.toString());
                changed = u.executeUpdate();
            }
            if (changed == 0) {
                try (PreparedStatement i = c.prepareStatement(
                        "INSERT INTO gs_housing (property_uuid, npc_eligible, audience, updated_by, updated_at) VALUES (?, ?, 'SOCIETY', ?, ?)")) {
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
            UUID householdId = resident.householdId();
            try (Connection c = platform.storage().connection();
                 PreparedStatement s = c.prepareStatement("DELETE FROM gs_residents WHERE villager_uuid = ?")) {
                s.setString(1, villagerId.toString());
                s.executeUpdate();
            }
            if (householdId != null && !householdHasMembers(householdId)) {
                try (Connection c = platform.storage().connection();
                     PreparedStatement s = c.prepareStatement(
                             "DELETE FROM gs_households WHERE household_uuid = ?")) {
                    s.setString(1, householdId.toString());
                    s.executeUpdate();
                }
            }
        } catch (SQLException exception) {
            plugin.getLogger().warning("Could not clean up Society resident " + villagerId + ": " + exception.getMessage());
        }
    }

    private boolean householdHasMembers(UUID householdId) throws SQLException {
        try (Connection c = platform.storage().connection();
             PreparedStatement s = c.prepareStatement(
                     "SELECT 1 FROM gs_residents WHERE household_uuid = ? LIMIT 1")) {
            s.setString(1, householdId.toString());
            try (ResultSet r = s.executeQuery()) { return r.next(); }
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
        try (Connection c = platform.storage().connection();
             PreparedStatement s = c.prepareStatement(
                     "SELECT COUNT(*) total FROM gs_residents WHERE territory_claim_uuid = ?")) {
            s.setString(1, territory.claimId().toString());
            try (ResultSet r = s.executeQuery()) {
                return r.next() ? r.getInt("total") : 0;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Society population could not be read.", exception);
        }
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

        boolean newHousehold = parent1 == null && parent2 == null;
        Household household;
        if (!newHousehold) {
            household = householdForParent(parent1, parent2)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "A Society child needs an existing parental household."));
            home = housingForHousehold(household).orElse(null);
        } else {
            if (home == null) {
                throw new IllegalArgumentException("A new Society resident needs an eligible home.");
            }
            household = purchaseHome(territory, home, name);
        }

        memberships.setMembership(villager.getUniqueId(), territory.claimId());
        boolean hired = false;
        try {
            if (vacancy != null) {
                hired = businesses.hire(vacancy.positionId(), villager.getUniqueId(), name);
                if (!hired) throw new IllegalArgumentException(
                        "The selected job was filled before the resident could take it.");
            }
            long now = System.currentTimeMillis();
            try (Connection c = platform.storage().connection();
                 PreparedStatement s = c.prepareStatement(
                         "INSERT INTO gs_residents "
                                 + "(villager_uuid, resident_name, territory_claim_uuid, household_uuid, "
                                 + "home_property_uuid, position_uuid, occupation_label, "
                                 + "parent1_uuid, parent2_uuid, born_at, created_at) "
                                 + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                s.setString(1, villager.getUniqueId().toString());
                s.setString(2, name);
                s.setString(3, territory.claimId().toString());
                s.setString(4, household.id().toString());
                s.setString(5, household.homePropertyId().toString());
                s.setString(6, positionId == null ? null : positionId.toString());
                s.setString(7, occupation);
                s.setString(8, parent1 == null ? null : parent1.toString());
                s.setString(9, parent2 == null ? null : parent2.toString());
                if (parent1 == null && parent2 == null) s.setObject(10, null); else s.setLong(10, now);
                s.setLong(11, now);
                s.executeUpdate();
            }
        } catch (Exception exception) {
            if (hired && positionId != null) {
                try { businesses.vacate(positionId, villager.getUniqueId()); } catch (SQLException ignored) {}
            }
            try { memberships.clearMembership(villager.getUniqueId()); } catch (SQLException ignored) {}
            if (newHousehold) {
                try { markHouseholdReview(household.id()); } catch (SQLException ignored) {}
            }
            if (exception instanceof SQLException sql) throw sql;
            if (exception instanceof RuntimeException runtime) throw runtime;
            throw new SQLException("Society resident creation failed.", exception);
        }

        applyIdentity(villager, name);
        Resident resident = resident(villager.getUniqueId()).orElseThrow();
        if (immigration && home != null && positionId != null) {
            logImmigration(
                    territory.claimId(),
                    resident.id(),
                    household.homePropertyId(),
                    positionId
            );
        }
        return resident;
    }

    private Household purchaseHome(
            TerritorySummary territory,
            Housing home,
            String residentName
    ) throws SQLException {
        UUID householdId = UUID.randomUUID();
        Household household = new Household(
                householdId,
                territory.claimId(),
                home.property().propertyId(),
                householdId,
                System.currentTimeMillis()
        );

        // Reserve the home before money or ownership changes. A later failure
        // remains visible as REVIEW rather than making the home look vacant.
        try (Connection c = platform.storage().connection();
             PreparedStatement s = c.prepareStatement(
                     "INSERT INTO gs_households "
                             + "(household_uuid, territory_claim_uuid, home_property_uuid, "
                             + "owner_account_uuid, state, created_at) VALUES (?, ?, ?, ?, 'PENDING', ?)")) {
            s.setString(1, household.id().toString());
            s.setString(2, household.territoryId().toString());
            s.setString(3, household.homePropertyId().toString());
            s.setString(4, household.ownerAccountId().toString());
            s.setLong(5, household.createdAt());
            s.executeUpdate();
        }

        try {
            long current = platform.currency().balance(householdId);
            long target = Math.max(startingObols, home.property().price());
            if (current < target && !platform.currency().deposit(householdId, target - current)) {
                throw new IllegalArgumentException("Society household starting funds could not be created.");
            }

            PropertyPurchaseResult purchase = propertyManagement.purchaseAccount(
                    householdId,
                    residentName + " Household",
                    "SOCIETY_CITIZEN",
                    home.property().propertyId()
            );
            if (!purchase.success()) {
                throw new IllegalArgumentException("Society home purchase failed: " + purchase.message());
            }

            try (Connection c = platform.storage().connection();
                 PreparedStatement s = c.prepareStatement(
                         "UPDATE gs_households SET state = 'ACTIVE' "
                                 + "WHERE household_uuid = ? AND state = 'PENDING'")) {
                s.setString(1, householdId.toString());
                if (s.executeUpdate() != 1) {
                    markHouseholdReview(householdId);
                    throw new SQLException(
                            "The home was purchased but household activation needs administrator review.");
                }
            }
            return household;
        } catch (IllegalArgumentException exception) {
            deletePendingHousehold(householdId);
            throw exception;
        } catch (SQLException exception) {
            try { markHouseholdReview(householdId); } catch (SQLException ignored) {}
            throw exception;
        }
    }

    private void deletePendingHousehold(UUID householdId) throws SQLException {
        try (Connection c = platform.storage().connection();
             PreparedStatement s = c.prepareStatement(
                     "DELETE FROM gs_households WHERE household_uuid = ? AND state = 'PENDING'")) {
            s.setString(1, householdId.toString());
            s.executeUpdate();
        }
    }

    private void markHouseholdReview(UUID householdId) throws SQLException {
        try (Connection c = platform.storage().connection();
             PreparedStatement s = c.prepareStatement(
                     "UPDATE gs_households SET state = 'REVIEW' WHERE household_uuid = ?")) {
            s.setString(1, householdId.toString());
            s.executeUpdate();
        }
    }

    private Optional<Household> householdForParent(UUID parent1, UUID parent2) throws SQLException {
        UUID[] parents = {parent1, parent2};
        for (UUID parent : parents) {
            if (parent == null) continue;
            Resident resident = resident(parent).orElse(null);
            if (resident == null || resident.householdId() == null) continue;
            Optional<Household> household = household(resident.householdId());
            if (household.isPresent()) return household;
        }
        return Optional.empty();
    }

    private Optional<Household> household(UUID householdId) throws SQLException {
        try (Connection c = platform.storage().connection();
             PreparedStatement s = c.prepareStatement(
                     "SELECT * FROM gs_households WHERE household_uuid = ?")) {
            s.setString(1, householdId.toString());
            try (ResultSet r = s.executeQuery()) {
                if (!r.next()) return Optional.empty();
                return Optional.of(new Household(
                        UUID.fromString(r.getString("household_uuid")),
                        UUID.fromString(r.getString("territory_claim_uuid")),
                        UUID.fromString(r.getString("home_property_uuid")),
                        UUID.fromString(r.getString("owner_account_uuid")),
                        r.getLong("created_at")
                ));
            }
        }
    }

    private Optional<Housing> housingForHousehold(Household household) throws SQLException {
        PropertyAddress property = properties.find(household.homePropertyId()).orElse(null);
        if (property == null) return Optional.empty();
        PropertyMailbox mailbox = properties.mailbox(property.propertyId()).orElse(null);
        return mailbox == null ? Optional.empty() : Optional.of(new Housing(property, mailbox));
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
        List<UUID> eligible = new ArrayList<>();
        try (Connection c = platform.storage().connection();
             PreparedStatement s = c.prepareStatement(
                     "SELECT property_uuid FROM gs_housing "
                             + "WHERE npc_eligible = 1 AND audience = 'SOCIETY' ORDER BY updated_at ASC");
             ResultSet r = s.executeQuery()) {
            while (r.next()) eligible.add(UUID.fromString(r.getString("property_uuid")));
        }

        List<Housing> homes = new ArrayList<>();
        for (UUID propertyId : eligible) {
            if (occupied(propertyId)) continue;
            PropertyAddress property = properties.find(propertyId).orElse(null);
            if (property == null || !property.forSale() || property.price() <= 0
                    || property.price() > maxHomePrice) continue;
            if (!claims.territoryAncestor(property.claimId()).filter(territoryId::equals).isPresent()) continue;
            ClaimSummary info = claims.find(property.claimId()).orElse(null);
            if (info == null || !isNpcHousingType(info)) continue;
            if (transferPolicy != null) {
                Optional<String> blocked = transferPolicy.blockReason(property.claimId());
                if (blocked != null && blocked.isPresent()) continue;
            }
            PropertyMailbox mailbox = properties.mailbox(propertyId).orElse(null);
            if (mailbox == null) continue;
            homes.add(new Housing(property, mailbox));
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
             PreparedStatement s = c.prepareStatement(
                     "SELECT 1 FROM gs_households WHERE home_property_uuid = ? LIMIT 1")) {
            s.setString(1, propertyId.toString());
            try (ResultSet r = s.executeQuery()) { return r.next(); }
        }
    }

    private boolean isNpcHousingType(ClaimSummary claim) {
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
        String household = r.getString("household_uuid");
        String home = r.getString("home_property_uuid");
        String pos = r.getString("position_uuid");
        String p1 = r.getString("parent1_uuid");
        String p2 = r.getString("parent2_uuid");
        Object born = r.getObject("born_at");
        return new Resident(
                UUID.fromString(r.getString("villager_uuid")),
                r.getString("resident_name"),
                UUID.fromString(r.getString("territory_claim_uuid")),
                household == null ? null : UUID.fromString(household),
                home == null ? null : UUID.fromString(home),
                pos == null ? null : UUID.fromString(pos),
                r.getString("occupation_label"),
                p1 == null ? null : UUID.fromString(p1),
                p2 == null ? null : UUID.fromString(p2),
                born == null ? null : r.getLong("born_at"),
                r.getLong("created_at")
        );
    }

    private record Housing(PropertyAddress property, PropertyMailbox mailbox) {}

    private record Household(UUID id, UUID territoryId, UUID homePropertyId, UUID ownerAccountId, long createdAt) {}
    public record Resident(UUID id, String name, UUID territoryClaimId, UUID householdId, UUID homePropertyId,
                           UUID positionId, String occupationLabel, UUID parent1Id, UUID parent2Id,
                           Long bornAt, long createdAt) {}
    public record ImmigrationResult(boolean moved, String message) {}
}
