/*
 * Copyright (C) 2018-2021 Sergej Shafarenka, www.halfbit.de
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package magnet.processor.instances.aspects.type

import com.squareup.javapoet.ClassName
import magnet.Instance
import magnet.Scoping
import magnet.processor.MagnetProcessorEnv
import magnet.processor.common.throwCompilationError
import magnet.processor.common.throwValidationError
import magnet.processor.instances.parser.AspectValidator
import magnet.processor.instances.parser.ParserInstance
import javax.lang.model.element.Element
import javax.lang.model.element.TypeElement

object TypeAndTypesValidator : AspectValidator {
    override fun <E : Element> ParserInstance<E>.validate(
        env: MagnetProcessorEnv
    ): ParserInstance<E> {
        val elementWithType =
            if (element is TypeElement && declaredType == null && declaredTypes.isNullOrEmpty())
                copy(declaredType = element.autodetectType()) else this
        return elementWithType.validateTypes()
    }

    private fun <E : Element> ParserInstance<E>.validateTypes(): ParserInstance<E> {
        val isTypeDeclared = declaredType != null
        val areTypesDeclared = declaredTypes?.isNotEmpty() ?: false

        if (isTypeDeclared && areTypesDeclared)
            element.throwValidationError(
                "${Instance::class.java} must declare either 'type' or 'types' property, not both."
            )

        if (declaredType != null) {
            val types = arrayListOf(declaredType)
            return copy(
                declaredTypes = arrayListOf(declaredType),
                types = types.map { ClassName.get(it) }
            )
        }

        if (declaredTypes != null) {
            if (scoping == Scoping.UNSCOPED.name)
                element.throwValidationError(
                    "types() property must be used with scoped instances only. Set " +
                        "scoping to Scoping.DIRECT or Scoping.TOPMOST."
                )
            return copy(
                types = declaredTypes.map { ClassName.get(it) }
            )
        }

        element.throwCompilationError("Cannot verify type declaration.")
    }

    private fun TypeElement.autodetectType(): TypeElement {
        if (interfaces.isEmpty()) {
            val superType = ClassName.get(superclass)
            if (superType is ClassName && superType.reflectionName() == "java.lang.Object") {
                return this
            }
        }
        throwValidationError(
            "${Instance::class.java} must declare either 'type' or 'types' property."
        )
    }
}
