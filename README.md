![img](logo.png)

# nob.kt - NoBuild for Kotlin
This is inspired by the header only library version for C [https://github.com/tsoding/nob.h](https://github.com/tsoding/nob.h)

Build kotlin files only with kotlin.

# Features
- Builds itself automatically
- Uses a kotlin daemon for faster compilation
- Resolves maven/gradle dependencies

# Setup
- clone `nob` and `nob.kt` into your project.
- ensure `KOTLIN_HOME` is on your PATH.

# Usage
Bootstrap nob.kt by running `./nob`

Compile your project by running `./nob`

Change the `main` to include your set of libraries, 
it should be possible to select a local only library as well (not tested)
```kotlin
fun main(args: Array<String>) {
    ...
    val libs = listOf(
        Lib.of("io.ktor:ktor-server-netty:3.2.2"),
        Lib.of("org.slf4j:slf4j-simple:2.0.17"),
    )
    ...
}
```

Start debug mode:
>./nob debug

Attach debugger:
> jdb -attach 5005

Run tests (nb, this is looking for a main() on your classpath, specify it explicit in `Opts` if necessary):
> ./nob test <test runner args>

Tune `Opts` with your preferences: 
```kotlin
data class Opts(
    val src_dir: Path = Paths.get(cwd, "example"),
    val test_dir: Path = Paths.get(cwd, "test"),
    val target_dir: Path = Paths.get(cwd, "out").also { it.toFile().mkdirs() },
    val kotlin_dir: Path = Paths.get(System.getProperty("KOTLIN_HOME"), "libexec/lib"),
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

