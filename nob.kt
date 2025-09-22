package nob

import java.io.*
import java.nio.file.*
import java.util.*
import java.util.jar.*
import kotlin.streams.asSequence
import org.jetbrains.kotlin.daemon.client.*
import org.jetbrains.kotlin.daemon.common.*
import org.w3c.dom.*

const val DEBUG = false

fun main(args: Array<String>) {
    val nob = Nob.new(args)

    val example = nob.module {
        name = "example"
        src = "example"
        libs = listOf(
            Lib.of("io.ktor:ktor-server-netty:3.2.2"),
            Lib.of("org.slf4j:slf4j-simple:2.0.17"),
        )
    }

    // val test = nob.module

    when {
        args.getOrNull(0) == "run" -> nob.run(example, "example.MainKt", arrayOf())
        // args.getOrNull(0) == "test" -> {
        //     nob.compile(test)
        //     nob.run(test, "test.TesterKt", arrayOf("-f", test.src_target().toAbsolutePath().normalize().toString()))
        // }
        args.getOrNull(0) == "release" -> nob.release(example)
        else -> nob.mods.filter { it.name != "nob" }.forEach { nob.compile(it) }
    }

    nob.exit()
}

class Nob(val opts: Opts) {
    private var exit_code = 0
    var mods = mutableListOf<Module>()
    var compiled = mutableSetOf<Module>()

    fun module(block: Module.() -> Unit): Module {
        val module = Module().apply(block)
        module.libs = solve_libs(opts, module)
        mods.add(module)
        return module
    }

    companion object {
        fun new(args: Array<String>): Nob {
            var opts = Opts()
            val arg = args.getOrNull(0)
            if (arg == "debug") opts.debug = true
            val nob = Nob(opts)
            val module = nob.module {
                name = "nob"
                src = "nob.kt"
                target = "out"
            }
            if (arg == "clean") nob.clean()
            nob.rebuild_urself(module, args)
            return nob
        }
    }

    // fun exit(): Unit = kotlin.system.exitProcess(exit_code)
    fun exit(code: Int = exit_code): Unit = System.exit(code)

    fun clean() {
        val target = path(mods.first().target)
        if (!Files.exists(target)) System.exit(0)
        Files.walk(target).forEach { f ->
            if (f.fileName.toString() !in listOf(".alive", "libs.cache", mods.first().target)) {
                Files.walk(f).sorted(Comparator.reverseOrder()).forEach { Files.delete(it) }
            }
        }
        exit(0)
    }

    fun compile(module: Module) {
        val start_time = System.nanoTime()
        if (module in compiled) return
        module.mods.forEach { compile(it) }

        val src = path(module.src).toFile()
        var target = module.src_target()
        val sources: Sequence<File> = when {
            src.isFile -> sequenceOf(src)
            src.isDirectory -> src.walkTopDown().filter { it.isFile && it.extension == "kt" }
            else -> emptySequence()
        }

        val any_compiled_target_file = get_any_compiled_file(target.toFile())
        val has_changes = sources.any { file -> 
            // println("src: $src")
            // println("target: $target")
            // println("file: $file")
            // println("any_compiled_target_file: $any_compiled_target_file")
            file.lastModified() > any_compiled_target_file?.lastModified() ?: 0
        }

        compiled.add(module)

        if (has_changes) {
            val classpath = module.compile_cp()
            if (src.isFile && src.name == "nob.kt") target = Paths.get(module.target).toAbsolutePath().normalize() 
            compile_with_daemon(src, target, classpath, module.name)
            info("Compiled ${module.name} ${stop(start_time)}")
        } else {
            info("${module.name} is up to date ${stop(start_time)}")
        }
    }

    fun run(module: Module, main_class_fq: String, run_args: Array<String>) {
        info("Running $main_class_fq")
        val cmd = buildList{
            add("java")
            add("-Dfile.encoding=UTF-8")
            add("-Dsun.stdout.encoding=UTF-8")
            add("-Dsun.stderr.encoding=UTF-8")
            if (opts.debug) add("-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=5005")
            add("-cp")
            add(module.runtime_cp())
            add(main_class_fq)
            run_args.forEach { add(it) }
        }
        exec(*cmd.toTypedArray())
    }

