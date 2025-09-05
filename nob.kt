package nob

import java.io.*
import java.lang.ProcessBuilder
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.function.*
import javax.xml.parsers.DocumentBuilderFactory
import org.jetbrains.kotlin.cli.common.messages.MessageRenderer
import org.jetbrains.kotlin.cli.common.messages.PrintingMessageCollector
import org.jetbrains.kotlin.daemon.client.*
import org.jetbrains.kotlin.daemon.common.*
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.NodeList
import org.w3c.dom.Node

const val DEBUG = false

fun main(args: Array<String>) {
    val opts = parse_args(args)
    val libs = listOf(
        Lib.of("io.ktor:ktor-server-netty:3.2.2"),
        Lib.of("org.slf4j:slf4j-simple:2.0.17"),
    )
    val nob = Nob(opts.copy(libs = solve_libs(opts, libs)))
    nob.compile(opts.nob_src.toFile())
    var exit_code =  nob.compile(opts.src_dir.toFile())
    if (exit_code == 0) exit_code = nob.run_target()
    System.exit(exit_code)
}

class Nob(private val opts: Opts) {
    fun compile(src: File): Int {
        val files = when {
            src.isFile() -> listOf(src)
            src.isDirectory() -> src.listFiles().filter { it.toString().endsWith(".kt") }
            else -> emptyList()
        }
        if (files.isEmpty()) error("no files to compile in $src")
        return compile_with_daemon(files) 
    }

    fun run_target(): Int {
        debug("Running ${opts.main_src}")
        return exec(
            buildList {
                add("java")
                if (opts.debugger) add("-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=5005")
                add("-cp")
                add(opts.runtime_classpath())
                add(opts.main_class(opts.main_src.toFile()))
            },
            opts
        )
    }

    // fun release(): Int {
    //     val opts = opts.copy(debug = false, error = true)
    //     val name = opts.name(opts.src_file)
    //     val cmd = listOf("jar", "cfe", "${name}.jar", "${name}Kt", "-C", "${opts.target_dir}", ".")
    //     if (opts.verbose) info("$cmd")
    //     info("package ${name}.jar")
    //     return exec(cmd, opts)
    // }

    fun compile_with_daemon(files: List<File>): Int {
        val compiler_id = CompilerId.makeCompilerId(Files.list(opts.kotlin_dir).filter { it.toString().endsWith(".jar") }.map { it.toFile() }.toList())
        val client_alive_file = opts.target_dir.resolve(".alive").toFile().apply { if (!exists()) createNewFile() }

        val classpath = when (files.any { it.toPath() == opts.nob_src }){
            true -> opts.nob_compile_classpath()
            else -> opts.compile_classpath()
        }

        val args = buildList {
            add("-d")
            add(opts.target_dir.toString())
            add("-jvm-target")
            add(opts.jvm_version.toString())
            add("-Xbackend-threads=${opts.backend_threads}")
            add("-cp")
            add(classpath)
            if (opts.verbose) add("-verbose")
            if (opts.debug) add("-Xdebug")
            if (opts.extra) add("-Wextra")
            if (opts.error) add("-Werror")
            files.forEach { add(it.absolutePath) }
        }
        debug("kotlinc ${args.joinToString(" ")}")

        val daemon_reports = arrayListOf<DaemonReportMessage>()

        val daemon = KotlinCompilerClient.connectToCompileService(
            compilerId = compiler_id,
            clientAliveFlagFile = client_alive_file,
            daemonJVMOptions = DaemonJVMOptions(),
            daemonOptions = DaemonOptions(verbose = opts.verbose),
            reportingTargets = DaemonReportingTargets(out = if (opts.verbose) System.out else null, messages = daemon_reports),
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
            info("Compiled ${files.map{it.name}} in " + TimeUnit.NANOSECONDS.toMillis(end_time - start_time) + " ms")
            return exit_code
        } finally {
            daemon.releaseCompileSession(session_id) 
        }
    }
}

