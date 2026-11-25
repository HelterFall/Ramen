package io.github.helterfall.ramen.importer

import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NsisArchiveExtractorTest {
    @Test
    fun normalizesWindowsSeparatorsAndDotSegments() {
        assertEquals("game/data/start.ax", normalizeNsisEntryName(".\\game\\data\\start.ax"))
    }

    @Test
    fun rejectsParentTraversalAndAbsolutePaths() {
        assertThrows(IOException::class.java) {
            normalizeNsisEntryName("..\\outside.txt")
        }
        assertThrows(IOException::class.java) {
            normalizeNsisEntryName("/absolute/file.txt")
        }
        assertThrows(IOException::class.java) {
            normalizeNsisEntryName("C:\\Windows\\system.ini")
        }
    }

    @Test
    fun rejectsControlCharactersAndExcessiveDepth() {
        assertThrows(IOException::class.java) {
            normalizeNsisEntryName("folder/\u0000evil")
        }
        assertThrows(IOException::class.java) {
            normalizeNsisEntryName("a/b/c/d", maxPathDepth = 3)
        }
    }

    @Test
    fun extractsUncompressedNonSolidNsisFile() {
        val dir=java.nio.file.Files.createTempDirectory("ramen-nsis").toFile()
        try {
            val installer=File(dir,"setup.exe")
            val output=File(dir,"out")
            val payload=byteArrayOf(1,2,3,4,5)
            installer.writeBytes(minimalInstaller("payload.bin",payload))
            val result=NsisArchiveExtractor().extract(installer,output)
            assertEquals(1,result.fileCount)
            assertEquals(payload.toList(),File(output,"payload.bin").readBytes().toList())
        } finally { dir.deleteRecursively() }
    }
    @Test
    fun inflatesRawDeflateAndHonorsOutputLimit() {
        val plain="raw NSIS Deflate".toByteArray(Charsets.UTF_8)
        val deflater=java.util.zip.Deflater(java.util.zip.Deflater.DEFAULT_COMPRESSION,true)
        val compressed=try {
            deflater.setInput(plain); deflater.finish()
            val buf=ByteArray(256); buf.copyOf(deflater.deflate(buf))
        } finally { deflater.end() }
        assertEquals(plain.toList(),inflateNsisDeflate(compressed,256).toList())
        assertThrows(IOException::class.java) { inflateNsisDeflate(compressed,2) }
    }
    private fun minimalInstaller(name:String,payload:ByteArray):ByteArray {
        val strings="\u0000$name\u0000".toByteArray(Charsets.UTF_16LE)
        val entries=68; val stringsAt=entries+28
        val header=ByteArray(stringsAt+strings.size)
        put32(header,20,entries); put32(header,24,1)
        put32(header,28,stringsAt); put32(header,32,strings.size)
        put32(header,entries,20); put32(header,entries+8,1); put32(header,entries+12,0)
        strings.copyInto(header,stringsAt)
        val record=ByteArray(payload.size+4); put32(record,0,payload.size); payload.copyInto(record,4)
        val fh=ByteArray(28)
        put32(fh,0,4); put32(fh,4,0xDEADBEEF.toInt())
        "NullsoftInst".toByteArray(Charsets.US_ASCII).copyInto(fh,8)
        put32(fh,20,header.size); put32(fh,24,28+4+header.size+record.size)
        val prefix=ByteArray(4); put32(prefix,0,header.size)
        return ByteArray(512)+fh+prefix+header+record
    }
    private fun put32(b:ByteArray,p:Int,v:Int) { repeat(4) { b[p+it]=(v ushr (8*it)).toByte() } }

}
