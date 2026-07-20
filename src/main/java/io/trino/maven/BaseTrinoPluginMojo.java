package io.trino.maven;

import java.util.List;
import javax.inject.Inject;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.repository.RemoteRepository;

/**
 * Base for mojos that resolve artifacts through the Maven repository system, holding the shared project, session and
 * repository-system injection along with convenience accessors for the repository session and remote repositories.
 */
abstract class BaseTrinoPluginMojo extends AbstractMojo {
    @Parameter(defaultValue = "${project}")
    protected MavenProject project;

    @Inject
    protected MavenSession session;

    @Inject
    protected RepositorySystem repositorySystem;

    protected RepositorySystemSession repositorySession() {
        return session.getRepositorySession();
    }

    protected List<RemoteRepository> remoteRepositories() {
        return project.getRemoteProjectRepositories();
    }
}
