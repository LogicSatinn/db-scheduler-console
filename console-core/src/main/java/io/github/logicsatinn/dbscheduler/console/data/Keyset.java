package io.github.logicsatinn.dbscheduler.console.data;

import java.io.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;

/** Bounded, versioned cursors bound to the query. Cursor fields are always SQL parameters. */
public final class Keyset {
    private Keyset() {}
    public record Key(String column, Object value, boolean descending) {}
    public record Cursor(boolean backwards, List<String> values) {}

    public static String scope(Object... parts) {
        try {
            var out = new ByteArrayOutputStream();
            var data = new DataOutputStream(out);
            for (Object part : parts) {
                data.writeBoolean(part != null);
                if (part != null) data.writeUTF(part.toString());
            }
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(out.toByteArray()));
        } catch (Exception e) { throw new IllegalArgumentException("Invalid cursor filters", e); }
    }
    public static Cursor decode(String token, String scope, int fields) {
        if (token == null || token.isBlank()) return null;
        try {
            if (token.length() > 4096) throw new IllegalArgumentException();
            var in = new DataInputStream(new ByteArrayInputStream(Base64.getUrlDecoder().decode(token)));
            if (in.readByte() != 1 || !in.readUTF().equals(scope)) throw new IllegalArgumentException();
            boolean backwards = in.readBoolean();
            List<String> values = new ArrayList<>();
            for (int i = 0; i < fields; i++) values.add(in.readBoolean() ? in.readUTF() : null);
            if (in.available() != 0) throw new IllegalArgumentException();
            return new Cursor(backwards, values);
        } catch (Exception e) { throw new IllegalArgumentException("Invalid cursor or changed filters"); }
    }
    private static String encode(String scope, boolean backwards, List<Key> keys) {
        try {
            var out = new ByteArrayOutputStream(); var data = new DataOutputStream(out);
            data.writeByte(1); data.writeUTF(scope); data.writeBoolean(backwards);
            for (Key key : keys) {
                data.writeBoolean(key.value() != null);
                if (key.value() != null) data.writeUTF(key.value().toString());
            }
            return Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray());
        } catch (IOException e) { throw new IllegalArgumentException("Cursor value too long", e); }
    }
    public static int size(int size) {
        if (size < 1 || size > 200) throw new IllegalArgumentException("Size must be between 1 and 200");
        return size;
    }
    public static Object value(String raw, Class<?> type) {
        if (raw == null) return null;
        if (raw.length() > 1000) throw new IllegalArgumentException("Invalid cursor value");
        try {
            if (type == Instant.class) {
                Instant instant = Instant.parse(raw);
                if (instant.isBefore(Instant.parse("0001-01-01T00:00:00Z"))
                        || instant.isAfter(Instant.parse("9999-12-31T23:59:59.999999999Z")))
                    throw new IllegalArgumentException();
                return instant;
            }
            if (type == Long.class) return Long.valueOf(raw);
            if (type == Integer.class) return Integer.valueOf(raw);
            return raw;
        } catch (RuntimeException e) { throw new IllegalArgumentException("Invalid cursor value"); }
    }
    private static Object jdbc(Object value, Dialect dialect) {
        return value instanceof Instant instant ? dialect.jdbcTimestamp(instant) : value;
    }
    /** Lexicographic seek, with NULLS LAST in the forward order on every database. */
    public static void seek(StringBuilder where, List<Object> params, List<Key> keys,
            boolean backwards, Dialect dialect) {
        where.append(" AND (");
        for (int i = 0; i < keys.size(); i++) {
            if (i > 0) where.append(" OR ");
            where.append('(');
            for (int j = 0; j < i; j++) {
                Key previous = keys.get(j);
                where.append(previous.column());
                if (previous.value() == null) where.append(" IS NULL AND ");
                else { where.append(" = ? AND "); params.add(jdbc(previous.value(), dialect)); }
            }
            Key key = keys.get(i);
            if (key.value() == null) where.append(backwards ? key.column() + " IS NOT NULL" : "1=0");
            else {
                where.append('(').append(key.column()).append(key.descending() ^ backwards ? " < ?" : " > ?");
                params.add(jdbc(key.value(), dialect));
                if (!backwards) where.append(" OR ").append(key.column()).append(" IS NULL");
                where.append(')');
            }
            where.append(')');
        }
        where.append(')');
    }
    public static String order(List<Key> keys, boolean backwards) {
        var out = new StringJoiner(", ", " ORDER BY ", "");
        Set<String> ordered = new HashSet<>();
        for (Key key : keys) {
            if (!ordered.add(key.column())) continue;
            // Only heartbeat is nullable; avoid CASE expressions on indexed, non-null keys.
            if (key.column().equals("last_heartbeat"))
                out.add("CASE WHEN " + key.column() + " IS NULL THEN 1 ELSE 0 END" + (backwards ? " DESC" : " ASC"));
            out.add(key.column() + (key.descending() ^ backwards ? " DESC" : " ASC"));
        }
        return out.toString();
    }
    public static <T> Page<T> page(List<T> fetched, int size, Cursor cursor, String scope,
            Function<T, List<Key>> keys) {
        boolean more = fetched.size() > size;
        List<T> rows = new ArrayList<>(fetched.subList(0, Math.min(size, fetched.size())));
        boolean back = cursor != null && cursor.backwards();
        if (back) Collections.reverse(rows);
        if (rows.isEmpty()) return new Page<>(List.of(), null, null);
        String previous = (back ? more : cursor != null) ? encode(scope, true, keys.apply(rows.get(0))) : null;
        String next = (back ? cursor != null : more) ? encode(scope, false, keys.apply(rows.get(rows.size()-1))) : null;
        return new Page<>(List.copyOf(rows), previous, next);
    }
}
