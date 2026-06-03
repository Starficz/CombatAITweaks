package org.starficz.combataitweaks.ui

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.ui.Alignment
import com.fs.starfarer.api.ui.TooltipMakerAPI
import com.fs.starfarer.api.ui.UIPanelAPI
import com.fs.starfarer.loading.specs.HullVariantSpec
import org.lwjgl.input.Keyboard
import org.lwjgl.opengl.GL11
import org.starficz.combataitweaks.ReflectionUtils.invoke
import org.starficz.combataitweaks.CombatAIConfig
import org.starficz.staruiframework.*
import java.awt.Color
import kotlin.math.roundToInt

class ConfigDialog() {
    companion object {
        const val ENABLE_CAT_AI_TAG = "cat_custom_ai_enabled"
    }

    fun show() {
        val refitTab = StarUIManager.currentCoreUITabPanel!!
        val refitPanel = refitTab.invoke("getRefitPanel") as UIPanelAPI
        val shipDisplay = refitPanel.invoke("getShipDisplay") as UIPanelAPI
        val baseVariant = shipDisplay.invoke("getCurrentVariant") as HullVariantSpec

        val coreUI = StarUIManager.coreUI ?: return
        val screenWidth = Global.getSettings().screenWidth
        val screenHeight = Global.getSettings().screenHeight


        // 1. Extract config and initialize the State Tracker
        val existingTag = baseVariant.tags.find { it.startsWith(CombatAIConfig.TAG_PREFIX) }
        val currentConfig = if (existingTag != null) {
            CombatAIConfig.fromJsonString(existingTag.removePrefix(CombatAIConfig.TAG_PREFIX))
        } else {
            CombatAIConfig()
        }

        val editorState = ConfigUIState(currentConfig, baseVariant.hasTag(ENABLE_CAT_AI_TAG))

        // 2. Build UI
        coreUI.CustomPanel(screenWidth, screenHeight, Anchor.inside.center.ofParent()) {
            val fullScreenPanel = this

            Plugin {
                consumeEvents = true // Block underlying clicks
                renderBelow { alphaMult ->
                    GL11.glDisable(GL11.GL_TEXTURE_2D)
                    org.lazywizard.lazylib.opengl.ColorUtils.glColor(Color.BLACK, alphaMult * 0.75f, false)
                    GL11.glRectf(0f, 0f, screenWidth, screenHeight)
                    GL11.glEnable(GL11.GL_TEXTURE_2D)
                }
                advance {
                    editorState.sanitizeInputs()
                }
            }
            FramedPanel(
                width = 620f,
                height = 700f,
                anchor = Anchor.inside.center.ofParent()
            ) {

                VerticalStackLayout(
                    anchor = Anchor.inside.topCenter.ofParent(yMargin = 20f),
                    spacing = 10f,
                    alignment = Alignment.TMID
                ) {

                    // --- HEADER ---
                    Text("Combat AI Tweaks Configuration", Font.ORBITRON_24)
                    Spacer(height = 5f)
                    Divider(580f)
                    Spacer(height = 5f)

                    // --- DESCRIPTIONS ---
                    VerticalStackLayout(spacing = 6f, alignment = Alignment.TL) {
                        val descColor = Color.LIGHT_GRAY
                        Text("This AI uses predictive timelines to anticipate incoming damage and flux before it happens. It constantly simulates two futures: holding its ground, and retreating to a safe point.", widthOverride = 580f, color = descColor)
                        Text("Venting is determined by simulating the exact \"Vulnerability Window\" (Time to Vent + Shield Raise Time + Buffer) and calculating the exact armor/hull damage the ship will take during that window.", widthOverride = 580f, color = descColor)
                        Text("AI Behaviors (Advance, Backoff, Disengage) are driven by the ship's predicted future flux, not just its current flux. This allows the ship to aggressively dive if it knows the enemy cannot generate enough threat, or preemptively back off before taking lethal burst damage.", widthOverride = 580f, color = descColor)
                    }

                    if (baseVariant.hullSpec.sourceMod?.id == "SCY") {
                        FramedPanel(300f, 30f){
                            Text("SCY Ships must use the Custom Combat AI", anchor = Anchor.inside.center.ofParent())
                        }
                    } else if (baseVariant.hullSpec.isPhase) {
                        FramedPanel(320f, 30f){
                            Text("Phase Ships cannot use the Custom Combat AI", anchor = Anchor.inside.center.ofParent())
                        }
                    }
                    else AreaCheckbox(300f, 30f, "Enable Custom Combat AI for this ship", bind = editorState.isEnabled)

                    // --- FORM VARIABLES ---
                    fun UIPanelAPI.addRow(label: String, state: UIState<String>, suffix: String, tooltipText: String) {
                        HorizontalStackLayout(spacing = 0f, alignment = Alignment.LMID) {
                            Text(label, widthOverride = 270f)
                            TextField(45f, 25f, Font.INSIGNIA_15, bind = state)
                            Text(suffix, widthOverride = 45f)

                            Tooltip(300f, TooltipMakerAPI.TooltipLocation.RIGHT) {
                                addPara(tooltipText, 0f)
                            }
                        }
                    }

                    VerticalStackLayout(spacing = 3f, alignment = Alignment.TMID) {
                        addRow("Vent Prediction Buffer", editorState.bufferTime, "s  ",
                            "Adds extra time to the vent vulnerability prediction. Higher values make the AI more cautious.")
                        addRow("Effective Shield Arc", editorState.safeShieldArc, "deg",
                            "Degrees of shield arc needed to catch immediate threats. A smaller arc means the AI assumes it is safe sooner while the shield is still unfolding.")
                        addRow("Minimum Vent Flux", editorState.minVentLevel, "%  ",
                            "The lowest flux percentage required for the AI to consider venting.")
                        addRow("Acceptable Vent Armor Dmg", editorState.maxArmorDmg, "%  ",
                            "Maximum predicted armor damage (as a % of total armor rating) the ship will tolerate taking during venting.")
                        addRow("Acceptable Vent Hull Dmg", editorState.maxHullDmg, "%  ",
                            "Maximum predicted hull damage (as a % of max hitpoints) the ship will tolerate taking during venting.")
                        addRow("Critical Hull Threshold", editorState.stopVenting, "%  ",
                            "If current hull falls below this %, the AI will refuse to take any hull damage to vent.")
                        addRow("Mobility Engage Threshold", editorState.maxFluxHarass, "%  ",
                            "The AI will only use mobility systems to aggressively rush enemies if its predicted future flux while holding its ground remains below this percentage.")
                        addRow("Aggressive Advance Threshold", editorState.forceAdvanceFlux, "%  ",
                            "The AI will advance as long as its predicted total flux while holding its ground stays below this percentage.")
                        addRow("Backoff Hard Flux Threshold", editorState.backoffHardflux, "%  ",
                            "The AI will backoff if its predicted hard flux while retreating exceeds this percentage.")
                        addRow("Backoff Total Flux Threshold", editorState.backoffFlux, "%  ",
                            "The AI will backoff if its predicted total flux while retreating exceeds this percentage.")
                        addRow("Full Disengage Threshold", editorState.disengageFlux, "%  ",
                            "The AI will disengage to vent if its predicted total flux while retreating exceeds this percentage.")
                        addRow("Disengage Recovery Threshold", editorState.reengageFlux, "%  ",
                            "If the ship was forced to disengage, it will not resume normal combat until its current flux drops below this percentage.")
                    }

                    Divider(580f)

                    // --- FOOTER (BUTTONS) ---
                    HorizontalStackLayout(spacing = 40f, alignment = Alignment.LMID, yMargin = 10f) {
                        Button(120f, 25f, "Reset", align = Alignment.MID, shortcut = Keyboard.KEY_R) {
                            onClick {
                                editorState.resetToDefaults()
                            }
                        }

                        Button(120f, 25f, "Save", align = Alignment.MID, shortcut = Keyboard.KEY_S) {
                            onClick {
                                val newConfig = editorState.buildConfig()
                                val tagsToDrop = baseVariant.tags.filter { it.startsWith(CombatAIConfig.TAG_PREFIX) || it == ENABLE_CAT_AI_TAG }
                                tagsToDrop.forEach { baseVariant.tags.remove(it) }
                                baseVariant.addTag(CombatAIConfig.TAG_PREFIX + newConfig.toJsonString())
                                if(editorState.isEnabled.value || baseVariant.hullSpec.sourceMod?.id == "SCY")
                                    baseVariant.addTag(ENABLE_CAT_AI_TAG)
                                if(baseVariant.hullSpec.isPhase)
                                    baseVariant.removeTag(ENABLE_CAT_AI_TAG)

                                refitPanel.invoke("syncWithCurrentVariant")
                                fullScreenPanel.parent?.removeComponent(fullScreenPanel)
                            }
                        }

                        Button(120f, 25f, "Cancel", align = Alignment.MID, shortcut = Keyboard.KEY_ESCAPE) {
                            onClick {
                                fullScreenPanel.parent?.removeComponent(fullScreenPanel)
                            }
                        }
                    }
                }
            }
        }
    }
}


