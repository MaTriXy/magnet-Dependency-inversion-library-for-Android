package magnet.processor.instances.aspects.factory

import com.squareup.javapoet.TypeName
import magnet.processor.instances.parser.AttributeParser
import magnet.processor.instances.parser.ParserInstance
import javax.lang.model.element.AnnotationValue
import javax.lang.model.element.Element

object FactoryAttributeParser : AttributeParser("factory") {
    override fun <E : Element> Scope<E>.parse(value: AnnotationValue): ParserInstance<E> =
        instance.copy(factory = parseFactoryType(value))

    private fun <E : Element> Scope<E>.parseFactoryType(value: AnnotationValue): TypeName? =
        TypeName.get(env.annotation.getTypeElement(value).asType())
}
