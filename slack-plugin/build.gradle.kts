import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType

plugins {
  kotlin("jvm")
  `java-gradle-plugin`
  alias(libs.plugins.mavenPublish)
  alias(libs.plugins.bestPracticesPlugin)
  alias(libs.plugins.moshix)
  alias(libs.plugins.buildConfig)
}

gradlePlugin {
  plugins.create("slack-root") {
    id = "com.slack.gradle.root"
    implementationClass = "slack.gradle.SlackRootPlugin"
  }
  plugins.create("slack-base") {
    id = "com.slack.gradle.base"
    implementationClass = "slack.gradle.SlackBasePlugin"
  }
  plugins.create("apkVersioning") {
    id = "com.slack.gradle.apk-versioning"
    implementationClass = "slack.gradle.ApkVersioningPlugin"
  }
}

buildConfig {
  packageName("slack.gradle.dependencies")
  useKotlinOutput { internalVisibility = true }
}

// Copy our hooks into resources for InstallCommitHooks
tasks.named<ProcessResources>("processResources") {
  from(rootProject.layout.projectDirectory.dir("config/git/hooks")) {
    // Give it a common prefix for us to look for
    rename { name -> "githook-$name" }
  }
}

moshi { enableSealed.set(true) }

// This is necessary for included builds, as the KGP plugin isn't applied in them and thus doesn't
// apply disambiguation rules
dependencies.constraints {
  add("implementation", "io.github.pdvrieze.xmlutil:serialization") {
    attributes { attribute(KotlinPlatformType.attribute, KotlinPlatformType.jvm) }
  }
  add("implementation", "io.github.pdvrieze.xmlutil:core") {
    attributes { attribute(KotlinPlatformType.attribute, KotlinPlatformType.jvm) }
  }
}

dependencies {
  api(platform(libs.okhttp.bom))
  api(libs.okhttp)
  // Better I/O
  api(libs.okio)
  api(projects.agpHandlers.agpHandler82)
  api(projects.agpHandlers.agpHandler83)
  api(projects.agpHandlers.agpHandlerApi)

  implementation(platform(libs.coroutines.bom))
  implementation(libs.commonsText) { because("For access to its StringEscapeUtils") }
  implementation(libs.coroutines.core)
  implementation(libs.gradlePlugins.graphAssert) { because("To use in Gradle graphing APIs.") }
  implementation(libs.guava)
  // Graphing library with Betweenness Centrality algo for modularization score
  implementation(libs.jgrapht)
  implementation(libs.jna)
  implementation(libs.jna.platform)
  implementation(libs.kotlinCliUtil)
  implementation(libs.moshi)
  implementation(libs.oshi) { because("To read hardware information") }
  implementation(libs.rxjava)

  compileOnly(platform(libs.kotlin.bom))
  compileOnly(gradleApi())
  compileOnly(libs.agp)
  compileOnly(libs.detekt)
  compileOnly(libs.gradlePlugins.anvil)
  // compileOnly because we want to leave versioning to the consumers
  // Add gradle plugins for the slack project itself, separate from plugins. We do this so we can
  // de-dupe version
  // management between this plugin and the root build.gradle.kts file.
  compileOnly(libs.gradlePlugins.bugsnag)
  compileOnly(libs.gradlePlugins.compose)
  compileOnly(libs.gradlePlugins.dependencyAnalysis)
  compileOnly(libs.gradlePlugins.detekt)
  compileOnly(libs.gradlePlugins.doctor)
  compileOnly(libs.gradlePlugins.enterprise)
  compileOnly(libs.gradlePlugins.errorProne)
  compileOnly(libs.gradlePlugins.kgp)
  compileOnly(libs.gradlePlugins.ksp)
  compileOnly(libs.gradlePlugins.moshix)
  compileOnly(libs.gradlePlugins.napt)
  compileOnly(libs.gradlePlugins.nullaway)
  compileOnly(libs.gradlePlugins.redacted)
  compileOnly(libs.gradlePlugins.retry)
  compileOnly(libs.gradlePlugins.spotless)
  compileOnly(libs.gradlePlugins.sqldelight)
  compileOnly(libs.gradlePlugins.versions)
  compileOnly(libs.gradlePlugins.wire)
  compileOnly(libs.kotlin.reflect)

  testImplementation(platform(libs.coroutines.bom))
  testImplementation(libs.agp)
  testImplementation(libs.coroutines.test)
  testImplementation(libs.junit)
  testImplementation(libs.okio.fakefilesystem)
  testImplementation(libs.truth)
}
