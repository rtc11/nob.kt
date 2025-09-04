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
private fun compile_target(opts: Opts): Int {
    val libs = solve_libs(opts, listOf(
        Lib.of("io.ktor:ktor-server-netty:3.2.2"),
    )).map { it.lib }.resolve_kotlin_libs(opts)

    val opts = opts.copy(libs = libs.toSet())

    var exit_code =  Nob.compile(opts, opts.src_file)
    if (exit_code == 0) exit_code = run_target(opts)
    return exit_code
}
```

Update `Opts` to change main source file or other options:
```kotlin
data class Opts(
    val src_file: String = "your-main-file.kt",
    val nob_src_file: String = "nob.kt",
    val kotlin_lib: Path, 
    val libs: Set<Lib> = emptySet(),
    val out_dir: String = "out",
    val jvm_target: Int = 21,
    val kotlin_target: String = "2.2.0",
    val backend_threads: Int = 3,
    val verbose: Boolean = false,
    val debug: Boolean = true,
    val error: Boolean = true,
    val extra: Boolean = true,
)
```
