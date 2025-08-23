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
    val type: String = "jar",
    val scope: String = "compile",
) {
    val base_url = "$repo/${group_id.replace('.', '/')}/$artifact_id" 
    val pom_url = "$base_url/$version/$artifact_id-$version.pom"
    val jar_url = "$base_url/$version/$artifact_id-$version.jar"
    override fun toString() = "$group_id:$artifact_id:$version"
    companion object {
        fun of(str: String) = str.split(':').let { Dep(it[0], it[1], it[2], type = it.getOrElse(3) { "compile" }) }
    }
}

object DepResolution {
    private val pom_cache = mutableMapOf<String, org.w3c.dom.Document>()

    fun resolve_transitive(dep: Dep, seen: MutableSet<Dep> = mutableSetOf()): Set<Dep> {
        if (!seen.add(dep)) return seen
        val deps = try {
            download(dep)
        } catch (e: FileNotFoundException) {
            println("[WARN] $dep not found. Skipping...")
            emptyList()
        }
        for (dep in deps) {
            if (dep.scope in listOf("test", "provided")) continue
            resolve_transitive(dep, seen)

        }
        return seen
    }

    fun download(dep: Dep): List<Dep> {
        val doc = try {
            download_pom(dep)
        } catch (e: Exception) {
            println("[WARN] $dep not found. Skipping...")
            return emptyList()
        }
        val props = Properties.collect(doc, dep)
        val managed = collect_managed_versions(doc, dep).toMutableMap()
        val deps = mutableListOf<Dep>()
        val dep_nodes = doc.getElementsByTagName("dependency")
        for (i in 0 until dep_nodes.length) {
            val node = dep_nodes.item(i) as org.w3c.dom.Element 
            val group_id = node.getElementsByTagName("groupId").item(0).textContent
            val artifact_id = node.getElementsByTagName("artifactId").item(0).textContent
            val version_node = node.getElementsByTagName("version")
            // resolve version: explicit > managed > skip optional
            var version = if (version_node.length > 0) {
                version_node.item(0).textContent.trim()
            } else {
                managed[group_id to artifact_id]
                    ?.also { println("[INFO] using managed version for $group_id:$artifact_id") }
                    ?: run { println("[WARN] no version for $group_id:$artifact_id (not managed), skipping."); continue }
            }
            // resolve properties in version
            version = version.replace(Regex("""\$\{(.+?)}""")) { match ->
                val prop_name = match.groupValues[1]
                props[prop_name] ?: run {
                    println("[WARN] unknown property $prop_name in $dep, skipping dependency $group_id:$artifact_id")
                    "" // return dummy value to satisfy the lambda
                }
            }
            if (version.isEmpty()) continue // skip dependency if versino could not be resolved
            // resolve version ranges
            if (version.contains('[') || version.contains('(')) {
                version = Versioning.resolve_range(Dep(group_id, artifact_id, version))
            }
            val type_node = node.getElementsByTagName("type")
            val type = if (type_node.length > 0) {
                type_node.item(0).textContent.trim().replace(Regex("""\$\{(.+?)}""")) { match ->
                    val prop_name = match.groupValues[1]
                    props[prop_name] ?: error("unknown property $prop_name for type in $dep")
                }
            } else "jar"
            val scope_node = node.getElementsByTagName("scope")
            val scope = if (scope_node.length > 0) {
                scope_node.item(0).textContent.trim().replace(Regex("""\$\{(.+?)}""")) { match ->
                    val prop_name = match.groupValues[1]
                    props[prop_name] ?: error("unknown property $prop_name for scope in $dep")
                }
            } else "compile"
            deps.add(Dep(group_id, artifact_id, version, type = type, scope = scope))
        }
        return deps
    }

    private val common_problematic_html_entities = mapOf(
        "&oslash;" to "ø", "&Oslash;" to "Ø", "&auml;" to "ä", "&ouml;" to "ö", "&uuml;" to "ü", "&Auml;" to "Ä",
        "&Ouml;" to "Ö", "&Uuml;" to "Ü", "&amp;" to "&", "&lt;" to "<", "&gt;" to ">", "&quot;" to "\"", "&apos;" to "'"
    )
    fun download_pom(dep: Dep): org.w3c.dom.Document {
        pom_cache[dep.toString()]?.let { return it }
        println("[INFO] fetching ${dep.pom_url}")
        val stream = URI(dep.pom_url).toURL().openStream()
        val text = stream.bufferedReader().use { it.readText() }
        val fixed_text = common_problematic_html_entities.entries.fold(text) { acc, (k, v) -> acc.replace(k, v) }
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val doc = factory.newDocumentBuilder().parse(fixed_text.byteInputStream())
        doc.documentElement.normalize()
        pom_cache[dep.toString()] = doc
        return doc
    } 

    fun parse_parent(doc: org.w3c.dom.Document): Dep? {
        val parent_nodes = doc.getElementsByTagName("parent")
        if (parent_nodes.length == 0) return null
        val parent = parent_nodes.item(0) as org.w3c.dom.Element
        val group_id = parent.getElementsByTagName("groupId").item(0).textContent
        val artifact_id = parent.getElementsByTagName("artifactId").item(0).textContent
        val version = parent.getElementsByTagName("version").item(0).textContent
        return Dep(group_id, artifact_id, version)
    }

