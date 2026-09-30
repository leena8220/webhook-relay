package dev.gdguesser.webhookrelay.core

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RelayProperties::class)
class CoreConfiguration {
    @Bean
    fun systemClock(): Clock = Clock.systemUTC()
}
