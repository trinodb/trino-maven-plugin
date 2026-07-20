package io.trino.maven;

import static io.trino.maven.Utils.parseOutputTimestamp;
import static java.nio.file.Files.newOutputStream;
import static java.nio.file.Files.readAllBytes;
import static java.nio.file.Files.setLastModifiedTime;
import static java.util.Map.entry;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.CompletableFuture.supplyAsync;
import static java.util.stream.Collectors.toList;
import static org.apache.maven.RepositoryUtils.toArtifact;
import static org.apache.maven.RepositoryUtils.toDependency;
import static org.eclipse.aether.util.artifact.JavaScopes.PROVIDED;
import static org.eclipse.aether.util.artifact.JavaScopes.SYSTEM;
import static org.eclipse.aether.util.artifact.JavaScopes.TEST;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.DependencyFilter;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.resolution.DependencyResolutionException;
import org.eclipse.aether.util.filter.ScopeDependencyFilter;

@Mojo(
        name = "package-trino-plugin",
        defaultPhase = LifecyclePhase.PACKAGE,
        requiresDependencyResolution = ResolutionScope.RUNTIME,
        threadSafe = true)
public class TrinoPluginPackager
        extends BaseTrinoPluginMojo
{
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
        Optional<FileTime> timestamp = parseOutputTimestamp(outputTimestamp);
        writeBundle(collectBundleEntries(prefix), timestamp);

        // Set the timestamp on the archive file itself for reproducible builds
        if (timestamp.isPresent()) {
            try {
                setLastModifiedTime(outputFile.toPath(), timestamp.get());
            }
            catch (IOException e) {
                throw new MojoExecutionException("Failed to set timestamp on plugin zip.", e);
            }
        }

        project.getArtifact().setFile(outputFile);
        if (getLog().isInfoEnabled()) {
            getLog().info("Created Trino plugin package: %s".formatted(outputFile.getName()));
        }
    }

    /**
     * Collects the files to bundle, keyed by their flat entry name: the runtime classpath jars plus the main project
     * jar and the generated services jar.
     */
    private List<Entry<String, Path>> collectBundleEntries(String prefix)
            throws MojoExecutionException
    {
        // entryName -> filePath
        List<Entry<String, Path>> filesToAdd = new ArrayList<>();

        // Collect runtime classpath artifacts (same logic as Provisio's getRuntimeClasspathAsArtifactSet)
        Map<String, org.eclipse.aether.artifact.Artifact> seenEntries = new HashMap<>();
        for (org.eclipse.aether.artifact.Artifact artifact : resolveRuntimeScopeTransitively()) {
            // Skip pom-type artifacts (e.g. aggregator/BOM dependencies); only real classpath jars belong in the bundle
            if ("pom".equals(artifact.getExtension())) {
                continue;
            }
            File file = artifact.getFile();
            if (file == null || !file.isFile()) {
                throw new MojoExecutionException(
                        "Runtime dependency %s has no resolved file; the plugin bundle would be incomplete.".formatted(artifact));
            }
            String entryName = prefix + file.getName();
            // The flat bundle layout keys entries by file name, so guard against the same file being added twice
            org.eclipse.aether.artifact.Artifact previous = seenEntries.putIfAbsent(entryName, artifact);
            if (previous != null) {
                if (!previous.equals(artifact)) {
                    throw new MojoExecutionException(
                            "Plugin bundle entry name '%s' is produced by two different dependencies: %s and %s.".formatted(entryName, previous, artifact));
                }
                continue;
            }
            filesToAdd.add(entry(entryName, file.toPath()));
        }

        // Add main project jar
        Artifact mainArtifact = project.getArtifact();
        if (mainArtifact.getFile() == null || !mainArtifact.getFile().isFile()) {
            throw new MojoExecutionException(
                    "Main project artifact JAR is missing; ensure maven-jar-plugin:jar runs before package-trino-plugin.");
        }
        filesToAdd.add(entry(prefix + mainArtifact.getFile().getName(), mainArtifact.getFile().toPath()));

        // Add services jar
        if (!servicesJar.isFile()) {
            throw new MojoExecutionException(
                    "Services JAR is missing; run generate-service-descriptor before package-trino-plugin.");
        }
        filesToAdd.add(entry(prefix + servicesJar.getName(), servicesJar.toPath()));

        return filesToAdd;
    }

    private void writeBundle(List<Entry<String, Path>> filesToAdd, Optional<FileTime> timestamp)
            throws MojoExecutionException
    {
        List<CompletableFuture<PreparedEntry>> futures = new ArrayList<>();
        for (Entry<String, Path> entry : filesToAdd) {
            futures.add(supplyAsync(() -> {
                try {
                    byte[] data = readAllBytes(entry.getValue());
                    return new PreparedEntry(prepareStoredEntry(entry.getKey(), data, timestamp), data);
                }
                catch (IOException e) {
                    throw new UncheckedIOException("Failed to read file: " + entry.getValue(), e);
                }
            }));
        }

        try (OutputStream out = new BufferedOutputStream(newOutputStream(outputFile.toPath()));
             ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.setMethod(ZipOutputStream.STORED);
            for (int i = 0; i < futures.size(); i++) {
                // Clear the slot so the data can be garbage collected once written
                PreparedEntry prepared = futures.set(i, null).get();
                zip.putNextEntry(prepared.entry);
                zip.write(prepared.data);
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
    }

    /**
     * Resolves the runtime classpath transitively instead of relying on the resolution scope requested by this Mojo.
     * This works around <a href="https://issues.apache.org/jira/browse/MNG-8041">MNG-8041</a>, where a dependency
     * declared directly with {@code test} (or {@code provided}) scope masks the same artifact required transitively at
     * runtime (for example {@code io.airlift:log}, needed via {@code io.airlift:bootstrap}). Dropping the test-scoped
     * direct dependencies before collection lets the surviving compile/runtime path be included in the bundle.
     */
    private List<org.eclipse.aether.artifact.Artifact> resolveRuntimeScopeTransitively()
            throws MojoExecutionException
    {
        RepositorySystemSession repositorySession = repositorySession();
        DependencyFilter runtimeFilter = new ScopeDependencyFilter(SYSTEM, PROVIDED, TEST);
        List<Dependency> dependencies = new ArrayList<>();
        for (org.apache.maven.model.Dependency dependency : project.getDependencies()) {
            String scope = dependency.getScope();
            // Drop non-runtime direct dependencies before collection. Leaving provided/system-scoped direct
            // declarations in lets conflict mediation prefer the wrong artifact before the ScopeDependencyFilter runs.
            if (TEST.equals(scope) || PROVIDED.equals(scope) || SYSTEM.equals(scope)) {
                continue;
            }
            dependencies.add(toDependency(dependency, repositorySession.getArtifactTypeRegistry()));
        }

        List<Dependency> managedDependencies = new ArrayList<>();
        if (project.getDependencyManagement() != null) {
            for (org.apache.maven.model.Dependency dependency : project.getDependencyManagement().getDependencies()) {
                managedDependencies.add(toDependency(dependency, repositorySession.getArtifactTypeRegistry()));
            }
        }

        CollectRequest collectRequest = new CollectRequest();
        collectRequest.setRootArtifact(toArtifact(project.getArtifact()));
        collectRequest.setRepositories(remoteRepositories());
        collectRequest.setDependencies(dependencies);
        collectRequest.setManagedDependencies(managedDependencies);

        DependencyRequest dependencyRequest = new DependencyRequest(collectRequest, runtimeFilter);
        try {
            return repositorySystem.resolveDependencies(repositorySession, dependencyRequest)
                    .getArtifactResults()
                    .stream()
                    .map(ArtifactResult::getArtifact)
                    .collect(toList());
        }
        catch (DependencyResolutionException e) {
            throw new MojoExecutionException("Failed to resolve runtime dependencies.", e);
        }
    }

    private static ZipEntry prepareStoredEntry(String entryName, byte[] data, Optional<FileTime> fileTime)
    {
        CRC32 crc = new CRC32();
        crc.update(data);

        ZipEntry entry = new ZipEntry(entryName);
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(data.length);
        entry.setCompressedSize(data.length);
        entry.setCrc(crc.getValue());
        fileTime.ifPresent(entry::setLastModifiedTime);
        return entry;
    }

    private record PreparedEntry(ZipEntry entry, byte[] data)
    {
        public PreparedEntry
        {
            requireNonNull(entry, "entry is null");
        }
    }
}
