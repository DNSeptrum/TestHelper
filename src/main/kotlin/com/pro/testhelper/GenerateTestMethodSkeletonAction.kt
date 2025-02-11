package com.pro.testhelper

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import groovy.util.logging.Slf4j
import java.util.*
import javax.swing.JOptionPane

@Slf4j
class GenerateTestMethodSkeletonAction : AnAction() {

    private val mockArgumentsMap =
        mutableMapOf<String, MutableMap<String, String>>() // Mapowanie: MockField -> Method -> Arguments

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val psiFile = event.dataContext.getData(com.intellij.openapi.actionSystem.CommonDataKeys.PSI_FILE)
        val psiClass = PsiTreeUtil.findChildOfType(psiFile, PsiClass::class.java) ?: return

        if (!psiClass.name!!.endsWith("Test")) {
            CustomLogger.logger.warning("Selected class is not a test class.")
            return
        }

        val classNameUnderTest = psiClass.name!!.removeSuffix("Test")
        val qualifiedNameUnderTest = "${psiClass.qualifiedName?.substringBeforeLast('.')}.$classNameUnderTest"
        val psiClassUnderTest =
            JavaPsiFacade.getInstance(project).findClass(qualifiedNameUnderTest, GlobalSearchScope.allScope(project))

        if (psiClassUnderTest == null) {
            CustomLogger.logger.warning("Could not find the class under test: $classNameUnderTest")
            return
        }

        val factory = JavaPsiFacade.getElementFactory(project)
        val methods = psiClassUnderTest.methods

        if (methods.isEmpty()) {
            CustomLogger.logger.warning("No methods available for generating test templates.")
            return
        }

