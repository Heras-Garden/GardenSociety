package com.herasgarden.gardensociety;

import com.herasgarden.gardencore.api.storage.GardenStorage;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

public final class SocietySchema {
    private SocietySchema() {}

    public static void ensure(GardenStorage storage) throws SQLException {
        try (Connection c = storage.connection(); Statement s = c.createStatement()) {
            s.executeUpdate("CREATE TABLE IF NOT EXISTS gs_housing ("
                    + "property_uuid VARCHAR(36) PRIMARY KEY,"
                    + "npc_eligible INTEGER NOT NULL DEFAULT 0,"
                    + "audience VARCHAR(24) NOT NULL DEFAULT 'SOCIETY',"
                    + "updated_by VARCHAR(36) NOT NULL,"
                    + "updated_at BIGINT NOT NULL)");
            ensureColumn(c, "gs_housing", "audience",
                    "ALTER TABLE gs_housing ADD COLUMN audience VARCHAR(24) NOT NULL DEFAULT 'SOCIETY'");

            s.executeUpdate("CREATE TABLE IF NOT EXISTS gs_households ("
                    + "household_uuid VARCHAR(36) PRIMARY KEY,"
                    + "territory_claim_uuid VARCHAR(36) NOT NULL,"
                    + "home_property_uuid VARCHAR(36) NOT NULL UNIQUE,"
                    + "owner_account_uuid VARCHAR(36) NOT NULL,"
                    + "created_at BIGINT NOT NULL)");

            s.executeUpdate("CREATE TABLE IF NOT EXISTS gs_residents ("
                    + "villager_uuid VARCHAR(36) PRIMARY KEY,"
                    + "resident_name VARCHAR(64) NOT NULL,"
                    + "territory_claim_uuid VARCHAR(36) NOT NULL,"
                    + "household_uuid VARCHAR(36) NULL,"
                    + "home_property_uuid VARCHAR(36) NULL,"
                    + "position_uuid VARCHAR(36) NULL,"
                    + "occupation_label VARCHAR(128) NULL,"
                    + "parent1_uuid VARCHAR(36) NULL,"
                    + "parent2_uuid VARCHAR(36) NULL,"
                    + "born_at BIGINT NULL,"
                    + "created_at BIGINT NOT NULL)");
            ensureColumn(c, "gs_residents", "household_uuid",
                    "ALTER TABLE gs_residents ADD COLUMN household_uuid VARCHAR(36) NULL");
            s.executeUpdate("DROP INDEX IF EXISTS idx_gs_resident_home");
            s.executeUpdate("CREATE INDEX IF NOT EXISTS idx_gs_resident_home "
                    + "ON gs_residents (home_property_uuid)");
            s.executeUpdate("CREATE INDEX IF NOT EXISTS idx_gs_resident_household "
                    + "ON gs_residents (household_uuid)");
            s.executeUpdate("CREATE INDEX IF NOT EXISTS idx_gs_resident_territory "
                    + "ON gs_residents (territory_claim_uuid)");
            s.executeUpdate("CREATE INDEX IF NOT EXISTS idx_gs_resident_position "
                    + "ON gs_residents (position_uuid)");

            s.executeUpdate("CREATE TABLE IF NOT EXISTS gs_immigration_log ("
                    + "immigration_uuid VARCHAR(36) PRIMARY KEY,"
                    + "territory_claim_uuid VARCHAR(36) NOT NULL,"
                    + "villager_uuid VARCHAR(36) NOT NULL,"
                    + "home_property_uuid VARCHAR(36) NOT NULL,"
                    + "position_uuid VARCHAR(36) NOT NULL,"
                    + "created_at BIGINT NOT NULL)");
            s.executeUpdate("CREATE INDEX IF NOT EXISTS idx_gs_immigration_territory "
                    + "ON gs_immigration_log (territory_claim_uuid, created_at)");
        }
        migrateHouseholds(storage);
    }

    private static void ensureColumn(
            java.sql.Connection connection,
            String table,
            String column,
            String ddl
    ) throws SQLException {
        try (java.sql.ResultSet result = connection.getMetaData().getColumns(null, null, table, column)) {
            if (result.next()) return;
        }
        try (java.sql.Statement statement = connection.createStatement()) {
            statement.executeUpdate(ddl);
        }
    }

    private static void migrateHouseholds(GardenStorage storage) throws SQLException {
        java.util.List<java.util.Map.Entry<java.util.UUID, java.util.UUID>> legacy = new java.util.ArrayList<>();
        try (Connection c = storage.connection();
             java.sql.PreparedStatement q = c.prepareStatement(
                     "SELECT villager_uuid, home_property_uuid FROM gs_residents "
                             + "WHERE home_property_uuid IS NOT NULL AND household_uuid IS NULL");
             java.sql.ResultSet r = q.executeQuery()) {
            while (r.next()) {
                legacy.add(java.util.Map.entry(
                        java.util.UUID.fromString(r.getString("villager_uuid")),
                        java.util.UUID.fromString(r.getString("home_property_uuid"))
                ));
            }
        }

        for (java.util.Map.Entry<java.util.UUID, java.util.UUID> entry : legacy) {
            java.util.UUID householdId = java.util.UUID.randomUUID();
            String territory;
            try (Connection c = storage.connection();
                 java.sql.PreparedStatement q = c.prepareStatement(
                         "SELECT territory_claim_uuid FROM gs_residents WHERE villager_uuid = ?")) {
                q.setString(1, entry.getKey().toString());
                try (java.sql.ResultSet r = q.executeQuery()) {
                    if (!r.next()) continue;
                    territory = r.getString("territory_claim_uuid");
                }
            }
            try (Connection c = storage.connection()) {
                c.setAutoCommit(false);
                try {
                    try (java.sql.PreparedStatement i = c.prepareStatement(
                            "INSERT INTO gs_households "
                                    + "(household_uuid, territory_claim_uuid, home_property_uuid, owner_account_uuid, created_at) "
                                    + "VALUES (?, ?, ?, ?, ?)")) {
                        i.setString(1, householdId.toString());
                        i.setString(2, territory);
                        i.setString(3, entry.getValue().toString());
                        i.setString(4, householdId.toString());
                        i.setLong(5, System.currentTimeMillis());
                        i.executeUpdate();
                    }
                    try (java.sql.PreparedStatement u = c.prepareStatement(
                            "UPDATE gs_residents SET household_uuid = ? WHERE villager_uuid = ?")) {
                        u.setString(1, householdId.toString());
                        u.setString(2, entry.getKey().toString());
                        u.executeUpdate();
                    }
                    c.commit();
                } catch (SQLException exception) {
                    c.rollback();
                    // A previously migrated home can legitimately collide here.
                } finally {
                    c.setAutoCommit(true);
                }
            }
        }

        // Existing children with no home inherit a parent's household where possible.
        try (Connection c = storage.connection();
             java.sql.PreparedStatement u = c.prepareStatement(
                     "UPDATE gs_residents SET household_uuid = ("
                             + "SELECT p.household_uuid FROM gs_residents p "
                             + "WHERE p.villager_uuid = gs_residents.parent1_uuid"
                             + "), home_property_uuid = ("
                             + "SELECT p.home_property_uuid FROM gs_residents p "
                             + "WHERE p.villager_uuid = gs_residents.parent1_uuid"
                             + ") WHERE household_uuid IS NULL AND parent1_uuid IS NOT NULL")) {
            u.executeUpdate();
        }
    }
}
