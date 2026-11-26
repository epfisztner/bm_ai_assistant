package com.epfisztner.knowledgeassistant.productcategory;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;

@RestController
@RequestMapping("/api/v1/product-categories")
@Tag(name = "Product Categories")
class ProductCategoryController {

    record ProductCategoryResponse(String code, String label) {
    }

    @GetMapping
    @Operation(summary = "List the Product Categories an Expert can filter by")
    List<ProductCategoryResponse> list() {
        return Arrays.stream(ProductCategory.values())
                .map(category -> new ProductCategoryResponse(category.code(), category.label()))
                .toList();
    }
}
