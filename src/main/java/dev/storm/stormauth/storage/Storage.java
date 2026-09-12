package dev.storm.stormauth.storage;

import dev.storm.stormauth.auth.PlayerAccount;

import java.util.Map;
import java.util.UUID;

public interface Storage {

    String name();

    Map<UUID, PlayerAccount> loadAll() throws Exception;

    void save(PlayerAccount account) throws Exception;

    void delete(UUID uuid) throws Exception;

    void close();
}
