package org.lastnpe.m2e.core.configurator;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.URI;
import java.nio.charset.Charset;
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
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.apache.commons.io.IOUtils;
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
    private static final MavenGAV JAVA_GAV = MavenGAV.of("java", "java");
    private static final String EEA_FOR_GAV_FILENAME = "eea-for-gav";

    /*
     * Classpath
     */
    private static final String JRE_CONTAINER = "org.eclipse.jdt.launching.JRE_CONTAINER";
    private static final String MAVEN_CLASSPATH_CONTAINER = "org.eclipse.m2e.MAVEN2_CLASSPATH_CONTAINER";
    private static final String PDE_REQUIRED_PLUGINS = "org.eclipse.pde.core.requiredPlugins";

    /*
     * Maven properties
     */
    private static final String M2E_JDT_ANNOTATIONPATH = "m2e.jdt.annotationpath";
    private static final String M2E_EEA_ANNOTATIONPATH_JRE = "m2e.eea.annotationpath.jre";
    private static final String M2E_EEA_ANNOTATIONPATH_MVN = "m2e.eea.annotationpath.maven";
    private static final String M2E_EEA_ANNOTATIONPATH_PDE = "m2e.eea.annotationpath.pde";

    private final static Logger LOGGER = LoggerFactory.getLogger(ClasspathConfigurator.class);

    @Override
    public void configureClasspath(final IMavenProjectFacade mavenProjectFacade, final IClasspathDescriptor classpath,
            final IProgressMonitor monitor) throws CoreException {
        final List<IPath> classpathEntryPaths = classpath.getEntryDescriptors().stream()
                .map(cpEntry -> cpEntry.getPath()).collect(Collectors.toList());
        final Map<MavenGAV, IPath> mapping = getExternalAnnotationMapping(classpathEntryPaths);
        for (final IClasspathEntryDescriptor cpEntry : classpath.getEntryDescriptors()) {
            mapping.entrySet().stream().filter(e -> e.getKey().matches(cpEntry.getArtifactKey())).findFirst()
                    .ifPresent(e -> {
                        setExternalAnnotationsPath(cpEntry, e.getValue().toString());
                    });
        }
        // Do *NOT* configure the JRE's EEA here, but in configureRawClasspath(),
        // because it's the wrong time for M2E (and will break project import,
        // when the IProject doesn't fully exist in JDT yet at this stage).
    }

    private void setExternalAnnotationsPath(final IClasspathEntryDescriptor cpEntry, final String path) {
        cpEntry.setClasspathAttribute("annotationpath", path);
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info("Setting External Annotations of {} to {}", toString(cpEntry), path);
        }
    }

    private Map<MavenGAV, IPath> getExternalAnnotationMapping(final List<IPath> classpathEntryPaths) {
        final Map<MavenGAV, IPath> mapping = new HashMap<>();
        for (final IPath cpEntryPath : classpathEntryPaths) {
            final Optional<File> optionalFileOrDirectory = toFile(cpEntryPath);
            optionalFileOrDirectory.ifPresent(fileOrDirectory -> {
                getExternalAnnotationMapping(fileOrDirectory).forEach(gav -> mapping.put(gav, cpEntryPath));
            });
        }
        return mapping;
    }

    private List<MavenGAV> getExternalAnnotationMapping(final File dependency) {
        final List<String> gavLines = readLines(dependency, EEA_FOR_GAV_FILENAME);
        final List<MavenGAV> result = new ArrayList<>(gavLines.size());
        gavLines.forEach(line -> {
            try {
                final MavenGAV gav = MavenGAV.parse(line);
                LOGGER.info("Found EEA for {} in {}", gav, dependency);
                result.add(gav);
            } catch (final IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "Bad line in " + EEA_FOR_GAV_FILENAME + " of " + dependency + ": " + line, e);
            }
        });
        return result;
    }

    private List<String> readLines(final File fileOrDirectory, final String fileName) {
        final Optional<String> fileContent = read(fileOrDirectory, fileName);
        if (fileContent.isPresent()) {
            return readLines(fileContent.get());
        } else {
            return Collections.emptyList();
        }
    }

    private Optional<File> toFile(final IPath iPath) {
        // iPath.toFile() is NOT what we want..
        // https://wiki.eclipse.org/FAQ_What_is_the_difference_between_a_path_and_a_location%3F
        final IWorkspace workspace = ResourcesPlugin.getWorkspace();
        final IWorkspaceRoot root = workspace.getRoot();
        final IResource resource = root.findMember(iPath);
        if (resource != null) {
            final IPath location = resource.getLocation();
            return Optional.ofNullable(location.toFile());
        } else {
            // In this case iPath likely was an absolute FS / non-WS path to being with (or a closed project), so try:
            return Optional.ofNullable(iPath.toFile());
        }
    }

    /**
     * Reads content of a name file from either inside a JAR or a directory
     *
     * @param fileOrDirectory either a ZIP/JAR file, or a directory
     * @param fileName file to look for in that ZIP/JAR file or directory
     * @return content of file, if any
     */
    private Optional<String> read(final File fileOrDirectory, final String fileName) {
        if (!fileOrDirectory.exists()) {
            LOGGER.error("File does not exist: {}", fileOrDirectory);
            return Optional.empty();
        }
        if (fileOrDirectory.isDirectory()) {
            final File file = new File(fileOrDirectory, fileName);
            return readFile(file.toPath());
        } else if (fileOrDirectory.isFile()) {
            try (ZipFile jar = new ZipFile(fileOrDirectory)) {
                ZipEntry entry = jar.getEntry(fileName);
                if (entry == null) {
                    return Optional.empty();
                }
                try (InputStream is = jar.getInputStream(entry)) {
                    return Optional.of(IOUtils.toString(is, "UTF-8"));
                }
            } catch (final IOException e) {
                LOGGER.error("IOException from ZipFile for: {}!{}", fileOrDirectory, fileName, e);
                return Optional.empty();
            }
        } else {
            LOGGER.error("File is neither a directory nor a file: {}", fileOrDirectory);
            return Optional.empty();
        }

    }

    private Optional<String> readFile(final Path path) {
        try {
            if (Files.exists(path)) {
                return Optional.of(new String(Files.readAllBytes(path), Charset.forName("UTF-8")));
            } else {
                return Optional.empty();
            }
        } catch (final IOException e) {
            LOGGER.error("IOException from Files.readAllBytes for: {}", path, e);
            return Optional.empty();
        }
    }

    private List<String> readLines(final String string) {
        return NEWLINE_REGEXP.splitAsStream(string).map(t -> t.trim()).filter(t -> !t.isEmpty())
                .filter(t -> !t.startsWith("#")).collect(Collectors.toList());
    }

    @Override
    public void configureRawClasspath(final ProjectConfigurationRequest request, final IClasspathDescriptor classpath,
            final IProgressMonitor monitor) throws CoreException {
        final IMavenProjectFacade mavenProjectFacade = request.getMavenProjectFacade();

        /*
         * First check for the property for one global path for all classpaths.
         */

        if (setContainerClasspathEeaPath(classpath, mavenProjectFacade, M2E_JDT_ANNOTATIONPATH, Optional.empty())) {
            return;
        }

        /*
         * If the above has not been set, check if there are properties for specific classpaths.
         */

        boolean hasProp = false;
        hasProp |= setContainerClasspathEeaPath(classpath, mavenProjectFacade, M2E_EEA_ANNOTATIONPATH_JRE,
                Optional.of(JRE_CONTAINER));
        hasProp |= setContainerClasspathEeaPath(classpath, mavenProjectFacade, M2E_EEA_ANNOTATIONPATH_MVN,
                Optional.of(MAVEN_CLASSPATH_CONTAINER));
        hasProp |= setContainerClasspathEeaPath(classpath, mavenProjectFacade, M2E_EEA_ANNOTATIONPATH_PDE,
                Optional.of(PDE_REQUIRED_PLUGINS));
        if (hasProp) {
            return;
        }

        /*
         * If the above has not been set, check the dependencies.
         */

        // Find the JRE's EEA among the dependencies to set it....
        // Note that at this stage of M2E we have to use the Maven project dependencies,
        // and cannot rely on the dependencies already being on the IClasspathDescriptor
        // (that happens in configureClasspath() not here in configureRawClasspath())
        //

        final Optional<String> cpe = Optional.of(JRE_CONTAINER);

        final MavenProject mavenProject = request.getMavenProject();
        for (final Dependency dependency : mavenProject.getDependencies()) {
            // Filter by "*-eea" artifactId naming convention, just for performance
            if (!dependency.getArtifactId().endsWith("-eea")) {
                continue;
            }
            final Artifact artifact = maven.resolve(dependency.getGroupId(), dependency.getArtifactId(),
                    dependency.getVersion(), dependency.getType(), dependency.getClassifier(),
                    mavenProject.getRemoteArtifactRepositories(), monitor);
            if (artifact != null && artifact.isResolved()) {
                final File eeaProjectOrJarFile = artifact.getFile();
                if (getExternalAnnotationMapping(eeaProjectOrJarFile).contains(JAVA_GAV)) {
                    final IPath iPath = getProjectPathFromAbsoluteLocationIfPossible(eeaProjectOrJarFile);
                    setContainerClasspathExternalAnnotationsPath(classpath, iPath.toString(), cpe);
                    return;
                }
            }
        }
    }

    /**
     * Attempt to convert an absolute File to a workspace relative project path.
     *
     * @param file a File pointing either to a JAR file in the Maven repo, or a project on disk
     * @return IPath which will either be workspace relative if match found, else same location (absolute)
     */
    private IPath getProjectPathFromAbsoluteLocationIfPossible(File file) {
        final Path targetClassesPath = Paths.get("target", "classes");
        if (file.toPath().endsWith(targetClassesPath)) {
            file = file.getParentFile().getParentFile();
        }
        final URI fileURI = file.toURI().normalize();
        final IProject[] projects = ResourcesPlugin.getWorkspace().getRoot().getProjects();
        for (final IProject project : projects) {
            if (!project.isOpen()) {
                continue;
            }
            final URI locationURI = project.getLocationURI().normalize();
            // The follow circus is because of a trailing slash difference:
            if (Paths.get(locationURI).equals(Paths.get(fileURI))) {
                return project.getFullPath();
            }
        }
        return org.eclipse.core.runtime.Path.fromOSString(file.getAbsolutePath());
    }

    private boolean setContainerClasspathEeaPath(final IClasspathDescriptor classpath,
            final IMavenProjectFacade mavenProjectFacade, final String mavenPropertyName,
            final Optional<String> startsWith) {
        final String annotationPath = getSingleProjectWideAnnotationPath(mavenProjectFacade, mavenPropertyName);
        if (annotationPath != null && !annotationPath.isEmpty()) {
            setContainerClasspathExternalAnnotationsPath(classpath, annotationPath, startsWith);
            return true;
        } else {
            return false;
        }
    }

    /**
     * Set classpath for external annotations.
     *
     * @param classpath the classpath
     * @param annotationPath the path of the annotation
     * @param startsWith if present the annotation path if added only if the classpath entry starts with the given
     *            prefix, if empty it is added to every classpath entry
     */
    private void setContainerClasspathExternalAnnotationsPath(final IClasspathDescriptor classpath,
            final String annotationPath, final Optional<String> startsWith) {
        for (final IClasspathEntryDescriptor cpEntry : classpath.getEntryDescriptors()) {
            if (cpEntry.getEntryKind() == IClasspathEntry.CPE_CONTAINER) {
                if (startsWith.isPresent()) {
                    if (cpEntry.getPath().toString().startsWith(startsWith.get())) {
                        setExternalAnnotationsPath(cpEntry, annotationPath);
                    }
                } else {
                    setExternalAnnotationsPath(cpEntry, annotationPath);
                }
            }
        }
    }

    private String getSingleProjectWideAnnotationPath(final IMavenProjectFacade mavenProjectFacade) {
        return getSingleProjectWideAnnotationPath(mavenProjectFacade, M2E_JDT_ANNOTATIONPATH);
    }

    private String getSingleProjectWideAnnotationPath(final IMavenProjectFacade mavenProjectFacade,
            final String propertyName) {
        if (mavenProjectFacade == null) {
            return null;
        }
        final MavenProject mavenProject = mavenProjectFacade.getMavenProject();
        if (mavenProject == null) {
            return null;
        }
        final Properties properties = mavenProject.getProperties();
        if (properties == null) {
            return null;
        }
        final String property = properties.getProperty(propertyName);
        if (property == null) {
            return null;
        } else {
            return property.trim();
        }
    }

    /**
     * Configure Project's JDT Compiler Properties. First attempts to read
     * a file named "org.eclipse.jdt.core.prefs" stored in a dependency of the
     * maven-compiler-plugin. If none found, copy the properties read from the
     * maven-compiler-plugin configuration compilerArguments properties.
     *
     * <p>
     * The reason we support (and give preference to) a dependency over the
     * properties from the compilerArguments configuration is that the latter
     * requires the file to be at the given location, which it may not (yet) be;
     * for the build this is typically based e.g. on a maven-dependency-plugin
     * unpack - which may not have run, yet.
     */
    @Override
    public void configure(final ProjectConfigurationRequest projectConfigurationRequest, final IProgressMonitor monitor)
            throws CoreException {
        final MavenProject mavenProject = projectConfigurationRequest.getMavenProject();
        final Plugin plugin = mavenProject.getPlugin("org.apache.maven.plugins:maven-compiler-plugin");
        if (plugin == null) {
            return;
        }
        for (final Dependency dependency : plugin.getDependencies()) {
            if ("tycho-compiler-jdt".equals(dependency.getArtifactId())) {
                // Just an optimization to save the unneeded resolve() below
                continue;
            }
            final Artifact artifact = maven.resolve(dependency.getGroupId(), dependency.getArtifactId(),
                    dependency.getVersion(), dependency.getType(), dependency.getClassifier(),
                    mavenProject.getRemoteArtifactRepositories(), monitor);
            if (artifact != null && artifact.isResolved()) {
                final Optional<String> optionalPropertiesAsText = read(artifact.getFile(),
                        "org.eclipse.jdt.core.prefs");
                optionalPropertiesAsText.ifPresent(propertiesAsText -> {
                    final Properties configurationCompilerArgumentsProperties = new Properties();
                    try {
                        configurationCompilerArgumentsProperties.load(new StringReader(propertiesAsText));
                        configureProjectFromProperties(projectConfigurationRequest.getProject(),
                                configurationCompilerArgumentsProperties);
                        return;
                    } catch (final IOException e) {
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
        final Xpp3Dom configurationDom = (Xpp3Dom) plugin.getConfiguration();
        if (configurationDom == null) {
            return;
        }
        final Xpp3Dom compilerArgumentsDom = configurationDom.getChild("compilerArguments");
        if (compilerArgumentsDom == null) {
            return;
        }
        final Xpp3Dom propertiesDom = compilerArgumentsDom.getChild("properties");
        if (propertiesDom == null) {
            return;
        }
        final String configurationCompilerArgumentsPropertiesPath = propertiesDom.getValue();
        if (configurationCompilerArgumentsPropertiesPath == null) {
            return;
        }

        final File configurationCompilerArgumentsFile = new File(configurationCompilerArgumentsPropertiesPath);
        if (!configurationCompilerArgumentsFile.exists()) {
            return;
        }
        if (configurationCompilerArgumentsFile.getPath().replace('\\', '/').contains("/.settings/")) {
            return;
        }

        try (FileInputStream inputStream = new FileInputStream(configurationCompilerArgumentsFile)) {
            final Properties configurationCompilerArgumentsProperties = new Properties();
            configurationCompilerArgumentsProperties.load(inputStream);
            configureProjectFromProperties(projectConfigurationRequest.getProject(),
                    configurationCompilerArgumentsProperties);
            return;
        } catch (final IOException e) {
            LOGGER.error("IOException while reading file: {}", configurationCompilerArgumentsFile, e);
        }
    }

    private void configureProjectFromProperties(final IProject project,
            final Properties configurationCompilerArgumentsProperties) {
        if (configurationCompilerArgumentsProperties.isEmpty()) {
            return;
        }
        final IJavaProject javaProject = JavaCore.create(project);
        if (javaProject == null) {
            return;
        }
        Map<String, String> options = javaProject.getOptions(false);
        options.putAll(fromProperties(configurationCompilerArgumentsProperties));
        javaProject.setOptions(options);
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private Map<String, String> fromProperties(final Properties properties) {
        final Map configurationCompilerArgumentsPropertiesAsMap = properties;
        final Map<String, String> map = new HashMap<String, String>(configurationCompilerArgumentsPropertiesAsMap);
        return map;
    }

    @Override
    public void mavenProjectChanged(final MavenProjectChangedEvent event, final IProgressMonitor monitor)
            throws CoreException {
        final String newAnnotationpath = getSingleProjectWideAnnotationPath(event.getMavenProject());
        final String oldAnnotationpath = getSingleProjectWideAnnotationPath(event.getOldMavenProject());
        if (newAnnotationpath == oldAnnotationpath
                || newAnnotationpath != null && newAnnotationpath.equals(oldAnnotationpath)) {
            return;
        }

        // TODO : warn the user that the config should be updated in full
    }

    private String toString(final IClasspathEntryDescriptor entry) {
        final StringBuilder sb = new StringBuilder("IClasspathEntryDescriptor[");
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
