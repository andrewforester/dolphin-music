package com.voiceproducer.feature.producer.ui.orb

import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder
import kotlin.test.Test
import kotlin.test.assertNotNull

class OrbShaderTest {
    @Test
    fun `orb shader compiles with Skia`() {
        val builder = RuntimeShaderBuilder(RuntimeEffect.makeForShader(ORB_SHADER))
        builder.uniform("uRes", 100f, 100f)
        builder.uniform("uTime", 1f)
        for (i in 0 until 4) builder.uniform("uC$i", 0.1f, 0.6f, 0.7f)
        builder.uniform("uSat", 1f)
        builder.uniform("uBright", 1f)
        assertNotNull(builder.makeShader())
    }
}
