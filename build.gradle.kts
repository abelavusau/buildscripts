import com.bmuschko.gradle.docker.tasks.container.DockerCreateContainer
import com.bmuschko.gradle.docker.tasks.container.DockerStartContainer
import com.bmuschko.gradle.docker.tasks.image.DockerBuildImage
import com.bmuschko.gradle.docker.tasks.image.Dockerfile

// We need this file to run a composite build core + native
plugins {
    alias(libs.plugins.build.conventions)
    alias(nativelibs.plugins.cmake.generator) apply false
    id("com.bmuschko.docker-remote-api") version "6.7.0"
    `cpp-unit-test`
}

val tasksRequiredDebugArtifacts = setOf(
    tasks.build.name,
    tasks.test.name,
    tasks.check.name
)

val debug = providers.gradleProperty("CI").isPresent     // -PCI - running on CI server
        || gradle.startParameter.taskNames.any(tasksRequiredDebugArtifacts::contains) // any task from the list

val optimizedLevel0 = providers.gradleProperty("DEBUG").isPresent // -PDEBUG

val rhelVersion = project.properties.getOrDefault("rhel.version", "8.9")
val rhelToolsetVersion = project.properties.getOrDefault("rhel.toolset.version", "12")

val centosVersion = project.properties.getOrDefault("centos.version", "7")
val centosToolsetVersion = project.properties.getOrDefault("centos.toolset.version", "11")

val dockerFileCentos by tasks.creating(Dockerfile::class) {
    group = "docker"

    instruction("FROM gradle:jdk8 as gradle")
    instruction("FROM centos:centos$centosVersion")
    instruction("COPY --from=gradle /opt/java/openjdk/ /opt/java/openjdk")
    instruction("COPY --from=gradle /opt/gradle/ /opt/gradle")
    instruction("""
        RUN yum -y install centos-release-scl && \
        yum -y group install "Development Tools" && \
        yum -y install devtoolset-$centosToolsetVersion && \
	    yum clean all && \
        rm -rf /var/cache/yum/*
    """.trimIndent()
    )
    environmentVariable("PATH", "/opt/rh/devtoolset-$centosToolsetVersion/root/usr/bin:\$PATH")
    environmentVariable("JAVA_HOME", "/opt/java/openjdk")

    // if we'd like to use the latest version of `gradle`, but for now we stick with the version defined in the wrapper -> gradle-wrapper.properties
    //environmentVariable("GRADLE_HOME", "/opt/gradle")
    //environmentVariable("PATH", "\$PATH:\$GRADLE_HOME/bin")

    workingDir("/core/native")
    entryPoint("./gradlew")
    defaultCommand("assemble", "--console=plain")
}

val buildImageCentos by tasks.creating(DockerBuildImage::class) {
    group = "docker"

    dependsOn(dockerFileCentos)
    images.add("native/centos$centosVersion")
}

val assembleInCentos by tasks.creating(Exec::class) {
    group = "docker"

    dependsOn(buildImageCentos)
    commandLine(
            "docker",
            "run",
            "--rm",
            "-v",
            "${layout.projectDirectory.asFile}/..:/core",
            "-v",
            "${System.getProperty("user.home")}/.gradle:/root/.gradle",
            "native/centos$centosVersion:latest"
    )
}

val dockerFileRhel by tasks.creating(Dockerfile::class) {
    group = "docker"

    instruction("FROM gradle:jdk8 as gradle")
    instruction("FROM redhat/ubi8:$rhelVersion")
    instruction("COPY --from=gradle /opt/java/openjdk/ /opt/java/openjdk")
    instruction("COPY --from=gradle /opt/gradle/ /opt/gradle")
    instruction("""
        RUN dnf -y install gcc-toolset-$rhelToolsetVersion
        """.trimIndent())
    environmentVariable("PATH", "/opt/rh/gcc-toolset-$rhelToolsetVersion/root/usr/bin:\$PATH")
    environmentVariable("JAVA_HOME", "/opt/java/openjdk")

    // if we'd like to use the latest version of `gradle`, but for now we stick with the version defined in the wrapper -> gradle-wrapper.properties
    //environmentVariable("GRADLE_HOME", "/opt/gradle")
    //environmentVariable("PATH", "\$PATH:\$GRADLE_HOME/bin")

    workingDir("/core/native")
    entryPoint("./gradlew")
    defaultCommand("assemble", "--console=plain")
}

