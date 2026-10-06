package dev.past.jetbrains

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/** Everything under ~/.past is the person's alone: folders 700, files 600 from the moment they exist. */
object PrivateFiles {
    fun directory(path: Path) {
        path.createDirectories()
        setPosix(path, "rwx------")
    }

    /** Written beside the target and moved over it, so a reader never sees half a file. */
    fun write(path: Path, text: String) {
        directory(path.parent)
        val temporary = path.resolveSibling("${path.fileName}.tmp")
        Files.deleteIfExists(temporary)
        Files.createFile(temporary)
        setPosix(temporary, "rw-------")
        temporary.writeText(text)
        Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun setPosix(path: Path, permissions: String) {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissions))
        } catch (_: UnsupportedOperationException) {
            // Windows has no POSIX permissions; the user profile is private there already.
        }
    }
}
