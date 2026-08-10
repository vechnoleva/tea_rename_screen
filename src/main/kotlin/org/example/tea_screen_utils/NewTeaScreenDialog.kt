package org.example.tea_screen_utils

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.ButtonGroup
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JRadioButton

class NewTeaScreenDialog(project: Project) : DialogWrapper(project) {

    private val nameField = JBTextField(30)
    private val hasParamsCheck = JBCheckBox("Has params", false)
    private val hasRecyclerViewCheck = JBCheckBox("Has RecyclerView", false)
    private val isBottomSheetCheck = JBCheckBox("Is Bottom Sheet", false)

    private val noToolbarRadio = JRadioButton("No Toolbar", true)
    private val titledToolbarRadio = JRadioButton("Titled Toolbar", false)
    private val toolbarGroup = ButtonGroup().apply {
        add(noToolbarRadio)
        add(titledToolbarRadio)
    }

    val screenName: String get() = nameField.text.trim()
    val hasParams: Boolean get() = hasParamsCheck.isSelected
    val hasRecyclerView: Boolean get() = hasRecyclerViewCheck.isSelected
    val isBottomSheet: Boolean get() = isBottomSheetCheck.isSelected
    val hasTitledToolbar: Boolean get() = titledToolbarRadio.isSelected

    init {
        title = "New Tea Screen"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(GridBagLayout())
        var row = 0

        fun gbc(x: Int, weightX: Double = 0.0, gridWidth: Int = 1) = GridBagConstraints().apply {
            gridx = x; gridy = row
            gridwidth = gridWidth
            fill = GridBagConstraints.HORIZONTAL
            weightx = weightX
            insets = Insets(4, 8, 4, 8)
        }

        panel.add(JBLabel("Screen name (PascalCase):"), gbc(0))
        panel.add(nameField, gbc(1, 1.0))
        row++

        panel.add(hasParamsCheck, gbc(0, 1.0, 2))
        row++
        panel.add(hasRecyclerViewCheck, gbc(0, 1.0, 2))
        row++
        panel.add(isBottomSheetCheck, gbc(0, 1.0, 2))
        row++

        val toolbarLabel = JBLabel("Toolbar:")
        val toolbarPanel = JPanel(FlowLayout(FlowLayout.LEFT, 20, 0)).apply {
            add(noToolbarRadio)
            add(titledToolbarRadio)
        }
        panel.add(toolbarLabel, gbc(0))
        panel.add(toolbarPanel, gbc(1, 1.0))
        row++

        fun updateBottomSheetRelated() {
            val isBS = isBottomSheetCheck.isSelected
            toolbarLabel.isVisible = !isBS
            toolbarPanel.isVisible = !isBS
            if (isBS) noToolbarRadio.isSelected = true
        }
        isBottomSheetCheck.addActionListener { updateBottomSheetRelated() }

        row++
        panel.add(JBLabel("Created by Levan Davityan ©"), gbc(0))

        return panel
    }

    override fun doValidate(): ValidationInfo? {
        val name = nameField.text.trim()
        return when {
            name.isEmpty() -> ValidationInfo("Screen name cannot be empty.", nameField)
            !ScreenNameUtils.isValidPascalCase(name) ->
                ValidationInfo("Screen name must be PascalCase (starts with uppercase letter, letters and digits only).", nameField)
            else -> null
        }
    }

    override fun getPreferredFocusedComponent(): JComponent = nameField
}
