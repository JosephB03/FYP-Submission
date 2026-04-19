package com.example.fypdeadreckoning.activity.debuggers

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.fypdeadreckoning.R
import com.example.fypdeadreckoning.helpers.dataClasses.LatLon
import com.example.fypdeadreckoning.helpers.location.PeerAugmentationDebug

class PeerAugmentationDummyUserActivity: AppCompatActivity() {

    // UI
    private var startButton: Button? = null
    private var stopButton: Button? = null
    private var statusText: TextView? = null

    // Bluetooth
    private lateinit var peerAugmentation: PeerAugmentationDebug

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_peer_test)

        peerAugmentation = PeerAugmentationDebug(this)

        startButton = findViewById<View?>(R.id.startButton) as Button
        stopButton = findViewById<View?>(R.id.stopButton) as Button
        statusText = findViewById(R.id.statusText)

        peerAugmentation.listener = object : PeerAugmentationDebug.BLEAugmentationListener {
            override fun onPeerDiscovered(peer: PeerAugmentationDebug.PeerInfo) {
                runOnUiThread {
                    statusText?.text = "Peer discovered: id=${peer.deviceId}, " +
                            "debug=${peer.isDebug}, peers=${peerAugmentation.getPeerCount()}"
                }
            }
            override fun onPeerLost(deviceId: Short) {
                runOnUiThread {
                    statusText?.text = "Peer lost: id=$deviceId, peers=${peerAugmentation.getPeerCount()}"
                }
            }
            override fun onAugmentationApplied(
                originalPosition: LatLon, augmentedPosition: LatLon,
                trustedPeerCount: Int, averageDiscrepancyM: Double
            ) {
                // Debug device doesn't augment its own position
            }
        }

        startButton!!.setOnClickListener {
            peerAugmentation.start()
            statusText?.text = "Status: scanning for real user..."
        }
        stopButton!!.setOnClickListener {
            peerAugmentation.stop()
            statusText?.text = "Status: stopped"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        peerAugmentation.stop()
    }
}