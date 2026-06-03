package org.starficz.combataitweaks.ui

import com.fs.starfarer.api.campaign.CoreUITabId
import com.fs.starfarer.api.combat.ShipVariantAPI
import com.fs.starfarer.api.ui.CutStyle
import com.fs.starfarer.api.ui.UIPanelAPI
import org.starficz.combataitweaks.ReflectionUtils.invoke
import org.starficz.staruiframework.*

class RefitButton : DynamicInjection {
    override val id = "CATRefitButton"

    override fun findTarget(): UIPanelAPI? {
        if (StarUIManager.currentCoreUITabID != CoreUITabId.REFIT) return null
        val refitTab = StarUIManager.currentCoreUITabPanel ?: return null
        val refitPanel = refitTab.invoke("getRefitPanel") as? UIPanelAPI ?: return null
        val statsAndHullmodsPanel = refitPanel.invoke("getModDisplay") as? UIPanelAPI ?: return null
        return statsAndHullmodsPanel.invoke("getMods") as? UIPanelAPI
    }

    override fun UIPanelAPI.injectUI() {
        val anchorTarget = this.children.lastOrNull() ?: return

        Button(
            style = CutStyle.ALL,
            font = Font.ORBITRON_20,
            width = anchorTarget.position.width,
            height = anchorTarget.position.height,
            text = "AI Config",
            anchor = Anchor.below.sibling(anchorTarget).matchingLeftEdge(3f)
        ) {
            onClick {
                ConfigDialog().show()
            }
        }
    }
}