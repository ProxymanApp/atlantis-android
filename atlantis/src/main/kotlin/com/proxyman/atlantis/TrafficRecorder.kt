package com.proxyman.atlantis

/**
 * Destination of the traffic captured by [AtlantisInterceptor]
 */
internal interface TrafficRecorder {
    fun sendPackage(trafficPackage: TrafficPackage)
}

internal object AtlantisRecorder : TrafficRecorder {
    override fun sendPackage(trafficPackage: TrafficPackage) = Atlantis.sendPackage(trafficPackage)
}
