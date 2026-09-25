package com.herasgarden.gardensociety;

import com.herasgarden.gardencore.api.storage.GardenStorage;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class SocietyHousingRepositoryTest {
    @Test
    void eligibleHousingIdsReleaseSingleConnectionBeforeNestedLookup() throws Exception {
        Path db = Files.createTempFile("garden-society-", ".sqlite");
        String url = "jdbc:sqlite:" + db;
        UUID propertyId = UUID.randomUUID();

        try (Connection setup = DriverManager.getConnection(url);
             Statement statement = setup.createStatement()) {
            statement.executeUpdate("CREATE TABLE gs_housing (property_uuid VARCHAR(36) PRIMARY KEY, npc_eligible INTEGER NOT NULL, updated_by VARCHAR(36) NOT NULL, updated_at BIGINT NOT NULL)");
            statement.executeUpdate("CREATE TABLE nested_lookup (property_uuid VARCHAR(36) PRIMARY KEY)");
            statement.executeUpdate("INSERT INTO gs_housing VALUES ('" + propertyId + "',1,'00000000-0000-0000-0000-000000000001',1)");
            statement.executeUpdate("INSERT INTO nested_lookup VALUES ('" + propertyId + "')");
        }

        GardenStorage storage = new SingleLeaseStorage(url);
        SocietyHousingRepository repository = new SocietyHousingRepository(storage);

        List<UUID> ids = repository.eligiblePropertyIds();
        assertEquals(List.of(propertyId), ids);

        // The production SQLite pool has one connection. A follow-up lookup must
        // be able to acquire it immediately after candidate IDs are fetched.
        try (Connection connection = storage.connection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM nested_lookup")) {
            assertTrue(result.next());
            assertEquals(1, result.getInt(1));
        }

        Files.deleteIfExists(db);
    }

    private static final class SingleLeaseStorage implements GardenStorage {
        private final String url;
        private final AtomicBoolean leased = new AtomicBoolean();

        private SingleLeaseStorage(String url) {
            this.url = url;
        }

        @Override
        public Connection connection() throws SQLException {
            if (!leased.compareAndSet(false, true)) {
                throw new SQLException("single SQLite connection is already leased");
            }
            Connection delegate = DriverManager.getConnection(url);
            return (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("close")) {
                            try {
                                delegate.close();
                            } finally {
                                leased.set(false);
                            }
                            return null;
                        }
                        try {
                            return method.invoke(delegate, args);
                        } catch (java.lang.reflect.InvocationTargetException exception) {
                            throw exception.getCause();
                        }
                    });
        }

        @Override
        public String dialect() {
            return "sqlite";
        }
    }
}
