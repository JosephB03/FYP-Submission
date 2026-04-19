package com.example.fypdeadreckoning.helpers.dialog

import android.app.AlertDialog
import android.app.Dialog
import android.os.Bundle
import androidx.fragment.app.DialogFragment
import com.example.fypdeadreckoning.interfaces.OnPreferredStepCounterListener


class StepCalibrationDialogFragment: DialogFragment() {
    private var onPreferredStepCounterListener: OnPreferredStepCounterListener? = null
    private lateinit var stepList: Array<java.lang.String?>

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val builder = AlertDialog.Builder(requireContext())
        builder.setTitle("Pick the sensitivity that best matches your step count:")
            .setItems(stepList
            ) { dialog, which -> onPreferredStepCounterListener?.onPreferredStepCounter(which) }

        return builder.create()
    }

    fun setOnPreferredStepCounterListener(onPreferredStepCounterListener: OnPreferredStepCounterListener) {
        this.onPreferredStepCounterListener = onPreferredStepCounterListener
    }

    fun setStepList(stepList: Array<java.lang.String?>) { this.stepList = stepList }
}