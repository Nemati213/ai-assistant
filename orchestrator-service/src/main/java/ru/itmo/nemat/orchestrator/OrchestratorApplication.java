package ru.itmo.nemat.orchestrator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;
import ru.itmo.nemat.shared.kafka.KafkaRetryConfiguration;

@SpringBootApplication
@EnableScheduling
@Import(KafkaRetryConfiguration.class)
public class OrchestratorApplication {
    public static void main(String[] args) {
        SpringApplication.run(OrchestratorApplication.class, args);
    }
}
