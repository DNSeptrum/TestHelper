package com.pro.testhelper

import com.intellij.ide.highlighter.JavaFileType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.*
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil

class GenerateTestClassSkeletonAction : AnAction() {
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val psiFile = event.getData(CommonDataKeys.PSI_FILE) ?: return
        val psiClass = PsiTreeUtil.findChildOfType(psiFile, PsiClass::class.java) ?: return

        WriteCommandAction.runWriteCommandAction(project) {
            val testClassName = "${psiClass.name}Test"
            val psiDirectory = psiFile.containingDirectory ?: return@runWriteCommandAction

            // Sprawdzenie, czy klasa testowa już istnieje
            if (checkIfTestClassExists(psiDirectory, testClassName)) return@runWriteCommandAction

            // Znalezienie source root
            val sourceRoot = findSourceRoot(psiClass) ?: return@runWriteCommandAction

            // Tworzenie klasy testowej
            val testClassFile = createTestClass(testClassName, project, psiDirectory, psiClass, sourceRoot)

            // Przenoszenie do katalogu testowego
            moveToTestDirectory(project, testClassFile, psiClass)

            // Automatyczne formatowanie pliku
            formatFile(testClassFile, project)
        }
    }

    /**
     * Sprawdza, czy klasa testowa o podanej nazwie już istnieje.
     */
    private fun checkIfTestClassExists(psiDirectory: PsiDirectory, testClassName: String): Boolean {
        val existingFile = psiDirectory.findFile("$testClassName.java")
        if (existingFile != null) {
            CustomLogger.logger.info("Test class $testClassName already exists.")
            return true
        }
        return false
    }

    /**
     * Znajduje source root dla podanej klasy.
     */
    private fun findSourceRoot(psiClass: PsiClass): VirtualFile? {
        val sourceRoot = ModuleRootManager.getInstance(ModuleUtilCore.findModuleForPsiElement(psiClass)!!)
            .sourceRoots
            .find { it.path.endsWith("/src/main/java") }

        if (sourceRoot == null) {
            CustomLogger.logger.warning("Source root not found for class: ${psiClass.name}")
        }
        return sourceRoot
    }

    /**
     * Automatyczne formatowanie pliku.
     */
    private fun formatFile(psiFile: PsiFile, project: Project) {
        val codeStyleManager = CodeStyleManager.getInstance(project)
        codeStyleManager.reformat(psiFile)
        CustomLogger.logger.info("Formatted file: ${psiFile.name}")
    }

    /**
     * Przenosi wygenerowaną klasę testową do katalogu testowego.
     */
    private fun moveToTestDirectory(project: Project, generatedFile: PsiFile, psiClass: PsiClass) {
        val module = ModuleUtilCore.findModuleForFile(psiClass.containingFile.virtualFile, project) ?: return
        val testSourceRoot = ModuleRootManager.getInstance(module)
            .sourceRoots
            .find { it.path.endsWith("/src/test/java") }

        if (testSourceRoot == null) {
            CustomLogger.logger.warning("Test source root not found.")
            return
        }

        val testDirectory = createTestDirectoryStructure(testSourceRoot, psiClass, project) ?: return
        testDirectory.add(generatedFile)
        CustomLogger.logger.info("Class ${generatedFile.name} successfully added to ${testDirectory.virtualFile.path}")
    }

    /**
     * Tworzy strukturę katalogów dla katalogu testowego.
     */
    private fun createTestDirectoryStructure(
        testSourceRoot: VirtualFile,
        psiClass: PsiClass,
        project: Project
    ): PsiDirectory? {
        val packageName = (psiClass.containingFile as PsiJavaFile).packageName
        val testDirectory = PsiManager.getInstance(project).findDirectory(testSourceRoot) ?: return null

        var targetDirectory: PsiDirectory? = testDirectory
        packageName.split('.').forEach { segment ->
            targetDirectory = targetDirectory?.findSubdirectory(segment)
                ?: targetDirectory?.createSubdirectory(segment)
        }
        return targetDirectory
    }

    /**
     * Tworzy szkielet klasy testowej.
     */
    private fun createTestClass(
        testClassName: String,
        project: Project,
        psiDirectory: PsiDirectory,
        psiClassUnderTest: PsiClass,
        sourceRoot: VirtualFile
    ): PsiFile {
        val packageName = JavaDirectoryService.getInstance().getPackage(psiDirectory)?.qualifiedName ?: ""

        // Znajdź zależności do zamockowania
        val dependencies = findAllDependenciesToMock(psiClassUnderTest, sourceRoot)
        val mocks = generateMocks(dependencies)

        // Znajdź zależności statyczne do zamockowania
        val staticMocks = findAllDependenciesToMock(psiClassUnderTest, sourceRoot)
        val staticMockDeclarations = generateStaticMockDeclarations(staticMocks)
        val staticMockInitialization = generateStaticMockInitialization(staticMocks)

        // Generowanie zawartości klasy testowej
        val testClassContent = """
        package $packageName;

        import static org.assertj.core.api.Assertions.*;
        import org.junit.jupiter.api.Test;
        import org.junit.jupiter.api.AfterEach;
        import org.junit.jupiter.api.BeforeEach;
        import org.junit.jupiter.api.extension.ExtendWith;
        import org.mockito.MockedStatic;
        import org.mockito.Mockito;
        import org.mockito.Mock;
        import org.mockito.junit.jupiter.MockitoExtension;

        @ExtendWith(MockitoExtension.class)
        class $testClassName {

        $mocks

        $staticMockDeclarations

        @BeforeEach
        void init() {
            $staticMockInitialization
        }

        @AfterEach
        void cleanUp() {
            ${staticMocks.joinToString("\n") { "${it.split('.').last().uppercase()}.close();" }}
        }

        @Test
        void testExample() {
            int result = 0;
            assertThat(result).isEqualTo(0);
        }
        }
        """.trimIndent()

        return PsiFileFactory.getInstance(project)
            .createFileFromText("$testClassName.java", JavaFileType.INSTANCE, testClassContent)
    }

    /**
     * Znajduje wszystkie zależności do zamockowania.
     */
    private fun findAllDependenciesToMock(psiClass: PsiClass, sourceRoot: VirtualFile): Set<String> {
        val fieldDependencies = findDependenciesToMockInFields(psiClass, sourceRoot)
        val methodDependencies = findDependenciesToMockInMethods(psiClass, sourceRoot)
        val parameterDependencies = findDependenciesToMockInParameters(psiClass, sourceRoot)
        return fieldDependencies + methodDependencies + parameterDependencies
    }

    private fun findDependenciesToMockInFields(psiClass: PsiClass, sourceRoot: VirtualFile): Set<String> {
        return psiClass.fields.mapNotNullTo(mutableSetOf()) { field ->
            val fieldType = field.type.canonicalText
            if (isClassInSourceRoot(fieldType, psiClass.project, sourceRoot)) fieldType else null
        }
    }

    private fun findDependenciesToMockInMethods(psiClass: PsiClass, sourceRoot: VirtualFile): Set<String> {
        return psiClass.methods.mapNotNullTo(mutableSetOf()) { method ->
            val returnType = method.returnType?.canonicalText
            if (returnType != null && isClassInSourceRoot(returnType, psiClass.project, sourceRoot)) returnType else null
        }
    }

    private fun findDependenciesToMockInParameters(psiClass: PsiClass, sourceRoot: VirtualFile): Set<String> {
        return psiClass.methods.flatMapTo(mutableSetOf()) { method ->
            method.parameterList.parameters.mapNotNull { parameter ->
                val parameterType = parameter.type.canonicalText
                if (isClassInSourceRoot(parameterType, psiClass.project, sourceRoot)) parameterType else null
            }
        }
    }

    private fun isClassInSourceRoot(classQualifiedName: String, project: Project, sourceRoot: VirtualFile): Boolean {
        val psiClass = JavaPsiFacade.getInstance(project).findClass(classQualifiedName, GlobalSearchScope.projectScope(project))
        return psiClass?.containingFile?.virtualFile?.path?.startsWith(sourceRoot.path) == true
    }

    private fun generateMocks(dependencies: Set<String>): String {
        return dependencies.joinToString("\n") { dependency ->
            "@Mock\nprivate ${dependency.split('.').last()} ${dependency.split('.').last().replaceFirstChar { it.lowercase() }};"
        }
    }

    private fun generateStaticMockDeclarations(staticMocks: Set<String>): String {
        return staticMocks.joinToString("\n") { staticMock ->
            "private MockedStatic<${staticMock.split('.').last()}> ${staticMock.split('.').last().uppercase()};"
        }
    }

    private fun generateStaticMockInitialization(staticMocks: Set<String>): String {
        return staticMocks.joinToString("\n") { staticMock ->
            "${staticMock.split('.').last().uppercase()} = Mockito.mockStatic(${staticMock.split('.').last()}.class);"
        }
    }
}