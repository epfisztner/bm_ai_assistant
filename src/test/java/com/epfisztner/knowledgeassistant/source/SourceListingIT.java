package com.epfisztner.knowledgeassistant.source;

import com.epfisztner.knowledgeassistant.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SourceListingIT extends IntegrationTest {

    @Test
    void listsIngestedSourcesAndFiltersThemByProductCategory() {
        fixtureSite.page("/listing/lakashitel/", "<html><body><main><h1>Lista Lakáshitel</h1><p>THM 6,5%.</p></main></body></html>");
        fixtureSite.page("/listing/bankszamla/", "<html><body><main><h1>Lista Számla</h1><p>Havi díj 500 Ft.</p></main></body></html>");
        ingest("listing-lakashitel");
        ingest("listing-bankszamla");

        api.get().uri("/sources").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[*].key").value(List.class, keys -> assertThat(keys).contains("listing-lakashitel", "listing-bankszamla"));

        api.get().uri("/sources?productCategory=LAKASHITEL").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[*].key").value(List.class, keys -> assertThat(keys).contains("listing-lakashitel").doesNotContain("listing-bankszamla"))
                .jsonPath("$[*].productCategory").value(List.class, categories -> assertThat(categories).containsOnly("LAKASHITEL"))
                .jsonPath("$[?(@.key == 'listing-lakashitel')].bank").isEqualTo(List.of("Lista Bank"))
                .jsonPath("$[?(@.key == 'listing-lakashitel')].bankProduct").isEqualTo(List.of("Lista Lakáshitel"))
                .jsonPath("$[?(@.key == 'listing-lakashitel')].title").isEqualTo(List.of("Lista Lakáshitel"))
                .jsonPath("$[?(@.key == 'listing-lakashitel')].currentVersion.versionNumber").isEqualTo(List.of(1));
    }

    @Test
    void anUnknownProductCategoryIsABadRequest() {
        api.get().uri("/sources?productCategory=AUTOHITEL").exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentType("application/problem+json")
                .expectBody()
                .jsonPath("$.status").isEqualTo(400)
                .jsonPath("$.instance").isEqualTo("/api/v1/sources")
                .jsonPath("$.errors[0].field").isEqualTo("productCategory")
                .jsonPath("$.errors[0].message").value(String.class, message -> assertThat(message)
                        .contains("AUTOHITEL").contains("LAKASHITEL, SZEMELYI_KOLCSON, BANKSZAMLA"));
    }

    @Test
    void unknownSourcesAndVersionsAreNotFound() {
        api.get().uri("/sources/{id}", 999_999).exchange().expectStatus().isNotFound();
        api.get().uri("/sources/{id}/versions", 999_999).exchange().expectStatus().isNotFound();
        api.get().uri("/source-versions/{id}", 999_999).exchange().expectStatus().isNotFound();
    }

    private void ingest(String sourceKey) {
        api.post().uri("/ingestion/runs?sourceKey={key}", sourceKey).exchange()
                .expectStatus().isCreated()
                .expectBody().jsonPath("$.outcomes[0].outcome").isEqualTo("NEW_VERSION");
    }
}
