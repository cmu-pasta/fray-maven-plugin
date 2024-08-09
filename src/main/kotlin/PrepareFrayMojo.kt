package org.pastalab.fray.maven

import org.apache.maven.artifact.Artifact
import org.apache.maven.execution.MavenSession
import org.apache.maven.plugin.AbstractMojo
import org.apache.maven.plugin.MojoExecutionException
import org.apache.maven.plugins.annotations.Component
import org.apache.maven.plugins.annotations.LifecyclePhase
import org.apache.maven.plugins.annotations.Mojo
import org.apache.maven.plugins.annotations.Parameter
import org.apache.maven.plugins.annotations.ResolutionScope
import org.apache.maven.project.MavenProject
import org.apache.maven.shared.dependency.graph.DependencyGraphBuilder
import org.apache.maven.shared.dependency.graph.DependencyNode
import java.io.File
import java.util.jar.JarFile


@Mojo(
    name = "prepare-fray",
    defaultPhase = LifecyclePhase.INITIALIZE,
    requiresDependencyResolution = ResolutionScope.RUNTIME,
    threadSafe = true
)
class PrepareFrayMojo: AbstractMojo() {
    @Parameter(defaultValue = "\${project}", readonly = true, required = true)
    private val project: MavenProject? = null

    @Parameter(defaultValue = "\${session}", readonly = true, required = true)
    private val session: MavenSession? = null


    @Parameter(property = "plugin.artifactMap", required = true, readonly = true)
    private val pluginArtifactMap: Map<String, Artifact>? = null

    @Parameter(property = "fray.workDir", defaultValue = "\${project.build.directory}/fray")
    private val destFile: File? = null

    @Component
    private val projectDependenciesResolver: DependencyGraphBuilder? = null


    @Throws(MojoExecutionException::class)
    override fun execute() {
        val jdkPath = destFile!!.absolutePath + "/fray-java"
        val jvmtiPath = destFile!!.absolutePath + "/fray-jvmti"
        val oldValue = project!!.properties.getProperty("argLine") ?: ""
        createInstrumentedJdk(jdkPath)
        prepareAgentLib(jvmtiPath)
        project.properties.setProperty("argLine", oldValue + " -javaagent:" + getAgentJarFile().absolutePath
                + " -agentpath:" + jvmtiPath + "/libjvmti.so")
        project.properties.setProperty("jvm", "$jdkPath/bin/java")
    }

    fun getAgentJarFile(): File {
        return pluginArtifactMap!!["org.pastalab.fray:instrumentation-agent"]!!.file
    }

    fun getJvmtiJarFile(): File {
        val os = when(System.getProperty("os.name").lowercase()) {
            "mac os x" -> "macos"
            "linux" -> "linux"
            "windows" -> "windows"
            else -> throw Exception("Unsupported OS")
        }

        val arch = System.getProperty("os.arch")
        return pluginArtifactMap!!["org.pastalab.fray:jvmti-$os-$arch"]!!.file
    }


    fun prepareAgentLib(path: String) {
        val jvmtiPath = File(path)
//        if (jvmtiPath.exists()) {
//            return
//        }
        jvmtiPath.mkdirs()
        val jvmtiJar = getJvmtiJarFile()
        val jarFile = JarFile(jvmtiJar)
        jarFile.entries().asSequence().forEach {
            val file = File(jvmtiPath, it.name)
            if (it.isDirectory) {
                file.mkdirs()
            } else {
                file.parentFile.mkdirs()
                jarFile.getInputStream(it).use { input ->
                    file.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }
//            resolveDependencyFiles(frayJvmti.get()).filter { it.name.contains("jvmti") }.first()
//        project.copy {
//            it.from(project.zipTree(jvmtiJar))
//            it.into(jvmtiPath)
//        }
    }

    fun createInstrumentedJdk(path: String) {
        val jdkPath = File(path)
        if (jdkPath.exists()) {
            return
        }
        val jdkJar = pluginArtifactMap!!["org.pastalab.fray:jdk"]!!.file
        val jdkJarDependencies = pluginArtifactMap.values.filter {
            it.groupId != "org.pastalab.fray" || it.artifactId != "instrumentation-agent"
        }.map { it.file }
        val command = arrayOf(
                "jlink",
                "-J-javaagent:$jdkJar",
                "-J--module-path=${jdkJarDependencies.joinToString(":")}",
                "-J--add-modules=org.pastalab.fray.jdk",
                "-J--class-path=${jdkJarDependencies.joinToString(":")}",
                "--output=${jdkPath.absolutePath}",
                "--add-modules=ALL-MODULE-PATH",
                "--fray-instrumentation"
            )
        log.info("Executing command: ${command.joinToString(" ")}")
        val process = Runtime.getRuntime().exec(
            arrayOf(
                "jlink",
                "-J-javaagent:$jdkJar",
                "-J--module-path=${jdkJarDependencies.joinToString(":")}",
                "-J--add-modules=org.pastalab.fray.jdk",
                "-J--class-path=${jdkJarDependencies.joinToString(":")}",
                "--output=${jdkPath.absolutePath}",
                "--add-modules=ALL-MODULE-PATH",
                "--fray-instrumentation"
            )
        )
        process.waitFor()
        log.info(process.inputStream.bufferedReader().readText())
    }
}