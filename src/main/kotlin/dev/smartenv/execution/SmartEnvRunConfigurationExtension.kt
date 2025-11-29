package dev.smartenv.execution

import com.intellij.execution.RunConfigurationExtension
import com.intellij.execution.configurations.JavaParameters
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Condition
import com.intellij.openapi.wm.ToolWindowManager
import dev.smartenv.engine.SmartEnvResolutionResult
import dev.smartenv.engine.SmartEnvResolver
import dev.smartenv.logging.SmartEnvLogEntry
import dev.smartenv.logging.SmartEnvLogsService
import dev.smartenv.services.SmartEnvProfile
import dev.smartenv.services.SmartEnvProjectService
import dev.smartenv.services.findProfileById
import java.time.LocalDateTime

private val LOG = Logger.getInstance(SmartEnvRunConfigurationExtension::class.java)

class SmartEnvRunConfigurationExtension : RunConfigurationExtension() {
    private val resolver = SmartEnvResolver()
    private val notificationGroupId = "SmartEnv Notifications"

    override fun isApplicableFor(configuration: RunConfigurationBase<*>): Boolean = true

    override fun isEnabledFor(configuration: RunConfigurationBase<*>, runnerSettings: RunnerSettings?): Boolean {
        val service = configuration.project.getService(SmartEnvProjectService::class.java) ?: return false
        return service.getState().enabled
    }

    override fun <T : RunConfigurationBase<*>> updateJavaParameters(
        configuration: T,
        javaParameters: JavaParameters,
        runnerSettings: RunnerSettings?
    ) {
        injectEnvironment(configuration, javaParameters.env)
    }

    private fun injectEnvironment(configuration: RunConfigurationBase<*>, env: MutableMap<String, String>) {
        val project = configuration.project
        val service = project.getService(SmartEnvProjectService::class.java)
        if (service == null || !service.getState().enabled) {
            LOG.debug("SmartEnv disabled or missing service; skipping injection.")
            return
        }

        val state = service.getState()
        val profile = state.findProfileById(state.activeProfileId)
        if (profile == null) {
            LOG.debug("SmartEnv active profile missing; skipping injection.")
            return
        }

        val resolution = resolver.resolve(project, state, profile)
        if (resolution.variables.isNotEmpty()) {
            env.putAll(resolution.variables)
        }

        val logEntry = SmartEnvLogEntry(
            timestamp = LocalDateTime.now(),
            profileId = profile.id,
            profileName = profile.name.ifBlank { profile.id },
            chain = resolution.chain,
            variables = resolution.resolvedKeys
        )
        project.getService(SmartEnvLogsService::class.java)?.record(logEntry)

        if (profile.showLogsWhenRunning) {
            LOG.info("SmartEnv (${profile.name.ifBlank { profile.id }}): Injecting ${resolution.resolvedKeys.size} vars.")
            resolution.resolvedKeys.take(50).forEach {
                val source = it.winningLayer.sourceDetail ?: it.winningLayer.layerName
                LOG.info("  ${it.key}=${it.finalValue} ($source)")
            }
            invokeOnEdt(project) {
                ToolWindowManager.getInstance(project).getToolWindow("SmartEnv Logs")?.show(null)
            }
        }

        showNotification(project, profile, resolution)
    }

    private fun showNotification(project: Project, profile: SmartEnvProfile, resolution: SmartEnvResolutionResult) {
        invokeOnEdt(project) {
            val detailChain = resolution.chain.joinToString(" ? ").ifBlank { profile.name.ifBlank { profile.id } }
            val message =
                "SmartEnv: Loaded ${resolution.resolvedKeys.size} variable(s) (Profile: ${profile.name.ifBlank { profile.id }}, Chain: $detailChain)"
            NotificationGroupManager.getInstance()
                .getNotificationGroup(notificationGroupId)
                ?.createNotification(message, NotificationType.INFORMATION)
                ?.notify(project)
        }
    }

    private fun invokeOnEdt(project: Project, action: () -> Unit) {
        val app = ApplicationManager.getApplication()
        if (app.isDispatchThread) {
            action()
        } else {
            app.invokeLater({
                if (!project.isDisposed) {
                    action()
                }
            }, ModalityState.NON_MODAL, Condition<Any?> { project.isDisposed })
        }
    }
}

