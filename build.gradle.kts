plugins {
    alias(libs.plugins.kotlin.multiplatform)

    alias(libs.plugins.vanniktech.maven.publish)
    alias(libs.plugins.signing)
}

group = "net.nekocurit.i4399_ocr"
version = project.property("version") as String

repositories {
    mavenCentral()
}

val resources2kt = tasks.register<Resources2KtTask>("resources2kt") {
    description = ""

    className.set("Resouces")
    packageName.set("${rootProject.group}")

    resourceDir.set(layout.projectDirectory.dir("src/commonMain/resources"))
    outputDir.set(layout.buildDirectory.dir("generated/sources/resources/commonMain/kotlin"))
}

kotlin {
    jvm()
    linuxX64()
    linuxArm64()
    mingwX64()
    macosArm64()
    js {
        browser()
        nodejs()
    }
    sourceSets {
        commonMain {
            kotlin.srcDir(resources2kt.flatMap { it.outputDir })
        }
    }
}



val isRelease = System.getenv("GITHUB_REF_TYPE") == "tag"
val privateKey = let {
    val key = System.getenv("GPG_PRIVATE_KEY")
        ?.replace("\\n", "\n")
        ?.takeIf { it.isNotBlank() }
        ?: return@let null
    val password = System.getenv("GPG_PASSWORD")
        ?.takeIf { it.isNotBlank() }
        ?: return@let null

    Pair(key, password)
}

mavenPublishing {
    if (isRelease) publishToMavenCentral()
    publishing {
        repositories {
            mavenLocal()
        }
    }
    if (privateKey != null) signAllPublications()
    coordinates("io.github.kawaiinekochanproject", project.name, project.version.toString())
    pom {
        name.set("I4399Ocr")
        description.set("A dedicated graphics recognition library for 4399.")
        url.set("https://github.com/KawaiiNekoChanProject/I4399Ocr")
        licenses {
            license {
                name.set("Zero-Clause BSD License")
                url.set("https://opensource.org/license/0bsd")
            }
        }
        developers {
            developer {
                id.set("nekocurit")
                name.set("nekocurit")
            }
        }
        scm {
            connection.set("scm:git:https://github.com/KawaiiNekoChanProject/I4399Ocr.git")
            developerConnection.set("scm:git:ssh://git@github.com/KawaiiNekoChanProject/I4399Ocr.git")
            url.set("https://github.com/KawaiiNekoChanProject/I4399Ocr")
        }
    }
}

privateKey?.also { (key, password) ->
    signing {
        useInMemoryPgpKeys(key, password)
        sign(publishing.publications)
    }
}