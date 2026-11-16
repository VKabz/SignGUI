plugins {
    `java-library`
    `maven-publish`
}

group = "ru.vkabz.signgui"
version = "2.5.5-gamai"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

// Build the API from source, preserving its Java 8 bytecode and public API.
// Reuse only unchanged legacy wrappers from the upstream release: compiling all
// old NMS modules requires dozens of obsolete Spigot/Paper server artifacts.
val legacyWrappers by configurations.creating {
    isTransitive = false
    resolutionStrategy.useGlobalDependencySubstitutionRules = false
}

val minecraftData = providers.gradleProperty("minecraftData")
    .orElse("../Servers/docker-server/minecraft-data")
val serverData = file(minecraftData.get())
val serverJar = file("$serverData/versions/26.3/leaf-26.3.jar")
val leafApiJar = file("$serverData/libraries/cn/dreeam/leaf/leaf-api/26.3.local-SNAPSHOT/leaf-api-26.3.local-SNAPSHOT.jar")

sourceSets.main {
    java.setSrcDirs(listOf("api/src/main/java", "wrapper/src/main/java"))
}

// The compile-time Bukkit API needs Java 21; our shared classes still target 8.
configurations.compileClasspath {
    attributes.attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 21)
}

val minecraft26 by sourceSets.creating {
    java.srcDir("Mojang26_R1/src/main/java")
    compileClasspath += sourceSets.main.get().output + legacyWrappers
}

// Compile the boolean API against 26.2, rather than inheriting the unsafe legacy lifecycle.
val minecraft26_2 by sourceSets.creating {
    java.srcDir("Mojang26_2/src/main/java")
    compileClasspath += sourceSets.main.get().output + minecraft26.output
}

sourceSets.test {
    compileClasspath += minecraft26.output + minecraft26.compileClasspath
    runtimeClasspath += minecraft26.output + minecraft26.compileClasspath
}

dependencies {
    legacyWrappers("de.rapha149.signgui:signgui:2.5.4")
    compileOnly("io.papermc.paper:paper-api:1.20.6-R0.1-SNAPSHOT")
    compileOnly("io.netty:netty-codec:4.1.118.Final")
    compileOnly("commons-lang:commons-lang:2.6")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.mockito:mockito-core:5.24.0")

    // File dependencies are compileOnly; no server, API or bundled libraries
    // are included in the published SignGUI jar.
    add(minecraft26.compileOnlyConfigurationName, files(serverJar, leafApiJar))
    add(minecraft26.compileOnlyConfigurationName, fileTree("$serverData/libraries") { include("**/*.jar") })
    add(minecraft26_2.compileOnlyConfigurationName, files("$serverData/versions/26.2/leaf-26.2.jar"))
    add(minecraft26_2.compileOnlyConfigurationName, fileTree("$serverData/libraries") { include("**/*.jar") })
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.compileJava {
    options.release.set(8)
}

tasks.test {
    useJUnitPlatform()
    workingDir = layout.buildDirectory.dir("test-work").get().asFile
    doFirst { workingDir.mkdirs() }
}

tasks.named<JavaCompile>(minecraft26.compileJavaTaskName) {
    // JDK 25 reads the server classes; Java 21 output also works with older
    // consumer shading tools that cannot yet parse Java 25 class files.
    options.release.set(21)
    doFirst {
        check(serverJar.isFile && leafApiJar.isFile) {
            "Missing local Leaf 26.3 jars. Set -PminecraftData=/path/to/minecraft-data"
        }
    }
}

tasks.named<JavaCompile>(minecraft26_2.compileJavaTaskName) {
    options.release.set(21)
}

tasks.jar {
    from(minecraft26.output)
    from(minecraft26_2.output)
    from({ legacyWrappers.map { zipTree(it) } }) {
        include("de/rapha149/signgui/version/*Wrapper*.class")
        exclude("de/rapha149/signgui/version/VersionWrapper.class")
    }
    from("LICENSE")
    manifest.attributes["Implementation-Version"] = project.version
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

publishing {
    publications {
        create<MavenPublication>("gamai") {
            groupId = "de.rapha149.signgui"
            artifact(tasks.jar)
            pom {
                name.set("SignGUI (Gamai fork)")
                description.set("SignGUI with Mojang-mapped Minecraft 26.x support")
                url.set("https://github.com/VKabz/SignGUI")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/license/mit")
                    }
                }
            }
        }
    }
}
