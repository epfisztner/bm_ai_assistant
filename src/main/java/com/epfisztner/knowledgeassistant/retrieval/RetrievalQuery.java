package com.epfisztner.knowledgeassistant.retrieval;

import com.epfisztner.knowledgeassistant.productcategory.ProductCategory;

import java.util.Optional;

/**
 * What to search for: the Standalone Question, optionally restricted to one Product Category (pre-filter).
 */
public record RetrievalQuery(String standaloneQuestion, Optional<ProductCategory> productCategory) {
}
