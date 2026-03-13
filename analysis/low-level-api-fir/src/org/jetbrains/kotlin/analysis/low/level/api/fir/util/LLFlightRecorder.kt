/*
 * Copyright 2010-2025 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.analysis.low.level.api.fir.util

import org.jetbrains.kotlin.analysis.api.KaImplementationDetail
import org.jetbrains.kotlin.analysis.api.projectStructure.KaBuiltinsModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaDanglingFileModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaLibraryFallbackDependenciesModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaLibraryModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaLibrarySourceModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaNotUnderContentRootModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaScriptDependencyModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaScriptModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaSourceModule
import org.jetbrains.kotlin.analysis.low.level.api.fir.api.targets.LLPartialBodyAnalysisState
import org.jetbrains.kotlin.analysis.low.level.api.fir.lazy.resolve.LLFirResolveDesignationCollector
import org.jetbrains.kotlin.analysis.low.level.api.fir.projectStructure.LLFirModuleData
import org.jetbrains.kotlin.analysis.low.level.api.fir.transformers.PartialBodyAnalysisSuspendedException
import org.jetbrains.kotlin.fir.FirElementWithResolveState
import org.jetbrains.kotlin.fir.declarations.FirAnonymousFunction
import org.jetbrains.kotlin.fir.declarations.FirAnonymousInitializer
import org.jetbrains.kotlin.fir.declarations.FirBackingField
import org.jetbrains.kotlin.fir.declarations.FirClass
import org.jetbrains.kotlin.fir.declarations.FirCodeFragment
import org.jetbrains.kotlin.fir.declarations.FirConstructor
import org.jetbrains.kotlin.fir.declarations.FirDanglingModifierList
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.declarations.FirEnumEntry
import org.jetbrains.kotlin.fir.declarations.FirField
import org.jetbrains.kotlin.fir.declarations.FirFile
import org.jetbrains.kotlin.fir.declarations.FirFunction
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.FirPropertyAccessor
import org.jetbrains.kotlin.fir.declarations.FirReceiverParameter
import org.jetbrains.kotlin.fir.declarations.FirReplSnippet
import org.jetbrains.kotlin.fir.declarations.FirResolvePhase
import org.jetbrains.kotlin.fir.declarations.FirScript
import org.jetbrains.kotlin.fir.declarations.FirTypeAlias
import org.jetbrains.kotlin.fir.declarations.FirTypeParameter
import org.jetbrains.kotlin.fir.declarations.FirValueParameter
import org.jetbrains.kotlin.fir.declarations.FirValueParameterKind
import org.jetbrains.kotlin.fir.declarations.FirVariable
import org.jetbrains.kotlin.fir.declarations.utils.classId
import org.jetbrains.kotlin.fir.declarations.utils.nameOrSpecialName
import org.jetbrains.kotlin.utils.exceptions.shouldIjPlatformExceptionBeRethrown

/**
 * Interface for completing phase events.
 */
internal interface LLPhaseEventCompleter {
    fun notifyCompleted()
    fun notifyCompletedWithFailure(throwable: Throwable)
}

/**
 * Interface for completing phase suspension events.
 */
internal interface LLPhaseSuspensionEventCompleter {
    fun notifyCompleted()
}

/**
 * Backend interface for flight recorder implementations.
 * Implement this interface to provide custom event recording (e.g., JFR, OpenTelemetry, logging).
 */
@KaImplementationDetail
internal interface LLFlightRecorderBackend {
    /** Whether phase events are enabled. */
    val isPhaseEventEnabled: Boolean

    /** Whether partial body analysis events are enabled. */
    val isPartialBodyAnalysisEventEnabled: Boolean

    /** Whether ready phase events are enabled. */
    val isReadyPhaseEventEnabled: Boolean

    /** Whether phase suspension events are enabled. */
    val isPhaseSuspensionEventEnabled: Boolean

