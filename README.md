[![Build Status](https://travis-ci.org/lastnpe/eclipse-external-annotations-m2e-plugin.svg)](https://travis-ci.org/lastnpe/eclipse-external-annotations-m2e-plugin)

Eclipse Maven integration (M2E extension) for null analysis
----

Configures the path to Eclipse external annotations for null analysis on the Maven Dependencies and JRE containers classpath containers, and sets JDT compiler configuration:

1. Allows to configure Java Compiler Project Properties from maven-compiler-plugin; read either from a dependency of maven-compiler-plugin containing a org.eclipse.jdt.core.prefs file, or from configuration/compilerArguments/properties.

2. Allows to configure the path to external annotations for the Maven Dependencies and JRE classpath containers.  The path is
  - either taken from the &quot;m2e.jdt.annotationpath&quot; property in POM files, if it exists (this for configuring one single archive of *.eea for ALL Maven dependencies AND the JRE), 
  - or by individually associating archives on the projects main (not maven-compiler-plugin) dependencies with classpath entries, based on a eea-for-gav marker file in the *-eea.jar which indicates for which Maven GAV it holds external annotations.



[p2 update site to install this from is being worked on](https://github.com/lastnpe/eclipse-external-annotations-m2e-plugin/issues/6) (#helpwanted) - for now, just `git clone; ./mvnw clean package` it yourself

see usage examples in [lastnpe/eclipse-null-eea-augments/examples/](https://github.com/lastnpe/eclipse-null-eea-augments/tree/master/examples/maven) (or [sylvainlaurent/null-pointer-analysis-examples](https://github.com/sylvainlaurent/null-pointer-analysis-examples/tree/master/with-external-annotations) for the older single EEA approach).

see also http://lastnpe.org

