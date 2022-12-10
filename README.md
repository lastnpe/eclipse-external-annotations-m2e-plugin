Eclipse Maven integration (M2E extension) for null analysis
----

Configures the path to Eclipse external annotations for null analysis on the Maven Dependencies and JRE containers classpath containers, and sets JDT compiler configuration:

1. Allows to configure Java Compiler Project Properties from maven-compiler-plugin; read either from a dependency of maven-compiler-plugin containing a org.eclipse.jdt.core.prefs file, or from configuration/compilerArguments/properties.

2. Allows to configure the path to external annotations for the Maven Dependencies and JRE classpath containers.
   The path is

   * either taken from the `m2e.jdt.annotationpath` property in POM files, if it exists.
     This is used to configure one single archive of *.eea for ALL Maven dependencies AND the JRE.

   * or taken from the different properties in POM files. This is used to configure different locations for JRE and Maven dependencies.
     * `m2e.eea.annotationpath.jre`: The annotation path for JRE
     * `m2e.eea.annotationpath.maven`: The annotation path for Maven dependencies
     * `m2e.eea.annotationpath.pde`: The annotation path for required PDE plugins

   * or by individually associating archives on the projects main (not maven-compiler-plugin) dependencies with classpath entries, based on a eea-for-gav marker file in the *-eea.jar which indicates for which Maven GAV it holds external annotations.

p2 update sites to install this from:

* Recent Eclipse versions (>= 2022-09)  
  https://www.lastnpe.org/eclipse-external-annotations-m2e-plugin-p2-site/m2e_2/
* Older Eclipse versions (<= 2022-06)  
  https://www.lastnpe.org/eclipse-external-annotations-m2e-plugin-p2-site/

_These URLs will work with Eclipse but return a 404 error in browsers because they do not have an index.html file._

You can also build it yourself:

```
git clone https://github.com/lastnpe/eclipse-external-annotations-m2e-plugin.git
./mvnw clean package
```

See usage examples in [lastnpe/eclipse-null-eea-augments/examples/](https://github.com/lastnpe/eclipse-null-eea-augments/tree/master/examples/maven) (or [sylvainlaurent/null-pointer-analysis-examples](https://github.com/sylvainlaurent/null-pointer-analysis-examples/tree/master/with-external-annotations) for the older single EEA approach).

If you like/use this project, a Star / Watch / Follow on GitHub is appreciated.

See also https://www.lastnpe.org/
