package io.github.helterfall.ramen.runtime.livemaker

import io.github.helterfall.ramen.inspection.LiveMakerEdition
import io.github.helterfall.ramen.inspection.LiveMakerLsb
import io.github.helterfall.ramen.inspection.LiveMakerLsbFixture
import io.github.helterfall.ramen.inspection.LiveMakerLsbParser
import io.github.helterfall.ramen.inspection.ReadOnlyRandomAccessSource
import io.github.helterfall.ramen.inspection.FileRandomAccessSource
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveMakerVirtualMachineTest {
    @Test
    fun executesVariableArithmeticAndSurfacesTextToTheHost() {
        val scriptBytes = LiveMakerLsbFixture.build(
            commands = listOf(
                LiveMakerLsbFixture.variableNew("score", 42),
                LiveMakerLsbFixture.calcIncrement("score", 8),
                LiveMakerLsbFixture.textInsert("TpWord".toByteArray()),
                LiveMakerLsbFixture.exitWhenTrue()
            )
        )

        withSource(scriptBytes) { source ->
            val script = LiveMakerLsbParser().parse(source)
            val vm = LiveMakerVirtualMachine(script)

            val event = vm.runUntilEvent()
            assertTrue(event is LiveMakerVmEvent.TextBlock)
            assertEquals(50L, vm.variablesSnapshot()["score"])

            vm.resume()
            assertTrue(vm.runUntilEvent() is LiveMakerVmEvent.Completed)
        }
    }

    private fun withSource(bytes: ByteArray, block: (ReadOnlyRandomAccessSource) -> Unit) {
        val directory = Files.createTempDirectory("ramen-livemaker-vm-test").toFile()
        try {
            File(directory, "script.lsb").apply { writeBytes(bytes) }
                .let(::FileRandomAccessSource)
                .use(block)
        } finally {
            directory.deleteRecursively()
        }
    }
}
