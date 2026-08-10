package org.example.tea_screen_utils

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
import com.intellij.psi.util.PsiTreeUtil

/**
 * Находит все PSI-элементы, относящиеся к заданному Android-экрану, и сопоставляет
 * их с новыми именами согласно [newName].
 *
 * Стратегия разрешения имени
 * --------------------------
 * resolveScreenName() анализирует кликнутый элемент без необходимости наличия
 * Kotlin-плагина в compile classpath (использует [PsiNameIdentifierOwner] вместо
 * KtClassOrObject):
 *
 *   - PsiClass (или обёртка KtLightClass)  → отрезать известный суффикс
 *   - PsiFile (XML-лейаут или Kotlin-файл) → эвристика по имени файла
 *   - PsiDirectory                         → заглянуть в имена вложенных файлов
 *   - Всё остальное                        → подняться до PsiNameIdentifierOwner
 *
 * findScreenElements() использует PsiShortNamesCache + FilenameIndex для поиска всех
 * связанных артефактов и возвращает map { элемент → новое имя }.
 */
object ScreenElementsFinder {

    /** Суффиксы, идентифицирующие класс верхнего уровня экрана. */
    private val TOP_LEVEL_SUFFIXES = listOf(
        "Fragment", "Presenter", "Contract",
        "Params", "Mapper", "MapperImpl", "Adapter", "DI"
    )

    // ─────────────────────────────────────────────────────────────────────────
    // Разрешение имени экрана
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Возвращает имя экрана в PascalCase, определённое по [element], или null,
     * если элемент не является распознанным артефактом экрана.
     *
     * Работает как с Java PSI, так и с Kotlin PSI (Kotlin-плагин не обязателен
     * в compile classpath — навигация через [PsiNameIdentifierOwner]).
     */
    fun resolveScreenName(element: PsiElement): String? {
        // Быстрый путь: хорошо известные типы PSI
        if (element is PsiClass) return resolveFromPsiClass(element)
        if (element is PsiDirectory) return resolveFromDirectory(element)
        if (element is PsiFile) return resolveFromFile(element)

        // Kotlin/generic-элементы: подняться до ближайшего именованного объявления
        // (PsiNameIdentifierOwner реализован KtClassOrObject, KtFunction, PsiClass, …)
        val named = PsiTreeUtil.getParentOfType(element, PsiNameIdentifierOwner::class.java)
        if (named != null) {
            // Если это оказался PsiClass (например, Java/KtLight-класс) — обработать напрямую
            if (named is PsiClass) return resolveFromPsiClass(named)
            // Иначе откатиться к строке имени (работает для Kotlin-классов без плагина)
            return resolveFromName(named.name, named)
        }

        // Последний вариант: использовать имя содержащего файла
        return element.containingFile?.let { resolveFromFile(it) }
    }

    private fun resolveFromPsiClass(cls: PsiClass): String? {
        val name = cls.name ?: return null

        // Внутренний класс DI-класса: SomeScreenModule / SomeScreenComponent
        val parent = cls.containingClass
        if (parent?.name?.endsWith("DI") == true) {
            return parent.name?.removeSuffix("DI")?.takeIf { it.isNotEmpty() }
        }

        // Совпадение по суффиксу верхнего уровня
        TOP_LEVEL_SUFFIXES.forEach { suffix ->
            if (name.endsWith(suffix) && name.length > suffix.length)
                return name.removeSuffix(suffix)
        }
        return null
    }

    /**
     * Разрешает имя экрана из именованного элемента, который НЕ является [PsiClass]
     * (например, Kotlin-класс, доступный без Kotlin-плагина в classpath).
     */
    private fun resolveFromName(name: String?, owner: PsiNameIdentifierOwner): String? {
        if (name == null) return null

        // Проверка: находится ли внутри DI-класса
        val parentNamed = PsiTreeUtil.getParentOfType(owner, PsiNameIdentifierOwner::class.java)
        if (parentNamed?.name?.endsWith("DI") == true) {
            return parentNamed.name?.removeSuffix("DI")?.takeIf { it.isNotEmpty() }
        }

        TOP_LEVEL_SUFFIXES.forEach { suffix ->
            if (name.endsWith(suffix) && name.length > suffix.length)
                return name.removeSuffix(suffix)
        }
        return null
    }

    private fun resolveFromFile(file: PsiFile): String? {
        val name = file.name
        // XML-лейаут: fragment_some_screen.xml → SomeScreen
        if (name.startsWith("fragment_") && name.endsWith(".xml")) {
            val snake = name.removePrefix("fragment_").removeSuffix(".xml")
            return ScreenNameUtils.snakeToPascal(snake)
        }
        // Kotlin-файл: SomeScreenFragment.kt → SomeScreen
        if (name.endsWith(".kt")) {
            val base = name.removeSuffix(".kt")
            TOP_LEVEL_SUFFIXES.forEach { suffix ->
                if (base.endsWith(suffix) && base.length > suffix.length)
                    return base.removeSuffix(suffix)
            }
        }
        return null
    }

