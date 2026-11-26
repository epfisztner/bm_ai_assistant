package com.epfisztner.knowledgeassistant.source;

import com.epfisztner.knowledgeassistant.productcategory.ProductCategory;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * What an Expert needs to recognise a Source: its title, URL, bank, Bank Product and Product
 * Category. Carried with every retrieved Passage and snapshotted on Citations; API views unwrap it
 * into their own fields ({@code sourceTitle}, {@code url}, {@code bank}, {@code bankProduct},
 * {@code productCategory}).
 */
public record SourceDetails(String sourceTitle, String url, String bank, String bankProduct,
                            ProductCategory productCategory) {

    /**
     * Reads the {@code url, bank, bank_product, product_category} columns and the given title column.
     */
    public static SourceDetails from(ResultSet rs, String titleColumn) throws SQLException {
        return new SourceDetails(rs.getString(titleColumn), rs.getString("url"), rs.getString("bank"),
                rs.getString("bank_product"), ProductCategory.valueOf(rs.getString("product_category")));
    }
}
