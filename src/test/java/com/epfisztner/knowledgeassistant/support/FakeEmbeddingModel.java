package com.epfisztner.knowledgeassistant.support;

import com.epfisztner.knowledgeassistant.embedding.EmbeddingModelPort;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Deterministic fake embedding model for integration tests: a tiny hand-made "semantic space".
 * Each dimension is a meaning (cash withdrawal, price, account opening); an accent-folded word
 * counts towards a dimension when it starts with one of the dimension's stems, so words with no
 * keyword overlap ("pénzt kivenni az ATM-ből" ~ "készpénzfelvétel") still land close together.
 * A small constant component keeps every vector non-zero.
 * <p>
 * Register it with {@code @Import(FakeEmbeddingModel.class)} (that test class then gets its own
 * context and database). Tests can switch the reported model id to simulate a model change.
 */
public class FakeEmbeddingModel implements EmbeddingModelPort {

    public static final String DEFAULT_MODEL = "fake-embedding-v1";

    private static final Map<Integer, List<String>> MEANINGS = Map.of(
            0, List.of("keszpenz", "penz", "kiven", "kivet", "atm", "automat"),
            1, List.of("dij", "ingyen", "kerul", "koltseg", "mennyi", "valamibe"),
            2, List.of("nyit", "online", "igenyl"));
    private static final int DIMENSIONS = MEANINGS.size() + 1;
    private static final float BIAS = 0.1f;

    private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final Pattern DIACRITICS = Pattern.compile("\\p{M}+");

    private volatile String modelId = DEFAULT_MODEL;

    /** From now on, report {@code modelId} as the model that produces the embeddings. */
    public void useModel(String modelId) {
        this.modelId = modelId;
    }

    @Override
    public String modelId() {
        return modelId;
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        return texts.stream().map(FakeEmbeddingModel::embed).toList();
    }

    private static float[] embed(String text) {
        float[] vector = new float[DIMENSIONS];
        vector[DIMENSIONS - 1] = BIAS;
        String folded = DIACRITICS.matcher(Normalizer.normalize(text, Normalizer.Form.NFD)).replaceAll("")
                .toLowerCase(Locale.ROOT);
        for (String word : NON_WORD.split(folded)) {
            MEANINGS.forEach((dimension, stems) -> {
                if (stems.stream().anyMatch(word::startsWith)) {
                    vector[dimension]++;
                }
            });
        }
        return vector;
    }
}
