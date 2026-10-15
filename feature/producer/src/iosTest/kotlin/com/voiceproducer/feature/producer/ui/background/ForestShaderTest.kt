package com.voiceproducer.feature.producer.ui.background

import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ForestShaderTest {
    @Test
    fun `shader compiles with Skia`() {
        val effect = RuntimeEffect.makeForShader(FOREST_SHADER)
        val builder = RuntimeShaderBuilder(effect)

        val declared = Regex("""uniform\s+\w+\s+(\w+)\s*;""").findAll(FOREST_SHADER).map { it.groupValues[1] }.toSet()
        val set = mutableListOf<String>()
        ShaderDriver().writeUniforms(
            width = 100f,
            height = 200f,
            sink =
                object : UniformSink {
                    override fun float(
                        name: String,
                        value: Float,
                    ) {
                        set += name
                        builder.uniform(name, value)
                    }

                    override fun float2(
                        name: String,
                        x: Float,
                        y: Float,
                    ) {
                        set += name
                        builder.uniform(name, x, y)
                    }
                },
        )
        assertEquals(declared, set.toSet())
        assertEquals(set.size, set.toSet().size, "a uniform is set twice")
        assertNotNull(builder.makeShader())
    }
}
