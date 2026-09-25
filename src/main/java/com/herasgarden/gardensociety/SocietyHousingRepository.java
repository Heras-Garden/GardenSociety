package com.herasgarden.gardensociety;

import com.herasgarden.gardencore.api.storage.GardenStorage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Reads Society housing candidates without holding a storage connection while
 * callers perform additional storage-backed checks. This is required for the
 * default single-connection SQLite configuration.
 */
final class SocietyHousingRepository {
    private final GardenStorage storage;

    SocietyHousingRepository(GardenStorage storage) {
        this.storage = storage;
    }

    List<UUID> eligiblePropertyIds() throws SQLException {
        List<UUID> ids = new ArrayList<>();
        try (Connection connection = storage.connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT property_uuid FROM gs_housing WHERE npc_eligible = 1 ORDER BY updated_at ASC");
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                ids.add(UUID.fromString(result.getString("property_uuid")));
            }
        }
        return List.copyOf(ids);
    }
}
