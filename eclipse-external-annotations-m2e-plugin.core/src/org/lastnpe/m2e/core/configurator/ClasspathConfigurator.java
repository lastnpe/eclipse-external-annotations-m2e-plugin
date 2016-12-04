package org.lastnpe.m2e.core.configurator;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Plugin;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.m2e.core.project.IMavenProjectFacade;
import org.eclipse.m2e.core.project.MavenProjectChangedEvent;
import org.eclipse.m2e.core.project.configurator.AbstractProjectConfigurator;
import org.eclipse.m2e.core.project.configurator.ProjectConfigurationRequest;
import org.eclipse.m2e.jdt.IClasspathDescriptor;
import org.eclipse.m2e.jdt.IClasspathEntryDescriptor;
import org.eclipse.m2e.jdt.IJavaProjectConfigurator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * M2E Project configurator to set external null annotations.
 *
 * @author Sylvain LAURENT - original impl: Single EEA for all containers
 * @author Michael Vorburger - support multiple individual EEA JARs, per M2E
 *         entry and JRE; plus set compiler properties from POM
 */
public class ClasspathConfigurator extends AbstractProjectConfigurator implements IJavaProjectConfigurator {

    private static final Pattern NEWLINE_REGEXP = Pattern.compile("\\n");
    private static final String JRE_CONTAINER = "org.eclipse.jdt.launching.JRE_CONTAINER";
    private static final MavenGAV JAVA_GAV = MavenGAV.of("java", "java");
    private static final String EEA_FOR_GAV_FILENAME = "eea-for-gav";
    private static final String M2E_JDT_ANNOTATIONPATH = "m2e.jdt.annotationpath";

    private final static Logger LOGGER = LoggerFactory.getLogger(ClasspathConfigurator.class);

    @Override
    public void configureClasspath(IMavenProjectFacade mavenProjectFacade, IClasspathDescriptor classpath,
            IProgressMonitor monitor) throws CoreException {
        List<IPath> classpathEntryPaths = classpath.getEntryDescriptors().stream().map(cpEntry -> cpEntry.getPath()).collect(Collectors.toList());
        Map<MavenGAV, IPath> mapping = getExternalAnnotationMapping(classpathEntryPaths);
        for (IClasspathEntryDescriptor cpEntry : classpath.getEntryDescriptors()) {
            mapping.entrySet().stream()
                .filter(e -> e.getKey().matches(cpEntry.getArtifactKey())).findFirst()
                .ifPresent(e -> {
                    setExternalAnnotationsPath(cpEntry, e.getValue().toString());
                 });
        }
        // Do *NOT* configure the JRE's EEA here, but in configureRawClasspath(),
        // because it's the wrong time for M2E (and will break project import,
        // when the IProject doesn't fully exist in JDT yet at this stage).
    }

