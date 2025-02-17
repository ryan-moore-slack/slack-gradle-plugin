/*
 * Copyright (C) 2022 Slack Technologies, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
@file:Suppress("UnnecessaryAbstractClass")

package slack.gradle

import com.android.build.api.dsl.CommonExtension
import com.android.build.gradle.LibraryExtension
import com.squareup.anvil.plugin.AnvilExtension
import dev.zacsweers.moshix.ir.gradle.MoshiPluginExtension
import javax.inject.Inject
import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import slack.gradle.agp.PermissionAllowlistConfigurer
import slack.gradle.compose.configureComposeCompiler
import slack.gradle.dependencies.SlackDependencies
import slack.gradle.util.booleanProperty
import slack.gradle.util.setDisallowChanges

@DslMarker public annotation class SlackExtensionMarker

@SlackExtensionMarker
public abstract class SlackExtension
@Inject
constructor(
  objects: ObjectFactory,
  globalSlackProperties: SlackProperties,
  private val slackProperties: SlackProperties,
  versionCatalog: VersionCatalog
) {
  internal val androidHandler = objects.newInstance<AndroidHandler>(slackProperties)
  internal val featuresHandler =
    objects.newInstance<FeaturesHandler>(globalSlackProperties, slackProperties, versionCatalog)

  /**
   * This is weird! Due to the non-property nature of some AGP DSL features (e.g. buildFeatures and
   * composeOptions DSLs), we can't lazily chain their values to our own extension's properties.
   * Because of this, we lazily set this instance from [StandardProjectConfigurations] during
   * Android extension evaluation and then make calls to enable them _directly_ set the values on
   * this instance. Ideally we could eventually remove this if/when AGP finally makes these
   * properties lazy.
   */
  private var androidExtension: CommonExtension<*, *, *, *, *>? = null
    set(value) {
      field = value
      androidHandler.setAndroidExtension(value)
      featuresHandler.setAndroidExtension(value)
    }

  internal fun setAndroidExtension(androidExtension: CommonExtension<*, *, *, *, *>) {
    this.androidExtension = androidExtension
  }

  public fun android(action: Action<AndroidHandler>) {
    action.execute(androidHandler)
  }

  public fun features(action: Action<FeaturesHandler>) {
    action.execute(featuresHandler)
  }

  internal fun applyTo(project: Project) {
    val logVerbose = slackProperties.slackExtensionVerbose
    // Dirty but necessary since the extension isn't configured yet when we call this
    project.afterEvaluate {
      featuresHandler.applyTo(project)

      var kaptRequired = false
      var naptRequired = false
      val moshiCodegenEnabled = featuresHandler.moshiHandler.moshiCodegen.getOrElse(false)
      val moshiSealedCodegenEnabled = featuresHandler.moshiHandler.sealedCodegen.getOrElse(false)
      val allowKsp = slackProperties.allowKsp
      val allowMoshiIr = slackProperties.allowMoshiIr
      val allowNapt = slackProperties.allowNapt
      val allowDaggerKsp = slackProperties.allowNapt
      val allowAnvilKsp = allowDaggerKsp && slackProperties.allowAnvilKsp

      /** Marks this project as needing kapt code gen. */
      fun markKaptNeeded(source: String) {
        if (allowNapt) {
          naptRequired = true
          // Apply napt for them
          pluginManager.apply("com.sergei-lapin.napt")
        } else {
          kaptRequired = true
          // Apply kapt for them
          pluginManager.apply("org.jetbrains.kotlin.kapt")
        }
        if (logVerbose) {
          logger.lifecycle(
            """
            [kapt/napt Config]
            project = $path
            source = $source
            """
              .trimIndent()
          )
        }
      }

      /** Marks this project as needing KSP code gen. */
      fun markKspNeeded(source: String) {
        if (logVerbose) {
          logger.lifecycle(
            """
            [KSP Config]
            project = $path
            KSP source = $source
            """
              .trimIndent()
          )
        }
        if (!isUsingKsp) {
          // Apply KSP for them
          pluginManager.apply("com.google.devtools.ksp")
        }
      }

      /** Marks this project as needing the Moshi Gradle Plugin. */
      fun markMoshiGradleNeeded(source: String, enableSealed: Boolean) {
        if (logVerbose) {
          logger.lifecycle(
            """
            [Moshi Gradle Config]
            project = $path
            source = $source
            """
              .trimIndent()
          )
        }
        if (!isUsingMoshiGradle) {
          // Apply Moshi gradle for them
          pluginManager.apply("dev.zacsweers.moshix")
        }
        if (enableSealed) {
          configure<MoshiPluginExtension> { this.enableSealed.setDisallowChanges(true) }
        }
        if (project.booleanProperty("moshix.generateProguardRules", defaultValue = true)) {
          markKspNeeded("Moshi IR code gen")
        }
      }

      fun aptConfiguration(): String {
        return if (isKotlin && !naptRequired) {
          "kapt"
        } else {
          "annotationProcessor"
        }
      }

      // Dagger is configured first. If Dagger's compilers are present,
      // everything else needs to also use kapt!
      val daggerConfig = featuresHandler.daggerHandler.computeConfig()
      if (daggerConfig != null) {
        dependencies.add("implementation", SlackDependencies.Dagger.dagger)
        dependencies.add("implementation", SlackDependencies.javaxInject)

        if (daggerConfig.runtimeOnly) {
          dependencies.add("compileOnly", SlackDependencies.Anvil.annotations)
        }

        if (logVerbose) {
          logger.lifecycle(
            """
            [Dagger Config]
            project = $path
            daggerConfig = $daggerConfig
            """
              .trimIndent()
          )
        }

        if (daggerConfig.enableAnvil) {
          pluginManager.apply("com.squareup.anvil")
          configure<AnvilExtension> {
            generateDaggerFactories.setDisallowChanges(daggerConfig.anvilFactories)
            generateDaggerFactoriesOnly.setDisallowChanges(daggerConfig.anvilFactoriesOnly)
          }

          val runtimeProjects =
            slackProperties.anvilRuntimeProjects?.splitToSequence(";")?.toSet().orEmpty()

          for (runtimeProject in runtimeProjects) {
            dependencies.add("implementation", project(runtimeProject))
          }

          val generatorProjects =
            buildSet<Any> {
              addAll(
                slackProperties.anvilGeneratorProjects
                  ?.splitToSequence(";")
                  ?.map(::project)
                  .orEmpty()
              )
              addAll(featuresHandler.daggerHandler.anvilGenerators)
            }
          for (generator in generatorProjects) {
            dependencies.add("anvil", generator)
          }
        }

        if (!daggerConfig.runtimeOnly && daggerConfig.useDaggerCompiler) {
          if (allowDaggerKsp && (!daggerConfig.enableAnvil || allowAnvilKsp)) {
            markKspNeeded("Dagger compiler")
            dependencies.add("ksp", SlackDependencies.Dagger.compiler)
          } else {
            markKaptNeeded("Dagger compiler")
            dependencies.add(aptConfiguration(), SlackDependencies.Dagger.compiler)
          }
        }
      }

      if (featuresHandler.circuitHandler.codegen.getOrElse(false)) {
        markKspNeeded("Circuit")
        dependencies.add("ksp", "com.slack.circuit:circuit-codegen")
        dependencies.add("compileOnly", "com.slack.circuit:circuit-codegen-annotations")
      }

      if (featuresHandler.autoService.getOrElse(false)) {
        if (allowKsp) {
          markKspNeeded("AutoService")
          dependencies.add("implementation", SlackDependencies.Auto.Service.annotations)
          dependencies.add("ksp", SlackDependencies.Auto.Service.ksp)
        } else {
          markKaptNeeded("AutoService")
          dependencies.add("compileOnly", SlackDependencies.Auto.Service.annotations)
          dependencies.add(aptConfiguration(), SlackDependencies.Auto.Service.autoservice)
        }
      }

      if (featuresHandler.redacted.getOrElse(false)) {
        pluginManager.apply("dev.zacsweers.redacted")
      }

      if (featuresHandler.moshiHandler.moshi.getOrElse(false)) {
        dependencies.add("implementation", SlackDependencies.Moshi.moshi)
        if (moshiCodegenEnabled) {
          if (allowMoshiIr) {
            markMoshiGradleNeeded("Moshi code gen", false)
          } else if (allowKsp) {
            markKspNeeded("Moshi code gen")
            dependencies.add("ksp", SlackDependencies.Moshi.codeGen)
          } else {
            markKaptNeeded("Moshi code gen")
            dependencies.add(aptConfiguration(), SlackDependencies.Moshi.codeGen)
          }
        }
        if (featuresHandler.moshiHandler.moshiAdapters.getOrElse(false)) {
          dependencies.add("implementation", SlackDependencies.Moshi.adapters)
        }
        if (featuresHandler.moshiHandler.moshiKotlinReflect.getOrElse(false)) {
          dependencies.add("implementation", SlackDependencies.Moshi.kotlinReflect)
        }
        if (featuresHandler.moshiHandler.moshixAdapters.getOrElse(false)) {
          dependencies.add("implementation", SlackDependencies.Moshi.MoshiX.adapters)
        }
        if (featuresHandler.moshiHandler.moshixMetadataReflect.getOrElse(false)) {
          dependencies.add("implementation", SlackDependencies.Moshi.MoshiX.metadataReflect)
        }
        if (featuresHandler.moshiHandler.lazyAdapters.getOrElse(false)) {
          dependencies.add("implementation", SlackDependencies.Moshi.lazyAdapters)
        }
        if (featuresHandler.moshiHandler.sealed.getOrElse(false)) {
          dependencies.add("implementation", SlackDependencies.Moshi.MoshiX.Sealed.runtime)
          if (moshiSealedCodegenEnabled) {
            if (allowMoshiIr) {
              markMoshiGradleNeeded("Moshi sealed codegen", enableSealed = true)
            } else if (allowKsp) {
              markKspNeeded("Moshi sealed codegen")
              dependencies.add("ksp", SlackDependencies.Moshi.MoshiX.Sealed.codegen)
            } else {
              markKaptNeeded("Moshi sealed codegen")
              dependencies.add(aptConfiguration(), SlackDependencies.Moshi.MoshiX.Sealed.codegen)
            }
          }
          if (featuresHandler.moshiHandler.sealedReflect.getOrElse(false)) {
            dependencies.add("implementation", SlackDependencies.Moshi.MoshiX.Sealed.reflect)
          }
          if (featuresHandler.moshiHandler.sealedMetadataReflect.getOrElse(false)) {
            dependencies.add(
              "implementation",
              SlackDependencies.Moshi.MoshiX.Sealed.metadataReflect
            )
          }
        }
      }

      // At the very end we check if kapt is enabled and disable anvil component merging if needed
      // https://github.com/square/anvil#incremental-kotlin-compilation-breaks-compiler-plugins
      if (
        kaptRequired &&
          daggerConfig?.enableAnvil == true &&
          !daggerConfig.alwaysEnableAnvilComponentMerging
      ) {
        configure<AnvilExtension> { disableComponentMerging.setDisallowChanges(true) }
      }
    }
  }
}