val buildImageRhel by tasks.creating(DockerBuildImage::class) {
    group = "docker"

    dependsOn(dockerFileRhel)
    images.add("native/redhat$rhelVersion")
}

val assembleInRhel by tasks.creating(Exec::class) {
    group = "docker"

    dependsOn(buildImageRhel)
    commandLine(
            "docker",
            "run",
            "--rm",
            "-v",
            "${layout.projectDirectory.asFile}/..:/core",
            "-v",
            "${System.getProperty("user.home")}/.gradle:/root/.gradle",
            "native/redhat$rhelVersion:latest"
    )
}

subprojects {
    apply(plugin = rootProject.libs.plugins.native.project.conventions.get().pluginId)
    apply(plugin = rootProject.nativelibs.plugins.cmake.generator.get().pluginId)

    val c = mutableListOf(
        "-fPIC",
        "-c",
        "-Wall",
        "-Wextra",
        "-Winit-self",
        "-Wmissing-prototypes",
        "-Wconversion",
        "-Wsign-conversion",
        "-Wno-long-long",
        "-Wpointer-arith"
    )

    val cpp = mutableListOf(
        "-std=c++20",
        "-fPIC",
        "-c",
        "-Werror",
        "-Wall",
        "-Wextra",
        "-Wconversion",
        "-Wsign-conversion",
        "-Winit-self",
        "-pedantic",
        "-Wno-long-long",
        "-Wpointer-arith",
        "-Wcast-qual",
        "-Wconversion-null",
        "-Wmissing-declarations",
        "-Woverlength-strings",
        "-Wunused-local-typedefs",
        "-Wunused-result",
        "-Wvarargs",
        "-Wvla",
        "-Wwrite-strings"
    )

    if (optimizedLevel0) {
        c.add("-O0")
        cpp.add("-O0")
        c.add("-fdebug-prefix-map=${projectDir}=.")
        cpp.add("-fdebug-prefix-map=${projectDir}=.")
    } else {
        c.add("-O3")
        cpp.add("-O3")
    }

    val copyNonStrippedFile by tasks.registering {
        doLast {
            val sourceFile = file("${layout.buildDirectory.asFile.get()}/exe/main/release/${project.name}")
            val destinationDir = file("${layout.buildDirectory.asFile.get()}/exe/main/release/stripped/")

            if (sourceFile.exists()) {
                copy {
                    from(sourceFile)
                    into(destinationDir)
                }
                println("File $sourceFile copied successfully.")
            } else {
                println("Source file $sourceFile does not exist.")
            }
        }
    }

    tasks.withType(CCompile::class.java).configureEach {
        // do not compile debug version by default
        if (name.contains("debug", true)) {
            enabled = debug
        }

        // Define C compiler options
        compilerArgs.set(c)

//        unitTest {
//            compilerArgs.add("-fno-devirtualize")
//        }
    }

    tasks.withType(CppCompile::class.java).configureEach {
        // do not compile debug version by default
        if (name.contains("debug", true)) {
            enabled = debug
        }

        // Define C++ compiler options
        compilerArgs.set(cpp)
        compilerArgs.add("-DNLOGGER_USE_THREAD_LOCAL")
        compilerArgs.add("-DNLOGGER_LOG_BUFFER_SIZE=1024")

//        unitTest {
//            compilerArgs.add("-fno-devirtualize")
//        }
    }

    tasks.withType(LinkSharedLibrary::class.java).configureEach {
        if (name.contains("debug", true)) {
            enabled = debug
        }
    }

    tasks.withType(LinkExecutable::class.java).configureEach {
        if (name.contains("debug", true)) {
            enabled = debug
        }

        finalizedBy(copyNonStrippedFile)
    }

    tasks.withType(GenerateModuleMetadata::class.java).configureEach {
        if (name.contains("debug", true)) {
            enabled = debug
        }
    }

    tasks.withType(GenerateMavenPom::class.java).configureEach {
        if (name.contains("debug", true)) {
            enabled = debug
        }
    }

    tasks.withType(PublishToMavenLocal::class.java).configureEach {
        if (name.contains("debug", true)) {
            enabled = debug
        }
    }

    tasks.withType(PublishToMavenRepository::class.java).configureEach {
        if (name.contains("debug", true)) {
            enabled = debug
        }
    }

    // skip preparing of the stripped artifact
    tasks.withType(StripSymbols::class.java).configureEach {
        enabled = project.plugins.hasPlugin("cpp-library")
    }

    val cmakeGenerate by tasks.getting

    tasks.assemble.get().dependsOn(cmakeGenerate)
}