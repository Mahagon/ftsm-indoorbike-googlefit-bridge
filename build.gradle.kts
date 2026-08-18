plugins {
    id("com.android.application") version "9.3.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
    id("com.google.devtools.ksp") version "2.3.6" apply false
    id("com.diffplug.spotless") version "8.10.0"
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
