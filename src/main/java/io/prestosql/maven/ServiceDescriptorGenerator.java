/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.prestosql.maven;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.FileUtils;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static java.lang.String.format;
import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Mojo that generates the default service descriptor for Presto plugins.
 *
 * @author Jason van Zyl
 */
@Mojo(name = "generate-service-descriptor", defaultPhase = LifecyclePhase.PACKAGE, requiresDependencyResolution = ResolutionScope.COMPILE)
public class ServiceDescriptorGenerator
        extends AbstractMojo
{
    private static final String LS = System.getProperty("line.separator");

    @Parameter(defaultValue = "com.facebook.presto.spi.Plugin")
    private String pluginClassName;

    @Parameter(defaultValue = "${project.build.outputDirectory}/META-INF/services")
    private File servicesDirectory;

    @Parameter(defaultValue = "${project.build.outputDirectory}")
    private File classesDirectory;

    @Parameter(defaultValue = "${project}")
    private MavenProject project;

    @Override
    public void execute()
            throws MojoExecutionException
    {
        File servicesFile = new File(servicesDirectory, pluginClassName);

        // If users have already provided their own service file then we will not overwrite it
        if (servicesFile.exists()) {
            return;
        }

        if (!servicesFile.getParentFile().exists()) {
            mkdirs(servicesFile.getParentFile());
        }

        List<Class<?>> pluginClasses;
        try {
            URLClassLoader loader = createClassloaderFromCompileTimeDependencies();
            pluginClasses = findPluginImplementations(loader);
        }
        catch (Exception e) {
            throw new MojoExecutionException(format("%n%nError scanning for classes implementing %s.", pluginClassName), e);
        }
        if (pluginClasses.isEmpty()) {
            throw new MojoExecutionException(format("%n%nYou must have at least one class that implements %s.", pluginClassName));
        }

        if (pluginClasses.size() > 1) {
            StringBuilder sb = new StringBuilder();
            for (Class<?> pluginClass : pluginClasses) {
                sb.append(pluginClass.getName()).append(LS);
            }
            throw new MojoExecutionException(format("%n%nYou have more than one class that implements %s:%n%n%s%nYou can only have one per plugin project.", pluginClassName, sb));
        }

        try {
            Class<?> pluginClass = pluginClasses.get(0);
            Files.write(servicesFile.toPath(), pluginClass.getName().getBytes(UTF_8));
            getLog().info(format("Wrote %s to %s", pluginClass.getName(), servicesFile));
        }
        catch (IOException e) {
            throw new MojoExecutionException("Failed to write service descriptor.", e);
        }
    }

    private URLClassLoader createClassloaderFromCompileTimeDependencies()
            throws Exception
    {
        List<URL> urls = new ArrayList<>();
        urls.add(classesDirectory.toURI().toURL());
        for (Artifact artifact : project.getArtifacts()) {
            if (artifact.getFile() != null) {
                urls.add(artifact.getFile().toURI().toURL());
            }
        }
        return new URLClassLoader(urls.toArray(new URL[0]));
    }

    private List<Class<?>> findPluginImplementations(URLClassLoader searchRealm)
            throws IOException, MojoExecutionException
    {
        List<Class<?>> implementations = new ArrayList<>();
        List<String> classes = FileUtils.getFileNames(classesDirectory, "**/*.class", null, false);
        for (String classPath : classes) {
            String className = classPath.substring(0, classPath.length() - 6).replace('/', '.');
            try {
                Class<?> implementation = searchRealm.loadClass(pluginClassName);
                Class<?> clazz = searchRealm.loadClass(className);
                if (implementation.isAssignableFrom(clazz)) {
                    implementations.add(clazz);
                }
            }
            catch (ClassNotFoundException e) {
                throw new MojoExecutionException("Failed to load class.", e);
            }
        }
        return implementations;
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    private static void mkdirs(File file)
            throws MojoExecutionException
    {
        file.mkdirs();
        if (!file.isDirectory()) {
            throw new MojoExecutionException(format("%n%nFailed to create directory: %s", file));
        }
    }
}
