package com.epfisztner.knowledgeassistant.verification;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Citation markers in model output: {@code [1]}, {@code [1][2]} or {@code [1, 2]}. The verifier
 * and the Citation extraction use this one definition so they never disagree.
 */
public final class CitationMarkers {

    static final Pattern MARKER = Pattern.compile("\\[\\s*(\\d+(?:\\s*,\\s*\\d+)*)\\s*]");

    private CitationMarkers() {
    }

    /**
     * The distinct markers in {@code text}, in order of first appearance.
     */
    public static List<Integer> in(String text) {
        Set<Integer> markers = new LinkedHashSet<>();
        Matcher m = MARKER.matcher(text);
        while (m.find()) {
            for (String number : m.group(1).split("\\s*,\\s*")) {
                markers.add(Integer.parseInt(number));
            }
        }
        return new ArrayList<>(markers);
    }

    /**
     * {@code text} with every marker removed (so marker digits are not mistaken for figures).
     */
    static String strip(String text) {
        return MARKER.matcher(text).replaceAll(" ");
    }
}
