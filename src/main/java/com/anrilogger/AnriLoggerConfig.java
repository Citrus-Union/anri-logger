package com.anrilogger;
import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
public final class AnriLoggerConfig {
    public enum PermissionMode { OP_ONLY, EVERYONE }
    public PermissionMode permissionMode = PermissionMode.OP_ONLY;
    public boolean enableLgAlias = false;
    public int flushIntervalMillis = 1000;
    public int queueCapacity = 32768;
    public int pageSize = 10;
    public int maxQueryScan = 100000;
    public int queryCooldownMillis = 1000;
    public int maxTrackedScheduledTicks = 100000;
    public static AnriLoggerConfig load(Path path) throws IOException {
        Gson gson = new GsonBuilder().setPrettyPrinting().create(); AnriLoggerConfig config;
        if (Files.exists(path)) {
            try (Reader reader=Files.newBufferedReader(path,StandardCharsets.UTF_8)) { config=gson.fromJson(reader,AnriLoggerConfig.class); }
            catch (JsonParseException ex) { throw new IOException("Invalid configuration: " + path,ex); }
        } else {
            config=new AnriLoggerConfig(); Files.createDirectories(path.getParent());
            Files.writeString(path,gson.toJson(config)+System.lineSeparator(),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
        }
        if(config==null || config.permissionMode==null || config.flushIntervalMillis<50 || config.flushIntervalMillis>60000
                || config.queueCapacity<512 || config.queueCapacity>1000000 || config.pageSize<1 || config.pageSize>50
                || config.maxQueryScan<100 || config.maxQueryScan>1000000 || config.queryCooldownMillis<0
                || config.maxTrackedScheduledTicks<100 || config.maxTrackedScheduledTicks>1000000)
            throw new IOException("Invalid anri-logger configuration values; see README");
        return config;
    }
}