private fun parse_args(args: Array<String>): Opts {
    var pos = 0
    var opts = Opts(kotlin_dir = args.get(pos++).let(Paths::get))
    while(true) {
        when (val arg = args.getOrNull(pos++)) {
            "-debugger" -> opts.debugger = true
            null -> break
        }
    }
    return opts
}

data class Opts(
    val cwd: String = System.getProperty("user.dir"),
    val src_dir: Path = Paths.get(cwd, "example").also { it.toFile().mkdirs() },
    val target_dir: Path = Paths.get(cwd, "out").also { it.toFile().mkdirs() },
    val kotlin_dir: Path = Paths.get(System.getProperty("KOTLIN_HOME"), "libexec/lib"),
    val nob_src: Path = Paths.get(cwd, "nob.kt"),

    val libs: List<Lib> = emptyList(),
    val jvm_version: Int = 21,
    val kotlin_version: String = "2.2.0",

    val backend_threads: Int = 0, // run codegen with N thread per processor (Default 1)
    val verbose: Boolean = false,
    val debug: Boolean = false,
    val error: Boolean = true,
    val extra: Boolean = false,
    var debugger: Boolean = false,
) {
    val main_src: Path = Files.walk(src_dir)
        .filter { it.toFile().isFile() }
        .filter { it.toFile().readText().contains("fun main(") }
        .toList()
        .firstOrNull() ?: error("no main() found")

    fun runtime_classpath(): String {
        val libs_paths = libs.filter { it.scope == "compile" || it.scope == "runtime" }
            .joinToString(File.pathSeparator) { it.jar_path.toAbsolutePath().normalize().toString() }
        val target_dir_path = target_dir.toAbsolutePath().normalize().toString()
        return "$libs_paths:$target_dir_path"
    }

    fun compile_classpath(): String {
        val kotlin_libs_paths = listOf("kotlin-stdlib.jar", "kotlin-compiler.jar", "kotlin-daemon.jar", "kotlin-daemon-client.jar")
            .joinToString(File.pathSeparator) { kotlin_dir.resolve(it).toAbsolutePath().normalize().toString() }
        val libs_paths = libs.filter { it.scope == "compile" }
            .joinToString(File.pathSeparator) { it.jar_path.toAbsolutePath().normalize().toString() }
        val target_dir_path = target_dir.toAbsolutePath().normalize().toString()
        return "$kotlin_libs_paths:$libs_paths:$target_dir_path"
    }

    fun nob_compile_classpath(): String {
        val kotlin_libs_paths = listOf("kotlin-stdlib.jar", "kotlin-compiler.jar", "kotlin-daemon.jar", "kotlin-daemon-client.jar")
            .joinToString(File.pathSeparator) { kotlin_dir.resolve(it).toAbsolutePath().normalize().toString() }
        val target_dir_path = target_dir.toAbsolutePath().normalize().toString()
        return "$kotlin_libs_paths:$target_dir_path"
    }

    fun name(file: File): String {
        return file.toPath().fileName.toString().removeSuffix(".kt").lowercase()
    }

    fun main_class(src: File): String { 
        val pkg = src.useLines { lines -> lines.firstOrNull { it.trim().startsWith("package ") }?.removePrefix("package ")?.trim() }?.replace('.', '/')
        val main = name(src).replaceFirstChar{ it.uppercase() } + "Kt"
        return when (pkg) {
            null -> "$main"
            else -> "$pkg.$main"
        }
    }
}

private val jar_cache_dir = Paths.get(System.getProperty("user.home"), ".nob_cache").also { it.toFile().mkdirs() }

