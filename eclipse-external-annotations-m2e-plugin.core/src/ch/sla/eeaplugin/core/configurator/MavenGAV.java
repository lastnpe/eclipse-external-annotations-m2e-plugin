/*
 * Copyright (c) 2016 Red Hat, Inc. and others. All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package ch.sla.eeaplugin.core.configurator;

import java.util.Objects;
import java.util.Optional;
import org.eclipse.m2e.core.embedder.ArtifactKey;

/**
 * Maven "GAV" = Group ID, Artifact ID and (optional) Version &amp; Classifier.
 *
 * With convenience {@link #matches(ArtifactKey)} method.
 *
 * @author Michael Vorburger
 */
public class MavenGAV {
    // TODO This could be fully replaced with ArtifactKey? It already has a fromPortableString method; just keep custom matches method?

    private final String groupId;
    private final String artifactId;
    private final Optional<String> version;
    private final Optional<String> classifier;

    public static MavenGAV parse(String line) throws IllegalArgumentException {
        String[] parts = line.trim().split(":");
        if (parts.length < 2) {
            throw new IllegalArgumentException("Line must have at least groupId:artifactId, but was: " + line);
        } else if (parts.length == 2) {
            return of(parts[0], parts[1]);
        } else if (parts.length == 3) {
            return of(parts[0], parts[1], parts[2]);
        } else {
            return of(parts[0], parts[1], parts[2], parts[3]);
        }
    }

    public static MavenGAV of(String groupId, String artifactId, String version, String classifier) {
        return new MavenGAV(checkNotNull(groupId, "groupId"), checkNotNull(artifactId, "artifactId"),
                Optional.of(checkNotNull(version, "version")), Optional.of(checkNotNull(classifier, "classifier")));
    }

    public static MavenGAV of(String groupId, String artifactId, String version) {
        return new MavenGAV(checkNotNull(groupId, "groupId"), checkNotNull(artifactId, "artifactId"),
                Optional.of(checkNotNull(version, "version")), Optional.empty());
    }

    public static MavenGAV of(String groupId, String artifactId) {
        return new MavenGAV(checkNotNull(groupId, "groupId"), checkNotNull(artifactId, "artifactId"),
                Optional.empty(), Optional.empty());
    }

    public boolean matches(ArtifactKey artifactKey) {
        return groupId.equals(artifactKey.getGroupId())
            && artifactId.equals(artifactKey.getArtifactId())
            && (!version.isPresent() || version.get().equals(artifactKey.getVersion()))
            && (!classifier.isPresent() || classifier.get().equals(artifactKey.getClassifier()));
    }

    @Override
    public int hashCode() {
        return Objects.hash(groupId, artifactId, version, classifier);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        MavenGAV other = (MavenGAV) obj;
        if (artifactId == null) {
            if (other.artifactId != null) {
                return false;
            }
        } else if (!artifactId.equals(other.artifactId)) {
            return false;
        }
        if (classifier == null) {
            if (other.classifier != null) {
                return false;
            }
        } else if (!classifier.equals(other.classifier)) {
            return false;
        }
        if (groupId == null) {
            if (other.groupId != null) {
                return false;
            }
        } else if (!groupId.equals(other.groupId)) {
            return false;
        }
        if (version == null) {
            if (other.version != null) {
                return false;
            }
        } else if (!version.equals(other.version)) {
            return false;
        }
        return true;
    }

    @Override
    public String toString() {
        StringBuffer stringBuffer = new StringBuffer(groupId).append(':').append(artifactId);
        if (version.isPresent()) {
            stringBuffer.append(version);
        }
        if (classifier.isPresent()) {
            stringBuffer.append(classifier);
        }
        return stringBuffer.toString();
    }

    private MavenGAV(String groupId, String artifactId, Optional<String> version, Optional<String> classifier) {
        super();
        this.groupId = groupId;
        this.artifactId = artifactId;
        this.version = version;
        this.classifier = classifier;
    }

    // as in Guava, of course
    private static <T> T checkNotNull(T reference, String name) {
        if (reference == null) {
            throw new NullPointerException(name + " cannot be null");
        } else {
            return reference;
        }
    }

}
