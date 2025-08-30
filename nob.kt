package nob

import java.io.*
import java.lang.ProcessBuilder
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.function.*
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.jetbrains.kotlin.cli.common.messages.MessageRenderer
import org.jetbrains.kotlin.cli.common.messages.PrintingMessageCollector
import org.jetbrains.kotlin.daemon.client.*
import org.jetbrains.kotlin.daemon.common.*
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.NodeList

const val DEBUG = true

private fun compile_target(opts: Opts): Int {

    val lib_tree = Nob.resolve_libs(
        Lib.of("io.ktor:ktor-server-netty:3.2.2"),
        // Lib.of("io.ktor:ktor-server-core:3.2.2"),
    )

    val final_libs = resolve_conflicts(lib_tree).resolve_kotlin_libs(opts)

    Nob.print_libs(lib_tree)
    final_libs.forEach { lib -> info(lib.toString())}

    val opts = opts.copy(libs = final_libs)

    // info(opts.compile_classpath.replace(':', '\n'))

    var exit_code =  Nob.compile(opts, opts.src_file)
    if (exit_code == 0) exit_code = run_target(opts)
    return exit_code
}

fun main(args: Array<String>) {
    val opts = Opts(kotlin_lib = args.get(0).let(Paths::get))
    compile_self(opts)
    System.exit(compile_target(opts))
}

data class Opts(
    val src_file: String = "main.kt",
    val nob_src_file: String = "nob.kt",
    val kotlin_lib: Path, 
    val libs: Set<Lib> = emptySet(),
    val out_dir: String = "out",
    val jvm_target: Int = 21,
    val kotlin_target: String = "2.2.0",
    val backend_threads: Int = 1, // run codegen with N thread per processor (Default 1)
    val verbose: Boolean = false,
    val debug: Boolean = false,
    val error: Boolean = true,
    val extra: Boolean = true,
) {
    val cwd: String = System.getProperty("user.dir")
    val target_dir: Path = Paths.get(cwd, out_dir).also { it.toFile().mkdirs() }

    // TODO: Do I need to include kotlin-stdlib and/or others if missing? 
    //       Are they always required but not always a transitive dependency?
    val runtime_classpath: String = (
        libs.filter { it.scope == "runtime" || it.scope == "compile" }.map { it.jar_path } + 
        listOf(target_dir)
    ).map { it.toAbsolutePath().normalize().toString() }.joinToString(":")

    val compile_classpath: String = (
        libs.filter { it.scope == "compile" }.map { it.jar_path } + 
        listOf("kotlin-stdlib.jar", "kotlin-compiler.jar", "kotlin-daemon.jar", "kotlin-daemon-client.jar").map { kotlin_lib.resolve(it)} + 
        listOf(target_dir)
    ).map { it.toAbsolutePath().normalize().toString() }
    .toSet()
    .joinToString(File.pathSeparator)

    fun name(src_file: String) = src_file.removeSuffix(".kt").lowercase()
    fun src(src_file: String): Path = Paths.get(cwd, src_file).normalize()
    fun main_class(src_file: String): Path { 
        val src = src(src_file).toFile()
        val pkg = src.useLines { lines -> lines.firstOrNull { it.trim().startsWith("package ") }?.removePrefix("package ")?.trim() }
        val class_name = name(src_file).replaceFirstChar{ it.uppercase() } + "Kt.class"
        return when (pkg) {
            null -> target_dir.resolve(class_name)
            else -> target_dir.resolve(pkg.replace('.', '/')).resolve(class_name)
        }
    }
    fun main_path(src_file: String): Path = Paths.get(cwd, src_file)
}

private val jar_cache_dir = Paths.get(System.getProperty("user.home"), ".nob_cache").also { it.toFile().mkdirs() }