data class Lib(
    val group_id: String,
    val artifact_id: String,
    val version: String,
    val type: String = "jar",
    val scope: String = "compile",
    val repo: String = "https://repo1.maven.org/maven2",
    val jar_path: Path = jar_cache_dir.resolve(group_id).resolve("${artifact_id}-$version.jar")
) {
    val base_url = "$repo/${group_id.replace('.', '/')}/$artifact_id" 
    val pom_url = "$base_url/$version/$artifact_id-$version.pom"
    val jar_url = "$base_url/$version/$artifact_id-$version.jar"
    val module_url = "$base_url/$version/$artifact_id-$version.module"
    fun pom_path(): Path = jar_cache_dir.resolve(group_id).resolve("${artifact_id}_$version.pom")
    val jar_file get() = File("${jar_cache_dir}/${group_id}/${artifact_id}-${version}.jar").also { it.parentFile.mkdirs() }
    val pom_file get() = File("${jar_cache_dir}/${group_id}/${artifact_id}-${version}.pom").also { it.parentFile.mkdirs() }
    val module_file get() = File("${jar_cache_dir}/${group_id}/${artifact_id}-${version}.module").also { it.parentFile.mkdirs() }

    override fun toString() = "$group_id:$artifact_id:$version"
    override fun hashCode(): Int = "${toString()}:$scope".hashCode()
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Lib) return false
        return hashCode() == other.hashCode()
    }

    companion object {
        fun of(str: String) = str.split(':').let { 
            Lib(
                group_id = it[0], 
                artifact_id = it[1], 
                version = it[2], 
                scope = it.getOrElse(3) { "compile" },
            ) 
        }
    }
}

private fun exec(cmd: List<String>, opts: Opts): Int {
    if (opts.verbose) info("exec: $cmd")
    val builder = ProcessBuilder(cmd)
    builder.inheritIO()
    builder.directory(File(opts.cwd))
    val process = builder.start()
    return process.waitFor()
}


private fun download_file(url: URI, file: File) {
    if (file.exists()) {
        debug("download $url ${color("[CACHE]", Color.yellow)}")
        return
    }
    try {
        url.toURL().openStream().use { stream ->
            stream.transferTo(Files.newOutputStream(file.toPath()))
            info("download $url ${color("[OK]", Color.green)}")
        }
    } catch (e: FileNotFoundException) {
        warn("download $url ${color("[FAIL]", Color.red)} - not found ${e.message}")
    } catch (e: Exception) {
        err("download $url ${color("[FAIL]", Color.red)} ${e.message}")
    }
}

private fun download_jar(lib: Lib) {
    if (lib.type != "jar") return
    download_file(URI(lib.jar_url),lib.jar_file)
}

data class ResolvedLib(val lib: Lib, val resolved_from: String)
data class LibKey(val group: String, val artifact: String)

private fun solve_libs(opts: Opts, libs: List<Lib>): List<Lib> {
    val start_time = System.nanoTime()
    val cache_file = opts.target_dir.resolve("libs.cache").toFile()
    val resolved = mutableListOf<ResolvedLib>()
    read_cache(cache_file, resolved)

    val keys = resolved.map { LibKey(it.lib.group_id, it.lib.artifact_id) }.toMutableSet()
    val missing_libs = libs.filter{ LibKey(it.group_id, it.artifact_id) !in keys }

    if (missing_libs.isNotEmpty()) {
        val gradle_resolver = GradleResolver()
        val maven_resolver = MavenResolver()
        val queue = ArrayDeque<Lib>()

        for (lib in libs) {
            val key = LibKey(lib.group_id, lib.artifact_id)
            if (key !in keys) {
                resolved.add(ResolvedLib(lib, "local"))
                keys.add(key)
                queue.add(lib)
                download_jar(lib)
            }
        }

        while(queue.isNotEmpty()) {
            val lib = queue.poll()
            debug("resolve: $lib")
            gradle_resolver.solve_deps(lib)
                .ifEmpty { maven_resolver.solve_deps(lib) }
                .forEach { dep ->
                    val key = LibKey(dep.lib.group_id, dep.lib.artifact_id)
                    if (key !in keys) {
                        resolved.add(dep)
                        keys.add(key)
                        queue.add(dep.lib)
                        download_jar(dep.lib)
                    }
                }
        }

        save_cache(cache_file, resolved)
    }

    val end_time = System.nanoTime()
    info("Resolved ${resolved.size} libs in " + TimeUnit.NANOSECONDS.toMillis(end_time - start_time) + " ms")
    return resolved.map { it.lib }.resolve_kotlin_libs(opts)
}

