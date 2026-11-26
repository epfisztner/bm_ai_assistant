package com.epfisztner.knowledgeassistant.jdbc;

import java.util.Optional;

/**
 * Enum bind parameters for the hand-written SQL (ADR-0005): enums are stored by name.
 */
public final class SqlEnums {

    private SqlEnums() {
    }

    /**
     * The enum's stored name; null for null.
     */
    public static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }

    /**
     * The enum's stored name; null when empty.
     */
    public static String name(Optional<? extends Enum<?>> value) {
        return value.map(Enum::name).orElse(null);
    }
}