        // Wybór metody przez użytkownika
        val chosenMethod = showMethodSelectionDialog(methods + psiClassUnderTest.constructors)
        if (chosenMethod != null) {

            val sourceRoot = ModuleRootManager.getInstance(ModuleUtilCore.findModuleForPsiElement(psiClassUnderTest)!!)
                .sourceRoots
                .find { it.path.endsWith("/src/main/java") } ?: return

            WriteCommandAction.runWriteCommandAction(project) {
                if (chosenMethod.isConstructor) {
                    // Wywołanie metody do generowania testu dla konstruktora
                    val constructorTestMethod = createConstructorTestMethod(
                        factory,
                        chosenMethod,
                        psiClass,
                        classNameUnderTest
                    )
                    psiClass.add(constructorTestMethod)
                    CustomLogger.logger.info("Constructor test method '${constructorTestMethod.name}' added to class ${psiClass.name}.")
                } else {

                    val dependenciesToMock = findDependenciesToMock(chosenMethod, psiClassUnderTest, sourceRoot)

                    val chosenConstructor = if (psiClassUnderTest.constructors.isNotEmpty()) {
                        showConstructorSelectionDialog(psiClassUnderTest.constructors)
                    } else null

                    val argumentsForConstructor = chosenConstructor?.let {
                        showArgumentInputDialog(it.parameterList.parameters, "konstruktor", dependenciesToMock)
                    } ?: emptyList()

                    // Wywołanie standardowej metody do generowania testu dla metody

                    val argumentsForMethod = showArgumentInputDialog(
                        chosenMethod.parameterList.parameters,
                        "metoda",
                        dependenciesToMock
                    )
                    val testMethod = createTestMethod(
                        factory,
                        chosenMethod,
                        psiClass,
                        classNameUnderTest,
                        argumentsForConstructor,
                        argumentsForMethod,
                        dependenciesToMock,
                        chosenConstructor
                    )

                    psiClass.add(testMethod)
                    CustomLogger.logger.info("Test method '${testMethod.name}' added to class ${psiClass.name}.")
                }
            }
        }
    }

    /**
     * Znajduje wszystkie zależności używane w podanej metodzie.
     */
    private fun findDependenciesToMock(
        method: PsiMethod,
        psiClass: PsiClass,
        sourceRoot: VirtualFile
    ): Map<String, Map<String, List<String>>> {
        val project = psiClass.project
        val dependencies = mutableMapOf<String, MutableMap<String, MutableList<String>>>()

        method.body?.statements?.forEach { statement ->
            val localDependencies = findMethodsCalledInStatement(statement, project, sourceRoot)
            localDependencies.forEach { (qualifiedName, methods) ->
                methods.forEach { calledMethod ->
                    val isStatic = isMethodStatic(qualifiedName, calledMethod, project)
                    val methodType = if (isStatic) "static" else "nonStatic"
                    dependencies.computeIfAbsent(qualifiedName) { mutableMapOf() }
                        .computeIfAbsent(methodType) { mutableListOf() }
                        .add(calledMethod)
                }
            }
        }

        CustomLogger.logger.info("Dependencies found: $dependencies")
        return dependencies
    }

    /**
     * Sprawdza, czy metoda jest statyczna.
     */
    private fun isMethodStatic(classQualifiedName: String, methodName: String, project: Project): Boolean {
        val psiClass =
            JavaPsiFacade.getInstance(project).findClass(classQualifiedName, GlobalSearchScope.projectScope(project))
        val method = psiClass?.findMethodsByName(methodName, true)?.firstOrNull()
        return method?.hasModifierProperty(PsiModifier.STATIC) == true
    }

    /**
     * Znajduje metody wywoływane w pojedynczym wyrażeniu.
     */
    private fun findMethodsCalledInStatement(
        statement: PsiStatement,
        project: Project,
        sourceRoot: VirtualFile
    ): Map<String, List<String>> {
        val dependencies = mutableMapOf<String, MutableList<String>>()

        // Szukanie wywołań metod w wyrażeniu
        val methodCallExpressions = PsiTreeUtil.findChildrenOfType(statement, PsiMethodCallExpression::class.java)
        methodCallExpressions.forEach { methodCall ->
            val resolvedMethod = methodCall.resolveMethod()
            CustomLogger.logger.info("resolvedMethod: $resolvedMethod")
            val containingClass = resolvedMethod?.containingClass
            CustomLogger.logger.info("containingClass: $containingClass")

            CustomLogger.logger.info("method: $methodCall")
            val methodExpression = methodCall.methodExpression
            val qualifierExpression = methodExpression.qualifierExpression
            val methodName = methodExpression.referenceName

            if (methodName != null) {
                CustomLogger.logger.info("methodName: $methodName")
                var qualifiedName: String? = if (qualifierExpression != null) {
                    // Jeśli jest qualifier, sprawdzamy jego typ
                    CustomLogger.logger.info("qualifierExpression: $qualifierExpression")
                    qualifierExpression.type?.canonicalText
                } else {
                    // Brak qualifiera oznacza, że to metoda statyczna w kontekście klasy
                    val resolvedMethod = methodCall.resolveMethod()
                    CustomLogger.logger.info("resolvedMethod: $resolvedMethod")
                    val containingClass = resolvedMethod?.containingClass
                    CustomLogger.logger.info("containingClass: $containingClass")
                    if (containingClass != null) {
                        containingClass.qualifiedName?.also {
                            CustomLogger.logger.info("Qualified name resolved: $it")
                        }
                    } else {
                        null
                    }
                }


                val name: String? = if (qualifierExpression != null) {
                    val resolvedElement = (qualifierExpression as? PsiReference)?.resolve()
                    if (resolvedElement is PsiClass) {
                        resolvedElement.qualifiedName
                    } else {
                        qualifierExpression.type?.canonicalText
                    }
                } else {
                    null
                }
                CustomLogger.logger.info("name: $name")

                if (qualifiedName == null) qualifiedName = name
                if (qualifiedName != null && isClassInSourceRoot(qualifiedName, project, sourceRoot)) {
                    CustomLogger.logger.info("Found qualified name: $qualifiedName for method: $methodName")
                    dependencies.computeIfAbsent(qualifiedName) { mutableListOf() }.add(methodName)
                } else {
                    CustomLogger.logger.info("Skipping method $methodName due to missing or invalid qualified name.")
                }
            }
        }

        CustomLogger.logger.info("dependencies: $dependencies")
        return dependencies
    }

    /**
     * Sprawdza, czy klasa znajduje się w określonym źródle.
     */
    private fun isClassInSourceRoot(classQualifiedName: String, project: Project, sourceRoot: VirtualFile): Boolean {
        val psiClass =
            JavaPsiFacade.getInstance(project).findClass(classQualifiedName, GlobalSearchScope.projectScope(project))
        return psiClass?.containingFile?.virtualFile?.path?.startsWith(sourceRoot.path) == true
    }


    private fun createTestMethod(
        factory: PsiElementFactory,
        method: PsiMethod,
        psiClass: PsiClass,
        classNameUnderTest: String,
        constructorArgs: List<String>,
        methodArgs: List<String>,
        dependenciesToMock: Map<String, Map<String, List<String>>>,
        chosenConstructor: PsiMethod?
    ): PsiMethod {
        // Generowanie unikalnej nazwy dla metody testowej
        val baseMethodName =
            "test${method.name.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }}"
        val testMethodName = generateUniqueTestMethodName(baseMethodName, psiClass)
        CustomLogger.logger.info("Creating test method: $testMethodName for target method: ${method.name}")

        // Tworzenie nowej metody testowej
        val testMethod = factory.createMethod(testMethodName, PsiTypes.voidType())
        testMethod.addBefore(factory.createAnnotationFromText("@Test", testMethod), testMethod.firstChild)

        val methodBody = testMethod.body ?: return testMethod

        // Dodanie sekcji "given"
        methodBody.add(factory.createCommentFromText("// given", testMethod))
        addMockStatements(factory, methodBody, dependenciesToMock, testMethod)

        // Dodanie wywołania konstruktora testowanej klasy
        addConstructorCall(factory, methodBody, classNameUnderTest, constructorArgs, chosenConstructor)

        // Dodanie sekcji "when"
        methodBody.add(factory.createCommentFromText("// when", testMethod))
        val resultVariable = addMethodCall(factory, methodBody, method, methodArgs)

        // Dodanie sekcji "then"
        methodBody.add(factory.createCommentFromText("// then", testMethod))
        if (resultVariable != null) {
            addAssertion(factory, methodBody, resultVariable, method.returnType, testMethod)
        }

        // Dodanie sekcji "verify"
        addVerifyStatements(factory, methodBody, dependenciesToMock, testMethod)

        CustomLogger.logger.info("Successfully created test method: $testMethodName")
        return testMethod
    }

    /**
     * Dodaje instrukcje `Mockito.when` dla zależności do zamockowania w sekcji "given".
     */
    private fun addMockStatements(
        factory: PsiElementFactory,
        methodBody: PsiCodeBlock,
        dependenciesToMock: Map<String, Map<String, List<String>>>,
        testMethod: PsiMethod
    ) {
        dependenciesToMock.forEach { (dependency, methodTypes) ->
            val mockFieldName = dependency.split('.').last().replaceFirstChar { it.lowercase() }

            // Obsługa niestatycznych metod
            methodTypes["nonStatic"]?.forEach { mockMethod ->
                val args = JOptionPane.showInputDialog("Podaj argumenty dla metody $mockMethod w $mockFieldName:") ?: ""
                val returnValue =
                    JOptionPane.showInputDialog("Podaj wartość zwracaną dla metody $mockMethod w $mockFieldName:")
                        ?: "null"

                val mockStatement = "Mockito.when($mockFieldName.$mockMethod($args)).thenReturn($returnValue);"
                methodBody.add(factory.createStatementFromText(mockStatement, testMethod))

                // Zapisz argumenty dla późniejszego wykorzystania w verify
                mockArgumentsMap.computeIfAbsent(mockFieldName) { mutableMapOf() }[mockMethod] = args

                CustomLogger.logger.info("Added Mockito.when statement: $mockStatement")
            }

            // Obsługa metod statycznych
            methodTypes["static"]?.forEach { staticMethod ->
                val staticMockFieldName = dependency.split('.').last().uppercase()
                val args =
                    JOptionPane.showInputDialog("Podaj argumenty dla metody $staticMethod w $staticMockFieldName:")
                        ?: ""
                val returnValue =
                    JOptionPane.showInputDialog("Podaj wartość zwracaną dla metody $staticMethod w $staticMockFieldName:")
                        ?: "null"

                val staticMockStatement =
                    "$staticMockFieldName.when(() -> $dependency.$staticMethod($args)).thenReturn($returnValue);"
                methodBody.add(factory.createStatementFromText(staticMockStatement, testMethod))

                // Zapisz argumenty dla późniejszego wykorzystania w verify
                mockArgumentsMap.computeIfAbsent(staticMockFieldName) { mutableMapOf() }[staticMethod] = args

                CustomLogger.logger.info("Added static Mockito.when statement: $staticMockStatement")
            }
        }
    }

    /**
     * Dodaje wywołanie konstruktora testowanej klasy.
     */
    private fun addConstructorCall(
        factory: PsiElementFactory,
        methodBody: PsiCodeBlock,
        classNameUnderTest: String,
        constructorArgs: List<String>,
        chosenConstructor: PsiMethod?
    ) {
        if (chosenConstructor != null) {
            val constructorCall =
                "$classNameUnderTest tested = new $classNameUnderTest(${constructorArgs.joinToString(", ")});"
            methodBody.add(factory.createStatementFromText(constructorCall, null))
            CustomLogger.logger.info("Added constructor call: $constructorCall")
        }
    }

    /**
     * Dodaje wywołanie testowanej metody w sekcji "when".
     */
    private fun addMethodCall(
        factory: PsiElementFactory,
        methodBody: PsiCodeBlock,
        method: PsiMethod,
        methodArgs: List<String>
    ): String? {
        val methodCall = "tested.${method.name}(${methodArgs.joinToString(", ")});"

        return if (method.returnType != PsiTypes.voidType()) {
            val resultVariable = "result"
            val resultType = method.returnType?.presentableText ?: "Object"
            val methodCallStatement = "$resultType $resultVariable = $methodCall"
            methodBody.add(factory.createStatementFromText(methodCallStatement, null))
            CustomLogger.logger.info("Added method call with return type: $methodCallStatement")
            resultVariable
        } else {
            methodBody.add(factory.createStatementFromText(methodCall, null))
            CustomLogger.logger.info("Added method call without return value: $methodCall")
            null
        }
    }

    /**
     * Dodaje instrukcje `Mockito.verify` w sekcji "verify".
     */
    private fun addVerifyStatements(
        factory: PsiElementFactory,
        methodBody: PsiCodeBlock,
        dependenciesToMock: Map<String, Map<String, List<String>>>,
        testMethod: PsiMethod
    ) {
        dependenciesToMock.forEach { (dependency, methodTypes) ->
            val mockFieldName = dependency.split('.').last().replaceFirstChar { it.lowercase() }
            methodTypes["nonStatic"]?.forEach { mockMethod ->
                val args = retrieveArgumentsForMethod(mockFieldName, mockMethod)
                val verifyStatement = "Mockito.verify($mockFieldName).$mockMethod($args);"
                methodBody.add(factory.createStatementFromText(verifyStatement, testMethod))
                CustomLogger.logger.info("Added Mockito.verify statement: $verifyStatement")
            }

            methodTypes["static"]?.forEach { staticMethod ->
                val staticMockFieldName = dependency.split('.').last().uppercase()
                val args = retrieveArgumentsForMethod(staticMockFieldName, staticMethod)
                val staticVerifyStatement = "$staticMockFieldName.verify(() -> $dependency.$staticMethod($args));"
                methodBody.add(factory.createStatementFromText(staticVerifyStatement, testMethod))
                CustomLogger.logger.info("Added static Mockito.verify statement: $staticVerifyStatement")
            }
        }
    }

    /**
     * Funkcja do pobrania argumentów użytych w Mockito.when.
     */
    private fun retrieveArgumentsForMethod(
        mockFieldName: String,
        mockMethod: String
    ): String {
        val args = mockArgumentsMap[mockFieldName]?.get(mockMethod) ?: ""
        CustomLogger.logger.info("Retrieving arguments for $mockFieldName.$mockMethod: $args")
        return args
    }

    /**
     * Wyświetla dialog wyboru metody z podanych metod testowanej klasy.
     */
    private fun showMethodSelectionDialog(methods: Array<PsiMethod>): PsiMethod? {
        val methodNames = methods.map { it.name }.toTypedArray()

        val selectedMethodName = JOptionPane.showInputDialog(
            null,
            "Wybierz metodę:",
            "Generuj szablon",
            JOptionPane.QUESTION_MESSAGE,
            null,
            methodNames,
            methodNames.getOrNull(0)
        ) as String?

        CustomLogger.logger.info("Selected method: $selectedMethodName")
        return methods.find { it.name == selectedMethodName }
    }

    /**
     * Prosi użytkownika o wprowadzenie wartości dla parametrów metody lub automatycznie dopasowuje mocki.
     */
    private fun showArgumentInputDialog(
        parameters: Array<PsiParameter>,
        context: String,
        dependenciesToMock: Map<String, Map<String, List<String>>>
    ): List<String> {
        val arguments = mutableListOf<String>()

        parameters.forEach { parameter ->
            val parameterType = parameter.type.canonicalText
            val mockDependency = dependenciesToMock.entries.find { it.key == parameterType }

            if (mockDependency != null) {
                val className = mockDependency.key
                val hasNonStaticMethods = mockDependency.value["nonStatic"]?.isNotEmpty() == true
                val hasStaticMethods = mockDependency.value["static"]?.isNotEmpty() == true

                arguments.add(
                    if (hasNonStaticMethods) {
                        className.split('.').last().replaceFirstChar { it.lowercase() }
                    } else if (hasStaticMethods) {
                        className.split('.').last()
                    } else {
                        "null"
                    }
                )
            } else {
                val defaultValue = getDefaultValue(parameterType)
                val input = JOptionPane.showInputDialog(
                    null,
                    "Podaj wartość dla argumentu ($context): ${parameter.name} (${parameter.type.presentableText})",
                    defaultValue
                )
                arguments.add(input ?: defaultValue)
            }
        }

        CustomLogger.logger.info("Arguments for $context: $arguments")
        return arguments
    }

    /**
     * Generuje domyślną wartość dla typu argumentu.
     */
    private fun getDefaultValue(type: String): String {
        return when {
            type.contains("int") || type.contains("double") || type.contains("float") -> "1"
            type.contains("boolean") -> "false"
            type.contains("String") -> "\"\""
            type.contains("List") -> "new ArrayList<>()"
            type.contains("Map") -> "new HashMap<>()"
            else -> "null"
        }
    }

    /**
     * Prosi użytkownika o podanie oczekiwanego wyniku dla typu zwracanego przez metodę.
     */
    private fun requestExpectedResultFromUser(returnType: PsiType?): String? {
        val defaultValue = getDefaultValueForReturnType(returnType)

        return JOptionPane.showInputDialog(
            null,
            "Podaj wartość expectedResult (${returnType?.presentableText ?: "unknown type"}):",
            defaultValue
        )
    }

    /**
     * Zwraca domyślną wartość dla podanego typu zwracanego.
     */
    private fun getDefaultValueForReturnType(returnType: PsiType?): String {
        return when {
            returnType == null || returnType == PsiTypes.voidType() -> "null"
            returnType.canonicalText.run { equals("int") || equals("double") || equals("float") } -> "0"
            returnType.canonicalText == "boolean" -> "false"
            returnType.canonicalText == "java.lang.String" -> "\"\""
            returnType.canonicalText.startsWith("java.util.List") -> "java.util.Collections.emptyList()"
            returnType.canonicalText.startsWith("java.util.Map") -> "java.util.Collections.emptyMap()"
            else -> "null"
        }
    }

    /**
     * Wyświetla użytkownikowi listę dostępnych konstruktorów i umożliwia wybór jednego.
     */
    private fun showConstructorSelectionDialog(constructors: Array<PsiMethod>): PsiMethod? {
        val constructorSignatures = constructors.map { it.generateConstructorSignature() }.toTypedArray()

        val selectedSignature = JOptionPane.showInputDialog(
            null,
            "Wybierz konstruktor:",
            "Wybór konstruktora",
            JOptionPane.QUESTION_MESSAGE,
            null,
            constructorSignatures,
            constructorSignatures.getOrNull(0)
        ) as? String

        return constructors.find { it.generateConstructorSignature() == selectedSignature }
    }

    /**
     * Generuje unikalny identyfikator metody testowej.
     */
    private fun generateUniqueTestMethodName(baseName: String, psiClass: PsiClass): String {
        var index = 1
        var uniqueName = "${baseName}_$index"

        while (psiClass.findMethodsByName(uniqueName, false).isNotEmpty()) {
            index++
            uniqueName = "${baseName}_$index"
        }

        CustomLogger.logger.info("Generated unique test method name: $uniqueName")
        return uniqueName
    }

    /**
     * Dodaje odpowiednią asercję do metody testowej w zależności od wyboru użytkownika.
     */
    private fun addAssertion(
        factory: PsiElementFactory,
        methodBody: PsiCodeBlock,
        resultVariable: String,
        returnType: PsiType?,
        testMethod: PsiMethod
    ) {
        val assertions = arrayOf("isEqualTo", "isEqualToComparingFieldByField", "isNull", "isNotNull")

        val chosenAssertion = JOptionPane.showInputDialog(
            null,
            "Wybierz typ asercji dla wyniku:",
            "Typ asercji",
            JOptionPane.QUESTION_MESSAGE,
            null,
            assertions,
            assertions[0]
        ) as? String

        if (chosenAssertion != null) {
            when (chosenAssertion) {
                "isEqualTo", "isEqualToComparingFieldByField" -> addEqualityAssertion(
                    factory,
                    methodBody,
                    resultVariable,
                    returnType,
                    testMethod,
                    chosenAssertion
                )

                "isNull", "isNotNull" -> addNullityAssertion(
                    factory,
                    methodBody,
                    resultVariable,
                    chosenAssertion,
                    testMethod
                )

                else -> JOptionPane.showMessageDialog(
                    null,
                    "Nieznany typ asercji: $chosenAssertion",
                    "Błąd",
                    JOptionPane.ERROR_MESSAGE
                )
            }
        }
    }

    /**
     * Dodaje asercję sprawdzającą równość wyniku.
     */
    private fun addEqualityAssertion(
        factory: PsiElementFactory,
        methodBody: PsiCodeBlock,
        resultVariable: String,
        returnType: PsiType?,
        testMethod: PsiMethod,
        assertion: String
    ) {
        val expectedResult = requestExpectedResultFromUser(returnType)
        if (expectedResult != null && returnType != null) {
            methodBody.add(
                factory.createStatementFromText(
                    "${returnType.presentableText} expectedResult = $expectedResult;",
                    testMethod
                )
            )
            methodBody.add(
                factory.createStatementFromText(
                    "assertThat($resultVariable).$assertion(expectedResult);",
                    testMethod
                )
            )
            CustomLogger.logger.info("Added equality assertion: $assertion")
        }
    }

    /**
     * Dodaje asercję sprawdzającą null lub nie-null wyniku.
     */
    private fun addNullityAssertion(
        factory: PsiElementFactory,
        methodBody: PsiCodeBlock,
        resultVariable: String,
        assertion: String,
        testMethod: PsiMethod
    ) {
        methodBody.add(
            factory.createStatementFromText(
                "assertThat($resultVariable).$assertion();",
                testMethod
            )
        )
        CustomLogger.logger.info("Added nullity assertion: $assertion")
    }

    /**
     * Rozszerzenie generujące sygnaturę konstruktora.
     */
    private fun PsiMethod.generateConstructorSignature(): String {
        return parameterList.parameters.joinToString(", ", "(", ")") { "${it.type.presentableText} ${it.name}" }
    }

    /**
     * Tworzy metodę testową dla konstruktora.
     */
    private fun createConstructorTestMethod(
        factory: PsiElementFactory,
        constructor: PsiMethod,
        psiClass: PsiClass,
        classNameUnderTest: String
    ): PsiMethod {
        val baseMethodName = "testConstructor_${constructor.parameterList.parameters.size}"
        val testMethodName = generateUniqueTestMethodName(baseMethodName, psiClass)
        CustomLogger.logger.info("Creating test method: $testMethodName for constructor: $constructor")

        val testMethod = factory.createMethod(testMethodName, PsiTypes.voidType())
        testMethod.addBefore(factory.createAnnotationFromText("@Test", testMethod), testMethod.firstChild)

        val methodBody = testMethod.body ?: return testMethod

        // given
        methodBody.add(factory.createCommentFromText("// given", testMethod))

        // Znajdź zależności w parametrach konstruktora
        val dependenciesFromParameters = findDependenciesToMockInConstructor(constructor, psiClass.project)

        // Znajdź zależności wewnętrzne w ciele konstruktora
        val sourceRoot = ModuleRootManager.getInstance(ModuleUtilCore.findModuleForPsiElement(constructor)!!)
            .sourceRoots
            .find { it.path.endsWith("/src/main/java") } ?: return testMethod
        val internalDependencies = findInternalDependenciesInConstructor(constructor, psiClass.project, sourceRoot)

        // Połącz zależności z parametrów i wewnętrzne
        val allDependenciesToMock = dependenciesFromParameters.toMutableMap()
        internalDependencies.forEach { (key, value) ->
            allDependenciesToMock.merge(key, value) { oldValue, newValue ->
                oldValue.toMutableMap().apply {
                    newValue.forEach { (methodType, methods) ->
                        merge(methodType, methods) { oldMethods, newMethods -> oldMethods + newMethods }
                    }
                }
            }
        }

        // Mockowanie zależności
        addMockStatements(factory, methodBody, allDependenciesToMock, testMethod)

        // Wprowadzenie argumentów dla konstruktora
        val arguments = constructor.parameterList.parameters.map { parameter ->
            val parameterType = parameter.type.canonicalText
            dependenciesFromParameters.keys.find { it == parameterType }?.let { dependency ->
                dependency.split('.').last().replaceFirstChar { it.lowercase() }
            } ?: run {
                val defaultValue = getDefaultValue(parameterType)
                JOptionPane.showInputDialog(
                    null,
                    "Podaj wartość dla argumentu konstruktora: ${parameter.name} (${parameter.type.presentableText})",
                    defaultValue
                ) ?: defaultValue
            }
        }

        // when
        val constructorCall = "$classNameUnderTest tested = new $classNameUnderTest(${arguments.joinToString(", ")});"
        methodBody.add(factory.createStatementFromText(constructorCall, null))
        CustomLogger.logger.info("Added constructor call: $constructorCall")

        // then
        methodBody.add(factory.createCommentFromText("// then", testMethod))
        methodBody.add(factory.createStatementFromText("assertThat(tested).isNotNull();", null))
        CustomLogger.logger.info("Added default assertion for constructor test.")

        // verify
        addVerifyStatements(factory, methodBody, allDependenciesToMock, testMethod)

        CustomLogger.logger.info("Successfully created test method: $testMethodName")
        return testMethod
    }

    /**
     * Znajduje zależności wywoływane wewnątrz konstruktora, które należy zamockować.
     */
    private fun findInternalDependenciesInConstructor(
        constructor: PsiMethod,
        project: Project,
        sourceRoot: VirtualFile
    ): Map<String, Map<String, List<String>>> {
        val dependencies = mutableMapOf<String, MutableMap<String, MutableList<String>>>()

        constructor.body?.statements?.forEach { statement ->
            val internalDependencies = findMethodsCalledInStatement(statement, project, sourceRoot)
            internalDependencies.forEach { (qualifiedName, methods) ->
                methods.forEach { calledMethod ->
                    val isStatic = isMethodStatic(qualifiedName, calledMethod, project)
                    val methodType = if (isStatic) "static" else "nonStatic"
                    dependencies.computeIfAbsent(qualifiedName) { mutableMapOf() }
                        .computeIfAbsent(methodType) { mutableListOf() }
                        .add(calledMethod)
                }
            }
        }

        CustomLogger.logger.info("Internal dependencies in constructor found: $dependencies")
        return dependencies
    }

    /**
     * Znajduje zależności do zamockowania na podstawie parametrów konstruktora.
     */
    private fun findDependenciesToMockInConstructor(
        constructor: PsiMethod,
        project: Project
    ): Map<String, Map<String, List<String>>> {
        val dependencies = mutableMapOf<String, MutableMap<String, MutableList<String>>>()

        constructor.parameterList.parameters.forEach { parameter ->
            val parameterType = parameter.type.canonicalText
            val psiClass =
                JavaPsiFacade.getInstance(project).findClass(parameterType, GlobalSearchScope.allScope(project))
            psiClass?.methods?.forEach { method ->
                val methodType = if (method.hasModifierProperty(PsiModifier.STATIC)) "static" else "nonStatic"
                dependencies.computeIfAbsent(parameterType) { mutableMapOf() }
                    .computeIfAbsent(methodType) { mutableListOf() }
                    .add(method.name)
            }
        }

        CustomLogger.logger.info("Constructor dependencies found: $dependencies")
        return dependencies
    }

}