private fun List<Lib>.resolve_kotlin_libs(opts: Opts): List<Lib> {
    return this
        .filterNot { it.group_id == "org.jetbrains.kotlin" && it.artifact_id == "kotlin-stdlib-common" }
        .map {
            when (it.group_id) {
                "org.jetbrains.kotlin" -> {
                    val local = opts.kotlin_dir.resolve("${it.artifact_id}.jar")
                    if (local.toFile().exists()) {
                        it.copy(version = opts.kotlin_version, jar_path = local)
                    } else {
                        err("dependent on ${it.scope} lib $it but it was not found in ${opts.kotlin_dir} with version ${opts.kotlin_version}. Fallback to version ${it.version}")
                        it // keep original if missing
                    }
                }
                else -> it
            }
    }
}

private fun read_cache(file: File, resolved: MutableList<ResolvedLib>) {
    if (!file.exists()) return
    file.readLines()
        .mapNotNull { line -> 
            val parts = line.split(":")
            if (parts.size != 6) null
            ResolvedLib(
                resolved_from = parts[5],
                lib = Lib(group_id = parts[0], artifact_id = parts[1], version = parts[2], scope = parts[3], type = parts[4]), 
            )
        }.forEach(resolved::add)
}

private fun save_cache(file: File, resolved: List<ResolvedLib>) {
    file.writeText(resolved.joinToString("\n") {
        "${it.lib}:${it.lib.scope}:${it.lib.type}:${it.resolved_from}"
    })
}

class GradleResolver {
    fun solve_deps(lib: Lib): Set<ResolvedLib> {
        val module = download_module(lib) ?: return emptySet()
        val component = module["component"] as Map<*, *>
        val component_group = component["group"] as String
        val variants = module["variants"] as? List<*> ?: return emptySet()
        val libs = mutableSetOf<ResolvedLib>()

        variants.forEach { 
            val variant = it as Map<*, *>
            val attrs = (variant["attributes"] as? Map<*, *>)?.mapNotNull { attr ->
                val key = attr.key as? String
                val value = (attr.value as? String)
                if (key != null && value != null) key to value else null
            }?.toMap() ?: emptyMap()

            if (attrs["org.jetbrains.kotlin.platform.type"] == "jvm" && attrs["org.gradle.category"] == "library") {
                val deps = variant["dependencies"] as? List<*> ?: emptySet<Any?>()
                val scope = when (val usage = attrs["org.gradle.usage"] as? String) {
                    "java-api" -> "compile"
                    "java-runtime" -> "runtime"
                    null -> "compile"
                    else -> "unknown"
                }

                val files = variant["files"] as? List<*> ?: emptyList<Any?>()
                files.forEach { file_info -> 
                    val file_map = file_info as? Map<*, *>
                    if (file_map == null) return@forEach
                    val file_name = file_info["name"] as? String
                    val file_url = file_info["url"] as? String
                    if (file_name != null && file_url != null) {
                        download_file(
                            url = URI(lib.module_url).resolve(file_url), 
                            file = File("${jar_cache_dir}/${component_group}/${file_name}").also { it.parentFile.mkdirs() },
                        )
                    }
                }

                for(d in deps) {
                    val dep = d as Map<*, *>
                    val group = dep["group"] as String
                    val module = dep["module"] as String
                    val version_obj = dep["version"] as? Map<*, *> ?: emptyMap<String, Any?>()
                    val version = listOf("requires", "strictly", "prefers")
                        .firstNotNullOfOrNull { key -> version_obj[key] as? String }
                        ?: "unspecified"

                    val dep_attrs = (dep["attributes"] as? Map<*, *>)?.mapNotNull { dep_attr ->
                        val key = dep_attr.key as? String
                        val value = (dep_attr.value as? String)
                        if (key != null && value != null) key to value else null
                    }?.toMap() ?: emptyMap()

                    val category = dep_attrs["org.gradle.category"] as? String  
                    if (category == "platform") {
                        debug("dependency $group:$module:$version:$scope had category $category, skipping.")
                        continue
                    }
                    libs.add(ResolvedLib(Lib(group, module, version, scope = scope), "gradle"))

                }
                val avail = variant["available-at"] as? Map<*, *>
                if (avail != null) {
                    val group = avail["group"] as String
                    val module = avail["module"] as String
                    val version = avail["version"] as String
                    libs.add(ResolvedLib(Lib(group, module, version, scope = scope), "gradle"))
                }
            }
        }
        return libs
    }

