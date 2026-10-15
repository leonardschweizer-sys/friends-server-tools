plugins {
    java
}

group = "net.friendsserver"
version = "1.3.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

// Compile-only stand-ins for the few BetterTeams classes we call.
// They are NOT packaged into the jar; at runtime the real BetterTeams classes are used.
val stubs: SourceSet = sourceSets.create("stubs")

val paperApi = "io.papermc.paper:paper-api:26.1.2.build.+"

dependencies {
    compileOnly(paperApi)
    "stubsCompileOnly"(paperApi)
    compileOnly(stubs.output)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
}

tasks.processResources {
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}

tasks.jar {
    archiveFileName.set("FriendsTools-${project.version}.jar")
}