data class Lib(
    val group_id: String,
    val artifact_id: String,
    val version: String,
    val type: String = "jar",
    val scope: String = "compile",
    val repo: String = "https://repo1.maven.org/maven2",
    val jar_path: Path = jar_cache_dir.resolve(group_id).resolve("${artifact_id}_$version.jar")
) {
    val base_url = "$repo/${group_id.replace('.', '/')}/$artifact_id" 
    val pom_url = "$base_url/$version/$artifact_id-$version.pom"
    val jar_url = "$base_url/$version/$artifact_id-$version.jar"
    fun pom_path(): Path = jar_cache_dir.resolve(group_id).resolve("${artifact_id}_$version.pom")
    val jar_file get() = File("${jar_cache_dir}/${group_id}/${artifact_id}_${version}.jar").also { it.parentFile.mkdirs() }
    val pom_file get() = File("${jar_cache_dir}/${group_id}/${artifact_id}_${version}.pom").also { it.parentFile.mkdirs() }

    override fun toString() = "$group_id:$artifact_id:$version"

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

data class LibNode(val lib: Lib, val children: MutableList<LibNode> = mutableListOf()) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LibNode) return false
        return lib == other.lib
    }
    override fun hashCode(): Int = lib.hashCode()
}

object Nob {
    fun release(opts: Opts): Int {
        val opts = opts.copy(debug = false, error = true)
        val name = opts.name(opts.src_file)
        val cmd = listOf("jar", "cfe", "${name}.jar", "${name}Kt", "-C", "${opts.target_dir}", ".")
        if (opts.verbose) info("$cmd")
        info("package ${name}.jar")
        return exec(cmd, opts)
    }

    fun compile(
        opts: Opts,
        src_file: String,
        backup: Boolean = false,
    ): Int {
        val src = opts.src(src_file)
        val main_class = opts.main_class(src_file)
        val src_modified = Files.getLastModifiedTime(src).toInstant() 
        val main_class_modified = if (Files.exists(main_class)) Files.getLastModifiedTime(main_class).toInstant() else Instant.EPOCH
        if (src_modified > main_class_modified) {
            if (backup) backup(opts)
            return compile_with_daemon(opts, src_file) 
        } 
        return 0
    }

    fun backup(opts: Opts) {
        val main_class = opts.main_class(opts.nob_src_file).toFile()
        if (main_class.exists()) {
            info("backup $main_class")
            val backup = File(main_class.parentFile, main_class.name + ".bak")
            main_class.copyTo(backup, overwrite = true)
        }
    }

    fun restore(opts: Opts) {
        val main_class = opts.main_class(opts.nob_src_file).toFile()
        val backup = File(main_class.parentFile, opts.name(opts.nob_src_file) + ".bak")
        if (backup.exists()) {
            info("restoring $main_class")
            backup.copyTo(main_class, overwrite = true) 
        }
    }

    fun resolve_libs(vararg libs: Lib): LibNode {
        val root = LibNode(Lib("root", "root", "0.0.0"))
        val resolved_libs = mutableMapOf<Pair<String, String>, LibNode>()
        libs.forEach { lib ->
            val node = resolve_recursive(lib, resolved_libs)
            if (node != null) root.children.add(node)
        }
        return root
    }

    fun print_libs(root: LibNode) {
        println(color("LIBS", Color.green))
        print_recursive(root, "")
    }
}

private fun compile_self(opts: Opts) {
    try {
        require(Nob.compile(opts, opts.nob_src_file, backup = true) == 0) { "Failed to compile nob." }
        val nob_class_name = opts.name(opts.nob_src_file).replaceFirstChar { it.uppercase() } + "Kt.class"
        val nob_class_path = opts.target_dir.resolve("nob").resolve(nob_class_name)
        require(Files.exists(nob_class_path)) { "$nob_class_path not found after compilation!"}
    } catch(e: Exception) {
        e.printStackTrace()
        Nob.restore(opts)
    }
}

