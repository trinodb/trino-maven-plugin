package io.trino.maven;

import static java.lang.String.format;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

@Mojo(
        name = "package-trino-plugin",
        defaultPhase = LifecyclePhase.PACKAGE,
        requiresDependencyResolution = ResolutionScope.RUNTIME,
        threadSafe = true)
public class TrinoPluginPackager
        extends AbstractMojo
{
    @Parameter(defaultValue = "${project}")
    private MavenProject project;

    @Parameter(defaultValue = "${project.build.directory}/${project.build.finalName}-services.jar")
    private File servicesJar;

    @Parameter(defaultValue = "${project.build.directory}/${project.build.finalName}.zip")
    private File outputFile;

    @Parameter(defaultValue = "${project.build.outputTimestamp}")
    private String outputTimestamp;

    @Override
    public void execute()
            throws MojoExecutionException
    {
        String prefix = project.getArtifactId() + "-" + project.getVersion() + "/";
        LocalDateTime timestamp = null;
        if (outputTimestamp != null && !outputTimestamp.isBlank()) {
            timestamp = parseOutputTimestamp(outputTimestamp);
        }

        try (OutputStream out = Files.newOutputStream(outputFile.toPath());
                ZipOutputStream zip = new ZipOutputStream(out)) {
            // Add runtime classpath artifacts
            for (Artifact artifact : project.getArtifacts()) {
                if (!Artifact.SCOPE_RUNTIME.equals(artifact.getScope())
                        && !Artifact.SCOPE_COMPILE.equals(artifact.getScope())) {
                    continue;
                }
                if ("pom".equals(artifact.getType())) {
                    continue;
                }
                File file = artifact.getFile();
                if (file == null || !file.isFile()) {
                    continue;
                }
                addFileToZip(zip, prefix + file.getName(), file.toPath(), timestamp);
            }

            // Add main project jar
            Artifact mainArtifact = project.getArtifact();
            if (mainArtifact.getFile() != null && mainArtifact.getFile().isFile()) {
                addFileToZip(zip, prefix + mainArtifact.getFile().getName(), mainArtifact.getFile().toPath(), timestamp);
            }

            // Add services jar
            if (servicesJar.isFile()) {
                addFileToZip(zip, prefix + servicesJar.getName(), servicesJar.toPath(), timestamp);
            }
        }
        catch (IOException e) {
            throw new MojoExecutionException("Failed to create plugin zip.", e);
        }

        project.getArtifact().setFile(outputFile);
        getLog().info(format("Created Trino plugin package: %s", outputFile.getName()));
    }

    private static void addFileToZip(ZipOutputStream zip, String entryName, Path file, LocalDateTime timestamp)
            throws IOException
    {
        ZipEntry entry = new ZipEntry(entryName);
        if (timestamp != null) {
            entry.setTimeLocal(timestamp);
        }
        zip.putNextEntry(entry);
        Files.copy(file, zip);
        zip.closeEntry();
    }

    private static LocalDateTime parseOutputTimestamp(String outputTimestamp)
    {
        try {
            return OffsetDateTime.parse(outputTimestamp)
                    .withOffsetSameInstant(ZoneOffset.UTC)
                    .truncatedTo(ChronoUnit.SECONDS)
                    .toLocalDateTime();
        }
        catch (DateTimeParseException e) {
            throw new IllegalArgumentException(
                    "Invalid project.build.outputTimestamp value '" + outputTimestamp + "'", e);
        }
    }
}
