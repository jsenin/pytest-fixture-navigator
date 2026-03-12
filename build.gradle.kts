plugins {
    id("org.jetbrains.intellij.platform") version "2.3.0"
    kotlin("jvm") version "2.0.21"
}

group   = "com.jsenin.pytestfixturenavigator"
version = "0.1.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        pycharmProfessional("2025.1")
        bundledPlugin("Pythonid")
        pluginVerifier()
        zipSigner()
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "251"
            untilBuild = "251.*"
        }
    }
}

kotlin {
    jvmToolchain(21)
}
