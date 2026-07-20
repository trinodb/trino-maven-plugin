package io.trino.maven;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProjectHelper;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.DependencyFilter;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.resolution.DependencyResolutionException;
import org.eclipse.aether.util.filter.ScopeDependencyFilter;

import javax.inject.Inject;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.zip.CRC32;
import java.util.zip.CheckedInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static io.trino.maven.Utils.groupAwareFileName;
import static io.trino.maven.Utils.parseOutputTimestamp;
import static java.io.OutputStream.nullOutputStream;
import static java.nio.file.Files.copy;
import static java.nio.file.Files.newInputStream;
import static java.nio.file.Files.newOutputStream;
import static java.nio.file.Files.setLastModifiedTime;
import static java.nio.file.Files.size;
import static java.util.Map.entry;
import static java.util.stream.Collectors.toList;
import static org.apache.maven.RepositoryUtils.toArtifact;
import static org.apache.maven.RepositoryUtils.toDependency;
import static org.eclipse.aether.util.artifact.JavaScopes.PROVIDED;
import static org.eclipse.aether.util.artifact.JavaScopes.SYSTEM;
import static org.eclipse.aether.util.artifact.JavaScopes.TEST;

@Mojo(name = "package-trino-plugin",
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

    @Parameter(property = "skipPackageTrinoPlugin", defaultValue = "false")
    private boolean skipPackageTrinoPlugin;

    @Inject
    private MavenProjectHelper projectHelper;

    @Override
    public void execute()
            throws MojoExecutionException
    {
        if (skipPackageTrinoPlugin) {
            getLog().info("Skipping Trino plugin packaging");
            return;
        }

        String prefix = project.getArtifactId() + "-" + project.getVersion() + "/";
        Optional<FileTime> timestamp = parseOutputTimestamp(outputTimestamp);
        File projectJar = project.getArtifact().getFile();
        writeBundle(collectBundleEntries(prefix), timestamp);

        // Set the timestamp on the archive file itself for reproducible builds
        if (timestamp.isPresent()) {
            try {
                setLastModifiedTime(outputFile.toPath(), timestamp.orElseThrow());
            }
            catch (IOException e) {
                throw new MojoExecutionException("Failed to set timestamp on plugin zip.", e);
            }
        }

        // The trino-plugin artifact handler uses the zip extension, so the main artifact is the plugin bundle. Attach
        // the classes jar as an additional artifact so it is installed and deployed too: plugin modules are legitimately
        // compile dependencies (with the default jar type) of other modules.
        projectHelper.attachArtifact(project, "jar", projectJar);

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
            // Prefix each dependency with its groupId (Provisio's "GA" naming) so two different groupIds that share
            // the same artifactId and version do not collide on a single flat file name.
            String entryName = prefix + groupAwareFileName(artifact.getGroupId(), file.getName());
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
        try (OutputStream out = new BufferedOutputStream(newOutputStream(outputFile.toPath()));
                ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.setMethod(ZipOutputStream.STORED);
            for (Entry<String, Path> file : filesToAdd) {
                zip.putNextEntry(storedEntry(file.getKey(), file.getValue(), timestamp));
                copy(file.getValue(), zip);
                zip.closeEntry();
            }
        }
        catch (IOException e) {
            throw new MojoExecutionException("Failed to create plugin zip.", e);
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

    /**
     * Builds a {@code STORED} (uncompressed) entry. The zip format requires the size and CRC of a stored entry to be
     * known before its data is written, so the file is streamed once here to checksum it and once again by the caller
     * to copy it. Streaming twice keeps memory flat no matter how large the bundle is; the jars are already compressed,
     * so storing them verbatim avoids pointlessly recompressing them.
     */
    private static ZipEntry storedEntry(String entryName, Path file, Optional<FileTime> fileTime)
            throws IOException
    {
        long fileSize = size(file);
        ZipEntry entry = new ZipEntry(entryName);
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(fileSize);
        entry.setCompressedSize(fileSize);
        entry.setCrc(checksum(file));
        fileTime.ifPresent(entry::setLastModifiedTime);
        return entry;
    }

    private static long checksum(Path file)
            throws IOException
    {
        try (CheckedInputStream in = new CheckedInputStream(newInputStream(file), new CRC32())) {
            in.transferTo(nullOutputStream());
            return in.getChecksum().getValue();
        }
    }
}
