package org.example.tea_screen_utils

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.LangDataKeys
import com.intellij.openapi.project.DumbService

class NewTeaScreenAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val view = e.getData(LangDataKeys.IDE_VIEW)
        val dirs = view?.directories ?: emptyArray()
        e.presentation.isEnabledAndVisible = dirs.isNotEmpty() && e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        if (DumbService.isDumb(project)) {
            DumbService.getInstance(project)
                .showDumbModeNotification("New Tea Screen requires indexing to complete first.")
            return
        }
        val view = e.getData(LangDataKeys.IDE_VIEW) ?: return
        val dir = view.orChooseDirectory ?: return

        val dialog = NewTeaScreenDialog(project)
        if (!dialog.showAndGet()) return

        NewTeaScreenGenerator(
            project = project,
            selectedDir = dir,
            screenName = dialog.screenName,
            hasParams = dialog.hasParams,
            hasRecyclerView = dialog.hasRecyclerView,
            isBottomSheet = dialog.isBottomSheet,
            hasTitledToolbar = dialog.hasTitledToolbar
        ).generate()
    }
}