    fun release(module: Module) {
        val start_time = System.nanoTime()
        val main_class_fq = module.main_src()?.main_class_fq()
        val compiled_dir = main_class_fq?.substringBeforeLast('.')?.replace('.', File.separatorChar) ?: module.src.substringBeforeLast(File.separator)
        if (main_class_fq == null) {
        val src_target = Paths.get(module.src).toFile()
            val meta_inf = when {
                src_target.isFile -> "${module.name}/META-INF/${module.name}.kotlin_module"
                src_target.isDirectory -> "${module.src}/META-INF/${module.name}.kotlin_module"
                else -> error ("src is not a file nor dir, cannot find the ${module.name}.kotlin_module")
            }
            exec(
                "jar", "cf", "${module.target}/${module.name}.jar",
                "-C", module.target, meta_inf,
                "-C", module.target, compiled_dir
            )
        } else {
            exec(
                "jar", "cfe", "${module.target}/${module.name}.jar",
                main_class_fq,
                "-C", module.target, compiled_dir
            )
        }
        if (exit_code != 0) err("Failed to release ${module.name}.jar")
        info("Released ${module.name}.jar ${stop(start_time)}")
    }

    fun release_fat(module: Module) {
        val start_time = System.nanoTime()
        val main_class_fq = module.main_src()!!.main_class_fq()
        val target_dir = path(module.target)
        val fat_jar_file = target_dir.resolve("${module.name}-fat.jar").toFile()
        val added_entries = mutableSetOf<String>()
        JarOutputStream(FileOutputStream(fat_jar_file)).use { jar ->
            val manifest = Manifest().apply {
                mainAttributes.put(Attributes.Name.MANIFEST_VERSION, "1.0")
                mainAttributes.put(Attributes.Name.MAIN_CLASS, main_class_fq)
            }
            JarOutputStream(FileOutputStream(fat_jar_file), manifest).use { jar_stream ->
                fun addFile(file: File, baseDir: File) {
                    val entry_name = baseDir.toPath().relativize(file.toPath()).toString().replace("\\", "/")
                    if (added_entries.add(entry_name)) {
                        jar_stream.putNextEntry(JarEntry(entry_name))
                        file.inputStream().use { it.copyTo(jar_stream) }
                        jar_stream.closeEntry()
                    }
                }
                target_dir.toFile().walkTopDown()
                    .filter { it.isFile && it.extension == "class" }
                    .forEach { addFile(it, target_dir.toFile()) }
                module.libs.forEach { lib ->
                    if (lib.jar_file.exists()) {
                        java.util.zip.ZipFile(lib.jar_file).use { zip ->
                            zip.entries().asSequence().forEach { entry ->
                                val name = entry.name
                                if (name.startsWith("META-INF/")) return@forEach
                                if (added_entries.add(name)) {
                                    jar_stream.putNextEntry(JarEntry(name))
                                    zip.getInputStream(entry).use { it.copyTo(jar_stream) }
                                    jar_stream.closeEntry()
                                }
                            }
                        }
                    }
                }
            }
        }
        info("Released ${module.name}-fat.jar ${stop(start_time)}")
    }

    fun exec(quiet: Boolean, vararg cmd: String) {
        if (quiet) {
            if (opts.verbose) info(cmd.joinToString(" "))
            exit_code = java.lang.ProcessBuilder(*cmd)
                .redirectOutput(java.lang.ProcessBuilder.Redirect.DISCARD)
                .redirectError(java.lang.ProcessBuilder.Redirect.DISCARD)
                .start()
                .waitFor()
        } else {
            exec(*cmd)
        }
    }

    fun exec(vararg cmd: String) {
        if (opts.verbose) info(cmd.joinToString(" "))
        // info(cmd.joinToString(" "))
        exit_code = java.lang.ProcessBuilder(*cmd).inheritIO().start().waitFor()
    }

    private val kotlin_home = Paths.get(System.getenv("KOTLIN_HOME"), "libexec", "lib")
    private val kotlin_libs = listOf("kotlin-stdlib.jar", "kotlin-compiler.jar", "kotlin-daemon.jar", "kotlin-daemon-client.jar").joinToString(File.pathSeparator) { kotlin_home.resolve(it).toAbsolutePath().normalize().toString() }

