plugins {
    kotlin("jvm")
}

// The core has NO Bukkit / PacketEvents dependency on purpose: everything that
// decides *what* a player sees lives here and is unit-tested without a server.
dependencies {
    testImplementation(kotlin("test"))
}

kotlin { jvmToolchain(21) }
tasks.test { useJUnitPlatform() }
