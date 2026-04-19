package com.example.fypdeadreckoning.ui.debug

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.example.fypdeadreckoning.activity.debuggers.DirectionTestActivity
import com.example.fypdeadreckoning.activity.debuggers.GroundTruthTestActivity
import com.example.fypdeadreckoning.activity.debuggers.MovementTestActivity
import com.example.fypdeadreckoning.activity.debuggers.PeerAugmentationDummyUserActivity
import com.example.fypdeadreckoning.activity.debuggers.StepCountTestActivity
import com.example.fypdeadreckoning.activity.StrideLengthCalibrationActivity
import com.example.fypdeadreckoning.databinding.FragmentDebugBinding

class DebugFragment : Fragment() {

    private var _binding: FragmentDebugBinding? = null

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val debugViewModel =
            ViewModelProvider(this)[DebugViewModel::class.java]

        _binding = FragmentDebugBinding.inflate(inflater, container, false)
        val root: View = binding.root

        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.stepDebugButton.setOnClickListener {
            val intent = Intent(requireContext(), StepCountTestActivity::class.java)
            startActivity(intent)
        }

        binding.directionDebugButton.setOnClickListener {
            val intent = Intent(requireContext(), DirectionTestActivity::class.java)
            startActivity(intent)
        }

        binding.strideDebugButton.setOnClickListener {
            val intent = Intent(requireContext(), StrideLengthCalibrationActivity::class.java)
            startActivity(intent)
        }
        binding.movementDebugButton.setOnClickListener {
            val intent = Intent(requireContext(), MovementTestActivity::class.java)
            startActivity(intent)
        }
        binding.localcoordinateDebugButton.setOnClickListener {
            val intent = Intent(requireContext(), GroundTruthTestActivity::class.java)
            startActivity(intent)
        }
        binding.peerAugmentationDebugButton.setOnClickListener {
            val intent = Intent(requireContext(), PeerAugmentationDummyUserActivity::class.java)
            startActivity(intent)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}