    private fun compile_with_daemon(src: File, target: Path, classpath: String, name: String, retries: Int = 3) {
        val args = buildList {
            add("-d")
            add(target.toString())
            add("-jvm-target")
            add(opts.jvm_version.toString())
            add("-module-name")
            add(name)
            add("-Xbackend-threads=${opts.backend_threads}")
            if (!opts.debug) add("-Xno-optimize")
            add("-Xuse-fast-jar-file-system")
            add("-Xenable-incremental-compilation")
            add("-cp")
            add("$classpath:$kotlin_libs")
            if (opts.verbose) add("-verbose")
            if (opts.debug) add("-Xdebug")
            if (opts.extra) add("-Wextra")
            if (opts.error) add("-Werror")
            add(src.absolutePath)
        }
        if (opts.verbose) info("kotlinc ${args.joinToString(" ")}")
        // info("kotlinc ${args.joinToString(" ")}")
        val client_alive_file = target.resolve(".alive").toFile().apply { if (!exists()) createNewFile() }
        val daemon_reports = arrayListOf<DaemonReportMessage>()
        val compiler_id = Files.list(kotlin_home).filter { it.toString().endsWith(".jar") }.map { it.toFile() }.toList()
        val daemon = KotlinCompilerClient.connectToCompileService(
            compilerId = CompilerId.makeCompilerId(compiler_id),
            clientAliveFlagFile = client_alive_file,
            daemonJVMOptions = DaemonJVMOptions(),
            daemonOptions = DaemonOptions(verbose = opts.verbose),
            reportingTargets = DaemonReportingTargets(out = if (opts.verbose) System.out else null, messages = daemon_reports),
            autostart = true,
        ) ?: error("unable to connect to compiler daemon: " + daemon_reports.joinToString("\n  ", prefix = "\n  ") { "${it.category.name} ${it.message}" })
        try {
            val session_id = daemon.leaseCompileSession(client_alive_file.absolutePath).get()
            try {
                exit_code = KotlinCompilerClient.compile(
                    compilerService = daemon,
                    sessionId = session_id,
                    targetPlatform = CompileService.TargetPlatform.JVM,
                    args = args.toTypedArray(),
                    messageCollector = org.jetbrains.kotlin.cli.common.messages.PrintingMessageCollector(System.err, org.jetbrains.kotlin.cli.common.messages.MessageRenderer.PLAIN_FULL_PATHS, true),
                    compilerMode = CompilerMode.NON_INCREMENTAL_COMPILER,
                    reportSeverity = ReportSeverity.INFO,
                )
            } finally {
                daemon.releaseCompileSession(session_id) 
            }
        } catch (e: IllegalStateException) {
            daemon.shutdown()
            Thread.sleep(1000)
            if (retries > 0) compile_with_daemon(src, target, classpath, name, retries-1)
            else throw e
            return
        }
    }

    private fun rebuild_urself(module: Module, args: Array<String>) {
        val src = Paths.get(module.src)
        val classfile = Paths.get("out/nob/nobKt.class")
        if (!classfile.toFile().exists() || Files.getLastModifiedTime(src).toMillis() > Files.getLastModifiedTime(classfile).toMillis()) {
            compile(module) // if we return if it was cached or compiled, we dont need the above code
            if (exit_code != 0) exit() 
            // we dont need to wait for this process, because this one should replace the current process
            java.lang.ProcessBuilder("java", "-cp", "${System.getProperty("java.class.path")}", "nob.NobKt", *args).inheritIO().start() 
            exit(0) // terminate this process because the new one is taking over
        }
    }

    private fun get_any_compiled_file(dir: File): File? {
        return when {
            dir.isFile -> dir
            dir.isDirectory -> dir.walkTopDown().firstOrNull { it.isFile && it.extension == "class" }
            else -> null
        }
    }
}

data class Opts(
    var jvm_version: Int = 21,
    var kotlin_version: String = "2.2.0",
    var backend_threads: Int = 0, // run codegen with N thread per processor (Default 1)
    var verbose: Boolean = false,
    var error: Boolean = true,
    var extra: Boolean = false,
    var debug: Boolean = false,
)

fun Nob.main_srcs(): Sequence<Path> = mods.map { path(it.src) }.asSequence()
    .flatMap { Files.walk(it).asSequence() }
    .filter { Files.isRegularFile(it) && it.toFile().readText().contains("fun main(") }

fun Path.main_class_fq(): String { 
    val pkg = this.toFile().useLines { lines -> lines.firstOrNull { it.trim().startsWith("package ") }?.removePrefix("package ")?.trim() }
    val main = this.fileName.toString().removeSuffix(".kt").replaceFirstChar{ it.uppercase() } + "Kt"
    return pkg?.let { "$pkg.$main" } ?: main
}

