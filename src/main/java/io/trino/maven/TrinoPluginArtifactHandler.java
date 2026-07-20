package io.trino.maven;

import org.apache.maven.artifact.handler.DefaultArtifactHandler;

import javax.inject.Named;
import javax.inject.Singleton;

@Named("trino-plugin")
@Singleton
public class TrinoPluginArtifactHandler
        extends DefaultArtifactHandler
{
    public TrinoPluginArtifactHandler()
    {
        super("trino-plugin");
        setExtension("zip");
        setLanguage("java");
        setAddedToClasspath(false);
    }
}
