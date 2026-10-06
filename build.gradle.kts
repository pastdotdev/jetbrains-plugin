import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.20"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "dev.past"
version = "0.1.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.14.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // The platform's test runtime loads JUnit 4 classes whatever framework the tests use.
    testRuntimeOnly("junit:junit:4.13.2")
    intellijPlatform {
        // The platform the plugin supports from: 2026.2 is where the IDE's MCP server lets a plugin
        // list its tool directly to Junie (McpToolFilterProvider with router-only states).
        intellijIdea("2026.2.3")
        // The IDE's own MCP server, where the recall tool is offered to agents. Optional at runtime.
        bundledPlugin("com.intellij.mcpServer")
    }
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        // Rely on the JVM's own interface default methods. Otherwise Kotlin writes a forwarder in
        // every class that implements a platform interface, and the Plugin Verifier reports each
        // forwarder as our use of that method, deprecated or experimental ones included.
        freeCompilerArgs.add("-jvm-default=no-compatibility")
    }
}

tasks.test {
    useJUnitPlatform()
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "262"
            // Only public platform APIs are used, so no upper bound is declared.
            untilBuild = provider { null }
        }
    }
    // The author's signature on the zip, checked by the IDE at install: an unsigned plugin installs
    // behind a warning. The certificate and its key come from the environment, never from this
    // repository.
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }
    pluginVerification {
        ides {
            // The oldest build the plugin claims, the build it is compiled against, and the other
            // IDEs Junie runs in most, at their current 2026.2 release.
            create(IntelliJPlatformType.IntellijIdea, "2026.2")
            create(IntelliJPlatformType.IntellijIdea, "2026.2.3")
            create(IntelliJPlatformType.PyCharm, "2026.2.3")
            create(IntelliJPlatformType.WebStorm, "2026.2.3")
            create(IntelliJPlatformType.Rider, "2026.2.3")
        }
    }
}
