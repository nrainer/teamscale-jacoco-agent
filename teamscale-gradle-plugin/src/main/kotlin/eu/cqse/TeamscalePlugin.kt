package eu.cqse

import eu.cqse.config.TeamscalePluginExtension
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.internal.tasks.testing.junitplatform.JUnitPlatformTestFramework
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.tasks.testing.Test
import org.gradle.util.GradleVersion

/**
 * Root entry point for the Teamscale plugin.
 *
 * The plugin applies the Java plugin and a root extension named teamscale.
 * Each Test task configured in the project the plugin creates a new task suffixed with {@value #impactedTestsSuffix}
 * that executes the same set of tests, but additionally collects testwise coverage and executes only impacted tests.
 * Furthermore all reports configured are uploaded to Teamscale after the tests have been executed.
 *
 * The plugin needs a gradle version of 4.6 or higher. */
open class TeamscalePlugin : Plugin<Project> {

    companion object {

        /** The name of the extension used to configure the plugin. */
        const val teamscaleExtensionName = "teamscale"

        /** The name of the configuration that holds the impacted test executor and its dependencies. */
        const val impactedTestExecutorConfiguration = "impactedTestsExecutor"

        /** The suffix that gets appended to the name of the ImpactedTestsExecutorTask. */
        private const val impactedTestsSuffix = "Impacted"
    }

    /** The version of the teamscale gradle plugin and impacted-tests-executor.  */
    private var pluginVersion = BuildVersion.buildVersion

    /** Applies the teamscale plugin against the given project.  */
    override fun apply(project: Project) {
        project.logger.info("Applying teamscale plugin $pluginVersion to ${project.name}")
        project.plugins.apply(JavaPlugin::class.java)

        project.extensions.add(teamscaleExtensionName, TeamscalePluginExtension::class.java)

        if (GradleVersion.current() < GradleVersion.version("4.6")) {
            throw GradleException("The teamscale plugin requires Gradle version 4.6 or higher")
        }

        project.repositories.maven {
            it.setUrl("https://share.cqse.eu/public/maven/")
        }

        // Add impacted tests executor to a custom configuration that will later be used to
        // create the classpath for the ImpactedTestsExecutorTask created by this plugin.
        project.configurations.maybeCreate(impactedTestExecutorConfiguration)
            .defaultDependencies { dependencies ->
                dependencies.add(project.dependencies.create("eu.cqse:impacted-tests-executor:$pluginVersion"))
            }

        // Add the teamscale extension also to all test tasks
        project.tasks.withType(Test::class.java) { gradleTestTask ->
            gradleTestTask.extensions.create(teamscaleExtensionName, TeamscalePluginExtension::class.java)

            // Create the Impacted task when the Test task is registered to allow client-side modifications of the classpath
            project.tasks.create("${gradleTestTask.name}$impactedTestsSuffix", ImpactedTestsExecutorTask::class.java)
        }

        project.afterEvaluate {
            project.tasks.withType(Test::class.java) { gradleTestTask ->
                if (gradleTestTask.testFramework !is JUnitPlatformTestFramework) {
                    return@withType
                }
                val root = project.extensions.getByType(TeamscalePluginExtension::class.java)
                val task = gradleTestTask.extensions.getByType(TeamscalePluginExtension::class.java)
                val config = TeamscalePluginExtension.merge(root, task)
                val impactedTestsExecutorTask =
                    project.tasks.getByName("${gradleTestTask.name}$impactedTestsSuffix") as ImpactedTestsExecutorTask
                impactedTestsExecutorTask.onlyIf { config.testImpactMode ?: false }
                if (!config.validate(project, gradleTestTask.name)) {
                    return@withType
                }
                configureTestwiseCoverageCollectingTestWrapperTask(
                    project,
                    gradleTestTask,
                    config,
                    impactedTestsExecutorTask
                )
            }
        }
    }

    /** Configures the given impacted test executor. */
    private fun configureTestwiseCoverageCollectingTestWrapperTask(
        project: Project,
        gradleTestTask: Test,
        config: TeamscalePluginExtension,
        impactedTestsExecutorTask: ImpactedTestsExecutorTask
    ) {
        project.logger.info("Configuring impacted tests executor task for ${project.name}:${gradleTestTask.name}")

        impactedTestsExecutorTask.apply {
            testTask = gradleTestTask
            configuration = config
            baselineCommit = config.commit.getCommitDescriptor().commitBefore()
            endCommit = config.commit.getCommitDescriptor()
            // Copy dependencies from gradle test task
            dependsOn(gradleTestTask.dependsOn)
            dependsOn.add(project.configurations.getByName(impactedTestExecutorConfiguration))
            dependsOn.add(project.sourceSets.getByName("test").runtimeClasspath)
        }

        val teamscaleUploadTask = project.rootProject.tasks
            .maybeCreate("${gradleTestTask.name}ReportUpload", TeamscaleUploadTask::class.java)
        impactedTestsExecutorTask.finalizedBy(teamscaleUploadTask)

        teamscaleUploadTask.apply {
            server = config.server
            commitDescriptor = config.commit.getCommitDescriptor()

            if (config.report.testwiseCoverage.upload == true) {
                reports.add(config.report.testwiseCoverage.getReport(project, gradleTestTask))
            }
            if (config.report.jUnit.upload == true) {
                reports.add(config.report.jUnit.getReport(project, gradleTestTask))
            }
        }
    }
}