@SlackExtensionMarker
public abstract class FeaturesHandler
@Inject
constructor(
  objects: ObjectFactory,
  globalSlackProperties: SlackProperties,
  private val slackProperties: SlackProperties,
  versionCatalog: VersionCatalog
) {
  // Dagger features
  internal val daggerHandler = objects.newInstance<DaggerHandler>()

  // Circuit features
  internal val circuitHandler = objects.newInstance<CircuitHandler>()

  /** Enables AutoService on this project. */
  internal abstract val autoService: Property<Boolean>

  /** Enables redacted-compiler-plugin on this project. */
  internal abstract val redacted: Property<Boolean>

  // Moshi
  internal val moshiHandler = objects.newInstance<MoshiHandler>()

  // Compose features
  internal val composeHandler =
    objects.newInstance<ComposeHandler>(globalSlackProperties, slackProperties, versionCatalog)

  /** @see [SlackExtension.androidExtension] */
  private var androidExtension: CommonExtension<*, *, *, *, *>? = null
    set(value) {
      field = value
      composeHandler.setAndroidExtension(value)
    }

  internal fun setAndroidExtension(androidExtension: CommonExtension<*, *, *, *, *>?) {
    this.androidExtension = androidExtension
  }

  /**
   * Configures Circuit on this project.
   *
   * @param codegen configures code gen on this project, including KSP setup and annotations. True
   *   by default.
   * @param runtime configures the circuit-runtime dependencies, including screen/ui/presenter. True
   *   by default.
   * @param commonBundle configures the project-common dependencies, defined by the circuit-common
   *   bundle ID in `libs.versions.toml`. True by default.
   * @param foundation configures the circuit-foundation dependency, false by default.
   * @param action optional configuration block for further Circuit config, such as CircuitX.
   */
  public fun circuit(
    codegen: Boolean = true,
    runtime: Boolean = true,
    commonBundle: Boolean = true,
    foundation: Boolean = false,
    action: Action<CircuitHandler> = Action {},
  ) {
    circuitHandler.codegen.set(codegen)
    circuitHandler.runtime.set(runtime)
    circuitHandler.commonBundle.set(commonBundle)
    circuitHandler.foundation.set(foundation)
    action.execute(circuitHandler)
  }

  /**
   * Enables dagger for this project.
   *
   * @param action optional block for extra configuration, such as anvil generators or android.
   */
  public fun dagger(action: Action<DaggerHandler>? = null) {
    daggerHandler.enabled.setDisallowChanges(true)
    action?.execute(daggerHandler)
  }

  /**
   * Enables dagger for this project.
   *
   * @param enableComponents enables dagger components in this project, which in turn imposes use of
   *   the dagger compiler (slower!)
   * @param projectHasJavaInjections indicates if this project has injected _Java_ files. This means
   *   any Java file with `@Inject` or `@AssistedInject`. This imposes use of the dagger compiler
   *   (slower!) because Anvil only processes Kotlin files.
   * @param action optional block for extra configuration, such as anvil generators or android.
   */
  @DelicateSlackPluginApi
  public fun dagger(
    enableComponents: Boolean = false,
    projectHasJavaInjections: Boolean = false,
    action: Action<DaggerHandler>? = null
  ) {
    check(enableComponents || projectHasJavaInjections) {
      "This function should not be called with both enableComponents and projectHasJavaInjections set to false. Either remove these parameters or call a more appropriate non-delicate dagger() overload."
    }
    daggerHandler.enabled.setDisallowChanges(true)
    daggerHandler.useDaggerCompiler.setDisallowChanges(true)
    action?.execute(daggerHandler)
  }

  /** Adds dagger's runtime as dependencies to this but runs no code generation. */
  public fun daggerRuntimeOnly() {
    daggerHandler.enabled.setDisallowChanges(true)
    daggerHandler.runtimeOnly.setDisallowChanges(true)
  }

  /**
   * Enables Moshi for this project.
   *
   * @param codegen Enables codegen.
   * @param adapters Enables moshi-adapters.
   * @param kotlinReflect Enables kotlin-reflect-based support. Should only be used in unit tests or
   *   CLIs!
   * @param action Optional extra configuration for other Moshi libraries, such as MoshiX.
   */
  public fun moshi(
    codegen: Boolean,
    adapters: Boolean = false,
    kotlinReflect: Boolean = false,
    action: Action<MoshiHandler> = Action {}
  ) {
    action.execute(moshiHandler)
    moshiHandler.moshi.setDisallowChanges(true)
    moshiHandler.moshiAdapters.setDisallowChanges(adapters)
    moshiHandler.moshiCodegen.setDisallowChanges(codegen)
    moshiHandler.moshiKotlinReflect.setDisallowChanges(kotlinReflect)
  }

  /** Enables AutoService on this project. */
  public fun autoService() {
    autoService.setDisallowChanges(true)
  }

  /** Enables redacted-compiler-plugin on this project. */
  public fun redacted() {
    redacted.setDisallowChanges(true)
  }

  /**
   * Enables Compose for this project and applies any version catalog bundle dependencies defined by
   * [SlackProperties.defaultComposeAndroidBundleAlias].
   */
  public fun compose(multiplatform: Boolean = false) {
    compose(multiplatform) {
      // No further configuration right now
    }
  }

  private fun compose(multiplatform: Boolean, action: Action<ComposeHandler>) {
    composeHandler.enable(multiplatform = multiplatform)
    action.execute(composeHandler)
  }

  internal fun applyTo(project: Project) {
    composeHandler.applyTo(project, slackProperties)
    circuitHandler.applyTo(project, slackProperties)
  }
}

