package org.example.tea_screen_utils

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.JavaDirectoryService
import com.intellij.psi.PsiDirectory

/**
 * Генерирует boilerplate MVP-экрана под текущую навигационную архитектуру проекта
 * (Cicerone `com.github.terrakok.cicerone` + `wrapScreen`/`wrapScreenWithParams`,
 * см. `ru.may24.app.core.ui.navigation.fragment.WrappedFragmentScreen`).
 *
 * Kotlin-файлы генерируются с табами (детект-правило `NeedToUseTabsInsteadSpaces`),
 * XML-лейауты — с обычными 4 пробелами (как форматирует Android Studio).
 */
class NewTeaScreenGenerator(
    private val project: Project,
    private val selectedDir: PsiDirectory,
    private val screenName: String,
    private val hasParams: Boolean,
    private val hasRecyclerView: Boolean,
    private val isBottomSheet: Boolean,
    private val hasTitledToolbar: Boolean
) {
    private val screenNameLower = screenName.lowercase()
    private val screenNameSnake = ScreenNameUtils.pascalToSnake(screenName)
    private val screenNameCamel = ScreenNameUtils.pascalToCamel(screenName)
    private val needsParams = hasParams
    private val parentPackage: String
    private val packageFromFolder: String

    init {
        val parentPkg = JavaDirectoryService.getInstance()
            .getPackage(selectedDir)?.qualifiedName ?: ""
        parentPackage = parentPkg
        packageFromFolder = if (parentPkg.isNotEmpty()) "$parentPkg.$screenNameLower" else screenNameLower
    }

    fun generate() {
        WriteCommandAction.runWriteCommandAction(
            project,
            "New Tea Screen '$screenName'",
            null,
            {
                // Create screen directory inside selected dir
                val screenDir = selectedDir.createSubdirectory(screenNameLower)

                // Always-created files
                createFile(screenDir, "${screenName}Fragment.kt", fragmentContent())
                createFile(screenDir, "${screenName}Contract.kt", contractContent())
                createFile(screenDir, "${screenName}Presenter.kt", presenterContent())
                createFile(screenDir, "${screenName}DI.kt", diContent())

                // RecyclerView optional files
                if (hasRecyclerView) {
                    val adapterDir = screenDir.createSubdirectory("adapter")
                    createFile(adapterDir, "${screenName}Adapter.kt", adapterContent())
                    val mapperDir = screenDir.createSubdirectory("mapper")
                    createFile(mapperDir, "${screenName}Mapper.kt", mapperContent())
                    createFile(mapperDir, "${screenName}MapperImpl.kt", mapperImplContent())
                }

                // Params optional file
                if (needsParams) {
                    val modelDir = screenDir.createSubdirectory("model")
                    createFile(modelDir, "${screenName}Params.kt", paramsContent())
                }

                // Layout XML
                createLayoutFile()

                // Modify existing project files
                modifyAppComponent()
                modifyScreens()

                // Open Fragment in editor
                screenDir.findFile("${screenName}Fragment.kt")?.virtualFile?.let { vf ->
                    FileEditorManager.getInstance(project).openFile(vf, true)
                }
            }
        )
    }

    // ─── File creation ────────────────────────────────────────────────────────

    private fun createFile(dir: PsiDirectory, name: String, content: String) {
        val vf = dir.virtualFile.createChildData(this, name)
        VfsUtil.saveText(vf, content)
    }

    private fun createLayoutFile() {
        val layoutPath = "${project.basePath}/app/src/main/res/layout"
        val layoutDir = LocalFileSystem.getInstance().findFileByPath(layoutPath)
            ?: LocalFileSystem.getInstance().refreshAndFindFileByPath(layoutPath)
            ?: return
        val vf = layoutDir.createChildData(this, "fragment_${screenNameSnake}.xml")
        VfsUtil.saveText(vf, layoutContent())
    }

    // ─── Existing file modifiers ──────────────────────────────────────────────

    private fun modifyAppComponent() {
        val path = "${project.basePath}/app/src/main/java/ru/may24/app/di/AppComponent.kt"
        val vf = LocalFileSystem.getInstance().findFileByPath(path) ?: return
        val doc = FileDocumentManager.getInstance().getDocument(vf) ?: return

        val imports = listOf(
            "import $packageFromFolder.${screenName}Component",
            "import $packageFromFolder.${screenName}Module"
        )
        val method = "\tfun plus(module: ${screenName}Module): ${screenName}Component"

        var text = doc.text
        text = addImports(text, imports)
        text = insertBeforeLastBrace(text, "\n$method\n")
        doc.replaceString(0, doc.textLength, text)
        FileDocumentManager.getInstance().saveDocument(doc)
    }

    /**
     * Добавляет фабричную функцию экрана в `Screens.kt`.
     *
     * `Screens.kt` разбит на вложенные `object`-ы по фичам (`Auth`, `Catalog`,
     * `SalesRepresentative`, …). Пытаемся найти вложенный object, чьё имя совпадает
     * (без учёта регистра) с одним из сегментов пакета экрана, и вставляем функцию
     * туда. Если совпадения нет — вставляем функцию прямо в тело `object Screens { }`,
     * на том же уровне, где уже лежат отдельно стоящие экраны вроде `MaintenanceWork`.
     *
     * Rename Screen эту запись отдельно не трогает: с новой навигацией запись — это
     * просто вызов `ScreenNameFragment.getScreen(...)`, обычное использование класса
     * Fragment, а не отдельная декларация — RenameProcessor обновит её автоматически
     * при переименовании самого Fragment-класса.
     */
    private fun modifyScreens() {
        val path = "${project.basePath}/app/src/main/java/ru/may24/app/ui/navigation/Screens.kt"
        val vf = LocalFileSystem.getInstance().findFileByPath(path) ?: return
        val doc = FileDocumentManager.getInstance().getDocument(vf) ?: return

        val imports = mutableListOf("import $packageFromFolder.${screenName}Fragment")
        if (needsParams) imports += "import $packageFromFolder.model.${screenName}Params"
        imports += if (isBottomSheet) {
            "import ru.may24.app.core.ui.navigation.BottomSheetScreen"
        } else {
            "import com.github.terrakok.cicerone.androidx.FragmentScreen"
        }

        var text = doc.text
        text = addImports(text, imports)
        text = insertScreensEntry(text)
        doc.replaceString(0, doc.textLength, text)
        FileDocumentManager.getInstance().saveDocument(doc)
    }

    // ─── Template generators ──────────────────────────────────────────────────

    private fun fragmentContent(): String = buildString {
        appendLine("package $packageFromFolder")
        appendLine()

        // Imports
        appendLine("import android.os.Bundle")
        appendLine("import android.view.LayoutInflater")
        appendLine("import android.view.View")
        appendLine("import android.view.ViewGroup")
        if (hasRecyclerView) {
            appendLine("import androidx.recyclerview.widget.LinearLayoutManager")
            appendLine("import androidx.recyclerview.widget.LinearLayoutManager.VERTICAL")
        }
        appendLine("import by.kirich1409.viewbindingdelegate.viewBinding")
        if (isBottomSheet) {
            appendLine("import ru.may24.app.core.ui.navigation.BottomSheetScreen")
        } else {
            appendLine("import com.github.terrakok.cicerone.androidx.FragmentScreen")
        }
        appendLine("import moxy.presenter.InjectPresenter")
        appendLine("import moxy.presenter.ProvidePresenter")
        appendLine("import ru.may24.app.R")
        appendLine(
            "import ru.may24.app.core.ui.navigation.fragment." +
                if (needsParams) "wrapScreenWithParams" else "wrapScreen"
        )
        appendLine("import ru.may24.app.databinding.Fragment${screenName}Binding")
        val base = if (isBottomSheet) "BaseBottomSheetFragment" else "BaseFragment"
        appendLine("import ru.may24.app.ui.fragment.base.$base")
        if (needsParams) appendLine("import $packageFromFolder.model.${screenName}Params")
        if (hasRecyclerView) {
            appendLine("import $packageFromFolder.adapter.${screenName}Adapter")
            appendLine("import ru.may24.uikit.ui.adapter.ListViewModel")
            appendLine("import ru.may24.uikit.ui.adapter.listener.ListItemClickListener")
        }
        appendLine("import javax.inject.Inject")
        appendLine("import javax.inject.Provider")
        if (hasTitledToolbar) appendLine("import ru.may24.uikit.R as UiKitR")
        appendLine("import $packageFromFolder.${screenName}Contract as Contract")
        appendLine()

        // Class declaration
        appendLine("class ${screenName}Fragment : $base(), Contract.View {")
        appendLine()
        appendLine("\t@InjectPresenter")
        appendLine("\tlateinit var presenter: Contract.Presenter")
        appendLine()
        appendLine("\t@Inject")
        appendLine("\tlateinit var presenterProvider: Provider<Contract.Presenter>")
        appendLine()
        appendLine("\tprivate val binding by viewBinding(Fragment${screenName}Binding::bind)")
        if (hasRecyclerView) {
            appendLine()
            appendLine("\t@Inject")
            appendLine("\tlateinit var adapter: ${screenName}Adapter")
        }
        appendLine()

        // companion object
        appendLine("\t//region ==================== Fragment creation ====================")
        appendLine()
        appendLine("\tcompanion object {")
        appendLine()
        val screenReturnType = if (isBottomSheet) "BottomSheetScreen" else "FragmentScreen"
        when {
            !isBottomSheet && !needsParams -> {
                appendLine("\t\tfun getScreen(): $screenReturnType {")
                appendLine("\t\t\treturn wrapScreen<${screenName}Fragment>()")
                appendLine("\t\t}")
            }
            !isBottomSheet && needsParams -> {
                appendLine("\t\tfun getScreen(params: ${screenName}Params): $screenReturnType {")
                appendLine("\t\t\treturn wrapScreenWithParams<${screenName}Fragment, ${screenName}Params>(")
                appendLine("\t\t\t\tparams = params,")
                appendLine("\t\t\t)")
                appendLine("\t\t}")
            }
            isBottomSheet && !needsParams -> {
                appendLine("\t\tfun getScreen(): $screenReturnType {")
                appendLine("\t\t\tval wrappedScreen = wrapScreen<${screenName}Fragment>()")
                appendLine("\t\t\treturn BottomSheetScreen(key = wrappedScreen.screenKey) { factory ->")
                appendLine("\t\t\t\twrappedScreen.createFragment(factory)")
                appendLine("\t\t\t}")
                appendLine("\t\t}")
            }
            else -> { // isBottomSheet && needsParams
                appendLine("\t\tfun getScreen(params: ${screenName}Params): $screenReturnType {")
                appendLine("\t\t\tval wrappedScreen = wrapScreenWithParams<${screenName}Fragment, ${screenName}Params>(")
                appendLine("\t\t\t\tparams = params,")
                appendLine("\t\t\t)")
                appendLine("\t\t\treturn BottomSheetScreen(key = wrappedScreen.screenKey) { factory ->")
                appendLine("\t\t\t\twrappedScreen.createFragment(factory)")
                appendLine("\t\t\t}")
                appendLine("\t\t}")
            }
        }
        appendLine("\t}")
        appendLine()
        appendLine("\t//endregion")
        appendLine()

        // Lifecycle
        appendLine("\t//region ==================== Lifecycle ====================")
        appendLine()
        appendLine("\toverride fun onCreate(savedInstanceState: Bundle?) {")
        appendLine("\t\tconfigureDI()")
        appendLine("\t\tsuper.onCreate(savedInstanceState)")
        appendLine("\t}")
        appendLine()
        appendLine("\toverride fun onCreateView(")
        appendLine("\t\tinflater: LayoutInflater,")
        appendLine("\t\tcontainer: ViewGroup?,")
        appendLine("\t\tsavedInstanceState: Bundle?")
        appendLine("\t): View? {")
        appendLine("\t\treturn inflater.inflate(R.layout.fragment_${screenNameSnake}, container, false)")
        appendLine("\t}")
        appendLine()
        appendLine("\toverride fun onViewCreated(view: View, savedInstanceState: Bundle?) {")
        appendLine("\t\tsuper.onViewCreated(view, savedInstanceState)")
        appendLine("\t\tinitUI()")
        appendLine("\t}")
        appendLine()
        appendLine("\t//endregion")
        appendLine()

        // UI handlers
        if (hasRecyclerView || hasTitledToolbar) {
            appendLine("\t//region ==================== UI handlers ====================")
            appendLine()
            if (hasRecyclerView) {
                appendLine("\tprivate val itemClickListener = object : ListItemClickListener {")
                appendLine("\t\toverride fun onListItemClicked(delegateViewModel: ListViewModel) {")
                appendLine("\t\t\tpresenter.onItemClicked(delegateViewModel)")
                appendLine("\t\t}")
                appendLine("\t}")
                if (hasTitledToolbar) appendLine()
            }
            if (hasTitledToolbar) {
                appendLine("\tprivate val btnBackClickListener = View.OnClickListener { presenter.onBackButtonClicked() }")
            }
            appendLine()
            appendLine("\t//endregion")
            appendLine()
        }

        // Contract.View
        appendLine("\t//region ==================== Contract.View ====================")
        appendLine()
        if (hasRecyclerView) {
            appendLine("\toverride fun showItemList(list: List<ListViewModel>) {")
            appendLine("\t\tadapter.swapItems(list)")
            appendLine("\t}")
            appendLine()
        }
        appendLine("\t//endregion")
        appendLine()

        // DI
        appendLine("\t//region ==================== DI ====================")
        appendLine()
        appendLine("\tprivate fun configureDI() {")
        if (needsParams) {
            appendLine("\t\tval params = getParams<${screenName}Params>()")
        }
        val moduleArgs = mutableListOf<String>()
        if (needsParams) moduleArgs += "params"
        if (hasRecyclerView) moduleArgs += "itemClickListener"
        appendLine("\t\tval component = getAppComponent().plus(${screenName}Module(${moduleArgs.joinToString(", ")}))")
        appendLine("\t\tcomponent.inject(this)")
        appendLine("\t}")
        appendLine()
        appendLine("\t@ProvidePresenter")
        appendLine("\tinternal fun providePresenter() = presenterProvider.get()")
        appendLine()
        appendLine("\t//endregion")
        appendLine()

        // UI
        appendLine("\t//region ==================== UI ====================")
        appendLine()
        appendLine("\tprivate fun initUI() = with(binding) {")
        if (hasTitledToolbar) {
            appendLine("\t\tsetupToolbar(")
            appendLine("\t\t\tbinding.root,")
            appendLine("\t\t\t\"$screenName\",")
            appendLine("\t\t\tUiKitR.drawable.ic_back_grey_32,")
            appendLine("\t\t\tbackButtonEnabled = true,")
            appendLine("\t\t\tbtnBackClickListener")
            appendLine("\t\t)")
        }
        if (hasRecyclerView) {
            appendLine("\t\trecyclerView.layoutManager = LinearLayoutManager(context, VERTICAL, false)")
            appendLine("\t\trecyclerView.adapter = adapter")
        }
        appendLine("\t}")
        appendLine()
        appendLine("\t//endregion")
        append("}")
    }

    private fun contractContent(): String = buildString {
        appendLine("package $packageFromFolder")
        appendLine()
        appendLine("import moxy.MvpView")
        appendLine("import moxy.viewstate.strategy.AddToEndSingleStrategy")
        appendLine("import moxy.viewstate.strategy.StateStrategyType")
        appendLine("import ru.may24.app.core.ui.fragment.base.BaseDisposablePresenter")
        if (hasRecyclerView) appendLine("import ru.may24.uikit.ui.adapter.ListViewModel")
        appendLine()
        appendLine("interface ${screenName}Contract {")
        appendLine()
        appendLine("\t@StateStrategyType(value = AddToEndSingleStrategy::class)")
        appendLine("\tinterface View : MvpView {")
        if (hasRecyclerView) {
            appendLine("\t\tfun showItemList(list: List<ListViewModel>)")
        }
        appendLine("\t}")
        appendLine()
        if (hasRecyclerView || hasTitledToolbar) {
            appendLine("\tabstract class Presenter : BaseDisposablePresenter<View>() {")
            if (hasRecyclerView) appendLine("\t\tabstract fun onItemClicked(viewModel: ListViewModel)")
            if (hasTitledToolbar) appendLine("\t\tabstract fun onBackButtonClicked()")
            appendLine("\t}")
        } else {
            appendLine("\tabstract class Presenter : BaseDisposablePresenter<View>()")
        }
        append("}")
    }

    private fun presenterContent(): String = buildString {
        appendLine("package $packageFromFolder")
        appendLine()
        appendLine("import com.github.terrakok.cicerone.Router")
        if (needsParams) appendLine("import $packageFromFolder.model.${screenName}Params")
        if (hasRecyclerView) appendLine("import ru.may24.uikit.ui.adapter.ListViewModel")
        appendLine("import javax.inject.Inject")
        if (hasRecyclerView) appendLine("import $packageFromFolder.mapper.${screenName}Mapper as Mapper")
        appendLine()
        appendLine("class ${screenName}Presenter @Inject constructor(")
        appendLine("\tprivate val router: Router,")
        if (needsParams) appendLine("\tprivate val params: ${screenName}Params,")
        if (hasRecyclerView) appendLine("\tprivate val mapper: Mapper,")
        appendLine(") : ${screenName}Contract.Presenter() {")
        appendLine()
        appendLine("\t//region ==================== MVP Presenter ====================")
        appendLine()
        appendLine("\t//endregion")
        appendLine()
        appendLine("\t//region ==================== ${screenName}Contract.Presenter ====================")
        appendLine()
        if (hasRecyclerView) {
            appendLine("\toverride fun onItemClicked(viewModel: ListViewModel) = Unit")
            appendLine()
        }
        if (hasTitledToolbar) {
            appendLine("\toverride fun onBackButtonClicked() = router.exit()")
            appendLine()
        }
        appendLine("\t//endregion")
        append("}")
    }

    private fun diContent(): String = buildString {
        appendLine("package $packageFromFolder")
        appendLine()
        appendLine("import dagger.Module")
        appendLine("import dagger.Provides")
        appendLine("import dagger.Subcomponent")
        if (needsParams) appendLine("import $packageFromFolder.model.${screenName}Params")
        if (hasRecyclerView) appendLine("import ru.may24.uikit.ui.adapter.listener.ListItemClickListener")
        appendLine("import $packageFromFolder.${screenName}Contract as Contract")
        appendLine("import $packageFromFolder.${screenName}Presenter as Presenter")
        if (hasRecyclerView) {
            appendLine("import $packageFromFolder.mapper.${screenName}Mapper as Mapper")
            appendLine("import $packageFromFolder.mapper.${screenName}MapperImpl as MapperImpl")
        }
        appendLine()
        appendLine("@Subcomponent(modules = [${screenName}Module::class])")
        appendLine("interface ${screenName}Component {")
        appendLine("\tfun inject(fragment: ${screenName}Fragment)")
        appendLine("}")
        appendLine()
        appendLine("@Module")

        val ctorLines = mutableListOf<String>()
        if (needsParams) ctorLines += "\tprivate val params: ${screenName}Params,"
        if (hasRecyclerView) ctorLines += "\tprivate val listItemClickListener: ListItemClickListener,"

        when (ctorLines.size) {
            0 -> appendLine("class ${screenName}Module {")
            1 -> appendLine("class ${screenName}Module(${ctorLines[0].trim().removeSuffix(",")}) {")
            else -> {
                appendLine("class ${screenName}Module(")
                ctorLines.forEach { appendLine(it) }
                appendLine(") {")
            }
        }
        appendLine()
        appendLine("\t@Provides")
        appendLine("\tfun presenter(presenter: Presenter): Contract.Presenter = presenter")
        if (needsParams) {
            appendLine()
            appendLine("\t@Provides")
            appendLine("\tfun provideParams() = params")
        }
        if (hasRecyclerView) {
            appendLine()
            appendLine("\t@Provides")
            appendLine("\tfun provideListItemClickListener() = listItemClickListener")
            appendLine()
            appendLine("\t@Provides")
            appendLine("\tfun provideMapper(impl: MapperImpl): Mapper = impl")
        }
        append("}")
    }

    private fun adapterContent(): String = buildString {
        appendLine("package $packageFromFolder.adapter")
        appendLine()
        appendLine("import ru.may24.uikit.ui.adapter.DiffAdapter")
        appendLine("import javax.inject.Inject")
        appendLine()
        appendLine("class ${screenName}Adapter @Inject constructor() : DiffAdapter() {")
        appendLine("\tinit {")
        appendLine("\t\tdelegatesManager")
        appendLine("\t}")
        append("}")
    }

    private fun mapperContent(): String = buildString {
        appendLine("package $packageFromFolder.mapper")
        appendLine()
        appendLine("import ru.may24.uikit.ui.adapter.ListViewModel")
        appendLine()
        appendLine("interface ${screenName}Mapper {")
        appendLine()
        appendLine("\tfun mapToUI(): List<ListViewModel>")
        append("}")
    }

    private fun mapperImplContent(): String = buildString {
        appendLine("package $packageFromFolder.mapper")
        appendLine()
        appendLine("import ru.may24.uikit.ui.adapter.ListViewModel")
        appendLine("import javax.inject.Inject")
        appendLine()
        appendLine("class ${screenName}MapperImpl @Inject constructor() : ${screenName}Mapper {")
        appendLine()
        appendLine("\toverride fun mapToUI(): List<ListViewModel> {")
        appendLine("\t\tval viewModels = mutableListOf<ListViewModel>()")
        appendLine("\t\treturn viewModels")
        appendLine("\t}")
        append("}")
    }

    private fun paramsContent(): String = buildString {
        appendLine("package $packageFromFolder.model")
        appendLine()
        appendLine("import android.os.Parcelable")
        appendLine("import kotlinx.parcelize.Parcelize")
        appendLine()
        appendLine("@Parcelize")
        appendLine("data class ${screenName}Params(")
        appendLine("\tval someId: String = \"\",")
        append(") : Parcelable")
    }

    private fun layoutContent(): String = buildString {
        appendLine("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
        appendLine("<androidx.constraintlayout.widget.ConstraintLayout")
        appendLine("    xmlns:android=\"http://schemas.android.com/apk/res/android\"")
        appendLine("    xmlns:app=\"http://schemas.android.com/apk/res-auto\"")
        appendLine("    android:layout_width=\"match_parent\"")
        appendLine("    android:layout_height=\"match_parent\">")
        appendLine()
        if (hasTitledToolbar) {
            appendLine("    <include")
            appendLine("        android:id=\"@+id/toolbar\"")
            appendLine("        layout=\"@layout/titled_toolbar\"")
            appendLine("        android:layout_width=\"match_parent\"")
            appendLine("        android:layout_height=\"wrap_content\"")
            appendLine("        app:layout_constraintTop_toTopOf=\"parent\" />")
            appendLine()
        }
        if (hasRecyclerView) {
            val rvTopConstraint = if (hasTitledToolbar) "@id/toolbar" else "parent"
            val rvTopAttr = if (hasTitledToolbar) "toBottomOf" else "toTopOf"
            appendLine("    <androidx.recyclerview.widget.RecyclerView")
            appendLine("        android:id=\"@+id/recyclerView\"")
            appendLine("        android:layout_width=\"match_parent\"")
            appendLine("        android:layout_height=\"0dp\"")
            appendLine("        app:layout_constraintTop_${rvTopAttr}=\"$rvTopConstraint\"")
            appendLine("        app:layout_constraintBottom_toBottomOf=\"parent\"")
            appendLine("        app:layout_constraintStart_toStartOf=\"parent\"")
            appendLine("        app:layout_constraintEnd_toEndOf=\"parent\" />")
            appendLine()
        }
        append("</androidx.constraintlayout.widget.ConstraintLayout>")
    }

    // ─── Screens.kt entry ──────────────────────────────────────────────────────

    private fun screensEntryContent(baseIndent: String): String = buildString {
        val returnType = if (isBottomSheet) "BottomSheetScreen" else "FragmentScreen"
        val paramArg = if (needsParams) "params: ${screenName}Params" else ""
        val callArg = if (needsParams) "params" else ""
        appendLine("$baseIndent\tfun $screenNameCamel($paramArg): $returnType {")
        appendLine("$baseIndent\t\treturn ${screenName}Fragment.getScreen($callArg)")
        append("$baseIndent\t}")
    }

    /** Сегменты пакета выбранной директории, без общих для всех экранов частей. */
    private fun parentPackageSegments(): List<String> {
        val boilerplate = setOf("ru", "may24", "app", "ui", "fragment")
        return parentPackage.split(".").filterNot { it in boilerplate || it.isBlank() }
    }

    /** Имя вложенного `object` внутри `Screens.kt`, совпадающее с сегментом пакета экрана. */
    private fun findMatchingFeatureObject(text: String): String? {
        val segments = parentPackageSegments()
        if (segments.isEmpty()) return null
        val objectRegex = Regex("(?m)^\\tobject (\\w+)\\s*\\{")
        return objectRegex.findAll(text)
            .map { it.groupValues[1] }
            .firstOrNull { objName -> segments.any { it.equals(objName, ignoreCase = true) } }
    }

    private fun insertScreensEntry(text: String): String {
        val targetObject = findMatchingFeatureObject(text)
        if (targetObject != null) {
            val inserted = insertIntoObject(text, targetObject)
            if (inserted != null) return inserted
        }
        // Фолбэк: вставляем прямо в тело `object Screens { ... }`, тем же способом,
        // каким и раньше добавлялись top-level записи вроде MaintenanceWork.
        return insertBeforeLastBrace(text, "\n" + screensEntryContent("") + "\n")
    }

    private fun insertIntoObject(text: String, objectName: String): String? {
        val openRegex = Regex("(?m)^\\tobject ${Regex.escape(objectName)}\\s*\\{")
        val openMatch = openRegex.find(text) ?: return null
        val searchStart = openMatch.range.last + 1
        val closeRegex = Regex("(?m)^\\t\\}$")
        val closeMatch = closeRegex.find(text, searchStart) ?: return null
        val insertPos = closeMatch.range.first
        val entry = "\n" + screensEntryContent("\t") + "\n"
        return text.substring(0, insertPos) + entry + text.substring(insertPos)
    }

    // ─── Text manipulation helpers ────────────────────────────────────────────

    private fun addImports(text: String, imports: List<String>): String {
        val newImports = imports.filter { !text.contains(it) }
        if (newImports.isEmpty()) return text
        val lines = text.lines().toMutableList()
        val lastImportIdx = lines.indexOfLast { it.startsWith("import ") }
        return if (lastImportIdx >= 0) {
            lines.addAll(lastImportIdx + 1, newImports)
            lines.joinToString("\n")
        } else {
            val pkgIdx = lines.indexOfFirst { it.startsWith("package ") }
            if (pkgIdx >= 0) {
                lines.add(pkgIdx + 1, "")
                lines.addAll(pkgIdx + 2, newImports)
                lines.joinToString("\n")
            } else {
                newImports.joinToString("\n") + "\n" + text
            }
        }
    }

    private fun insertBeforeLastBrace(text: String, content: String): String {
        val idx = text.lastIndexOf('}')
        return if (idx >= 0) text.substring(0, idx) + content + text.substring(idx)
        else text + content
    }
}
