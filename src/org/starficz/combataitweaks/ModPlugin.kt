package org.starficz.combataitweaks

import com.fs.starfarer.api.BaseModPlugin
import org.starficz.combataitweaks.ui.RefitButton
import org.starficz.staruiframework.*

class ModPlugin : BaseModPlugin()  {
    override fun onApplicationLoad() {
        StarUIManager.registerDynamicInjection(RefitButton())

    }
}