fun List<Path>.into_cp(): String = joinToString(File.pathSeparator) { it.toAbsolutePath().normalize().toString() }
fun path(str: String): Path = Paths.get(System.getProperty("user.dir"), str).also { it.toFile().mkdirs() }

data class Module(
    var name: String = "app",
    var src: String = "src",
    var res: String = "res",
    var target: String = "out",
    var libs: List<Lib> = emptyList(), 
    var mods: List<Module> = emptyList(),
) {
    fun src_target(): Path { 
        val src_target = Paths.get(src).toFile()
        return when {
            src_target.isFile -> path("$target/$name")
            src_target.isDirectory -> path("$target/$src")
            else -> error("file is not file or directory $src_target")
        }
    }

    fun compile_cp(): String { 
        val libs = libs.filter { it.scope in listOf("compile") }.map { it.jar_path }.into_cp() 
        val mods = mods.map { it.src_target().toAbsolutePath().normalize().toString() }.joinToString(File.pathSeparator)
        val target = path(target).toAbsolutePath().normalize().toString() 
        return listOf(libs, mods, target).filter { it.isNotBlank() }.joinToString(File.pathSeparator)
    }

    fun runtime_cp(): String {
        val libs = libs.filter { it.scope in listOf("runtime", "compile") }.map { it.jar_path }.into_cp() 
        val mods = mods.map { it.src_target().toAbsolutePath().normalize().toString() }.joinToString(File.pathSeparator)
        val target = path(target).toAbsolutePath().normalize().toString() 
        val src_target = src_target().toAbsolutePath().normalize().toString()
        val res = path(res).toAbsolutePath().normalize().toString()
        return listOf(libs, mods, res, target, src_target).filter { it.isNotBlank() }.joinToString(File.pathSeparator)
    }

    fun main_srcs(): Sequence<Path> = Files.walk(path(src)).asSequence().filter { Files.isRegularFile(it) && it.toFile().readText().contains("fun main(") }
    fun main_src(): Path? = Files.walk(path(src)).asSequence().singleOrNull { Files.isRegularFile(it) && it.toFile().readText().contains("fun main(") }
}

private val jar_cache_dir = Paths.get(System.getProperty("user.home"), ".nob_cache").also { it.toFile().mkdirs() }

data class Lib(
    val group_id: String,
    val artifact_id: String,
    val version: String,
    val scope: String = "compile",
    val type: String = "jar",
    val repo: String = "https://repo1.maven.org/maven2",
    val jar_path: Path = jar_cache_dir.resolve(group_id).resolve("${artifact_id}-$version.jar"),
    val base_url: String = "$repo/${group_id.replace('.', '/')}/$artifact_id", 
    val pom_url: String = "$base_url/$version/$artifact_id-$version.pom",
    val jar_url: String = "$base_url/$version/$artifact_id-$version.jar",
    val module_url: String = "$base_url/$version/$artifact_id-$version.module",
) {
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
        fun of(str: String) = str.split(':').let { Lib(it[0], it[1], it[2], it.getOrElse(3) { "compile" }) } 
    }
}

private fun download_file(url: java.net.URI, file: File) {
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
    download_file(java.net.URI(lib.jar_url),lib.jar_file)
}

data class ResolvedLib(val lib: Lib, val resolved_from: String)
data class LibKey(val group: String, val artifact: String)

