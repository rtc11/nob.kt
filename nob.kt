import java.io.*
import java.lang.ProcessBuilder
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.function.*
import javax.xml.parsers.DocumentBuilderFactory
import org.jetbrains.kotlin.cli.common.messages.MessageRenderer
import org.jetbrains.kotlin.cli.common.messages.PrintingMessageCollector
import org.jetbrains.kotlin.daemon.client.*
import org.jetbrains.kotlin.daemon.common.*

fun main(args: Array<String>) {
    val nob_opts = Opts("nob.kt", out_dir = "out/nob")
    val opts = Opts(args.firstOrNull() ?: Nob.default_src())

    try {
        Nob.backup(nob_opts)
        Nob.compile(nob_opts)
        // val dep = Dep.of("org.apache.commons:commons-lang3:3.14.0")
        // val all = DepResolution.resolve_transitive(dep)
        // println("[INFO] resolved dependencies:")
        // all.forEach { println("[INFO] $it") }
        val exit_code = Nob.compile(opts)
        System.exit(exit_code)
    } catch (e: Exception) {
        e.printStackTrace()
        Nob.restore(nob_opts)
        System.exit(1)
    }
}

data class Opts(
    val src_file: String,
    val kotlin_lib: Path = Paths.get("/opt/homebrew/Cellar/kotlin/2.2.0/libexec/lib"),
    val kotlin_stdlib: Path = kotlin_lib.resolve("kotlin-stdlib.jar").normalize(),
    val kotlin_compiler: Path = kotlin_lib.resolve("kotlin-compiler.jar").normalize(),
    val kotlin_daemon_client: Path = kotlin_lib.resolve("kotlin-daemon-client.jar").normalize(),
    val kotlin_daemon: Path = kotlin_lib.resolve("kotlin-daemon.jar").normalize(),
    val out_dir: String = "out",
    val jvm_target: Int = 21,
    val backend_threads: Int = 1, // run codegen with N thread per processor (Default 1)
    val verbose: Boolean = false,
    val debug: Boolean = true,
    val error: Boolean = false,
    val extra: Boolean = true,
) {
    val name: String = src_file.removeSuffix(".kt").lowercase()
    val cwd: String = System.getProperty("user.dir")
    val classpath: Path = Paths.get(cwd)
    val src: Path = Paths.get(cwd, src_file).normalize()
    val target_dir: Path = Paths.get(cwd, out_dir).also { it.toFile().mkdirs() }
    val cls: Path = target_dir.resolve(name.replaceFirstChar{ it.uppercase() } + "Kt.class")
    val main_path: Path = Paths.get(cwd, src_file)

    fun jar_command(): String {
        return """jar cfe ${name}.jar ${name}Kt -C "$target_dir" ."""
    }
}

object Nob {
    fun release(opts: Opts): Int {
        val opts = opts.copy(debug = false, error = true)
        val cmd = opts.jar_command()
        if (opts.verbose) println("[INFO] $cmd")
        println("[INFO] package ${opts.name}.jar")
        return run_command(cmd, opts)
    }

    fun compile(opts: Opts): Int {
        val src_modified = Files.getLastModifiedTime(opts.src).toInstant() 
        val cls_modified = if (Files.exists(opts.cls)) Files.getLastModifiedTime(opts.cls).toInstant() else Instant.EPOCH

        if (src_modified > cls_modified) {
            println("[INFO] compiling ${opts.name} to ${opts.target_dir}")
            compile_with_daemon(opts) 
        } 
        return 0
    }

    fun backup(opts: Opts) {
        val cls = opts.cls.toFile()
        if (cls.exists()) {
            println("[INFO] backup ${opts.cls}")
            val backup = File(cls.parentFile, cls.name + ".bak")
            cls.copyTo(backup, overwrite = true)
        }
    }

    fun restore(opts: Opts) {
        val cls = opts.cls.toFile()
        val backup = File(cls.parentFile, opts.name + ".bak")
        if (backup.exists()) {
            println("[INFO] restoring ${opts.cls}")
            backup.copyTo(cls, overwrite = true) 
        }
    }

    fun default_src(): String = "Main.kt".also {
        println("[INFO] No src file specified, using $it")
    }