    /** Whether stop-the-world invalidation events are enabled. */
    val isStopWorldInvalidationEventEnabled: Boolean

    /**
     * Record a phase event start.
     * @return A completer to signal event completion, or null if recording is not needed.
     */
    fun beginPhaseEvent(
        path: String,
        hash: Int,
        phase: Byte,
        moduleKind: Byte
    ): LLPhaseEventCompleter?

    /**
     * Record a partial body analysis event.
     */
    fun recordPartialBodyAnalysisEvent(hash: Int, count: Int, attempt: Int)

    /**
     * Record a ready phase event.
     */
    fun recordReadyPhaseEvent(path: String, hash: Int, phase: Byte, moduleKind: Byte)

    /**
     * Record a phase suspension event start.
     * @return A completer to signal event completion, or null if recording is not needed.
     */
    fun beginPhaseSuspensionEvent(hash: Int, phase: Byte): LLPhaseSuspensionEventCompleter?

    /**
     * Record a stop-the-world invalidation event.
     * @param state true if invalidation was scheduled, false if completed.
     */
    fun recordStopWorldInvalidationEvent(state: Boolean)
}

/**
 * No-op implementation of [LLFlightRecorderBackend].
 * All events are disabled and no recording occurs.
 */
@KaImplementationDetail
internal object NoOpFlightRecorderBackend : LLFlightRecorderBackend {
    override val isPhaseEventEnabled: Boolean = false
    override val isPartialBodyAnalysisEventEnabled: Boolean = false
    override val isReadyPhaseEventEnabled: Boolean = false
    override val isPhaseSuspensionEventEnabled: Boolean = false
    override val isStopWorldInvalidationEventEnabled: Boolean = false

    override fun beginPhaseEvent(path: String, hash: Int, phase: Byte, moduleKind: Byte): LLPhaseEventCompleter? = null
    override fun recordPartialBodyAnalysisEvent(hash: Int, count: Int, attempt: Int) {}
    override fun recordReadyPhaseEvent(path: String, hash: Int, phase: Byte, moduleKind: Byte) {}
    override fun beginPhaseSuspensionEvent(hash: Int, phase: Byte): LLPhaseSuspensionEventCompleter? = null
    override fun recordStopWorldInvalidationEvent(state: Boolean) {}
}

@KaImplementationDetail
object LLFlightRecorder {
    /**
     * The backend used for recording events.
     * Can be replaced with a custom implementation (e.g., JFR-based) at initialization time.
     */
    @Volatile
    internal var backend: LLFlightRecorderBackend = NoOpFlightRecorderBackend

    /**
     * Notify that the [target] declaration was successfully analyzed up to the given [phase] (possibly partially).
     *
     * @param target The declaration being analyzed.
     * @param containingDeclarations The list of declarations enclosing [target] starting from the [FirFile].
     * @param phase The phase the declaration was analyzed to.
     */
    internal fun phase(
        target: FirElementWithResolveState,
        containingDeclarations: List<FirDeclaration>,
        requestedPhase: FirResolvePhase
    ): LLPhaseEventCompleter? {
        if (!backend.isPhaseEventEnabled) {
            return null
        }

        return backend.beginPhaseEvent(
            path = path(containingDeclarations, target),
            hash = System.identityHashCode(target),
            phase = PHASE_COMPACT_NAMES[requestedPhase.ordinal],
            moduleKind = computeModuleKind(target)
        )
    }

    /**
     * Notify that the [declaration]'s body is analyzed partially.
     *
     * @param declaration The declaration analyzed partially.
     * @param state The current partial analysis state of the [declaration].
     */
    internal fun partialBodyAnalyzed(declaration: FirElementWithResolveState, state: LLPartialBodyAnalysisState) {
        if (!backend.isPartialBodyAnalysisEventEnabled) {
            return
        }

        backend.recordPartialBodyAnalysisEvent(
            hash = System.identityHashCode(declaration),
            count = state.analyzedPsiStatementCount,
            attempt = state.performedAnalysesCount
        )
    }

