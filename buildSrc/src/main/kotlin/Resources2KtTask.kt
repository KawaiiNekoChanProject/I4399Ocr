
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.util.Base64

abstract class Resources2KtTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val resourceDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Input
    abstract val className: Property<String>

    @get:Input
    abstract val packageName: Property<String>

    init {
        className.convention("Res")
        packageName.convention("generated.resources")
        outputs.cacheIf { true }
    }

    @TaskAction
    fun generate() {
        val resourceRoot = resourceDir.get().asFile

        val files = resourceRoot.walkTopDown()
            .filter { it.isFile }
            .sortedBy {
                it.relativeTo(resourceRoot).invariantSeparatorsPath
            }
            .toList()

        val names = files.map {
            it.nameWithoutExtension.replace(
                Regex("[^A-Za-z0-9_]"), "_"
            )
        }

        require(names.distinct().size == names.size) {
            "Duplicate resource names: $names"
        }

        val classNameValue = className.get()
        val packageNameValue = packageName.get()

        require(
            classNameValue.matches(
                Regex("[A-Za-z_][A-Za-z0-9_]*")
            )
        ) {
            "Invalid Kotlin class name: $classNameValue"
        }

        require(
            packageNameValue.split(".").all {
                it.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))
            }
        ) {
            "Invalid Kotlin package name: $packageNameValue"
        }

        val packageDir = outputDir.get().asFile.resolve(
            packageNameValue.replace('.', '/')
        )
        packageDir.mkdirs()

        val outputFile = packageDir.resolve("$classNameValue.kt")

        outputFile.writeText(buildString {
            appendLine("package $packageNameValue")
            appendLine()
            appendLine("import kotlin.io.encoding.Base64")
            appendLine("import kotlin.io.encoding.ExperimentalEncodingApi")
            appendLine()
            appendLine("@OptIn(ExperimentalEncodingApi::class)")
            appendLine("object $classNameValue {")

            files.forEachIndexed { index, file ->
                val name = names[index]
                val base64 = Base64.getEncoder()
                    .encodeToString(file.readBytes())
                val chunks = base64.chunked(4096)

                appendLine("    val $name: ByteArray by lazy {")
                appendLine("        Base64.decode(")

                chunks.forEachIndexed { chunkIndex, chunk ->
                    val suffix = if (chunkIndex < chunks.lastIndex) {
                        " +"
                    } else {
                        ""
                    }

                    appendLine(
                        "            \"$chunk\"$suffix"
                    )
                }

                appendLine("        )")
                appendLine("    }")
                appendLine()
            }

            appendLine("}")
        })

        logger.lifecycle(
            "Generated ${files.size} resources into $outputFile"
        )
    }
}