    fun compile_with_daemon(opts: Opts): Int {
        val compiler_id = CompilerId.makeCompilerId(
            opts.kotlin_stdlib.toFile(),
            opts.kotlin_compiler.toFile(),
            opts.kotlin_daemon_client.toFile(),
            opts.kotlin_daemon.toFile(),
        )
        val daemon_opts = DaemonOptions(verbose = opts.verbose)
        val daemon_jvm_opts = DaemonJVMOptions()
        val client_alive_file = File("${opts.out_dir}/.alive").apply { if (!exists()) createNewFile() }
        val args = mutableListOf(
            File(opts.src_file).absolutePath,
            "-d", opts.target_dir.toString(),
            "-jvm-target", opts.jvm_target.toString(),
            "-Xbackend-threads=${opts.backend_threads}",
            "-cp", listOf(opts.kotlin_stdlib, opts.kotlin_compiler, opts.kotlin_daemon_client, opts.kotlin_daemon).joinToString(":"),
        )
        if (opts.verbose) args += "-verbose"
        if (opts.debug) args += "-Xdebug"
        if (opts.extra) args += "-Wextra"
        if (opts.error) args += "-Werror"

        val daemon_reports = arrayListOf<DaemonReportMessage>()

        val daemon = KotlinCompilerClient.connectToCompileService(
            compilerId = compiler_id,
            clientAliveFlagFile = client_alive_file,
            daemonJVMOptions = daemon_jvm_opts,
            daemonOptions = daemon_opts,
            reportingTargets = DaemonReportingTargets(out = System.out, messages = daemon_reports),
            autostart = true,
        ) ?: error("unable to connect to compiler daemon: " + 
            daemon_reports.joinToString("\n  ", prefix = "\n  ") { 
                "${it.category.name} ${it.message}"
            })

        val session_id = daemon.leaseCompileSession(client_alive_file.absolutePath).get()
        try {
            val start_time = System.nanoTime()
            val exit_code = KotlinCompilerClient.compile(
                compilerService = daemon,
                sessionId = session_id,
                targetPlatform = CompileService.TargetPlatform.JVM,
                args = args.toTypedArray(),
                messageCollector = PrintingMessageCollector(System.err, MessageRenderer.WITHOUT_PATHS, true),
                compilerMode = CompilerMode.NON_INCREMENTAL_COMPILER,
                reportSeverity = ReportSeverity.INFO,
            )
            val end_time = System.nanoTime()
            println("[INFO] Compilation complete in " + TimeUnit.NANOSECONDS.toMillis(end_time - start_time) + " ms")
            return exit_code
        } finally {
            daemon.releaseCompileSession(session_id) 
        }
    }

    fun clear(opts: Opts): Int {
        println("[INFO] clear ${opts.target_dir}")
        return run_command("rm -rf ${opts.target_dir}", opts)
    }

    fun run_command(cmd: String, opts: Opts): Int {
        val cmd = listOf("/bin/sh", "-c", cmd)
        if (opts.verbose) {
            println("[INFO] command: $cmd")
            println("[INFO] build dir: ${opts.cwd}")
        }
        val builder = ProcessBuilder(cmd)
        builder.inheritIO()
        builder.directory(File(opts.cwd))
        val process = builder.start()
        return process.waitFor()
    }
}

data class Dep(
    val group_id: String,
    val artifact_id: String,
    val version: String,
    val repo: String = "https://repo1.maven.org/maven2",
) {
    val base_url = "$repo/${group_id.replace('.', '/')}/$artifact_id" 
    val pom_url = "$base_url/$version/$artifact_id-$version.pom"
    val jar_url = "$base_url/$version/$artifact_id-$version.jar"
    override fun toString() = "$group_id:$artifact_id:$version"
    companion object {
        fun of(str: String) = str.split(':').let { Dep(it[0], it[1], it[2]) }
    }
}

object DepResolution {