    private fun download_module(lib: Lib): Map<*, *>? {
        return try {
            val text = if (lib.module_file.exists()) {
                lib.module_file.readText()
                    .also { debug("download ${lib.module_url} ${color("[CACHE]", Color.yellow)}") }
            } else {
                URI(lib.module_url).toURL().openStream().bufferedReader().use { it.readText() }
                    .also { lib.module_file.writeText(it) }
                    .also { info("download ${lib.module_url} ${color("[OK]", Color.green)}") }
            }

            val parser = JsonParser(text)
            parser.parse() as Map<*, *>
        } catch (e: FileNotFoundException) {
            debug("download ${lib.module_url} ${color("[FAIL]", Color.red)} - not found, skipping.")
            null
        } catch (e: Exception) {
            err("download ${lib.module_url} ${color("[FAIL]", Color.red)} ${e.message}")
            null
        }
    }
}

class MavenResolver {
    private val pom_cache = mutableMapOf<Lib, Document>()
    private val props_cache = mutableMapOf<Lib, Map<String, String>>()
    private val managed_cache = mutableMapOf<Lib, Map<LibKey, String>>()
    private val boms_cache = mutableMapOf<Lib, Set<Lib>>()

    fun solve_deps(lib: Lib): Set<ResolvedLib> {
        val pom = download_pom(lib) ?: return emptySet()
        val project = pom.getElementsByTagName("project").item(0) as Element
        val dependencies_node = project.get_direct_child("dependencies") ?: return emptySet()
        val dependencies = dependencies_node.getElementsByTagName("dependency")
        val resolved = mutableSetOf<ResolvedLib>()
        for (i in 0 until dependencies.length) {
            val node = dependencies.item(i) as Element
            val group = resolve_property_lazy(node["groupId"], pom) ?: continue
            val artifact = resolve_property_lazy(node["artifactId"], pom) ?: continue
            val scope = node["scope"] ?: find_managed_scope(LibKey(group, artifact), pom)
            val is_optional = node["optional"] == "true"
            val is_test_or_provided = scope in setOf("provided", "test", "system")
            if (is_optional || is_test_or_provided) continue

            val type = node["type"] ?: "jar"
            if (type == "pom" && scope == "import") continue

            val declared_version = node["version"]
            val version = when(declared_version) {
                null -> find_managed_version(LibKey(group, artifact), pom)
                else -> resolve_property_lazy(declared_version, pom)
            }

            if (version != null) {
                resolved.add(ResolvedLib(Lib(group, artifact, version, scope = scope, type = type), "maven"))
            } else {
                warn("Could not resolve version for $group:$artifact:$version")
            }
        }
        return resolved
    }

    private fun get_doc(lib: Lib): Document {
        return pom_cache.getOrPut(lib) { download_pom(lib) ?: error("pom not found") }
    }