private fun solve_libs(opts: Opts, module: Module): List<Lib> {
    val start_time = System.nanoTime()
    val cache_file = path(module.target).resolve("libs.cache").toFile()
    val resolved = mutableListOf<ResolvedLib>()
    read_cache(cache_file, resolved)
    val keys = resolved.map { LibKey(it.lib.group_id, it.lib.artifact_id) }.toMutableSet()
    val missing_libs = module.libs.filter{ LibKey(it.group_id, it.artifact_id) !in keys }
    if (missing_libs.isNotEmpty()) {
        val gradle_resolver = GradleResolver()
        val maven_resolver = MavenResolver()
        val queue = ArrayDeque<Lib>()
        for (lib in module.libs) {
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
        return resolved.resolve_kotlin_libs(opts).also {
            save_cache(cache_file, it)
            info("Resolved ${resolved.size} libs ${stop(start_time)}")
        }.map { it.lib }
    }
    return resolved.map { it.lib }
}

private fun List<ResolvedLib>.resolve_kotlin_libs(opts: Opts): List<ResolvedLib> {
    val kotlin_home = Paths.get(System.getenv("KOTLIN_HOME"), "libexec", "lib")
    return this
        // .filterNot { it.group_id == "org.jetbrains.kotlin" && it.artifact_id == "kotlin-stdlib-common" }
        .map { resolved -> 
            val lib = resolved.lib
            when (lib.group_id) {
                "org.jetbrains.kotlin" -> {
                    val local = kotlin_home.resolve("${lib.artifact_id}.jar")
                    when (local.toFile().exists()) {
                        true -> {
                            info("found local kotlin substitute: $local")
                            ResolvedLib(lib.copy(version = opts.kotlin_version, jar_path = local), resolved.resolved_from)
                        }
                        false -> {
                            info("found no kotlin subsistute, fallback to $resolved")
                            resolved
                        }
                    }
                }
                else -> resolved
            }
    }
}

private fun read_cache(file: File, resolved: MutableList<ResolvedLib>) {
    if (!file.exists()) return
    file.readLines()
        .mapNotNull { line -> 
            val parts = line.split(":")
            if (parts.size != 6) null
            // restore jar_path for local libs
            when (val jar_path = parts.getOrNull(6)) {
                null -> ResolvedLib(resolved_from = parts[5], lib = Lib(parts[0], parts[1], parts[2], parts[3], parts[4]))
                else -> ResolvedLib(resolved_from = parts[5], lib = Lib(parts[0], parts[1], parts[2], parts[3], parts[4], jar_path = Paths.get(jar_path)))
            }
        }.forEach(resolved::add)
}

private fun save_cache(file: File, resolved: List<ResolvedLib>) {
    file.writeText(resolved.joinToString("\n") {
        // save jar_path for local libs
        if (it.lib.jar_path.parent.parent.fileName.toString() != ".nob_cache") {
            "${it.lib}:${it.lib.scope}:${it.lib.type}:${it.resolved_from}:${it.lib.jar_path.toAbsolutePath().normalize().toString()}"
        } else {
            "${it.lib}:${it.lib.scope}:${it.lib.type}:${it.resolved_from}"
        }
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
                            url = java.net.URI(lib.module_url).resolve(file_url), 
                            file = File("${jar_cache_dir}/${component_group}/${file_name}").also { it.parentFile.mkdirs() },
                        )
                    }
                }
                for(d in deps) {
                    val dep = d as Map<*, *>
                    val group = dep["group"] as String
                    val module = dep["module"] as String
                    val version_obj = dep["version"] as? Map<*, *> ?: emptyMap<String, Any?>()
                    val version = listOf("requires", "strictly", "prefers").firstNotNullOfOrNull { key -> version_obj[key] as? String } ?: "unspecified"
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
                    libs.add(ResolvedLib(Lib(group, module, version, scope), "gradle"))
                }
                val avail = variant["available-at"] as? Map<*, *>
                if (avail != null) {
                    val group = avail["group"] as String
                    val module = avail["module"] as String
                    val version = avail["version"] as String
                    libs.add(ResolvedLib(Lib(group, module, version, scope), "gradle"))
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
                java.net.URI(lib.module_url).toURL().openStream().bufferedReader().use { it.readText() }
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
            val version = resolve_best_version(LibKey(group, artifact), declared_version, pom)
            if (version != null) {
                resolved.add(ResolvedLib(Lib(group, artifact, version, scope, type), "maven"))
            } else {
                warn("Could not resolve version for $group:$artifact:$version")
            }
        }
        return resolved
    }

    private fun resolve_best_version(key: LibKey, declared_version: String?, pom: Document): String? {
        val candidates = mutableSetOf<String>()
        if (declared_version != null) {
            val resolved = resolve_property_lazy(declared_version, pom)
            if (resolved != null) candidates.add(resolved)
        }
        find_managed_version_candidates(key, pom, candidates)
        return candidates.maxWithOrNull(::compare_version)
    }
    private fun compare_version(v1: String, v2: String): Int {
        val parts1 = v1.split('.').map { it.toIntOrNull() ?: 0 }
        val parts2 = v2.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(parts1.size, parts2.size)) {
            val p1 = parts1.getOrElse(i) { 0 }
            val p2 = parts2.getOrElse(i) { 0 }
            if (p1 != p2) return p1.compareTo(p2)
        }
        return 0
    }
    private fun find_managed_version_candidates(key: LibKey, pom: Document, candidates: MutableSet<String>) {
        val managed_libs = get_managed_libs(pom)
        val managed_lib = managed_libs[key]
        if (managed_lib != null) {
            val resolved_version = resolve_property_lazy(managed_lib.version, pom)
            if (resolved_version != null) candidates.add(resolved_version)
        }
        val parent_lib = get_parent_lib(pom)
        if (parent_lib != null) {
            get_doc(parent_lib)?.let { doc -> find_managed_version_candidates(key, doc, candidates)}
        }
        val boms = get_boms(pom)
        for (bom_lib in boms) {
            get_doc(bom_lib)?.let { doc -> find_managed_version_candidates(key, doc, candidates)}
        }
    }

    private fun get_doc(lib: Lib): Document? {
        val pom = pom_cache[lib] ?: download_pom(lib) 
        if (pom != null) pom_cache[lib] = pom
        return pom
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
                LibKey(group, artifact) to Lib(group, artifact, version, scope, type)
            }.toMap()
    }

    private fun find_prop(prop_name: String, pom: Document): String? {
        val props = get_props(pom)
        val value = props[prop_name]
        if (value != null) return value.takeIf { !it.matches(Regex("""\$\{(.+?)}""")) } ?: find_prop(value.substringAfter("\${").substringBefore("}"), pom)
        val parent_lib = get_parent_lib(pom)
        if (parent_lib != null) {
            get_doc(parent_lib)?.let { doc ->
                return find_prop(prop_name, doc)
            }
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
                    val resolved_version = resolve_property_lazy(version, pom) ?: continue
                    boms.add(Lib(group, artifact, resolved_version))
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
            get_doc(parent_lib)?.let { doc ->
                val scope = find_managed_scope(key, doc)
                if (scope != "compile") return scope
            }
        } 
        val boms = get_boms(pom)
        for (bom_lib in boms) {
            get_doc(bom_lib)?.let { doc ->
                val scope = find_managed_scope(key, doc)
                if (scope != "compile") return scope
            }
        }
        return "compile"
    }

    private fun resolve_property_lazy(prop: String?, pom: Document): String? {
        if (prop == null) return null
      when (prop) {
            "\${project.version}" -> return pom.getElementsByTagName("project").item(0)?.let { it as Element }?.get_direct_child("version")?.textContent?.trim() ?: pom.getElementsByTagName("parent").item(0)?.let { it as? Element }?.get("version")
            "\${project.groupId}" -> return pom.getElementsByTagName("project").item(0)?.let { it as Element }?.get_direct_child("groupId")?.textContent?.trim() ?: pom.getElementsByTagName("parent").item(0)?.let { it as? Element }?.get("groupId")
            "\${project.artifactId}" -> return pom.getElementsByTagName("project").item(0)?.let { it as Element }?.get_direct_child("artifactId")?.textContent?.trim()
            "\${project.parent.version}" -> return pom.getElementsByTagName("parent").item(0)?.let { it as? Element }?.get("version")
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
        "&oslash;" to "ø", "&Oslash;" to "Ø", "&auml;" to "ä",
        "&ouml;"   to "ö", "&uuml;"   to "ü", "&Auml;" to "Ä",
        "&Ouml;"   to "Ö", "&Uuml;"   to "Ü", "&apos;" to "'"
    )

    private fun String.sanitize(): String = problematic_html_entities.entries
        .fold(this) { acc, (k, v) -> acc.replace(k, v) }  
        .replace(Regex("<[^>]*@[^>]*>"), "") // remove <abc@abc>
        .replace(Regex("&(?!amp;)(?!lt;)(?!gt;)(?!quot;)(?!apos;).+;"), "") // remove malformed tags

    private fun download_pom(lib: Lib): Document? {
        fun document(text: String) = javax.xml.parsers.DocumentBuilderFactory
            .newInstance()
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
            val text = java.net.URI(lib.pom_url).toURL().openStream().bufferedReader().use { it.readText() }.sanitize()
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

operator fun Document.get(tag_name: String): Element = getElementsByTagName(tag_name).item(0) as Element

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

fun stop(start_time: Long): String = "[${java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start_time)} ms]"

private fun color(text: Any, color: Color) = "${color.code}$text${Color.reset.code}" 
private enum class Color(val code: String) {
    red("\u001B[31m"),  green("\u001B[32m"),   yellow("\u001B[33m"),
    blue("\u001B[34m"), magenta("\u001B[35m"), cyan("\u001B[36m"),
    reset("\u001B[0m"),
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


