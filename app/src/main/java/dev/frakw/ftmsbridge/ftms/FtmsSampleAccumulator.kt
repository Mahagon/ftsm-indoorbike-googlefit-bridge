package dev.frakw.ftmsbridge.ftms

import dev.frakw.ftmsbridge.model.IndoorBikeSample

internal class FtmsSampleAccumulator {
    private var previous: IndoorBikeSample? = null

    fun merge(sample: IndoorBikeSample): IndoorBikeSample {
        val old = previous
        return sample
            .copy(
                speedKph = sample.speedKph ?: old?.speedKph,
                cadenceRpm = sample.cadenceRpm ?: old?.cadenceRpm,
                powerWatts = sample.powerWatts ?: old?.powerWatts,
                totalDistanceMeters = sample.totalDistanceMeters ?: old?.totalDistanceMeters,
                elapsedTimeSeconds = sample.elapsedTimeSeconds ?: old?.elapsedTimeSeconds,
                totalEnergyKcal = sample.totalEnergyKcal ?: old?.totalEnergyKcal,
            ).also { previous = it }
    }

    fun reset() {
        previous = null
    }
}
