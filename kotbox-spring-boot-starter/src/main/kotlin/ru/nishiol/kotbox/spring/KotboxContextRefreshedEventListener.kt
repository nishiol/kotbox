package ru.nishiol.kotbox.spring

import org.springframework.context.event.ContextRefreshedEvent
import org.springframework.context.event.EventListener
import ru.nishiol.kotbox.Kotbox

class KotboxContextRefreshedEventListener(
    private val kotbox: Kotbox,
    private val configurationProperties: KotboxConfigurationProperties
) {
    @EventListener(ContextRefreshedEvent::class)
    fun onContextRefreshed() {
        if (configurationProperties.autoStart) {
            kotbox.start()
        }
    }
}