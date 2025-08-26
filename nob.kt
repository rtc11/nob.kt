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

const val DEBUG = false

private fun compile_target(opts: Opts): Int {
    val opts = opts.copy(
        libs = Nob.resolve_libs(
            listOf(
                Lib.of("io.ktor:ktor-server-netty:3.2.2"),
            ),
            opts
        )
    )
    val exit_code =  Nob.compile(opts, opts.src_file)
    run_target(opts)
    return exit_code
}

fun main(args: Array<String>) {
    val opts = Opts(kotlin_libs = args.get(0).split(':').map(Paths::get))
    compile_self(opts)
    System.exit(compile_target(opts))
}

data class Opts(
    val src_file: String = "main.kt",
    val nob_src_file: String = "nob.kt",
    val kotlin_libs: List<Path>, 
    val libs: Set<Lib> = emptySet(),
    val out_dir: String = "out",
    val jvm_target: Int = 21,
    val backend_threads: Int = 1, // run codegen with N thread per processor (Default 1)
    val verbose: Boolean = false,
    val debug: Boolean = false,
    val error: Boolean = false,
    val extra: Boolean = true,
) {
    val cwd: String = System.getProperty("user.dir")
    val target_dir: Path = Paths.get(cwd, out_dir).also { it.toFile().mkdirs() }

    val classpath: String = (
        kotlin_libs.map { it.toString() } + 
        libs.map { it.jar_path().toString() } + 
        target_dir.toString()
    ).joinToString(":")   

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

data class Lib(
    val group_id: String,
    val artifact_id: String,
    val version: String,
    val repo: String = "https://repo1.maven.org/maven2",
    val type: String = "jar",
    val scope: String = "compile",
) {
    val base_url = "$repo/${group_id.replace('.', '/')}/$artifact_id" 
    val pom_url = "$base_url/$version/$artifact_id-$version.pom"
    val jar_url = "$base_url/$version/$artifact_id-$version.jar"
    fun jar_path(): Path = jar_cache_dir.resolve("${group_id}_${artifact_id}_$version.jar")
    fun pom_path(): Path = jar_cache_dir.resolve("${group_id}_${artifact_id}_$version.pom")

    override fun toString() = "$group_id:$artifact_id:$version"

    companion object {
        fun of(str: String) = str.split(':').let { 
            Lib(
                group_id = it[0], 
                artifact_id = it[1], 
                version = it[2], 
                type = it.getOrElse(3) { "compile" },
            ) 
        }
    }
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

    fun resolve_libs(libs: List<Lib>, opts: Opts): Set<Lib> {
        val cached = load_cached_libs().toMutableSet()
        val resolved = resolve_transitive(libs, seen = cached)
        for (lib in resolved) download_jar(lib)
        cached.addAll(resolved)
        save_libs(cached)
        return resolved
    }

    fun print_tree(vararg libs: Lib) {
        val seen = mutableSetOf<Lib>()
        print_tree(libs.toSet(), seen = seen)
        info("Resolved ${color(seen.size, Color.green)} total dependencies.") 
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

private fun run_target(opts: Opts) {
    info("Running ${opts.name(opts.src_file)}...")
    val main_class = opts.name(opts.src_file).replaceFirstChar { it.uppercase() } + "Kt"
    exec(listOf("java", "-cp", opts.classpath, main_class), opts)
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
    val compiler_id = CompilerId.makeCompilerId(opts.kotlin_libs.map { it.toFile() })
    val daemon_opts = DaemonOptions(verbose = opts.verbose)
    val daemon_jvm_opts = DaemonJVMOptions()
    val client_alive_file = File("${opts.out_dir}/.alive").apply { if (!exists()) createNewFile() }

    val args = mutableListOf(
        File(src_file).absolutePath,
        "-d", opts.target_dir.toString(),
        "-jvm-target", opts.jvm_target.toString(),
        "-Xbackend-threads=${opts.backend_threads}",
        "-cp", opts.classpath,
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

private enum class Color(val code: String) {
    reset("\u001B[0m"),
    red("\u001B[31m"),
    green("\u001B[32m"),
    yellow("\u001B[33m"),
    blue("\u001B[34m"),
    magenta("\u001B[35m"),
    cyan("\u001B[36m")
}
private fun color(text: Any, color: Color) = "${color.code}$text${Color.reset.code}" 

private val pom_cache = mutableMapOf<String, org.w3c.dom.Document>()
private val jar_cache_dir = Paths.get(System.getProperty("user.home"), ".nob_cache").also { it.toFile().mkdirs() }

private fun download(lib: Lib): List<Lib> {
    val doc = try {
        download_pom(lib)
    } catch (e: Exception) {
        warn("$lib not found. Skipping...")
        return emptyList()
    }
    val props = Properties.collect(doc, lib)
    val managed = collect_managed_versions(doc, lib).toMutableMap()
    val dep_nodes = doc.getElementsByTagName("dependency")
    val libs = (0 until dep_nodes.length)
        .mapNotNull { (dep_nodes.item(it) as org.w3c.dom.Element).to_lib(props, managed, lib) }
        .filterNot { it.scope in listOf("test", "provided")}
    return libs
}

private val common_problematic_html_entities = mapOf(
    "&oslash;" to "ø", "&Oslash;" to "Ø", "&auml;" to "ä", "&ouml;" to "ö", "&uuml;" to "ü", "&Auml;" to "Ä",
    "&Ouml;" to "Ö", "&Uuml;" to "Ü", "&amp;" to "&", "&lt;" to "<", "&gt;" to ">", "&quot;" to "\"", "&apos;" to "'"
)

private fun download_pom(lib: Lib): org.w3c.dom.Document {
    pom_cache[lib.toString()]?.let { return it }
    val pom_file = jar_cache_dir.resolve(lib.toString() + ".pom")
    val text = if (pom_file.toFile().exists()) {
        pom_file.toFile().readText()
    } else {
        val stream = URI(lib.pom_url).toURL().openStream()
        val t = stream.bufferedReader().use { it.readText() }
        pom_file.toFile().writeText(t)
        t
    }
    var fixed_text = common_problematic_html_entities.entries.fold(text) { acc, (k, v) -> acc.replace(k, v) }
    fixed_text = fixed_text.replace(Regex("&(?![a-zA-Z]+;|#\\d+;|#x[0-9a-fA-F]+;)"), "&amp;")
    fixed_text = fixed_text.replace(Regex("<\\?xml[^>]*\\?>"), "")
    val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
    val doc = factory.newDocumentBuilder().parse(fixed_text.byteInputStream())
    doc.documentElement.normalize()

    // save to memory
    pom_cache[lib.toString()] = doc
    return doc
} 

private fun download_jar(lib: Lib) {
    if (lib.artifact_id.endsWith("-common") || lib.artifact_id.endsWith("-metadata")) return
    val jar_path = jar_cache_dir.resolve("${lib.group_id}_${lib.artifact_id}_${lib.version}.jar")
    if (!jar_path.toFile().exists()) {
        try {
            URI(lib.jar_url).toURL().openStream().use { it.transferTo(Files.newOutputStream(jar_path)) }
            info("[OK] download ${lib.jar_url}")
        } catch (e: Exception) {
            info("[FAIL] download ${lib.jar_url}")
        }
    }
}

private fun parse_parent(doc: org.w3c.dom.Document): Lib? {
    val parent_nodes = doc.getElementsByTagName("parent")
    if (parent_nodes.length == 0) return null
    val parent = parent_nodes.item(0) as org.w3c.dom.Element
    val group_id = parent.getElementsByTagName("groupId").item(0).textContent
    val artifact_id = parent.getElementsByTagName("artifactId").item(0).textContent
    val version = parent.getElementsByTagName("version").item(0).textContent
    return Lib(group_id, artifact_id, version)
}

private fun collect_managed_versions(
    doc: org.w3c.dom.Document,
    lib: Lib,
    props: Map<String, String>? = null,
): Map<Pair<String, String>, String> {
    val props = props ?: Properties.collect(doc, lib)
    val parent_managed = parse_parent(doc)?.let {
        val parent_doc = download_pom(it)
        collect_managed_versions(parent_doc, it, Properties.collect(parent_doc, it))
    } ?: emptyMap()
    val managed = mutableMapOf<Pair<String, String>, String>()
    val dependencyManagement = doc.getElementsByTagName("dependencyManagement")
    if (dependencyManagement.length > 0) {
        val dependency = (dependencyManagement.item(0) as org.w3c.dom.Element).getElementsByTagName("dependency")
        for (i in 0 until dependency.length) {
            val node = dependency.item(i) as org.w3c.dom.Element
            val group_id = node.child_text("groupId", props, parent = lib) ?: continue
            val artifact_id = node.child_text("artifactId", props, parent = lib) ?: continue
            val version = node.child_text("version", props, parent = lib)
            val type = node.child_text("type", props, default = "", parent = lib) ?: ""
            val scope = node.child_text("scope", props, default = "", parent = lib) ?: ""
            if (type == "pom" && scope == "import" && version != null) {
                val bom_lib = Lib(group_id, artifact_id, version)
                val bom_doc = try {
                    download_pom(bom_lib)
                } catch (e: Exception) {
                    debug("failed to download BOM $bom_lib: ${e.message}, skipping")
                    continue
                }
                val bom_props = Properties.collect(bom_doc, bom_lib)
                managed.putAll(collect_managed_versions(bom_doc, bom_lib, bom_props))
                continue
            }
            if (version != null) {
                managed[group_id to artifact_id] = version
            }
        }
    }
    return parent_managed + managed
}

private fun print_tree(
    roots: Collection<Lib>,
    indent: String = "",
    seen: MutableSet<Lib> = mutableSetOf(),
    first_parent: MutableMap<Lib, Lib?> = mutableMapOf(),
    parent: Lib? = null,
    show_all: Boolean = false,
) { 
    for (lib in roots.sortedWith(compareBy({ it.group_id }, { it.artifact_id }, { it.version }))) {
        if (!seen.add(lib)) {
            if (show_all) first_parent[lib]?.let { println("$indent- $lib (see $it)")} ?: println("$indent- $lib (already shown)")
            continue
        }
        first_parent[lib] = parent
        val lib_colorized = lib.toString().split(":").let { it.dropLast(1).joinToString(":") + ":" + color(it.last(), Color.magenta)}
        println("$indent- $lib_colorized")
        runCatching { 
            resolve_transitive(listOf(lib))
                .sortedWith(compareBy({ it.group_id }, { it.artifact_id }, { it.version })) 
                .forEach { print_tree(listOf(it), indent + "  ", seen, first_parent, lib) }
        }
    } 
}

fun resolve_transitive(
    roots: Collection<Lib>,
    seen: MutableSet<Lib> = mutableSetOf(),
): Set<Lib> {
    val resolved = mutableSetOf<Lib>()
    val to_process = ArrayDeque<Lib>()
    to_process.addAll(roots)
    // Track discovered managed versions (BOMs / parents)
    val global_managed_versions = mutableMapOf<Pair<String, String>, String>()
    while (to_process.isNotEmpty()) {
        val current = to_process.removeFirst()
        if (current in resolved || current in seen) continue
        resolved.add(current)
        seen.add(current)
        val pom_file = current.pom_path().toFile()
        val doc = try {
            if (pom_file.exists()) {
                DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(pom_file)
            } else {
                try {
                    val downloaded = download_pom(current)
                    pom_file.parentFile.mkdirs()
                    val xml = node_to_string(downloaded)
                    pom_file.writeText(xml)
                    downloaded
                } catch (e: Exception) {
                    debug("missing POM for $current and no network: ${e.message}")
                    continue
                }
            }
        } catch (e: Exception) {
            debug("failed to download or parse POM $current: ${e.message}")
            continue
        }
        // Collect managed versions from BOMs / parent POMs
        val managed_versions = try {
            collect_managed_versions(doc, current)
        } catch (e: Exception) {
            debug("failed to collect managed versions for $current: ${e.message}")
            emptyMap()
        }
        global_managed_versions.putAll(managed_versions)
        val props = try {
            Properties.collect(doc, current)
        } catch (e: Exception) {
            debug("failed to collect properties for $current: ${e.message}")
            emptyMap()
        }
        // Process declared dependencies
        val dep_nodes = doc.getElementsByTagName("dependency")
        for (i in 0 until dep_nodes.length) {
            val node = dep_nodes.node_or_null(i) ?: continue
            val group_id = node.child_text("groupId", props = props, parent = current) ?: continue
            val artifact_id = node.child_text("artifactId", props = emptyMap(), parent = current) ?: continue
            var version = node.child_text("version", props = props, parent = current)
            // fallback to managed version
            if (version == null) {
                version = managed_versions[group_id to artifact_id]
                if (version == null) {
                    debug("no version for $group_id:$artifact_id, skipping")
                    continue
                }
            }
            val type = node.child_text("type", props = props, parent = current) ?: ""
            val scope = node.child_text("scope", props = props, parent = current) ?: ""
            val optional = node.child_text("optional", props = props, parent = current) == "true"
            if (optional || scope == "test" || scope == "provided") continue
            // handle BOM imports
            if (type == "pom" && scope == "import") {
                to_process.addAll(try { resolve_transitive(listOf(Lib(group_id, artifact_id, version)), seen) } catch (_: Exception) { emptySet() })
                continue
            }
            to_process.add(Lib(group_id, artifact_id, version))
        }
    }
    // Collapse multiple versions
    return resolved
        .groupBy { it.group_id to it.artifact_id }.map { (ga, libs) ->
            when (val managed = global_managed_versions[ga]) {
                null -> libs.maxByOrNull { ComparableVersion(Versioning.to_comparable_version(it.version)) }!! 
                else -> Lib(ga.first, ga.second, managed) 
            }
        }.toSet()
}

fun node_to_string(doc: org.w3c.dom.Document): String {
    val transformer = TransformerFactory.newInstance().newTransformer()
    transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no")
    transformer.setOutputProperty(OutputKeys.METHOD, "xml")
    transformer.setOutputProperty(OutputKeys.INDENT, "yes")
    val writer = StringWriter()
    transformer.transform(DOMSource(doc), StreamResult(writer))
    return writer.toString()
}

private val libs_lock_file = Paths.get("out/libs.lock")

private fun load_cached_libs(): Set<Lib> {
    if (!Files.exists(libs_lock_file)) return emptySet()
    return libs_lock_file.toFile().readLines()
        .filter { it.isNotBlank() }
        .map { Lib.of(it) }
        .toSet()
}

private fun save_libs(libs: Set<Lib>) {
    libs_lock_file.toFile().writeText(
        libs.joinToString("\n") { it.toString() }
    )
}

object Properties {
    fun parse(doc: org.w3c.dom.Document): Map<String, String> {
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

    fun builtins(doc: org.w3c.dom.Document, lib: Lib): Map<String, String> {
        val project_node = doc.getElementsByTagName("project").item(0) as org.w3c.dom.Element
        val group_id = project_node.getElementsByTagName("groupId").item(0)?.textContent ?: lib.group_id
        val artifact_id = project_node.getElementsByTagName("artifactId").item(0)?.textContent ?: lib.artifact_id
        val version = project_node.getElementsByTagName("version").item(0)?.textContent ?: lib.version
        return mapOf(
            "project.groupId" to group_id, 
            "project.artifactId" to artifact_id, 
            "project.version" to version, 
            "pom.groupId" to group_id,
            "pom.artifactId" to artifact_id,
            "pom.version" to version,
        )
    }

    fun collect(doc: org.w3c.dom.Document, lib: Lib): Map<String, String> {
        val parent_props = parse_parent(doc)?.let { Properties.collect(download_pom(it), it) } ?: emptyMap()
        return parent_props + Properties.parse(doc) + Properties.builtins(doc, lib)
    }
}

object Versioning {
    fun resolve_range(lib: Lib): String {
        if(!lib.version.contains('[') && !lib.version.contains('(')) return lib.version
        val url = "${lib.base_url}/maven-metadata.xml"
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(URI(url).toURL().openStream())
        doc.documentElement.normalize()
        val versions = mutableListOf<String>()
        val nodes = doc.getElementsByTagName("version")
        for (i in 0 until nodes.length) versions.add(nodes.item(i).textContent)
        if (versions.isEmpty()) error("no versions found in $url")
        val range_regex = Regex("""([\[\(])\s*([^,]*)?\s*,\s*([^,]*)?\s*([\]\)])""")
        val match = range_regex.matchEntire(lib.version) ?: error("invalid range: ${lib.version}")
        val lower_inc = match.groupValues[1] == "["
        val upper_inc = match.groupValues[4] == "]"
        val lower_bound = match.groupValues[2].takeIf { it.isNotEmpty() }
        val upper_bound = match.groupValues[3].takeIf { it.isNotEmpty() }
        val compatible = versions.filter { 
            (lower_bound == null || compare(it, lower_bound) > if (lower_inc) -1 else 0) && 
            (upper_bound == null || compare(it, upper_bound) < if (upper_inc) 1 else 0)
        }
        if (compatible.isEmpty()) error("no version satisfies range ${lib.version}")
        val releases = compatible.filterNot { it.contains("snapshot", ignoreCase = true )}
        return (releases.ifEmpty { compatible }).maxWithOrNull(::compare)!!
    }

    fun compare(version1: String, version2: String): Int {
        val parts1 = tokenize(version1.lowercase())
        val parts2 = tokenize(version2.lowercase())
        val len = maxOf(parts1.size, parts2.size)
        for (i in 0 until len) {
            val p1 = parts1.getOrElse(i) { Item("", ItemType.Str) }
            val p2 = parts2.getOrElse(i) { Item("", ItemType.Str) }
            val cmp = p1.compareTo(p2)
            if (cmp != 0) return cmp
        }
        return 0
    }

    fun to_comparable_version(str: String): List<Any> {
        return str.split("[.-]".toRegex()).map { it.toIntOrNull() ?: it }
    }

    private enum class ItemType { Int, Str }
    private data class Item(val value: String, val type: ItemType): Comparable<Item> {
        override fun compareTo(other: Item): Int = when {
            this.type == ItemType.Int && other.type == ItemType.Int -> this.value.toBigInteger().compareTo(other.value.toBigInteger())
            this.type == ItemType.Int -> 1
            other.type == ItemType.Int -> -1
            else -> qualifier_compare(this.value, other.value)
        }
    }
    private fun tokenize(version: String): List<Item> = version
        .split('.', '-', '_')
        .filter { it.isNotEmpty() }
        .map { it.toBigIntegerOrNull()?.let { num -> Item(num.toString(), ItemType.Int) } ?: Item(it, ItemType.Str) }

    private val QUALIFIERS = listOf("alpha", "a", "beta", "b", "milestone", "m", "rc", "cr", "snapshot", "sp", "")

    fun qualifier_compare(q1: String, q2: String): Int {
        if (q1 == q2) return 0
        val i1 = QUALIFIERS.indexOf(q1)
        val i2 = QUALIFIERS.indexOf(q2)
        return when {
            i1 >= 0 && i2 >= 0 -> i1.compareTo(i2)
            i1 >= 0 -> -1
            i2 >= 0 -> 1
            else -> q1.compareTo(q2)
        }
    }
}

private data class ComparableVersion(val parts: List<Any>): Comparable<ComparableVersion> {
    override fun compareTo(other: ComparableVersion): Int {
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

private fun org.w3c.dom.Element.node_or_null(tag: String): org.w3c.dom.Element? =
    getElementsByTagName(tag).item(0) as? org.w3c.dom.Element

private fun org.w3c.dom.NodeList.node_or_null(index: Int): org.w3c.dom.Element? {
    val node = item(index) ?: return null
    return if (node.nodeType == org.w3c.dom.Node.ELEMENT_NODE) node as org.w3c.dom.Element else null
}

private fun org.w3c.dom.Element.child_text(
    tag: String,
    props: Map<String, String>,
    default: String? = null,
    parent: Lib? = null,
): String? {
    val node = getElementsByTagName(tag)
    if (node.length == 0) return default
    val raw = node.item(0).textContent.trim()
    if (raw.isEmpty()) return default
    return raw
        .resolve_props(props) { prop -> "".also { debug("unresolved property $prop in ${parent ?: "element"} <$tag>, library may be skipped") } }
        .ifEmpty { default }
}

private fun org.w3c.dom.Element.to_lib(
    props: Map<String, String>,
    managed: Map<Pair<String, String>, String>, 
    parent_dep: Lib,
): Lib? {
    val group_id = child_text("groupId", props, parent = parent_dep)!!
    val artifact_id = child_text("artifactId", props, parent = parent_dep)!!
    var version = child_text("version", props, parent = parent_dep) ?: managed[group_id to artifact_id] ?: run {
        debug("no version for $group_id:$artifact_id, skipping")
        return null
    }
    if (version.contains('[') || version.contains('(')) {
        version = Versioning.resolve_range(Lib(group_id, artifact_id, version))
    }
    val type = child_text("type", props, default = "jar")!!
    val scope = child_text("scope", props, default = "compile")!!
    return Lib(group_id, artifact_id, version, type = type, scope = scope)
}

private fun String.resolve_props(props: Map<String, String>, on_missing: (String) -> String = { ""}): String = 
    replace(Regex("""\$\{(.+?)}""")) { match -> 
        props[match.groupValues[1]] ?: on_missing(match.groupValues[1]) 
    }

private fun debug(msg: String) { if (DEBUG) println("${color("[DEBUG]", Color.yellow)} $msg") }
private fun info(msg: String) { println("${color("[INFO]", Color.cyan)} $msg") }
private fun warn(msg: String) { println("${color("[WARN]", Color.magenta)} $msg") }
private fun err(msg: String) { println("${color("[ERR]", Color.red)} $msg") }

