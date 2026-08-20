buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        constraints {
            classpath("org.bouncycastle:bcpkix-jdk18on:1.84")
            classpath("org.bouncycastle:bcprov-jdk18on:1.84")
            classpath("org.bouncycastle:bcutil-jdk18on:1.84")
            classpath("org.apache.commons:commons-lang3:3.18.0")
            classpath("org.apache.httpcomponents:httpclient:4.5.14")
            classpath("org.bitbucket.b_c:jose4j:0.9.6")
            classpath("org.jdom:jdom2:2.0.6.1")
        }
    }
}

plugins {
    id("com.android.application") version "9.3.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20-Beta2" apply false
    id("com.google.devtools.ksp") version "2.3.11" apply false
    id("com.diffplug.spotless") version "8.10.0"
}

allprojects {
    configurations.configureEach {
        resolutionStrategy.force(
            "io.netty:netty-buffer:4.1.137.Final",
            "io.netty:netty-codec:4.1.137.Final",
            "io.netty:netty-codec-http:4.1.137.Final",
            "io.netty:netty-codec-http2:4.1.137.Final",
            "io.netty:netty-codec-socks:4.1.137.Final",
            "io.netty:netty-common:4.1.137.Final",
            "io.netty:netty-handler:4.1.137.Final",
            "io.netty:netty-handler-proxy:4.1.137.Final",
            "io.netty:netty-resolver:4.1.137.Final",
            "io.netty:netty-transport:4.1.137.Final",
            "io.netty:netty-transport-native-unix-common:4.1.137.Final",
            "org.bouncycastle:bcpkix-jdk18on:1.84",
            "org.bouncycastle:bcprov-jdk18on:1.84",
            "org.bouncycastle:bcutil-jdk18on:1.84",
            "org.apache.commons:commons-lang3:3.18.0",
            "org.apache.httpcomponents:httpclient:4.5.14",
            "org.bitbucket.b_c:jose4j:0.9.6",
            "org.jdom:jdom2:2.0.6.1",
        )
    }
}

spotless {
    kotlin {
        target("app/src/**/*.kt")
        ktlint("1.8.0").editorConfigOverride(
            mapOf("ktlint_function_naming_ignore_when_annotated_with" to "Composable"),
        )
    }
    kotlinGradle {
        target("*.gradle.kts", "app/*.gradle.kts")
        ktlint("1.8.0")
    }
    format("misc") {
        target("*.md", ".gitignore", ".github/**/*.yml", ".github/**/*.yaml")
        trimTrailingWhitespace()
        endWithNewline()
    }
}
