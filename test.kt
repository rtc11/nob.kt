package nob

import test.*
import java.nio.file.Paths

class ModuleTest {
    private val opts = Opts()

    @Test
    fun `compile classpath - defaults`() {
        val module = Module(opts)
        val target = Paths.get(System.getProperty("user.dir"), "out")
        eq("$target", module.compile_cp())
    }

    @Test
    fun `compile classpath - add lib`() {
        val module = Module(opts, libs = listOf(Lib.of("a:b:c")))
        val target = Paths.get(System.getProperty("user.dir"), "out")
        val lib = Paths.get(System.getProperty("user.home"), ".nob_cache", "a", "b-c.jar")
        eq("$lib:$target", module.compile_cp())
    }

    @Test
    fun `compile classpath - change target`() {
        val module = Module(opts, target = "out/custom")
        val target = Paths.get(System.getProperty("user.dir"), "out", "custom")
        eq("$target", module.compile_cp())
    }

    @Test
    fun `compile classpath - add mod`() {
        val module = Module(opts, mods = listOf(Module(opts, src = "out")))
        val target = Paths.get(System.getProperty("user.dir"), "out")
        val mods = Paths.get(System.getProperty("user.dir"), "out", "out") // target/src = out/out
        eq("$mods:$target", module.compile_cp())
    }

    @Test
    fun `compile classpath - change all`() {
        val module = Module(
            opts,
            target = "out/custom",
            libs = listOf(Lib.of("a:b:c")),
            mods = listOf(Module(opts, src = "out")),
        )
        val libs = Paths.get(System.getProperty("user.home"), ".nob_cache", "a", "b-c.jar")
        val mods = Paths.get(System.getProperty("user.dir"), "out", "out") // target/src = out/out
        val target = Paths.get(System.getProperty("user.dir"), "out", "custom")
        eq("$libs:$mods:$target", module.compile_cp())
    }

    @Test
    fun `runtime classpath - change src`() {
        val module = Module(opts, src = "nob", res = "res")
        val res = Paths.get(System.getProperty("user.dir"), "res")
        val target = Paths.get(System.getProperty("user.dir"), "out")
        val src_target = Paths.get(System.getProperty("user.dir"), "out")
        val kotlin_home = Paths.get(System.getenv("KOTLIN_HOME"), "libexec", "lib")
        val stdlib = kotlin_home.resolve("kotlin-stdlib.jar").toAbsolutePath().normalize().toString()
        eq("$res:$target:$src_target:$stdlib", module.runtime_cp())
    }

    @Test
    fun `runtime classpath - add lib`() {
        val module = Module(opts, src = "nob", libs = listOf(Lib.of("a:b:c")))
        val lib = Paths.get(System.getProperty("user.home"), ".nob_cache", "a", "b-c.jar")
        val target = Paths.get(System.getProperty("user.dir"), "out")
        val src_target = java.nio.file.Paths.get(System.getProperty("user.dir"), "out")
        val kotlin_home = Paths.get(System.getenv("KOTLIN_HOME"), "libexec", "lib")
        val stdlib = kotlin_home.resolve("kotlin-stdlib.jar").toAbsolutePath().normalize().toString()
        eq("$lib:$target:$src_target:$stdlib", module.runtime_cp())
    }

    @Test
    fun `runtime classpath - change target`() {
        val module = Module(opts, src = "nob", target = "out/custom")
        val target = Paths.get(System.getProperty("user.dir"), "out", "custom")
        val src_target = Paths.get(System.getProperty("user.dir"), "out", "custom")
        val kotlin_home = Paths.get(System.getenv("KOTLIN_HOME"), "libexec", "lib")
        val stdlib = kotlin_home.resolve("kotlin-stdlib.jar").toAbsolutePath().normalize().toString()
        eq("$target:$src_target:$stdlib", module.runtime_cp())
    }

    @Test
    fun `runtime classpath - add mod`() {
        val module = Module(opts, src = "nob", mods = listOf(Module(opts, src = "out")))
        val mods = Paths.get(System.getProperty("user.dir"), "out", "out") // target/src = out/out
        val target = Paths.get(System.getProperty("user.dir"), "out")
        val src_target = Paths.get(System.getProperty("user.dir"), "out")
        val kotlin_home = Paths.get(System.getenv("KOTLIN_HOME"), "libexec", "lib")
        val stdlib = kotlin_home.resolve("kotlin-stdlib.jar").toAbsolutePath().normalize().toString()
        eq("$mods:$target:$src_target:$stdlib", module.runtime_cp())
    }

    @Test
    fun `runtime classpath - change all`() {
        val module = Module(
            opts,
            name = "app",
            res = "res",
            src = "nob",
            target = "out/custom",
            libs = listOf(Lib.of("a:b:c")),
            mods = listOf(Module(opts, src = "out")),
        )
        val libs = Paths.get(System.getProperty("user.home"), ".nob_cache", "a", "b-c.jar")
        val mods = Paths.get(System.getProperty("user.dir"), "out", "out") // target/src = out/out
        val target = Paths.get(System.getProperty("user.dir"), "out", "custom")
        val src_target = Paths.get(System.getProperty("user.dir"), "out", "custom", "app")
        val res = Paths.get(System.getProperty("user.dir"), "res")
        val kotlin_home = Paths.get(System.getenv("KOTLIN_HOME"), "libexec", "lib")
        val stdlib = kotlin_home.resolve("kotlin-stdlib.jar").toAbsolutePath().normalize().toString()
        eq("$res:$libs:$mods:$target:$src_target:$stdlib", module.runtime_cp())
    }

}
