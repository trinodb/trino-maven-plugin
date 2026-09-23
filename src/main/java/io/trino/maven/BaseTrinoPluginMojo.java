package io.trino.maven;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.collection.DependencyCollectionException;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.DependencyNode;
import org.eclipse.aether.repository.RemoteRepository;

import javax.inject.Inject;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static io.trino.maven.Utils.artifactName;

/**
 * Base for mojos that resolve artifacts through the Maven repository system, holding the shared project, session and
 * repository-system injection along with convenience accessors for the repository session and remote repositories.
 */
abstract class BaseTrinoPluginMojo
        extends AbstractMojo
{
    @Parameter(defaultValue = "${project}")
    protected MavenProject project;

    @Parameter(defaultValue = "io.trino")
    protected String spiGroupId;

    @Parameter(defaultValue = "trino-spi")
    protected String spiArtifactId;

    @Inject
    protected MavenSession session;

    @Inject
    protected RepositorySystem repositorySystem;

    protected RepositorySystemSession repositorySession()
    {
        return session.getRepositorySession();
    }

    protected List<RemoteRepository> remoteRepositories()
    {
        return project.getRemoteProjectRepositories();
    }

    protected boolean isSpiArtifact(String groupId, String artifactId, String type, String classifier)
    {
        return spiGroupId.equals(groupId)
                && spiArtifactId.equals(artifactId)
                && "jar".equals(type)
                && (classifier == null || classifier.isEmpty());
    }

    protected String spiName()
    {
        return spiGroupId + ":" + spiArtifactId;
    }

    /**
     * Returns the names, as produced by {@link Utils#artifactName}, of the non-optional transitive dependencies of the
     * given SPI artifact. The server provides these to every plugin, so they are part of the SPI.
     */
    protected Set<String> spiDependencies(Artifact spiArtifact)
            throws MojoExecutionException
    {
        DependencyNode root;
        try {
            root = repositorySystem.collectDependencies(
                            repositorySession(),
                            new CollectRequest(new Dependency(spiArtifact, null), remoteRepositories()))
                    .getRoot();
        }
        catch (DependencyCollectionException e) {
            throw new MojoExecutionException("Failed to resolve dependencies.", e);
        }

        Set<String> spiDependencies = new HashSet<>();
        for (DependencyNode child : root.getChildren()) {
            collectSpiDependencies(child, spiDependencies);
        }
        return spiDependencies;
    }

    private static void collectSpiDependencies(DependencyNode node, Set<String> spiDependencies)
    {
        if (node.getDependency().isOptional()) {
            return;
        }
        // The set doubles as the visited set: a repeated node means a diamond or a cycle in the graph
        if (!spiDependencies.add(artifactName(node.getArtifact()))) {
            return;
        }
        for (DependencyNode child : node.getChildren()) {
            collectSpiDependencies(child, spiDependencies);
        }
    }
}
