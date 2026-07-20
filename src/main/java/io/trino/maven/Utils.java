package io.trino.maven;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.MojoExecutionException;
import org.eclipse.aether.artifact.DefaultArtifact;

import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Optional;

import static java.time.ZoneOffset.UTC;
import static java.time.temporal.ChronoUnit.SECONDS;
import static java.util.Objects.requireNonNull;

public class Utils
{
    private static final String ELLIPSIS = "...";
    private static final String GROUP_ID_SEPARATOR = "_";
    private static final int MAX_FILE_NAME_LENGTH = 64;

    private Utils() {}

    static Optional<FileTime> parseOutputTimestamp(String outputTimestamp)
            throws MojoExecutionException
    {
        if (outputTimestamp == null || outputTimestamp.isBlank()) {
            return Optional.empty();
        }

        // A single non-numeric character disables the timestamp; Maven uses this to override an inherited value
        if (outputTimestamp.length() == 1 && !Character.isDigit(outputTimestamp.charAt(0))) {
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

    public static String artifactName(String groupId, String artifactId, String classifier)
    {
        requireNonNull(groupId, "groupId is null");
        requireNonNull(artifactId, "artifactId is null");
        String name = groupId + ":" + artifactId;
        if (classifier != null && !classifier.isEmpty()) {
            return name + ":" + classifier;
        }
        return name;
    }

    public static org.eclipse.aether.artifact.Artifact aetherArtifact(Artifact artifact)
    {
        return new DefaultArtifact(
                artifact.getGroupId(),
                artifact.getArtifactId(),
                artifact.getClassifier(),
                artifact.getType(),
                artifact.getVersion());
    }

    /**
     * Prefixes the resolved file name with its groupId, matching Provisio's "GA" fallback naming. When the combined
     * name would exceed {@value #MAX_FILE_NAME_LENGTH} characters the groupId (or, if even that is not enough, the
     * file name) is abbreviated in the middle with {@value #ELLIPSIS}.
     */
    public static String groupAwareFileName(String groupId, String fileName)
    {
        int remaining = MAX_FILE_NAME_LENGTH - fileName.length() - GROUP_ID_SEPARATOR.length();
        // The groupId prefix only stays within the max length if it either fits whole (remaining >= groupId.length())
        // or there is enough room to abbreviate it in the middle (which needs at least ELLIPSIS + 2 chars). Otherwise
        // abbreviateMiddle would return the groupId unchanged and overflow, so drop the prefix and bound the file name.
        if (remaining < groupId.length() && remaining < ELLIPSIS.length() + 2) {
            return abbreviateMiddle(fileName, ELLIPSIS, MAX_FILE_NAME_LENGTH);
        }
        return abbreviateMiddle(groupId, ELLIPSIS, remaining) + GROUP_ID_SEPARATOR + fileName;
    }

    /**
     * Abbreviates a string to the given length by replacing the middle with {@code middle}, leaving the original
     * unchanged when it already fits. Mirrors Apache Commons Lang {@code StringUtils.abbreviateMiddle}.
     */
    private static String abbreviateMiddle(String value, String middle, int length)
    {
        if (value.isBlank() || length >= value.length() || length < middle.length() + 2) {
            return value;
        }
        int target = length - middle.length();
        int startOffset = target / 2 + target % 2;
        int endOffset = value.length() - target / 2;
        return value.substring(0, startOffset) + middle + value.substring(endOffset);
    }
}