@SlackExtensionMarker
public abstract class MoshiHandler {
  internal abstract val moshi: Property<Boolean>
  internal abstract val moshiAdapters: Property<Boolean>
  internal abstract val moshiCodegen: Property<Boolean>
  internal abstract val moshiKotlinReflect: Property<Boolean>

  internal abstract val moshixAdapters: Property<Boolean>
  internal abstract val moshixMetadataReflect: Property<Boolean>

  internal abstract val sealed: Property<Boolean>
  internal abstract val sealedCodegen: Property<Boolean>
  internal abstract val sealedReflect: Property<Boolean>
  internal abstract val sealedMetadataReflect: Property<Boolean>

  internal abstract val lazyAdapters: Property<Boolean>

  /**
   * Enables MoshiX on this project.
   *
   * @param adapters Enables moshix-adapters.
   * @param metadataReflect Enables metadata-reflect. Should only be used in unit tests or CLIs!
   */
  public fun moshix(
    adapters: Boolean,
    metadataReflect: Boolean = false,
  ) {
    moshixAdapters.setDisallowChanges(adapters)
    moshixMetadataReflect.setDisallowChanges(metadataReflect)
  }

  /**
   * Enables MoshiX-sealed on this project. This is used for polymorphic types.
   *
   * @param codegen Enables codegen.
   * @param kotlinReflect Enables kotlin-reflect-based support. Should only be used in unit tests or
   *   CLIs!
   * @param metadataReflect Enables metadata-based reflection support. Should only be used in unit
   *   tests or CLIs!
   */
  public fun sealed(
    codegen: Boolean,
    kotlinReflect: Boolean = false,
    metadataReflect: Boolean = false
  ) {
    sealed.setDisallowChanges(true)
    sealedCodegen.setDisallowChanges(codegen)
    sealedReflect.setDisallowChanges(kotlinReflect)
    sealedMetadataReflect.setDisallowChanges(metadataReflect)
  }

