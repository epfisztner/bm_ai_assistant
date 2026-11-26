package com.epfisztner.knowledgeassistant.productcategory;

/**
 * The business line a Source belongs to. The enum name is the stable code used in the API,
 * the Source Registry and the database; the label is the Hungarian name shown to Experts.
 */
public enum ProductCategory {

    LAKASHITEL("lakáshitel"),
    SZEMELYI_KOLCSON("személyi kölcsön"),
    BANKSZAMLA("bankszámla");

    private final String label;

    ProductCategory(String label) {
        this.label = label;
    }

    public String code() {
        return name();
    }

    public String label() {
        return label;
    }
}
