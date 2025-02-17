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
package slack.unittest

import com.gradle.enterprise.gradleplugin.testretry.retry as geRetry
import kotlin.math.max
import kotlin.math.roundToInt
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.retry
import org.gradle.language.base.plugins.LifecycleBasePlugin
import slack.gradle.SlackProperties
import slack.gradle.ciUnitTestAndroidVariant
import slack.gradle.configureEach
import slack.gradle.isActionsCi
import slack.gradle.isCi
import slack.gradle.util.setDisallowChanges
import slack.gradle.util.synchronousEnvProperty

/**
 * This code creates a task named "ciUnitTest" in the project it is applied to, which depends on the
 * specific unit test task that we want to run on CI for the project.
 *
 * Instead of running "./gradlew test" on CI (which would run tests on every build variant on every
 * project), we prefer to run unit tests on only one build variant to save time.
 *
 * For an Android application or library, "ciUnitTest" will depend on "testInternalDebugUnitTest"
 * (if the "internal"/"external" product flavors exist), or "testDebugUnitTest". For a Java or
 * Kotlin library, it will depend on "test".
 *
 * For convenience, this also creates a task named `compileCiUnitTest` for just _building_ the
 * tests.
 *
 * Finally, this plugin creates a convenience task named "globalCiUnitTest" in the root project,
 * which depends on all the "ciUnitTest" tasks in the subprojects. This is the task that should be
 * run on CI.
 */
internal object UnitTests {
  private const val GLOBAL_CI_UNIT_TEST_TASK_NAME = "globalCiUnitTest"
  private const val CI_UNIT_TEST_TASK_NAME = "ciUnitTest"
  private const val COMPILE_CI_UNIT_TEST_NAME = "compileCiUnitTest"
  private const val LOG = "SlackUnitTests:"

  private fun maxForks(slackProperties: SlackProperties): Int {
    val multiplier = slackProperties.unitTestParallelismMultiplier
    return max((Runtime.getRuntime().availableProcessors() * multiplier).roundToInt(), 1)
  }

  fun configureRootProject(project: Project): TaskProvider<Task> =
    project.tasks.register(GLOBAL_CI_UNIT_TEST_TASK_NAME) {
      group = LifecycleBasePlugin.VERIFICATION_GROUP
      description = "Global lifecycle task to run all ciUnitTest tasks."
    }

  fun configureSubproject(
    project: Project,
    pluginId: String,
    slackProperties: SlackProperties,
    affectedProjects: Set<String>?,
    onProjectSkipped: (String, String) -> Unit,
  ) {
    // Projects can opt out of creating the task with this property.
    val enabled = slackProperties.ciUnitTestEnabled
    if (!enabled) {
      project.logger.debug("$LOG Skipping creation of \"$CI_UNIT_TEST_TASK_NAME\" task")
      return
    }

    if (
      slackProperties.ciUnitTestEnableKover && project.path != slackProperties.platformProjectPath
    ) {
      project.afterEvaluate {
        // Remove afterEvaluate
        // after https://github.com/Kotlin/kotlinx-kover/issues/362 is fixed
        project.pluginManager.apply("org.jetbrains.kotlinx.kover")
      }
    }

    val globalTask =
      if (affectedProjects == null || project.path in affectedProjects) {
        project.rootProject.tasks.named(GLOBAL_CI_UNIT_TEST_TASK_NAME)
      } else {
        val taskPath = "${project.path}:$CI_UNIT_TEST_TASK_NAME"
        onProjectSkipped(GLOBAL_CI_UNIT_TEST_TASK_NAME, taskPath)
        val log = "$LOG Skipping $taskPath because it is not affected."
        if (slackProperties.debug) {
          project.logger.lifecycle(log)
        } else {
          project.logger.debug(log)
        }
        null
      }

    when (pluginId) {
      "com.android.application",
      "com.android.library" -> {
        createAndroidCiUnitTestTask(project, globalTask)
      }
      else -> {
        // Standard JVM projects like kotlin-jvm, java-library, etc
        project.logger.debug("$LOG Creating CI unit test tasks")
        val ciUnitTest =
          project.tasks.register(CI_UNIT_TEST_TASK_NAME) {
            group = LifecycleBasePlugin.VERIFICATION_GROUP
            dependsOn("test")
          }
        globalTask?.configure { dependsOn(ciUnitTest) }
        project.tasks.register(COMPILE_CI_UNIT_TEST_NAME) {
          group = LifecycleBasePlugin.VERIFICATION_GROUP
          dependsOn("testClasses")
        }
      }
    }

    configureTestTasks(project, slackProperties)
  }