  /** Enables [moshi-lazy-adapters](https://github.com/serj-lotutovici/moshi-lazy-adapters). */
  public fun lazyAdapters() {
    lazyAdapters.setDisallowChanges(true)
  }
}

/** DSL for configuring Circuit. */
@SlackExtensionMarker
public abstract class CircuitHandler @Inject constructor(objects: ObjectFactory) {
  /** Sets up circuit code gen, includes annotations and KSP setup. */
  public val codegen: Property<Boolean> = objects.property<Boolean>().convention(false)
  /** Includes the circuit-runtime dep (including screen, ui, presenter). */
  public val runtime: Property<Boolean> = objects.property<Boolean>().convention(false)
  /** Includes the circuit-foundation dep. */
  public val foundation: Property<Boolean> = objects.property<Boolean>().convention(false)
  /**
   * When enabled, includes a common bundle as defined by the `circuit-common` bundle ID in
   * `libs.versions.toml`.
   */
  public val commonBundle: Property<Boolean> = objects.property<Boolean>().convention(false)
  public val circuitx: CircuitXHandler = objects.newInstance<CircuitXHandler>()

  /** DSL entrypoint for CircuitX dependencies. */
  public fun circuitx(action: Action<CircuitXHandler>) {
    action.execute(circuitx)
  }