    /**
     * Notify that the [target] declaration was required to be analyzed up to the given [phase].
     * However, the declaration already reached it, so no work has been performed.
     *
     * Use `readyPhase(target, containingDeclarations, requestedPhase, withCallableMembers)` when you have the list of containing
     * declarations, e.g., from a [org.jetbrains.kotlin.analysis.low.level.api.fir.api.FirDesignation].
     *
     * @param target The declaration being analyzed.
     * @param phase The phase the declaration is already analyzed to.
     */
    internal fun readyPhase(target: FirElementWithResolveState, requestedPhase: FirResolvePhase) {
        if (!backend.isReadyPhaseEventEnabled) {
            return
        }

        val designation = LLFirResolveDesignationCollector.getDesignationToResolve(target)?.designation ?: return

        backend.recordReadyPhaseEvent(
            path = path(designation.path, target),
            hash = System.identityHashCode(target),
            phase = PHASE_COMPACT_NAMES[requestedPhase.ordinal],
            moduleKind = computeModuleKind(target)
        )
    }

    /**
     * Notify that the [target] declaration was required to be analyzed up to the given [phase].
     * However, the declaration already reached it, so no work has been performed.
     *
     * @param target The declaration being analyzed.
     * @param containingDeclarations The list of declarations enclosing [target] starting from the [FirFile].
     * @param phase The phase the declaration is already analyzed to.
     */
    internal fun readyPhase(
        target: FirElementWithResolveState,
        containingDeclarations: List<FirDeclaration>,
        requestedPhase: FirResolvePhase
    ) {
        if (!backend.isReadyPhaseEventEnabled) {
            return
        }

        backend.recordReadyPhaseEvent(
            path = path(containingDeclarations, target),
            hash = System.identityHashCode(target),
            phase = PHASE_COMPACT_NAMES[requestedPhase.ordinal],
            moduleKind = computeModuleKind(target)
        )
    }

    /**
     * Notify that the current thread acknowledged the [declaration] is either finished analyzing up to [phase],
     * or got an exception, such as [com.intellij.openapi.progress.ProcessCanceledException].
     *
     * @param declaration The analyzed declaration.
     * @param phase The phase the [declaration] is being analyzed to.
     */
    internal fun phaseSuspension(declaration: FirElementWithResolveState, requestedPhase: FirResolvePhase): LLPhaseSuspensionEventCompleter? {
        if (!backend.isPhaseSuspensionEventEnabled) {
            return null
        }

        return backend.beginPhaseSuspensionEvent(
            hash = System.identityHashCode(declaration),
            phase = PHASE_COMPACT_NAMES[requestedPhase.ordinal]
        )
    }

    /**
     * Notify that a stop-the-world session invalidation has been scheduled.
     */
    fun stopWorldSessionInvalidationScheduled() {
        stopWorldSessionInvalidation(newState = true)
    }

    /**
     * Notify that a stop-the-world session invalidation has been completed (either after being scheduled, or immediately).
     */
    fun stopWorldSessionInvalidationComplete() {
        stopWorldSessionInvalidation(newState = false)
    }

    private fun stopWorldSessionInvalidation(newState: Boolean) {
        if (!backend.isStopWorldInvalidationEventEnabled) {
            return
        }

        backend.recordStopWorldInvalidationEvent(newState)
    }

