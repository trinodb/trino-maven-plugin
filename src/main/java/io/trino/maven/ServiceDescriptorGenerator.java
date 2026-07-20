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
package io.trino.maven;

import static io.trino.maven.Utils.parseOutputTimestamp;
import static java.lang.String.join;
import static java.lang.reflect.Modifier.isAbstract;
import static java.lang.reflect.Modifier.isInterface;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.Files.exists;
import static java.nio.file.Files.isDirectory;
import static java.nio.file.Files.isRegularFile;
import static java.nio.file.Files.newOutputStream;
import static java.nio.file.Files.readAllBytes;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.objectweb.asm.ClassReader;

/**
 * Mojo that generates the service descriptor JAR for Trino plugins.
 */
@Mojo(
        name = "generate-service-descriptor",
        defaultPhase = LifecyclePhase.PACKAGE,
        requiresDependencyResolution = ResolutionScope.COMPILE,
        threadSafe = true)
public class ServiceDescriptorGenerator extends AbstractMojo {
    @Parameter(defaultValue = "io.trino.spi.Plugin")
    private String pluginClassName;

    @Parameter(defaultValue = "${project.build.directory}/${project.build.finalName}-services.jar")
    private String servicesJar;

    @Parameter(defaultValue = "${project.build.outputDirectory}/META-INF/services")
    private String servicesDirectory;

    @Parameter(defaultValue = "${project.build.outputDirectory}")
    private String classesDirectory;

    @Parameter(defaultValue = "${project.build.outputTimestamp}")
    private String outputTimestamp;

    @Parameter(defaultValue = "${project}")
    private MavenProject project;

    @Override
    public void execute() throws MojoExecutionException {
        Path servicesFile = Path.of(servicesDirectory, pluginClassName);
        Optional<FileTime> outputTimestamp = parseOutputTimestamp(this.outputTimestamp);
        if (exists(servicesFile)) {
            throw new MojoExecutionException("Existing service descriptor for %s found in output directory.".formatted(pluginClassName));
        }

        List<String> pluginClasses = findPluginImplementations();
        if (pluginClasses.isEmpty()) {
            throw new MojoExecutionException("Trino plugin must contain a class that implements %s.".formatted(pluginClassName));
        }

        if (pluginClasses.size() > 1) {
            throw new MojoExecutionException(
                    "Trino plugin must contain only one class that implements %s, but found: %s"
                            .formatted(pluginClassName, join(", ", pluginClasses)));
        }

        String implementationName = pluginClasses.get(0);
        byte[] servicesFileData = (implementationName + "\n").getBytes(UTF_8);
        try (OutputStream out = newOutputStream(Path.of(servicesJar));
                JarOutputStream jar = new JarOutputStream(out)) {

            JarEntry jarEntry = new JarEntry("META-INF/services/" + pluginClassName);
            outputTimestamp.ifPresent(jarEntry::setLastModifiedTime);
            jar.putNextEntry(jarEntry);
            jar.write(servicesFileData);
            jar.closeEntry();
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to write services JAR file.", e);
        }
        if (getLog().isInfoEnabled()) {
            getLog().info("Wrote %s to %s".formatted(implementationName, servicesJar));
        }
    }

    private List<String> findPluginImplementations() throws MojoExecutionException {
        Path classesRoot = Path.of(classesDirectory);
        if (!isDirectory(classesRoot)) {
            return List.of();
        }

        Map<String, ClassInfo> classInfoMap = scanLocalClasses(classesRoot);
        Set<String> localClasses = Set.copyOf(classInfoMap.keySet());

        // The lists are filled in by the callee so that anything already opened is still closed if it fails part way
        List<Path> dependencyDirectories = new ArrayList<>();
        List<JarFile> dependencyJars = new ArrayList<>();
        try {
            openDependencyArchives(dependencyDirectories, dependencyJars);
            return findConcreteImplementations(localClasses, classInfoMap, dependencyDirectories, dependencyJars);
        }
        catch (IOException e) {
            throw new MojoExecutionException("Could not scan classes", e);
        }
        finally {
            closeAll(dependencyJars);
        }
    }

    /**
     * Reads every compiled class in the output directory, recording its hierarchy straight from the bytecode.
     */
    private static Map<String, ClassInfo> scanLocalClasses(Path classesRoot) throws MojoExecutionException {
        Map<String, ClassInfo> classInfoMap = new HashMap<>();
        try (Stream<Path> paths = Files.walk(classesRoot)) {
            for (Path classFile : paths.filter(path -> path.toString().endsWith(".class")).toList()) {
                ClassReader reader = new ClassReader(readAllBytes(classFile));
                classInfoMap.put(reader.getClassName(), ClassInfo.from(reader));
            }
        }
        catch (IOException e) {
            throw new MojoExecutionException("Could not walk class hierarchy", e);
        }
        return classInfoMap;
    }

