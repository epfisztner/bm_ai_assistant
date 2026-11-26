package com.epfisztner.knowledgeassistant.jdbc;

import java.sql.Array;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Postgres array values ({@code text[]}, {@code bigint[]}) for the hand-written SQL (ADR-0005).
 */
public final class SqlArrays {

    private SqlArrays() {
    }

    /**
     * A literal for a {@code :param::text[]} bind parameter, e.g. {"Díjak","Számlavezetés"}.
     */
    public static String literal(List<String> values) {
        StringBuilder literal = new StringBuilder("{");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                literal.append(',');
            }
            literal.append('"').append(values.get(i).replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        return literal.append('}').toString();
    }

    /**
     * A literal for a {@code :param::bigint[]} bind parameter, e.g. {1,2,3}.
     */
    public static String longLiteral(Collection<Long> values) {
        return values.stream().map(String::valueOf).collect(Collectors.joining(",", "{", "}"));
    }

    /**
     * A {@code text[]} column value as a list; empty for SQL NULL.
     */
    public static List<String> stringList(Array array) throws SQLException {
        return array == null ? List.of() : Arrays.asList((String[]) array.getArray());
    }
}
