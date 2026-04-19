package com.example.fypdeadreckoning.ui.instructions

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.example.fypdeadreckoning.databinding.FragmentInstructionsBinding

class InstructionsFragment : Fragment() {

    private var _binding: FragmentInstructionsBinding? = null

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val instructionsViewModel =
            ViewModelProvider(this).get(InstructionsViewModel::class.java)

        _binding = FragmentInstructionsBinding.inflate(inflater, container, false)
        val root: View = binding.root

        val instructions: TextView = binding.textInstructions
        instructionsViewModel.text.observe(viewLifecycleOwner) {
            instructions.text = it
        }
        return root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}