    /**
     * Opens the dependency archives once, to be kept open for the duration of the scan. Reactor dependencies are
     * backed by their output directory (e.g. target/classes) rather than a jar, and only real jars can be opened as
     * archives, so pom-type (BOM/aggregator) and other non-jar artifacts are skipped.
     */
    private void openDependencyArchives(List<Path> dependencyDirectories, List<JarFile> dependencyJars)
            throws IOException {
        for (Artifact artifact : project.getArtifacts()) {
            File file = artifact.getFile();
            if (file == null) {
                continue;
            }
            if (file.isDirectory()) {
                dependencyDirectories.add(file.toPath());
            } else if (file.isFile() && "jar".equals(artifact.getType())) {
                dependencyJars.add(new JarFile(file));
            }
        }
    }

    /**
     * Returns the local classes that can be instantiated as the plugin, i.e. those that are neither abstract nor an
     * interface and that reach the plugin interface through their hierarchy.
     */
    private List<String> findConcreteImplementations(
            Set<String> localClasses,
            Map<String, ClassInfo> classInfoMap,
            List<Path> dependencyDirectories,
            List<JarFile> dependencyJars)
            throws IOException {
        String pluginInternalName = pluginClassName.replace('.', '/');
        List<String> implementations = new ArrayList<>();
        for (String className : localClasses) {
            ClassInfo classInfo = classInfoMap.get(className);
            if (isAbstract(classInfo.access) || isInterface(classInfo.access)) {
                continue;
            }
            if (implementsInterface(className, pluginInternalName, classInfoMap, dependencyDirectories, dependencyJars)) {
                implementations.add(className.replace('/', '.'));
            }
        }
        return implementations;
    }

    private static void closeAll(List<JarFile> dependencyJars) {
        for (JarFile jar : dependencyJars) {
            try {
                jar.close();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    private static boolean implementsInterface(
            String className,
            String targetInternalName,
            Map<String, ClassInfo> classInfoMap,
            List<Path> dependencyDirectories,
            List<JarFile> dependencyJars)
            throws IOException {
        Set<String> visited = new HashSet<>();
        Queue<String> pending = new ArrayDeque<>();
        pending.add(className);

        while (!pending.isEmpty()) {
            String currentClassName = pending.poll();
            if (!visited.add(currentClassName)) {
                continue;
            }

            ClassInfo classInfo = classInfoMap.get(currentClassName);
            if (classInfo == null) {
                // Resolve from dependency directories and JARs on demand
                classInfo = resolveFromDependencies(currentClassName, dependencyDirectories, dependencyJars);
                if (classInfo != null) {
                    classInfoMap.put(currentClassName, classInfo);
                } else {
                    continue;
                }
            }

            if (classInfo.superName != null) {
                if (classInfo.superName.equals(targetInternalName)) {
                    return true;
                }
                pending.add(classInfo.superName);
            }
            for (String interfaceName : classInfo.interfaces) {
                if (interfaceName.equals(targetInternalName)) {
                    return true;
                }
                pending.add(interfaceName);
            }
        }
        return false;
    }

    private static ClassInfo resolveFromDependencies(
            String internalName,
            List<Path> dependencyDirectories,
            List<JarFile> dependencyJars)
            throws IOException {
        String entryName = internalName + ".class";
        for (Path directory : dependencyDirectories) {
            Path classFile = directory.resolve(entryName);
            if (isRegularFile(classFile)) {
                return ClassInfo.from(new ClassReader(readAllBytes(classFile)));
            }
        }
        for (JarFile jarFile : dependencyJars) {
            JarEntry entry = jarFile.getJarEntry(entryName);
            if (entry != null) {
                try (InputStream inputStream = jarFile.getInputStream(entry)) {
                    return ClassInfo.from(new ClassReader(inputStream));
                }
            }
        }
        return null;
    }

    private static class ClassInfo {
        final int access;
        final String superName;
        final String[] interfaces;

        static ClassInfo from(ClassReader reader) {
            return new ClassInfo(reader.getAccess(), reader.getSuperName(), reader.getInterfaces());
        }

        ClassInfo(int access, String superName, String[] interfaces) {
            this.access = access;
            this.superName = superName;
            this.interfaces = interfaces;
        }
    }
}