class ConfigUIState(private val currentConfig: CombatAIConfig, isAiEnabled: Boolean) {
    val isEnabled = UIState(isAiEnabled)
    // Formatters
    private fun toPctStr(f: Float) = (f * 100).roundToInt().toString()

    private fun parsePct(s: String, default: Float): Float {
        val clean = s.filter { it.isDigit() || it == '.' }
        return clean.toFloatOrNull()?.div(100f) ?: default
    }

    private fun parseFlt(s: String, default: Float): Float {
        return s.filter { it.isDigit() || it == '.' }.toFloatOrNull() ?: default
    }

    // UI States (holding only raw numbers)
    val bufferTime = UIState(currentConfig.bufferTime.toString())
    val safeShieldArc = UIState(currentConfig.safeShieldArc.toInt().toString())
    val minVentLevel = UIState(toPctStr(currentConfig.minVentLevel))
    val maxArmorDmg = UIState(toPctStr(currentConfig.maxArmorDamageLevelToVent))
    val maxHullDmg = UIState(toPctStr(currentConfig.maxHullDamageLevelToVent))
    val stopVenting = UIState(toPctStr(currentConfig.stopVentingBelowHullLevel))
    val maxFluxHarass = UIState(toPctStr(currentConfig.maxFluxLevelRaiseToHarass))
    val reengageFlux = UIState(toPctStr(currentConfig.reengageIfFluxDropsBelowLevel))
    val forceAdvanceFlux = UIState(toPctStr(currentConfig.forceAdvanceIfFluxBelowLevel))
    val disengageFlux = UIState(toPctStr(currentConfig.disengageIfFluxAboveLevel))
    val backoffFlux = UIState(toPctStr(currentConfig.backoffIfFluxAboveLevel))
    val backoffHardflux = UIState(toPctStr(currentConfig.backoffIfHardfluxAboveLevel))