  private fun createAndroidCiUnitTestTask(project: Project, globalTask: TaskProvider<*>?) {
    val variant = project.ciUnitTestAndroidVariant()
    val variantUnitTestTaskName = "test${variant}UnitTest"
    val variantCompileUnitTestTaskName = "compile${variant}UnitTestSources"
    project.logger.debug("$LOG Creating CI unit test tasks for variant '$variant'")
    val ciUnitTest =
      project.tasks.register(CI_UNIT_TEST_TASK_NAME) {
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        // Even if the task isn't created yet, we can do this by name alone and it will resolve at
        // task configuration time.
        dependsOn(variantUnitTestTaskName)
      }
    globalTask?.configure { dependsOn(ciUnitTest) }
    project.tasks.register(COMPILE_CI_UNIT_TEST_NAME) {
      group = LifecycleBasePlugin.VERIFICATION_GROUP
      // Even if the task isn't created yet, we can do this by name alone and it will resolve at
      // task configuration time.
      dependsOn(variantCompileUnitTestTaskName)
    }
  }

  private fun configureTestTasks(project: Project, slackProperties: SlackProperties) {
    val isCi = project.isCi

    // Unit test task configuration
    project.tasks.configureEach<Test> {
      // Run unit tests in parallel if multiple CPUs are available. Use at most half the available
      // CPUs.
      maxParallelForks =
        maxForks(slackProperties).also { logger.debug("$LOG Setting maxParallelForks to $it") }

      // Denote flaky failures as <flakyFailure> instead of <failure> in JUnit test XML files
      reports.junitXml.mergeReruns.setDisallowChanges(true)

      /*
       * Much of the below is to improve memory management on CI
       * https://github.com/tinyspeck/slack-android-ng/issues/22005
       */

      // helps when tests leak memory
      setForkEvery(slackProperties.unitTestForkEvery)

      // Cap JVM args per test
      minHeapSize = "128m"
      maxHeapSize = "1g"

      jvmArgs(
        // region compile-testing args
        // TODO would be nice if we could apply this _only_ if compile-testing is on the test
        //  classpath
        // Required for Google compile-testing to work.
        // https://github.com/google/compile-testing/issues/222
        "--add-opens=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
        // endregion

        // region Robolectric args
        // Robolectric 4.9+ requires these --add-opens options.
        // https://github.com/robolectric/robolectric/issues/7456
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--add-opens=java.base/java.util=ALL-UNNAMED",
        // endregion

        "-Xss1m", // Stack size
      )

      // TODO would be nice to some day only conditionally apply robolectric args
      // Enable Robolectric's new NATIVE graphics mode.
      // https://github.com/robolectric/robolectric/releases/tag/robolectric-4.10-alpha-1
      systemProperty("robolectric.graphicsMode", "NATIVE")

      if (slackProperties.testVerboseLogging) {
        // Add additional logging on Jenkins to help debug hanging or OOM-ing unit tests.
        testLogging {
          showStandardStreams = true
          showStackTraces = true

          // Set options for log level LIFECYCLE
          events("started", "passed", "failed", "skipped")
          setExceptionFormat("short")

          // Setting this to 0 (the default is 2) will display the test executor that each test is
          // running on.
          displayGranularity = 0
        }

        val workspaceDir =
          when {
            project.isActionsCi -> project.synchronousEnvProperty("GITHUB_WORKSPACE")
            else -> project.rootProject.projectDir.absolutePath
          }
        jvmArgs(
          "-XX:+HeapDumpOnOutOfMemoryError", // Produce a heap dump when an OOM occurs
          "-XX:+CrashOnOutOfMemoryError", // Produce a crash report when an OOM occurs
          "-XX:+UseGCOverheadLimit",
          "-XX:GCHeapFreeLimit=10",
          "-XX:GCTimeLimit=20",
          "-XX:HeapDumpPath=$workspaceDir/fs_oom_err_pid<pid>.hprof",
          "-XX:ErrorFile=$workspaceDir/fs_oom_err_pid<pid>.log",
        )
      }
    }

    if (isCi) {
      if (slackProperties.testRetryPluginType == SlackProperties.TestRetryPluginType.RETRY_PLUGIN) {
        project.pluginManager.withPlugin("org.gradle.test-retry") {
          project.tasks.withType(Test::class.java).configureEach {
            retry {
              failOnPassedAfterRetry.setDisallowChanges(
                slackProperties.testRetryFailOnPassedAfterRetry
              )
              maxFailures.setDisallowChanges(slackProperties.testRetryMaxFailures)
              maxRetries.setDisallowChanges(slackProperties.testRetryMaxRetries)
            }
          }
        }
      } else {
        // TODO eventually expose if GE was enabled in settings via our own settings plugin?
        project.tasks.withType(Test::class.java).configureEach {
          geRetry {
            failOnPassedAfterRetry.setDisallowChanges(
              slackProperties.testRetryFailOnPassedAfterRetry
            )
            maxFailures.setDisallowChanges(slackProperties.testRetryMaxFailures)
            maxRetries.setDisallowChanges(slackProperties.testRetryMaxRetries)
          }
        }
      }
    }
  }
}