    private fun name(declaration: FirElementWithResolveState): String {
        /**
         * As [name] is used as a component of [path], names must not contain colons.
         * So theoretically, we should escape/substitute all colon characters.
         * However, colons are forbidden in JVM bytecode, and overall, the chance that we find them is considerably low.
         */
        @Suppress("SpellCheckingInspection")
        return when (declaration) {
            is FirFile -> "fl/" + declaration.name
            is FirScript -> "s/" + declaration.name.asString()
            is FirTypeParameter -> "tp/" + declaration.name.asString()
            is FirTypeAlias -> "ta/" + declaration.classId.asString()
            is FirClass -> "c/" + declaration.classId.asString()
            is FirEnumEntry -> "ee/" + declaration.name.asString()
            is FirField -> "fi/" + declaration.name.asString()
            is FirProperty -> "p/" + declaration.name.asString()
            is FirBackingField -> "bf/" + declaration.name.asString()
            is FirValueParameter -> {
                val kind = if (declaration.valueParameterKind == FirValueParameterKind.Regular) "vp/" else "cp/"
                kind + declaration.name.asString()
            }
            is FirVariable -> "v/" + declaration.name.asString() + "/${declaration::class.java.simpleName.lowercase()}"
            is FirPropertyAccessor -> (if (declaration.isGetter) "pg/" else "ps/") + declaration.propertySymbol.name.asString()
            is FirConstructor -> "ctor/" + signature(declaration)
            is FirAnonymousFunction -> "lambda"
            is FirFunction -> {
                val baseName = "f/" + declaration.nameOrSpecialName.asString()
                baseName + '/' + signature(declaration)
            }
            is FirReplSnippet -> "repl"
            is FirCodeFragment -> "code"
            is FirReceiverParameter -> "recv"
            is FirDanglingModifierList -> "dml"
            is FirAnonymousInitializer -> "init"
            else -> "?/" + declaration.javaClass.simpleName
        }
    }

    private fun signature(declaration: FirFunction): String {
        return declaration.valueParameters.joinToString(",") { it.name.asString() }
    }

    private fun path(containingDeclarations: List<FirDeclaration>, target: FirElementWithResolveState): String = buildString {
        for (entry in containingDeclarations) {
            append(name(entry))
            append(":")
        }
        append(name(target))
    }
}

private fun computeModuleKind(target: FirElementWithResolveState): Byte {
    val moduleData = target.moduleData as LLFirModuleData
    return when (moduleData.ktModule) {
        is KaSourceModule -> 0
        is KaDanglingFileModule -> 1
        is KaNotUnderContentRootModule -> 2
        is KaScriptModule -> 3
        is KaScriptDependencyModule -> 4
        is KaLibraryFallbackDependenciesModule -> 5
        is KaLibraryModule -> 6
        is KaLibrarySourceModule -> 7
        is KaBuiltinsModule -> 8
        else -> -1
    }
}

/**
 *                  !!!
 * When adding or removing phases, use unused numbers.
 * Never change existing mappings!
 */
private val PHASE_COMPACT_NAMES = run {
    val phases = FirResolvePhase.entries
    ByteArray(phases.size) {
        when (phases[it]) {
            FirResolvePhase.RAW_FIR -> 0
            FirResolvePhase.IMPORTS -> 1
            FirResolvePhase.COMPILER_REQUIRED_ANNOTATIONS -> 2
            FirResolvePhase.COMPANION_GENERATION -> 3
            FirResolvePhase.SUPER_TYPES -> 4
            FirResolvePhase.SEALED_CLASS_INHERITORS -> 5
            FirResolvePhase.TYPES -> 6
            FirResolvePhase.STATUS -> 7
            FirResolvePhase.EXPECT_ACTUAL_MATCHING -> 8
            FirResolvePhase.CONTRACTS -> 9
            FirResolvePhase.IMPLICIT_TYPES_BODY_RESOLVE -> 10
            FirResolvePhase.CONSTANT_EVALUATION -> 11
            FirResolvePhase.ANNOTATION_ARGUMENTS -> 12
            FirResolvePhase.BODY_RESOLVE -> 13
        }
    }
}

/**
 * Utility to determine the execution result code from a throwable.
 * 0 - Success, 1 - Cancellation, 2 - Exception
 */
internal fun computeExecutionResult(throwable: Throwable): Byte = when {
    throwable is PartialBodyAnalysisSuspendedException -> 0
    shouldIjPlatformExceptionBeRethrown(throwable) -> 1
    else -> 2
}