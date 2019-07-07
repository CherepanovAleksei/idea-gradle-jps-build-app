package org.jetbrains.kotlin.tools.testutils

import com.intellij.compiler.CompilerConfigurationImpl
import com.intellij.compiler.impl.InternalCompileDriver
import com.intellij.ide.impl.ProjectUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.application.ex.ApplicationManagerEx
import com.intellij.openapi.compiler.CompileStatusNotification
import com.intellij.openapi.compiler.CompilerMessageCategory
import com.intellij.openapi.components.ServiceManager
import com.intellij.openapi.externalSystem.model.DataNode
import com.intellij.openapi.externalSystem.model.project.ProjectData
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.service.execution.ProgressExecutionMode
import com.intellij.openapi.externalSystem.service.project.ExternalProjectRefreshCallback
import com.intellij.openapi.externalSystem.service.project.ProjectDataManager
import com.intellij.openapi.externalSystem.settings.ExternalProjectSettings
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ex.ProjectManagerEx
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.Computable
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.util.ThreeState
import org.jetbrains.plugins.gradle.settings.DefaultGradleProjectSettings
import org.jetbrains.plugins.gradle.settings.DistributionType
import org.jetbrains.plugins.gradle.settings.GradleProjectSettings
import org.jetbrains.plugins.gradle.util.GradleConstants
import java.io.File
import java.lang.reflect.Array
import java.lang.reflect.Field
import java.lang.reflect.Proxy
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.collections.HashMap
import kotlin.collections.HashSet

private fun getUsedMemory(): Long {
    val runtime = Runtime.getRuntime()
    return runtime.totalMemory() - runtime.freeMemory()
}


fun printMemory(afterGc: Boolean) {
    val runtime = Runtime.getRuntime()
    printMessage("Low memory ${if (afterGc) "after GC" else ", invoking GC"}. Total memory=${runtime.totalMemory()}, free=${runtime.freeMemory()}", if (afterGc) MessageStatus.ERROR else MessageStatus.WARNING)
}

fun importProject(projectPath: String, jdkPath: String): Project? {
    val application = ApplicationManagerEx.getApplicationEx()
    return application.runReadAction(Computable<Project?> {
        return@Computable try {
            application.isSaveAllowed = true
            doImportProject(projectPath, jdkPath)
        } catch (e: Exception) {
            printException(e)
            null
        }
    })
}

private fun doImportProject(projectPath: String, jdkPath: String): Project? {
    printProgress("Opening project")
    val path = projectPath.replace(File.separatorChar, '/')
    val vfsProject = LocalFileSystem.getInstance().findFileByPath(path)
    if (vfsProject == null) {
        printMessage("Cannot find directory $path", MessageStatus.ERROR)
        return null
    }

    val project = ProjectUtil.openProject(path, null, false)

    if (project == null) {
        printMessage("Unable to open project", MessageStatus.ERROR)
        return null
    }
    printProgress("Project loaded, refreshing from Gradle")
    WriteAction.runAndWait<RuntimeException> {
        val sdkType = JavaSdk.getInstance()
        val mySdk = sdkType.createJdk("JDK_1.8", jdkPath, false)

        ProjectJdkTable.getInstance().addJdk(mySdk)
        ProjectRootManager.getInstance(project).projectSdk = mySdk
    }


    val startTime = System.nanoTime() //NB do not use currentTimeMillis() as it is sensitive to time adjustment
    startOperation(OperationType.TEST, "Import project")
    reportStatistics("used_memory_before_import", getUsedMemory().toString())
    reportStatistics("total_memory_before_import", Runtime.getRuntime().totalMemory().toString())
    ExternalSystemUtil.refreshProject(
            project,
            GradleConstants.SYSTEM_ID,
            path,
            object : ExternalProjectRefreshCallback {
                override fun onSuccess(externalProject: DataNode<ProjectData>?) {
                    reportStatistics("import_duration", ((System.nanoTime() - startTime) / 1000_000).toString())
                    if (externalProject != null) {
                        finishOperation(OperationType.TEST, "Import project", duration = (System.nanoTime() - startTime) / 1000_000)
                        ServiceManager.getService(ProjectDataManager::class.java)
                                .importData(externalProject, project, true)
                    } else {
                        finishOperation(OperationType.TEST, "Import project", "Filed to import project. See IDEA logs for details")
                        throw RuntimeException("Failed to import project due to unknown error")
                    }
                    testExternalSubsystemForProxyMemoryLeak(externalProject)

                }


                override fun onFailure(externalTaskId: ExternalSystemTaskId, errorMessage: String, errorDetails: String?) {
                    finishOperation(OperationType.TEST, "Import project", "Filed to import project: $errorMessage. Details: $errorDetails")
                }
            },
            false,
            ProgressExecutionMode.MODAL_SYNC,
            true
    )
    reportStatistics("used_memory_after_import", getUsedMemory().toString())
    reportStatistics("total_memory_after_import", Runtime.getRuntime().totalMemory().toString())
    System.gc()
    reportStatistics("used_memory_after_import_gc", getUsedMemory().toString())
    reportStatistics("total_memory_after_import_gc", Runtime.getRuntime().totalMemory().toString())

    printProgress("Save IDEA projects")

    project.save()
    ProjectManagerEx.getInstanceEx().openProject(project)
    FileDocumentManager.getInstance().saveAllDocuments()
    ApplicationManager.getApplication().saveSettings()
    ApplicationManager.getApplication().saveAll()

    printProgress("Import done")
    return project
}

