import java.io.*
import java.lang.ProcessBuilder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.function.*
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageRenderer
import org.jetbrains.kotlin.cli.common.messages.PrintingMessageCollector
import org.jetbrains.kotlin.daemon.client.*
import org.jetbrains.kotlin.daemon.common.*

// TODO: save current NobKt.class as temp and revert back on failure
fun main(args: Array<String>) {
    try {
        Nob.compile(Opts("nob.kt"))

        val src_file = args.firstOrNull() ?: "Main.kt".also { 
            println("[INFO] No file specified, assuming $it")
        }

        val opts = Opts(src_file)
        val exit_code = when (args.getOrNull(0)) {
            "debug" -> Nob.compile(opts)
            "release" -> Nob.release(opts)
            "clear" -> Nob.clear(opts)
            else -> Nob.compile(opts)
        }

        System.exit(exit_code)
    } catch (e: Exception) {
        e.printStackTrace()
        System.exit(1)
    }
}

data class Opts(
    val src_file: String,
    val java_bin: Path = Paths.get("/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home/bin"),
    val kotlin_bin: Path = Paths.get("/opt/homebrew/bin"),
    val kotlin_lib: Path = Paths.get("/opt/homebrew/Cellar/kotlin/2.2.0/libexec/lib"),
    val kotlin_stdlib: Path = kotlin_lib.resolve("kotlin-stdlib.jar").normalize(),
    val kotlin_compiler: Path = kotlin_lib.resolve("kotlin-compiler.jar").normalize(),
    val kotlin_daemon_client: Path = kotlin_lib.resolve("kotlin-daemon-client.jar").normalize(),
    val kotlin_daemon: Path = kotlin_lib.resolve("kotlin-daemon.jar").normalize(),
    val jvm_target: Int = 21,
    val backend_threads: Int = 2, // run codegen with N thread per processor
    val verbose: Boolean = false,
    val debug: Boolean = true,
    val error: Boolean = false,
    val extra: Boolean = true,
    val use_daemon: Boolean = true,
) {
    val name: String = src_file.removeSuffix(".kt").lowercase()
    val cwd: String = System.getProperty("user.dir")
    val classpath: Path = Paths.get(cwd)
    val src: Path = Paths.get(cwd, src_file).normalize()
    val target_dir: Path = Paths.get(cwd, "out")
    val cls: Path = target_dir.resolve(name.replaceFirstChar{ it.uppercase() } + "Kt.class")
    val main_path: Path = Paths.get(cwd, src_file)
}

object Nob {
    fun release(opts: Opts): Int {
        val opts = opts.copy(
            debug = false,
            error = true,
        )
        val cmd = BuildCommand(opts).to_jar()
        if (opts.verbose) println("[INFO] $cmd")
        println("[INFO] package ${opts.name}.jar")
        return run_command(cmd, opts)
    }

    fun compile(opts: Opts): Int {
        val src_modified = Files.getLastModifiedTime(opts.src).toInstant() 
        val cls_modified = if (Files.exists(opts.cls)) Files.getLastModifiedTime(opts.cls).toInstant() else Instant.EPOCH

        if (src_modified > cls_modified) {
            println("[INFO] compiling ${opts.name} to ${opts.target_dir}")
            return when (opts.use_daemon) {
                true -> compile_with_daemon(opts) 
                false -> run_command(BuildCommand(opts).to_kotlinc(), opts)
            }
        } 
        return 0
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
        val client_alive_file = File("out/.alive")
        if (!client_alive_file.exists()) client_alive_file.createNewFile()
        val args = arrayOf(
            File(opts.src_file).absolutePath.toString(),
            "-d", opts.target_dir.toString(),
            "-jvm-target", opts.jvm_target.toString(),
            "-cp", listOf(
                opts.kotlin_stdlib.toString(),
                opts.kotlin_compiler.toString(),
                opts.kotlin_daemon_client.toString(),
                opts.kotlin_daemon.toString(),
            ).joinToString(":"),
        )

        val daemon_reports = arrayListOf<DaemonReportMessage>()

        val daemon = KotlinCompilerClient.connectToCompileService(
            compiler_id,
            client_alive_file,
            daemon_jvm_opts,
            daemon_opts,
            DaemonReportingTargets(out = System.out, messages = daemon_reports),
            true,
        ) ?: error("unable to connect to compiler daemon: " + daemon_reports.joinToString("\n  ", prefix = "\n  ") { "${it.category.name} ${it.message}" })

        var session_id: Int? = null
        try {
            session_id = daemon.leaseCompileSession(client_alive_file.absolutePath).get()

            val start_time = System.nanoTime()
            val exit_code = KotlinCompilerClient.compile(
                daemon,
                session_id,
                CompileService.TargetPlatform.JVM,
                args,
                PrintingMessageCollector(System.err, MessageRenderer.WITHOUT_PATHS, true),
                reportSeverity = ReportSeverity.DEBUG,
            )
            val end_time = System.nanoTime()
            println("[INFO] Compilation complete in " + TimeUnit.NANOSECONDS.toMillis(end_time - start_time) + " ms")
            return exit_code
        } finally {
            session_id?.let { daemon.releaseCompileSession(it) }
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

data class BuildCommand(private val opts: Opts) {
    fun to_kotlinc(): String {
        return """
            "${opts.kotlin_bin.resolve("kotlinc").normalize()}" \
            -Xbackend-threads=${opts.backend_threads} \
            -jvm-target ${opts.jvm_target} \
            -d "${opts.target_dir}" \
            -cp "${opts.classpath}:${opts.kotlin_compiler}:${opts.kotlin_stdlib}:${opts.kotlin_daemon_client}:${opts.kotlin_daemon}" \
            ${if(opts.verbose) "-verbose" else ""} \
            ${if(opts.debug) "-Xdebug" else ""} \
            ${if(opts.extra) "-Wextra" else ""} \
            ${if(opts.error) "-Werror" else ""} \
            "${opts.main_path}"
        """.trimIndent()
    }

    fun to_jar(): String { 
        return """
            jar cfe ${opts.name}.jar ${opts.name}Kt \
            -C "${opts.target_dir}" .
        """.trimIndent()
    }
}
