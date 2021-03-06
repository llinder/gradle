/*
 * Copyright 2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.api.internal.tasks.compile;

import org.gradle.internal.Factory;
import org.gradle.internal.SystemProperties;
import org.gradle.internal.jvm.Jvm;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.Serializable;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class JavaHomeBasedJavaCompilerFactory implements Factory<JavaCompiler>, Serializable {
    private final Lock lock = new ReentrantLock();
    private JavaHomeProviderFacade currentJvmJavaHomeProviderFacade = new CurrentJvmJavaHomeProviderFacade();
    private JavaHomeProviderFacade systemPropertiesJavaHomeProviderFacade = new SystemPropertiesJavaHomeProviderFacade();
    private JavaCompilerProviderFacade javaCompilerProviderFacade = new ToolProviderJavaCompilerProviderFacade();

    public JavaCompiler create() {
        JavaCompiler compiler = findCompiler();

        if(compiler==null){
            throw new RuntimeException("Cannot find System Java Compiler. Ensure that you have installed a JDK (not just a JRE) and configured your JAVA_HOME system variable to point to the according directory.");
        }

        return compiler;
    }

    private JavaCompiler findCompiler() {
        File realJavaHome = currentJvmJavaHomeProviderFacade.getDir();
        File javaHomeFromToolProvidersPointOfView = systemPropertiesJavaHomeProviderFacade.getDir();
        if (realJavaHome.equals(javaHomeFromToolProvidersPointOfView)) {
            return javaCompilerProviderFacade.getCompiler();
        }

        lock.lock();
        SystemProperties.setJavaHomeDir(realJavaHome);
        try {
            return javaCompilerProviderFacade.getCompiler();
        } finally {
            SystemProperties.setJavaHomeDir(javaHomeFromToolProvidersPointOfView);
            lock.unlock();
        }
    }

    public static interface JavaHomeProviderFacade extends Serializable {
        File getDir();
    }

    public static class CurrentJvmJavaHomeProviderFacade implements JavaHomeProviderFacade {
        public File getDir() {
            return Jvm.current().getJavaHome();
        }
    }

    public static class SystemPropertiesJavaHomeProviderFacade implements JavaHomeProviderFacade {
        public File getDir() {
            return SystemProperties.getJavaHomeDir();
        }
    }

    public static interface JavaCompilerProviderFacade extends Serializable {
        JavaCompiler getCompiler();
    }

    public static class ToolProviderJavaCompilerProviderFacade implements JavaCompilerProviderFacade {
        public JavaCompiler getCompiler() {
            return ToolProvider.getSystemJavaCompiler();
        }
    }
}