fun setDelegationMode(path: String, project: Project, delegationMode: Boolean) {
    DefaultGradleProjectSettings.getInstance(project).isDelegatedBuild = false
    val projectSettings = GradleProjectSettings()
    projectSettings.externalProjectPath = path
    projectSettings.delegatedBuild = if (delegationMode) ThreeState.YES else ThreeState.NO // disable delegated build in irder to run JPS build
    projectSettings.distributionType = DistributionType.DEFAULT_WRAPPED // use default wrapper
    projectSettings.storeProjectFilesExternally = ThreeState.NO
    projectSettings.withQualifiedModuleNames()

    val systemSettings = ExternalSystemApiUtil.getSettings(project, GradleConstants.SYSTEM_ID)
    @Suppress("UNCHECKED_CAST") val linkedSettings: Collection<ExternalProjectSettings> = systemSettings.getLinkedProjectsSettings() as Collection<ExternalProjectSettings>
    linkedSettings.filterIsInstance<GradleProjectSettings>().forEach { systemSettings.unlinkExternalProject(it.externalProjectPath) }

    systemSettings.linkProject(projectSettings)
}

fun buildProject(project: Project?): Boolean {
    val finishedLautch = CountDownLatch(1)
    if (project == null) {
        printMessage("Project is null", MessageStatus.ERROR)
        return false
    } else {
        startOperation(OperationType.COMPILATION, "Compile project with JPS")
        var errorsCount = 0
        var abortedStatus = false
        val compilationStarted = System.nanoTime()
        val callback = CompileStatusNotification { aborted, errors, warnings, compileContext ->
            run {
                try {
                    errorsCount = errors
                    abortedStatus = aborted
                    printMessage("Compilation done. Aborted=$aborted, Errors=$errors, Warnings=$warnings", MessageStatus.WARNING)
                    reportStatistics("jps_compilation_errors", errors.toString())
                    reportStatistics("jps_compilation_warnings", warnings.toString())
                    reportStatistics("jps_compilation_duration", ((System.nanoTime() - compilationStarted) / 1000_000).toString())

                    CompilerMessageCategory.values().forEach { category ->
                        compileContext.getMessages(category).forEach {
                            val message = "$category - ${it.virtualFile?.canonicalPath ?: "-"}: ${it.message}"
                            when (category) {
                                CompilerMessageCategory.ERROR -> {
                                    printMessage(message, MessageStatus.ERROR)
                                    //reportTestError("Compile project with JPS", message)
                                }
                                CompilerMessageCategory.WARNING -> printMessage(message, MessageStatus.WARNING)
                                else -> printMessage(message)
                            }
                        }
                    }
                } finally {
                    finishedLautch.countDown()
                }
            }
        }

        CompilerConfigurationImpl.getInstance(project).setBuildProcessHeapSize(3500)

        val compileContext = InternalCompileDriver(project).rebuild(callback)
        while (!finishedLautch.await(1, TimeUnit.MINUTES)) {
            if (!compileContext.progressIndicator.isRunning) {
                printMessage("Progress indicator says that compilation is not running.", MessageStatus.ERROR)
                break
            }
            printProgress("Compilation status: Errors: ${compileContext.getMessages(CompilerMessageCategory.ERROR).size}. Warnings: ${compileContext.getMessages(CompilerMessageCategory.WARNING).size}.")
        }

        if (errorsCount > 0 || abortedStatus) {
            finishOperation(OperationType.COMPILATION, "Compile project with JPS", "Compilation failed with $errorsCount errors")
            return false
        } else {
            finishOperation(OperationType.COMPILATION, "Compile project with JPS")
        }
    }
    return true
}

