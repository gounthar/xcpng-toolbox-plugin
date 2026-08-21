package dev.gounthar.xcpng.toolbox

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * No em dash survives in anything this repository publishes.
 *
 * The rule came from reading one out loud. The certificate branch of the Test connection popup was
 * finally transcribed off the screen on 2026-08-21, and the dash arrived in the middle of the
 * sentence as the spoken words "em dash", in a message whose whole job is to tell somebody how to
 * fix their connection. A mark that has to be pronounced is doing less work than the full stop it
 * replaced.
 *
 * The character is an escaped constant below rather than a literal, or this file would be its own
 * first violation. It was, on the first run: the paragraph saying so was already written, and the
 * scanning line used the character anyway.
 *
 * **Why a test rather than a check on the pipeline.** It fails on the local build, at the moment
 * somebody writes one, rather than after a push. The eight that started this had sat there for
 * days precisely because nothing looked at them until a human read one aloud.
 *
 * **Why it asks git what to scan.** The rule covers comments and KDoc as well as strings, because
 * this repository is going public and the prose in the source is published writing too. "What is
 * published" is exactly "what git tracks", so the tracked set is the honest definition of the
 * scope rather than a hand-maintained list. It also keeps deliberately untracked working notes out
 * of scope without this file having to name them, which the project's own rule about not
 * advertising them requires.
 */
class NoEmDashesTest {

    private companion object {
        /** The character under test, escaped. See the note in the class KDoc. */
        const val EM_DASH = '\u2014'

        /** The separator `git ls-files -z` puts between paths, so a path with a space survives. */
        const val NUL = '\u0000'

        /** A newline, for the same reason the two above are escapes. */
        const val LF = "\n"

        /**
         * Scanned by extension rather than everything tracked, so adding a binary or a fixture
         * cannot make this test start reading things it has no opinion about.
         */
        val EXTENSIONS = setOf("kt", "kts", "md", "yml", "yaml", "toml", "json", "svg", "properties")
    }

    @Test
    fun `nothing this repository publishes contains an em dash`() {
        val root = projectRoot()
        val tracked = trackedFiles(root).filter { it.extension in EXTENSIONS }
        val offenders = mutableListOf<String>()

        tracked.forEach { file ->
            file.readText().lineSequence().forEachIndexed { index, line ->
                if (line.contains(EM_DASH)) {
                    offenders += "${file.relativeTo(root).invariantSeparatorsPath}:${index + 1}: ${line.trim()}"
                }
            }
        }

        // A scan that silently covered nothing would pass, and a tool reporting absence is a claim
        // that needs its own control. Both floors sit well under the real count, so they catch a
        // broken listing without tripping on ordinary growth.
        assertTrue(
            tracked.size >= 20,
            "only ${tracked.size} tracked files were scanned, so this test proved nothing. " +
                "Root was ${root.absolutePath}",
        )
        assertTrue(
            tracked.any { it.name == "XoFailure.kt" },
            "the scan never reached XoFailure.kt, which holds the messages this rule exists for",
        )

        if (offenders.isNotEmpty()) {
            fail(
                "em dash found in ${offenders.size} place(s). Use the punctuation the clause " +
                    "wants: a full stop between two sentences, a colon before an explanation, a " +
                    "comma or parentheses around an aside." + LF + offenders.joinToString(LF),
            )
        }
    }

    /**
     * The tracked set, straight from git.
     *
     * A failure to run git fails the test rather than returning an empty list. An empty list is
     * indistinguishable from a clean repository, and a check that passes when it could not run is
     * worse than no check at all.
     */
    private fun trackedFiles(root: File): List<File> {
        val process = ProcessBuilder("git", "ls-files", "-z")
            .directory(root)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
        check(process.waitFor(30, TimeUnit.SECONDS)) { "git ls-files did not finish within 30 s" }
        check(process.exitValue() == 0) { "git ls-files failed: $output" }
        return output.split(NUL)
            .filter { it.isNotEmpty() }
            .map { File(root, it) }
            .filter { it.isFile }
    }

    /**
     * Gradle runs tests with the project directory as the working directory. Walking up to the
     * settings file makes that an observation rather than an assumption, and keeps this working if
     * it is ever run with a different default.
     */
    private fun projectRoot(): File {
        var candidate: File? = File(".").absoluteFile
        while (candidate != null) {
            if (File(candidate, "settings.gradle.kts").isFile) return candidate
            candidate = candidate.parentFile
        }
        error("could not find settings.gradle.kts above ${File(".").absolutePath}")
    }
}
