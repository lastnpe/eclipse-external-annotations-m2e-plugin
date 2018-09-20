package org.lastnpe.m2e.core.configurator.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.Optional;

import org.junit.BeforeClass;
import org.junit.Test;
import org.lastnpe.m2e.core.configurator.ClasspathConfigurator;

@SuppressWarnings("javadoc")
public class ClasspathConfiguratorTest {

    private static File jar;
    private static Method read;

    @BeforeClass
    public static void setup() throws Exception {
        URL jarRes = ClasspathConfiguratorTest.class.getResource("jar.jar");
        jar = new File(jarRes.getFile());
        assertTrue(jar.exists());

        Class<ClasspathConfigurator> clz = ClasspathConfigurator.class;
        read = clz.getDeclaredMethod("read", File.class, String.class);
        read.setAccessible(true);
    }

    @Test
    public void testReadJar() throws Throwable {
        @SuppressWarnings("unchecked")
        Optional<String> first = (Optional<String>)read.invoke(null, jar, "eea-for-gav");
        assertEquals("java:java", first.get());

        @SuppressWarnings("unchecked")
        Optional<String> second = (Optional<String>)read.invoke(null, jar, "eea-for-gav");
        assertEquals("java:java", second.get());

        @SuppressWarnings("unchecked")
        Optional<String> missing = (Optional<String>)read.invoke(null, jar, "missing");
        assertFalse(missing.isPresent());

        @SuppressWarnings("unchecked")
        Optional<String> nest = (Optional<String>)read.invoke(null, jar, "nested/eea-for-gav");
        assertEquals("java:java", nest.get());
    }

}