    fun collect_managed_versions(
        doc: org.w3c.dom.Document,
        dep: Dep,
        props: Map<String, String>? = null,
    ): Map<Pair<String, String>, String> {
        // parent-managed versions
        val props_map = props ?: Properties.collect(doc, dep)
        val parent_managed = parse_parent(doc)?.let {
            val parent_doc = download_pom(it)
            val parent_props = Properties.collect(parent_doc, it)
            collect_managed_versions(parent_doc, it, parent_props)
        } ?: emptyMap()

        val managed = mutableMapOf<Pair<String, String>, String>()

        // dependency-managed versions
        val dm_nodes = doc.getElementsByTagName("dependencyManagement")
        if (dm_nodes.length > 0) {
            val deps = (dm_nodes.item(0) as org.w3c.dom.Element).getElementsByTagName("dependency")
            for (i in 0 until deps.length) {
                val node = deps.item(i) as org.w3c.dom.Element
                val group_id = node.getElementsByTagName("groupId").item(0).textContent
                val artifact_id = node.getElementsByTagName("artifactId").item(0).textContent
                var version = node.getElementsByTagName("version").item(0)?.textContent?.trim()
                // resolve properties in version
                if (version != null) {
                    val unresolved = Regex("""\$\{(.+?)}""").findAll(version).map { it.groupValues[1] }
                        .filter { it !in props_map }
                        .toList()
                    if (unresolved.isNotEmpty()) {
                        println("[WARN] unknown properties ${unresolved.joinToString()} in $dep, skipping dependency")
                        continue // skip this dependency entirely
                    }
                    version = version.replace(Regex("""\$\{(.+?)}""")) { match ->
                        val prop_name = match.groupValues[1]
                        props_map[prop_name]!!
                    }
                }
                val type = node.getElementsByTagName("type").item(0)?.textContent ?: ""
                val scope = node.getElementsByTagName("scope").item(0)?.textContent ?: ""
                // handle BOM imports
                if (type == "pom" && scope == "import" && version != null) {
                    val bom_dep = Dep(group_id, artifact_id, version)
                    val bom_doc = try {
                        download_pom(bom_dep)
                    } catch (e: Exception) {
                        println("[WARN] failed to download BOM $bom_dep: ${e.message}, skipping")
                        continue;
                    }
                    val bom_props = Properties.collect(bom_doc, bom_dep)
                    managed.putAll(collect_managed_versions(bom_doc, bom_dep, bom_props))
                    continue // skip adding BOM as a regular dependency
                }
                if (version != null) {
                    managed[group_id to artifact_id] = version
                }
            }
        }
        return parent_managed + managed
    }

    private object Properties {
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

        fun builtins(doc: org.w3c.dom.Document, dep: Dep): Map<String, String> {
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

        fun collect(doc: org.w3c.dom.Document, dep: Dep): Map<String, String> {
            val parent_props = parse_parent(doc)?.let { Properties.collect(download_pom(it), it) } ?: emptyMap()
            return parent_props + Properties.parse(doc) + Properties.builtins(doc, dep)
        }
    }

    private object Versioning {

        fun resolve(raw: String, props: Map<String, String>): String =
            raw.replace(Regex("""\$\{(.+?)}""")) { match ->
                val prop_name = match.groupValues[1]
                props[prop_name] ?: error("unknown property $prop_name among $props")
            }

        fun resolve_range(dep: Dep): String {
            if(!dep.version.contains('[') && !dep.version.contains('(')) return dep.version
            val url = "${dep.base_url}/maven-metadata.xml"
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(URI(url).toURL().openStream())
            doc.documentElement.normalize()
            val versions = mutableListOf<String>()
            val nodes = doc.getElementsByTagName("version")
            for (i in 0 until nodes.length) versions.add(nodes.item(i).textContent)
            if (versions.isEmpty()) error("no versions found in $url")
            val range_regex = Regex("""([\[\(])\s*([^,]*)?\s*,\s*([^,]*)?\s*([\]\)])""")
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

        private enum class ItemType { Int, Str }
        private data class Item(val value: String, val type: ItemType): Comparable<Item> {
            override fun compareTo(other: Item): Int = when {
                this.type == ItemType.Int && other.type == ItemType.Int -> this.value.toBigInteger().compareTo(other.value.toBigInteger())
                this.type == ItemType.Int -> 1
                other.type == ItemType.Int -> -1
                else -> qualifier_compare(this.value, other.value)
            }
        }
        fun tokenize(version: String): List<Item> = version
            .split('.', '-', '_')
            .filter { it.isNotEmpty() }
            .map { it.toBigIntegerOrNull()?.let { num -> Item(num.toString(), ItemType.Int) } ?: Item(it, ItemType.Str) }

        private val QUALIFIERS = listOf(
            "alpha", "a",
            "beta", "b",
            "milestone", "m",
            "rc", "cr",
            "snapshot",
            "sp",
            "", // empty qualifier = GA release = highest
        )

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
}
