package nob

import test.*
import java.nio.file.Paths

class NobTest {

    @Test
    fun `can resolve default comp cp`() {
        val module = Module()
        val target = Paths.get(System.getProperty("user.dir"), "out")
        eq("$target", module.compile_cp())
    }

    @Test
    fun `can resolve comp cp with lib`() {
        val module = Module(libs = listOf(Lib.of("a:b:c")))
        val target = Paths.get(System.getProperty("user.dir"), "out")
        val lib = Paths.get(System.getProperty("user.home"), ".nob_cache", "a", "b-c.jar")
        eq("$lib:$target", module.compile_cp())
    }

    @Test
    fun `can resolve comp cp with target`() {
        val module = Module(target = "out/custom")
        val target = Paths.get(System.getProperty("user.dir"), "out", "custom")
        eq("$target", module.compile_cp())
    }

    @Test
    fun `can resolve comp cp with mod`() {
        val module = Module(mods = listOf(Module(src = "out")))
        val target = Paths.get(System.getProperty("user.dir"), "out")
        val mods = Paths.get(System.getProperty("user.dir"), "out", "out") // target/src = out/out
        eq("$mods:$target", module.compile_cp())
    }

    @Test
    fun `can resolve comp cp with lib target mod`() {
        val module = Module(
            target = "out/custom",
            libs = listOf(Lib.of("a:b:c")),
            mods = listOf(Module(src = "out")),
        )
        val libs = Paths.get(System.getProperty("user.home"), ".nob_cache", "a", "b-c.jar")
        val mods = Paths.get(System.getProperty("user.dir"), "out", "out") // target/src = out/out
        val target = Paths.get(System.getProperty("user.dir"), "out", "custom")
        eq("$libs:$mods:$target", module.compile_cp())
    }

    @Test
    fun `can resolve run cp for src`() {
        val module = Module(src = "nob")
        val res = Paths.get(System.getProperty("user.dir"), "res")
        val target = Paths.get(System.getProperty("user.dir"), "out")
        val src_target = Paths.get(System.getProperty("user.dir"), "out", "app")
        eq("$res:$target:$src_target", module.runtime_cp())
    }

    @Test
    fun `can resolve run cp with lib`() {
        val module = Module(src = "nob", libs = listOf(Lib.of("a:b:c")))
        val lib = Paths.get(System.getProperty("user.home"), ".nob_cache", "a", "b-c.jar")
        val res = Paths.get(System.getProperty("user.dir"), "res")
        val target = Paths.get(System.getProperty("user.dir"), "out")
        val src_target = java.nio.file.Paths.get(System.getProperty("user.dir"), "out", "app")
        eq("$lib:$res:$target:$src_target", module.runtime_cp())
    }

    @Test
    fun `can resolve run cp with target`() {
        val module = Module(src = "nob", target = "out/custom")
        val res = Paths.get(System.getProperty("user.dir"), "res")
        val target = Paths.get(System.getProperty("user.dir"), "out", "custom")
        val src_target = Paths.get(System.getProperty("user.dir"), "out", "custom", "app")
        eq("$res:$target:$src_target", module.runtime_cp())
    }

    @Test
    fun `can resolve run cp with mod`() {
        val module = Module(src = "nob", mods = listOf(Module(src = "out")))
        val mods = Paths.get(System.getProperty("user.dir"), "out", "out") // target/src = out/out
        val target = Paths.get(System.getProperty("user.dir"), "out")
        val src_target = Paths.get(System.getProperty("user.dir"), "out", "app")
        val res = Paths.get(System.getProperty("user.dir"), "res")
        eq("$mods:$res:$target:$src_target", module.runtime_cp())
    }

    @Test
    fun `can resolve run cp with lib target mod`() {
        val module = Module(
            src = "nob",
            target = "out/custom",
            libs = listOf(Lib.of("a:b:c")),
            mods = listOf(Module(src = "out")),
        )
        val libs = Paths.get(System.getProperty("user.home"), ".nob_cache", "a", "b-c.jar")
        val mods = Paths.get(System.getProperty("user.dir"), "out", "out") // target/src = out/out
        val target = Paths.get(System.getProperty("user.dir"), "out", "custom")
        val src_target = Paths.get(System.getProperty("user.dir"), "out", "custom", "app")
        val res = Paths.get(System.getProperty("user.dir"), "res")
        eq("$libs:$mods:$res:$target:$src_target", module.runtime_cp())
    }

}