  internal fun applyTo(project: Project, slackProperties: SlackProperties) {
    if (runtime.getOrElse(false)) {
      project.dependencies.add("implementation", "com.slack.circuit:circuit-runtime")
      project.dependencies.add("implementation", "com.slack.circuit:circuit-runtime-presenter")
      project.dependencies.add("implementation", "com.slack.circuit:circuit-runtime-ui")
      project.dependencies.add("testImplementation", "com.slack.circuit:circuit-test")
    }
    if (foundation.getOrElse(false)) {
      project.dependencies.add("implementation", "com.slack.circuit:circuit-foundation")
    }
    if (commonBundle.getOrElse(false)) {
      slackProperties.versions.bundles.commonCircuit.ifPresent {
        project.dependencies.add("implementation", it)
      }
    }

    circuitx.applyTo(project)
  }

  @SlackExtensionMarker
  public abstract class CircuitXHandler @Inject constructor(objects: ObjectFactory) {
    /** Correpsonds to the circuitx-android artifact. */
    public val android: Property<Boolean> = objects.property<Boolean>().convention(false)
    /** Correpsonds to the circuitx-gesture-navigation artifact. */
    public val gestureNav: Property<Boolean> = objects.property<Boolean>().convention(false)
    /** Correpsonds to the circuitx-overlays artifact. */
    public val overlays: Property<Boolean> = objects.property<Boolean>().convention(false)

