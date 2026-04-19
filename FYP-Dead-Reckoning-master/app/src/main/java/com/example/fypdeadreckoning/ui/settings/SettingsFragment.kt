package com.example.fypdeadreckoning.ui.settings

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.fypdeadreckoning.databinding.FragmentSettingsBinding
import com.example.fypdeadreckoning.helpers.extra.PowerModeManager
import com.example.fypdeadreckoning.helpers.extra.SettingsManager
import kotlinx.coroutines.launch

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private data class PresetColor(val name: String, val color: Int)

    private val presetColors = listOf(
        PresetColor("Blue", Color.BLUE),
        PresetColor("Green", Color.GREEN),
        PresetColor("Red", Color.RED),
        PresetColor("Magenta", Color.MAGENTA),
        PresetColor("Yellow", Color.YELLOW),
        PresetColor("Cyan", Color.CYAN)
    )

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    @SuppressLint("SetTextI18n")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Power Mode
        binding.lowPowerModeSwitch.setOnCheckedChangeListener { _, isChecked ->
            val target = if (isChecked) PowerModeManager.PowerMode.LOW_POWER
                         else PowerModeManager.PowerMode.NORMAL
            PowerModeManager.setMode(target)
        }

        // Confidence Level display
        binding.confidenceLevelGroup.setOnCheckedChangeListener { _, checkedId ->
            val level = if (checkedId == binding.confidence95.id) 0.95 else 0.68
            SettingsManager.setConfidenceLevel(level)
        }

        // Show Uncertainty
        binding.showUncertaintySwitch.setOnCheckedChangeListener { _, isChecked ->
            SettingsManager.setShowUncertaintyRadius(isChecked)
        }

        // Color pickers
        binding.userDotColorButton.setOnClickListener {
            showColorPicker("User Dot Color") { color ->
                SettingsManager.setUserDotColor(color)
            }
        }

        binding.uncertaintyColorButton.setOnClickListener {
            showColorPicker("Uncertainty Circle Color") { color ->
                SettingsManager.setUncertaintyCircleColor(color)
            }
        }

        // Augmentation switches
        binding.gpsAugmentationSwitch.setOnCheckedChangeListener { _, isChecked ->
            SettingsManager.setGpsAugmentationEnabled(isChecked)
        }

        binding.bleAugmentationSwitch.setOnCheckedChangeListener { _, isChecked ->
            SettingsManager.setBleAugmentationEnabled(isChecked)
        }

        // Complementary Filter ratio
        binding.filterRatioSlider.addOnChangeListener { _, value, _ ->
            val magPct = (value * 100).toInt()
            val gyroPct = 100 - magPct
            binding.filterRatioLabel.text = "Complementary Filter — Mag: $magPct% / Gyro: $gyroPct%"
            SettingsManager.setComplementaryFilterRatio(value)
        }

        // Step counter mode (Linear Accel or Android)
        binding.androidStepCounterSwitch.setOnCheckedChangeListener { _, isChecked ->
            SettingsManager.setUseAndroidStepCounter(isChecked)
        }

        // Gyroscope Sensitivity
        binding.gyroSensitivitySlider.addOnChangeListener { _, value, _ ->
            binding.gyroSensitivityLabel.text = "Gyroscope Sensitivity: ${"%.2f".format(value)}"
            SettingsManager.setGyroSensitivity(value)
        }

        // Exponential Moving Average (EMA) Alpha
        binding.emaAlphaSlider.addOnChangeListener { _, value, _ ->
            binding.emaAlphaLabel.text = "Step Counter EMA Alpha: ${"%.2f".format(value)}"
            SettingsManager.setStepCounterAlpha(value.toDouble())
        }

        // GPS Nudge Factor
        binding.gpsNudgeSlider.addOnChangeListener { _, value, _ ->
            binding.gpsNudgeLabel.text = "GPS Nudge Factor: ${"%.2f".format(value)}"
            SettingsManager.setGpsNudgeFactor(value)
        }

        // BLE Nudge Factor
        binding.bleNudgeSlider.addOnChangeListener { _, value, _ ->
            binding.bleNudgeLabel.text = "BLE Nudge Factor: ${"%.2f".format(value)}"
            SettingsManager.setBleNudgeFactor(value)
        }

        // Observe flows to sync the UI
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    PowerModeManager.mode.collect { mode ->
                        binding.lowPowerModeSwitch.isChecked =
                            mode == PowerModeManager.PowerMode.LOW_POWER
                    }
                }
                launch {
                    SettingsManager.userDotColor.collect { color ->
                        binding.userDotColorPreview.setBackgroundColor(color)
                    }
                }
                launch {
                    SettingsManager.uncertaintyCircleColor.collect { color ->
                        binding.uncertaintyColorPreview.setBackgroundColor(color)
                    }
                }
            }
        }
    }

    private fun showColorPicker(title: String, onColorSelected: (Int) -> Unit) {
        val names = presetColors.map { it.name }.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setItems(names) { _, which ->
                onColorSelected(presetColors[which].color)
            }
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}