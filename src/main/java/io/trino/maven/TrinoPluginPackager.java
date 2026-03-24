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
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.zip.CRC32;
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

        // entryName -> filePath
        List<Map.Entry<String, Path>> filesToAdd = new ArrayList<>();

        // Collect runtime classpath artifacts
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
            filesToAdd.add(new AbstractMap.SimpleEntry<>(prefix + file.getName(), file.toPath()));
        }

        // Add main project jar
        Artifact mainArtifact = project.getArtifact();
        if (mainArtifact.getFile() != null && mainArtifact.getFile().isFile()) {
            filesToAdd.add(new AbstractMap.SimpleEntry<>(prefix + mainArtifact.getFile().getName(), mainArtifact.getFile().toPath()));
        }

        // Add services jar
        if (servicesJar.isFile()) {
            filesToAdd.add(new AbstractMap.SimpleEntry<>(prefix + servicesJar.getName(), servicesJar.toPath()));
        }

        // Read files and compute CRC32 checksums in parallel for STORED entries
        LocalDateTime finalTimestamp = timestamp;
        List<CompletableFuture<byte[]>> futures = new ArrayList<>();
        for (Map.Entry<String, Path> entry : filesToAdd) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    return Files.readAllBytes(entry.getValue());
                }
                catch (IOException e) {
                    throw new RuntimeException("Failed to read file: " + entry.getValue(), e);
                }
            }));
        }

        try (OutputStream out = Files.newOutputStream(outputFile.toPath());
                ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.setMethod(ZipOutputStream.STORED);
            for (int i = 0; i < filesToAdd.size(); i++) {
                byte[] data = futures.get(i).get();
                ZipEntry zipEntry = prepareStoredEntry(filesToAdd.get(i).getKey(), data, finalTimestamp);
                zip.putNextEntry(zipEntry);
                zip.write(data);
                zip.closeEntry();
            }
        }
        catch (IOException e) {
            throw new MojoExecutionException("Failed to create plugin zip.", e);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MojoExecutionException("Interrupted while creating plugin zip.", e);
        }
        catch (ExecutionException e) {
            throw new MojoExecutionException("Failed to prepare zip entry.", e.getCause());
        }

        project.getArtifact().setFile(outputFile);
        getLog().info(format("Created Trino plugin package: %s", outputFile.getName()));
    }

    private static ZipEntry prepareStoredEntry(String entryName, byte[] data, LocalDateTime timestamp)
    {
        CRC32 crc = new CRC32();
        crc.update(data);

        ZipEntry entry = new ZipEntry(entryName);
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(data.length);
        entry.setCompressedSize(data.length);
        entry.setCrc(crc.getValue());
        if (timestamp != null) {
            entry.setTimeLocal(timestamp);
        }
        return entry;
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