private fun run_target(opts: Opts): Int {
    info("Running ${opts.name(opts.src_file)}...")
    val main_class = opts.name(opts.src_file).replaceFirstChar { it.uppercase() } + "Kt"

    // warn("runtime classpath: ${opts.runtime_classpath}")
    return exec(listOf("java", "-cp", opts.runtime_classpath, main_class), opts)
}

private fun exec(cmd: List<String>, opts: Opts): Int {
    if (opts.verbose) info("exec: $cmd")
    val builder = ProcessBuilder(cmd)
    builder.inheritIO()
    builder.directory(File(opts.cwd))
    val process = builder.start()
    return process.waitFor()
}

private fun compile_with_daemon(opts: Opts, src_file: String): Int {
    val compiler_id = CompilerId.makeCompilerId(
        Files.list(opts.kotlin_lib)
            .filter { it.toString().endsWith(".jar") } 
            .map { it.toFile() }
            .toList()
    )
    val daemon_opts = DaemonOptions(verbose = opts.verbose)
    val daemon_jvm_opts = DaemonJVMOptions()
    val client_alive_file = File("${opts.out_dir}/.alive").apply { if (!exists()) createNewFile() }
    // warn("compile classpath: ${opts.compile_classpath}")

    val args = mutableListOf(
        File(src_file).absolutePath,
        "-d", opts.target_dir.toString(),
        "-jvm-target", opts.jvm_target.toString(),
        "-Xbackend-threads=${opts.backend_threads}",
        "-cp", opts.compile_classpath,
    )
    // info("kotlinc args: $args")
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
        info("Compiled $src_file in " + TimeUnit.NANOSECONDS.toMillis(end_time - start_time) + " ms")
        return exit_code
    } finally {
        daemon.releaseCompileSession(session_id) 
    }
}

private fun resolve_conflicts(root: LibNode): Set<Lib> {
    val resolved = mutableMapOf<Pair<String, String>, Lib>()
    val queue = ArrayDeque<LibNode>()
    val visited = mutableSetOf<LibNode>()
    queue.add(root)
    visited.add(root)
    // BFS
    while (queue.isNotEmpty()) {
        val node = queue.poll()
        val lib = node.lib
        val ga = lib.group_id to lib.artifact_id
        val existing_lib = resolved[ga]
        if (existing_lib == null || CompareVersion(lib.version.split("[.-]".toRegex())).compareTo(CompareVersion(existing_lib.version.split("[.-]".toRegex()))) > 0) {
            resolved[ga] = lib
        }
        node.children.forEach { child -> 
            if (visited.add(child)) {
                queue.add(child)
            }
        }
    }
    val final_libs = resolved.values.filter { download_jar(it) }.toSet()
    return final_libs
}

private fun Set<Lib>.resolve_kotlin_libs(opts: Opts): Set<Lib> =
    filterNot { it.group_id == "org.jetbrains.kotlin" && it.artifact_id == "kotlin-stdlib-common"}
    .map { lib ->
        if (lib.group_id == "org.jetbrains.kotlin") {
            val local = opts.kotlin_lib.resolve("${lib.artifact_id}.jar")
            if (local.toFile().exists()) {
                lib.copy(version = opts.kotlin_target, jar_path = local)
            } else {
                err("dependent on ${lib.scope} lib $lib but it was not found in ${opts.kotlin_lib} with version ${opts.kotlin_target}, fallback to $lib")
                lib // keep original if missing
            }
        } else {
            lib // keep non-kotlin libs
        }
    }.toSet()

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

private fun print_recursive(node: LibNode, indent: String, visited: MutableSet<LibNode> = mutableSetOf()) {
    if (!visited.add(node)) {
        println("${indent}[cycle detected] ${node.lib}")
        return
    }
    println("${indent}${node.lib}")
    node.children.forEach { child -> print_recursive(child, "$indent    ", visited) }
}

