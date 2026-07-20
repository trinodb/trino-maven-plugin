package io.trino.maven;

import static io.trino.maven.Utils.aetherArtifact;
import static io.trino.maven.Utils.artifactName;

import java.util.HashSet;
import java.util.Set;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.collection.CollectResult;
import org.eclipse.aether.collection.DependencyCollectionException;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.DependencyNode;

@Mojo(
        name = "check-spi-dependencies",
        defaultPhase = LifecyclePhase.VALIDATE,
        requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME,
        threadSafe = true)
public class TrinoPluginDependencyChecker extends BaseTrinoPluginMojo {
    @Parameter(defaultValue = "io.trino")
    private String spiGroupId;

    @Parameter(defaultValue = "trino-spi")
    private String spiArtifactId;

    @Parameter(defaultValue = "false")
    private boolean skipCheckSpiDependencies;

    @Parameter
    private final Set<String> allowedProvidedDependencies = new HashSet<>();

    @Override
    public void execute() throws MojoExecutionException {
        if (skipCheckSpiDependencies) {
            getLog().info("Skipping SPI dependency checks");
            return;
        }

        Set<String> spiDependencies = getSpiDependencies();
        if (getLog().isDebugEnabled()) {
            getLog().debug("SPI dependencies: " + spiDependencies);
        }

        for (Artifact artifact : project.getArtifacts()) {
            checkArtifact(artifact, spiDependencies);
        }
    }

    private void checkArtifact(Artifact artifact, Set<String> spiDependencies) throws MojoExecutionException {
        if (isSpiArtifact(artifact)) {
            return;
        }
        String dependencyName = artifactName(aetherArtifact(artifact));

        if (spiDependencies.contains(dependencyName)) {
            if (!"jar".equals(artifact.getType())) {
                throw new MojoExecutionException("Trino plugin dependency %s must have type 'jar'.".formatted(dependencyName));
            }
            if (!"provided".equals(artifact.getScope())) {
                throw new MojoExecutionException("Trino plugin dependency %s must have scope 'provided'. It is part of the SPI and will be provided at runtime.".formatted(dependencyName));
            }
            return;
        }

        if ("io.trino".equals(artifact.getGroupId()) && "trino-main".equals(artifact.getArtifactId()) && !"test".equals(artifact.getScope())) {
            throw new MojoExecutionException("Trino plugin dependency %s must have scope 'test'. It must not be on the plugin classpath.".formatted(dependencyName));
        }

        if ("provided".equals(artifact.getScope()) && !allowedProvidedDependencies.contains(dependencyName)) {
            throw new MojoExecutionException("Trino plugin dependency %s must not have scope 'provided'. It is not part of the SPI and will not be available at runtime.".formatted(dependencyName));
        }
    }

    private Set<String> getSpiDependencies() throws MojoExecutionException {
        Set<String> spiDependencies = new HashSet<>();
        for (DependencyNode child : getArtifactDependencies(getSpiDependency()).getRoot().getChildren()) {
            collectSpiDependencies(child, spiDependencies);
        }
        return spiDependencies;
    }

    private static void collectSpiDependencies(DependencyNode node, Set<String> spiDependencies) {
        if (node.getDependency().isOptional()) {
            return;
        }
        // The set doubles as the visited set: a repeated node means a diamond or a cycle in the graph
        if (!spiDependencies.add(Utils.artifactName(node.getArtifact()))) {
            return;
        }
        for (DependencyNode child : node.getChildren()) {
            collectSpiDependencies(child, spiDependencies);
        }
    }

    private CollectResult getArtifactDependencies(Artifact artifact) throws MojoExecutionException {
        try {
            Dependency dependency = new Dependency(aetherArtifact(artifact), null);
            return repositorySystem.collectDependencies(
                    repositorySession(),
                    new CollectRequest(dependency, remoteRepositories()));
        } catch (DependencyCollectionException e) {
            throw new MojoExecutionException("Failed to resolve dependencies.", e);
        }
    }

    private Artifact getSpiDependency() throws MojoExecutionException {
        for (Artifact artifact : project.getArtifacts()) {
            if (isSpiArtifact(artifact)) {
                if (!"provided".equals(artifact.getScope())) {
                    throw new MojoExecutionException("Trino plugin dependency %s must have scope 'provided'.".formatted(spiName()));
                }
                return artifact;
            }
        }
        throw new MojoExecutionException("Trino plugin must depend on %s.".formatted(spiName()));
    }

    private boolean isSpiArtifact(Artifact artifact) {
        return spiGroupId.equals(artifact.getGroupId())
                && spiArtifactId.equals(artifact.getArtifactId())
                && "jar".equals(artifact.getType())
                && artifact.getClassifier() == null;
    }

    private String spiName() {
        return spiGroupId + ":" + spiArtifactId;
    }
}
