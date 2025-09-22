package nob

import test.*
import util.*
import nob.*

class NobTest {

    @Test
    fun `default classpath`() {
        val module = Module()
        val out = java.nio.file.Paths.get(System.getProperty("user.dir"), "out")
        eq("$out", module.compile_cp())
    }

    @Test
    fun `classpath with one lib`() {
        val module = Module(libs = listOf(Lib.of("a:b:c")))
        val out = java.nio.file.Paths.get(System.getProperty("user.dir"), "out")
        val lib = java.nio.file.Paths.get(System.getProperty("user.home"), ".nob_cache", "a", "b-c.jar")
        eq("$lib:$out", module.compile_cp())
    }

    @Test
    fun `classpath with custom target`() {
        val module = Module(target = "custom")
        val out = java.nio.file.Paths.get(System.getProperty("user.dir"), "custom")
        eq("$out", module.compile_cp())
    }
}