private fun resolve_recursive(
    lib: Lib,
    resolved_libs: MutableMap<Pair<String, String>, LibNode>,
): LibNode? {
    debug("resolve $lib [ ]")
    val ga = lib.group_id to lib.artifact_id
    val existing_node = resolved_libs[ga]
    if (existing_node != null) return existing_node
    val node = LibNode(lib)
    resolved_libs[ga] = node
    val pom = download_pom(lib) ?: return null
    val props = pom.props(lib)

    // Process parent POM first to inherit properties and managed dependencies
    val parent_lib = parent(pom)
    if (parent_lib != null) {
        resolve_recursive(parent_lib, resolved_libs)
        download_pom(parent_lib)?.let { parent_pom ->
            val parent_props = parent_pom.props(parent_lib)
            props.merge_props(parent_props)
        }
    }

    // Get managed dependencies, including BOMs, from the current POM
    val boms_to_fetch = mutableSetOf<Lib>()
    debug("resolving managed dependencies [ ] ($lib)")
    val managed = pom.managed(props, boms_to_fetch)
    debug("resolving managed dependencies [X] ($lib)")

    // Process imported BOMs recursively
    debug("resolving BOMs [ ] ($lib)")
    boms_to_fetch.forEach { bom -> resolve_recursive(bom, resolved_libs) }
    debug("resolving BOMs [X] ($lib)")

    // Get the direct dependencies from the current POM, applying the full set of resolved properties and managed dependencies from the entire parent/BOM hierarchy.
    debug("resolving direct dependencies [ ] ($lib)")
    val direct_dependencies = pom.deps(props, managed)
    direct_dependencies.forEach { dep -> 
        val child_node = resolve_recursive(dep, resolved_libs)
        if (child_node != null) node.children.add(child_node)
    }
    debug("resolving direct dependencies [X] ($lib)")

    download_jar(lib)
    debug("resolve $lib [X]")
    return node
}

private fun parent(doc: Document): Lib? {
    val parent_nodes = doc.getElementsByTagName("parent")
    if (parent_nodes.length == 0) return null
    val parent = parent_nodes.item(0) as Element
    val group_id = parent.getElementsByTagName("groupId").item(0).textContent.trim()
    val artifact_id = parent.getElementsByTagName("artifactId").item(0).textContent.trim()
    val version = parent.getElementsByTagName("version").item(0).textContent.trim()
    val packaging = parent.getOrDefault("packaging", emptyMap(), "jar") ?: "jar"
    return Lib(group_id, artifact_id, version, type = packaging)
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
    // "&amp;" to "&",
    // "&lt;" to "<",
    // "&gt;" to ">",
    // "&quot;" to "\"",
    "&apos;" to "'"
)

private fun String.sanitize(): String = problematic_html_entities.entries
    .fold(this) { acc, (k, v) -> acc.replace(k, v) }  
    .replace(Regex("<[^>]*@[^>]*>"), "") // remove <abc@abc>
    .replace(Regex("&(?!amp;)(?!lt;)(?!gt;)(?!quot;)(?!apos;).+;"), "") // remove malformed tags

private fun download_pom(lib: Lib): Document? {
    debug("downloading ${lib.pom_url}")
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

    if (lib.pom_file.exists()) {
        return document(lib.pom_file.readText().sanitize())
    }

    try {
        val text = URI(lib.pom_url).toURL().openStream().bufferedReader().use { it.readText() }.sanitize()
        lib.pom_file.writeText(text)
        return document(text)
    } catch (e: FileNotFoundException) {
        debug("could not find POM for $lib at ${lib.pom_url}. skipping.")
        return null
    } catch (e: Exception) {
        err("failed to download POM for $lib: ${e.message}")
        return null
    }
}