    private val allStates = listOf(
        bufferTime, safeShieldArc, minVentLevel, maxArmorDmg, maxHullDmg, stopVenting,
        maxFluxHarass, reengageFlux, forceAdvanceFlux, disengageFlux, backoffFlux, backoffHardflux
    )

    // Strips out any characters that aren't digits or decimals
    fun sanitizeInputs() {
        allStates.forEach { state ->
            val clean = state.value.filter { it.isDigit() || it == '.' }
            if (state.value != clean) {
                state.value = clean
            }
        }
    }

    // Resets all UI fields to a fresh default configuration
    fun resetToDefaults() {
        val defaults = CombatAIConfig()
        bufferTime.value = defaults.bufferTime.toString()
        safeShieldArc.value = defaults.safeShieldArc.toInt().toString()
        minVentLevel.value = toPctStr(defaults.minVentLevel)
        maxArmorDmg.value = toPctStr(defaults.maxArmorDamageLevelToVent)
        maxHullDmg.value = toPctStr(defaults.maxHullDamageLevelToVent)
        stopVenting.value = toPctStr(defaults.stopVentingBelowHullLevel)
        maxFluxHarass.value = toPctStr(defaults.maxFluxLevelRaiseToHarass)
        reengageFlux.value = toPctStr(defaults.reengageIfFluxDropsBelowLevel)
        forceAdvanceFlux.value = toPctStr(defaults.forceAdvanceIfFluxBelowLevel)
        disengageFlux.value = toPctStr(defaults.disengageIfFluxAboveLevel)
        backoffFlux.value = toPctStr(defaults.backoffIfFluxAboveLevel)
        backoffHardflux.value = toPctStr(defaults.backoffIfHardfluxAboveLevel)
    }

    // Compiles everything back into a final Config
    fun buildConfig(): CombatAIConfig {
        return CombatAIConfig(
            bufferTime = parseFlt(bufferTime.value, currentConfig.bufferTime),
            safeShieldArc = parseFlt(safeShieldArc.value, currentConfig.safeShieldArc),
            minVentLevel = parsePct(minVentLevel.value, currentConfig.minVentLevel),
            maxArmorDamageLevelToVent = parsePct(maxArmorDmg.value, currentConfig.maxArmorDamageLevelToVent),
            maxHullDamageLevelToVent = parsePct(maxHullDmg.value, currentConfig.maxHullDamageLevelToVent),
            stopVentingBelowHullLevel = parsePct(stopVenting.value, currentConfig.stopVentingBelowHullLevel),
            maxFluxLevelRaiseToHarass = parsePct(maxFluxHarass.value, currentConfig.maxFluxLevelRaiseToHarass),
            reengageIfFluxDropsBelowLevel = parsePct(reengageFlux.value, currentConfig.reengageIfFluxDropsBelowLevel),
            forceAdvanceIfFluxBelowLevel = parsePct(forceAdvanceFlux.value, currentConfig.forceAdvanceIfFluxBelowLevel),
            disengageIfFluxAboveLevel = parsePct(disengageFlux.value, currentConfig.disengageIfFluxAboveLevel),
            backoffIfFluxAboveLevel = parsePct(backoffFlux.value, currentConfig.backoffIfFluxAboveLevel),
            backoffIfHardfluxAboveLevel = parsePct(backoffHardflux.value, currentConfig.backoffIfHardfluxAboveLevel)
        )
    }
}