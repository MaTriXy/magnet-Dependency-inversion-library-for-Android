package magnet.processor.instances

import magnet.Instance
import magnet.processor.MagnetProcessorEnv
import magnet.processor.instances.aspects.index.FactoryIndexCodeGenerator
import magnet.processor.instances.generator.CodeWriter
import magnet.processor.instances.generator.FactoryTypeCodeGenerator
import magnet.processor.instances.parser.InstanceParserForClass
import magnet.processor.instances.parser.InstanceParserForMethod
import javax.annotation.processing.RoundEnvironment
import javax.lang.model.util.ElementFilter

class InstanceProcessor(
    private val env: MagnetProcessorEnv
) {

    private val factoryFromClassAnnotationParser = InstanceParserForClass(env)
    private val factoryFromMethodAnnotationParser = InstanceParserForMethod(env)
    private val factoryTypeCodeGenerator = FactoryTypeCodeGenerator()
    private val factoryIndexCodeGenerator = FactoryIndexCodeGenerator()

    fun process(roundEnv: RoundEnvironment): Boolean {

        val annotatedElements = roundEnv.getElementsAnnotatedWith(Instance::class.java)
        if (annotatedElements.isEmpty()) {
            return false
        }

        val factoryTypes = mutableListOf<FactoryType>()
        ElementFilter.typesIn(annotatedElements).forEach { element ->
            val parsedFactoryTypes = with(factoryFromClassAnnotationParser) { element.parse() }
            for (factoryType in parsedFactoryTypes) {
                if (!factoryType.disabled) {
                    factoryTypes.add(factoryType)
                }
            }
        }
        ElementFilter.methodsIn(annotatedElements).forEach { element ->
            val parsedFactoryTypes = with(factoryFromMethodAnnotationParser) { element.parse() }
            for (factoryType in parsedFactoryTypes) {
                if (!factoryType.disabled) {
                    factoryTypes.add(factoryType)
                }
            }
        }

        factoryTypes.sortBy { factoryName(it) }

        val codeWriters = mutableListOf<CodeWriter>()
        factoryTypes.forEach { factoryType ->
            codeWriters.add(factoryTypeCodeGenerator.generateFrom(factoryType))
            codeWriters.add(factoryIndexCodeGenerator.generateFrom(factoryType))
        }

        codeWriters.forEach { codeWriter ->
            codeWriter.writeInto(env.filer)
        }

        return true
    }
}

private fun factoryName(factoryType: FactoryType): String = factoryType.factoryType.simpleName()
