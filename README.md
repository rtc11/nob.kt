# nob.kt - NoBuild for Kotlin
This is inspired by the header only library version for C [https://tsoding/nob.h](https://github.com/tsoding/nob.h)

The idea is to build Kotlin files only with the use of Kotlin it self. 
You only need to install the Kotlin compiler.

# Setup
`nob.kt` contains the source code that can compile Kotlin files.

`nob` is a bootstrap shell-script for ease, but its not necessary, you can do this manually.
If using the shell script `nob`, make sure its pointing to the correct KOTLIN_LIB path.
This also assumes you have the binaries for kotlinc and java available.

# Usage
Bootstrap nob and compile Main.kt:
> ./nob Main.kt

Compile Main.kt:
> ./nob Main.kt

Compile nob.kt:
> ./nob Main.kt