    internal fun applyTo(project: Project) {
      if (android.getOrElse(false)) {
        project.dependencies.add("implementation", "com.slack.circuit:circuitx-android")
      }
      if (gestureNav.getOrElse(false)) {
        project.dependencies.add("implementation", "com.slack.circuit:circuitx-gesture-navigation")
      }
      if (overlays.getOrElse(false)) {
        project.dependencies.add("implementation", "com.slack.circuit:circuitx-overlays")
      }
    }
  }
}

@SlackExtensionMarker
public abstract class DaggerHandler @Inject constructor(objects: ObjectFactory) {
  internal val enabled: Property<Boolean> = objects.property<Boolean>().convention(false)
  internal val useDaggerCompiler: Property<Boolean> = objects.property<Boolean>().convention(false)
  internal val disableAnvil: Property<Boolean> = objects.property<Boolean>().convention(false)
  internal val runtimeOnly: Property<Boolean> = objects.property<Boolean>().convention(false)
  internal val alwaysEnableAnvilComponentMerging: Property<Boolean> =
    objects.property<Boolean>().convention(false)
  internal val anvilGenerators = objects.domainObjectSet<Any>()

  /**
   * Dependencies for Anvil generators that should be added. These should be in the same form as
   * they would be added to regular project dependencies.
   *
   * ```
   * slack {
   *   features {
   *     dagger(...) {
   *       anvilGenerators(projects.libraries.foundation.anvil.injection.compiler)
   *     }
   *   }
   * }
   * ```
   */
  public fun anvilGenerators(vararg generators: ProjectDependency) {
    anvilGenerators.addAll(generators)
  }

  /**
   * By default, if kapt is enabled we will disable anvil component merging as an optimization as it
   * incurs a cost of disabling incremental kapt stubs. If we need it though (aka this is running in
   * app-di or another project that actually has components), this can be always enabled as needed.
   */
  @DelicateSlackPluginApi
  public fun alwaysEnableAnvilComponentMerging() {
    alwaysEnableAnvilComponentMerging.setDisallowChanges(true)
  }

  /**
   * Disables anvil. Should only be used for cases where anvil is explicitly not wanted, such as
   * using Dagger KSP while Anvil doesn't support it.
   */
  @DelicateSlackPluginApi
  public fun disableAnvil() {
    disableAnvil.setDisallowChanges(true)
  }

