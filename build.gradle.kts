plugins {
    java
}

group = "kr.minq"
version = "0.9.0"

repositories {
    mavenCentral()
    maven {
        name = "papermc"
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.87-stable")
    compileOnly("com.google.code.gson:gson:2.11.0")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

val itemRaceSource = file("src/main/java/kr/minq/itemrace/ItemRacePlugin.java")

tasks {
    compileJava {
        options.encoding = "UTF-8"
        options.release.set(25)

        doFirst {
            if (itemRaceSource.isFile) {
                val original = itemRaceSource.readText(Charsets.UTF_8)
                val fixed = original.replace(
                    "Sound.UI_TOAST_CHALLENGE",
                    "Sound.ENTITY_PLAYER_LEVELUP"
                )
                if (fixed != original) {
                    itemRaceSource.writeText(fixed, Charsets.UTF_8)
                }
            }
        }
    }

    processResources {
        filteringCharset = "UTF-8"
        filesMatching("plugin.yml") {
            expand("version" to project.version)
        }
    }

    jar {
        archiveBaseName.set("ItemRace")
        archiveVersion.set(project.version.toString())
    }
}
