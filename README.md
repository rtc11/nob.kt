# nob.kt - NoBuild for Kotlin
This is inspired by the header only library version for C [https://github.com/tsoding/nob.h](https://github.com/tsoding/nob.h)

Build kotlin files only with kotlin.
This no-build library rebuild it self if changed and uses a Kotlin daemon for speeding up the compilation times.

`nob.kt` contains the source code of the library.

`nob` is a bootstrap and entrypoint shell script for nob and your kotlin source files.
Make sure the KOTLIN_LIB path is correct.

# Usage
Bootstrap nob for the first time, and compile Main.kt
> ./nob Main.kt

Compile changes in nob.kt or Main.kt
> ./nob Main.kt