    private fun get_parent_lib(pom: Document): Lib? {
        val parent_nodes = pom.getElementsByTagName("parent")
        if (parent_nodes.length == 0) return null
        val parent = parent_nodes.item(0) as Element
        val group_id = parent["groupId"]!!
        val artifact_id = parent["artifactId"]!!
        val version = parent["version"]!!
        val packaging = parent["packaging"] ?: "pom"
        return Lib(group_id, artifact_id, version, type = packaging)
    }

    private fun get_props(pom: Document): Map<String, String> {
        return props_cache.getOrPut(lib(pom)) {
            val res = mutableMapOf<String, String>()
            val props_nodes = pom.getElementsByTagName("properties")
            if (props_nodes.length == 0) return res
            val children = props_nodes.item(0).childNodes
            for (i in 0 until children.length) {
                val node = children.item(i)
                if (node is Element) res[node.tagName] = node.textContent.trim()
            }
            res
        }
    }

    private fun get_managed_libs(pom: Document): Map<LibKey, Lib> {
        val dm = pom.getElementsByTagName("dependencyManagement")
        if (dm.length == 0) return emptyMap()
        val deps_nodes = (dm.item(0) as Element).getElementsByTagName("dependency")
        return List(deps_nodes.length) { i -> deps_nodes.item(i) as Element }
            .mapNotNull { node ->
                val group = node["groupId"] ?: return@mapNotNull null
                val artifact = node["artifactId"] ?: return@mapNotNull null
                val version = node["version"] ?: "" // todo: should be null?
                val type = node["type"] ?: "jar"
                val scope = node["scope"] ?: "compile"
                LibKey(group, artifact) to Lib(group, artifact, version, type, scope)
            }.toMap()
    }

    private fun find_prop(prop_name: String, pom: Document): String? {
        val props = get_props(pom)
        val value = props[prop_name]
        if (value != null) return value.takeIf { !it.matches(Regex("""\$\{(.+?)}""")) } ?: find_prop(value.substringAfter("\${").substringBefore("}"), pom)
        val parent_lib = get_parent_lib(pom)
        return if (parent_lib != null) find_prop(prop_name, get_doc(parent_lib)) else null
    }

    private fun find_managed_version(key: LibKey, pom: Document): String? {
        val managed_deps = get_managed_libs(pom)
        val managed_lib = managed_deps[key]
        if (managed_lib != null) {
            val resolved_version = resolve_property_lazy(managed_lib.version, pom)
            if (resolved_version != null) return resolved_version
        }
        val parent_lib = get_parent_lib(pom)
        if (parent_lib != null) {
            val version = find_managed_version(key, get_doc(parent_lib))
            if (version != null) return version
        }

        val boms = get_boms(pom)
        for (bom_lib in boms) {
            val bom_pom = get_doc(bom_lib)
            val version = find_managed_version(key, bom_pom)
            if (version != null) return version
        }
        return null
    }

    private fun get_boms(pom: Document): Set<Lib> {
        return boms_cache.getOrPut(lib(pom)) {
            val dm = pom.getElementsByTagName("dependencyManagement")
            if (dm.length == 0) return@getOrPut emptySet()
            val dep_node = (dm.item(0) as Element).getElementsByTagName("dependency")
            val boms = mutableSetOf<Lib>()
            for (i in 0 until dep_node.length) {
                val node = dep_node.item(i) as Element
                if (node["type"] == "pom" && node["scope"] == "import") {
                    val group = node["groupId"] ?: continue
                    val artifact = node["artifactId"] ?: continue
                    val version = node["version"] ?: continue
                    boms.add(Lib(group, artifact, version))
                }
            }
            boms
        }
    }

