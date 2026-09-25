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
                    + "updated_by VARCHAR(36) NOT NULL,"
                    + "updated_at BIGINT NOT NULL)");

            s.executeUpdate("CREATE TABLE IF NOT EXISTS gs_residents ("
                    + "villager_uuid VARCHAR(36) PRIMARY KEY,"
                    + "resident_name VARCHAR(64) NOT NULL,"
                    + "territory_claim_uuid VARCHAR(36) NOT NULL,"
                    + "home_property_uuid VARCHAR(36) NULL,"
                    + "position_uuid VARCHAR(36) NULL,"
                    + "occupation_label VARCHAR(128) NULL,"
                    + "parent1_uuid VARCHAR(36) NULL,"
                    + "parent2_uuid VARCHAR(36) NULL,"
                    + "born_at BIGINT NULL,"
                    + "created_at BIGINT NOT NULL)");
            // Older builds enforced one resident per home. Households share a
            // residence, so migrate that index to a normal lookup index.
            s.executeUpdate("DROP INDEX IF EXISTS idx_gs_resident_home");
            s.executeUpdate("CREATE INDEX IF NOT EXISTS idx_gs_resident_home "
                    + "ON gs_residents (home_property_uuid)");
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
    }
}
