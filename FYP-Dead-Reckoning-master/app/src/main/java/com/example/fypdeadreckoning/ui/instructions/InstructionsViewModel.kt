package com.example.fypdeadreckoning.ui.instructions

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class InstructionsViewModel : ViewModel() {

    private val _text = MutableLiveData<String>().apply {
        value = """
            You will receive a notification when you are near a building. Check the Home page and press the Load Map button.
            
            Ensure you have Location/GPS services and Bluetooth enabled. The application uses these services to enhance your position estimates.
            
            A Map should appear from a nearby building. You will need to press the Start Tracking button for your location to be tracked. You must be at the given starting position, or overwrite your location with a long press.
            For best results, before you start moving, please:
            - Point your phone straight, parallel to the ground.
            - Ensure your phone's compass is calibrated. Methods will vary per device
            - Ideally face northwards when you begin tracking. This gives a more accurate starting heading.
            
            While tracking, ideally do not quickly change the position of your phone. Position updates are based on your steps. Walk at a steady, comfortable pace.
            
            If a map has multiple floors, you can navigate using the top control panel.
            
            When your movements are being tracked, you have the ability to update your position manually by long pressing a point on the Map. It is best to zoom in when doing this. If your uncertainty is too high, you may be encouraged to overwrite.
            
            A red circle will appear surrounding your position. Given your position may be inaccurate, this circle shows you a range where the application is very confident you are inside. 
            Manually updating your position will reduce the uncertainty range. This is reflected in the uncertainty bar at the bottom of the screen
            
            Your position will continue to be tracked if you view the Instructions page.
            
            When you are ready to stop tracking, press the Stop Tracking button.
            
            If you wish to place a pin on the map, enable pin mode using the top left button and tap the map. You can remove a pin while in pin mode by tapping an existing pin.
            
            If you wish to view analytics, view the Analytics Display using the info button in the top right.
            
            .
        """.trimIndent()
    }
    val text: LiveData<String> = _text
}