    private fun find_managed_scope(key: LibKey, pom: Document): String {
        val managed_deps = get_managed_libs(pom)
        val managed_lib = managed_deps[key]
        if (managed_lib != null) return managed_lib.scope
        val parent_lib = get_parent_lib(pom)
        if (parent_lib != null) {
            val scope = find_managed_scope(key, get_doc(parent_lib))
            if (scope != "compile") return scope
        } 
        val boms = get_boms(pom)
        for (bom_lib in boms) {
            val scope = find_managed_scope(key, get_doc(bom_lib))
            if (scope != "compile") return scope
        }
        return "compile"
    }

    private fun resolve_property_lazy(prop: String?, pom: Document): String? {
        if (prop == null) return null
        when (prop) {
            "\${project.groupId}" -> return pom.getElementsByTagName("groupId").item(0)?.textContent?.trim() ?: pom.getElementsByTagName("parent").item(0)?.let { it as? Element }?.get("groupId")
            "\${project.artifactId}" -> return pom.getElementsByTagName("artifactId").item(0)?.textContent?.trim()
            "\${project.version}" -> return pom.getElementsByTagName("version").item(0)?.textContent?.trim() ?: pom.getElementsByTagName("parent").item(0)?.let { it as? Element }?.get("version")
        }

        if (!prop.matches(Regex("""\$\{(.+?)}"""))) {
            return prop
        }
        val prop_name = prop.substringAfter("\${").substringBefore("}")
        return find_prop(prop_name, pom)
    }

    private fun lib(pom: Document): Lib {
        val project = pom["project"]
        val groupId = project["groupId"] ?: pom["parent"]["groupId"]!!
        val artifactId = project["artifactId"]!!
        val version = project["version"] ?: pom["parent"]["version"]!!
        return Lib(groupId, artifactId, version, type = "pom")
    }

    private val problematic_html_entities = mapOf(
        "&oslash;" to "ø", 
        "&Oslash;" to "Ø",
        "&auml;" to "ä",
        "&ouml;" to "ö",
        "&uuml;" to "ü",
        "&Auml;" to "Ä",
        "&Ouml;" to "Ö",
        "&Uuml;" to "Ü",
        "&apos;" to "'"
    )

    private fun String.sanitize(): String = problematic_html_entities.entries
        .fold(this) { acc, (k, v) -> acc.replace(k, v) }  
        .replace(Regex("<[^>]*@[^>]*>"), "") // remove <abc@abc>
        .replace(Regex("&(?!amp;)(?!lt;)(?!gt;)(?!quot;)(?!apos;).+;"), "") // remove malformed tags

    private fun download_pom(lib: Lib): Document? {
        fun document(text: String) = DocumentBuilderFactory.newInstance()
            .apply { 
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
                isNamespaceAware = true
            }
            .newDocumentBuilder()
            .parse(text.byteInputStream())
            .apply { documentElement.normalize() }


        try {
            if (lib.pom_file.exists()) {
                return document(lib.pom_file.readText().sanitize())
                    .also { debug("download ${lib.pom_url} ${color("[CACHE]", Color.yellow)}") }
            }
            val text = URI(lib.pom_url).toURL().openStream().bufferedReader().use { it.readText() }.sanitize()
            lib.pom_file.writeText(text)
            return document(text)
                .also { info("download ${lib.pom_url} ${color("[OK]", Color.green)}") }
        } catch (e: FileNotFoundException) {
            debug("download ${lib.pom_url} ${color("[FAIL]", Color.red)} - not found, skipping.")
            return null
        } catch (e: Exception) {
            err("download ${lib.pom_url} ${color("[FAIL]", Color.red)} ${e.message}")
            return null
        }
    }
}

operator fun Document.get(tag_name: String): Element {
    return getElementsByTagName(tag_name).item(0) as Element
}

operator fun Element.get(tag_name: String): String? {
    val nodes = getElementsByTagName(tag_name)
    if (nodes.length == 0) return null
    return nodes.item(0)?.textContent?.trim()
}

