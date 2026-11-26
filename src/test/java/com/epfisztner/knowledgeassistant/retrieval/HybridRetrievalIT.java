package com.epfisztner.knowledgeassistant.retrieval;

import com.jayway.jsonpath.JsonPath;
import com.epfisztner.knowledgeassistant.support.FakeEmbeddingModel;
import com.epfisztner.knowledgeassistant.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.util.List;

import static com.epfisztner.knowledgeassistant.retrieval.CsiripeloSzamla.FEE_SENTENCE;
import static com.epfisztner.knowledgeassistant.retrieval.CsiripeloSzamla.KEYWORD_QUESTION;
import static com.epfisztner.knowledgeassistant.retrieval.CsiripeloSzamla.PARAPHRASED_QUESTION;
import static com.epfisztner.knowledgeassistant.retrieval.CsiripeloSzamla.ask;
import static com.epfisztner.knowledgeassistant.retrieval.CsiripeloSzamla.runIngestion;
import static com.epfisztner.knowledgeassistant.retrieval.CsiripeloSzamla.version;
import static org.assertj.core.api.Assertions.assertThat;

/** Hybrid retrieval with an embedding provider configured (a deterministic fake). */
@Import(FakeEmbeddingModel.class)
class HybridRetrievalIT extends IntegrationTest {

    private static long sourceVersionId;

    @Autowired
    private FakeEmbeddingModel embeddingModel;

    @BeforeEach
    void ingestOnce() {
        if (sourceVersionId == 0) {
            String report = CsiripeloSzamla.ingest(fixtureSite, api);
            sourceVersionId = JsonPath.<Number>read(report, "$.outcomes[0].sourceVersionId").longValue();
        }
    }

    @Test
    void ingestedPassagesAreEmbeddedWithTheConfiguredModel() {
        List<String> models = JsonPath.read(version(api, sourceVersionId), "$.passages[*].embeddingModel");

        assertThat(models).isNotEmpty().containsOnly(FakeEmbeddingModel.DEFAULT_MODEL);
    }

    @Test
    void aQuestionWithNoKeywordOverlapIsAnsweredFromTheSemanticallyMatchingPassage() {
        String turn = ask(api, PARAPHRASED_QUESTION);

        assertThat(JsonPath.<String>read(turn, "$.status")).isEqualTo("ANSWERED");
        assertThat(JsonPath.<String>read(turn, "$.citations[0].passageText")).contains(FEE_SENTENCE);
        assertThat(JsonPath.<Number>read(turn, "$.citations[0].sourceVersionId").longValue()).isEqualTo(sourceVersionId);
    }

    @Test
    void aQuestionUsingTheSourcesWordsIsStillAnswered() {
        String turn = ask(api, KEYWORD_QUESTION);

        assertThat(JsonPath.<String>read(turn, "$.status")).isEqualTo("ANSWERED");
        assertThat(JsonPath.<String>read(turn, "$.citations[0].passageText")).contains(FEE_SENTENCE);
    }

    @Test
    void afterAnEmbeddingModelChangePassagesAreReEmbeddedOnTheNextIngestionRun() {
        try {
            embeddingModel.useModel("fake-embedding-v2");

            // Vectors from different models are never compared: until re-embedding, no semantic match.
            assertThat(JsonPath.<String>read(ask(api, PARAPHRASED_QUESTION), "$.refusalReason")).isEqualTo("NO_EVIDENCE");

            runIngestion(api);

            List<String> models = JsonPath.read(version(api, sourceVersionId), "$.passages[*].embeddingModel");
            assertThat(models).isNotEmpty().containsOnly("fake-embedding-v2");
            String turn = ask(api, PARAPHRASED_QUESTION);
            assertThat(JsonPath.<String>read(turn, "$.status")).isEqualTo("ANSWERED");
            assertThat(JsonPath.<String>read(turn, "$.citations[0].passageText")).contains(FEE_SENTENCE);
        } finally {
            embeddingModel.useModel(FakeEmbeddingModel.DEFAULT_MODEL);
            runIngestion(api);
        }
    }
}
