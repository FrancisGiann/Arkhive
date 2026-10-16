package mihon.gradle.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

abstract class PrepareControlledFixturesTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val apkDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun prepare() {
        val destination = outputDirectory.get().asFile.resolve("readingapp/compat")
        check(destination.mkdirs() || destination.isDirectory)
        listOf("legacy", "modern", "unsupported", "malformed").forEach { flavor ->
            val candidates = apkDirectory.get().asFile.resolve("$flavor/debug")
                .listFiles { file -> file.extension == "apk" }.orEmpty()
            check(candidates.size == 1) { "Expected one self-authored $flavor fixture APK" }
            candidates.single().copyTo(destination.resolve("$flavor.apk"), overwrite = true)
        }
    }
}
