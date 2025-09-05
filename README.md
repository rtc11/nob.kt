# nob.kt - NoBuild for Kotlin
This is inspired by the header only library version for C [https://github.com/tsoding/nob.h](https://github.com/tsoding/nob.h)

Build kotlin files only with kotlin.
This no-build library rebuild it self if changed and uses a Kotlin daemon for speeding up the compilation times.

`nob.kt` contains the source code of the library.

`nob` is a bootstrap and entrypoint shell script for nob and your kotlin source files.
Make sure the KOTLIN_LIB path is correct.

# Usage
Bootstrap nob.kt with `./nob`

Compile and run with the same `./nob`

Update nob.kt inside `compile_target` to include necessary libs.
This will resolve necessary compile/runtime libraries from gradle and maven.

Example:
```kotlin
fun main(args: Array<String>) {
    val opts = parse_args(args)
    val libs = listOf(
        Lib.of("io.ktor:ktor-server-netty:3.2.2"),
        Lib.of("org.slf4j:slf4j-simple:2.0.17"),
    )
    val nob = Nob(opts.copy(libs = solve_libs(opts, libs)))
    nob.compile_self()
    var exit_code =  nob.compile(opts.src_file)
    if (exit_code == 0) exit_code = nob.run_target()
    System.exit(exit_code)
}
```

Update `Opts` to change main source file or other options:
```kotlin
data class Opts(
    val cwd: String = System.getProperty("user.dir"),
    val src_dir: Path = Paths.get(cwd, "src").also { it.toFile().mkdirs() },
    val target_dir: Path = Paths.get(cwd, "out").also { it.toFile().mkdirs() },
    val kotlin_dir: Path = Paths.get(System.getProperty("KOTLIN_HOME"), "libexec/lib"),
    val nob_src: Path = Paths.get(cwd, "nob.kt"),

    val libs: List<Lib> = emptyList(),
    val jvm_version: Int = 21,
    val kotlin_version: String = "2.2.0",

    val backend_threads: Int = 0, // used in codegen where 1 = default, 0 = available cores
    val verbose: Boolean = false,
    val debug: Boolean = false,
    val error: Boolean = true,
    val extra: Boolean = false,
    var debugger: Boolean = false,
)
```

Run debugger:
>./nob -debugger

Attach debugger:
> jdb -attach 5005

See example project in `example/` 
