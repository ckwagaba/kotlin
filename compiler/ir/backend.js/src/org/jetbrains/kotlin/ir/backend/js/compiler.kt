/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.backend.js

import com.intellij.openapi.project.Project
import org.jetbrains.kotlin.backend.common.CompilerPhaseManager
import org.jetbrains.kotlin.backend.common.LoggingContext
import org.jetbrains.kotlin.builtins.functions.functionInterfacePackageFragmentProvider
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.languageVersionSettings
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.descriptors.impl.CompositePackageFragmentProvider
import org.jetbrains.kotlin.descriptors.impl.ModuleDescriptorImpl
import org.jetbrains.kotlin.incremental.components.LookupTracker
import org.jetbrains.kotlin.ir.backend.js.lower.inline.replaceUnboundSymbols
import org.jetbrains.kotlin.ir.backend.js.lower.serialization.ir.*
import org.jetbrains.kotlin.ir.backend.js.lower.serialization.metadata.*
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.descriptors.IrBuiltIns
import org.jetbrains.kotlin.ir.util.ConstantValueGenerator
import org.jetbrains.kotlin.ir.util.SymbolTable
import org.jetbrains.kotlin.ir.util.TypeTranslator
import org.jetbrains.kotlin.js.analyze.TopDownAnalyzerFacadeForJS
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.progress.ProgressIndicatorAndCompilationCanceledStatus
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi2ir.Psi2IrTranslator
import org.jetbrains.kotlin.resolve.CompilerDeserializationConfiguration
import org.jetbrains.kotlin.storage.LockBasedStorageManager
import java.io.File

data class Result(val moduleDescriptor: ModuleDescriptor, val generatedCode: String, val moduleFragment: IrModuleFragment?)