private fun testExternalSubsystemForProxyMemoryLeak(externalProject: DataNode<ProjectData>) {


    class MemoryLeakChecker(externalProject: DataNode<ProjectData>, val errorConsumer: (String) -> Unit) {
        val referencingObjects = HashMap<Any, Any>()
        val leakedObjects = ArrayList<Any>()
        var errorsCount = 0
            private set

        private val typesToSkip = setOf("java.lang.String", "java.lang.Integer", "java.lang.Character", "java.lang.Byte", "java.lang.Long")

        private fun shouldBeProcessed(toProcess: Any?, processed: Set<Any>): Boolean {
            return toProcess != null && !typesToSkip.contains(toProcess.javaClass.name) && !processed.contains(toProcess)
        }

        private fun isLeakedObject(o: Any) = o is Proxy

        private fun reportLeakedObject(o: Any) {
            leakedObjects.add(o)
            @Suppress("NAME_SHADOWING") var o: Any? = o
            val errMessage = StringBuilder()
            errMessage.append(String.format("Object [%s] seems to be a referenced gradle tooling api object. (it may lead to memory leaks during import) Referencing path: ", o))
            while (o != null) {
                errMessage.append(String.format("[%s] type: %s <-\r\n", o, o.javaClass.toString()))
                o = referencingObjects[o]
            }
            errorConsumer(errMessage.toString())
            errorsCount++
        }

        private fun saveToProcessIfRequired(processed: Set<Any>, toProcess: Queue<Any>, referrers: MutableMap<Any, Any>, referringObject: Any, o: Any) {
            if (shouldBeProcessed(o, processed)) {
                toProcess.add(o)
                referrers[o] = referringObject
            }
        }

        init {
            val processed = HashSet<Any>()
            val toProcess = LinkedList<Any>()
            toProcess.add(externalProject)

            val fieldsWithModifiedAccessibility = HashSet<Field>()
            try {
                while (!toProcess.isEmpty()) {
                    val nextObject = toProcess.poll()
                    processed.add(nextObject)
                    try {
                        if (isLeakedObject(nextObject)) {
                            reportLeakedObject(nextObject)
                        } else {
                            nextObject.javaClass.declaredFields.forEach {
                                if (!it.isAccessible) {
                                    fieldsWithModifiedAccessibility.add(it)
                                    it.isAccessible = true
                                }
                                it.get(nextObject)?.let { fieldValue ->
                                    when {
                                        fieldValue is Collection<*> -> for (o in (fieldValue as Collection<*>?)!!) {
                                            if (o != null) saveToProcessIfRequired(processed, toProcess, referencingObjects, nextObject, o)
                                        }
                                        fieldValue.javaClass.isArray -> for (i in 0 until Array.getLength(fieldValue)) {
                                            Array.get(fieldValue, i)?.let { arrayObject ->
                                                saveToProcessIfRequired(processed, toProcess, referencingObjects, nextObject, arrayObject)
                                            }

                                        }
                                        else -> saveToProcessIfRequired(processed, toProcess, referencingObjects, nextObject, fieldValue)
                                    }
                                }
                            }
                        }
                    } catch (e: Throwable) {
                        errorConsumer("Could not process object [$nextObject] with type [${nextObject.javaClass}]. ${e.javaClass} Error message: [${e.message}]")
                        errorsCount++
                    }
                }
            } finally {
                fieldsWithModifiedAccessibility.forEach { it.isAccessible = false }
            }
        }
    }

    val start = System.nanoTime()
    val memoryLeakTestName = "Check for memory leaks"
    startOperation(OperationType.TEST, memoryLeakTestName)
    val memoryLeakChecker = MemoryLeakChecker(externalProject) { printMessage(it, MessageStatus.ERROR) }
    reportStatistics("memory_number_of_leaked_objects", memoryLeakChecker.leakedObjects.size.toString())
    if (memoryLeakChecker.errorsCount == 0) {
        finishOperation(OperationType.TEST, memoryLeakTestName, duration = ((System.nanoTime() - start) / 1000_000))
    } else {
        finishOperation(OperationType.TEST, memoryLeakTestName, failureMessage = "Check for memory leaks finished with ${memoryLeakChecker.errorsCount} errors.", duration = ((System.nanoTime() - start) / 1000_000))
    }
}
