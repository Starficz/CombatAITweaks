package org.starficz.combataitweaks.ui

import com.fs.starfarer.api.ui.CutStyle
import com.fs.starfarer.api.ui.UIPanelAPI
import org.starficz.starui.Anchor
import org.starficz.starui.components.Button
import org.starficz.starui.inject
import org.starficz.starui.modifier.Modifier
import org.starficz.starui.modifier.anchor
import org.starficz.starui.Size
import org.starficz.starui.vanilla.ButtonFont
import org.starficz.starui.vanilla.children
import patchlib.api.context.AfterContext
import patchlib.api.match.ClassMatch
import patchlib.api.match.MethodMatch
import patchlib.api.match.MethodType
import patchlib.api.patch.After
import patchlib.api.patch.Patch

/**
 * The "AI Config" button under the refit screen's hullmod list, in the campaign and in mission refits.
 *
 * The list is vanilla's ModWidget (refitPanel.getModDisplay().getMods()). Its constructor adds all its children (the
 * heading, the list, "Add", and "Build in" in the campaign) and it never clears them: adding or removing a hullmod
 * resizes the inner list and packs. So one injection per widget, right after it is made, is enough.
 *
 * ModWidget is a readable engine name, not API: re-check it after a game update.
 * 0.98.5a-RC4: com.fs.starfarer.coreui.refit.ModWidget(Objectsuper, C).
 */
@Patch(target = ClassMatch(typeName = "com.fs.starfarer.coreui.refit.ModWidget"))
object RefitButton {
    private const val KEY = "combataitweaks:ai-config"

    @JvmStatic
    @After(target = MethodMatch(methodType = MethodType.CONSTRUCTOR))
    fun afterCreate(context: AfterContext) {
        val widget = context.self as? UIPanelAPI ?: return
        // the bottom button ("Build in", or "Add" where there is none): ours goes below it, at its size
        val anchorTarget = widget.children.lastOrNull() ?: return
        widget.inject(KEY, modifier = Modifier.anchor(Anchor.below.sibling(anchorTarget).matchingLeftEdge(3f))) {
            Button(
                text = "AI Config",
                size = Size.exact(anchorTarget.position.width, anchorTarget.position.height),
                font = ButtonFont.ORBITRON_20,
                cutStyle = CutStyle.ALL,
                onClick = { ConfigDialog().show() }
            )
        }
    }
}
