package com.anrilogger.store;

/** Immutable, independent of live registries and world state. Never persist runtime registry IDs. */
public record LogEvent(long id, long time, long tick, String world, String session, String dimension,
                       int x, int y, int z, String playerId, String playerName, String action,
                       String object, int count, String detail, String cause, long previousPosition,
                       long previousPlayer) {
    public LogEvent withStorage(long id, long previousPosition, long previousPlayer) {
        return new LogEvent(id, time, tick, world, session, dimension, x, y, z, playerId, playerName,
                action, object, count, detail, cause, previousPosition, previousPlayer);
    }
    public String positionKey() { return world + "|" + dimension + "|" + x + "," + y + "," + z; }
    public String playerKey() { return world + "|" + playerId; }
}
