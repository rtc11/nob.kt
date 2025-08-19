import java.io.*
import java.nio.file.*
import java.nio.file.attribute.*
import java.util.*
import java.util.function.*
import java.lang.ProcessBuilder

fun main(args: Array<String>) {
    try {
        val src_file = args.firstOrNull() ?: "Main.kt".also { println("[INFO] No file specified, assuming $it") }

        Nob.build(Opts("nob.kt"))
        val opts = Opts(src_file)
        when (args.firstOrNull()) {
            "debug" -> Nob.build(opts)
            "release" -> Nob.release(opts)
            "clear" -> Nob.clear(opts)
            else -> Nob.build(opts)
        }
    } catch (e: Exception) {
        println("[ERR] build failed with $e")
    }
}

data class Opts(
    val src_file: String,
    val java_bin: Path = Paths.get("/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home/bin"),
    val kotlin_bin: Path = Paths.get("/opt/homebrew/bin"),
    val jvm_target: Int = 21,
    val backend_threads: Int = 2, // run codegen with N thread per processor
    val verbose: Boolean = false,
    val debug: Boolean = true,
    val error: Boolean = false,
    val extra: Boolean = true,
) {
    val name: String = src_file.removeSuffix(".kt").lowercase()
    val fs: FileSystem = FileSystems.getDefault()
    val cwd: String = System.getProperty("user.dir")
    val classpath: Path = fs.getPath(cwd)
    val src: Path = fs.getPath(cwd, src_file).normalize()
    val src_time: FileTime get() = Files.getLastModifiedTime(src)
    val target_dir: Path = fs.getPath(cwd, "out")
    val cls: Path = target_dir.resolve(name.replaceFirstChar{ it.uppercase() } + "Kt.class")
    val cls_time: FileTime get() = Files.getLastModifiedTime(cls)
    val main_path: Path = fs.getPath(cwd, src_file) // fs.getPath(cwd, "src", nob.kt"),
}

object Nob {

    fun release(opts: Opts) {
        val opts = opts.copy(
            debug = false,
            error = true,
        )
        val cmd = BuildCommand(opts).to_jar()
        if (opts.verbose) println("[INFO] $cmd")
        println("[INFO] package ${opts.name}.jar")
        run_command(cmd, opts).let(System::exit)
    }

    fun build(opts: Opts) {
        val cmd = BuildCommand(opts).to_kotlinc()
        if (opts.src_time.toInstant() > opts.cls_time.toInstant()) {
            println("[INFO] compiling ${opts.name} to ${opts.target_dir}")
            if (opts.verbose) println("[INFO] $cmd")
            val status = run_command(cmd, opts)
            if (status != 0) System.exit(status)
        }
    }

    fun clear(opts: Opts) {
        println("[INFO] clear ${opts.target_dir}")
        run_command("rm -rf ${opts.target_dir}", opts).let(System::exit)
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
            -daemon \
            -Xbackend-threads=${opts.backend_threads} \
            -jvm-target ${opts.jvm_target} \
            -d "${opts.target_dir}" \
            -cp "${opts.classpath}" \
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
