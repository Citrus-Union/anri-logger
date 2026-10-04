import java.io.File

plugins {
    id("net.fabricmc.fabric-loom") version "1.17.20"
    `maven-publish`
}

val modVersion = property("mod.version").toString()
val loaderVersion = property("deps.fabric_loader").toString()
version = "$modVersion+mc${sc.current.version}"
base.archivesName = property("mod.id") as String
val javaRelease: String = sc.properties["java.release"]
val requiredJava = javaRelease.toInt()
val fabricApiVersion: String = sc.properties["deps.fabric_api"]
val minecraftCompatibility: String = sc.properties["mod.mc_compat"]

repositories { mavenCentral() }
loom {
    mods {
        register("anri-logger") { sourceSet(sourceSets.main.get()) }
    }
    fabricModJsonPath = rootProject.file("src/main/resources/fabric.mod.json")
}

dependencies {
    minecraft("com.mojang:minecraft:${sc.current.version}")
    implementation("net.fabricmc:fabric-loader:$loaderVersion")
    implementation("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")
    include(implementation("com.h2database:h2-mvstore:2.3.232")!!)
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test { useJUnitPlatform() }
fabricApi {
    configureTests {
        createSourceSet = true
        modId = "anri-logger-tests"
        enableGameTests = true
        enableClientGameTests = false
    }
}

// Optional co-loading tests; third-party mod JARs never enter the release or its runtime dependencies.
providers.gradleProperty("carpetTestJars").orNull?.let { paths ->
    dependencies.add("gametestRuntimeOnly",files(paths.split(File.pathSeparator)))
}

tasks.processResources {
    val props = mapOf("version" to modVersion, "minecraft" to minecraftCompatibility,
                     "loader" to loaderVersion, "java" to requiredJava.toString())
    inputs.properties(props)
    filesMatching("fabric.mod.json") { expand(props) }
    filesMatching("*.mixins.json") { expand("java" to requiredJava.toString()) }
}

tasks.withType<JavaCompile>().configureEach { options.release = requiredJava }
java {
    withSourcesJar()
    sourceCompatibility = JavaVersion.toVersion(requiredJava)
    targetCompatibility = JavaVersion.toVersion(requiredJava)
}

tasks.jar {
    from(rootProject.file("THIRD_PARTY_NOTICES.txt"))
    from(rootProject.file("LICENSE")) { rename { "${it}_anri-logger" } }
}

tasks.named<Jar>("sourcesJar") {
    from(rootProject.file("LICENSE"), rootProject.file("THIRD_PARTY_NOTICES.txt"))
}

tasks.register<Copy>("buildAndCollect") {
    group = "build"
    description = "Build this Minecraft version and collect its binary and sources JARs."
    dependsOn(tasks.build)
    from(tasks.jar.flatMap { it.archiveFile }, tasks.named<Jar>("sourcesJar").flatMap { it.archiveFile })
    into(rootProject.layout.buildDirectory.dir("libs"))
}

publishing {
    publications { create<MavenPublication>("mavenJava") { from(components["java"]) } }
}
