package dev.gdguesser.webhookrelay.worker

import dev.gdguesser.webhookrelay.core.DeliveryBusyException
import dev.gdguesser.webhookrelay.core.RelayProperties
import org.apache.kafka.clients.admin.NewTopic
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.config.TopicBuilder
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.util.backoff.FixedBackOff

@SpringBootApplication(scanBasePackages = ["dev.gdguesser.webhookrelay"])
@EnableConfigurationProperties(RelayProperties::class)
@EnableScheduling
class RelayWorkerApplication

fun main(args: Array<String>) {
    SpringApplication.run(RelayWorkerApplication::class.java, *args)
}

@Configuration(proxyBeanMethods = false)
class KafkaConfiguration {
    @Bean
    fun deliveryTopic(properties: RelayProperties): NewTopic =
        TopicBuilder
            .name(properties.outbox.topic)
            .partitions(TOPIC_PARTITIONS)
            .replicas(1)
            .build()

    @Bean
    fun kafkaErrorHandler(): DefaultErrorHandler =
        DefaultErrorHandler(FixedBackOff(BUSY_RETRY_DELAY_MILLIS, Long.MAX_VALUE)).also {
            it.addRetryableExceptions(DeliveryBusyException::class.java)
        }

    companion object {
        private const val TOPIC_PARTITIONS = 6
        private const val BUSY_RETRY_DELAY_MILLIS = 1_000L
    }
}