private fun download_jar(lib: Lib): Boolean {
    if (lib.jar_file.exists()) return true
    if (lib.type == "jar") return false
    try {
        URI(lib.jar_url).toURL().openStream().use { stream ->
            stream.transferTo(Files.newOutputStream(lib.jar_file.toPath()))
            info("[OK] download $lib")
            return true
        }
    } catch (e: FileNotFoundException) {
        warn("[FAIL] $lib not found: ${e.message}")
        return false
    } catch (e: Exception) {
        err("[FAIL] download $lib $e")
        return false
    }
}

private fun Document.props(lib: Lib): MutableMap<String, String> {
    debug("looking for properties in ${lib.pom_file}")
    val props = mutableMapOf<String, String>()
    fun collect_props(element: Element) {
        val children = element.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node is Element) props[node.tagName] = node.textContent.trim()
        }
    }
    val proj = getElementsByTagName("project").item(0) as Element
    val properties_nodes = proj.getElementsByTagName("properties")
    for (i in 0 until properties_nodes.length) {
        val prop_element = properties_nodes.item(i) as Element
        collect_props(prop_element)
    }
    props.forEach { (k, v) -> debug("found $k = $v")}
    val group = proj.getElementsByTagName("groupId").item(0)?.textContent?.trim()
    val artifact = proj.getElementsByTagName("artifactId").item(0)?.textContent?.trim()
    val version = proj.getElementsByTagName("version").item(0)?.textContent?.trim()
    props["project.groupId"] = group ?: lib.group_id 
    props["pom.groupId"]= group ?: lib.group_id
    props["project.artifactId"]= artifact ?: lib.artifact_id
    props["pom.artifactId"]= artifact ?: lib.artifact_id
    props["project.version"]= version ?: lib.version
    props["pom.version"]= version ?: lib.version
    return props
}

// TODO: specify scope when resolving, .e.g. test or include provided
private fun Document.deps(
    props: MutableMap<String, String>,
    managed: MutableMap<Pair<String, String>, String>,
): Set<Lib> {
    val libs = mutableSetOf<Lib>()
    val dependency = getElementsByTagName("dependency")
    for (i in 0 until dependency.length) {
        val node = dependency.item(i) as Element
        val group = node.getOrDefault("groupId", props)
        val artifact = node.getOrDefault("artifactId", props)
        if (group == null || artifact == null) {
            warn("unresolvable lib found ($group:$artifact)")
            continue
        }
        var version = node.getOrDefault("version", props)
        if (version == null) {
            version = managed[group to artifact]
            if (version == null) {
                debug("no version for lib found ($group:$artifact)")
                continue
            }
        }
        val type = node.getOrDefault("type", props, "jar") ?: "jar"
        val scope = node.getOrDefault("scope", props, "compile") ?: "compile"
        val optional = node.getOrDefault("optional", props) == "true"
        if (optional) {
            debug("skipping optional lib $group:$artifact:$version")
            continue
        }
        if (scope == "test" || scope == "provided") {
            debug("skipping $scope lib $scope$group:$artifact:$version")
            continue
        }
        if (type == "pom" && scope == "import") {
            debug("lib was bom ($group:$artifact:$version) skipping...")
            continue
        }
        val resolved_version = replace_prop(version!!, props)
        if (resolved_version == null) {
            err("unresolvable version for $group:$artifact")
            continue
        }

        libs.add(Lib(group, artifact, resolved_version, type, scope))
    }
    return libs
        .groupBy { it.group_id to it.artifact_id }
        .map { (ga, libs) -> 
            val (group, artifact) = ga
            val version = managed[ga]
            if (version == null) {
                // libs.forEach { it -> debug("managed candidate: ${it}")}
                val lib_with_max_version = libs.maxBy { lib -> CompareVersion(lib.version.split("[.-]".toRegex()).map { it.toIntOrNull() ?: it }) }
                debug("$group:$artifact was not managed, resolved version ${lib_with_max_version.version}")
                lib_with_max_version
            } else {
                val resolved_managed_version = replace_prop(version, props)
                if (resolved_managed_version == null) {
                    err("$group:$artifact was managed, unresolved version")
                    Lib(group, artifact, "unresolved") // return a dummy to avoid crash
                } else {
                    val maybe_lib = libs.firstOrNull { it.version == version }
                    val type = maybe_lib?.type ?: "jar".also { warn("no type and scope found for managed dependency $group:$artifact:$version, assuming 'jar' and 'compile")}
                    val scope = maybe_lib?.scope ?: "compile"
                    debug("$group:$artifact:$version was managed")
                    Lib(group, artifact, resolved_managed_version, type, scope)
                }
            }
        }
        .toSet()
}

