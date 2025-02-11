package com.pro.testhelper

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.search.GlobalSearchScope

class ConvertJMockitToMockitoAction : AnAction() {
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val psiFile = event.getData(CommonDataKeys.PSI_FILE) ?: return

        WriteCommandAction.runWriteCommandAction(project) {
            val psiJavaFile = psiFile as? PsiJavaFile ?: return@runWriteCommandAction
            CustomLogger.logger.info("Processing file: ${psiJavaFile.name}")

            // Modyfikacja klasy (adnotacje i zawartość)
            modifyClass(psiJavaFile, project)

            // Zamiana Expectations na Mockito.when i Verifications na Mockito.verify
            replaceExpectationsAndVerifications(psiJavaFile, project)

            // Modyfikacja importów
            modifyImports(psiJavaFile)

            // Automatyczne formatowanie pliku
            formatFile(psiJavaFile, project)

            CustomLogger.logger.info("Successfully converted ${psiFile.name} to Mockito.")
        }
    }

    /**
     * Automatyczne formatowanie pliku po zakończeniu konwersji.
     */
    private fun formatFile(psiFile: PsiFile, project: Project) {
        val codeStyleManager = CodeStyleManager.getInstance(project)
        codeStyleManager.reformat(psiFile)
        CustomLogger.logger.info("Formatted file: ${psiFile.name}")
    }

    /**
     * Modyfikacja klasy: dodanie adnotacji, zamiana @Mocked na @Mock.
     */
    private fun modifyClass(psiJavaFile: PsiJavaFile, project: Project) {
        val psiClass = psiJavaFile.classes.firstOrNull() ?: return

        CustomLogger.logger.info("Modifying class: ${psiClass.name}")

        // Dodanie @ExtendWith(MockitoExtension.class), jeśli jej brakuje
        addMockitoExtensionAnnotation(psiClass, project)

        // Zamiana adnotacji @Mocked na @Mock
        val fields = psiClass.fields.filter { it.hasAnnotation("mockit.Mocked") }
        replaceAnnotations(fields, project)
    }

    /**
     * Dodanie adnotacji @ExtendWith(MockitoExtension.class) do klasy, jeśli jeszcze jej nie ma.
     */
    private fun addMockitoExtensionAnnotation(psiClass: PsiClass, project: Project) {
        val factory = JavaPsiFacade.getElementFactory(project)

        // Sprawdź, czy adnotacja już istnieje
        val existingAnnotations = psiClass.modifierList?.annotations
        val hasMockitoExtensionAnnotation = existingAnnotations?.any { annotation ->
            annotation.qualifiedName == "org.junit.jupiter.api.extension.ExtendWith" &&
                    annotation.text.contains("MockitoExtension.class")
        } == true

        if (!hasMockitoExtensionAnnotation) {
            val mockitoExtensionAnnotation = factory.createAnnotationFromText(
                "@ExtendWith(MockitoExtension.class)", psiClass
            )
            psiClass.addBefore(mockitoExtensionAnnotation, psiClass.firstChild)
            CustomLogger.logger.info("Added @ExtendWith(MockitoExtension.class) annotation to class: ${psiClass.name}")
        } else {
            CustomLogger.logger.info("@ExtendWith(MockitoExtension.class) annotation already exists in class: ${psiClass.name}")
        }
    }

    /**
     * Zamiana @Mocked na @Mock dla wszystkich pól w klasie.
     */
    private fun replaceAnnotations(fields: List<PsiField>, project: Project) {
        fields.forEach { field ->
            val mockAnnotation = field.getAnnotation("mockit.Mocked") ?: return@forEach
            val factory = JavaPsiFacade.getElementFactory(project)

            val mockitoAnnotation = factory.createAnnotationFromText("@org.mockito.Mock", field)
            mockAnnotation.replace(mockitoAnnotation)
            CustomLogger.logger.info("Replaced @Mocked with @Mock for field: ${field.name}")
        }
    }

    /**
     * Modyfikacja importów: usunięcie nieużywanych, dodanie nowych.
     */
    private fun modifyImports(psiJavaFile: PsiJavaFile) {
        val importList = psiJavaFile.importList ?: return

        val importsToRemove = listOf(
            "mockit.Expectations",
            "mockit.Mocked",
            "mockit.Verifications"
        )
        val importsToAdd = listOf(
            "org.mockito.Mockito",
            "org.mockito.Mock",
            "org.junit.jupiter.api.extension.ExtendWith",
            "org.mockito.junit.jupiter.MockitoExtension"
        )

        val factory = JavaPsiFacade.getElementFactory(psiJavaFile.project)
        importsToAdd.forEach { importToAdd ->
            if (!importList.text.contains(importToAdd)) {
                val importClass = JavaPsiFacade.getInstance(psiJavaFile.project)
                    .findClass(importToAdd, GlobalSearchScope.allScope(psiJavaFile.project))
                if (importClass != null) {
                    val importStatement = factory.createImportStatement(importClass)
                    importList.add(importStatement)
                    CustomLogger.logger.info("Added import: $importToAdd")
                }
            }
        }

        importsToRemove.forEach { importToRemove ->
            val importStatement = importList.findSingleImportStatement(importToRemove)
            if (importStatement != null && !isImportStillUsed(psiJavaFile, importToRemove)) {
                importList.deleteChildRange(importStatement, importStatement)
                CustomLogger.logger.info("Removed unused import: $importToRemove")
            }
        }
    }

    /**
     * Sprawdza, czy dany import jest nadal używany w kodzie.
     */
    private fun isImportStillUsed(psiJavaFile: PsiJavaFile, importQualifiedName: String): Boolean {
        return psiJavaFile.text.contains(importQualifiedName.substringAfterLast('.'))
    }

    /**
     * Zamiana Expectations i Verifications na odpowiednie wyrażenia Mockito.
     */
    private fun replaceExpectationsAndVerifications(psiJavaFile: PsiJavaFile, project: Project) {
        val factory = JavaPsiFacade.getElementFactory(project)

        psiJavaFile.classes.forEach { psiClass ->
            psiClass.methods.forEach { method ->
                val methodBody = method.body ?: return@forEach

                val statements = methodBody.statements.toList()
                statements.forEach { statement ->
                    val statementText = statement.text

                    // Zamiana Expectations na Mockito.when
                    if (statementText.contains("new Expectations")) {
                        processExpectations(statement, factory, method)
                    }

                    // Zamiana Verifications na Mockito.verify
                    if (statementText.contains("new Verifications")) {
                        processVerifications(statement, factory, method)
                    }
                }
            }
        }
    }

    /**
     * Procesuje blok Expectations i zamienia na odpowiednie wywołania Mockito.when.
     */
    private fun processExpectations(statement: PsiStatement, factory: PsiElementFactory, method: PsiMethod) {
        val statementText = statement.text
        CustomLogger.logger.info("Found Expectations in method: ${method.name}")

        val expectationBlockMatch = Regex("new Expectations\\s*\\(\\s*\\)\\s*\\{\\{(.*?)\\}\\}", RegexOption.DOT_MATCHES_ALL)
            .find(statementText)

        if (expectationBlockMatch != null) {
            val expectationBody = expectationBlockMatch.groupValues[1].trim()

            val lines = expectationBody.lines().map { it.trim() }.filter { it.isNotEmpty() }

            val mockitoStatements = lines.chunked(2).mapNotNull { chunk ->
                if (chunk.size == 2) {
                    val methodCall = chunk[0].removeSuffix(";")
                    val resultValue = chunk[1].substringAfter("=").removeSuffix(";").trim()
                    "Mockito.when($methodCall).thenReturn($resultValue);"
                } else null
            }

            mockitoStatements.forEach { mockitoStatement ->
                method.body?.add(factory.createStatementFromText(mockitoStatement, null))
            }

            statement.delete()
            CustomLogger.logger.info("Replaced Expectations with Mockito.when in method: ${method.name}")
        }
    }

    /**
     * Procesuje blok Verifications i zamienia na odpowiednie wywołania Mockito.verify.
     */
    private fun processVerifications(statement: PsiStatement, factory: PsiElementFactory, method: PsiMethod) {
        val statementText = statement.text
        CustomLogger.logger.info("Found Verifications in method: ${method.name}")

        val verificationsBlockMatch = Regex("""new Verifications\s*\(\s*\)\s*\{\{\s*(.*?)\s*\}\}""", RegexOption.DOT_MATCHES_ALL)
            .find(statementText)

        if (verificationsBlockMatch != null) {
            val verificationsBody = verificationsBlockMatch.groupValues[1].trim()
            val lines = verificationsBody.lines().map { it.trim() }.filter { it.isNotEmpty() }

            lines.forEach { line ->
                if (line.endsWith(";")) {
                    val methodCall = line.removeSuffix(";")
                    val verifyStatement = "Mockito.verify($methodCall);"
                    method.body?.add(factory.createStatementFromText(verifyStatement, null))
                }
            }

            statement.delete()
            CustomLogger.logger.info("Replaced Verifications with Mockito.verify in method: ${method.name}")
        }
    }
}