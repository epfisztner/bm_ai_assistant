package com.epfisztner.knowledgeassistant.ingestion;

import com.jayway.jsonpath.JsonPath;
import com.epfisztner.knowledgeassistant.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.json.JsonCompareMode;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ContentExtractionIT extends IntegrationTest {

    @Test
    void splitsContentIntoPassagesAtHeadingsLabeledWithTheirHeadingPath() {
        fixtureSite.page("/extraction/headings/", """
                <html><head><title>Fejezetes Számla - Próba Bank</title></head>
                <body><main>
                  <h1>Fejezetes Számla</h1>
                  <p>Bevezető a számláról.</p>
                  <h2>Díjak</h2>
                  <h3>Számlavezetés</h3>
                  <p>A számlavezetési díj havi 990 Ft.</p>
                  <ul><li>Első tétel</li><li>Második tétel</li></ul>
                  <h3>Kártya</h3>
                  <p>A kártyadíj évi 2 500 Ft.</p>
                  <h2>Feltételek</h2>
                  <p>Havi 250 000 Ft jóváírás szükséges.</p>
                </main></body></html>
                """);

        api.get().uri("/source-versions/{id}", ingest("extract-headings")).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.title").isEqualTo("Fejezetes Számla - Próba Bank")
                .json("""
                        {"passages": [
                          {"ordinal": 1, "headingPath": ["Fejezetes Számla"], "text": "Bevezető a számláról."},
                          {"ordinal": 2, "headingPath": ["Fejezetes Számla", "Díjak", "Számlavezetés"],
                           "text": "A számlavezetési díj havi 990 Ft.\\n\\nElső tétel\\n\\nMásodik tétel"},
                          {"ordinal": 3, "headingPath": ["Fejezetes Számla", "Díjak", "Kártya"], "text": "A kártyadíj évi 2 500 Ft."},
                          {"ordinal": 4, "headingPath": ["Fejezetes Számla", "Feltételek"], "text": "Havi 250 000 Ft jóváírás szükséges."}
                        ]}
                        """, JsonCompareMode.LENIENT);
    }

    @Test
    void stripsNavigationHeaderFooterScriptsStylesAndFormsWhenThereIsNoMainElement() {
        fixtureSite.page("/extraction/boilerplate/", """
                <html><head><title>Zajos Számla</title><style>body { color: red; }</style></head>
                <body>
                  <header><a href="/">Logó</a> Fejléc szöveg</header>
                  <nav><ul><li><a href="/a">Menüpont egy</a></li><li><a href="/b">Menüpont kettő</a></li></ul></nav>
                  <div class="content">
                    <h1>Zajos Számla</h1>
                    <p>A számla <strong>díjmentes</strong> az első évben.</p>
                    <script>window.tracking = "követőkód";</script>
                    <form><label>Hírlevél</label><input name="email"><button>Feliratkozás</button></form>
                  </div>
                  <footer>Lábléc © Próba</footer>
                </body></html>
                """);

        api.get().uri("/source-versions/{id}", ingest("extract-boilerplate")).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.extractedText").isEqualTo("Zajos Számla\n\nA számla díjmentes az első évben.")
                .jsonPath("$.passages.length()").isEqualTo(1)
                .jsonPath("$.passages[0].text").isEqualTo("A számla díjmentes az első évben.");
    }

    @Test
    void prefersThePagesMainContentElement() {
        fixtureSite.page("/extraction/main-element/", """
                <html><head><title>Oldalcím</title></head>
                <body>
                  <div class="sidebar"><p>Ajánlott cikkek oldalsávban</p></div>
                  <main><h2>Kamat</h2><p>A betéti kamat 0,1%.</p></main>
                  <div class="cookie"><p>Sütiket használunk</p></div>
                </body></html>
                """);

        long versionId = ingest("extract-main-element");
        api.get().uri("/source-versions/{id}", versionId).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.extractedText").isEqualTo("Kamat\n\nA betéti kamat 0,1%.")
                .jsonPath("$.passages[0].headingPath[0]").isEqualTo("Kamat");

        // The registry's title override wins over the page's own <title>.
        api.get().uri("/sources?productCategory=BANKSZAMLA").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[?(@.key == 'extract-main-element')].title").isEqualTo(List.of("Főtartalmas Számla"));
    }

    @Test
    void keepsMenusOutOfPassagesOnAComparisonPageWithoutAMainElement() {
        fixtureSite.page("/extraction/comparison-page/", """
                <html><head><title>Próba összehasonlítás</title></head>
                <body>
                  <header class="main-header">
                    <nav aria-label="Lakossági főmenü"><ul role="menubar">
                      <li><a href="/lakashitel/" role="menuitem">Lakáshitel</a><span>Összesen 492 termék</span></li>
                      <li><a href="/bankszamla/" role="menuitem">Bankszámla</a><span>Összesen 54 termék</span></li>
                    </ul></nav>
                  </header>
                  <div class="page">
                    <h1>Fedezd fel a díjmentes számlákat!</h1>
                    <div role="menu" class="dropdown-panel">
                      <a role="menuitem" href="/szemelyi-kolcson/">Személyi kölcsön</a><span>Összesen 48 termék</span>
                      <a role="menuitem" href="/babavaro/">Babaváró hitel</a><span>Összesen 12 termék</span>
                    </div>
                    <h2>Mi a díjmentes számla?</h2>
                    <p>A díjmentes számlának nincs havi számlavezetési díja.</p>
                    <div class="cards">
                      <div class="card"><h3>Próba Számla</h3>
                        <div><span>Ingyenesség hossza</span><span>2 év</span></div>
                        <div><span>Elvárt havi jóváírás</span><span>250 000 Ft</span></div>
                      </div>
                    </div>
                  </div>
                  <footer><nav><a href="/impresszum/">Impresszum</a></nav></footer>
                </body></html>
                """);

        String body = new String(api.get().uri("/source-versions/{id}", ingest("extract-comparison-page")).exchange()
                .expectStatus().isOk()
                .expectBody().returnResult().getResponseBodyContent());

        assertThat(JsonPath.<String>read(body, "$.extractedText"))
                .doesNotContain("termék", "Lakáshitel", "Babaváró", "Impresszum");
        assertThat(JsonPath.<List<String>>read(body, "$.passages[*].text")).containsExactly(
                "A díjmentes számlának nincs havi számlavezetési díja.",
                "Ingyenesség hossza 2 év\n\nElvárt havi jóváírás 250 000 Ft");
        assertThat(JsonPath.<List<String>>read(body, "$.passages[1].headingPath"))
                .containsExactly("Fedezd fel a díjmentes számlákat!", "Mi a díjmentes számla?", "Próba Számla");
    }

    @Test
    void givesConditionsSectionsAndFootnotesTheirOwnPassages() {
        fixtureSite.page("/extraction/conditions/", """
                <html><head><title>Feltételes Számla</title></head>
                <body><main>
                  <h1>Feltételes Számla - 2 évig díjmentes</h1>
                  <ul><li>0 Ft számlavezetési díj</li><li>Évi 8% kamat lekötésre *</li></ul>
                  <div class="row"><div class="col">
                    <h2>Az ingyenesség feltételei:</h2>
                    <ul>
                      <li><svg aria-hidden="true"><path d="M0 0"/></svg>Havonta legalább 250 000 Ft jóváírás</li>
                      <li><svg aria-hidden="true"><path d="M0 0"/></svg>Online számlanyitás</li>
                    </ul>
                  </div></div>
                  <h2>Számlanyitás egyszerűen</h2>
                  <p>Nyiss számlát néhány perc alatt.</p>
                  <div class="footnotes">
                    <p>*A kamatot a betéti szerződés rögzíti.</p>
                    <small>**A tájékoztatás nem teljes körű, a részleteket a kondíciós lista tartalmazza.</small>
                  </div>
                </main></body></html>
                """);

        api.get().uri("/source-versions/{id}", ingest("extract-conditions")).exchange()
                .expectStatus().isOk()
                .expectBody()
                .json("""
                        {"passages": [
                          {"ordinal": 1, "headingPath": ["Feltételes Számla - 2 évig díjmentes"],
                           "text": "0 Ft számlavezetési díj\\n\\nÉvi 8% kamat lekötésre *"},
                          {"ordinal": 2, "headingPath": ["Feltételes Számla - 2 évig díjmentes", "Az ingyenesség feltételei:"],
                           "text": "Havonta legalább 250 000 Ft jóváírás\\n\\nOnline számlanyitás"},
                          {"ordinal": 3, "headingPath": ["Feltételes Számla - 2 évig díjmentes", "Számlanyitás egyszerűen"],
                           "text": "Nyiss számlát néhány perc alatt."},
                          {"ordinal": 4, "headingPath": ["Feltételes Számla - 2 évig díjmentes", "Számlanyitás egyszerűen"],
                           "text": "*A kamatot a betéti szerződés rögzíti.\\n\\n**A tájékoztatás nem teljes körű, a részleteket a kondíciós lista tartalmazza."}
                        ]}
                        """, JsonCompareMode.LENIENT)
                .jsonPath("$.passages.length()").isEqualTo(4);
    }

    @Test
    void dropsAdsRelatedContentPlaceholdersAndMobileOnlyDuplicatesInsideTheMainElement() {
        fixtureSite.page("/extraction/page-furniture/", """
                <html><head><title>Bútorzott Számla</title></head>
                <body><main>
                  <h1><div><span class="d-md-none">Bútorzott Számla</span><span class="d-none d-md-inline">Bútorzott Számla</span></div></h1>
                  <ul class="d-md-hide"><li>Díjmentes kártya</li></ul>
                  <ul class="d-none d-md-flex"><li>Díjmentes kártya</li></ul>
                  <div class="adzone"><div id="ad1"></div><small>Hirdetés</small></div>
                  <h2>Díjak</h2>
                  <p>A számlavezetés díjmentes.</p>
                  <div class="hidden lg:block" style="position:absolute"><div id="ad2" class="goAdverticum"></div><small>Hirdetés</small></div>
                  <aside class="related-news">
                    <h2>Kapcsolódó tartalmak</h2>
                    <h3><span>&zwnj;</span> <span>&zwnj;</span></h3>
                    <p>&zwnj; &zwnj; &zwnj;</p>
                  </aside>
                  <h2>Blogajánló</h2>
                  <div><span>&zwnj;</span></div>
                </main></body></html>
                """);

        api.get().uri("/source-versions/{id}", ingest("extract-page-furniture")).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.extractedText").isEqualTo("""
                        Bútorzott Számla

                        Díjmentes kártya

                        Díjak

                        A számlavezetés díjmentes.

                        Blogajánló""")
                .json("""
                        {"passages": [
                          {"ordinal": 1, "headingPath": ["Bútorzott Számla"], "text": "Díjmentes kártya"},
                          {"ordinal": 2, "headingPath": ["Bútorzott Számla", "Díjak"], "text": "A számlavezetés díjmentes."}
                        ]}
                        """, JsonCompareMode.LENIENT)
                .jsonPath("$.passages.length()").isEqualTo(2);
    }

    @Test
    void placesContentStreamedInAtTheEndOfThePageUnderTheHeadingItBelongsTo() {
        // Server-streamed pages send a placeholder <template id="B:n"> in place and the real content
        // later, at the end of the body, in a hidden <div id="S:n">.
        fixtureSite.page("/extraction/streamed-content/", """
                <html><head><title>Próba számlaajánlatok</title></head>
                <body>
                  <div class="page">
                    <h2>3 díjmentes számlát ajánlunk</h2>
                    <!--$?--><template id="B:0"></template><!--/$-->
                    <h2>Blogajánló</h2>
                    <p>Olvasd el legújabb cikkeinket.</p>
                  </div>
                  <div hidden id="S:0">
                    <div class="card"><span>Próba Számla</span><span>Ingyenesség hossza 2 év</span></div>
                  </div>
                </body></html>
                """);

        api.get().uri("/source-versions/{id}", ingest("extract-streamed-content")).exchange()
                .expectStatus().isOk()
                .expectBody()
                .json("""
                        {"passages": [
                          {"ordinal": 1, "headingPath": ["3 díjmentes számlát ajánlunk"], "text": "Próba Számla Ingyenesség hossza 2 év"},
                          {"ordinal": 2, "headingPath": ["Blogajánló"], "text": "Olvasd el legújabb cikkeinket."}
                        ]}
                        """, JsonCompareMode.LENIENT)
                .jsonPath("$.passages.length()").isEqualTo(2);
    }

    @Test
    void splitsAnOversizedSectionAtParagraphBoundaries() {
        String first = "Első bekezdés. " + "Ez a mondat a kölcsön feltételeit részletezi. ".repeat(18);
        String second = "Második bekezdés. " + "Ez a mondat a törlesztés menetét magyarázza el. ".repeat(18);
        String third = "Harmadik bekezdés a díjakról.";
        fixtureSite.page("/extraction/oversized/", """
                <html><head><title>Hosszú Kölcsön</title></head>
                <body><main>
                  <h2>Részletek</h2>
                  <p>%s</p><p>%s</p><p>%s</p>
                </main></body></html>
                """.formatted(first, second, third));

        String body = new String(api.get().uri("/source-versions/{id}", ingest("extract-oversized")).exchange()
                .expectStatus().isOk()
                .expectBody().returnResult().getResponseBodyContent());

        // max-passage-chars defaults to 1500: the first two ~850-char paragraphs don't fit together.
        assertThat(JsonPath.<Integer>read(body, "$.passages.length()")).isEqualTo(2);
        assertThat(JsonPath.<String>read(body, "$.passages[0].text")).isEqualTo(first.strip());
        assertThat(JsonPath.<String>read(body, "$.passages[1].text")).isEqualTo(second.strip() + "\n\n" + third);
        assertThat(JsonPath.<List<String>>read(body, "$.passages[1].headingPath")).containsExactly("Részletek");
        assertThat(JsonPath.<Integer>read(body, "$.passages[1].ordinal")).isEqualTo(2);
    }

    private long ingest(String sourceKey) {
        String report = new String(api.post().uri("/ingestion/runs?sourceKey={key}", sourceKey).exchange()
                .expectStatus().isCreated()
                .expectBody().jsonPath("$.outcomes[0].outcome").isEqualTo("NEW_VERSION")
                .returnResult().getResponseBodyContent());
        return JsonPath.<Number>read(report, "$.outcomes[0].sourceVersionId").longValue();
    }
}
