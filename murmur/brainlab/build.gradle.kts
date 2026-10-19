// Runs Murmur Brain's real prompts, parsers and tools against the real model on a computer.
// The plain-Kotlin brain files are compiled straight from the app, so what passes here is what ships.
plugins { kotlin("jvm") version "2.3.21"; application }
kotlin { jvmToolchain(21) }
sourceSets.main {
    kotlin.srcDir("src")
    kotlin.srcDir("../app/src/main/java")
    kotlin.include(
        "*.kt",
        "app/murmur/brain/BrainPrompts.kt", "app/murmur/brain/Calc.kt", "app/murmur/brain/Skills.kt",
        "app/murmur/brain/PlanTime.kt", "app/murmur/brain/tools/*.kt",
    )
}
dependencies { implementation("com.google.ai.edge.litertlm:litertlm-jvm:0.18.0") }
application {
    mainClass.set(providers.gradleProperty("mainClass").orElse("PromptLabKt"))
    applicationDefaultJvmArgs = listOf("-Dstdout.encoding=UTF-8")
}