    private void setExternalAnnotationsPath(IClasspathEntryDescriptor cpEntry, String path) {
        cpEntry.setClasspathAttribute("annotationpath", path);
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info("Setting External Annotations of {} to {}", toString(cpEntry), path);
        }
    }

    private Map<MavenGAV, IPath> getExternalAnnotationMapping(List<IPath> classpathEntryPaths) {
        Map<MavenGAV, IPath> mapping = new HashMap<>();
        for (IPath cpEntryPath : classpathEntryPaths) {
            Optional<File> optionalFileOrDirectory = toFile(cpEntryPath);
            optionalFileOrDirectory.ifPresent(fileOrDirectory -> {
                getExternalAnnotationMapping(fileOrDirectory).forEach(gav -> mapping.put(gav, cpEntryPath));
            });
        }
        return mapping;
    }

    private List<MavenGAV> getExternalAnnotationMapping(File dependency) {
        List<String> gavLines = readLines(dependency, EEA_FOR_GAV_FILENAME);
        List<MavenGAV> result = new ArrayList<>(gavLines.size());
        gavLines.forEach(line -> {
            try {
                MavenGAV gav = MavenGAV.parse(line);
                LOGGER.info("Found EEA for {} in {}", gav, dependency);
                result.add(gav);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Bad line in " + EEA_FOR_GAV_FILENAME + " of " + dependency + ": " + line, e);
            }
        });
        return result;
    }

    private List<String> readLines(File fileOrDirectory, String fileName) {
        Optional<String> fileContent = read(fileOrDirectory, fileName);
        if (fileContent.isPresent()) {
            return readLines(fileContent.get());
        } else {
            return Collections.emptyList();
        }
    }

    private Optional<File> toFile(IPath iPath) {
        // iPath.toFile() is NOT what we want..
        // https://wiki.eclipse.org/FAQ_What_is_the_difference_between_a_path_and_a_location%3F
        IWorkspace workspace = ResourcesPlugin.getWorkspace();
        IWorkspaceRoot root = workspace.getRoot();
        IResource resource = root.findMember(iPath);
        if (resource != null) {
            IPath location = resource.getLocation();
            return Optional.ofNullable(location.toFile());
        } else {
            // In this case iPath likely was an absolute FS / non-WS path to being with (or a closed project), so try:
            return Optional.ofNullable(iPath.toFile());
        }
    }

    /**
     * Reads content of a name file from either inside a JAR or a directory
     * @param fileOrDirectory either a ZIP/JAR file, or a directory
     * @param fileName file to look for in that ZIP/JAR file or directory
     * @return content of file, if any
     */
    private Optional<String> read(File fileOrDirectory, String fileName) {
        if (!fileOrDirectory.exists()) {
            LOGGER.error("File does not exist: {}", fileOrDirectory);
            return Optional.empty();
        }
        if (fileOrDirectory.isDirectory()) {
            File file = new File(fileOrDirectory, fileName);
            return readFile(file.toPath());
        } else if (fileOrDirectory.isFile()) {
            Path jarFilePath = Paths.get(fileOrDirectory.toURI());
            URI jarEntryURI = URI.create("jar:file:" + jarFilePath.toUri().getPath() + "!/" + fileName);
            try (FileSystem zipfs = FileSystems.newFileSystem(jarEntryURI, Collections.emptyMap())) {
                Path jarEntryPath = Paths.get(jarEntryURI);
                return readFile(jarEntryPath);
            } catch (IOException e) {
                LOGGER.error("IOException from ZipFileSystemProvider for: {}", jarEntryURI, e);
                return Optional.empty();
            }
        } else {
            LOGGER.error("File is neither a directory nor a file: {}", fileOrDirectory);
            return Optional.empty();
        }

    }

    private Optional<String> readFile(Path path) {
        try {
            if (Files.exists(path)) {
                return Optional.of(new String(Files.readAllBytes(path), Charset.forName("UTF-8")));
            } else {
                return Optional.empty();
            }
        } catch (IOException e) {
            LOGGER.error("IOException from Files.readAllBytes for: {}", path, e);
            return Optional.empty();
        }
    }

    private List<String> readLines(String string) {
        return NEWLINE_REGEXP.splitAsStream(string)
                .map(t -> t.trim())
                .filter(t -> !t.isEmpty())
                .filter(t -> !t.startsWith("#"))
                .collect(Collectors.toList());
    }

    @Override
    public void configureRawClasspath(ProjectConfigurationRequest request, IClasspathDescriptor classpath,
            IProgressMonitor monitor) throws CoreException {
        String annotationPath = getSingleProjectWideAnnotationPath(request.getMavenProjectFacade());
        if (annotationPath != null && !annotationPath.isEmpty()) {
            setContainerClasspathExternalAnnotationsPath(classpath, annotationPath, false);
        } else {
            // Find the JRE's EEA among the dependencies to set it....
            // Note that at this stage of M2E we have to use the Maven project dependencies,
            // and cannot rely on the dependencies already being on the IClasspathDescriptor
            // (that happens in configureClasspath() not here in configureRawClasspath())
            //
            final MavenProject mavenProject = request.getMavenProject();
            for (Dependency dependency : mavenProject.getDependencies()) {
                // Filter by "*-eea" artifactId naming convention, just for performance
                if (!dependency.getArtifactId().endsWith("-eea")) {
                    continue;
                }
                Artifact artifact = maven.resolve(dependency.getGroupId(), dependency.getArtifactId(), dependency.getVersion(),
                        dependency.getType(), dependency.getClassifier(), mavenProject.getRemoteArtifactRepositories(),
                        monitor);
                if (artifact != null && artifact.isResolved()) {
                    final File eeaProjectOrJarFile = artifact.getFile();
                    if (getExternalAnnotationMapping(eeaProjectOrJarFile).contains(JAVA_GAV)) {
                        IPath iPath = getProjectPathFromAbsoluteLocationIfPossible(eeaProjectOrJarFile);
                        setContainerClasspathExternalAnnotationsPath(classpath, iPath.toString(), true);
                        return;
                    }
                }
            }
        }
    }

    /**
     * Attempt to convert an absolute File to a workspace relative project patch.
     * @param file a File pointing either to a JAR file in the Maven repo, or a project on disk
     * @return IPath which will either be workspace relative if match found, else same location (absolute)
     */
    private IPath getProjectPathFromAbsoluteLocationIfPossible(File file) {
        Path targetClassesPath = Paths.get("target", "classes");
        if (file.toPath().endsWith(targetClassesPath)) {
            file = file.getParentFile().getParentFile();
        }
        URI fileURI = file.toURI().normalize();
        IProject[] projects = ResourcesPlugin.getWorkspace().getRoot().getProjects();
        for (IProject project : projects) {
            if (!project.isOpen()) {
                continue;
            }
            URI locationURI = project.getLocationURI().normalize();
            // The follow circus is because of a trailing slash difference:
            if (Paths.get(locationURI).equals(Paths.get(fileURI))) {
                return project.getFullPath();
            }
        }
        return org.eclipse.core.runtime.Path.fromOSString(file.getAbsolutePath());
    }

    private void setContainerClasspathExternalAnnotationsPath(IClasspathDescriptor classpath, String annotationPath, boolean onlyJRE) {
        for (IClasspathEntryDescriptor cpEntry : classpath.getEntryDescriptors()) {
            if (cpEntry.getEntryKind() == IClasspathEntry.CPE_CONTAINER) {
                if (onlyJRE && !cpEntry.getPath().toString().startsWith(JRE_CONTAINER)) {
                    continue;
                }
                setExternalAnnotationsPath(cpEntry, annotationPath);
            }
        }
    }

    private String getSingleProjectWideAnnotationPath(IMavenProjectFacade mavenProjectFacade) {
        if (mavenProjectFacade == null) {
            return null;
        }
        MavenProject mavenProject = mavenProjectFacade.getMavenProject();
        if (mavenProject == null) {
            return null;
        }
        Properties properties = mavenProject.getProperties();
        if (properties == null) {
            return null;
        }
        String property = properties.getProperty(M2E_JDT_ANNOTATIONPATH);
        if (property == null) {
            return null;
        } else {
            return property.trim();
        }
    }

    /**
     * Configure Project's JDT Compiler Properties. First attempts to read
     * a file named "org.eclipse.jdt.core.prefs" stored in a dependency of the
     * maven-compiler-plugin.  If none found, copy the properties read from the
     * maven-compiler-plugin configuration compilerArguments properties.
     *
     * <p>The reason we support (and give preference to) a dependency over the
     * properties from the compilerArguments configuration is that the latter
     * requires the file to be at the given location, which it may not (yet) be;
     * for the build this is typically based e.g. on a maven-dependency-plugin
     * unpack - which may not have run, yet.
     */
    @Override
    public void configure(ProjectConfigurationRequest projectConfigurationRequest, IProgressMonitor monitor) throws CoreException {
        final MavenProject mavenProject = projectConfigurationRequest.getMavenProject();
        final Plugin plugin = mavenProject.getPlugin("org.apache.maven.plugins:maven-compiler-plugin");
        if (plugin == null) {
            return;
        }
        for (Dependency dependency : plugin.getDependencies()) {
            if ("tycho-compiler-jdt".equals(dependency.getArtifactId())) {
                // Just an optimization to save the unneeded resolve() below
                continue;
            }
            Artifact artifact = maven.resolve(dependency.getGroupId(), dependency.getArtifactId(), dependency.getVersion(),
                    dependency.getType(), dependency.getClassifier(), mavenProject.getRemoteArtifactRepositories(),
                    monitor);
            if (artifact != null && artifact.isResolved()) {
                Optional<String> optionalPropertiesAsText = read(artifact.getFile(), "org.eclipse.jdt.core.prefs");
                optionalPropertiesAsText.ifPresent(propertiesAsText -> {
                    Properties configurationCompilerArgumentsProperties = new Properties();
                    try {
                        configurationCompilerArgumentsProperties.load(new StringReader(propertiesAsText));
                        configureProjectFromProperties(projectConfigurationRequest.getProject(), configurationCompilerArgumentsProperties);
                        return;
                    } catch (IOException e) {
                        LOGGER.error("IOException while reading properties: {}", propertiesAsText, e);
                    }
                });
            }
        }

        // If we reach here then we haven't been able to use a suitable dependency of maven-compiler-plugin,
        // so we fall back to trying to use the properties file given in the plugin's configuration:
        //
        if (!(plugin.getConfiguration() instanceof Xpp3Dom)) {
            return;
        }
        Xpp3Dom configurationDom = (Xpp3Dom) plugin.getConfiguration();
        if (configurationDom == null) {
            return;
        }
        Xpp3Dom compilerArgumentsDom = configurationDom.getChild("compilerArguments");
        if (compilerArgumentsDom == null) {
            return;
        }
        Xpp3Dom propertiesDom = compilerArgumentsDom.getChild("properties");
        String configurationCompilerArgumentsPropertiesPath = propertiesDom.getValue();
        if (configurationCompilerArgumentsPropertiesPath == null) {
            return;
        }

        File configurationCompilerArgumentsFile = new File(configurationCompilerArgumentsPropertiesPath);
        if (!configurationCompilerArgumentsFile.exists()) {
            return;
        }
        if (configurationCompilerArgumentsFile.getPath().replace('\\', '/').contains("/.settings/")) {
            return;
        }

        try (FileInputStream inputStream = new FileInputStream(configurationCompilerArgumentsFile)) {
            Properties configurationCompilerArgumentsProperties = new Properties();
            configurationCompilerArgumentsProperties.load(inputStream);
            configureProjectFromProperties(projectConfigurationRequest.getProject(), configurationCompilerArgumentsProperties);
            return;
        } catch (IOException e) {
            LOGGER.error("IOException while reading file: {}", configurationCompilerArgumentsFile, e);
        }
    }

    private void configureProjectFromProperties(IProject project, Properties configurationCompilerArgumentsProperties) {
        if (configurationCompilerArgumentsProperties.isEmpty()) {
            return;
        }
        IJavaProject javaProject = JavaCore.create(project);
        if (javaProject == null) {
            return;
        }
        javaProject.setOptions(fromProperties(configurationCompilerArgumentsProperties));
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private Map<String, String> fromProperties(Properties properties) {
        Map configurationCompilerArgumentsPropertiesAsMap = properties;
        Map<String, String> map = new HashMap<String, String>(configurationCompilerArgumentsPropertiesAsMap);
        return map;
    }

    @Override
    public void mavenProjectChanged(MavenProjectChangedEvent event, IProgressMonitor monitor) throws CoreException {
        String newAnnotationpath = getSingleProjectWideAnnotationPath(event.getMavenProject());
        String oldAnnotationpath = getSingleProjectWideAnnotationPath(event.getOldMavenProject());
        if (newAnnotationpath == oldAnnotationpath
                || newAnnotationpath != null && newAnnotationpath.equals(oldAnnotationpath)) {
            return;
        }

        // TODO : warn the user that the config should be updated in full
    }

    private String toString(IClasspathEntryDescriptor entry) {
        StringBuilder sb = new StringBuilder("IClasspathEntryDescriptor[");
        if (entry.getArtifactKey() != null) {
            sb.append(entry.getArtifactKey().toString()).append(" - ");
        }
        sb.append(entry.getEntryKind());
        sb.append(": ");
        sb.append(entry.getPath());
        sb.append("]");
        return sb.toString();
    }

}
