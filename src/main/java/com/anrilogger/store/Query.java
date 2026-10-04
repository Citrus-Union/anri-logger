package com.anrilogger.store;

import java.util.*;
import java.util.regex.*;

public record Query(String world, String dimension, int x, int y, int z, int range,
                    String source, String action, String object, String session, String cause,
                    long after, long before) {
    public static Query parse(String text, String world, String dimension, int x, int y, int z, long now) {
        int range = 16;
        String source = "", action = "", object = "", session = "", cause = "";
        long after = 0, before = Long.MAX_VALUE;
        Set<String> seen = new HashSet<>();
        for (String token : text.trim().split("\\s+")) {
            if (token.isBlank()) continue;
            int colon = token.indexOf(':');
            if (colon < 1 || colon == token.length() - 1) throw new IllegalArgumentException("参数格式应为 key:value：" + token);
            String key = token.substring(0, colon), value = token.substring(colon + 1);
            key = switch (key) { case "player" -> "source"; case "radius" -> "range"; case "time" -> "after"; default -> key; };
            if (!seen.add(key)) throw new IllegalArgumentException("参数重复：" + key);
            switch (key) {
                case "range" -> { range = value.equals("@global") ? -1 : Integer.parseInt(value); if (range < -1 || range > 4096 || (range == -1 && !value.equals("@global"))) throw new IllegalArgumentException("range 应为 0..4096 或 @global"); }
                case "world" -> dimension = value;
                case "source" -> source = value;
                case "action" -> { if (!Set.of("block-break", "block-place", "block-use", "block-change", "item-insert", "item-remove", "container", "container-open", "entity-trigger", "explosion").contains(value)) throw new IllegalArgumentException("未知 action：" + value); action = value; }
                case "object" -> object = value.contains(":") ? value : "minecraft:" + value;
                case "session" -> session = value;
                case "cause" -> { UUID.fromString(value); cause = value; }
                case "after" -> after = Math.max(0, Math.subtractExact(now, duration(value)));
                case "before" -> before = Math.subtractExact(now, duration(value));
                default -> throw new IllegalArgumentException("未知参数：" + key);
            }
        }
        if (after > before) throw new IllegalArgumentException("时间范围为空");
        return new Query(world, dimension, x, y, z, range, source, action, object, session, cause, after, before);
    }
    public static long duration(String value) {
        Matcher matcher = Pattern.compile("(\\d+)([smhdw])").matcher(value);
        long result = 0; int end = 0;
        while (matcher.find()) {
            if (matcher.start() != end) throw new IllegalArgumentException("无效时间：" + value);
            long unit = switch (matcher.group(2)) { case "s" -> 1000L; case "m" -> 60000L; case "h" -> 3600000L; case "d" -> 86400000L; default -> 604800000L; };
            result = Math.addExact(result, Math.multiplyExact(Long.parseLong(matcher.group(1)), unit)); end = matcher.end();
        }
        if (end != value.length() || end == 0 || result <= 0) throw new IllegalArgumentException("无效时间：" + value);
        return result;
    }
    public boolean matches(LogEvent e) {
        return world.equals(e.world()) && (dimension.equals("@all") || dimension.equals(e.dimension()))
                && (range < 0 || (Math.abs((long)e.x()-x) <= range && Math.abs((long)e.y()-y) <= range && Math.abs((long)e.z()-z) <= range))
                && (source.isEmpty() || source.equalsIgnoreCase(e.playerName()) || source.equalsIgnoreCase(e.playerId()))
                && (action.isEmpty() || action.equals(e.action()) || (action.equals("container") && (e.action().equals("item-insert") || e.action().equals("item-remove")))) && (object.isEmpty() || object.equals(e.object()))
                && (session.isEmpty() || session.equals(e.session())) && (cause.isEmpty() || cause.equals(e.cause()))
                && e.time() >= after && e.time() <= before;
    }
    public String positionKey() { return world + "|" + dimension + "|" + x + "," + y + "," + z; }
}
