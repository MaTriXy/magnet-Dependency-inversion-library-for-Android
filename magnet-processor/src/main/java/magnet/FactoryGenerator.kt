/*
 * Copyright (C) 2018 Sergej Shafarenka, www.halfbit.de
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

package magnet

import com.squareup.javapoet.ClassName
import com.squareup.javapoet.CodeBlock
import com.squareup.javapoet.JavaFile
import com.squareup.javapoet.MethodSpec
import com.squareup.javapoet.ParameterSpec
import com.squareup.javapoet.ParameterizedTypeName
import com.squareup.javapoet.TypeName
import com.squareup.javapoet.TypeSpec
import javax.lang.model.element.Modifier
import javax.lang.model.element.TypeElement
import javax.lang.model.type.TypeKind
import javax.lang.model.util.ElementFilter

private const val CLASS_NULLABLE = ".Nullable"
private const val PARAM_SCOPE = "scope"
private const val METHOD_GET_OPTIONAL = "getOptional"
private const val METHOD_GET_SINGLE = "getSingle"

class FactoryGenerator {

    private lateinit var env: MagnetProcessorEnv

    fun generate(implTypeElement: TypeElement, env: MagnetProcessorEnv) {
        this.env = env
        val implClassName = ClassName.get(implTypeElement)

        implTypeElement.annotationMirrors.forEach {
            if (it.mirrors<Implementation>()) {
                var annotationValueType: String? = null
                var implIsScoped = true

                it.elementValues.entries.forEach {
                    val valueName = it.key.simpleName.toString()
                    val value = it.value.value.toString()
                    if (valueName == "type") {
                        annotationValueType = value
                    } else if (valueName == "scoped") {
                        implIsScoped = value.toBoolean()
                    }
                }

                val implType = env.elements.getTypeElement(annotationValueType)
                val isTypeImplemented = env.types.isAssignable(
                    implTypeElement.asType(),
                    env.types.getDeclaredType(implType) // we deliberately erase generic type here
                )
                if (!isTypeImplemented) {
                    env.reportError(implTypeElement, "$implTypeElement must implement $implType")
                    throw BreakGenerationException()
                }

                val implTypeClassName = ClassName.get(implType)
                val factoryTypeSpec = generateFactory(
                    implClassName,
                    implTypeClassName,
                    implTypeElement,
                    implIsScoped
                )

                val packageName = implClassName.packageName()
                JavaFile.builder(packageName, factoryTypeSpec)
                    .skipJavaLangImports(true)
                    .build()
                    .writeTo(env.filer)
            }
        }
    }

    private fun generateFactory(
        implClassName: ClassName,
        implTypeClassName: ClassName,
        implTypeElement: TypeElement,
        implIsScoped: Boolean
    ): TypeSpec {

        val factoryPackage = implClassName.packageName()
        val factoryName = "Magnet${implClassName.simpleName()}Factory"
        val factoryClassName = ClassName.bestGuess("$factoryPackage.$factoryName")

        val extensionFactorySuperInterface = ParameterizedTypeName.get(
            ClassName.get(InstanceFactory::class.java),
            implTypeClassName
        )

        return TypeSpec
            .classBuilder(factoryClassName)
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addSuperinterface(extensionFactorySuperInterface)
            .addMethod(
                generateCreateMethod(
                    implClassName,
                    implTypeClassName,
                    implTypeElement
                )
            )
            .addMethod(
                generateIsScopedMethod(
                    implIsScoped
                )
            )
            .addMethod(
                generateGetTypeMethod(
                    implTypeClassName
                )
            )
            .build()
    }

    private fun generateIsScopedMethod(
        implIsScoped: Boolean
    ): MethodSpec {
        return MethodSpec
            .methodBuilder("isScoped")
            .addModifiers(Modifier.PUBLIC)
            .addAnnotation(Override::class.java)
            .returns(TypeName.BOOLEAN)
            .addStatement("return \$L", implIsScoped)
            .build()
    }

    private fun generateGetTypeMethod(
        implTypeClassName: ClassName
    ): MethodSpec {
        return MethodSpec
            .methodBuilder("getType")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(Class::class.java)
            .addStatement("return \$T.class", implTypeClassName)
            .build()
    }

    private fun generateCreateMethod(
        implClassName: ClassName,
        implTypeClassName: ClassName,
        implTypeElement: TypeElement
    ): MethodSpec {
        val scopeClassName = ClassName.get(Scope::class.java)

        // We have following cases:
        // 1. No parameters -> empty constructor
        // 2. One or many parameters -> Scope used "as is" others are required() from scope

        val constructors = ElementFilter.constructorsIn(implTypeElement.enclosedElements)
        if (constructors.size != 1) {
            env.reportError(implTypeElement, "Exactly one constructor is required for $implTypeElement")
            throw BreakGenerationException()
        }

        val parameters = constructors[0].parameters
        val codeBlockBuilder = CodeBlock.builder()
        val methodParamsBuilder = StringBuilder()

        parameters.forEach {
            val type = it.asType()
            if (type.kind == TypeKind.TYPEVAR) {
                env.reportError(implTypeElement,
                    "Constructor parameter '${it.simpleName}' is specified using a generic type which" +
                        " is an invalid parameter type. Use a class or an interface type instead." +
                        " 'Scope' is a valid parameter type too.")
                throw BreakGenerationException()
            }

            val isScopeParam = type.toString() == Scope::class.java.name
            val paramName = if (isScopeParam) PARAM_SCOPE else it.simpleName.toString()

            if (!isScopeParam) {
                val paramClassName = ClassName.get(type)

                var hasNullableAnnotation = false
                var namedAnnotationValue: String? = null

                it.annotationMirrors.forEach { annotationMirror ->

                    if (annotationMirror.mirrors<Classifier>()) {
                        namedAnnotationValue = annotationMirror.elementValues.values.firstOrNull()?.value.toString()
                        namedAnnotationValue?.removeSurrounding("\"", "\"")

                    } else {
                        val annotationType = annotationMirror.annotationType.toString()
                        if (annotationType.endsWith(CLASS_NULLABLE)) {
                            hasNullableAnnotation = true

                        }
                    }
                }

                val getMethodName = if (hasNullableAnnotation) METHOD_GET_OPTIONAL else METHOD_GET_SINGLE

                if (namedAnnotationValue != null) {
                    codeBlockBuilder.addStatement(
                        "\$T $paramName = scope.$getMethodName(\$T.class, \$S)",
                        paramClassName,
                        paramClassName,
                        namedAnnotationValue
                    )
                } else {
                    codeBlockBuilder.addStatement(
                        "\$T $paramName = scope.$getMethodName(\$T.class)",
                        paramClassName,
                        paramClassName
                    )
                }

            }

            methodParamsBuilder.append(paramName).append(", ")
        }

        if (methodParamsBuilder.isNotEmpty()) {
            methodParamsBuilder.setLength(methodParamsBuilder.length - 2)
        }

        return MethodSpec
            .methodBuilder("create")
            .addAnnotation(Override::class.java)
            .addModifiers(Modifier.PUBLIC)
            .addParameter(ParameterSpec
                .builder(scopeClassName, PARAM_SCOPE)
                .build())
            .returns(implTypeClassName)
            .addCode(codeBlockBuilder.build())
            .addStatement("return new \$T($methodParamsBuilder)", implClassName)
            .build()
    }

}
