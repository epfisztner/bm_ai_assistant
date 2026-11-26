package com.epfisztner.knowledgeassistant.source;

import com.epfisztner.knowledgeassistant.productcategory.ProductCategory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The declared list of Sources the Knowledge Base is built from, read from a versioned YAML file
 * ({@code knowledge.source-registry.location}). The file is read on every call, so a changed
 * registry takes effect on the next ingestion run. {@code ${...}} placeholders are resolved
 * against the Spring environment (tests use this to point at the fixture site).
 */
@Component
public class SourceRegistry {

    /**
     * One declared Source. {@code title} is an optional override of the page's own title.
     */
    public record RegisteredSource(String key, String url, String title, ProductCategory productCategory,
                                   String bank, String bankProduct) {
    }

    private record RegistryFile(List<RegisteredSource> sources) {
    }

    private static final ObjectMapper YAML = YAMLMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private final Resource location;
    private final Environment environment;

    SourceRegistry(@Value("${knowledge.source-registry.location}") Resource location, Environment environment) {
        this.location = location;
        this.environment = environment;
    }

    /**
     * All declared Sources, in registry order.
     */
    public List<RegisteredSource> sources() {
        String content;
        try (var in = location.getInputStream()) {
            content = environment.resolveRequiredPlaceholders(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read Source Registry " + location, e);
        }
        RegistryFile file = YAML.readValue(content, RegistryFile.class);
        List<RegisteredSource> sources = file == null || file.sources() == null ? List.of() : file.sources();
        validate(sources);
        return sources;
    }

    public Optional<RegisteredSource> find(String key) {
        return sources().stream().filter(source -> source.key().equals(key)).findFirst();
    }

    private void validate(List<RegisteredSource> sources) {
        var keys = new HashSet<String>();
        for (RegisteredSource source : sources) {
            Objects.requireNonNull(source.key(), "Source Registry entry without key");
            Objects.requireNonNull(source.url(), () -> "Source " + source.key() + " has no url");
            Objects.requireNonNull(source.productCategory(), () -> "Source " + source.key() + " has no productCategory");
            Objects.requireNonNull(source.bank(), () -> "Source " + source.key() + " has no bank");
            Objects.requireNonNull(source.bankProduct(), () -> "Source " + source.key() + " has no bankProduct");
            if (!keys.add(source.key())) {
                throw new IllegalStateException("Duplicate Source key in Source Registry: " + source.key());
            }
        }
    }
}
