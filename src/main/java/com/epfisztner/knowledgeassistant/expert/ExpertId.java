package com.epfisztner.knowledgeassistant.expert;

/**
 * The calling Expert, taken from the {@code X-Expert-Id} header (stand-in for SSO identity).
 * Declare it as a controller method parameter; a missing or blank header yields 401.
 */
public record ExpertId(String value) {

    public static final String HEADER = "X-Expert-Id";
}
