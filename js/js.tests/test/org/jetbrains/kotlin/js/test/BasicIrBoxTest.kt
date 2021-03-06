/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.js.test

import org.jetbrains.kotlin.config.*
import org.jetbrains.kotlin.descriptors.impl.ModuleDescriptorImpl
import org.jetbrains.kotlin.ir.backend.js.Result
import org.jetbrains.kotlin.ir.backend.js.compile
import org.jetbrains.kotlin.js.config.JSConfigurationKeys
import org.jetbrains.kotlin.js.config.JsConfig
import org.jetbrains.kotlin.js.facade.MainCallParameters
import org.jetbrains.kotlin.js.facade.TranslationUnit
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.test.KotlinTestUtils
import org.jetbrains.kotlin.test.TargetBackend
import java.io.File

private var runtimeResults = mutableMapOf<JsIrTestRuntime, Result>()

abstract class BasicIrBoxTest(
    pathToTestDir: String,
    testGroupOutputDirPrefix: String,
    pathToRootOutputDir: String = BasicBoxTest.TEST_DATA_DIR_PATH,
    generateSourceMap: Boolean = false,
    generateNodeJsRunner: Boolean = false
) : BasicBoxTest(
    pathToTestDir,
    testGroupOutputDirPrefix,
    pathToRootOutputDir = pathToRootOutputDir,
    typedArraysEnabled = true,
    generateSourceMap = generateSourceMap,
    generateNodeJsRunner = generateNodeJsRunner,
    targetBackend = TargetBackend.JS_IR
) {

    override val skipMinification = true

    // TODO Design incremental compilation for IR and add test support
    override val incrementalCompilationChecksEnabled = false

    private val compilationCache = mutableMapOf<String, Result>()

    override fun doTest(filePath: String, expectedResult: String, mainCallParameters: MainCallParameters, coroutinesPackage: String) {
        compilationCache.clear()
        super.doTest(filePath, expectedResult, mainCallParameters, coroutinesPackage)
    }

    override fun translateFiles(
        units: List<TranslationUnit>,
        outputFile: File,
        config: JsConfig,
        outputPrefixFile: File?,
        outputPostfixFile: File?,
        mainCallParameters: MainCallParameters,
        incrementalData: IncrementalData,
        remap: Boolean,
        testPackage: String?,
        testFunction: String,
        runtime: JsIrTestRuntime
    ) {
        val filesToCompile = units
            .map { (it as TranslationUnit.SourceFile).file }
            // TODO: split input files to some parts (global common, local common, test)
            .filterNot { it.virtualFilePath.contains(BasicBoxTest.COMMON_FILES_DIR_PATH) }

//        config.configuration.put(CommonConfigurationKeys.EXCLUDED_ELEMENTS_FROM_DUMPING, setOf("<JS_IR_RUNTIME>"))
        config.configuration.put(
            CommonConfigurationKeys.PHASES_TO_VALIDATE_AFTER,
            setOf(
                "RemoveInlineFunctionsWithReifiedTypeParametersLowering",
                "InnerClassConstructorCallsLowering",
                "InlineClassLowering", "ConstLowering"
            )
        )

        val runtimeConfiguration = config.configuration.copy()

        // TODO: is it right in general? Maybe sometimes we need to compile with newer versions or with additional language features.
        runtimeConfiguration.languageVersionSettings = LanguageVersionSettingsImpl(
            LanguageVersion.LATEST_STABLE, ApiVersion.LATEST_STABLE,
            specificFeatures = mapOf(
                LanguageFeature.AllowContractsForCustomFunctions to LanguageFeature.State.ENABLED,
                LanguageFeature.MultiPlatformProjects to LanguageFeature.State.ENABLED
            ),
            analysisFlags = mapOf(
                AnalysisFlags.useExperimental to listOf("kotlin.contracts.ExperimentalContracts", "kotlin.Experimental"),
                AnalysisFlags.allowResultReturnType to true
            )
        )

        val runtimeFile = File(runtime.path)
        val runtimeResult = runtimeResults.getOrPut(runtime) {
            runtimeConfiguration.put(CommonConfigurationKeys.MODULE_NAME, "JS_IR_RUNTIME")
            val result = compile(config.project, runtime.sources.map(::createPsiFile), runtimeConfiguration)
            runtimeFile.write(result.generatedCode)
            result
        }

        val dependencyNames = config.configuration[JSConfigurationKeys.LIBRARIES]!!.map { File(it).name }
        val dependencies = listOf(runtimeResult.moduleDescriptor) + dependencyNames.mapNotNull { compilationCache[it]?.moduleDescriptor }
        val irDependencies = listOf(runtimeResult.moduleFragment) + compilationCache.values.map { it.moduleFragment }

//        config.configuration.put(CommonConfigurationKeys.PHASES_TO_DUMP_STATE, setOf("UnitMaterializationLowering"))
//        config.configuration.put(CommonConfigurationKeys.PHASES_TO_DUMP_STATE_BEFORE, setOf("ReturnableBlockLowering"))
//        config.configuration.put(CommonConfigurationKeys.PHASES_TO_DUMP_STATE_AFTER, setOf("MultipleCatchesLowering"))
//        config.configuration.put(CommonConfigurationKeys.PHASES_TO_VALIDATE, setOf("ALL"))

        val result = compile(
            config.project,
            filesToCompile,
            config.configuration,
            FqName((testPackage?.let { "$it." } ?: "") + testFunction),
            dependencies,
            irDependencies,
            runtimeResult.moduleDescriptor as ModuleDescriptorImpl
        )

        compilationCache[outputFile.name.replace(".js", ".meta.js")] = result

        // Prefix to help node.js runner find runtime
        val runtimePrefix = "// RUNTIME: [\"${runtimeFile.path}\"]\n"

        outputFile.write(runtimePrefix + result.generatedCode)
    }

    override fun runGeneratedCode(
        jsFiles: List<String>,
        testModuleName: String?,
        testPackage: String?,
        testFunction: String,
        expectedResult: String,
        withModuleSystem: Boolean,
        runtime: JsIrTestRuntime
    ) {
        // TODO: should we do anything special for module systems?
        // TODO: return list of js from translateFiles and provide then to this function with other js files
        nashornIrJsTestCheckers[runtime]!!.check(jsFiles, null, null, testFunction, expectedResult, false)
    }
}


private fun File.write(text: String) {
    parentFile.mkdirs()
    writeText(text)
}