    fun download(dep: Dep): List<Dep> {
        val url = dep.pom_url
        println("[INFO] fetching $url")
        val stream = URI(url).toURL().openStream()
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(stream)
        doc.documentElement.normalize()
        val props = parse_props(doc) + builtin_props(doc, dep)
        val deps = mutableListOf<Dep>()
        val dep_nodes = doc.getElementsByTagName("dependency")
        for (i in 0 until dep_nodes.length) {
            val node = dep_nodes.item(i) as org.w3c.dom.Element 
            val version_node = node.getElementsByTagName("version")
            if (version_node.length == 0) continue // managed elsewhere
            val group_id = node.getElementsByTagName("groupId").item(0).textContent
            val artifact_id = node.getElementsByTagName("artifactId").item(0).textContent
            var version = version_node.item(0).textContent
            version = version.replace(Regex("""\$\{(.+?)}""")) { match -> 
                val prop_name = match.groupValues[1]
                props[prop_name] ?: error("unknown property $prop_name in $dep among $props")
            }
            if (version.contains('[') || version.contains('(')) {
                version = resolve_version_range(Dep(group_id, artifact_id, version))
            }
            deps.add(Dep(group_id, artifact_id, version))
        }
        return deps
    }

    fun resolve_transitive(dep: Dep, seen: MutableSet<Dep> = mutableSetOf()): Set<Dep> {
        if (!seen.add(dep)) return seen
        val deps = try {
            download(dep)
        } catch (e: FileNotFoundException) {
            println("[WARN] $dep not found. Skipping...")
            emptyList()
        }
        for (dep in deps) resolve_transitive(dep, seen)
        return seen
    }

    fun parse_props(doc: org.w3c.dom.Document): Map<String, String> {
        val props = mutableMapOf<String, String>()
        val xpath = javax.xml.xpath.XPathFactory.newInstance().newXPath()
        val expr = xpath.compile("/project/properties/*")
        val nodes = expr.evaluate(doc, javax.xml.xpath.XPathConstants.NODESET) as org.w3c.dom.NodeList
        for (i in 0 until nodes.length) {
            val node = nodes.item(i) as org.w3c.dom.Element
            props[node.tagName] = node.textContent.trim()
        }
        return props
    }

    fun builtin_props(doc: org.w3c.dom.Document, dep: Dep): Map<String, String> {
        val project_node = doc.getElementsByTagName("project").item(0) as org.w3c.dom.Element
        val group_id = project_node.getElementsByTagName("groupId").item(0)?.textContent ?: dep.group_id
        val artifact_id = project_node.getElementsByTagName("artifactId").item(0)?.textContent ?: dep.artifact_id
        val version = project_node.getElementsByTagName("version").item(0)?.textContent ?: dep.version
        return mapOf(
            "project.groupId" to group_id, 
            "project.artifactId" to artifact_id, 
            "project.version" to version, 
            "pom.groupId" to group_id,
            "pom.artifactId" to artifact_id,
            "pom.version" to version,
        )
    }

    fun resolve_version_range(dep: Dep): String {
        if(!dep.version.contains('[') && !dep.version.contains('(')) return dep.version
        val url = "${dep.base_url}/maven-metadata.xml"
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(URI(url).toURL().openStream())
        doc.documentElement.normalize()
        val versions = mutableListOf<String>()
        val nodes = doc.getElementsByTagName("version")
        for (i in 0 until nodes.length) versions.add(nodes.item(i).textContent)
        if (versions.isEmpty()) error("no versions found in $url")
        val range_regex = Regex("""([\[\(])\s*([\d\.\-]+)?\s*,\s*([\d\.\-]+)?\s*([\]\)])""")
        val match = range_regex.matchEntire(dep.version) ?: error("invalid range: ${dep.version}")
        val lower_inc = match.groupValues[1] == "["
        val upper_inc = match.groupValues[4] == "]"
        val lower_bound = match.groupValues[2].takeIf { it.isNotEmpty() }
        val upper_bound = match.groupValues[3].takeIf { it.isNotEmpty() }
        val compatible = versions.filter { 
            (lower_bound == null || compare(it, lower_bound) > if (lower_inc) -1 else 0) && 
            (upper_bound == null || compare(it, upper_bound) < if (upper_inc) 1 else 0)
        }
        if (compatible.isEmpty()) error("no version satisfies range ${dep.version}")
        return compatible.maxWithOrNull(::compare)!!
    }
    private fun compare(version1: String, version2: String): Int {
        val parts1 = version1.split(".", "-")
        val parts2 = version2.split(".", "-")
        val len = maxOf(parts1.size, parts2.size)
        for (i in 0 until len) {
            val p1 = parts1.getOrElse(i) { "0" }.toIntOrNull() ?: 0
            val p2 = parts2.getOrElse(i) { "0" }.toIntOrNull() ?: 0
            if (p1 != p2) return p1 - p2
        }
        return 0
    }
}
