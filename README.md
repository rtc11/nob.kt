# nob.kt - NoBuild for Kotlin
This is inspired by the header only library version for C [https://github.com/tsoding/nob.h](https://github.com/tsoding/nob.h)

Build kotlin files only with kotlin.
This no-build library rebuild it self if changed and uses a Kotlin daemon for speeding up the compilation times.

`nob.kt` contains the source code of the library.

`nob` is a bootstrap and entrypoint shell script for nob and your kotlin source files.
Make sure the KOTLIN_LIB path is correct.

# Usage
Bootstrap nob for the first time, and compile Main.kt
> ./nob

Recompile changes in nob.kt or Main.kt
> ./nob

Download and cache dependencies:

Update nob.kt
```kotlin
val deps = DepResolution.resolve_with_cache(
    listOf(
        Dep.of("io.ktor:ktor-server-netty:3.2.2"),
        Dep.of("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2"),
    )
)
val opts = Opts(libs = listOf(deps.map { it.jar_path() }))
Nob.compile(opts)
```


