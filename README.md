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
    val opts = parse_args(args)
    var nob = Nob(opts)
    nob.rebuild_urself(args)

    nob.resolve(
        Lib.of("io.ktor:ktor-server-netty:3.2.2"),
        Lib.of("org.slf4j:slf4j-simple:2.0.17"),
    )

    when {
        opts.test -> {
            nob.compile(opts.test_dir.toFile())
            nob.run_test(args)
        }
        opts.run -> nob.run_target()
        else -> nob.compile(opts.src_dir.toFile())
    }

    nob.exit()
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
    var cwd: String = System.getProperty("user.dir"),
    var src_dir: Path = Paths.get(cwd, "example"),
    var test_dir: Path = Paths.get(cwd, "test"),
    var target_dir: Path = Paths.get(cwd, "out").also { it.toFile().mkdirs() },
    var kotlin_dir: Path = Paths.get(System.getProperty("KOTLIN_HOME"), "libexec", "lib"),
    var nob_src: Path = Paths.get(cwd, "nob.kt"),
    var libs: List<Lib> = emptyList(),
    var jvm_version: Int = 21,
    var kotlin_version: String = "2.2.0",
    var backend_threads: Int = 0,
    var verbose: Boolean = false,
    var debug: Boolean = false,
    var error: Boolean = true,
    var extra: Boolean = false,
    var run: Boolean = false,
    var test: Boolean = false,
)
```