  internal fun computeConfig(): DaggerConfig? {
    if (!enabled.get()) return null
    val runtimeOnly = runtimeOnly.get()
    val enableAnvil = !runtimeOnly && !disableAnvil.get()
    var anvilFactories = true
    var anvilFactoriesOnly = false
    val useDaggerCompiler = useDaggerCompiler.get()
    val alwaysEnableAnvilComponentMerging = !runtimeOnly && alwaysEnableAnvilComponentMerging.get()

    if (useDaggerCompiler) {
      anvilFactories = false
      anvilFactoriesOnly = false
    }

    return DaggerConfig(
      runtimeOnly,
      enableAnvil,
      anvilFactories,
      anvilFactoriesOnly,
      useDaggerCompiler,
      alwaysEnableAnvilComponentMerging,
    )
  }

  internal data class DaggerConfig(
    val runtimeOnly: Boolean,
    val enableAnvil: Boolean,
    var anvilFactories: Boolean,
    var anvilFactoriesOnly: Boolean,
    val useDaggerCompiler: Boolean,
    val alwaysEnableAnvilComponentMerging: Boolean,
  )
}

@SlackExtensionMarker
public abstract class ComposeHandler
@Inject
constructor(
  objects: ObjectFactory,
  globalSlackProperties: SlackProperties,
  private val slackProperties: SlackProperties,
  versionCatalog: VersionCatalog
) {

  private val composeBundleAlias =
    globalSlackProperties.defaultComposeAndroidBundleAlias?.let { alias ->
      versionCatalog.findBundle(alias).orElse(null)
    }
  private val composeCompilerVersion by lazy {
    slackProperties.versions.composeCompiler
      ?: error("Missing `compose-compiler` version in catalog")
  }
  internal val enabled = objects.property<Boolean>().convention(false)
  internal val multiplatform = objects.property<Boolean>().convention(false)

  /** @see [AndroidHandler.androidExtension] */
  private var androidExtension: CommonExtension<*, *, *, *, *>? = null

  internal fun setAndroidExtension(androidExtension: CommonExtension<*, *, *, *, *>?) {
    this.androidExtension = androidExtension
  }

  internal fun enable(multiplatform: Boolean) {
    enabled.setDisallowChanges(true)
    this.multiplatform.setDisallowChanges(multiplatform)
    if (!multiplatform) {
      val extension =
        checkNotNull(androidExtension) {
          "ComposeHandler must be configured with an Android extension before it can be enabled. Did you apply the Android gradle plugin?"
        }
      extension.apply {
        buildFeatures { compose = true }
        composeOptions {
          kotlinCompilerExtensionVersion = composeCompilerVersion
          // Disable live literals by default
          useLiveLiterals = slackProperties.composeEnableLiveLiterals
        }
      }
    }
  }

  internal fun applyTo(project: Project, slackProperties: SlackProperties) {
    if (enabled.get()) {
      val isMultiplatform = multiplatform.get()
      if (isMultiplatform) {
        project.pluginManager.apply("org.jetbrains.compose")
      } else {
        composeBundleAlias?.let { project.dependencies.add("implementation", it) }
      }
      project.configureComposeCompiler(slackProperties, isMultiplatform)
    }
  }
}

