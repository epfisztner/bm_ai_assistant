package com.epfisztner.knowledgeassistant.productcategory;

import com.epfisztner.knowledgeassistant.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.json.JsonCompareMode;

class ProductCategoriesIT extends IntegrationTest {

    @Test
    void listsTheThreeProductCategoriesWithStableCodesAndHungarianLabels() {
        api.get().uri("/product-categories")
                .exchange()
                .expectStatus().isOk()
                .expectBody().json("""
                        [
                          {"code": "LAKASHITEL", "label": "lakáshitel"},
                          {"code": "SZEMELYI_KOLCSON", "label": "személyi kölcsön"},
                          {"code": "BANKSZAMLA", "label": "bankszámla"}
                        ]
                        """, JsonCompareMode.STRICT);
    }
}
