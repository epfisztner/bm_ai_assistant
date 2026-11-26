package com.epfisztner.knowledgeassistant.ingestion;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.nio.file.Path;

/**
 * Snapshot mode for offline work (ADR-0004): {@code record} writes each fetched raw page to
 * {@code directory} as {@code <source key>.html}; {@code replay} reads pages from there instead of
 * the network. The directory is git-ignored.
 */
@ConfigurationProperties("knowledge.ingestion.snapshots")
record SnapshotProperties(@DefaultValue("off") Mode mode, @DefaultValue("snapshots") Path directory) {

    enum Mode {OFF, RECORD, REPLAY}
}
