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
    val nob = Nob.new(args)

    nob.module {
        name = "example"
        src = "example"
        libs = listOf(
            Lib.of("io.ktor:ktor-server-netty:3.2.2"),
            Lib.of("org.slf4j:slf4j-simple:2.0.17"),
        )
    }

    when {
        nob.opts.debug -> nob.run(args)
        args.getOrNull(0) == "run" -> nob.run(args)
        args.getOrNull(0) == "test" -> nob.test(args)
        args.getOrNull(0) == "release" -> {
            var module = nob.modules.filter { it.name in args }.firstOrNull() 
                ?: nob.modules.firstOrNull { it.name != "nob" }
                ?: error("no modules provided")

            val main_class = nob.main_srcs().filter { !it.toAbsolutePath().toString().endsWith("nob.kt")}.first().main_class()
            nob.release(module, main_class)
        }
        else -> nob.compile(args)
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

## Example
From clean state, this illustrate compile times, release times and dependency resolution
![img](example.png)

Tune `Opts` with your preferences: 
```kotlin
data class Opts(
    var jvm_version: Int = 21,
    var kotlin_version: String = "2.2.0",
    var backend_threads: Int = 0, // run codegen with N thread per processor (Default 1)
    var verbose: Boolean = false,
    var error: Boolean = true,
    var extra: Boolean = false,
    var debug: Boolean = false,
)
```

