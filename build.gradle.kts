
import kr.entree.spigradle.kotlin.jitpack
import kr.entree.spigradle.kotlin.paper
import kr.entree.spigradle.kotlin.vaultAll

plugins {
    kotlin("jvm") version "1.4.10"

    id("kr.entree.spigradle") version "2.2.3"
    id("com.github.johnrengelman.shadow") version "5.2.0"

    id("org.jlleitschuh.gradle.ktlint") version "9.4.1"
    id("eclipse")
}

group = "com.dumbdogdiner"
version = "1.0.0-beta"

repositories {
    mavenCentral()
    jitpack()
    maven("https://repo.extendedclip.com/content/repositories/placeholderapi/")
    maven("https://repo.codemc.org/repository/maven-public/")
    maven {
        credentials {
            username = "${property("ghUser")}"
            password = "${property("ghPass")}"
        }
        url = uri("https://maven.pkg.github.com/DumbDogDiner/StickyAPI/")
    }
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation(kotlin("reflect"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.3.9")

    compileOnly(vaultAll())
    compileOnly(paper("1.16.4"))

    compileOnly("me.clip:placeholderapi:2.10.0")

    implementation("de.tr7zw:item-nbt-api-plugin:2.6.0")
    implementation("com.dumbdogdiner:stickyapi:1.4.6")
}

tasks {
    ktlintKotlinScriptCheck {
        dependsOn("ktlintFormat")
    }

    build {
        dependsOn("shadowJar")
    }

    shadowJar {
        archiveClassifier.set("")
    }

    spigot {
        authors = listOf("spazzylemons")
        softDepends = listOf("AnimatedScoreboard", "PlaceholderAPI", "Vault")
        depends = listOf()
        apiVersion = "1.16"
        permissions {
            create("stickysurvival.join") {
                description = "Allows a user to join and spectate games."
                defaults = "true"
            }

            create("stickysurvival.leave") {
                description = "Allows a user to leave games. Does not prevent leaving games by disconnecting."
                defaults = "true"
            }

            create("stickysurvival.reload") {
                description = "Allows a user to reload the plugin."
                defaults = "op"
            }

            create("stickysurvival.kit") {
                description = "Allows a user to choose a kit."
                defaults = "true"
            }

            create("stickysurvival.kits") {
                description = "Allows a user to list all kits."
                defaults = "true"
            }
        }
    }

    eclipse {
        classpath {
            containers = mutableSetOf("org.eclipse.buildship.core.gradleclasspathcontainer")
        }
    }
}