fun compile(
    project: Project,
    files: List<KtFile>,
    configuration: CompilerConfiguration,
    export: FqName? = null,
    isKlibCompilation: Boolean = false,
    dependencies: List<ModuleDescriptor> = listOf(),
    irDependencyModules: List<IrModuleFragment> = listOf(),
    builtInsModule: ModuleDescriptorImpl? = null
): Result {
    val analysisResult =
        TopDownAnalyzerFacadeForJS.analyzeFiles(
            files,
            project,
            configuration,
            dependencies.mapNotNull { it as? ModuleDescriptorImpl },
            emptyList(),
            thisIsBuiltInsModule = builtInsModule == null,
            customBuiltInsModule = builtInsModule
        )

    ProgressIndicatorAndCompilationCanceledStatus.checkCanceled()

    TopDownAnalyzerFacadeForJS.checkForErrors(files, analysisResult.bindingContext)

    val symbolTable = SymbolTable()
//    irDependencyModules.forEach { symbolTable.loadModule(it) }

    val psi2IrTranslator = Psi2IrTranslator(configuration.languageVersionSettings)
    val psi2IrContext = psi2IrTranslator.createGeneratorContext(analysisResult.moduleDescriptor, analysisResult.bindingContext, symbolTable)

    val moduleFragment = psi2IrTranslator.generateModuleFragment(psi2IrContext, files)

    if (isKlibCompilation) {
        val logggg = object : LoggingContext {
            override fun log(message: () -> String) {}
        }

        val declarationTable = DeclarationTable(moduleFragment.irBuiltins, DescriptorTable(), psi2IrContext.symbolTable)

        val serializedIr = IrModuleSerializer(logggg, declarationTable/*, onlyForInlines = false*/).serializedIrModule(moduleFragment)
        val serializer = JsKlibMetadataSerializationUtil

        val moduleName = configuration.get(CommonConfigurationKeys.MODULE_NAME) as String
        val metadataVersion = configuration.get(CommonConfigurationKeys.METADATA_VERSION)  as? JsKlibMetadataVersion
            ?: JsKlibMetadataVersion.INSTANCE
        val moduleDescription =
            JsKlibMetadataModuleDescriptor(moduleName, dependencies.map { it.name.asString() }, moduleFragment.descriptor)
        val serializedData = serializer.serializeMetadata(
            psi2IrContext.bindingContext,
            moduleDescription,
            configuration.get(CommonConfigurationKeys.LANGUAGE_VERSION_SETTINGS)!!,
            metadataVersion
        ) { declarationDescriptor ->
            val index = declarationTable.descriptorTable.get(declarationDescriptor)
            index?.let { newDescriptorUniqId(it) }
        }

        val stdKlibDir = File("js/js.translator/testData/out/klibs/runtime/").also {
            it.deleteRecursively()
            it.mkdirs()
        }

        val moduleFile = File(stdKlibDir, "module.kji")
        moduleFile.writeBytes(serializedIr.module)

        val irDeclarationDir = File(stdKlibDir, "ir/").also { it.mkdir() }

        for ((id, data) in serializedIr.declarations) {
            val file = File(irDeclarationDir, id.declarationFileName)
            file.writeBytes(data)
        }


        val debugFile = File(stdKlibDir, "debug.txt")

        for ((id, data) in serializedIr.debugIndex) {
            debugFile.appendText(id.toString())
            debugFile.appendText(" --- ")
            debugFile.appendText(data)
            debugFile.appendText("\n")
        }

        val metadata = File(stdKlibDir, "${moduleDescription.name}.${JsKlibMetadataSerializationUtil.CLASS_METADATA_FILE_EXTENSION}").also {
            it.writeBytes(serializedData.asByteArray())
        }

        val storageManager = LockBasedStorageManager("JsConfig")
        val lookupTracker = configuration.get(CommonConfigurationKeys.LOOKUP_TRACKER, LookupTracker.DO_NOTHING)
        val parts = serializer.readModuleAsProto(metadata.readBytes())
        val builtIns = analysisResult.moduleDescriptor.builtIns
        val md = ModuleDescriptorImpl(Name.special("<$moduleName>"), storageManager, builtIns)
        val packageProviders = listOf(
            functionInterfacePackageFragmentProvider(storageManager, md),
            createJsKlibMetadataPackageFragmentProvider(
                storageManager, md, parts.header, parts.body, metadataVersion,
                CompilerDeserializationConfiguration(configuration.languageVersionSettings),
                lookupTracker
            )
        )

        md.initialize(CompositePackageFragmentProvider(packageProviders))
        md.setDependencies(listOf(md/*, builtIns.builtInsModule*/))


        val st = SymbolTable()

        val typeTranslator = TypeTranslator(st, configuration.languageVersionSettings).also {
            it.constantValueGenerator = ConstantValueGenerator(md, st)
        }

        val irBuiltIns = IrBuiltIns(md.builtIns, typeTranslator, st)

        val deserializer = IrKlibProtoBufModuleDeserializer(
            md,
            logggg,
            irBuiltIns,
            stdKlibDir,
            st,
            null
        )
        val deserializedModuleFragment = deserializer.deserializeIrModule(md, moduleFile.readBytes(), true)

        val context = JsIrBackendContext(md, irBuiltIns, st, deserializedModuleFragment, configuration, irDependencyModules)

        deserializedModuleFragment.replaceUnboundSymbols(context)

        CompilerPhaseManager(context, context.phases, deserializedModuleFragment, JsPhaseRunner).run {
            jsPhases.fold(data) { m, p -> phase(p, context, m) }
        }

        return Result(md, context.jsProgram.toString(), null)
    } else {

        val context = JsIrBackendContext(
            analysisResult.moduleDescriptor,
            psi2IrContext.irBuiltIns,
            psi2IrContext.symbolTable,
            moduleFragment,
            configuration,
            irDependencyModules
        )

        CompilerPhaseManager(context, context.phases, moduleFragment, JsPhaseRunner).run {
            jsPhases.fold(data) { m, p -> phase(p, context, m) }
        }

        return Result(analysisResult.moduleDescriptor, context.jsProgram.toString(), context.moduleFragmentCopy)
    }
}