@SlackExtensionMarker
public abstract class AndroidHandler
@Inject
constructor(
  objects: ObjectFactory,
  private val slackProperties: SlackProperties,
) {
  internal val libraryHandler = objects.newInstance<SlackAndroidLibraryExtension>()
  internal val appHandler = objects.newInstance<SlackAndroidAppExtension>()

  @Suppress("MemberVisibilityCanBePrivate")
  internal val featuresHandler = objects.newInstance<AndroidFeaturesHandler>()

  /** @see [SlackExtension.androidExtension] */
  private var androidExtension: CommonExtension<*, *, *, *, *>? = null
    set(value) {
      field = value
      featuresHandler.setAndroidExtension(value)
    }

  internal fun setAndroidExtension(androidExtension: CommonExtension<*, *, *, *, *>?) {
    this.androidExtension = androidExtension
  }

  public fun features(action: Action<AndroidFeaturesHandler>) {
    action.execute(featuresHandler)
  }

  public fun library(action: Action<SlackAndroidLibraryExtension>) {
    action.execute(libraryHandler)
  }

  public fun app(action: Action<SlackAndroidAppExtension>) {
    action.execute(appHandler)
  }

  internal fun applyTo(project: Project) {
    // Dirty but necessary since the extension isn't configured yet when we call this
    project.afterEvaluate {
      if (featuresHandler.robolectric.getOrElse(false)) {
        checkNotNull(slackProperties.versions.robolectric) {
          "Robolectric support requested in ${project.path} but no version was specified in the version catalog."
        }
        project.dependencies.apply {
          // For projects using robolectric, we want to make sure they include robolectric-core to
          // ensure robolectric uses our custom dependency resolver and config (which just need
          // to be on the classpath).
          add("testImplementation", SlackDependencies.Testing.Robolectric.annotations)
          add("testImplementation", SlackDependencies.Testing.Robolectric.robolectric)
          add("testImplementation", slackProperties.robolectricCoreProject)
        }
      }
    }
  }
}

@SlackExtensionMarker
public abstract class AndroidFeaturesHandler @Inject constructor() {
  internal abstract val androidTest: Property<Boolean>
  internal abstract val androidTestExcludeFromFladle: Property<Boolean>
  internal abstract val androidTestAllowedVariants: SetProperty<String>
  internal abstract val robolectric: Property<Boolean>

  /** @see [AndroidHandler.androidExtension] */
  private var androidExtension: CommonExtension<*, *, *, *, *>? = null

  internal fun setAndroidExtension(androidExtension: CommonExtension<*, *, *, *, *>?) {
    this.androidExtension = androidExtension
  }

  /**
   * Enables android instrumentation tests for this project.
   *
   * @param excludeFromFladle If true, the test will be excluded from Flank/Fladle tests.
   * @param allowedVariants If set, the allowed variants to enable android tests for.
   */
  public fun androidTest(
    excludeFromFladle: Boolean = false,
    allowedVariants: Iterable<String>? = null
  ) {
    androidTest.setDisallowChanges(true)
    androidTestExcludeFromFladle.setDisallowChanges(excludeFromFladle)
    androidTestAllowedVariants.setDisallowChanges(allowedVariants)
  }

  /** Enables robolectric for this project. */
  // In the future, we may want to add an enum for picking which shadows/artifacts
  public fun robolectric() {
    // Required for Robolectric to work.
    androidExtension!!.testOptions.unitTests.isIncludeAndroidResources = true
    robolectric.setDisallowChanges(true)
  }

  /**
   * **LIBRARIES ONLY**
   *
   * Enables android resources in this library and enforces use of the given [prefix] for all
   * resources.
   */
  public fun resources(prefix: String) {
    val libraryExtension =
      androidExtension as? LibraryExtension
        ?: error("slack.android.features.resources() is only applicable in libraries!")
    libraryExtension.resourcePrefix = prefix
    libraryExtension.buildFeatures { androidResources = true }
  }
}

@SlackExtensionMarker
public abstract class SlackAndroidLibraryExtension {
  // Left as a toe-hold for the future
}

@SlackExtensionMarker
public abstract class SlackAndroidAppExtension {
  internal var allowlistAction: Action<PermissionAllowlistConfigurer>? = null

  /**
   * Configures a permissions allowlist on a per-variant basis with a VariantFilter-esque API.
   *
   * Example:
   * ```
   * slack {
   *   permissionAllowlist {
   *     if (buildType.name == "release") {
   *       setAllowlistFile(file('path/to/allowlist.txt'))
   *     }
   *   }
   * }
   * ```
   */
  public fun permissionAllowlist(factory: Action<PermissionAllowlistConfigurer>) {
    allowlistAction = factory
  }
}
