package io.github.helterfall.ramen.runtime.systemnnn

import io.github.helterfall.ramen.parser.SystemNnnScript
import java.io.IOException

/**
 * Deliberately disabled placeholder.
 *
 * The former implementation treated a header logical base as a physical
 * UInt32 command table and guessed that values outside the image range meant
 * "next dialogue". The complete SPT samples disproved that interpretation.
 * Keep this type temporarily for source compatibility, but refuse to execute
 * unverified bytes until the VM opcode and operand grammar is implemented.
 */
@Deprecated("The System-NNN VM instruction grammar is not verified yet.")
class SystemNnnCommandDecoderImpl(
    gtbResourceCount: Int
) : SystemNnnCommandDecoder {

    init {
        require(gtbResourceCount >= 0) { "gtbResourceCount must not be negative." }
    }

    override fun decodeNext(
        script: SystemNnnScript,
        commandOffset: Long
    ): SystemNnnDecodedCommand {
        throw IOException(
            "System-NNN command decoding is not implemented. " +
                "The SPT pools are parsed, but opcode meanings are still unverified."
        )
    }
}
