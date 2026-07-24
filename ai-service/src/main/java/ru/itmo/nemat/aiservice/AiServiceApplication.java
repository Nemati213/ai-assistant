package ru.itmo.nemat.aiservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;
import ru.itmo.nemat.shared.kafka.KafkaRetryConfiguration;

@SpringBootApplication
@EnableScheduling
@Import(KafkaRetryConfiguration.class)
public class AiServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(AiServiceApplication.class, args);
    }
}
