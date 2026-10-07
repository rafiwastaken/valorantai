plugins {
    kotlin("jvm")
}

repositories {
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.codemc.io/repository/maven-releases/")
}

val packetEventsVersion = "2.9.5" // pin to the PacketEvents plugin build you run on the server

dependencies {
    implementation(project(":core"))
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    compileOnly("com.github.retrooper:packetevents-spigot:$packetEventsVersion")
}

kotlin { jvmToolchain(21) }

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("plugin.yml") { expand("version" to project.version) }
}

// Ship the (dependency-free) core inside the plugin jar. Kotlin's stdlib is pulled by the
// server via `libraries:` in plugin.yml, PacketEvents via `depend:`.
tasks.jar {
    dependsOn(":core:classes")
    from(project(":core").layout.buildDirectory.dir("classes/kotlin/main"))
}
