package com.epfisztner.knowledgeassistant.source;

import com.jayway.jsonpath.JsonPath;
import com.epfisztner.knowledgeassistant.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * The Source Registry the application ships with: the agreed bankmonitor.hu pages (spec, ADR-0004).
 * Its own context reads the default registry and replays from an empty snapshot directory, so a
 * full run registers every Source (and fails its fetch) without touching the network.
 */
class DefaultSourceRegistryIT extends IntegrationTest {

    private static final Path NO_SNAPSHOTS = Path.of("target", "default-registry-it", "snapshots").toAbsolutePath();

    @DynamicPropertySource
    static void defaultRegistryWithoutNetwork(DynamicPropertyRegistry registry) {
        registry.add("knowledge.source-registry.location", () -> "classpath:source-registry.yaml");
        registry.add("knowledge.ingestion.snapshots.mode", () -> "replay");
        registry.add("knowledge.ingestion.snapshots.directory", NO_SNAPSHOTS::toString);
    }

    @Test
    void declaresTheNineAgreedBankmonitorPagesWithTheirProductCategoryBankAndBankProduct() {
        String report = new String(api.post().uri("/ingestion/runs").exchange()
                .expectStatus().isCreated()
                .expectBody().returnResult().getResponseBodyContent(), StandardCharsets.UTF_8);
        assertThat(JsonPath.<List<String>>read(report, "$.outcomes[*].outcome")).hasSize(9).containsOnly("FAILED");

        String body = new String(api.get().uri("/sources").exchange()
                .expectStatus().isOk()
                .expectBody().returnResult().getResponseBodyContent(), StandardCharsets.UTF_8);
        List<Map<String, Object>> sources = JsonPath.read(body, "$");

        assertThat(sources)
                .extracting(s -> s.get("url"), s -> s.get("productCategory"), s -> s.get("bank"), s -> s.get("bankProduct"))
                .containsExactlyInAnyOrder(
                        tuple("https://bankmonitor.hu/bankszamla/ingyenes-bankszamlak/",
                                "BANKSZAMLA", "Több bank", "Ingyenes bankszámlák összehasonlítása"),
                        tuple("https://bankmonitor.hu/bankszamla/ingyenes-bankszamla-online/",
                                "BANKSZAMLA", "CIB Bank", "CIB ECO"),
                        tuple("https://bankmonitor.hu/bankszamla/cib-eco-plusz/",
                                "BANKSZAMLA", "CIB Bank", "CIB ECO Plusz"),
                        tuple("https://bankmonitor.hu/bankszamla/ingyenes-bankszamla-nyitas/",
                                "BANKSZAMLA", "UniCredit Bank", "UniCredit Partner Aktív Nulla"),
                        tuple("https://bankmonitor.hu/bankszamla/erste-ingyenes-bankszamla-nyitas/",
                                "BANKSZAMLA", "Erste Bank", "Erste Privilégium 2.0 Partner"),
                        tuple("https://bankmonitor.hu/fogyasztobarat-lakashitel/",
                                "LAKASHITEL", "Több bank", "Minősített Fogyasztóbarát Lakáshitel"),
                        tuple("https://bankmonitor.hu/lakashitel-kalkulator/mennyi-lakashitelt-kaphatok/",
                                "LAKASHITEL", "Több bank", "Lakáshitel összeg kalkulátor"),
                        tuple("https://bankmonitor.hu/szemelyi-kolcson/",
                                "SZEMELYI_KOLCSON", "Több bank", "Személyi kölcsönök összehasonlítása"),
                        tuple("https://bankmonitor.hu/nagyosszegu-szemelyi-kolcson/",
                                "SZEMELYI_KOLCSON", "Több bank", "Nagyösszegű személyi kölcsön"));
        assertThat(sources).allSatisfy(source -> {
            assertThat((String) source.get("key")).matches("[a-z0-9-]+");
            assertThat((String) source.get("title")).isNotBlank().doesNotContain("Bankmonitor");
        });
    }
}