private data class CompareVersion(val parts: List<Any>): Comparable<CompareVersion> {
    override fun compareTo(other: CompareVersion): Int {
        val maxSize = maxOf(parts.size, other.parts.size)
        for (i in 0 until maxSize) {
            val a = parts.getOrNull(i)
            val b = other.parts.getOrNull(i)
            if (a == null) return -1
            if (b == null) return 1
            val cmp = when {
                a is Int && b is Int -> a.compareTo(b)
                else -> a.toString().compareTo(b.toString())
            }
            if (cmp != 0) return cmp
        }
        return 0
    }
}

private fun Document.managed(
    props: MutableMap<String, String>,
    boms_to_fetch: MutableSet<Lib>,
): MutableMap<Pair<String, String>, String> {
    val managed = mutableMapOf<Pair<String, String>, String>()
    val dm = getElementsByTagName("dependencyManagement")
    if (dm.length == 0) return managed
    val dependency = (dm.item(0) as Element).getElementsByTagName("dependency")
    for (i in 0 until dependency.length) {
        val node = dependency.item(i) as Element
        val group = node.getOrDefault("groupId", props)
        val artifact = node.getOrDefault("artifactId", props)
        val version = node.getOrDefault("version", props)
        val type = node.getOrDefault("type", props, "jar") ?: "jar"
        val scope = node.getOrDefault("scope", props, "compile") ?: "compile"
        if (group == null || artifact == null) {
            warn("unresolvable managed dependency found ($group:$artifact)")
            continue
        }
        val resolved_version = replace_prop(version!!, props)
        if (resolved_version == null) {
            err("unresolvable version for $group:$artifact")
            continue
        }
        if (type == "pom" && scope == "import") {
            boms_to_fetch.add(Lib(group, artifact, resolved_version, type, scope))
            continue
        }
        // TODO: should we add even if it is ${property.version}?
        managed[group to artifact] = resolved_version
    }
    return managed
}

private fun Element.getOrDefault(
    tag: String,
    props: Map<String, String>,
    default: String? = null,
): String? {
    val node = getElementsByTagName(tag)
    if (node.length == 0) return default
    val raw = node.item(0).textContent.trim()
    return replace_prop(raw, props, default)
}

private fun replace_prop(
    raw: String,
    props: Map<String, String>,
    default: String? = null,
): String? {
    if (raw.isEmpty()) return default
    return raw.replace(Regex("""\$\{(.+?)}""")) { m -> 
        val key = m.groupValues[1]
        props[key] ?: m.value.also { err("unresolved property $key for $raw") }
    }.ifEmpty { default }
}

private fun MutableMap<String, String>.merge_props(other: MutableMap<String, String>) {
    other.forEach { (key, value) -> this.putIfAbsent(key, value) }
}

private fun MutableMap<Pair<String, String>, String>.merge_managed(other: MutableMap<Pair<String, String>, String>) {
    other.forEach { (key, value) -> this.putIfAbsent(key, value) }
}

private fun debug(msg: String) { if (DEBUG) println("${color("[DEBUG]", Color.yellow)} $msg") }
private fun info(msg: String) { println("${color("[INFO]", Color.cyan)} $msg") }
private fun warn(msg: String) { println("${color("[WARN]", Color.magenta)} $msg") }
private fun err(msg: String) { println("${color("[ERR]", Color.red)} $msg") }
