package com.advisorsearch

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class AdvisorSearchApplication

fun main(args: Array<String>) {
    runApplication<AdvisorSearchApplication>(*args)
}