fun Element.get_direct_child(tag_name: String): Element? {
    val children = this.childNodes
    for (i in 0 until children.length) {
        val node = children.item(i)
        if (node.nodeType == Node.ELEMENT_NODE && node.nodeName == tag_name) {
            return node as Element
        }
    }
    return null
}

fun debug(msg: String) { if (DEBUG) println("${color("[DEBUG]", Color.yellow)} $msg") }
fun info(msg: String) { println("${color("[INFO]", Color.cyan)} $msg") }
fun warn(msg: String) { println("${color("[WARN]", Color.magenta)} $msg") }
fun err(msg: String) { println("${color("[ERR]", Color.red)} $msg") }

private fun color(text: Any, color: Color) = "${color.code}$text${Color.reset.code}" 

private enum class Color(val code: String) {
    reset("\u001B[0m"),
    red("\u001B[31m"),
    green("\u001B[32m"),
    yellow("\u001B[33m"),
    blue("\u001B[34m"),
    magenta("\u001B[35m"),
    cyan("\u001B[36m")
}

class JsonParser(private val text: String) {
    private var i = 0

    fun parse(): Any? = parse_value().also { skip_whitespace() }

    private fun parse_value(): Any? {
        skip_whitespace()
        return when (peek()) {
            '{' -> parse_object()
            '[' -> parse_array()
            '"' -> parse_string()
            in '0'..'9', '-' -> parse_number()
            't' -> parse_literal("true", true)
            'f' -> parse_literal("false", false)
            'n' -> parse_literal("null", null)
            else -> error("unexpected char '${peek()}' at $i")
        }
    }

    private fun parse_object(): Map<String, Any?> {
        expect('{')
        val map = mutableMapOf<String, Any?>()
        skip_whitespace()
        if (peek() == '}') { i++; return map }
        while(true) {
            skip_whitespace()
            val key = parse_string()
            skip_whitespace(); 
            expect(':')
            val value = parse_value()
            map[key] = value
            skip_whitespace()
            if (peek() == '}') { i++; break }
            expect(',')
        }
        return map
    }

    private fun parse_array(): List<Any?> {
        expect('[')
        val list = mutableListOf<Any?>()
        skip_whitespace()
        if (peek() == ']') { i++; return list }
        while(true) {
            val value = parse_value()
            list.add(value)
            skip_whitespace()
            if (peek() == ']') { i++; break }
            expect(',')
        }
        return list
    }

    private fun parse_string(): String {
        expect('"')
        val sb = StringBuilder()
        while(true) {
            when (val c = text[i++]) {
                '"' -> break
                '\\' -> {
                    sb.append(
                        when(val escape = text[i++]) {
                            '"', '\\', '/' -> escape
                            'b' -> '\b'
                            'n' -> '\n'
                            'r' -> '\r'
                            't' -> '\t'
                            'f' -> '\u000C'
                            'u' -> {
                                val hex = text.substring(i, i + 4)
                                i += 4
                                hex.toInt(16).toChar()
                            }
                            else -> error("bad escape: $escape")
                        }
                    )
                }
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun parse_number(): Number {
        val start = i
        while (i < text.length && (text[i].isDigit() || text[i] in "-+eE.")) i++
        val num_str = text.substring(start, i)
        return if (num_str.contains('.') || num_str.contains('e') || num_str.contains('E')) {
            num_str.toDouble()
        } else {
            num_str.toLong()
        }
    }

    private fun parse_literal(lit: String, value: Any?): Any? {
        require(text.startsWith(lit, i)) { "Expected $lit at $i" }
        i += lit.length
        return value
    }

    private fun skip_whitespace() {
        while (i < text.length && text[i].isWhitespace()) i++
    }

    private fun expect(c: Char) {
        if (peek() != c) error("Expected '$c' at $i but got '${peek()}'")
        i++
    }

    private fun peek(): Char = if (i < text.length) text[i] else '\u0000'
}

