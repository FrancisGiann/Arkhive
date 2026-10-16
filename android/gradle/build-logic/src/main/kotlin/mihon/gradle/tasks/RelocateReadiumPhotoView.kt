package mihon.gradle.tasks

import org.gradle.api.artifacts.transform.CacheableTransform
import org.gradle.api.artifacts.transform.InputArtifact
import org.gradle.api.artifacts.transform.TransformAction
import org.gradle.api.artifacts.transform.TransformOutputs
import org.gradle.api.artifacts.transform.TransformParameters
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.commons.Remapper
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Relocate only Readium's vendored PhotoView; retain Mihon's original dependency/ABI. */
@CacheableTransform
abstract class RelocateReadiumPhotoView : TransformAction<TransformParameters.None> {
    @get:InputArtifact
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val inputArtifact: Provider<FileSystemLocation>
    override fun transform(outputs: TransformOutputs) {
        val input = inputArtifact.get().asFile
        if (!input.name.startsWith("readium-navigator-")) {
            outputs.file(input)
            return
        }
        val output = outputs.file(input.nameWithoutExtension + "-arkhive.aar")
        val original = "com/github/chrisbanes/photoview"
        val relocated = "org/readium/r2/navigator/internal/arkhivephotoview"
        fun virtualHosts(value: String) = value.replace("readium_package", "readium-package.invalid")
            .replace("readium_assets", "readium-assets.invalid")
        val mapper = object : Remapper() {
            override fun map(name: String): String = if (name.startsWith(original + "/"))
                relocated + name.removePrefix(original) else name
            override fun mapValue(value: Any?): Any? {
                if (value is String) return virtualHosts(value.replace(original, relocated)
                    .replace(original.replace('/', '.'), relocated.replace('/', '.')))
                return super.mapValue(value)
            }
        }
        fun remapJar(bytes: ByteArray): ByteArray {
            val result = ByteArrayOutputStream()
            ZipInputStream(ByteArrayInputStream(bytes)).use { jar ->
                ZipOutputStream(result).use { out ->
                    while (true) {
                        val entry = jar.nextEntry ?: break
                        if (entry.isDirectory) continue
                        val content = jar.readBytes()
                        val changed = if (entry.name.endsWith(".class")) {
                            val writer = ClassWriter(0)
                            ClassReader(content).accept(ClassRemapper(writer, mapper), 0)
                            writer.toByteArray()
                        } else content
                        val name = if (entry.name.startsWith(original + "/"))
                            relocated + entry.name.removePrefix(original) else entry.name
                        out.putNextEntry(ZipEntry(name).apply { time = 0L }); out.write(changed); out.closeEntry()
                    }
                }
            }
            return result.toByteArray()
        }
        ZipInputStream(input.inputStream()).use { aar ->
            ZipOutputStream(output.outputStream()).use { out ->
                while (true) {
                    val entry = aar.nextEntry ?: break
                    if (entry.isDirectory) continue
                    val bytes = aar.readBytes()
                    val changed = when {
                        entry.name.endsWith(".jar") -> remapJar(bytes)
                        listOf(".xml", ".pro", ".js", ".css", ".json").any(entry.name::endsWith) ->
                            virtualHosts(bytes.toString(Charsets.UTF_8)
                                .replace(original.replace('/', '.'), relocated.replace('/', '.'))).toByteArray()
                        else -> bytes
                    }
                    out.putNextEntry(ZipEntry(entry.name).apply { time = 0L }); out.write(changed); out.closeEntry()
                }
            }
        }
    }
}
