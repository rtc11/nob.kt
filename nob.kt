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
}

object Nob {
    fun release(opts: Opts): Int {
        val opts = opts.copy(debug = false, error = true)
        val cmd = jar_command(opts)
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

fun jar_command(opts: Opts): String {
    return """jar cfe ${opts.name}.jar ${opts.name}Kt -C "${opts.target_dir}" ."""
}
