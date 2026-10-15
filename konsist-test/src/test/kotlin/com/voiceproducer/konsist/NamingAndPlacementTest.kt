package com.voiceproducer.konsist

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.ext.list.withNameEndingWith
import com.lemonappdev.konsist.api.ext.list.withParentNamed
import com.lemonappdev.konsist.api.verify.assertTrue
import kotlin.test.Test
import kotlin.test.assertTrue as kotlinAssertTrue

class NamingAndPlacementTest {
    private val production = Konsist.scopeFromProduction()

    @Test
    fun useCasesLiveInDomainAndExposeOneInvokeOperator() {
        production
            .classes()
            .withNameEndingWith("UseCase")
            .assertTrue { useCase ->
                useCase.resideInPackage("com.voiceproducer.core.domain.usecase..") &&
                    useCase.functions().count { it.hasOperatorModifier && it.name == "invoke" } == 1
            }
    }

    @Test
    fun repositoryInterfacesLiveInDomain() {
        production
            .interfaces()
            .withNameEndingWith("Repository")
            .assertTrue { it.resideInPackage("com.voiceproducer.core.domain.repository..") }
    }

    @Test
    fun portImplementationsLiveInDataAndAreInternal() {
        val ports = setOf("TrackLibrary", "TrackStore", "ConversationHistory", "AgentTokenSource", "ApiLogFeed")
        production
            .classes()
            .filter { cls -> cls.parents().any { it.name in ports } }
            .filterNot { it.resideInPackage("com.voiceproducer.core.testing..") }
            .also { kotlinAssertTrue(it.isNotEmpty(), "no implementations of the data ports found") }
            .assertTrue { it.resideInPackage("com.voiceproducer.core.data..") && (it.hasInternalModifier || it.name == "ApiLog") }
    }

    @Test
    fun viewModelsLiveInFeaturesAndAreNamedSo() {
        production
            .classes()
            .withParentNamed("ViewModel")
            .also { kotlinAssertTrue(it.isNotEmpty(), "no ViewModel subclasses found") }
            .assertTrue { it.name.endsWith("ViewModel") && it.resideInPackage("com.voiceproducer.feature..") }
    }
}
