package com.epfisztner.knowledgeassistant.retrieval;

import com.jayway.jsonpath.JsonPath;
import com.epfisztner.knowledgeassistant.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.epfisztner.knowledgeassistant.retrieval.CsiripeloSzamla.FEE_SENTENCE;
import static com.epfisztner.knowledgeassistant.retrieval.CsiripeloSzamla.KEYWORD_QUESTION;
import static com.epfisztner.knowledgeassistant.retrieval.CsiripeloSzamla.PARAPHRASED_QUESTION;
import static com.epfisztner.knowledgeassistant.retrieval.CsiripeloSzamla.ask;
import static com.epfisztner.knowledgeassistant.retrieval.CsiripeloSzamla.version;
import static org.assertj.core.api.Assertions.assertThat;

/** No embedding provider configured (embedding provider {@code none}, the test default): keyword search alone. */
class KeywordOnlyRetrievalIT extends IntegrationTest {

    private static long sourceVersionId;

    @BeforeEach
    void ingestOnce() {
        if (sourceVersionId == 0) {
            String report = CsiripeloSzamla.ingest(fixtureSite, api);
            sourceVersionId = JsonPath.<Number>read(report, "$.outcomes[0].sourceVersionId").longValue();
        }
    }

    @Test
    void aQuestionUsingTheSourcesWordsIsAnsweredByKeywordSearch() {
        String turn = ask(api, KEYWORD_QUESTION);

        assertThat(JsonPath.<String>read(turn, "$.status")).isEqualTo("ANSWERED");
        assertThat(JsonPath.<String>read(turn, "$.citations[0].passageText")).contains(FEE_SENTENCE);
    }

    @Test
    void aQuestionWithNoKeywordOverlapFindsNoEvidence() {
        String turn = ask(api, PARAPHRASED_QUESTION);

        assertThat(JsonPath.<String>read(turn, "$.status")).isEqualTo("REFUSED");
        assertThat(JsonPath.<String>read(turn, "$.refusalReason")).isEqualTo("NO_EVIDENCE");
    }

    @Test
    void passagesAreNotEmbedded() {
        List<Object> models = JsonPath.read(version(api, sourceVersionId), "$.passages[*].embeddingModel");

        assertThat(models).isNotEmpty().containsOnlyNulls();
    }
}
