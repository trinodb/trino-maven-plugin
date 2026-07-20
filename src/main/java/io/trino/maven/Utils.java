package io.trino.maven;

import static java.time.ZoneOffset.UTC;
import static java.time.temporal.ChronoUnit.SECONDS;
import static java.util.Objects.requireNonNull;

import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.MojoExecutionException;
import org.eclipse.aether.artifact.DefaultArtifact;

public class Utils
{
    static Optional<FileTime> parseOutputTimestamp(String outputTimestamp)
            throws MojoExecutionException
    {
        if (outputTimestamp == null || outputTimestamp.isBlank()) {
            return Optional.empty();
        }

        try {
            return Optional.of(FileTime.from(OffsetDateTime.parse(outputTimestamp)
                    .withOffsetSameInstant(UTC)
                    .truncatedTo(SECONDS)
                    .toLocalDateTime()
                    .toInstant(UTC)));
        }
        catch (DateTimeParseException e) {
            // project.build.outputTimestamp may also be given as an integer of seconds since the epoch
            try {
                return Optional.of(FileTime.from(Instant.ofEpochSecond(Long.parseLong(outputTimestamp.trim()))));
            }
            catch (NumberFormatException ignored) {
                throw new MojoExecutionException(
                        "Invalid project.build.outputTimestamp value '" + outputTimestamp + "'", e);
            }
        }
    }

    public static String artifactName(org.eclipse.aether.artifact.Artifact artifact)
    {
        return artifactName(artifact.getGroupId(), artifact.getArtifactId(), artifact.getClassifier());
    }

    public static String artifactName(String groupId, String artifactId, String classifier) {
        requireNonNull(groupId, "groupId is null");
        requireNonNull(artifactId, "artifactId is null");
        String name = groupId + ":" + artifactId;
        if (classifier != null && !classifier.isEmpty()) {
            return name + ":" + classifier;
        }
        return name;
    }

    public static org.eclipse.aether.artifact.Artifact aetherArtifact(Artifact artifact) {
        return new DefaultArtifact(
                artifact.getGroupId(),
                artifact.getArtifactId(),
                artifact.getClassifier(),
                artifact.getType(),
                artifact.getVersion());
    }

}