    private fun resolveFromDirectory(dir: PsiDirectory): String? =
        dir.files.firstNotNullOfOrNull { resolveFromFile(it) }

    // ─────────────────────────────────────────────────────────────────────────
    // Обнаружение элементов
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Возвращает map { PsiNamedElement → новое имя } для каждого найденного
     * артефакта, принадлежащего экрану с именем [oldName].
     *
     * Элементы добавляются в порядке переименования:
     *   1. Классы верхнего уровня Kotlin/Java (Fragment, Presenter, …)
     *   2. Внутренние классы (Module, Component внутри DI)
     *   3. XML-файл лейаута
     *   4. Директория/пакет экрана (последним — переименовывается после классов,
     *      чтобы PSI-поиск по короткому имени работал на шагах 1–3)
     *
     * Запись экрана в «Screens.kt» отдельно не ищется: с текущей навигацией
     * (`wrapScreen`/`wrapScreenWithParams`) это просто вызов `OldNameFragment.getScreen(...)`
     * внутри функции вложенного feature-object'а — обычное использование класса Fragment,
     * а не отдельная декларация. RenameProcessor для класса Fragment (шаг 1) находит
     * и обновляет и эту ссылку через обычный usage-search.
     */
    fun findScreenElements(
        project: Project,
        oldName: String,
        newName: String
    ): MutableMap<PsiNamedElement, String> {
        val result = mutableMapOf<PsiNamedElement, String>()
        val scope = GlobalSearchScope.projectScope(project)
        val cache = PsiShortNamesCache.getInstance(project)

        // 1. Классы верхнего уровня экрана
        TOP_LEVEL_SUFFIXES.forEach { suffix ->
            val className = "$oldName$suffix"
            val found = cache.getClassesByName(className, scope)
            found.forEach { cls -> result[cls] = "$newName$suffix" }

            // Запасной вариант: если индекс PSI устарел или класс индексируется
            // иначе (например, Kotlin-сгенерированные light-классы не всегда
            // присутствуют в PsiShortNamesCache) — ищем файл по имени и извлекаем
            // основное объявление напрямую.
            if (found.isEmpty()) {
                FilenameIndex.getFilesByName(project, "$className.kt", scope)
                    .forEach { psiFile ->
                        psiFile.children
                            .filterIsInstance<PsiNameIdentifierOwner>()
                            .firstOrNull { it.name == className }
                            ?.let { decl ->
                                if (decl is PsiNamedElement) result[decl] = "$newName$suffix"
                            }
                    }
            }
        }

        // 2. Внутренние классы: SomeScreenModule, SomeScreenComponent
        listOf("Module", "Component").forEach { suffix ->
            cache.getClassesByName("$oldName$suffix", scope).forEach { cls ->
                result[cls] = "$newName$suffix"
            }
        }

        // 3. Файл лейаута: fragment_old_screen.xml
        val layoutFileName = ScreenNameUtils.toLayoutFileName(oldName)
        FilenameIndex.getFilesByName(project, layoutFileName, scope).forEach { psiFile ->
            result[psiFile] = ScreenNameUtils.toLayoutFileName(newName)
        }

        // 3б. View Binding-класс, сгенерированный из лейаута (Fragment${oldName}Binding).
        //     Добавление его сюда заставляет RenameProcessor обновить все ссылки в исходниках
        //     (например, вызов inflate() во Fragment). Сам сгенерированный файл класса исключён
        //     из шага физического переименования в MultiRenameProcessor (проверка пути build/)
        //     и будет пересоздан AGP после переименования лейаута.
        val bindingClassName = ScreenNameUtils.toBindingClassName(oldName)
        cache.getClassesByName(bindingClassName, scope).forEach { cls ->
            result[cls] = ScreenNameUtils.toBindingClassName(newName)
        }

        // 4. Директория пакета экрана — добавляется последней, чтобы переименование классов
        //    выполнилось первым. RenameProcessor для PsiDirectory запускает полное переименование
        //    пакета, обновляя все объявления «package» и инструкции import.
        findScreenDirectory(project, oldName)?.let { dir ->
            result[dir] = ScreenNameUtils.toFolderName(newName)
        }

        return result
    }

    /**
     * Возвращает [PsiDirectory], содержащую основной класс Fragment для [screenName].
     */
    fun findScreenDirectory(project: Project, screenName: String): PsiDirectory? {
        val scope = GlobalSearchScope.projectScope(project)
        return PsiShortNamesCache.getInstance(project)
            .getClassesByName("${screenName}Fragment", scope)
            .firstOrNull()
            ?.containingFile
            ?.containingDirectory
    }
}
